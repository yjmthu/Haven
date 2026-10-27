package sh.haven.feature.connections

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.PortForwardRule
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.repository.DecryptedKeys
import sh.haven.core.data.repository.KeyMaterial
import sh.haven.core.data.repository.KeyUnlockDeclinedException
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.HardwareKeyInvalidatedException
import sh.haven.core.ssh.HardwareKeySignDeclinedException
import sh.haven.core.ssh.KeystoreSshKeySigner
import sh.haven.core.ssh.OpenKeychainClientFactory
import sh.haven.core.ssh.openkeychain.OpenKeychainKeyData
import sh.haven.core.ssh.HostKeyAuthFailure
import sh.haven.core.ssh.HostKeyResult
import sh.haven.core.ssh.isSshNetworkError
import sh.haven.core.ssh.HostKeyVerifier
import sh.haven.core.ssh.KeyboardInteractiveChallenge
import sh.haven.core.ssh.KeyboardInteractivePrompter
import sh.haven.core.ssh.KnownHostEntry
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshConnection
import sh.haven.core.ssh.SshConnectionFactory
import sh.haven.core.ssh.sshEngineFromOptionsText
import sh.haven.core.ssh.SshConnectionService
import sh.haven.core.ssh.SshKeyExporter
import sh.haven.core.ssh.SessionManager
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.ssh.SshVerboseLogger
import sh.haven.core.data.db.entities.KnownHost
import sh.haven.core.fido.FidoAuthenticator
import sh.haven.core.fido.FidoTouchPrompt
import sh.haven.core.security.Totp
import sh.haven.core.security.CredentialEncryption
import sh.haven.feature.connections.R
import android.util.Log
import sh.haven.core.ssh.HavenProxy
import java.io.File
import javax.inject.Inject
import sh.haven.core.redact.LogRedact

private const val TAG = "ConnectionsVM"

/**
 * True when [profile] would offer an SSH key during auth: it has an explicit
 * key or a Key element in its auth chain, and keys aren't force-disabled.
 * Used to decide whether a jump-host connect may borrow the profile's saved
 * login password (#381).
 */
internal fun profileUsesKeyAuth(profile: ConnectionProfile): Boolean =
    !profile.ignoreSavedKeys && (
        profile.keyId != null ||
            profile.authMethodSpecs.any { it is ConnectionProfile.AuthMethodSpec.Key }
        )

/**
 * The two enums share names by construction (#137) — defined separately
 * because `core/data` can't depend on `core/ssh`. Convert via name lookup.
 */
internal val ConnectionProfile.addressFamilyForSsh: ConnectionConfig.AddressFamily
    get() = ConnectionConfig.AddressFamily.valueOf(addressFamilyEnum.name)

/**
 * Per-profile reconnect knobs to a value object the SSH session
 * manager understands. Three columns from the data model collapse
 * into one [ConnectionConfig.ReconnectPolicy] — keeps the connect-
 * config builders one line longer instead of three (#150).
 */
internal val ConnectionProfile.reconnectPolicy: ConnectionConfig.ReconnectPolicy
    get() = ConnectionConfig.ReconnectPolicy(
        autoReconnect = autoReconnect,
        maxAttempts = reconnectMaxAttempts,
        onNetworkChange = reconnectOnNetworkChange,
    )

/**
 * The password value a jump-host connect threads into `resolveAuthMethods`.
 * `resolveAuthMethods` reuses this both as the password-auth password AND as
 * the passphrase for an encrypted key, so the choice matters:
 *  - A caller-supplied password (explicit SSH-side jump chain) always wins.
 *  - #121: a PASSWORD-auth jump borrows the profile's saved sshPassword when
 *    the auto-connect caller passed none, so password jumps still succeed.
 *  - #381: a KEY-auth jump must NOT borrow it — the saved *login* password
 *    would be handed to a passphrase-protected key as its passphrase, so the
 *    key can't decrypt and is never offered (fails "publickey" on the jump leg
 *    while a direct connect, which passes "", works). Return "" so the key
 *    resolves via its stored passphrase, exactly like a direct connect.
 */
internal fun jumpConnectPassword(
    typedPassword: String,
    jumpUsesKeyAuth: Boolean,
    savedSshPassword: String?,
): String = when {
    typedPassword.isNotEmpty() -> typedPassword
    jumpUsesKeyAuth -> ""
    else -> savedSshPassword ?: ""
}

/**
 * How long a host-key accept/reject prompt waits for an on-screen tap before
 * giving up. Without a bound, a prompt raised while Connections isn't the
 * composed/visible screen (a background reconnect, an MCP-triggered
 * connect_profile) blocks the connect forever with no error and no further
 * log output — reproduced live via a USB-drive-VM profile hanging 5+ minutes.
 */
private const val HOST_KEY_PROMPT_TIMEOUT_MS = 90_000L

/** Unified connection status that maps transport session states. */
enum class ProfileStatus { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, ERROR }

data class GroupLaunchState(
    val groupId: String,
    val total: Int,
    val completed: Int,
    val succeeded: Int,
    val skipped: Int,
    val connectingIds: Set<String>,
)

@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: ConnectionRepository,
    private val connectionGroupDao: sh.haven.core.data.db.ConnectionGroupDao,
    private val sshIdentityRepository: sh.haven.core.data.repository.SshIdentityRepository,
    private val portForwardRepository: PortForwardRepository,
    private val sshSessionManager: SshSessionManager,
    private val backgroundDisconnectDetector: sh.haven.core.ssh.BackgroundDisconnectDetector,
    private val sshSessionAttacher: sh.haven.core.ssh.SshSessionAttacher,
    private val fidoAuthenticator: FidoAuthenticator,
    /** Security-chip SSH keys sign through the Keystore + biometric ceremony. */
    private val keystoreSshKeySigner: KeystoreSshKeySigner,
    private val sessionManagerRegistry: SessionManagerRegistry,
    private val sshKeyRepository: SshKeyRepository,
    private val totpSecretRepository: sh.haven.core.data.repository.TotpSecretRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val hostKeyVerifier: HostKeyVerifier,
    private val connectionLogRepository: ConnectionLogRepository,
    private val tunnelResolver: sh.haven.core.tunnel.TunnelResolver,
    private val tunnelConfigRepository: sh.haven.core.data.repository.TunnelConfigRepository,
    private val agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
    private val desktopSessionRegistry: sh.haven.core.data.desktop.DesktopSessionRegistry,
    private val userMessageBus: sh.haven.core.data.message.UserMessageBus,
    private val hostRediscovery: HostRediscovery,
    private val biometricGate: sh.haven.core.data.keystore.BiometricGate,
    private val pendingAuthPromptHolder: sh.haven.core.data.agent.PendingAuthPromptHolder,
    private val sessionSelectionHolder: sh.haven.core.data.agent.SessionSelectionHolder,
) : ViewModel() {

    /**
     * Gate revealing a stored credential behind a biometric/device-credential
     * prompt (#274). Returns true only on an explicit ALLOW; DENY, timeout, or
     * no-foreground-Activity (UNAVAILABLE) keep the value masked. Reuses the
     * key-unlock [sh.haven.core.data.keystore.BiometricGate], whose 30s
     * session-unlock window means one prompt per edit session.
     */
    suspend fun authToRevealPassword(title: String, subtitle: String): Boolean =
        biometricGate.request(label = title, detail = subtitle) ==
            sh.haven.core.data.keystore.BiometricGate.Decision.ALLOW

    /** Verbose SSH log captured during connect, keyed by sessionId. Consumed by finishConnect. */
    private val pendingVerboseLogs = mutableMapOf<String, String>()

    init {
        // #495: an SSH session that dies within seconds of the app going to the
        // background, while the process and its foreground-service notification
        // are both still alive, is the signature of a vendor background
        // restriction rather than anything Haven did. Say so once, with the
        // setting to check — Android's own battery-optimisation exemption cannot
        // see those switches, so Haven otherwise reports "exempt, all good"
        // while the thing killing connections sits in a screen it can't read.
        //
        // Deliberately NOT a general SSH-drop log: an intentional disconnect
        // leaves an SSH session in the map as DISCONNECTED, so logging every
        // transition would be noise. Gating on the background window keeps
        // this to the case it can explain.
        viewModelScope.launch {
            val wasConnected = mutableSetOf<String>()
            var alreadyReported = false
            sshSessionManager.sessions.collect { map ->
                map.values.forEach { s ->
                    val connected = s.status == SshSessionManager.SessionState.Status.CONNECTED
                    if (connected) {
                        wasConnected += s.sessionId
                    } else if (s.sessionId in wasConnected) {
                        wasConnected -= s.sessionId
                        if (!alreadyReported && backgroundDisconnectDetector.looksLikeBackgroundRestriction()) {
                            alreadyReported = true
                            val hint = sh.haven.core.ssh.BackgroundDisconnectDetector
                                .vendorBackgroundSettingHint(android.os.Build.MANUFACTURER)
                            connectionLogRepository.logEvent(
                                s.profileId,
                                ConnectionLog.Status.DISCONNECTED,
                                details = buildString {
                                    append(
                                        "Dropped within ")
                                    append(sh.haven.core.ssh.BackgroundDisconnectDetector.WINDOW_MS / 1000)
                                    append("s of Haven going to the background, while the ")
                                    append("connection notification was still showing. That is usually the ")
                                    append("phone's own background restrictions cutting the connection, not ")
                                    append("Haven closing it — and it is a separate setting from Android's ")
                                    append("battery optimisation, which Haven cannot read. ")
                                    if (hint != null) {
                                        append("On ")
                                        append(android.os.Build.MANUFACTURER)
                                        append(": ")
                                        append(hint)
                                        append(".")
                                    } else {
                                        append(
                                            "Look for an 'allow background activity' or 'autostart' " +
                                                "switch in your phone's app settings.",
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        // Agent-driven connect: MCP `connect_profile` tool posts here.
        // Look up the profile and dispatch through the unified connect()
        // entry so route-through, stored passwords, and key auth all
        // apply identically to a UI tap.
        viewModelScope.launch {
            agentUiCommandBus.commands.collect { command ->
                if (command is sh.haven.core.data.agent.AgentUiCommand.ConnectProfile) {
                    val profile = repository.getById(command.profileId)
                    if (profile != null) {
                        connect(profile, password = profile.sshPassword.orEmpty(), sessionName = command.sessionName)
                    } else {
                        Log.w(TAG, "ConnectProfile: profile ${command.profileId} not found")
                    }
                } else if (command is sh.haven.core.data.agent.AgentUiCommand.AnswerAuthPrompt) {
                    // MCP answer_auth_prompt: supply the secret to the live
                    // password/passphrase fallback and re-drive the connect.
                    answerPasswordFallback(
                        command.password,
                        username = command.username,
                        rememberPassword = command.rememberPassword,
                    )
                } else if (command is sh.haven.core.data.agent.AgentUiCommand.AnswerSessionSelection) {
                    // MCP answer_session_picker: choose a session (or null =
                    // create new) on the live picker and re-drive the attach,
                    // exactly as a human tap on the picker would.
                    if (_sessionSelection.value != null) {
                        onSessionSelected(command.sessionId, command.sessionName)
                    }
                } else if (command is sh.haven.core.data.agent.AgentUiCommand.ConnectFromDeepLink) {
                    // haven://connect matched a saved profile (#305). Show a
                    // confirm sheet first — a BROWSABLE link can be fired by a
                    // web page, and confirming blocks a drive-by connect that
                    // would use this profile's stored credentials.
                    val profile = repository.getById(command.profileId)
                    if (profile != null) {
                        _connectConfirm.value = ConnectConfirm(profile, command.sessionName, command.startupCommand)
                    } else {
                        Log.w(TAG, "ConnectFromDeepLink: profile ${command.profileId} not found")
                    }
                } else if (command is sh.haven.core.data.agent.AgentUiCommand.PrefillNewConnection) {
                    // haven://connect with no saved match (#305): surface the
                    // New-Connection editor pre-filled for the user to review,
                    // add auth, and save.
                    _prefillNewConnection.value = command
                }
            }
        }

        // Stop foreground service when last session closes, regardless of how it was removed
        // (close tab, network drop, etc.). Debounce prevents rapid start/stop cycling.
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        viewModelScope.launch {
            sshSessionManager.sessions
                .debounce(500L)
                .collect { updateServiceNotification() }
        }

    }

    // SAF "local folder" locations (#415) are Files-tab constructs, not real
    // connections — they carry no host and nothing to connect. Keep them out of
    // the Connections screen (and its group-launch/auto-connect sweeps, which
    // would otherwise misroute a host-less profile to the SSH path); they're
    // created, browsed and managed entirely from the Files tab.
    val connections: StateFlow<List<ConnectionProfile>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groups: StateFlow<List<sh.haven.core.data.db.entities.ConnectionGroup>> =
        connectionGroupDao.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sshKeys: StateFlow<List<SshKey>> = sshKeyRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Reusable SSH identities (#360), for the connection editor's picker. */
    val identities: StateFlow<List<sh.haven.core.data.db.entities.SshIdentity>> =
        sshIdentityRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totpSecrets: StateFlow<List<sh.haven.core.data.db.entities.TotpSecret>> =
        totpSecretRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Shared (non-embedded) tunnel configs the route-through dropdown
     * surfaces in the SSH profile editor. Cloudflare Tunnels embedded on
     * a single SSH profile (GH #154, `ownerProfileId != null`) are
     * excluded from this list — they're surfaced inline on the owning
     * profile instead.
     */
    val tunnelConfigs: StateFlow<List<sh.haven.core.data.db.entities.TunnelConfig>> =
        tunnelConfigRepository.observeStandalone()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val globalSessionManagerLabel: StateFlow<String> = preferencesRepository.sessionManager
        .map { it.label }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "None")

    val batteryPromptDismissed: StateFlow<Boolean> = preferencesRepository.batteryPromptDismissed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true) // default true to avoid flash

    fun dismissBatteryPrompt() {
        viewModelScope.launch {
            preferencesRepository.setBatteryPromptDismissed()
        }
    }

    /** Permanent opt-out — the prompt never returns, drops included (#494). */
    fun neverAskBatteryPrompt() {
        viewModelScope.launch {
            preferencesRepository.setBatteryPromptNeverAsk()
        }
    }

    /**
     * Decide what the screen should do about the battery prompt for the
     * current [exemptNow] observation, and record that observation. A drop
     * (previously exempt, now not) clears the episodic dismissal so the
     * offer returns with the it-was-switched-off wording (#494 — Realme UI
     * resets the exemption on app update).
     */
    suspend fun batteryPromptCheck(exemptNow: Boolean): BatteryPromptAction {
        val action = batteryPromptAction(
            exemptNow = exemptNow,
            lastKnownExempt = preferencesRepository.batteryLastKnownExempt.first(),
            dismissed = preferencesRepository.batteryPromptDismissed.first(),
            neverAsk = preferencesRepository.batteryPromptNeverAsk.first(),
        )
        if (action == BatteryPromptAction.OFFER_DROPPED) {
            preferencesRepository.setBatteryPromptDismissed(false)
        }
        preferencesRepository.setBatteryLastKnownExempt(exemptNow)
        return action
    }

    /**
     * Tell the user, once per death, when the previous Haven process was
     * killed by the system while it had visible work (#494) — the in-app
     * version of `adb shell dumpsys activity exit-info`. Runs from the
     * Connections screen so the root snackbar collector is already live.
     */
    fun announceRecentKills() {
        viewModelScope.launch(Dispatchers.IO) {
            val fresh = try {
                sh.haven.core.data.ProcessExitLog(appContext).consumeUnnotified()
            } catch (e: Exception) {
                Log.w(TAG, "process exit announce failed: ${e.message}")
                return@launch
            }
            // Newest only: after a kill-loop the count is the story, not
            // each entry.
            val latest = fresh.maxByOrNull { it.timestampMs } ?: return@launch
            val time = android.text.format.DateFormat.getTimeFormat(appContext)
                .format(java.util.Date(latest.timestampMs))
            val cause = appContext.getString(processExitCauseRes(latest.kind))
            userMessageBus.emit(
                sh.haven.core.data.message.UserMessage(
                    appContext.getString(R.string.connections_process_killed_banner, time, cause),
                    sh.haven.core.data.message.UserMessage.Severity.ERROR,
                ),
            )
        }
    }

    val sessions: StateFlow<Map<String, SshSessionManager.SessionState>> = sshSessionManager.sessions

    /** Derive profile-level statuses for the connections list UI. */
    val profileStatuses: StateFlow<Map<String, ProfileStatus>> =
        combine(
            sshSessionManager.sessions,
            desktopSessionRegistry.statuses,
        ) { sshMap, deskMap ->
            val result = mutableMapOf<String, ProfileStatus>()

            // SSH statuses.
            sshMap.values.groupBy { it.profileId }.forEach { (profileId, states) ->
                val statuses = states.map { it.status }
                result[profileId] = when {
                    SshSessionManager.SessionState.Status.CONNECTED in statuses -> ProfileStatus.CONNECTED
                    SshSessionManager.SessionState.Status.RECONNECTING in statuses -> ProfileStatus.RECONNECTING
                    SshSessionManager.SessionState.Status.CONNECTING in statuses -> ProfileStatus.CONNECTING
                    SshSessionManager.SessionState.Status.ERROR in statuses -> ProfileStatus.ERROR
                    else -> ProfileStatus.DISCONNECTED
                }
            }

            // Remote-desktop (VNC/RDP) tabs have no transport session of their
            // own — they live as Desktop tabs in DesktopViewModel — so their
            // connections-list row would otherwise show no status. Reflect the
            // tab's real live state (DesktopSessionRegistry, set at the actual
            // handshake) onto the profile row: connecting while it dials,
            // connected once the desktop is up, cleared on teardown (#121).
            deskMap.forEach { (profileId, status) ->
                val deskStatus = when (status) {
                    sh.haven.core.data.desktop.DesktopStatus.CONNECTED -> ProfileStatus.CONNECTED
                    sh.haven.core.data.desktop.DesktopStatus.CONNECTING -> ProfileStatus.CONNECTING
                    sh.haven.core.data.desktop.DesktopStatus.DISCONNECTED -> ProfileStatus.DISCONNECTED
                    sh.haven.core.data.desktop.DesktopStatus.ERROR -> ProfileStatus.ERROR
                }
                val existing = result[profileId]
                if (existing == null || deskStatus.ordinal < existing.ordinal) {
                    result[profileId] = deskStatus
                }
            }

            result.toMap()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** The profileId currently being connected (for spinner in UI). */
    private val _connectingProfileId = MutableStateFlow<String?>(null)
    val connectingProfileId: StateFlow<String?> = _connectingProfileId.asStateFlow()

    /** Tracks progress of a group launch operation. */
    private val _groupLaunchState = MutableStateFlow<GroupLaunchState?>(null)
    val groupLaunchState: StateFlow<GroupLaunchState?> = _groupLaunchState.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Non-fatal warning surfaced alongside a successful or in-progress connect.
     * Distinct from [error] so the UI can style it differently and so callers
     * don't accidentally abort a working connection on a cosmetic warning.
     * Example: agent forwarding enabled but all stored keys are encrypted.
     */
    private val _warning = MutableStateFlow<String?>(null)
    val warning: StateFlow<String?> = _warning.asStateFlow()

    /** Profiles that have already shown the agent-forwarding warning this session. */
    private val agentWarningShownFor = mutableSetOf<String>()

    /**
     * Direct re-export of [FidoAuthenticator.touchPrompt] so the connections
     * screen can show a "plug in / tap your security key" prompt during a
     * FIDO2 SSH assertion. Re-exporting (rather than collecting and copying)
     * keeps the StateFlow's hot semantics — the prompt appears and disappears
     * exactly when the authenticator's assertion goroutine sets/clears it.
     */
    val fidoTouchPrompt: StateFlow<FidoTouchPrompt?> = fidoAuthenticator.touchPrompt

    /** Cancel an in-flight FIDO key wait/touch (the dialog's Cancel button). */
    fun cancelFido() = fidoAuthenticator.cancelPending()

    /** When non-null, key auth failed and the UI should show a password dialog as fallback. */
    private val _passwordFallback = MutableStateFlow<ConnectionProfile?>(null)
    val passwordFallback: StateFlow<ConnectionProfile?> = _passwordFallback.asStateFlow()

    // Mirror the password/passphrase fallback dialog to a process-wide holder so
    // the MCP agent can observe it (get_pending_auth_prompt) and answer it
    // (answer_auth_prompt) without a human tap. This init block is declared
    // AFTER _passwordFallback so the collector never captures the field before
    // it's initialised (Main.immediate runs init collectors synchronously).
    init {
        viewModelScope.launch {
            _passwordFallback.collect { profile ->
                pendingAuthPromptHolder.set(
                    profile?.let {
                        val encrypted = it.keyId?.let { id ->
                            sshKeyRepository.getById(id)?.isEncrypted
                        } ?: false
                        sh.haven.core.data.agent.PendingAuthPrompt(
                            profileId = it.id,
                            label = it.label,
                            requiresPassphrase = encrypted,
                        )
                    }
                )
            }
        }
    }

    /**
     * VNC/RDP-over-SSH-tunnel paths used to fail silently when the
     * jump host had no saved password — they auto-connected with empty
     * credentials and the resulting "Auth cancel for methods…" error
     * went unsurfaced (#121a, KoriKraut). We now detect "no saved auth"
     * up front, surface a password prompt, and (after submit) replay
     * the dependent connect against the now-authed jump host. This
     * holds the dependent's profile so the password dialog's onConnect
     * handler knows to dispatch back here instead of running the
     * generic SSH-login path.
     */
    private val _pendingTunnelDependent = MutableStateFlow<ConnectionProfile?>(null)
    val pendingTunnelDependent: StateFlow<ConnectionProfile?> = _pendingTunnelDependent.asStateFlow()

    // #729: when the jump leg of an SSH-terminal connect fails auth, the
    // replay has to re-drive the TARGET connect with the password the user
    // already typed for it (and the remember tick from that dialog), or the
    // target comes up password-less and fails its own auth a second time.
    // Tunnel-mode dependents (VNC/RDP) authenticate later in their own
    // dialogs, so they thread null and keep today's behaviour.
    private val _pendingDependentPassword = MutableStateFlow<String?>(null)
    private val _pendingDependentRememberPassword = MutableStateFlow<Boolean?>(null)
    // internal for unit tests (#729): the replay's queued dependent credentials
    // are not otherwise observable.
    internal val pendingDependentPassword: StateFlow<String?> = _pendingDependentPassword.asStateFlow()
    internal val pendingDependentRememberPassword: StateFlow<Boolean?> = _pendingDependentRememberPassword.asStateFlow()

    sealed class HostKeyPrompt {
        data class NewHost(
            val entry: KnownHostEntry,
            val deferred: kotlinx.coroutines.CompletableDeferred<Boolean>,
        ) : HostKeyPrompt()
        data class KeyChanged(
            val oldFingerprint: String,
            val entry: KnownHostEntry,
            val deferred: kotlinx.coroutines.CompletableDeferred<Boolean>,
        ) : HostKeyPrompt()
    }

    private val _hostKeyPrompt = MutableStateFlow<HostKeyPrompt?>(null)
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = _hostKeyPrompt.asStateFlow()

    fun onHostKeyAccepted() {
        (_hostKeyPrompt.value as? HostKeyPrompt.NewHost)?.deferred?.complete(true)
        (_hostKeyPrompt.value as? HostKeyPrompt.KeyChanged)?.deferred?.complete(true)
        _hostKeyPrompt.value = null
    }

    fun onHostKeyRejected() {
        (_hostKeyPrompt.value as? HostKeyPrompt.NewHost)?.deferred?.complete(false)
        (_hostKeyPrompt.value as? HostKeyPrompt.KeyChanged)?.deferred?.complete(false)
        _hostKeyPrompt.value = null
    }

    /**
     * In-flight keyboard-interactive auth round. When non-null the UI should
     * render [KeyboardInteractiveDialog] to collect responses (typically a
     * 2FA / TOTP code after the password) and call either [submit] or
     * [cancel]. Cancelling returns `null` to JSch, which aborts the KI
     * auth attempt. See #100.
     */
    class PendingKeyboardInteractiveAuth(
        val challenge: KeyboardInteractiveChallenge,
        private val deferred: CompletableDeferred<List<String>?>,
    ) {
        fun submit(responses: List<String>) {
            deferred.complete(responses)
        }
        fun cancel() {
            deferred.complete(null)
        }
    }

    private val _keyboardInteractiveAuth = MutableStateFlow<PendingKeyboardInteractiveAuth?>(null)
    val keyboardInteractiveAuth: StateFlow<PendingKeyboardInteractiveAuth?> =
        _keyboardInteractiveAuth.asStateFlow()

    /**
     * Shared prompter for every SSH connect path. Suspends in the JSch IO
     * thread (via [KeyboardInteractiveUserInfo]'s `runBlocking`) until the
     * user submits or cancels the dialog.
     */
    private val keyboardInteractivePrompter = KeyboardInteractivePrompter { challenge ->
        val deferred = CompletableDeferred<List<String>?>()
        android.util.Log.d("HavenKI", "VM prompter: set pending challenge prompts=${challenge.prompts.size} first='${challenge.prompts.firstOrNull()?.text}'")
        _keyboardInteractiveAuth.value = PendingKeyboardInteractiveAuth(challenge, deferred)
        try {
            deferred.await().also {
                android.util.Log.d("HavenKI", "VM prompter: deferred resumed with ${if (it == null) "null" else "${it.size} responses"}")
            }
        } finally {
            _keyboardInteractiveAuth.value = null
        }
    }

    fun submitKeyboardInteractiveResponses(responses: List<String>) {
        val pending = _keyboardInteractiveAuth.value
        android.util.Log.d("HavenKI", "VM submit called: responses=${responses.size} lens=${responses.map{it.length}} pending=${pending != null}")
        pending?.submit(responses)
    }

    fun cancelKeyboardInteractive() {
        android.util.Log.d("HavenKI", "VM cancel called")
        _keyboardInteractiveAuth.value?.cancel()
    }

    /**
     * Run TOFU host-key verification against [entry], prompting the user for
     * accept/reject on first contact or key change. Throws if rejected, or if
     * nobody answers within [HOST_KEY_PROMPT_TIMEOUT_MS] — a prompt raised
     * while Connections isn't the visible screen (background reconnect,
     * MCP-triggered connect_profile) would otherwise await a tap that can
     * never come, hanging the connect forever with no error and no further
     * log output (reproduced live).
     *
     * [autoAccept] skips the prompt (and its timeout) entirely, accepting the
     * key immediately — only correct for profiles Haven itself originates
     * *and* owns both ends of (USB-drive-VM loopback profiles): the guest's
     * host key is generated fresh by Haven's own boot script every VM boot,
     * over a loopback-only connection, so there is no third party to
     * trust-on-first-use against, and a fresh key every boot would otherwise
     * re-prompt on every single drive reopen. Every other profile keeps the
     * normal interactive prompt — callers must not set this for anything else
     * without deliberately deciding to trade away that check.
     *
     * Shared by every interactive connect path so that they behave the same
     * way on fresh contact, key change, and (post-fix) auth-failure-after-KEX.
     */
    /**
     * Silent-path variant of the TOFU decision: a null [entry] means the
     * host presented a certificate JSch already validated, so it is Trusted.
     */
    private suspend fun verifyOrCaTrusted(entry: KnownHostEntry?): HostKeyResult =
        if (entry == null) HostKeyResult.Trusted else hostKeyVerifier.verify(entry)

    private suspend fun runTofuVerification(
        entry: KnownHostEntry?,
        clientToDisconnectOnReject: SshConnection? = null,
        rejectedOnNewHostMessage: String = "Host key rejected by user",
        rejectedOnChangeMessage: String = "Host key change rejected by user",
        autoAccept: Boolean = false,
    ) {
        if (entry == null) {
            // SshClient.connect returned null: the host presented a certificate
            // that JSch verified during KEX. Nothing to TOFU.
            Log.i(TAG, "Host verified by supplied CA key — no TOFU prompt")
            return
        }
        when (val result = hostKeyVerifier.verify(entry)) {
            is HostKeyResult.Trusted -> return
            is HostKeyResult.NewHost -> {
                if (autoAccept) {
                    Log.d(TAG, "Auto-accepting new host key — Haven-owned USB-drive-VM loopback profile")
                    hostKeyVerifier.accept(result.entry)
                    return
                }
                val deferred = CompletableDeferred<Boolean>()
                _hostKeyPrompt.value = HostKeyPrompt.NewHost(result.entry, deferred)
                if (!awaitHostKeyDecision(deferred)) {
                    clientToDisconnectOnReject?.disconnect()
                    throw Exception(rejectedOnNewHostMessage)
                }
                hostKeyVerifier.accept(result.entry)
            }
            is HostKeyResult.KeyChanged -> {
                if (autoAccept) {
                    Log.d(TAG, "Auto-accepting changed host key — Haven-owned USB-drive-VM loopback profile")
                    hostKeyVerifier.accept(result.new)
                    return
                }
                val deferred = CompletableDeferred<Boolean>()
                _hostKeyPrompt.value = HostKeyPrompt.KeyChanged(
                    oldFingerprint = result.old.fingerprint,
                    entry = result.new,
                    deferred = deferred,
                )
                if (!awaitHostKeyDecision(deferred)) {
                    clientToDisconnectOnReject?.disconnect()
                    throw Exception(rejectedOnChangeMessage)
                }
                hostKeyVerifier.accept(result.new)
            }
        }
    }

    /** Awaits [deferred] with [HOST_KEY_PROMPT_TIMEOUT_MS]; a timeout clears the stale prompt and counts as reject. */
    private suspend fun awaitHostKeyDecision(deferred: CompletableDeferred<Boolean>): Boolean {
        val decided = withTimeoutOrNull(HOST_KEY_PROMPT_TIMEOUT_MS) { deferred.await() }
        if (decided == null) {
            Log.w(TAG, "Host-key prompt timed out after ${HOST_KEY_PROMPT_TIMEOUT_MS}ms with no on-screen response — treating as rejected")
            _hostKeyPrompt.value = null
        }
        return decided ?: false
    }

    /** Emitted once after a successful connect to trigger navigation to terminal (profileId). */
    private val _navigateToTerminal = MutableStateFlow<String?>(null)
    val navigateToTerminal: StateFlow<String?> = _navigateToTerminal.asStateFlow()

    /** Emitted to navigate back to Connections screen after a post-connect failure. */
    private val _navigateToConnections = MutableStateFlow(false)
    val navigateToConnections: StateFlow<Boolean> = _navigateToConnections.asStateFlow()

    /** Emitted to navigate to VNC screen with connection params. */
    data class VncNavigation(
        val host: String,
        val port: Int,
        val password: String?,
        val username: String? = null,
        val sshForward: Boolean = false,
        val sshSessionId: String? = null,
        val profileId: String? = null,
        val colorDepth: String = "BPP_24_TRUE",
    )
    private val _navigateToVnc = MutableStateFlow<VncNavigation?>(null)
    val navigateToVnc: StateFlow<VncNavigation?> = _navigateToVnc.asStateFlow()

    /** Emitted to navigate to RDP screen with connection params. */
    data class RdpNavigation(val host: String, val port: Int, val username: String, val password: String, val domain: String, val sshForward: Boolean = false, val sshSessionId: String? = null, val profileId: String? = null, val useNla: Boolean = true, val colorDepth: Int = 32)
    private val _navigateToRdp = MutableStateFlow<RdpNavigation?>(null)
    val navigateToRdp: StateFlow<RdpNavigation?> = _navigateToRdp.asStateFlow()

    /** Emitted to open a new session (new tab) on an already-connected profile. */
    private val _newSessionProfileId = MutableStateFlow<String?>(null)
    val newSessionProfileId: StateFlow<String?> = _newSessionProfileId.asStateFlow()

    /** When non-null, the UI should show a session picker dialog. */
    data class SessionSelection(
        val sessionId: String,
        val profileId: String,
        val managerLabel: String,
        val sessionNames: List<String>,
        /** Session names that were open last time (for "Restore" action). */
        val previousSessionNames: List<String> = emptyList(),
        val manager: SessionManager = SessionManager.NONE,
        /** "SSH" or "MOSH" — determines which finish path onSessionSelected uses. */
        val transportType: String = "SSH",
        /** Pre-filled name for the "Create new session" text field. (#112) */
        val suggestedNewName: String = "",
    )

    private val _sessionSelection = MutableStateFlow<SessionSelection?>(null)
    val sessionSelection: StateFlow<SessionSelection?> = _sessionSelection.asStateFlow()

    // --- haven://connect deep link (#305) ---

    /** A matched saved profile awaiting the user's confirm before connecting. */
    data class ConnectConfirm(
        val profile: ConnectionProfile,
        val sessionName: String?,
        val startupCommand: String? = null,
    )

    private val _connectConfirm = MutableStateFlow<ConnectConfirm?>(null)
    val connectConfirm: StateFlow<ConnectConfirm?> = _connectConfirm.asStateFlow()

    /** User confirmed the deep-link connect: run the same path as ConnectProfile. */
    fun confirmDeepLinkConnect() {
        val c = _connectConfirm.value ?: return
        _connectConfirm.value = null
        connect(
            c.profile,
            password = c.profile.sshPassword.orEmpty(),
            sessionName = c.sessionName,
            startupCommand = c.startupCommand,
        )
    }

    fun dismissDeepLinkConnect() {
        _connectConfirm.value = null
    }

    /** Params for the New-Connection editor to open pre-filled (unmatched host). */
    private val _prefillNewConnection =
        MutableStateFlow<sh.haven.core.data.agent.AgentUiCommand.PrefillNewConnection?>(null)
    val prefillNewConnection:
        StateFlow<sh.haven.core.data.agent.AgentUiCommand.PrefillNewConnection?> =
        _prefillNewConnection.asStateFlow()

    /** Clear the prefill request once the editor has consumed it. */
    fun consumePrefillNewConnection() {
        _prefillNewConnection.value = null
    }

    // Mirror the session-manager picker to a process-wide holder so the MCP
    // agent can observe it (get_pending_session_picker) and answer it
    // (answer_session_picker) without a human tap. Declared AFTER
    // _sessionSelection so the Main.immediate init collector never captures the
    // field before it's set (same gotcha as the _passwordFallback mirror).
    init {
        viewModelScope.launch {
            _sessionSelection.collect { sel ->
                sessionSelectionHolder.set(
                    sel?.let {
                        sh.haven.core.data.agent.PendingSessionSelection(
                            profileId = it.profileId,
                            sessionId = it.sessionId,
                            managerLabel = it.managerLabel,
                            sessionNames = it.sessionNames,
                            previousSessionNames = it.previousSessionNames,
                            suggestedNewName = it.suggestedNewName,
                        )
                    }
                )
            }
        }
    }

    fun onNavigated() {
        _navigateToTerminal.value = null
        _navigateToVnc.value = null
        _navigateToRdp.value = null
        _navigateToConnections.value = false
        _newSessionProfileId.value = null
    }

    /**
     * Consume just the desktop (VNC/RDP) navigation events. Collected at the
     * always-composed nav-host level so a desktop tab is created the instant the
     * connect emits, independent of which screen is on-screen — fixes Retry /
     * MCP connect_profile failing to open a tab when the Connections screen
     * isn't composed (#121). Separate from [onNavigated] so it doesn't clobber a
     * concurrent terminal navigation owned by the Connections screen.
     */
    fun onDesktopNavigated() {
        _navigateToVnc.value = null
        _navigateToRdp.value = null
    }

    /** Open a new terminal session on an already-connected profile. */
    fun openNewSession(profileId: String) {
        _newSessionProfileId.value = profileId
    }

    private val networkDiscovery = NetworkDiscovery(appContext)
    val discoveredHosts: StateFlow<List<DiscoveredHost>> = networkDiscovery.hosts
    init {
        // Feed the Tailscale-discovery preference (#654) into the probe: the
        // scan paths read the @Volatile flag, so flipping the setting takes
        // effect on the next scan without recreating the VM.
        viewModelScope.launch {
            preferencesRepository.tailscaleDiscoveryEnabled.collect {
                networkDiscovery.tailscaleDiscoveryEnabled = it
            }
        }
    }

    fun startNetworkDiscovery() {
        networkDiscovery.start()
        viewModelScope.launch { networkDiscovery.discoverTailscale() }
    }

    private val _subnetScanning = MutableStateFlow(false)
    val subnetScanning: StateFlow<Boolean> = _subnetScanning.asStateFlow()

    fun scanSubnet() {
        viewModelScope.launch {
            _subnetScanning.value = true
            networkDiscovery.scanSubnet()
            networkDiscovery.discoverTailscale()
            _subnetScanning.value = false
        }
    }


    // --- Scan the network behind a jump host (opt-in) ---

    private val _jumpScanning = MutableStateFlow(false)
    val jumpScanning: StateFlow<Boolean> = _jumpScanning.asStateFlow()

    /** Non-null when the last jump scan could not run, for the dialog to show. */
    private val _jumpScanError = MutableStateFlow<String?>(null)
    val jumpScanError: StateFlow<String?> = _jumpScanError.asStateFlow()

    fun clearJumpScanError() { _jumpScanError.value = null }

    /**
     * Sweep the jump host's own /24 for SSH, using its dynamic (SOCKS) forward
     * so the probes originate there instead of here.
     *
     * v1 requires the jump host to be connected already. Dialling it here would
     * mean driving the full connect path — host-key prompts, passphrases, MFA —
     * from inside the edit dialog, on top of whatever the user was typing. That
     * is a worse experience than asking them to connect it first, so this says
     * so plainly rather than doing it badly.
     */
    fun scanSubnetViaJump(jumpProfileId: String) {
        viewModelScope.launch {
            _jumpScanError.value = null
            val client = sshSessionManager.getSshClientForProfile(jumpProfileId)
            if (client == null) {
                _jumpScanError.value = appContext.getString(R.string.connections_jump_scan_not_connected)
                return@launch
            }

            _jumpScanning.value = true
            var socksPort = -1
            try {
                withContext(Dispatchers.IO) {
                    // Ephemeral port: nothing else needs to find this proxy, and
                    // a fixed one would collide with a user's own -D forward.
                    socksPort = client.setPortForwardingDynamic("127.0.0.1", 0)

                    val bases = discoverJumpSubnets(client)
                    if (bases.isEmpty()) {
                        _jumpScanError.value =
                            appContext.getString(R.string.connections_jump_scan_no_subnet)
                        return@withContext
                    }

                    val proxy = java.net.Proxy(
                        java.net.Proxy.Type.SOCKS,
                        java.net.InetSocketAddress("127.0.0.1", socksPort),
                    )
                    networkDiscovery.clearJumpHosts()
                    // Usually one; a multi-homed jump host is swept in turn.
                    bases.forEach { base -> networkDiscovery.scanSubnetVia(proxy, base) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Jump-host scan failed", e)
                _jumpScanError.value =
                    appContext.getString(R.string.connections_jump_scan_failed, e.message ?: "")
            } finally {
                if (socksPort > 0) {
                    runCatching { client.delPortForwardingDynamic("127.0.0.1", socksPort) }
                        .onFailure { Log.d(TAG, "SOCKS teardown: ${it.message}") }
                }
                _jumpScanning.value = false
            }
        }
    }

    /**
     * Ask the jump host which networks it is on. `ip -o -4 addr` is the only
     * thing consulted — the address Haven dials the jump host on is a poor
     * guess, because that is frequently a WAN address with no relationship to
     * the LAN behind it.
     */
    private suspend fun discoverJumpSubnets(client: sh.haven.core.ssh.SshConnection): List<String> {
        val addrs = runCatching {
            client.execCommand("ip -o -4 addr show scope global", timeoutMs = 8_000)
        }.getOrNull() ?: return emptyList()
        // The default route decides WHICH of those networks the host is really
        // on. Without it a developer box offers up docker0, virbr0 and a
        // compose bridge alongside the LAN, and the sweep quadruples into
        // networks nobody asked about.
        val route = runCatching {
            client.execCommand("ip -o -4 route show default", timeoutMs = 8_000)
        }.getOrNull()
        return NetworkDiscovery.parseSubnetBases(addrs.stdout, route?.stdout)
    }

    fun stopNetworkDiscovery() {
        networkDiscovery.stop() // also stops VM polling
    }

    fun saveConnection(profile: ConnectionProfile) {
        viewModelScope.launch {
            try {
                repository.save(profile)
            } catch (e: CredentialEncryption.KeystoreUnavailableException) {
                // #579: the save encrypts every credential field on the profile,
                // and a Keystore that will not serve the master key used to take
                // the process down from here — an uncaught throw on the main
                // thread the moment anyone typed a passphrase. Nothing is
                // written when this happens, so the stored profile is untouched
                // and the user is told rather than dropped back to a dead app.
                Log.e(TAG, "save failed: credential Keystore unavailable", e)
                _error.value = appContext.getString(R.string.connections_error_keystore_unavailable)
            } catch (e: Exception) {
                Log.e(TAG, "save failed", e)
                _error.value = e.message ?: "Could not save the connection"
            }
        }
    }

    /**
     * Persist an SSH profile alongside its embedded Cloudflare Tunnel
     * transport (GH #154). When [cfTunnel] is non-null, the tunnel blob is
     * upserted first (so we know its id), then the profile is saved with
     * `tunnelConfigId` pointing at it and port forced to 22 — the CF
     * tunnel dial ignores port anyway, but the resolver path expects a
     * sensible value. When [cfTunnel] is null, any existing embedded
     * tunnel owned by this profile is dropped so the user can switch back
     * to direct-dial or a shared tunnel cleanly.
     */
    fun saveProfileWithEmbeddedCloudflareTunnel(
        profile: ConnectionProfile,
        cfTunnel: EmbeddedCloudflareTunnelInput?,
    ) {
        viewModelScope.launch {
            if (cfTunnel != null) {
                val hostname = cfTunnel.hostname.trim()
                    .removePrefix("https://")
                    .removePrefix("http://")
                    .substringBefore('/')
                val blob = sh.haven.core.tunnel.CloudflareAccessConfigBlob(
                    hostname = hostname,
                    teamDomain = cfTunnel.teamDomain.trim()
                        .removePrefix("https://")
                        .removePrefix("http://"),
                    jwt = cfTunnel.jwt.trim(),
                    jwtExpiresAt = cfTunnel.jwtExpiresAt,
                    jumpDestination = cfTunnel.jumpDestination.trim(),
                )
                val tunnelId = tunnelConfigRepository.upsertEmbedded(
                    profileId = profile.id,
                    type = sh.haven.core.data.db.entities.TunnelConfigType.CLOUDFLARE_ACCESS.name,
                    label = "Cloudflare Tunnel: $hostname",
                    configText = blob.encode(),
                )
                repository.save(
                    profile.copy(
                        tunnelConfigId = tunnelId,
                        port = 22,
                        proxyType = null,
                        proxyHost = null,
                    ),
                )
            } else {
                tunnelConfigRepository.deleteByOwner(profile.id)
                repository.save(profile)
            }
        }
    }

    /**
     * One-shot fetch of an SSH profile's embedded Cloudflare Tunnel, if
     * any. The Connections screen calls this when opening the edit dialog
     * so the inline form can pre-populate. Returns the decrypted blob row;
     * null if the profile has no embedded tunnel.
     */
    suspend fun embeddedCloudflareTunnelFor(profileId: String): sh.haven.core.data.db.entities.TunnelConfig? =
        tunnelConfigRepository.findByOwner(profileId)

    /** Persist new sort order for a reordered list of top-level profile IDs. */
    fun createGroup(label: String) {
        viewModelScope.launch {
            connectionGroupDao.upsert(sh.haven.core.data.db.entities.ConnectionGroup(label = label))
        }
    }

    fun renameGroup(id: String, label: String) {
        viewModelScope.launch {
            connectionGroupDao.getById(id)?.let {
                connectionGroupDao.upsert(it.copy(label = label))
            }
        }
    }

    fun deleteGroup(id: String) {
        viewModelScope.launch {
            // Move connections in this group to ungrouped
            connections.value.filter { it.groupId == id }.forEach { profile ->
                repository.save(profile.copy(groupId = null))
            }
            connectionGroupDao.deleteById(id)
        }
    }

    fun toggleGroupCollapsed(id: String) {
        viewModelScope.launch {
            connectionGroupDao.getById(id)?.let {
                connectionGroupDao.updateCollapsed(id, !it.collapsed)
            }
        }
    }

    fun reorderGroups(orderedIds: List<String>) {
        viewModelScope.launch {
            orderedIds.forEachIndexed { index, id ->
                connectionGroupDao.updateSortOrder(id, index)
            }
        }
    }

    fun updateGroupSortOrder(id: String, sortOrder: Int) {
        viewModelScope.launch {
            connectionGroupDao.updateSortOrder(id, sortOrder)
        }
    }

    fun updateSortOrder(profileId: String, sortOrder: Int) {
        viewModelScope.launch {
            repository.updateSortOrder(profileId, sortOrder)
        }
    }

    fun updateGroupColor(id: String, colorTag: Int) {
        viewModelScope.launch {
            connectionGroupDao.getById(id)?.let {
                connectionGroupDao.upsert(it.copy(colorTag = colorTag))
            }
        }
    }

    /** Set (or clear, id=null) the group's default identity (#360). */
    fun setGroupIdentity(groupId: String, identityId: String?) {
        viewModelScope.launch {
            connectionGroupDao.getById(groupId)?.let {
                connectionGroupDao.upsert(it.copy(identityId = identityId))
            }
        }
    }

    /**
     * Returns true if the profile can connect without interactive dialogs.
     * VNC/RDP are excluded (they navigate to non-terminal screens).
     * SSH requires a saved password or SSH keys.
     */
    private fun canAutoConnect(profile: ConnectionProfile, keys: List<SshKey>): Boolean = when {
        profile.isVnc -> false
        profile.isRdp -> false
        else -> !profile.sshPassword.isNullOrBlank() || keys.isNotEmpty()
    }

    /**
     * Launch all auto-connectable profiles in a group concurrently.
     * Skips already-connected profiles and profiles requiring interactive auth.
     */
    fun launchGroup(groupId: String) {
        if (_groupLaunchState.value != null) return

        viewModelScope.launch {
            val allProfiles = connections.value.filter { it.groupId == groupId }
            val statuses = profileStatuses.value
            val keys = sshKeys.value

            val disconnected = allProfiles.filter { statuses[it.id] != ProfileStatus.CONNECTED }
            val launchable = disconnected.filter { canAutoConnect(it, keys) }
            val skipped = disconnected.size - launchable.size

            if (launchable.isEmpty()) return@launch

            _groupLaunchState.value = GroupLaunchState(
                groupId = groupId,
                total = launchable.size,
                completed = 0,
                succeeded = 0,
                skipped = skipped,
                connectingIds = launchable.map { it.id }.toSet(),
            )

            val deferreds = launchable.map { profile ->
                async {
                    val success = try {
                        connectSshSilent(profile)
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Group launch failed for ${LogRedact.of(profile.label)}: ${e.message}")
                        false
                    }
                    _groupLaunchState.update { state ->
                        state?.copy(
                            connectingIds = state.connectingIds - profile.id,
                            completed = state.completed + 1,
                            succeeded = state.succeeded + if (success) 1 else 0,
                        )
                    }
                    success
                }
            }

            val outcomes = deferreds.map { it.await() }
            val successCount = outcomes.count { it }

            if (successCount > 0) {
                launchable.firstOrNull()?.let { _navigateToTerminal.value = it.id }
            }

            delay(1500)
            _groupLaunchState.value = null
        }
    }

    fun reorderConnections(orderedIds: List<String>) {
        viewModelScope.launch {
            orderedIds.forEachIndexed { index, id ->
                repository.updateSortOrder(id, index)
            }
        }
    }

    fun duplicateConnection(id: String) {
        viewModelScope.launch {
            val profile = connections.value.find { it.id == id } ?: return@launch
            val copy = profile.copy(
                id = java.util.UUID.randomUUID().toString(),
                label = "${profile.label} (copy)",
                lastConnected = null,
            )
            repository.save(copy)
        }
    }

    fun deleteConnection(id: String) {
        viewModelScope.launch {
            sessionManagerRegistry.disconnectProfile(id)
            updateServiceNotification()
            repository.delete(id)
        }
    }

    /**
     * Try connecting with key auth (no password dialog). On failure, show password dialog.
     */
    /**
     * Dial [profile] as a one-off that never touches the database (#751).
     * The flag is set here rather than trusting the caller so no path can
     * accidentally dial a non-ephemeral profile through this entry point and
     * have it treated as saved (or vice versa). The PasswordDialog hides its
     * Remember tick for the resulting fallback prompt, and
     * [answerPasswordFallback] downgrades rememberPassword to null, so the
     * persist helpers never upsert the throwaway row.
     */
    fun connectEphemeral(profile: ConnectionProfile) {
        connect(profile.copy().also { it.ephemeral = true }, password = "")
    }

    fun connectWithKey(profile: ConnectionProfile) {
        if (profile.username.isBlank() && profile.isSsh) {
            // Route through the prompt dialog so the user can supply a username first.
            _passwordFallback.value = profile
            return
        }
        connect(profile, password = "", keyOnly = true)
    }

    /**
     * Clear the saved SSH password for [profileId] (#121b). Mirror of the
     * existing `repository.save(profile.copy(sshPassword = password))`
     * remember-password path at line 1810 — same DAO call, just nulled.
     */
    fun forgetPassword(profileId: String) {
        viewModelScope.launch {
            val profile = repository.getById(profileId) ?: return@launch
            repository.save(profile.copy(sshPassword = null))
        }
    }

    fun dismissPasswordFallback() {
        _passwordFallback.value = null
        // If the dialog was opened for a tunnel-mode dependent (#121a),
        // dismissing means "skip this connect" — clear the pending
        // dependent so the next password dialog (e.g. an explicit SSH
        // login) doesn't accidentally trigger a tunnel retry.
        _pendingTunnelDependent.value = null
        _pendingDependentPassword.value = null
        _pendingDependentRememberPassword.value = null
    }

    /**
     * Answer the active password / key-passphrase fallback prompt with
     * [password] (and optional [username] / [rememberPassword]) and re-drive
     * the stalled connect — the same logic the dialog's onConnect runs. Used by
     * both the UI dialog and the MCP `answer_auth_prompt` verb. No-op when no
     * prompt is pending.
     */
    fun answerPasswordFallback(
        password: String,
        username: String? = null,
        rememberPassword: Boolean = false,
    ) {
        val profile = _passwordFallback.value ?: return
        val pending = _pendingTunnelDependent.value
        if (pending != null) {
            // Tunnel-mode (#121a): the prompt was opened for a VNC/RDP
            // dependent whose jump host needs a password — route to the
            // tunnel-replay path.
            connectTunnelDependentAfterAuth(
                jumpProfile = profile,
                dependentProfile = pending,
                password = password,
                rememberPassword = rememberPassword,
                dependentPassword = _pendingDependentPassword.value ?: "",
                dependentRememberPassword = _pendingDependentRememberPassword.value,
            )
        } else {
            connect(
                profile,
                password,
                // An ephemeral one-off (#751) has no row to remember into —
                // null keeps the persist helpers from upserting the
                // throwaway profile into the database.
                rememberPassword = if (profile.ephemeral) null else rememberPassword,
                usernameOverride = username,
            )
        }
        dismissPasswordFallback()
    }

    /**
     * Returns the jump host profile if it needs a password prompt before
     * the tunnel auto-connect can proceed (#121a). "Needs a prompt" means
     * the profile has no saved password, no assigned key, and no SSH key
     * in the keystore — software, FIDO or security-chip — that JSch could
     * try as a fallback. Returns null when at least one credential path is
     * available — the existing silent auto-connect handles those.
     */
    // internal for unit test (FIDO-jump-host-no-prompt regression, #286).
    internal suspend fun jumpHostNeedsPasswordPrompt(
        jumpProfileId: String,
    ): ConnectionProfile? {
        // A live jump session already exists → reuse it silently, no prompt.
        // Without this, the post-auth replay of connectVnc/connectRdp
        // re-prompts forever when the entered password wasn't saved ("remember"
        // off): the prompt check fires before connectJumpHost's connected-session
        // reuse, so it never sees that auth already succeeded — an endless
        // password loop, and cancelling left the now-open SSH session orphaned
        // (#121, KoriKraut). Also covers the case where the user already opened
        // the jump host in a Terminal tab.
        val alreadyConnected = sshSessionManager.getSessionsForProfile(jumpProfileId)
            .any { it.status == SshSessionManager.SessionState.Status.CONNECTED }
        if (alreadyConnected) return null

        val jp = repository.getById(jumpProfileId) ?: return null
        if (!jp.sshPassword.isNullOrBlank()) return null
        // "Use password only" — keys (explicit or keystore) are never offered,
        // so the prompt is the only credential path; fire it (#121).
        if (jp.ignoreSavedKeys) return jp
        if (jp.keyId != null) return null
        // connectJumpHost wires the FIDO authenticator + live prompter and
        // resolves the profile's full auth spec (#286), so recognise the
        // credentials it can complete WITHOUT a typed jump-host password and
        // don't pre-empt them with a prompt.
        val specs = jp.authMethodSpecs
        // An explicit spec key, or a live keyboard-interactive / TOTP round.
        if (specs.any {
                it is ConnectionProfile.AuthMethodSpec.KeyboardInteractive ||
                    it is ConnectionProfile.AuthMethodSpec.Totp ||
                    (it is ConnectionProfile.AuthMethodSpec.Key && it.keyId != null)
            }
        ) return null
        // The same pool shape the auth path reads (getAllDecryptedDetailed,
        // #559): declined keys are none of our business here, but the
        // hardware pools classify correctly instead of collapsing to bytes.
        val detailed = sshKeyRepository.getAllDecryptedDetailed()
        // An offer-enabled security-chip key joins the auto-offer pool via
        // KeystoreSshIdentity (resolveAnyUsableKeys appends it after the
        // software bundle), so it is a credential path the silent
        // auto-connect can complete — the #531 sibling: counting only the
        // byte-keyed pools pre-prompted for a password the connect never
        // needed.
        if (detailed.hardware.any { it.enabledForAuth }) return null
        val keys = detailed.keys.filter { it.enabledForAuth }
        // An offer-enabled sk-key: FIDO needs only a touch, and since #531
        // resolveAuthMethod auto-offers the FIDO pool with no spec needed —
        // so an enrolled key suppresses the prompt unconditionally (#286
        // covered only the explicit "Any hardware key" spec).
        if (keys.any { it.keyType.startsWith("sk-") }) return null
        // A usable unencrypted software key also suppresses the prompt; a
        // provider-held key doesn't — `resolveAnyUsableKeys` never offers one,
        // so counting it here suppressed the prompt and then had nothing to
        // connect with.
        if (keys.any { !it.isEncrypted && holdsLoadablePrivateKey(it.keyType) }) return null
        return jp
    }

    /**
     * After the password dialog resolves for a tunnel-mode prompt, save
     * the password (if requested) and replay the dependent connect.
     * Calling [connectJumpHost] with the entered password establishes
     * the SSH session up front; the subsequent dependent connect then
     * finds and reuses that session via the existing-session lookup at
     * [connectJumpHost]'s `firstOrNull { CONNECTED }` branch.
     */
    fun connectTunnelDependentAfterAuth(
        jumpProfile: ConnectionProfile,
        dependentProfile: ConnectionProfile,
        password: String,
        rememberPassword: Boolean?,
        // #729: a terminal-SSH dependent re-drives with the password (and
        // remember tick) its own dialog already collected; the jump failure
        // happened before the dependent's auth ever ran.
        dependentPassword: String = "",
        dependentRememberPassword: Boolean? = null,
    ) {
        viewModelScope.launch {
            try {
                if (rememberPassword == true && password.isNotBlank()) {
                    repository.save(jumpProfile.copy(sshPassword = password))
                }
                connectJumpHost(
                    jumpProfile.id,
                    password,
                    tunnelOwnerProfileId = dependentProfile.id,
                )
                connect(
                    dependentProfile,
                    password = dependentPassword,
                    rememberPassword = dependentRememberPassword,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Tunnel-auth retry failed: ${e.message}", e)
                // A mistyped password lands here — re-open the prompt (user-driven,
                // cancellable) instead of dead-ending on a raw JSch message.
                // #729: keep the dependent's already-typed password queued
                // across retries — a repeated jump-leg failure must not lose
                // what the target's dialog collected the first time.
                handleTunnelJumpFailure(
                    e,
                    dependentProfile,
                    jumpProfile.id,
                    dependentPassword = dependentPassword,
                    dependentRememberPassword = dependentRememberPassword,
                )
            }
        }
    }

    /**
     * Surface a jump-host connect failure — the VNC/RDP-over-SSH tunnel
     * paths (#121a) and, since #729, a terminal-SSH dependent whose jump leg
     * failed. On an authentication failure with a recoverable credential path,
     * re-open the password prompt wired to [connectTunnelDependentAfterAuth] so
     * the user can enter or retry a password — mirroring the direct SSH tap's
     * key→password fallback — instead of dead-ending on a raw "Auth cancel for
     * methods 'password'" message (#121). Network and other failures fall
     * through to a clean [_error] string. Classification matches
     * [connectSsh]'s catch block.
     */
    // internal for unit test (#559: a declined key unlock must not surface
    // as an auth failure and re-open the password prompt).
    internal suspend fun handleTunnelJumpFailure(
        e: Exception,
        dependentProfile: ConnectionProfile,
        sshProfileId: String,
        // #729: a terminal-SSH dependent routes its jump failure through the
        // same replay path as the tunnel flows; its [_error] label differs.
        errorPrefix: String = "SSH tunnel",
        dependentPassword: String = "",
        dependentRememberPassword: Boolean? = null,
    ) {
        if (isKeyRefusal(e)) {
            // Same rule as connectSsh (#559): the user declined a key
            // ceremony on the jump host (software-key unlock, or the
            // security-chip sign prompt). The message contains
            // "authentication", so the classifier below would read it as an
            // auth failure and re-open the password prompt — offering another
            // way in after a refusal. Report the decline and stop.
            _error.value = (e.message ?: "").ifBlank { "Key unlock was declined" }
            return
        }
        val msg = e.message ?: ""
        val isNetworkError = e.isSshNetworkError()
        val isAuthMessage = !isNetworkError && (
            msg.contains("Auth fail", ignoreCase = true) ||
            msg.contains("Auth cancel", ignoreCase = true) ||
            msg.contains("authentication", ignoreCase = true) ||
            msg.contains("publickey", ignoreCase = true) ||
            // #729 follow-up: JSch throws "Incorrect passphrase provided."
            // synchronously from addIdentity when an encrypted key's stored
            // passphrase is wrong or stale (e.g. after a backup restore). The
            // direct path classifies it as recoverable and prompts (#292);
            // without this the jump leg dead-ended on the raw JSch string
            // instead of opening the jump's own prompt.
            msg.contains("passphrase", ignoreCase = true)
        )
        val jumpProfile = if (isAuthMessage) repository.getById(sshProfileId) else null
        if (jumpProfile != null) {
            Log.d(TAG, "Jump-host auth failed; prompting for password (${LogRedact.of(jumpProfile.label)})")
            _pendingTunnelDependent.value = dependentProfile
            _pendingDependentPassword.value = dependentPassword
            _pendingDependentRememberPassword.value = dependentRememberPassword
            _passwordFallback.value = jumpProfile
        } else {
            _error.value = "${errorPrefix}: ${msg.ifBlank { "connection failed" }}"
        }
    }

    fun connect(
        profile: ConnectionProfile,
        password: String,
        keyOnly: Boolean = false,
        rememberPassword: Boolean? = null,
        usernameOverride: String? = null,
        sessionName: String? = null,
        startupCommand: String? = null,
    ) {
        // Identity resolution (#360): for SSH-family profiles, substitute the
        // effective identity's username/password/key just before dialing, so
        // the whole downstream auth machinery sees ordinary inline
        // credentials. A no-op (returns the profile unchanged) when no
        // identity is assigned or resolvable, so existing per-host
        // credentials behave exactly as before. Needs a DAO read → suspend.
        if (profile.isSsh) {
            viewModelScope.launch {
                val resolved = sshIdentityRepository.applyTo(profile)
                // If the caller didn't already carry a typed password, adopt
                // the identity's (mirrors the jump-host merge idiom).
                val effectivePassword =
                    if (password.isEmpty()) resolved.sshPassword.orEmpty() else password
                connectAfterIdentity(resolved, effectivePassword, keyOnly, rememberPassword, usernameOverride, sessionName, startupCommand)
            }
            return
        }
        connectAfterIdentity(profile, password, keyOnly, rememberPassword, usernameOverride, sessionName, startupCommand)
    }

    /** Post-identity dispatch to [connectInner]. */
    private fun connectAfterIdentity(
        profile: ConnectionProfile,
        password: String,
        keyOnly: Boolean,
        rememberPassword: Boolean?,
        usernameOverride: String?,
        sessionName: String?,
        startupCommand: String? = null,
    ) {
        connectInner(profile, password, keyOnly, rememberPassword, usernameOverride, sessionName, startupCommand)
    }

    private fun connectInner(
        profile: ConnectionProfile,
        password: String,
        keyOnly: Boolean = false,
        rememberPassword: Boolean? = null,
        usernameOverride: String? = null,
        sessionName: String? = null,
        startupCommand: String? = null,
    ) {
        if (profile.isVnc) {
            connectVnc(profile)
            return
        }
        if (profile.isRdp) {
            connectRdp(profile, password)
            return
        }
        // SSH-family: if the saved profile has no username, the user must supply one
        // at connect time via the prompt dialog. Resolve here, then thread the runtime
        // value through to the connect routines as a per-call override — the persisted
        // profile must not be mutated, since the whole point of leaving it blank is to
        // reuse one connection entry for many users.
        val runtimeUsername = usernameOverride?.takeIf { it.isNotBlank() }
        if (profile.username.isBlank() && runtimeUsername == null) {
            _passwordFallback.value = profile
            return
        }
        connectSsh(profile, password, keyOnly, rememberPassword, usernameOverride = runtimeUsername, preselectedSessionName = sessionName)
    }

    private fun connectVnc(profile: ConnectionProfile) {
        val host = profile.host
        val port = profile.vncPort ?: profile.port
        val password = profile.vncPassword
        val username = profile.vncUsername
        viewModelScope.launch {
            repository.markConnected(profile.id)
            val sshProfileId = profile.vncSshProfileId
            if (profile.vncSshForward && sshProfileId != null) {
                // If the jump host has no saved credential and no key
                // JSch could try, prompt for a password up front instead
                // of silently failing the auto-connect (#121a).
                val needsPrompt = jumpHostNeedsPasswordPrompt(sshProfileId)
                if (needsPrompt != null) {
                    _pendingTunnelDependent.value = profile
                    _passwordFallback.value = needsPrompt
                    return@launch
                }
                // Auto-connect SSH tunnel host (reuses existing session if
                // available), mirroring the RDP path. The VNC screen itself
                // uses this sshSessionId to open the local forward.
                try {
                    _connectingProfileId.value = profile.id
                    val (sshSessionId, _) = connectJumpHost(
                        sshProfileId, "", tunnelOwnerProfileId = profile.id,
                    )
                    _navigateToVnc.value = VncNavigation(
                        host, port, password, username,
                        sshForward = true,
                        sshSessionId = sshSessionId,
                        profileId = profile.id,
                        colorDepth = profile.vncColorDepth,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect SSH tunnel host for VNC", e)
                    handleTunnelJumpFailure(e, profile, sshProfileId)
                } finally {
                    _connectingProfileId.value = null
                }
            } else {
                _navigateToVnc.value = VncNavigation(
                    host, port, password, username,
                    sshForward = profile.vncSshForward,
                    profileId = profile.id,
                    colorDepth = profile.vncColorDepth,
                )
            }
        }
    }

    private fun connectRdp(profile: ConnectionProfile, password: String) {
        val host = profile.host
        val port = profile.rdpPort
        val username = profile.rdpUsername ?: profile.username
        val rdpPassword = password.ifBlank { profile.rdpPassword ?: "" }
        val domain = profile.rdpDomain ?: ""
        viewModelScope.launch {
            repository.markConnected(profile.id)
            val sshProfileId = profile.rdpSshProfileId
            if (profile.rdpSshForward && sshProfileId != null) {
                val needsPrompt = jumpHostNeedsPasswordPrompt(sshProfileId)
                if (needsPrompt != null) {
                    _pendingTunnelDependent.value = profile
                    _passwordFallback.value = needsPrompt
                    return@launch
                }
                // Auto-connect SSH tunnel host (reuses existing session if available)
                try {
                    _connectingProfileId.value = profile.id
                    // Pass empty password — SSH profile uses its own auth (key or password)
                    val (sshSessionId, _) = connectJumpHost(
                        sshProfileId, "", tunnelOwnerProfileId = profile.id,
                    )
                    _navigateToRdp.value = RdpNavigation(
                        host, port, username, rdpPassword, domain,
                        sshForward = true,
                        sshSessionId = sshSessionId,
                        profileId = profile.id,
                        useNla = profile.rdpUseNla,
                        colorDepth = profile.rdpColorDepth,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect SSH tunnel host for RDP", e)
                    handleTunnelJumpFailure(e, profile, sshProfileId)
                } finally {
                    _connectingProfileId.value = null
                }
            } else {
                _navigateToRdp.value = RdpNavigation(host, port, username, rdpPassword, domain, profile.rdpSshForward, profileId = profile.id, useNla = profile.rdpUseNla, colorDepth = profile.rdpColorDepth)
            }
        }
    }

    /**
     * Persist or clear the remembered SSH password/passphrase after a successful
     * connect. Skips multi-user profiles (a runtime-prompted username) — persisting
     * one user's password would bleed it into other sessions. Called from both the
     * direct connect path and the session-picker path (#290), since the latter
     * returns before the inline save block would otherwise run.
     */
    // internal for unit test (#582: a failed credential save must not escape
    // and cancel a session the server has already authenticated).
    internal suspend fun persistRememberedPassword(
        profile: ConnectionProfile,
        password: String,
        rememberPassword: Boolean?,
        multiUserProfile: Boolean,
    ) {
        try {
            persistRememberedPasswordOrThrow(profile, password, rememberPassword, multiUserProfile)
        } catch (e: Exception) {
            // #582: this runs AFTER the server has authenticated us and the
            // session channel is open. Letting it throw unwound connectSsh,
            // which reported a failed connection and disconnected a session the
            // server had already recorded as opened — the user saw a failure
            // and no terminal, and the server was left holding an orphan.
            //
            // Remembering a password is a convenience. It cannot be allowed to
            // cancel a login that already worked, whatever the reason it fails
            // (a Keystore that will not serve its key is only the reported one).
            Log.e(TAG, "could not persist the remembered credential; keeping the session", e)
            _error.value = appContext.getString(R.string.connections_error_credential_not_saved)
        }
    }

    /** The actual persistence. Throws; [persistRememberedPassword] is the guard. */
    private suspend fun persistRememberedPasswordOrThrow(
        profile: ConnectionProfile,
        password: String,
        rememberPassword: Boolean?,
        multiUserProfile: Boolean,
    ) {
        if (multiUserProfile) return

        // For an encrypted-key profile the entered secret is the KEY
        // PASSPHRASE, not the host password — resolveExplicitKey feeds
        // `password` to JSch as the passphrase. Persist it on the KEY
        // (#290 store) rather than profile.sshPassword. Storing it on the
        // profile mislabels it "<host> — SSH password" in the credential
        // audit (#290 follow-up) and duplicates the passphrase into every
        // host that shares the key; the per-key store is reused across all
        // of them and labelled correctly. resolveExplicitKey already reads
        // getStoredPassphrase when no passphrase is supplied, so this slots
        // straight into the existing auto-unlock path.
        val keyId = profile.keyId?.takeIf { !profile.ignoreSavedKeys }
        val encryptedKeyId = keyId
            ?.let { sshKeyRepository.getById(it) }
            ?.takeIf { it.isEncrypted }
            ?.let { keyId }
        if (encryptedKeyId != null) {
            if (rememberPassword == true && password.isNotBlank()) {
                sshKeyRepository.setStoredPassphrase(encryptedKeyId, password)
            } else if (rememberPassword == false) {
                sshKeyRepository.setStoredPassphrase(encryptedKeyId, null)
            }
            // Migrate away from the conflated slot: a passphrase saved here
            // by an older build (or fed in as a "host password") would keep
            // showing as an SSH password in the audit.
            if (rememberPassword != null && profile.sshPassword != null) {
                repository.save(profile.copy(sshPassword = null))
            }
            return
        }

        if (rememberPassword == true && password.isNotBlank()) {
            repository.save(profile.copy(sshPassword = password))
        } else if (rememberPassword == false && profile.sshPassword != null) {
            repository.save(profile.copy(sshPassword = null))
        }
    }

    // internal for unit test (#559: a declined key unlock must not surface
    // as an auth failure and pop the password fallback).
    internal fun connectSsh(
        profile: ConnectionProfile,
        password: String,
        keyOnly: Boolean,
        rememberPassword: Boolean? = null,
        usernameOverride: String? = null,
        preselectedSessionName: String? = null,
        allowRediscovery: Boolean = true,
    ) {
        val effectiveUsername = usernameOverride?.takeIf { it.isNotBlank() } ?: profile.username
        viewModelScope.launch {
            _connectingProfileId.value = profile.id
            _error.value = null

            val verboseEnabled = preferencesRepository.verboseLoggingEnabled.first()
            val verboseLogger = if (verboseEnabled) SshVerboseLogger() else null
            // Reuse a live SSH connection to this profile instead of dialing a
            // second flow. A 2nd dial over a WireGuard/Tailscale tunnel can't be
            // serviced by some peers (a FRITZ!Box drops the 2nd concurrent
            // WG→LAN flow), and one SSH connection carrying several tmux/zellij
            // sessions is the canonical model anyway. Scoped to tunnel-routed
            // profiles so direct connections keep independent-per-tab semantics.
            // awaitReusableClient (not getSshClientForProfile) also waits for an
            // in-flight connect to the same profile — closing the app-launch race
            // where this restore runs before the MCP carrier reaches CONNECTED.
            val reuseClient = if (profile.tunnelConfigId != null) {
                sshSessionManager.awaitReusableClient(profile.id)
            } else null
            val client = reuseClient ?: clientFactory(profile).apply {
                fidoAuthenticator = this@ConnectionsViewModel.fidoAuthenticator
                hardwareKeySigner = this@ConnectionsViewModel.keystoreSshKeySigner
                openKeychainClients = OpenKeychainClientFactory.from(appContext)
                this.verboseLogger = verboseLogger
            }
            val sessionId = sshSessionManager.registerSession(profile.id, profile.label, client)

            // Track whether we auto-created the jump session (for cleanup on failure)
            var autoCreatedJumpSessionId: String? = null
            var isFidoAuth = false
            try {
                // If profile has a jump host, establish that connection first.
                // Skipped when reusing — the live client already rode its jump.
                val jumpProfileId = if (reuseClient == null) profile.jumpProfileId else null
                val jumpSessionId = if (jumpProfileId != null) {
                    try {
                        // #729 follow-up: pass "", not the target's typed
                        // password. connectJumpHost threads this value into
                        // resolveAuthMethods, which reuses it as the KEY
                        // passphrase too — so handing it the TARGET's password
                        // made a key-auth jump try to decrypt its own
                        // passphrase-protected key with the wrong secret and
                        // fail "Incorrect passphrase provided." before the
                        // jump's own prompt could appear (the #381 borrow rule
                        // only applies to the jump's saved login password,
                        // never to a dependent's credential). "" lets the jump
                        // resolve its OWN credentials — stored key passphrase,
                        // or its saved password for a password-auth jump — and
                        // a failure then routes to the jump's own prompt below.
                        val (jid, reused) = connectJumpHost(jumpProfileId, "")
                        if (!reused) autoCreatedJumpSessionId = jid
                        jid
                    } catch (e: Exception) {
                        // #729: the jump leg failed auth. It used to fall
                        // through to this catch block's generic handling, which
                        // opened the password dialog against the TARGET profile
                        // — remembering there saved the jump's password onto the
                        // target, so the jump hop kept prompting on every
                        // connect ("the prompt appears even when Remember
                        // password is enabled"). Route it through the #121a
                        // replay instead: prompt for the jump profile's OWN
                        // credentials (remembered there), then re-drive this
                        // dependent connect with the password already typed for
                        // it.
                        // #757: the dependent session was already registered
                        // (CONNECTING) above. The replay re-drives connect()
                        // from scratch, which registers a fresh one — so this
                        // one must be taken out here, or it leaks as a phantom
                        // "active" session (the ongoing notification counts
                        // CONNECTING, and registerSession's reaper only takes
                        // DISCONNECTED/ERROR, so retries piled up). The
                        // network-error branch already did this; the auth
                        // branch inherited the omission from 5d682a40b.
                        sshSessionManager.updateStatus(sessionId, SshSessionManager.SessionState.Status.ERROR)
                        sshSessionManager.removeSession(sessionId)
                        if (!e.isSshNetworkError()) {
                            handleTunnelJumpFailure(
                                e,
                                dependentProfile = profile,
                                sshProfileId = jumpProfileId,
                                errorPrefix = "Jump host",
                                dependentPassword = password,
                                dependentRememberPassword = rememberPassword,
                            )
                        } else {
                            _error.value = "Jump host: ${e.message?.ifBlank { "connection failed" }}"
                        }
                        return@launch
                    }
                } else null

                if (jumpSessionId != null) {
                    sshSessionManager.setJumpSessionId(sessionId, jumpSessionId)
                }

                val sshSessionMgr = withContext(Dispatchers.IO) {
                    // A profile RemoteCommand is itself the program to run, so
                    // session-manager discovery and post-login keystrokes must
                    // not prepend another shell command to it.
                    val sshSessionMgr = if (profile.remoteCommand.isNullOrBlank()) {
                        resolveSessionManager(profile)
                    } else {
                        SessionManager.NONE
                    }
                    val cmdOverride = profile.sessionCommandOverride?.takeIf { it.isNotBlank() }
                    ?: preferencesRepository.sessionCommandOverride.first()
                    if (reuseClient != null) {
                        // Reuse path: the SSH connection is already up, so no
                        // dial, auth, host-key check or reconnect provider — the
                        // primary session owns all of those. Store a bookkeeping
                        // config with reconnect OFF (the primary's reconnect
                        // re-establishes the shared client) and a fresh empty
                        // Password so tearDown's auth-zeroing can never reach
                        // back into the primary's live credential.
                        Log.i(TAG, "Reusing live SSH connection to ${LogRedact.of(profile.label)} for a second session (no new dial)")
                        val config = ConnectionConfig(
                            host = profile.host,
                            port = profile.port,
                            username = effectiveUsername,
                            authMethod = ConnectionConfig.AuthMethod.Password(""),
                            remoteCommand = profile.remoteCommand,
                            requestPty = profile.requestPty,
                            reconnectPolicy = ConnectionConfig.ReconnectPolicy(autoReconnect = false),
                        )
                        sshSessionManager.storeConnectionConfig(sessionId, config, sshSessionMgr, cmdOverride, profile.postLoginCommand, profile.postLoginBeforeSessionManager)
                    } else {
                        val authMethod = resolveAuthMethods(profile, password)
                        isFidoAuth = authMethod.containsFidoKey()
                        val config = ConnectionConfig(
                            host = profile.host,
                            port = profile.port,
                            username = effectiveUsername,
                            authMethod = authMethod,
                            sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
                            forwardAgent = profile.forwardAgent,
                            remoteCommand = profile.remoteCommand,
                            requestPty = profile.requestPty,
                            addressFamily = profile.addressFamilyForSsh,
                            bindAddress = profile.bindAddress,
                            agentIdentities = agentIdentitiesFor(profile),
                            reconnectPolicy = profile.reconnectPolicy,
                        )

                        // Proxy precedence: jump host > Route-through (tunnel /
                        // SOCKS / HTTP). Jump host needs a live SSH session so it
                        // stays inline; everything else delegates to TunnelResolver.
                        val proxy = if (jumpSessionId != null) {
                            sshSessionManager.createProxyJump(jumpSessionId)
                                ?: throw Exception("Jump host session not usable for tunneling")
                        } else {
                            tunnelResolver.havenProxy(profile)
                        }
                        Log.d(TAG, "Connecting to ${LogRedact.host(config.host, config.port)} (proxy=${proxy != null})")
                        try {
                            val hostKeyEntry = client.connect(
                                config,
                                proxy = proxy,
                                keyboardInteractivePrompter = keyboardInteractivePrompter,
                                totpCodeProvider = buildTotpCodeProvider(profile),
                                confirmOtp = profile.totpConfirmBeforeSend,
                            )
                            runTofuVerification(hostKeyEntry, clientToDisconnectOnReject = client, autoAccept = profile.usbDriveSerial != null)
                        } catch (e: HostKeyAuthFailure) {
                            // KEX succeeded but auth failed — still run the TOFU
                            // prompt on first contact (Haven#75 follow-up) then
                            // rethrow the underlying JSch auth error so the catch
                            // block below can trigger the password-fallback UX.
                            runTofuVerification(e.hostKey, clientToDisconnectOnReject = null, autoAccept = profile.usbDriveSerial != null)
                            throw e.cause ?: e
                        }

                        sshSessionManager.storeConnectionConfig(sessionId, config, sshSessionMgr, cmdOverride, profile.postLoginCommand, profile.postLoginBeforeSessionManager)
                        // Re-resolve the profile's tunnel/proxy on auto-reconnect.
                        // Jump-host sessions reconnect via createProxyJump instead,
                        // so only register for the non-jump (havenProxy) case.
                        if (jumpSessionId == null) {
                            sshSessionManager.setReconnectProxyProvider(sessionId) {
                                tunnelResolver.havenProxy(profile)
                            }
                        }
                    }
                    sshSessionMgr
                }

                // Drain verbose log now (before session picker might return from the coroutine)
                verboseLogger?.drain()?.let { pendingVerboseLogs[sessionId] = it }

                // Agent-supplied session name (connect_profile sessionName): attach
                // or create it directly and skip the interactive picker, which the
                // agent can't tap. tmux/zellij "new-session -A -s <name>" attaches
                // if the session exists and creates it otherwise.
                if (preselectedSessionName != null) {
                    sshSessionManager.setChosenSessionName(sessionId, preselectedSessionName)
                }

                // If session manager supports listing, check for existing sessions
                val listCmd = sshSessionMgr.listCommand
                if (preselectedSessionName == null && listCmd != null) {
                    val existingSessions = withContext(Dispatchers.IO) {
                        try {
                            val result = client.execCommand(listCmd)
                            if (result.exitStatus == 0) {
                                SessionManager.parseSessionList(sshSessionMgr, result.stdout)
                            } else emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    if (existingSessions.isNotEmpty()) {
                        // Parse previously open sessions from the pipe-delimited lastSessionName
                        val previousNames = profile.lastSessionName
                            ?.split("|")
                            ?.filter { it.isNotBlank() && it in existingSessions }
                            ?: emptyList()
                        // #290: auth already succeeded (the list command ran above), so
                        // persist the remembered passphrase NOW — the session-picker early
                        // return below exits before the post-finishConnect save block. If the
                        // user later cancels the picker, the saved passphrase is still valid
                        // and the "remember" intent was already given, so keeping it is fine.
                        persistRememberedPassword(profile, password, rememberPassword, usernameOverride != null)
                        _sessionSelection.value = SessionSelection(
                            sessionId = sessionId,
                            profileId = profile.id,
                            managerLabel = sshSessionMgr.label,
                            sessionNames = existingSessions,
                            previousSessionNames = previousNames,
                            manager = sshSessionMgr,
                            suggestedNewName = generateUniqueSessionName(profile.label, existingSessions),
                        )
                        _connectingProfileId.value = null
                        return@launch // UI will call onSessionSelected() to continue
                    }
                }

                // No existing sessions or no session manager — proceed directly
                finishConnect(sessionId, profile.id, verboseLog = verboseLogger?.drain())

                // Save or clear remembered password after successful connect.
                persistRememberedPassword(profile, password, rememberPassword, usernameOverride != null)

                // Auto-deploy SSH key for VM connections after first password connect
                if (password.isNotBlank()) {
                    maybeAutoDeployKey(profile, password)
                }
            } catch (e: Exception) {
                Log.e(TAG, "connectSsh failed for ${LogRedact.of(profile.label)}: ${e.message}", e)
                connectionLogRepository.logEvent(profile.id, ConnectionLog.Status.FAILED, details = e.message, verboseLog = verboseLogger?.drain())
                sshSessionManager.updateStatus(sessionId, SshSessionManager.SessionState.Status.ERROR)
                sshSessionManager.removeSession(sessionId)
                // Clean up auto-created jump session if the final host failed
                autoCreatedJumpSessionId?.let { jid ->
                    Log.d(TAG, "Cleaning up auto-created jump session $jid")
                    sshSessionManager.removeSession(jid)
                }
                val msg = e.message ?: ""
                val isNetworkError = e.isSshNetworkError()
                val isAuthMessage = !isNetworkError && (
                    msg.contains("Auth fail", ignoreCase = true) ||
                    msg.contains("Auth cancel", ignoreCase = true) ||
                    msg.contains("authentication", ignoreCase = true) ||
                    msg.contains("publickey", ignoreCase = true)
                )
                // #292: JSch throws "Incorrect passphrase provided." synchronously
                // from addIdentity when an assigned encrypted key's passphrase
                // (a saved host password fed in as the passphrase, blank, or a
                // stale stored #290 value) fails to decrypt it. That string is
                // not an "Auth fail"-style message and the saved-password tap
                // path runs with keyOnly=false, so without this branch it
                // dead-ends to an error toast instead of the passphrase prompt.
                val isPassphraseError = !isNetworkError &&
                    msg.contains("passphrase", ignoreCase = true)
                val isAuthError = keyOnly && isAuthMessage
                if (isKeyRefusal(e)) {
                    // The user was asked for a key's biometric ack and said no
                    // (or could not be asked) — a software-key unlock decline,
                    // or the security-chip sign ceremony being refused/timing
                    // out/failing on invalidation. Say so and stop.
                    //
                    // This branch has to come first because the message
                    // contains the word "authentication", which the classifier
                    // below reads as an auth FAILURE and answers with the
                    // password-fallback prompt. Offering another way in is a
                    // politer version of the #559 bug itself: the answer to a
                    // refusal is not "try a different credential".
                    _error.value = msg.ifBlank { "Key unlock was declined" }
                } else if (isFidoAuth && (isAuthError || (keyOnly && !isNetworkError))) {
                    val fidoDetail = fidoAuthenticator.lastAssertionError
                    _error.value = if (fidoDetail != null) {
                        "Security key: $fidoDetail"
                    } else {
                        msg.ifBlank { "Security key authentication failed" }
                    }
                } else if (isAuthError) {
                    Log.d(TAG, "Auth failed, showing password fallback for ${LogRedact.of(profile.label)}")
                    _passwordFallback.value = profile
                } else if (keyOnly && !isNetworkError && msg.isBlank()) {
                    _passwordFallback.value = profile
                } else if (isPassphraseError) {
                    Log.d(TAG, "Encrypted-key passphrase failed — prompting (#292)")
                    _passwordFallback.value = profile
                } else if (!keyOnly && isAuthMessage) {
                    _error.value = "Authentication failed — check username and password"
                } else {
                    // #376: a network error on a private address may just mean
                    // DHCP moved the device. If exactly one machine on the
                    // subnet presents this profile's known host key, follow it
                    // and retry once (allowRediscovery=false stops recursion).
                    val newHost = if (isNetworkError && allowRediscovery) {
                        runCatching { hostRediscovery.rediscover(profile) }.getOrNull()
                    } else null
                    if (newHost != null) {
                        userMessageBus.emit(
                            sh.haven.core.data.message.UserMessage(
                                "${profile.label}: address updated ${profile.host} → $newHost (matched host key)",
                                sh.haven.core.data.message.UserMessage.Severity.INFO,
                            ),
                        )
                        connectSsh(
                            profile.copy(host = newHost), password, keyOnly, rememberPassword,
                            usernameOverride, preselectedSessionName, allowRediscovery = false,
                        )
                    } else {
                        _error.value = msg.ifBlank { "Connection failed" }
                    }
                }
            } finally {
                _connectingProfileId.value = null
            }
        }
    }


    /**
     * Called from the session picker dialog when user selects a session.
     * @param sessionName The name to attach to, or null to create a new session.
     */
    /**
     * Restore multiple previous sessions by opening one tab per session name.
     * The first session uses the existing SSH connection; additional sessions
     * ride the same connection as extra shell channels via
     * [sh.haven.core.ssh.SshSessionAttacher] — one SSH connection carrying
     * several multiplexer sessions, the same model as a manual new tab —
     * instead of dialing (and re-authenticating) once per name.
     */
    fun restorePreviousSessions(sessionId: String, sessionNames: List<String>) {
        val sel = _sessionSelection.value
        _sessionSelection.value = null
        if (sel == null || sessionNames.isEmpty()) return

        val profileId = sel.profileId
        viewModelScope.launch {
            _connectingProfileId.value = profileId
            try {
                // First session uses the already-open SSH connection
                var anyReady = false
                sshSessionManager.setChosenSessionName(sessionId, sessionNames.first())
                anyReady = finishConnect(sessionId, profileId, silent = true) || anyReady

                // A failed name doesn't abort the rest — restore what we can
                // and report the last failure.
                var lastFailure: String? = null
                for (name in sessionNames.drop(1)) {
                    when (val r = sshSessionAttacher.ensureAttached(profileId, name)) {
                        is sh.haven.core.ssh.SshSessionAttacher.Result.Attached,
                        is sh.haven.core.ssh.SshSessionAttacher.Result.AlreadyLive,
                        -> anyReady = true
                        is sh.haven.core.ssh.SshSessionAttacher.Result.NoLiveConnection ->
                            lastFailure = "connection is not up"
                        is sh.haven.core.ssh.SshSessionAttacher.Result.Failed ->
                            lastFailure = r.message
                    }
                }
                lastFailure?.let { _error.value = "Restore failed: $it" }
                // Navigate only if at least one session reached a live terminal
                // (a host with no session manager closes the shell immediately).
                if (anyReady) _navigateToTerminal.value = profileId
            } catch (e: Exception) {
                Log.e(TAG, "Restore sessions failed", e)
                _error.value = "Restore failed: ${e.message}"
            } finally {
                _connectingProfileId.value = null
            }
        }
    }

    /**
     * "Plain shell" path from the session picker — finish connection
     * without invoking the configured session manager (tmux / zellij /
     * screen). Lets a user open a one-off bare bash on a profile that's
     * normally wrapped in a multiplexer, e.g. for a quick check that
     * shouldn't disturb the long-running session.
     */
    fun onPlainShellSelected(sessionId: String) {
        val sel = _sessionSelection.value
        _sessionSelection.value = null
        if (sel?.transportType != "SSH" && sel?.transportType != null) {
            // Only meaningful for SSH right now; fall through to the
            // normal "create new session" flow otherwise.
            onSessionSelected(sessionId, null)
            return
        }
        val profileId = sel?.profileId ?: sshSessionManager.getSession(sessionId)?.profileId ?: return
        viewModelScope.launch {
            _connectingProfileId.value = profileId
            try {
                sshSessionManager.setBypassSessionManager(sessionId, true)
                sshSessionManager.setChosenSessionName(sessionId, "shell")
                finishConnect(sessionId, profileId)
            } catch (e: Exception) {
                sshSessionManager.updateStatus(sessionId, SshSessionManager.SessionState.Status.ERROR)
                _error.value = e.message ?: "Connection failed"
                sshSessionManager.removeSession(sessionId)
            } finally {
                _connectingProfileId.value = null
            }
        }
    }

    fun onSessionSelected(sessionId: String, sessionName: String?) {
        val sel = _sessionSelection.value
        _sessionSelection.value = null

        // SSH path
        val profileId = sel?.profileId ?: sshSessionManager.getSession(sessionId)?.profileId ?: return
        viewModelScope.launch {
            _connectingProfileId.value = profileId
            try {
                val effectiveName = sessionName ?: generateUniqueSessionName(
                    sshSessionManager.getSession(sessionId)?.label ?: sessionId.take(8),
                    sel?.sessionNames ?: emptyList(),
                )
                sshSessionManager.setChosenSessionName(sessionId, effectiveName)
                finishConnect(sessionId, profileId)
            } catch (e: Exception) {
                sshSessionManager.updateStatus(sessionId, SshSessionManager.SessionState.Status.ERROR)
                _error.value = e.message ?: "Connection failed"
                sshSessionManager.removeSession(sessionId)
            } finally {
                _connectingProfileId.value = null
            }
        }
    }

    /**
     * Kill a remote session (tmux/zellij/screen) and refresh the session list.
     */
    fun killRemoteSession(sessionName: String) {
        val sel = _sessionSelection.value ?: return
        val killCmd = sel.manager.killCommand?.invoke(sessionName) ?: return
        val client = sshSessionManager.getSession(sel.sessionId)?.client
            ?: return

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    client.execCommand(killCmd)
                }
                // Refresh the session list
                val listCmd = sel.manager.listCommand ?: return@launch
                val updated = withContext(Dispatchers.IO) {
                    try {
                        val result = client.execCommand(listCmd)
                        if (result.exitStatus == 0) {
                            SessionManager.parseSessionList(sel.manager, result.stdout)
                        } else emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                if (updated.isNotEmpty()) {
                    _sessionSelection.value = sel.copy(sessionNames = updated)
                } else {
                    _sessionSelection.value = null
                }
            } catch (e: Exception) {
                _error.value = "Failed to kill session: ${e.message}"
            }
        }
    }

    /**
     * Rename a remote session (tmux/screen/byobu) and refresh the session list.
     */
    fun renameRemoteSession(oldName: String, newName: String) {
        val sel = _sessionSelection.value ?: return
        val renameCmd = sel.manager.renameCommand?.invoke(oldName, newName) ?: return
        val client = sshSessionManager.getSession(sel.sessionId)?.client
            ?: return

        viewModelScope.launch {
            try {
                val renameResult = withContext(Dispatchers.IO) {
                    client.execCommand(renameCmd)
                }
                val renameSucceeded = renameResult.exitStatus == 0
                if (!renameSucceeded) {
                    Log.w(TAG, "renameRemoteSession failed: exit=${renameResult.exitStatus} stderr='${renameResult.stderr}'")

                    _error.value = renameResult.stderr.ifBlank { "Rename failed (exit ${renameResult.exitStatus})" }
                }
                // Give the session manager a moment to propagate the rename
                kotlinx.coroutines.delay(500)
                // Refresh the session list
                val listCmd = sel.manager.listCommand ?: return@launch
                val updated = withContext(Dispatchers.IO) {
                    try {
                        val result = client.execCommand(listCmd)
                        if (result.exitStatus == 0) {
                            SessionManager.parseSessionList(sel.manager, result.stdout)
                        } else emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                if (renameSucceeded) {
                    propagateSessionRename(sel.profileId, oldName, newName)
                }
                val newPrev = renameInList(sel.previousSessionNames, oldName, newName)
                _sessionSelection.value = sel.copy(
                    sessionNames = updated,
                    previousSessionNames = newPrev,
                )
            } catch (e: Exception) {
                _error.value = "Failed to rename session: ${e.message}"
            }
        }
    }

    /**
     * After a successful remote rename, update the cached session name everywhere
     * Haven still references the old name: the profile's stored `lastSessionName`
     * (used for restore-on-reconnect), and any live SSH session record on this
     * profile whose `chosenSessionName` matches. Tab labels follow via syncSessions.
     */
    private suspend fun propagateSessionRename(profileId: String, oldName: String, newName: String) {
        val oldSan = sanitizeSessionName(oldName)
        val newSan = sanitizeSessionName(newName)
        if (oldSan == newSan) return
        val profile = repository.getById(profileId) ?: return
        profile.lastSessionName?.takeIf { it.isNotBlank() }?.let { current ->
            val parts = current.split("|")
            if (oldSan in parts) {
                val updated = parts.map { if (it == oldSan) newSan else it }.joinToString("|")
                if (updated != current) repository.save(profile.copy(lastSessionName = updated))
            }
        }
        sshSessionManager.sessions.value.values
            .filter { it.profileId == profileId && sanitizeSessionName(it.chosenSessionName ?: "") == oldSan }
            .forEach { sshSessionManager.setChosenSessionName(it.sessionId, newName) }
    }

    private fun renameInList(names: List<String>, oldName: String, newName: String): List<String> {
        val oldSan = sanitizeSessionName(oldName)
        return names.map { if (sanitizeSessionName(it) == oldSan) newName else it }
    }

    private fun sanitizeSessionName(name: String): String =
        SessionManager.sanitizeSessionName(name)

    /**
     * Generate a session name that doesn't conflict with existing remote sessions.
     * Appends "-2", "-3", etc. if the base name is already taken.
     */
    private fun generateUniqueSessionName(label: String, remoteNames: List<String>): String {
        val base = sanitizeSessionName(label)
        val existing = remoteNames.toSet()
        if (base !in existing) return base
        var i = 2
        while ("$base-$i" in existing) i++
        return "$base-$i"
    }

    fun dismissSessionPicker() {
        val sel = _sessionSelection.value ?: return
        _sessionSelection.value = null
        sshSessionManager.removeSession(sel.sessionId)
    }

    /**
     * Connect to a jump host profile, reusing an existing connected session if available.
     * Returns Pair(sessionId, reused) — reused=true if an existing session was used.
     */
    /**
     * Seam for tests to observe the jump leg's dial (#690). Production keeps
     * the plain JSch client, matching the pre-existing construction.
     */
    /**
     * Seam for tests to stand in the target leg's client (#751 ephemeral
     * connect coverage). Production keeps the factory the pre-existing code
     * used, with the same engine selection.
     */
    internal var clientFactory: (ConnectionProfile) -> SshConnection = {
        SshConnectionFactory.create(sshEngineFromOptionsText(it.sshOptions))
    }

    internal var jumpClientFactory: () -> SshConnection = {
        // The jump leg runs the jump profile's own auth — an FIDO2 SK key or
        // a security-chip key on the jump host must work here too (#732).
        newSshClient()
    }

    internal suspend fun connectJumpHost(
        jumpProfileId: String,
        password: String,
        tunnelOwnerProfileId: String? = null,
    ): Pair<String, Boolean> {
        // Reuse existing connected session for this jump profile
        val existing = sshSessionManager.getSessionsForProfile(jumpProfileId)
            .firstOrNull { it.status == SshSessionManager.SessionState.Status.CONNECTED }
        if (existing != null) {
            Log.d(TAG, "Reusing existing jump host session ${existing.sessionId}")
            // Reused session might have been opened directly by the user
            // (terminal) — record the new dependent without flipping the
            // tunnelOpened flag, so disconnecting the dependent later won't
            // also tear down a session the user wants kept alive.
            if (tunnelOwnerProfileId != null) {
                sshSessionManager.attachTunnelDependent(existing.sessionId, tunnelOwnerProfileId)
            }
            return existing.sessionId to true
        }

        val jumpProfile = repository.getById(jumpProfileId)
            ?: throw Exception("Jump host profile not found")

        // Auto-connect paths (VNC/RDP-over-SSH-tunnel) call us with an
        // empty password — they don't have one to pass. Fall back to the
        // jump-host profile's saved sshPassword so password-auth jumps
        // actually succeed instead of bouncing off "Auth cancel for methods
        // public key,password" (#121, KoriKraut). Explicit SSH-side jump
        // chains keep the user-typed password they passed in.
        //
        // #381 (BlackDex): but only when the jump authenticates with a
        // PASSWORD. resolveAuthMethods threads this value through as the KEY
        // passphrase too, so borrowing the saved login password for a
        // key-auth jump hands it to a passphrase-protected key (whose real
        // passphrase is stored separately, #290/#377) as its passphrase —
        // the key then can't be decrypted and is never offered, so the jump
        // leg fails "Auth fail for methods 'publickey'" even though a direct
        // connect (which passes "") works. For key-auth jumps keep "" so the
        // key resolves via its stored passphrase, exactly like a direct
        // connect.
        val effectivePassword = jumpConnectPassword(
            typedPassword = password,
            jumpUsesKeyAuth = profileUsesKeyAuth(jumpProfile),
            savedSshPassword = jumpProfile.sshPassword,
        )

        Log.d(TAG, "Auto-connecting jump host: ${LogRedact.of(jumpProfile.label)} (${LogRedact.host(jumpProfile.host, jumpProfile.port)})")
        // #381: a jump-host connect used to log nothing to the in-app
        // connection log and never wired a verbose SSH logger, so an auth
        // failure on the jump leg went only to logcat with no detail on WHY
        // (which key/signature the server rejected). Wire the same verbose
        // logger the direct path uses and persist the result below, so a
        // failed jump is diagnosable from Settings → View connection log.
        val jumpVerbose = if (preferencesRepository.verboseLoggingEnabled.first()) SshVerboseLogger() else null
        // Wire the verbose logger the direct path uses — the FIDO
        // authenticator, chip signer and OpenKeychain clients are already
        // attached by newSshClient (jumpClientFactory's default), so a
        // jump host with a security-key or OpenKeychain auth method works
        // without re-wiring it here (#286).
        val jumpClient = jumpClientFactory().apply {
            verboseLogger = jumpVerbose
        }
        val jumpSessionId = sshSessionManager.registerSession(jumpProfileId, "Jump: ${jumpProfile.label}", jumpClient)

        try {
            withContext(Dispatchers.IO) {
                // Plural resolver so a jump host (the SSH leg under VNC/RDP)
                // honours its full auth-methods spec — "Any hardware key", AND
                // chains, etc. — not just the legacy single keyId. Falls back to
                // the singular path for simple/legacy profiles. (#286)
                val authMethod = resolveAuthMethods(jumpProfile, effectivePassword)
                val config = ConnectionConfig(
                    host = jumpProfile.host,
                    port = jumpProfile.port,
                    username = jumpProfile.username,
                    authMethod = authMethod,
                    sshOptions = ConnectionConfig.parseSshOptions(jumpProfile.sshOptions),
                    addressFamily = jumpProfile.addressFamilyForSsh,
                    reconnectPolicy = jumpProfile.reconnectPolicy,
                )
                // #690: the jump leg must ride the JUMP profile's own
                // Route-through tunnel. The direct path resolves
                // tunnelResolver.havenProxy(profile) for its dial; the jump
                // leg used to dial with no proxy at all, so a jump server
                // only reachable over Tailscale/WireGuard failed here even
                // though connecting to that same profile directly worked.
                val jumpProxy = tunnelResolver.havenProxy(jumpProfile)
                Log.d(TAG, "Jump host SSH connecting... (proxy=${jumpProxy != null})")
                try {
                    val hostKeyEntry = jumpClient.connect(
                        config,
                        proxy = jumpProxy,
                        keyboardInteractivePrompter = keyboardInteractivePrompter,
                        totpCodeProvider = buildTotpCodeProvider(jumpProfile),
                        confirmOtp = jumpProfile.totpConfirmBeforeSend,
                    )
                    Log.d(TAG, "Jump host SSH connected, verifying host key...")
                    runTofuVerification(
                        hostKeyEntry,
                        clientToDisconnectOnReject = jumpClient,
                        rejectedOnNewHostMessage = "Jump host key rejected by user",
                        rejectedOnChangeMessage = "Jump host key change rejected by user",
                    )
                } catch (e: HostKeyAuthFailure) {
                    runTofuVerification(
                        e.hostKey,
                        clientToDisconnectOnReject = null,
                        rejectedOnNewHostMessage = "Jump host key rejected by user",
                        rejectedOnChangeMessage = "Jump host key change rejected by user",
                    )
                    throw e.cause ?: e
                }

                sshSessionManager.storeConnectionConfig(jumpSessionId, config, SessionManager.NONE)
                // Re-resolve the jump profile's tunnel on auto-reconnect, same
                // contract the direct path registers (#690).
                sshSessionManager.setReconnectProxyProvider(jumpSessionId) {
                    tunnelResolver.havenProxy(jumpProfile)
                }
            }

            sshSessionManager.updateStatus(jumpSessionId, SshSessionManager.SessionState.Status.CONNECTED)
            Log.d(TAG, "Jump host connected: ${LogRedact.of(jumpProfile.label)} ($jumpSessionId), isConnected=${jumpClient.isConnected}")
            connectionLogRepository.logEvent(jumpProfileId, ConnectionLog.Status.CONNECTED, verboseLog = jumpVerbose?.drain())
            // Newly-opened tunnel session: bind it to its owner so the
            // session is torn down when the owner disconnects (#121).
            if (tunnelOwnerProfileId != null) {
                sshSessionManager.markTunnelOpened(jumpSessionId, tunnelOwnerProfileId)
            }
            return jumpSessionId to false
        } catch (e: Exception) {
            // Auth failure (or anything else) used to leave the registered
            // session orphaned in the active-sessions list — undismissable,
            // and one accumulates per retry (#121, KoriKraut: "5 active
            // sessions"). Mark it ERROR and unregister before re-throwing.
            Log.e(TAG, "Jump host connect failed for ${LogRedact.of(jumpProfile.label)}: ${e.message}", e)
            connectionLogRepository.logEvent(jumpProfileId, ConnectionLog.Status.FAILED, details = e.message, verboseLog = jumpVerbose?.drain())
            sshSessionManager.updateStatus(jumpSessionId, SshSessionManager.SessionState.Status.ERROR)
            sshSessionManager.removeSession(jumpSessionId)
            throw e
        }
    }

    private suspend fun finishConnect(sessionId: String, profileId: String, verboseLog: String? = pendingVerboseLogs.remove(sessionId), silent: Boolean = false): Boolean {
        // Tunnel-only profiles (#150 Phase B): bring up the transport,
        // register port forwards, but skip shell allocation and the
        // terminal navigation. Session lives in the background just
        // for its forwards — autossh-style when paired with the
        // auto-reconnect policy from Phase A.
        val tunnelOnly = repository.getById(profileId)?.tunnelOnly == true

        // Open the shell and AWAIT a definitive outcome — mark CONNECTED /
        // navigate only on a confirmed live terminal. A shell that closes
        // immediately (no session manager on the host) is reported here, not
        // discovered later by a timer, so the user gets a clear error and is
        // never stranded on the wrong tab. Tunnel-only sessions have no shell.
        if (!tunnelOnly) {
            val outcome = withContext(Dispatchers.IO) {
                sshSessionManager.openShellAndAwaitReady(sessionId)
            }
            when (outcome) {
                is sh.haven.core.ssh.ShellOutcome.Ready -> { /* proceed below */ }
                is sh.haven.core.ssh.ShellOutcome.ShellClosed -> {
                    connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = "shell closed (exit ${outcome.exitStatus})", verboseLog = verboseLog)
                    sshSessionManager.removeSession(sessionId)
                    userMessageBus.error("Shell closed — is your session manager (tmux/zellij/screen) installed on this host?")
                    return false
                }
                is sh.haven.core.ssh.ShellOutcome.Failed -> {
                    connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = outcome.reason, verboseLog = verboseLog)
                    sshSessionManager.removeSession(sessionId)
                    userMessageBus.error(outcome.reason.ifBlank { "Failed to open the remote shell" })
                    return false
                }
            }
        }

        // Apply enabled port forward rules (best-effort; not gated on shell health).
        // Skipped for connection-reuse sessions — the primary session already
        // holds these forwards on the shared SSH client, so re-binding them here
        // only produces "port already registered" failures.
        if (!sshSessionManager.isClientShared(sessionId)) {
            withContext(Dispatchers.IO) {
                val rules = portForwardRepository.getEnabledForProfile(profileId)
                if (rules.isNotEmpty()) {
                    sshSessionManager.applyPortForwards(
                        sessionId,
                        rules.map { it.toForwardInfo() },
                    )
                }
            }
        }
        sshSessionManager.updateStatus(sessionId, SshSessionManager.SessionState.Status.CONNECTED)
        repository.markConnected(profileId)
        // Persist all open session names for this profile (pipe-delimited)
        sshSessionManager.getSession(sessionId)?.let { session ->
            if (session.sessionManager != SessionManager.NONE) {
                // Collect session names from all sessions for this profile
                val allNames = sshSessionManager.sessions.value.values
                    .filter { it.profileId == profileId && it.chosenSessionName != null }
                    .map { sanitizeSessionName(it.chosenSessionName!!) }
                    .toMutableList()
                // Add the current session if not already included
                val currentName =
                    sanitizeSessionName(session.chosenSessionName ?: session.label ?: sessionId.take(8))
                if (currentName !in allNames) allNames.add(currentName)
                repository.getById(profileId)?.let { profile ->
                    repository.save(profile.copy(lastSessionName = allNames.joinToString("|")))
                }
            }
        }
        val authDetail = sshSessionManager.getConnectionConfigForProfile(profileId)?.first?.let { config ->
            fun label(m: ConnectionConfig.AuthMethod): String = when (m) {
                is ConnectionConfig.AuthMethod.Password -> "password"
                is ConnectionConfig.AuthMethod.PrivateKey -> "key"
                is ConnectionConfig.AuthMethod.PrivateKeys -> "key"
                is ConnectionConfig.AuthMethod.FidoKey -> "FIDO2"
                is ConnectionConfig.AuthMethod.ProviderKey -> "provider key"
                is ConnectionConfig.AuthMethod.HardwareKey -> "security-chip key"
                is ConnectionConfig.AuthMethod.Multi -> m.methods.joinToString("+") { label(it) }
            }
            label(config.authMethod)
        }
        connectionLogRepository.logEvent(profileId, ConnectionLog.Status.CONNECTED, details = authDetail, verboseLog = verboseLog)
        startForegroundServiceIfNeeded()
        if (!silent && !tunnelOnly) {
            // Shell confirmed live above, so a terminal tab will materialise —
            // safe to navigate. No optimistic-navigate + recovery poll.
            _navigateToTerminal.value = profileId
        }
        return true
    }

    /**
     * Resolve the auth method for a connection profile.
     * If the profile has an assigned key, use it.
     * Otherwise if keys exist and password is empty, try the first available key.
     * Falls back to password auth.
     */
    /**
     * Resolve the profile's ordered [ConnectionProfile.authMethodSpecs] into a
     * connect-time auth method (#166). One method → that method (preserving the
     * pre-#166 single-method behaviour exactly); several → a
     * [ConnectionConfig.AuthMethod.Multi] so a `publickey,password`-style chain
     * completes in one attempt. Keyboard-interactive specs carry no credential
     * (the prompter handles them live), so they don't add to the list.
     */
    private suspend fun resolveAuthMethods(
        profile: ConnectionProfile,
        password: String,
    ): ConnectionConfig.AuthMethod {
        // "Use password only" overrides any key element in the chain — keys are
        // never offered (#121). resolveAuthMethod enforces the same for single
        // specs and the legacy/explicit-key callers.
        if (profile.ignoreSavedKeys) return ConnectionConfig.AuthMethod.Password(password)
        val specs = profile.authMethodSpecs
        // A single explicit-key spec must be honoured via the SPEC's keyId.
        // The legacy resolveAuthMethod reads profile.keyId, which can be out of
        // sync with the spec — e.g. a profile pinned via MCP authMethods, or a
        // UI save that didn't mirror the spec to the legacy field. When unsynced
        // (keyId null) it silently fell back to "any saved key" and ignored the
        // pin, so a connection set to require one specific key authenticated
        // with whatever other key the server accepted.
        (specs.singleOrNull() as? ConnectionProfile.AuthMethodSpec.Key)?.keyId?.let { kid ->
            resolveExplicitKey(kid, password)?.let { return it }
        }
        // A lone "Any hardware key" must resolve to the FIDO pool, not fall to
        // resolveAuthMethod (which only knows password / any-software-key).
        if (specs.singleOrNull() is ConnectionProfile.AuthMethodSpec.AnyHardwareKey) {
            return resolveAnyHardwareKeys() ?: ConnectionConfig.AuthMethod.Password(password)
        }
        if (specs.size <= 1) return resolveAuthMethod(profile, password)

        val methods = specs.mapNotNull { spec ->
            when (spec) {
                is ConnectionProfile.AuthMethodSpec.Password ->
                    ConnectionConfig.AuthMethod.Password(password)
                is ConnectionProfile.AuthMethodSpec.Key ->
                    if (spec.keyId != null) resolveExplicitKey(spec.keyId!!, password)
                    else resolveAnyUsableKeys()
                ConnectionProfile.AuthMethodSpec.AnyHardwareKey -> resolveAnyHardwareKeys()
                ConnectionProfile.AuthMethodSpec.KeyboardInteractive -> null
                // TOTP is auto-filled into the live keyboard-interactive
                // round (#178), not registered as a JSch credential.
                is ConnectionProfile.AuthMethodSpec.Totp -> null
                // ProtonSrp is an EMAIL-only method handled by the Go mailbridge,
                // never part of a JSch/SSH auth chain.
                ConnectionProfile.AuthMethodSpec.ProtonSrp -> null
            }
        }
        return when {
            methods.isEmpty() -> ConnectionConfig.AuthMethod.Password(password)
            methods.size == 1 -> methods.single()
            else -> ConnectionConfig.AuthMethod.Multi(methods)
        }
    }

    /**
     * Build a live TOTP code provider for [profile] if its auth chain
     * carries a `TOTP` element with a resolvable stored secret (#178).
     * The secret is decrypted once here and captured in the closure; the
     * code itself is generated on each invocation so it's current for the
     * window the server validates. Returns null when the profile has no
     * TOTP element or the referenced secret is missing/undecryptable —
     * the keyboard-interactive prompt then falls through to the dialog.
     */
    private suspend fun buildTotpCodeProvider(profile: ConnectionProfile): (() -> String)? {
        val totpSpec = profile.authMethodSpecs
            .filterIsInstance<ConnectionProfile.AuthMethodSpec.Totp>()
            .firstOrNull() ?: return null
        // Explicit id, else the single stored secret if there's exactly one.
        val specId = totpSpec.secretId
        val secret = when {
            !specId.isNullOrEmpty() -> totpSecretRepository.getById(specId)
            else -> totpSecretRepository.getAll().singleOrNull()
        } ?: return null
        val plain = totpSecretRepository.getDecryptedSecret(secret.id) ?: return null
        val algorithm = runCatching { sh.haven.core.security.Totp.Algorithm.valueOf(secret.algorithm) }
            .getOrDefault(sh.haven.core.security.Totp.Algorithm.SHA1)
        return {
            sh.haven.core.security.Totp.generate(
                secretBase32 = plain,
                algorithm = algorithm,
                digits = secret.digits,
                periodSeconds = secret.periodSeconds,
            )
        }
    }

    // internal for unit test (#531 sk-key auto-offer).
    internal suspend fun resolveAuthMethod(
        profile: ConnectionProfile,
        password: String,
    ): ConnectionConfig.AuthMethod {
        // "Use password only" — never offer keys (explicit or keystore), so a
        // password-only server gets the password prompt instead of failed key
        // attempts (#121).
        if (profile.ignoreSavedKeys) {
            return ConnectionConfig.AuthMethod.Password(password)
        }

        // Profile has an explicit key assigned
        val keyId = profile.keyId
        if (keyId != null) {
            resolveExplicitKey(keyId, password)?.let { return it }
        }

        // No explicit key but keys are available — try every key the
        // server might accept. Software keys go first: SshClient's
        // deferred any-hardware-key detection then skips the up-front
        // token prompt, and the FIDO ceremony only runs for an sk-key
        // the server actually accepts the offer of (#531).
        if (password.isEmpty()) {
            val software = resolveAnyUsableKeys()
            val hardware = resolveAnyHardwareKeys()
            when {
                software != null && hardware != null ->
                    return ConnectionConfig.AuthMethod.Multi(listOf(software, hardware))
                software != null -> return software
                hardware != null -> return hardware
            }
        }

        return ConnectionConfig.AuthMethod.Password(password)
    }

    /**
     * Stop the connect if the user declined to unlock any key.
     *
     * The "try every key" paths walk the whole keyring, so without this
     * a declined prompt just removed one key from the list and the
     * connect carried on with the rest (#559). One decline is enough:
     * the user was asked and said no, and there is no reading of that
     * which means "go ahead with a different credential".
     */
    private fun failIfAnyDeclined(result: DecryptedKeys) {
        result.declined.firstOrNull()?.let { throw KeyUnlockDeclinedException(it.reason) }
    }

    /**
     * True when [e] means "a key ceremony was refused or could not run":
     * a declined software-key unlock ([KeyUnlockDeclinedException], thrown
     * pre-connect), or the security-chip sign ceremony being refused / timed
     * out / invalidated ([HardwareKeySignDeclinedException] /
     * [HardwareKeyInvalidatedException], thrown from inside the JSch auth —
     * RuntimeExceptions are the only kind JSch rethrows raw instead of
     * folding them into a generic "Auth fail"). All connect paths treat
     * these the same: report and stop, never offer another credential (#559).
     */
    // internal for unit test (the #559 classification pin for the chip
    // ceremony exceptions, ConnectionsViewModelSessionTest).
    internal fun isKeyRefusal(e: Exception): Boolean =
        e is KeyUnlockDeclinedException ||
            e is HardwareKeySignDeclinedException ||
            e is HardwareKeyInvalidatedException

    /**
     * Resolve a specific saved key into an auth method, running the
     * cert-renewal gate and handling FIDO2 / encrypted keys. Returns null
     * if the key is missing or its bytes can't be decrypted, and callers
     * fall through. [password] is used as the passphrase for an encrypted
     * key.
     *
     * A **declined** unlock is not a fall-through: it throws
     * [KeyUnlockDeclinedException] so the connect stops before a socket
     * is opened. Falling through on a decline is what #559 reported —
     * the user pressed back on the prompt and Haven connected anyway,
     * using whatever else the profile had (another key, a stored
     * password, keyboard-interactive). The key itself was never
     * unlocked, so nothing leaked; what failed was the promise that no
     * connection is attempted without the ack.
     */
    private suspend fun resolveExplicitKey(
        keyId: String,
        password: String,
    ): ConnectionConfig.AuthMethod? {
        // Fetch row metadata first (no biometric prompt yet) so nothing
        // prompts before we trigger the biometric prompt and the JSch
        // connect.
        val key = sshKeyRepository.getById(keyId)
        if (key != null && key.hardwareAlias != null) {
            // Hardware-resident: no local bytes exist to fetch or prompt for —
            // the biometric ceremony happens per signature inside
            // KeystoreSshIdentity, only after the server accepts this key.
            // Cert is public material, same fetch as the software path.
            Log.d(TAG, "Using security-chip key: ${key.keyType}" +
                if (key.certificateBytes != null) " (with certificate)" else "")
            return ConnectionConfig.AuthMethod.HardwareKey(
                // hardwareAlias is immutable once minted (rows are born
                // hardware or software; the repository never rewrites it),
                // so the null-checked value cannot go stale between here
                // and the signing ceremony.
                alias = key.hardwareAlias!!,
                publicKeyOpenSsh = key.publicKeyOpenSsh,
                fingerprintSha256 = key.fingerprintSha256,
                keyLabel = key.label,
                certBytes = sshKeyRepository.getCertificateBytes(keyId),
            )
        }
        val keyBytes = if (key != null) {
            when (val material = sshKeyRepository.fetchKeyMaterial(keyId)) {
                is KeyMaterial.Available -> material.bytes
                is KeyMaterial.Declined -> throw KeyUnlockDeclinedException(material.reason)
                KeyMaterial.Missing -> null
                // Unreachable: a row with a hardwareAlias returned above,
                // and only such rows dispatch to Hardware.
                is KeyMaterial.Hardware -> null
            }
        } else {
            null
        }
        if (keyBytes != null && key != null) {
            // Cert is public material so the same fetch works for both
            // software and FIDO2 paths; null = plain pubkey auth.
            val certBytes = sshKeyRepository.getCertificateBytes(keyId)
            if (key.keyType.startsWith("sk-")) {
                Log.d(TAG, "Using FIDO2 SK key: ${key.keyType}" +
                    if (certBytes != null) " (with certificate)" else "")
                return ConnectionConfig.AuthMethod.FidoKey(
                    skKeyData = keyBytes,
                    certBytes = certBytes,
                    keyLabel = key.label,
                )
            }
            // A key held by another app (#487): the bytes are a reference to
            // it, not key material, so they go through verbatim exactly as
            // an SK credential handle does.
            if (key.keyType == OpenKeychainKeyData.KEY_TYPE) {
                Log.d(TAG, "Using a provider-held key: ${LogRedact.of(key.label)}")
                return ConnectionConfig.AuthMethod.ProviderKey(
                    keyData = keyBytes,
                    keyLabel = key.label,
                )
            }
            // For encrypted keys, pass the original encrypted bytes + passphrase.
            // JSch decrypts at auth time — key never stored in plaintext.
            // When the caller supplied no passphrase, fall back to the opt-in
            // per-key stored passphrase (#290) so one encrypted key works across
            // all profiles without prompting. A wrong/stale stored value just
            // fails auth and surfaces the existing fallback prompt.
            val effectivePassword = if (key.isEncrypted && password.isBlank()) {
                sshKeyRepository.getStoredPassphrase(keyId).orEmpty()
            } else {
                password
            }
            val passphrase = if (key.isEncrypted) effectivePassword.toCharArray() else CharArray(0)
            return ConnectionConfig.AuthMethod.PrivateKey(
                keyBytes = if (key.isEncrypted) keyBytes else rawKeyToPem(keyBytes, key.keyType),
                passphrase = passphrase,
                certificateBytes = certBytes,
            )
        }
        return null
    }

    /**
     * Every usable saved key, or null if none — the fallback when a profile
     * has no explicit key assigned. Software keys become a
     * [ConnectionConfig.AuthMethod.PrivateKeys] "try them all" bundle;
     * hardware-resident keys (security chip, `hardwareAlias != null`) become
     * individual [ConnectionConfig.AuthMethod.HardwareKey] methods appended
     * AFTER the software bundle, so JSch offers software keys first and the
     * hardware biometric ceremony only fires when the server wants a chip
     * key (and the software keys failed). Within each group, row order
     * (the software pool keeps the repository's order, then each hardware
     * key in the repository's order).
     *
     * Includes plaintext keys AND passphrase-protected keys whose passphrase
     * is stored (#290/#381): the encrypted bytes + stored passphrase ride
     * along so JSch decrypts at auth time, exactly like a pinned key. An
     * encrypted key with NO stored passphrase is skipped (nothing to decrypt
     * it with unattended). Biometric-protected software keys are included
     * (each prompts via its keystore gate in [getAllDecryptedDetailed]);
     * hardware keys prompt per signature later, by design.
     *
     * #381: previously this offered ONLY unencrypted keys, so a user whose only
     * key is passphrase-protected (the shape #290/#377 encourage) got no key
     * offered unless they pinned it to every profile by hand.
     *
     * FIDO2/SK keys (`sk-ssh-ed25519@openssh.com` etc.) are excluded: their
     * `privateKeyBytes` hold a credential handle, not loadable private-key
     * material, so PEM-encoding one throws "Invalid Key" — and a hardware key
     * shouldn't be silently tried (with a touch prompt) on every connect. SK
     * keys are used only when explicitly assigned to a profile, where
     * [resolveExplicitKey] resolves them to a [ConnectionConfig.AuthMethod.FidoKey].
     */
    private suspend fun resolveAnyUsableKeys(): ConnectionConfig.AuthMethod? {
        val detailed = sshKeyRepository.getAllDecryptedDetailed().also(::failIfAnyDeclined)
        val entries = detailed.keys
            .filter { holdsLoadablePrivateKey(it.keyType) && it.enabledForAuth }
            .mapNotNull { key ->
                if (key.isEncrypted) {
                    // Only usable unattended if its passphrase is stored (#290).
                    val stored = sshKeyRepository.getStoredPassphrase(key.id)
                    if (stored.isNullOrEmpty()) return@mapNotNull null
                    ConnectionConfig.AuthMethod.PrivateKeys.KeyEntry(
                        label = key.label,
                        // Encrypted PEM as-is; JSch decrypts with the passphrase.
                        keyBytes = key.privateKeyBytes,
                        certificateBytes = key.certificateBytes,
                        passphrase = stored.toByteArray(Charsets.UTF_8),
                    )
                } else {
                    ConnectionConfig.AuthMethod.PrivateKeys.KeyEntry(
                        label = key.label,
                        keyBytes = rawKeyToPem(key.privateKeyBytes, key.keyType),
                        // Carry the attached cert so a CA-only server accepts this
                        // key even without an explicit profile assignment. (#185)
                        certificateBytes = key.certificateBytes,
                    )
                }
            }
        val hardwareKeys = detailed.hardware
            .filter { it.enabledForAuth }
            .map { key ->
                ConnectionConfig.AuthMethod.HardwareKey(
                    alias = key.hardwareAlias!!,
                    publicKeyOpenSsh = key.publicKeyOpenSsh,
                    fingerprintSha256 = key.fingerprintSha256,
                    keyLabel = key.label,
                    certBytes = key.certificateBytes,
                )
            }
        val methods = buildList {
            if (entries.isNotEmpty()) add(ConnectionConfig.AuthMethod.PrivateKeys(keys = entries))
            addAll(hardwareKeys)
        }
        if (methods.isEmpty()) return null
        return if (methods.size == 1) methods.single() else ConnectionConfig.AuthMethod.Multi(methods)
    }

    /**
     * Every enrolled hardware/FIDO (`sk-*`) key as an either-of pool — each a
     * [ConnectionConfig.AuthMethod.FidoKey] with `anyOf = true`, so [SshClient]
     * asks the user to present whichever one they have and offers only that
     * (#237). Backs the "Any hardware key" auth option. Null if none enrolled.
     * Unlike [resolveExplicitKey] (which yields a single required key) and
     * unlike pinning/listing keys (require-all), this is convenience OR-auth.
     * sk-key bytes are a credential handle, not loadable material, so they're
     * passed verbatim (no PEM wrap, unlike [resolveAnyUsableKeys]).
     */
    private suspend fun resolveAnyHardwareKeys(): ConnectionConfig.AuthMethod? {
        val keys = sshKeyRepository.getAllDecryptedDetailed()
            .also(::failIfAnyDeclined)
            .keys
            .filter { it.keyType.startsWith("sk-") && it.enabledForAuth }
        if (keys.isEmpty()) return null
        val fidoKeys = keys.map { key ->
            ConnectionConfig.AuthMethod.FidoKey(
                skKeyData = key.privateKeyBytes,
                certBytes = key.certificateBytes,
                keyLabel = key.label,
                anyOf = true,
            )
        }
        return if (fidoKeys.size == 1) fidoKeys.single()
        else ConnectionConfig.AuthMethod.Multi(fidoKeys)
    }

    /**
     * Resolved agent-forwarding identities for a profile, plus diagnostics
     * needed to warn the user when the forwarded agent would be empty (e.g.
     * all stored SSH keys are passphrase-protected or none are stored at all).
     */
    private data class AgentIdentitiesResult(
        val keys: List<ConnectionConfig.AgentIdentity>,
        val skippedLockedCount: Int,
        val hadNoStoredKeys: Boolean,
        val forwardAgentEnabled: Boolean,
    ) {
        /**
         * User-visible warning if [forwardAgentEnabled] is true but [keys] ended
         * up empty. Returns null otherwise — no warning needed either when
         * forwarding is disabled, or when at least one usable key was found.
         */
        val warningMessage: String?
            get() {
                if (!forwardAgentEnabled || keys.isNotEmpty()) return null
                return when {
                    skippedLockedCount > 0 -> buildString {
                        append("Agent forwarding enabled but all ")
                        append(skippedLockedCount)
                        append(" stored SSH key")
                        if (skippedLockedCount != 1) append("s are")
                        else append(" is")
                        append(" passphrase-protected with no stored passphrase; the forwarded agent will be empty. ")
                        append("Store the passphrase on the key (Keys tab) to forward it.")
                    }
                    hadNoStoredKeys -> "Agent forwarding enabled but no SSH keys are stored in Haven; the forwarded agent will be empty."
                    else -> null
                }
            }
    }

    /**
     * Pure helper — computes the agent-forwarding result for [profile]
     * without any side effects. Kept separate from [agentIdentitiesFor] so
     * the warning logic can be unit-tested.
     */
    private suspend fun computeAgentIdentities(profile: ConnectionProfile): AgentIdentitiesResult {
        if (!profile.forwardAgent) {
            return AgentIdentitiesResult(
                keys = emptyList(),
                skippedLockedCount = 0,
                hadNoStoredKeys = false,
                forwardAgentEnabled = false,
            )
        }
        val allKeys = sshKeyRepository.getAllDecryptedDetailed()
        val enabled = allKeys.keys.filter { it.enabledForAuth }
        val keys = mutableListOf<ConnectionConfig.AgentIdentity>()
        // #602: FIDO2/SK keys are forwardable through a hardware-backed
        // identity (AgentSafeIdentity over FidoIdentity): each signature from
        // the remote runs a CTAP assertion, so presence is proven per use
        // rather than delegated to a stored secret. Their privateKeyBytes are
        // a credential handle, so they carry skKeyData and skip the PEM path.
        for (key in enabled.filter { it.keyType.startsWith("sk-") }) {
            keys += ConnectionConfig.AgentIdentity(
                label = key.label,
                keyBytes = key.privateKeyBytes,
                skKeyData = key.privateKeyBytes,
                certBytes = key.certificateBytes,
            )
        }
        // Security-chip keys forward the same way (AgentSafeIdentity over
        // KeystoreSshIdentity): each forwarded sign request runs the
        // biometric ceremony on this phone, so the remote cannot get a
        // signature the user didn't just authorize.
        for (key in allKeys.hardware.filter { it.enabledForAuth }) {
            keys += ConnectionConfig.AgentIdentity(
                label = key.label,
                keyBytes = ByteArray(0),
                hardwareAlias = key.hardwareAlias!!,
                hardwarePublicKeyOpenSsh = key.publicKeyOpenSsh,
                certBytes = key.certificateBytes,
            )
        }
        val candidates = enabled.filter { holdsLoadablePrivateKey(it.keyType) }
        var skippedLocked = 0
        for (key in candidates) {
            if (!key.isEncrypted) {
                keys += ConnectionConfig.AgentIdentity(key.label, rawKeyToPem(key.privateKeyBytes, key.keyType))
                continue
            }
            // Passphrase-protected key: forwardable when the passphrase is
            // stored on the key (#290 opt-in). Pass the ORIGINAL encrypted
            // container + passphrase — same contract as the auth path — and
            // JSch decrypts at add time so ChannelAgentForwarding exposes it.
            // No stored passphrase → skip (JSch would silently drop it) and
            // count for the warning. (#377)
            val passphrase = sshKeyRepository.getStoredPassphrase(key.id)
            if (passphrase != null) {
                keys += ConnectionConfig.AgentIdentity(
                    key.label,
                    key.privateKeyBytes,
                    passphrase.toByteArray(Charsets.UTF_8),
                )
            } else {
                skippedLocked++
            }
        }
        return AgentIdentitiesResult(
            keys = keys,
            skippedLockedCount = skippedLocked,
            hadNoStoredKeys = allKeys.keys.isEmpty() && allKeys.hardware.isEmpty(),
            forwardAgentEnabled = true,
        )
    }

    /**
     * Build the list of identities to expose via SSH agent forwarding for
     * [profile]. Side-effectfully publishes a non-fatal warning to [warning]
     * when agent forwarding is enabled but the forwarded agent would end up
     * empty (e.g. all stored SSH keys are passphrase-protected, or none are
     * stored at all) — the connection itself still proceeds, the warning is
     * shown via snackbar so the user isn't left guessing why `ssh-add -l` on
     * the remote returns nothing.
     *
     * Passphrase-protected keys are forwarded when their passphrase is stored
     * on the key (#290 opt-in) — JSch decrypts at add time. Without a stored
     * passphrase they are skipped: ChannelAgentForwarding has no hook for
     * prompting at sign-request time. (#377) FIDO2/SK keys ARE forwarded
     * (#602): the per-signature CTAP touch IS the sign-time prompt.
     */
    private suspend fun agentIdentitiesFor(profile: ConnectionProfile): List<ConnectionConfig.AgentIdentity> {
        val result = computeAgentIdentities(profile)
        result.warningMessage?.let {
            if (agentWarningShownFor.add(profile.id)) {
                _warning.value = it
            }
        }
        return result.keys
    }

    /**
     * Convert raw private key bytes to PEM format that JSch can parse.
     * Delegates to SshKeyExporter which handles all formats correctly:
     * - PEM/OpenSSH: pass through
     * - Ed25519 32-byte seed: OpenSSH format (JSch can't parse PKCS#8 for Ed25519)
     * - PKCS#8 DER (RSA/ECDSA): wrapped in PEM
     */
    private fun rawKeyToPem(rawBytes: ByteArray, keyType: String): ByteArray {
        return SshKeyExporter.toPem(rawBytes, keyType)
    }

    /**
     * Ensure a connected session for a profile has a shell channel open.
     * Jump host sessions are connected but have no shell until the user opens one.
     * Runs the full session manager (tmux/screen) detection flow.
     */
    fun ensureShellForProfile(profileId: String) {
        val session = sshSessionManager.getSessionsForProfile(profileId)
            .firstOrNull { it.status == SshSessionManager.SessionState.Status.CONNECTED }
            ?: return
        if (sshSessionManager.isLiveTerminal(session.sessionId)) {
            // Shell open AND a terminal is attached — navigate directly. (A
            // dead/absent channel falls through to (re)open below, rather than
            // navigating to a tab that will never appear.)
            _navigateToTerminal.value = profileId
            return
        }

        viewModelScope.launch {
            _connectingProfileId.value = profileId
            try {
                // A RemoteCommand is the program for the channel itself; do
                // not replace it with a configured multiplexer command here.
                val profile = repository.getById(profileId)
                val sshSessionMgr = if (profile?.remoteCommand.isNullOrBlank()) {
                    resolveSessionManager(profile)
                } else {
                    SessionManager.NONE
                }
                val cmdOverride = profile?.sessionCommandOverride?.takeIf { it.isNotBlank() }
                    ?: preferencesRepository.sessionCommandOverride.first()
                val config = session.connectionConfig
                if (config != null) {
                    sshSessionManager.storeConnectionConfig(session.sessionId, config, sshSessionMgr, cmdOverride, profile?.postLoginCommand, profile?.postLoginBeforeSessionManager ?: false)
                }

                // Check for existing tmux/screen sessions
                val listCmd = sshSessionMgr.listCommand
                if (listCmd != null) {
                    val existingSessions = withContext(Dispatchers.IO) {
                        try {
                            val result = session.client.execCommand(listCmd)
                            if (result.exitStatus == 0) {
                                SessionManager.parseSessionList(sshSessionMgr, result.stdout)
                            } else emptyList()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    if (existingSessions.isNotEmpty()) {
                        _sessionSelection.value = SessionSelection(
                            sessionId = session.sessionId,
                            profileId = profileId,
                            managerLabel = sshSessionMgr.label,
                            sessionNames = existingSessions,
                            manager = sshSessionMgr,
                        )
                        _connectingProfileId.value = null
                        return@launch
                    }
                }

                finishConnect(session.sessionId, profileId)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to open shell"
            } finally {
                _connectingProfileId.value = null
            }
        }
    }

    // --- Port Forward Management ---

    fun portForwardRules(profileId: String): StateFlow<List<PortForwardRule>> =
        portForwardRepository.observeForProfile(profileId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun savePortForwardRule(rule: PortForwardRule) {
        viewModelScope.launch {
            val session = sshSessionManager.getSessionsForProfile(rule.profileId)
                .firstOrNull { it.status == SshSessionManager.SessionState.Status.CONNECTED }

            // If editing an existing rule on a live session, remove the old forward first
            if (session != null) {
                val oldForward = session.activeForwards.firstOrNull { it.ruleId == rule.id }
                if (oldForward != null) {
                    withContext(Dispatchers.IO) {
                        sshSessionManager.removePortForward(session.sessionId, oldForward)
                    }
                }
            }

            portForwardRepository.save(rule)

            // Activate the new/updated forward on the live session
            if (session != null && rule.enabled) {
                withContext(Dispatchers.IO) {
                    sshSessionManager.applyPortForwards(session.sessionId, listOf(rule.toForwardInfo()))
                }
            }
        }
    }

    fun deletePortForwardRule(ruleId: String, profileId: String) {
        viewModelScope.launch {
            // Deactivate on live session if connected
            val session = sshSessionManager.getSessionsForProfile(profileId)
                .firstOrNull { it.status == SshSessionManager.SessionState.Status.CONNECTED }
            if (session != null) {
                val forward = session.activeForwards.firstOrNull { it.ruleId == ruleId }
                if (forward != null) {
                    withContext(Dispatchers.IO) {
                        sshSessionManager.removePortForward(session.sessionId, forward)
                    }
                }
            }
            portForwardRepository.delete(ruleId)
        }
    }

    private fun PortForwardRule.toForwardInfo() = SshSessionManager.PortForwardInfo(
        ruleId = id,
        type = when (type) {
            PortForwardRule.Type.LOCAL -> SshSessionManager.PortForwardType.LOCAL
            PortForwardRule.Type.REMOTE -> SshSessionManager.PortForwardType.REMOTE
            PortForwardRule.Type.DYNAMIC -> SshSessionManager.PortForwardType.DYNAMIC
        },
        bindAddress = bindAddress,
        bindPort = bindPort,
        targetHost = targetHost,
        targetPort = targetPort,
    )

    fun disconnect(profileId: String) {
        viewModelScope.launch { connectionLogRepository.logEvent(profileId, ConnectionLog.Status.DISCONNECTED) }
        sessionManagerRegistry.disconnectProfile(profileId)
        // Tear down this profile's tunnel-dependent resources: close its VNC/
        // RDP Desktop tab (via the lease's parent-gone callback) AND release
        // its SSH dependent. Closing the tab here is required because when the
        // carrying SSH is a shared / user-opened session (e.g. a terminal the
        // VNC reused) it legitimately survives, so the SSH-teardown cascade
        // alone would leave the tab open over a now-detached tunnel (#121).
        sshSessionManager.teardownTunnelDependent(profileId)
        // Drop the WireGuard / Tailscale refcount this profile may have
        // acquired via TunnelResolver. No-op if the profile didn't go
        // through a tunnel; otherwise the underlying tunnel closes only
        // when its last dependent disconnects (#149).
        viewModelScope.launch { tunnelResolver.release(profileId) }
        updateServiceNotification()
    }

    private fun updateServiceNotification() {
        if (sessionManagerRegistry.hasActiveSessions()) {
            startForegroundServiceIfNeeded()
        } else {
            val intent = Intent(appContext, SshConnectionService::class.java)
            appContext.stopService(intent)
        }
    }

    fun dismissError() {
        _error.value = null
    }

    fun showError(message: String) {
        _error.value = message
    }

    fun dismissWarning() {
        _warning.value = null
    }

    private val _deploySuccess = MutableStateFlow(false)
    val deploySuccess: StateFlow<Boolean> = _deploySuccess.asStateFlow()

    fun dismissDeploySuccess() {
        _deploySuccess.value = false
    }

    /**
     * A fresh [SshClient] with this ViewModel's hardware-ceremony wiring
     * pre-attached: the FIDO2 authenticator and the security-chip signer.
     * Any connect whose auth comes from [resolveAuthMethods] /
     * [resolveAuthMethod] can fold either credential in, and SshClient
     * throws at assembly time when its collaborator is missing — an
     * unwired client turned "deploy a key" into an IllegalStateException
     * the moment the pool held a chip key. All construction sites in this
     * class go through here (a few legacy ones wire the full ceremony by
     * hand — connectSsh and connectSshSilent — see #732, deploy-key).
     */
    private fun newSshClient(): SshClient = SshClient().apply {
        fidoAuthenticator = this@ConnectionsViewModel.fidoAuthenticator
        hardwareKeySigner = keystoreSshKeySigner
        openKeychainClients = OpenKeychainClientFactory.from(appContext)
    }

    /**
     * Auto-deploy the first SSH key to a localhost/VM profile after a successful
     * password-based connect. Only fires if keys exist and haven't been deployed yet.
     */
    private fun maybeAutoDeployKey(profile: ConnectionProfile, password: String) {
        val isLocalVm = profile.host in listOf("localhost", "127.0.0.1")
        if (!isLocalVm) return

        viewModelScope.launch {
            val keys = sshKeyRepository.observeAll().first()
            if (keys.isEmpty()) return@launch

            // Check if key is already deployed by trying key auth
            // (security-chip keys can be in the pool resolveAuthMethod builds,
            // so the signer must be wired before connect).
            val testClient = newSshClient()
            try {
                val keyAuth = resolveAuthMethod(profile, "")
                if (keyAuth is ConnectionConfig.AuthMethod.Password) return@launch // no keys to deploy
                val config = ConnectionConfig(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authMethod = keyAuth,
                )
                withContext(Dispatchers.IO) {
                    testClient.connect(config)
                }
                // Key auth succeeded — already deployed
                testClient.disconnect()
                return@launch
            } catch (_: Exception) {
                testClient.disconnect()
            }

            // Key auth failed — deploy the first key
            val key = keys.first()
            val deployClient = newSshClient()
            try {
                val config = ConnectionConfig(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authMethod = ConnectionConfig.AuthMethod.Password(password),
                )
                withContext(Dispatchers.IO) {
                    deployClient.connect(config)
                }
                // Skip host key verification — we already verified during the connect

                val pubKey = key.publicKeyOpenSsh.trim()
                val command = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
                    "grep -qF '${pubKey.substringAfterLast(' ')}' ~/.ssh/authorized_keys 2>/dev/null || " +
                    "echo '${pubKey}' >> ~/.ssh/authorized_keys && " +
                    "chmod 600 ~/.ssh/authorized_keys"

                val result = withContext(Dispatchers.IO) {
                    deployClient.execCommand(command)
                }
                if (result.exitStatus == 0) {
                    _deploySuccess.value = true
                    Log.d(TAG, "Auto-deployed SSH key to ${LogRedact.host(profile.host, profile.port)}")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Auto-deploy key failed (non-fatal): ${e.message}")
            } finally {
                deployClient.disconnect()
            }
        }
    }

    fun deployKey(profile: ConnectionProfile, keyId: String, password: String) {
        viewModelScope.launch {
            _error.value = null
            val key = sshKeyRepository.getById(keyId)
            if (key == null) {
                _error.value = "SSH key not found"
                return@launch
            }

            // resolveAuthMethods can fold a security-chip key (or a pinned
            // FIDO key) into the chain — the factory wires both ceremonies.
            val client = newSshClient()
            // The deploy connection has to honour the profile's "Route through"
            // jump host, WireGuard tunnel, or legacy SOCKS/HTTP proxy — same
            // precedence the regular connect path uses (#143). If we
            // auto-create a jump session here we tear it down on failure to
            // avoid leaving an orphan jump connection behind.
            var autoCreatedJumpSessionId: String? = null
            try {
                // Authenticate the deploy connection with the profile's own
                // configured auth (existing SSH key, key+password chain, …) —
                // not a forced password. This is the ssh-copy-id case: use a
                // credential the server already trusts to append the new key,
                // so deploying to a key-only / YubiKey-only server works. The
                // password is only consumed if the profile's chain includes a
                // password element (or for a key passphrase / jump host).
                val config = ConnectionConfig(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authMethod = resolveAuthMethods(profile, password),
                )
                val proxy = withContext(Dispatchers.IO) {
                    resolveDeployProxy(profile, password) { autoCreated ->
                        autoCreatedJumpSessionId = autoCreated
                    }
                }
                try {
                    val hostKeyEntry = client.connect(
                        config,
                        proxy = proxy,
                    )
                    runTofuVerification(hostKeyEntry, clientToDisconnectOnReject = client)
                } catch (e: HostKeyAuthFailure) {
                    runTofuVerification(e.hostKey, clientToDisconnectOnReject = null)
                    throw e.cause ?: e
                }

                val pubKey = key.publicKeyOpenSsh.trim()
                val command = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
                    "echo '${pubKey}' >> ~/.ssh/authorized_keys && " +
                    "chmod 600 ~/.ssh/authorized_keys"

                val result = client.execCommand(command)
                if (result.exitStatus != 0) {
                    _error.value = "Deploy failed: ${result.stderr.ifBlank { "exit ${result.exitStatus}" }}"
                } else {
                    _deploySuccess.value = true
                }
            } catch (e: Exception) {
                _error.value = "Deploy failed: ${e.message ?: "unknown error"}"
                autoCreatedJumpSessionId?.let { sshSessionManager.removeSession(it) }
            } finally {
                client.disconnect()
            }
        }
    }

    /**
     * Build the proxy chain a deploy / probe connect should use to honour
     * the profile's "Route through" setting. Same precedence as the live
     * connect path: jump host > WireGuard tunnel > legacy SOCKS/HTTP proxy.
     * Reuses an existing connected jump session when one is open, otherwise
     * stands up a new one and reports its session id via [onAutoCreatedJump]
     * so the caller can tear it down if the dependent operation fails.
     */
    private suspend fun resolveDeployProxy(
        profile: ConnectionProfile,
        password: String,
        onAutoCreatedJump: (String) -> Unit,
    ): HavenProxy? {
        val jumpProfileId = profile.jumpProfileId
        if (jumpProfileId != null) {
            val (jumpSessionId, reused) = connectJumpHost(jumpProfileId, password)
            if (!reused) onAutoCreatedJump(jumpSessionId)
            return sshSessionManager.createProxyJump(jumpSessionId)
                ?: throw Exception("Jump host session not usable for tunneling")
        }
        return tunnelResolver.havenProxy(profile)
    }

    private fun startForegroundServiceIfNeeded() {
        val intent = Intent(appContext, SshConnectionService::class.java)
        appContext.startForegroundService(intent)
    }

    fun parseQuickConnect(input: String): ConnectionProfile? {
        val config = ConnectionConfig.parseQuickConnect(input) ?: return null
        return ConnectionProfile(
            label = "${config.username}@${config.host}",
            host = config.host,
            port = config.port,
            username = config.username,
        )
    }

    private fun UserPreferencesRepository.SessionManager.toSshSessionManager(): SessionManager =
        when (this) {
            UserPreferencesRepository.SessionManager.NONE -> SessionManager.NONE
            UserPreferencesRepository.SessionManager.TMUX -> SessionManager.TMUX
            UserPreferencesRepository.SessionManager.ZELLIJ -> SessionManager.ZELLIJ
            UserPreferencesRepository.SessionManager.SCREEN -> SessionManager.SCREEN
            UserPreferencesRepository.SessionManager.BYOBU -> SessionManager.BYOBU
            UserPreferencesRepository.SessionManager.HERDR -> SessionManager.HERDR
            UserPreferencesRepository.SessionManager.PSMUX -> SessionManager.PSMUX
        }

    private suspend fun resolveSessionManager(profile: ConnectionProfile?): SessionManager {
        val override = profile?.sessionManager
        return if (override != null) {
            try {
                SessionManager.valueOf(override)
            } catch (_: IllegalArgumentException) {
                preferencesRepository.sessionManager.first().toSshSessionManager()
            }
        } else {
            preferencesRepository.sessionManager.first().toSshSessionManager()
        }
    }

    // --- Silent (non-interactive) connect methods for group launch ---

    /**
     * Connect a profile without interactive dialogs. Throws on failure.
     * Does not set _connectingProfileId or emit _navigateToTerminal.
     */
    private suspend fun connectSshSilent(profile: ConnectionProfile) {
        val password = profile.sshPassword ?: ""
        val verboseEnabled = preferencesRepository.verboseLoggingEnabled.first()
        val verboseLogger = if (verboseEnabled) SshVerboseLogger() else null
        val client = SshConnectionFactory.create(sshEngineFromOptionsText(profile.sshOptions)).apply {
            fidoAuthenticator = this@ConnectionsViewModel.fidoAuthenticator
            hardwareKeySigner = this@ConnectionsViewModel.keystoreSshKeySigner
            openKeychainClients = OpenKeychainClientFactory.from(appContext)
            this.verboseLogger = verboseLogger
        }
        val sessionId = sshSessionManager.registerSession(profile.id, profile.label, client)
        var autoCreatedJumpSessionId: String? = null

        try {
            val jumpProfileId = profile.jumpProfileId
            val jumpSessionId = if (jumpProfileId != null) {
                val (jid, reused) = connectJumpHost(jumpProfileId, password)
                if (!reused) autoCreatedJumpSessionId = jid
                jid
            } else null

            if (jumpSessionId != null) {
                sshSessionManager.setJumpSessionId(sessionId, jumpSessionId)
            }

            withContext(Dispatchers.IO) {
                val authMethod = resolveAuthMethods(profile, password)
                val config = ConnectionConfig(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authMethod = authMethod,
                    sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
                    forwardAgent = profile.forwardAgent,
                    remoteCommand = profile.remoteCommand,
                    requestPty = profile.requestPty,
                    addressFamily = profile.addressFamilyForSsh,
                    bindAddress = profile.bindAddress,
                    agentIdentities = agentIdentitiesFor(profile),
                )
                val proxy = if (jumpSessionId != null) {
                    sshSessionManager.createProxyJump(jumpSessionId)
                        ?: throw Exception("Jump host session not usable for tunneling")
                } else {
                    tunnelResolver.havenProxy(profile)
                }
                val hostKeyEntry = client.connect(
                    config,
                    proxy = proxy,
                )

                // Silent TOFU is fail-closed: a background / workspace connect
                // never silently trusts an unknown OR changed host key. Trust is
                // established interactively via the host-key prompt. (#5)
                when (val result = verifyOrCaTrusted(hostKeyEntry)) {
                    is HostKeyResult.Trusted -> {}
                    is HostKeyResult.NewHost -> {
                        client.disconnect()
                        throw Exception(
                            "Unknown host key for ${profile.host} — open this connection from " +
                                "the Connections tab first to verify and trust its host key.",
                        )
                    }
                    is HostKeyResult.KeyChanged -> {
                        client.disconnect()
                        throw Exception("Host key changed for ${profile.host} — possible MITM")
                    }
                }

                val sshSessionMgr = if (profile.remoteCommand.isNullOrBlank()) {
                    resolveSessionManager(profile)
                } else {
                    SessionManager.NONE
                }
                val cmdOverride = profile.sessionCommandOverride?.takeIf { it.isNotBlank() }
                    ?: preferencesRepository.sessionCommandOverride.first()
                sshSessionManager.storeConnectionConfig(sessionId, config, sshSessionMgr, cmdOverride, profile.postLoginCommand, profile.postLoginBeforeSessionManager)
            }

            verboseLogger?.drain()?.let { pendingVerboseLogs[sessionId] = it }

            // Restore last session name if persisted, so the session manager
            // command (tmux -A, zellij --create, screen -dRR) reattaches to it.
            profile.lastSessionName?.let {
                sshSessionManager.setChosenSessionName(sessionId, it)
            }
            finishConnect(sessionId, profile.id, verboseLog = verboseLogger?.drain(), silent = true)
        } catch (e: Exception) {
            Log.e(TAG, "connectSshSilent failed for ${LogRedact.of(profile.label)}: ${e.message}", e)
            connectionLogRepository.logEvent(profile.id, ConnectionLog.Status.FAILED, details = e.message, verboseLog = verboseLogger?.drain())
            sshSessionManager.updateStatus(sessionId, SshSessionManager.SessionState.Status.ERROR)
            sshSessionManager.removeSession(sessionId)
            autoCreatedJumpSessionId?.let { sshSessionManager.removeSession(it) }
            throw e
        }
    }

}

/**
 * Whether a stored key's `privateKeyBytes` are private-key material Haven can
 * load, rather than a reference to a key held somewhere else.
 *
 * Two kinds fail that: FIDO2/SK keys (`sk-ssh-ed25519@openssh.com` and
 * friends), whose bytes are a credential handle, and provider-held keys
 * (#487), whose bytes name a key inside OpenKeychain. PEM-encoding either
 * throws "Invalid Key", neither can be forwarded as a software agent
 * identity, and signing with one puts a prompt in front of the user.
 *
 * Shared because the two callers had written the same rule out separately and
 * one of them had lost the provider half, so an OpenKeychain key was being
 * offered to JSch's agent repository on every connect and rejected with
 * `JSchException: invalid privatekey` (#487). Keeping it in one place is what
 * stops that happening again — a second copy is what caused it.
 */
internal fun holdsLoadablePrivateKey(keyType: String): Boolean =
    !keyType.startsWith("sk-") && keyType != OpenKeychainKeyData.KEY_TYPE
