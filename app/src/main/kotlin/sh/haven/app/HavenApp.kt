package sh.haven.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import sh.haven.core.data.NativeCrashLog
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import sh.haven.core.data.preferences.UserPreferencesRepository
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class HavenApp : Application(), Configuration.Provider {

    @Inject lateinit var preferencesRepository: UserPreferencesRepository
    @Inject lateinit var launcherShortcutManager: sh.haven.app.launcher.LauncherShortcutManager
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var sessionManagerRegistry: sh.haven.core.ssh.SessionManagerRegistry
    @Inject lateinit var backupAutoSyncScheduler: sh.haven.app.backup.BackupAutoSyncScheduler
    @Inject lateinit var backupAutoPullScheduler: sh.haven.app.backup.BackupAutoPullScheduler
    @Inject lateinit var sshTerminalEmulatorOwner: sh.haven.feature.terminal.SshTerminalEmulatorOwner
    @Inject lateinit var updateNotifier: sh.haven.app.update.UpdateNotifier

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Required by [Configuration.Provider] so the Hilt-aware worker
     * factory is wired before any [androidx.work.WorkManager] lookup.
     * Workers that declare `@AssistedInject` deps require this; plain-
     * constructor workers don't go through the factory.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Before any SSH session exists: on some devices AndroidKeyStore wins an
        // unpinned raw-key JCE lookup, which breaks every curve25519 key exchange
        // on the sshlib engine. No-op on devices with a sane provider order (#451).
        sh.haven.core.ssh.RawKeyProviderOrder.apply()

        // Register the connect-time SSH emulator provider before any SSH session
        // can be created, so tmux's attach-time probes are answered live (#290 #2).
        sshTerminalEmulatorOwner.start()

        // Apply the saved theme override to AppCompatDelegate before any
        // activity is created, so the cold-launch splash window uses the
        // user's chosen mode rather than only the system uiMode (#153).
        // Synchronous DataStore read is intentional here — must happen
        // before the first activity, and the cost is bounded.
        val savedMode = try {
            runBlocking { preferencesRepository.theme.first() }
        } catch (_: Exception) {
            UserPreferencesRepository.ThemeMode.SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(savedMode.toNightMode())

        // Recover the tombstone from any previous native crash (#509, #517).
        // Haven records its logcat from inside its own process, so a native
        // signal kills the recorder along with everything else and the backtrace
        // is written after Haven is already gone — the one log that identifies a
        // native crash was the one Haven structurally could not capture. This is
        // the next launch asking the system what happened to the last one.
        //
        // Off the main thread: it reads a file and a system service, and nothing
        // about startup should wait on diagnostics.
        appScope.launch {
            try {
                NativeCrashLog(this@HavenApp).refresh()
            } catch (e: Exception) {
                android.util.Log.w("HavenApp", "native crash sweep failed: ${e.message}")
            }
            // Same next-launch pattern for non-crash deaths (#494): fold the
            // system's kill records (force-stop, freezer, low memory, ANR)
            // into the process-exit log, so the Connections screen and the
            // get_process_exits MCP verb can say who killed the last process
            // instead of a reporter needing adb.
            try {
                sh.haven.core.data.ProcessExitLog(this@HavenApp).refresh()
            } catch (e: Exception) {
                android.util.Log.w("HavenApp", "process exit sweep failed: ${e.message}")
            }
        }

        // Mirror saved connections (#699) and workspaces into Android
        // launcher long-press shortcuts — "Connect <profile>" and
        // "Open <workspace>" entries from the home screen. Self-observing —
        // recomputes on every repo change.
        launcherShortcutManager.start()

        // Auto-push backup watch (#359) — same pattern: inert until the user
        // enables auto-sync in Settings → Backup → Sync to a remote.
        backupAutoSyncScheduler.start(appScope)
        backupAutoPullScheduler.start(appScope)

        // Opt-in update check (#578). Same pattern again: returns without a
        // network request unless the switch is on, the copy was signed with the
        // GitHub-release key, and a day has passed since the last look.
        updateNotifier.start(appScope)

        // Extend the shell-prompt terminator set used for command-on-attach
        // detection with the user's custom prompt characters (#280). Replays
        // the current value on subscribe, so it's armed before the first
        // session opens. TerminalSession keeps it in a process-wide field
        // (no DataStore dependency in core:ssh).
        preferencesRepository.terminalPromptChars
            .distinctUntilChanged()
            .onEach { extra ->
                sh.haven.core.ssh.TerminalSession.promptTerminators =
                    sh.haven.core.ssh.TerminalSession.DEFAULT_PROMPT_TERMINATORS +
                    extra.filterNot { it.isWhitespace() }.toSet()
            }
            .launchIn(appScope)

        // #418 debug: bridge the "RDP progressive upgrade" preference into the
        // process-global RdpDebugToggles the native decoder reads at connect.
        // Replays on subscribe (seeds at startup) and updates live when the
        // Settings → Diagnostics toggle changes. Temporary until the upgrade
        // decode is verified and enabled by default.
        preferencesRepository.rdpProgressiveUpgrade
            .distinctUntilChanged()
            .onEach { sh.haven.core.rdp.RdpDebugToggles.progressiveUpgrade = it }
            .launchIn(appScope)

        // #425: bridge the "RDP H.264/AVC420 (KRDP)" preference into the same
        // process-global toggles so the native side advertises AVC and the
        // MediaCodec decoder is registered at connect.
        preferencesRepository.rdpAvcEnabled
            .distinctUntilChanged()
            .onEach { sh.haven.core.rdp.RdpDebugToggles.avcEnabled = it }
            .launchIn(appScope)
    }

    /**
     * Start [SshConnectionService] from the Application context. The
     * service's onStartCommand calls startForeground within Android's
     * 5-second budget, so this is safe from a non-Activity context. If the
     * OS refuses the start (none currently does), the catch keeps Haven
     * alive.
     */
    private fun startSessionServiceIfPossible() {
        try {
            val intent = Intent(this, sh.haven.core.ssh.SshConnectionService::class.java)
            startForegroundService(intent)
        } catch (e: Exception) {
            android.util.Log.w("HavenApp", "startForegroundService failed: ${e.message}")
        }
    }

    companion object {
    }
}

internal fun UserPreferencesRepository.ThemeMode.toNightMode(): Int = when (this) {
    UserPreferencesRepository.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
    UserPreferencesRepository.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    UserPreferencesRepository.ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
}
