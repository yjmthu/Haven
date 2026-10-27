package sh.haven.app

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import sh.haven.app.navigation.HavenNavHost
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.fido.FidoAuthenticator
import sh.haven.core.security.BiometricAuthenticator
import sh.haven.core.ssh.SshConnectionService
import sh.haven.core.ui.KeyEventInterceptor
import sh.haven.core.ui.theme.HavenTheme
import javax.inject.Inject
import sh.haven.core.redact.LogRedact

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var preferencesRepository: UserPreferencesRepository
    @Inject lateinit var biometricAuthenticator: BiometricAuthenticator
    // Eagerly injected to trigger one-time password encryption migration
    @Inject lateinit var connectionRepository: ConnectionRepository
    @Inject lateinit var sshKeyRepository: sh.haven.core.data.repository.SshKeyRepository
    // Published to FidoAuthenticator in onResume so NFC reader mode can
    // be enabled during FIDO2 SSH assertions. Without this, Nitrokey /
    // SoloKey / YubiKey-over-NFC flows never saw a Tag (#15).
    @Inject lateinit var fidoAuthenticator: FidoAuthenticator
    // Cross-tab navigation verbs: HavenNavHost collects from this so an
    // MCP `navigate_sftp_browser` switches the pager to the right tab.
    @Inject lateinit var agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus
    // App-global user-facing messages (e.g. shell-closed connect errors);
    // HavenNavHost shows these in a root snackbar over every screen (#215 follow-up).
    @Inject lateinit var userMessageBus: sh.haven.core.data.message.UserMessageBus
    // Cert renewal request bus: notifications fire haven://renew-cert/<id>
    // intents which land here and we re-publish via the existing
    // AgentUiCommandBus so HavenNavHost flips to Keys and KeysViewModel
    // picks up the regenerate request. (#133 phase 2b)
    // Keystore biometric gate: a fetch on a BIOMETRIC_PROTECTED entry
    // queues a request here, this Activity collects, runs
    // BiometricPrompt, and posts the decision back. Foreground tracking
    // mirrors AgentConsentManager (fail-closed when backgrounded).
    @Inject lateinit var biometricGate: sh.haven.core.data.keystore.BiometricGate
    // Workspace launcher (singleton) — invoked when a launcher
    // long-press shortcut routes the workspace id through onCreate /
    // onNewIntent.
    @Inject lateinit var workspaceLauncher: sh.haven.app.workspace.WorkspaceLauncher

    @Inject lateinit var taskerOverlayCoordinator: sh.haven.app.tasker.TaskerOverlayCoordinator

    /**
     * A pending Tasker overlay run (profileId → command). Set by
     * [handleTaskerRunIntent] and consumed by a LaunchedEffect inside
     * [setContent] — deferring past composition so the always-composed
     * ConnectionsViewModel is subscribed to the (replay=0) command bus before
     * the coordinator emits ConnectProfile, otherwise the connect is dropped.
     */
    private val pendingTaskerRun = androidx.compose.runtime.mutableStateOf<Pair<String, String>?>(null)

    private fun exitIfDisconnected() {
        // Only the service's own auto-launch (it carries
        // EXTRA_EXIT_AFTER_DISCONNECT_ALL) may finish the activity. When that
        // launch is blocked by background-activity-launch restrictions the
        // flag would otherwise survive to the next manual open from the
        // launcher, which then bounced straight back out (#640) — clear the
        // flag on a plain open instead of finishing.
        val exitExtra = intent?.getBooleanExtra(
            SshConnectionService.EXTRA_EXIT_AFTER_DISCONNECT_ALL, false) ?: false
        if (SshConnectionService.shouldFinishOnResume(
                SshConnectionService.disconnectedAll, exitExtra)) {
            Log.d("MainActivity", "Disconnect All detected — exiting")
            SshConnectionService.clearDisconnectedAll()
            finishAndRemoveTask()
        } else if (SshConnectionService.disconnectedAll) {
            SshConnectionService.clearDisconnectedAll()
        }
    }

    override fun onResume() {
        super.onResume()
        exitIfDisconnected()
        fidoAuthenticator.setActiveActivity(this)
        biometricGate.setForegroundActive(true)
    }

    override fun onPause() {
        fidoAuthenticator.clearActiveActivity(this)
        biometricGate.setForegroundActive(false)
        super.onPause()
    }

    /** Last deep-link URI handled, with its elapsed-realtime stamp. See [isFreshDeepLink]. */
    private var lastDeepLink: Pair<String, Long>? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        exitIfDisconnected()
        handleWorkspaceShortcut(intent)
        handleConnectionShortcut(intent)
        handleRenewCertDeepLink(intent)
        handleConnectDeepLink(intent)
        handleStandardSchemeDeepLink(intent)
        handleTaskerRunIntent(intent)
        handleBackupShortcutIntent(intent)
    }

    /**
     * Tap-to-watch from the Tasker plugin (#367 Phase 2): the fire receiver
     * can't foreground us itself (BAL), so it posts a notification whose tap
     * lands here. Now that we're foreground the coordinator can connect the
     * profile into a visible terminal and stream the command in. Extras are
     * cleared once consumed so a config change doesn't re-fire it.
     */
    private fun handleTaskerRunIntent(intent: Intent?) {
        val profileId = intent?.getStringExtra(
            sh.haven.app.tasker.TaskerFireReceiver.EXTRA_RUN_PROFILE_ID,
        ) ?: return
        val command = intent.getStringExtra(
            sh.haven.app.tasker.TaskerFireReceiver.EXTRA_RUN_COMMAND,
        ) ?: return
        intent.removeExtra(sh.haven.app.tasker.TaskerFireReceiver.EXTRA_RUN_PROFILE_ID)
        intent.removeExtra(sh.haven.app.tasker.TaskerFireReceiver.EXTRA_RUN_COMMAND)
        pendingTaskerRun.value = profileId to command
    }


    /**
     * If [intent] carries an app-window launch action (home-screen pinned
     * shortcut tap), start the cage and present it. Cold-start safe — the
     * launcher queues straight onto the retained presentation queue, so a
     * tap that cold-starts Haven still opens the app. The extra is cleared
     * once consumed so a configuration change doesn't relaunch.
     */
    /**
     * Parse `haven://renew-cert/<keyId>` deep links posted by
     * [sh.haven.core.stepca.RenewalNotifier] and re-publish onto the
     * existing UI command bus. The notifier never imports the bus
     * directly — that would couple core/stepca to core/data — so the
     * Activity bridges the two.
     */
    /**
     * True the first time a given deep-link URI is seen, false for a repeat
     * within [DEEP_LINK_DEDUPE_MS].
     *
     * `intent.data = null` below already stops the *same* Intent being replayed
     * across a configuration change. It cannot help when the same URI arrives as
     * two *different* Intents, which is exactly what [DeepLinkActivity] produces:
     * it hands the URI straight to a live MainActivity (the ColorOS path, where
     * onNewIntent is not reliably delivered) and *also* forwards an Intent that
     * reaches onNewIntent wherever the platform behaves. On a device where both
     * arrive, one tap would otherwise emit two connect commands.
     *
     * Deduping here rather than dropping one of the two delivery paths keeps the
     * ColorOS workaround intact: whichever arrives first wins, the other is
     * ignored, and neither has to be trusted to be the one that works.
     */
    private fun isFreshDeepLink(data: android.net.Uri): Boolean {
        val key = data.toString()
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastDeepLink
        if (previous != null && previous.first == key &&
            now - previous.second < DEEP_LINK_DEDUPE_MS
        ) {
            Log.d("MainActivity", "ignoring duplicate deep link within dedupe window")
            return false
        }
        lastDeepLink = key to now
        return true
    }

    private fun handleRenewCertDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "haven" || data.host != "renew-cert") return
        if (!isFreshDeepLink(data)) return
        val keyId = data.lastPathSegment ?: return
        Log.d("MainActivity", "renew-cert deep link for key $keyId")
        agentUiCommandBus.emit(
            sh.haven.core.data.agent.AgentUiCommand.RegenerateStepCaCert(keyId),
        )
        intent.data = null
    }

    /**
     * Parse a `haven://connect?host=…&user=…&port=…&transport=…&session=…`
     * deep link (#305) and re-publish a connect/prefill command onto the UI
     * bus. A host matching exactly one saved profile connects (routed through
     * a confirm in ConnectionsViewModel, since a BROWSABLE link can be fired
     * by a web page); no/ambiguous match opens the New-Connection editor
     * pre-filled. The saved-profile lookup is async (Room), which also defers
     * the emit past `setContent` so the always-composed ConnectionsViewModel
     * is collecting by the time it lands.
     */
    private fun handleConnectDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "haven" || data.host != "connect") return
        val params = ConnectDeepLink.parse { data.getQueryParameter(it) } ?: return
        if (!isFreshDeepLink(data)) return
        intent.data = null
        Log.d("MainActivity", "connect deep link host=${LogRedact.of(params.host)} transport=${params.transport}")
        MainScope().launch {
            agentUiCommandBus.emit(ConnectDeepLink.resolve(connectionRepository.getAll(), params))
        }
    }

    /**
     * Standard-scheme links (#751): `ssh://[user@]host[:port]`, `rdp://…`,
     * `vnc://…` — the form mail clients, desktop terminals and QR codes emit.
     * Same resolve path as haven://connect: a single matching saved profile
     * connects after the confirm sheet; anything else opens the
     * New-Connection editor pre-filled (a link carries no credentials, so it
     * never silently creates a profile).
     */
    private fun handleStandardSchemeDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        val scheme = data.scheme?.lowercase() ?: return
        if (scheme != "ssh" && scheme != "rdp" && scheme != "vnc") return
        val params = ConnectDeepLink.parseStandardScheme(scheme, data.userInfo, data.host, data.port.takeIf { it > 0 })
            ?: return
        if (!isFreshDeepLink(data)) return
        intent.data = null
        Log.d("MainActivity", "standard-scheme deep link host=${LogRedact.of(params.host)} transport=${params.transport}")
        MainScope().launch {
            agentUiCommandBus.emit(ConnectDeepLink.resolve(connectionRepository.getAll(), params))
        }
    }

    /**
     * If [intent] carries a workspace launch action (long-press shortcut
     * tap, or a `compose_workspace` MCP call routed through here in a
     * future patch), kick off the launcher. Idempotent — extras are
     * cleared once consumed so a configuration change doesn't replay.
     */
    /**
     * Connection launcher shortcut tap (#699): dial the profile directly
     * through the same command an MCP `connect_profile` uses — the intent
     * is app-issued (no BROWSABLE action), so unlike the haven://connect
     * deep link there is no drive-by confirm sheet. Extras are cleared once
     * consumed so a configuration change doesn't replay.
     */
    private fun handleConnectionShortcut(intent: Intent?) {
        if (intent?.action != sh.haven.app.launcher.LauncherShortcutManager.ACTION_LAUNCH_CONNECTION) return
        val profileId = intent.getStringExtra(
            sh.haven.app.launcher.LauncherShortcutManager.EXTRA_CONNECTION_ID,
        ) ?: return
        intent.removeExtra(sh.haven.app.launcher.LauncherShortcutManager.EXTRA_CONNECTION_ID)
        Log.d("MainActivity", "connecting profile $profileId from launcher shortcut")
        MainScope().launch {
            agentUiCommandBus.emit(sh.haven.core.data.agent.AgentUiCommand.ConnectProfile(profileId))
        }
    }

    private fun handleWorkspaceShortcut(intent: Intent?) {
        if (intent?.action != sh.haven.app.launcher.LauncherShortcutManager.ACTION_LAUNCH_WORKSPACE) return
        val workspaceId = intent.getStringExtra(
            sh.haven.app.launcher.LauncherShortcutManager.EXTRA_WORKSPACE_ID,
        ) ?: return
        intent.removeExtra(sh.haven.app.launcher.LauncherShortcutManager.EXTRA_WORKSPACE_ID)
        Log.d("MainActivity", "launching workspace $workspaceId from shortcut")
        MainScope().launch {
            workspaceLauncher.launch(workspaceId)
        }
    }

    private fun handleBackupShortcutIntent(intent: Intent?) {
        if (intent?.action != sh.haven.app.backup.PullBackupShortcutActivity.ACTION_SHOW_BACKUP_PASSWORD_DIALOG) return
        intent.action = null
        agentUiCommandBus.emit(sh.haven.core.data.agent.AgentUiCommand.OpenBackupPasswordDialog)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyEventInterceptor.handler?.let { interceptor ->
            if (interceptor(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        activeInstanceRef = java.lang.ref.WeakReference(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleWorkspaceShortcut(intent)
        handleConnectionShortcut(intent)
        handleRenewCertDeepLink(intent)
        handleConnectDeepLink(intent)
        handleStandardSchemeDeepLink(intent)
        handleTaskerRunIntent(intent)
        handleBackupShortcutIntent(intent)
        setContent {
            // Tasker overlay run (#367): deferred here so the ConnectionsViewModel
            // is subscribed to the command bus before the coordinator connects.
            val pendingRun by pendingTaskerRun
            LaunchedEffect(pendingRun) {
                pendingRun?.let { (profileId, command) ->
                    taskerOverlayCoordinator.runInTerminal(profileId, command)
                    pendingTaskerRun.value = null
                }
            }

            // Prevent screenshots/screen recording when enabled
            val screenSecurity by preferencesRepository.screenSecurity
                .collectAsState(initial = false)
            LaunchedEffect(screenSecurity) {
                if (screenSecurity) {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            // Hide Haven's task card from the recents screen (#239). Dynamic
            // (AppTask API) rather than the manifest flag so it's toggleable;
            // sessions and services are untouched — recents UI only.
            val excludeFromRecents by preferencesRepository.excludeFromRecents
                .collectAsState(initial = false)
            LaunchedEffect(excludeFromRecents) {
                getSystemService(android.app.ActivityManager::class.java)
                    ?.appTasks?.forEach { it.setExcludeFromRecents(excludeFromRecents) }
            }

            val themeMode by preferencesRepository.theme
                .collectAsState(initial = UserPreferencesRepository.ThemeMode.SYSTEM)
            // Mirror the user's choice to AppCompatDelegate so the next
            // cold-launch splash window matches without waiting for the
            // Application to re-read DataStore (#153 follow-up).
            LaunchedEffect(themeMode) {
                AppCompatDelegate.setDefaultNightMode(themeMode.toNightMode())
            }
            val darkTheme = when (themeMode) {
                UserPreferencesRepository.ThemeMode.LIGHT -> false
                UserPreferencesRepository.ThemeMode.DARK -> true
                UserPreferencesRepository.ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            HavenTheme(darkTheme = darkTheme) {
                val biometricEnabled by preferencesRepository.biometricEnabled
                    .collectAsState(initial = false)
                val lockTimeout by preferencesRepository.lockTimeout
                    .collectAsState(initial = sh.haven.core.data.preferences.UserPreferencesRepository.LockTimeout.IMMEDIATE)
                var unlocked by remember { mutableStateOf(false) }
                var backgroundedAt by remember { mutableStateOf(0L) }

                // Re-lock when app goes to background, respecting timeout.
                // Minimum 5s grace period so file pickers, permission dialogs,
                // and other brief system activities don't trigger re-lock.
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_STOP) {
                            backgroundedAt = System.currentTimeMillis()
                        }
                        if (event == Lifecycle.Event.ON_START && unlocked && backgroundedAt > 0) {
                            val elapsed = (System.currentTimeMillis() - backgroundedAt) / 1000
                            val effectiveTimeout = maxOf(lockTimeout.seconds, 5L)
                            if (elapsed >= effectiveTimeout) {
                                unlocked = false
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                if (biometricEnabled && !unlocked) {
                    BiometricLockScreen(
                        authenticator = biometricAuthenticator,
                        onUnlocked = { unlocked = true },
                    )
                } else {
                    HavenNavHost(
                        preferencesRepository = preferencesRepository,
                        connectionRepository = connectionRepository,
                        sshKeyRepository = sshKeyRepository,
                        agentUiCommandBus = agentUiCommandBus,
                        userMessageBus = userMessageBus,
                    )
                    // Top-of-tree host for BIOMETRIC_PROTECTED keystore
                    // fetches — the gate publishes; this host renders
                    // BiometricPrompt; the result resumes the
                    // suspending fetch caller.
                    sh.haven.app.keystore.BiometricGateHost()
                    // VPN sign-in questions (AnyConnect SMS code / unstored
                    // password) — published by TunnelAuthBroker, possibly
                    // from a background re-login, answered here.
                    TunnelAuthHost()
                }
            }
        }
    }

    override fun onDestroy() {
        if (activeInstanceRef?.get() === this) {
            activeInstanceRef = null
        }
        super.onDestroy()
    }

    /**
     * Handle a deep link handed straight to a live instance by
     * [DeepLinkActivity], bypassing the Intent system. Only needed because
     * onNewIntent is not reliably delivered on ColorOS; deduped against the
     * forwarded Intent by [isFreshDeepLink] so both paths can fire safely.
     */
    fun handleDeepLinkUri(uri: android.net.Uri) {
        val intent = Intent().apply { data = uri }
        handleRenewCertDeepLink(intent)
        handleConnectDeepLink(intent)
        handleStandardSchemeDeepLink(intent)
    }

    companion object {
        /**
         * Long enough to cover the gap between DeepLinkActivity's direct call
         * and the forwarded Intent landing in onNewIntent, short enough that a
         * user deliberately re-firing the same link still works.
         */
        private const val DEEP_LINK_DEDUPE_MS = 3_000L

        @Volatile
        private var activeInstanceRef: java.lang.ref.WeakReference<MainActivity>? = null

        fun getActiveInstance(): MainActivity? = activeInstanceRef?.get()
    }
}
