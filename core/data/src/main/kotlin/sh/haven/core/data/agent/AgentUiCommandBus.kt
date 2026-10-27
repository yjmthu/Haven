package sh.haven.core.data.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton dispatch channel for [AgentUiCommand]. The agent transport
 * (`McpTools`) publishes here when the user — via an MCP client —
 * invokes a navigation/state-promotion verb. UI collectors (`HavenNavHost`,
 * `SftpViewModel`, future feature ViewModels) subscribe and react.
 *
 * Modeled on [sh.haven.app.navigation.DebugNavEvents] but Hilt-scoped
 * because it has real (not debug-only) wiring and needs to be injectable
 * into MCP tool handlers.
 *
 * ### Buffering
 *
 * `extraBufferCapacity = 1` and `replay = 0` means:
 *   - The most recent emission is briefly held while collectors process,
 *     so a fast-following burst does not drop on a slow collector.
 *   - New collectors do **not** see prior emissions, which is the right
 *     semantics here — UI verbs are *commands*, not state, and replaying
 *     them on a screen rotation would re-fire the navigation.
 *
 * `tryEmit` is non-suspending and never blocks the caller. If a UI is
 * not currently mounted (e.g. app backgrounded) the emission is simply
 * dropped, which is the correct fail-closed behaviour for this surface.
 */
@Singleton
class AgentUiCommandBus @Inject constructor() {
    private val _commands = MutableSharedFlow<AgentUiCommand>(
        extraBufferCapacity = 1,
        replay = 0,
    )
    val commands: SharedFlow<AgentUiCommand> = _commands.asSharedFlow()

    /**
     * Single-consume latch for SFTP-targeted commands, to close the
     * **cold-start race**: when an agent verb (e.g. `encrypt_file`,
     * `navigate_sftp_browser`) emits before the file-browser screen — and
     * thus [sh.haven.feature.sftp.SftpViewModel] — has mounted, only the
     * always-present app-level nav collector (`HavenNavHost`) sees the
     * `replay = 0` emission; it switches to the Files tab, but the
     * SftpViewModel subscribes *after* the emission and misses it.
     *
     * So the bus also holds the most recent SFTP-targeted command here.
     * The SFTP collector [takePendingSftpCommand]s on subscription (catching
     * a missed cold-start command) and [clearPendingSftp]s after handling a
     * live one (so the latch can't re-fire on a later remount / rotation).
     * Non-SFTP commands never touch the latch.
     */
    private val pendingSftpCommand = java.util.concurrent.atomic.AtomicReference<AgentUiCommand?>(null)

    /** Returns true when the command was buffered/delivered, false on overflow. */
    fun emit(command: AgentUiCommand): Boolean {
        if (command.isSftpTargeted()) pendingSftpCommand.set(command)
        return _commands.tryEmit(command)
    }

    /** Drain a command that arrived before the SFTP collector subscribed (cold-start). Single-consume. */
    fun takePendingSftpCommand(): AgentUiCommand? = pendingSftpCommand.getAndSet(null)

    /**
     * Peek the latched cold-start SFTP command **without** consuming it.
     * `HavenNavHost` uses this to switch to the Files tab (which mounts
     * [sh.haven.feature.sftp.SftpViewModel]); the SftpViewModel then consumes
     * it via [takePendingSftpCommand]. Without this peek the nav collector,
     * which also missed the `replay = 0` emission, would never switch tabs, so
     * the SftpViewModel would never be created to drain the latch.
     */
    fun peekPendingSftpCommand(): AgentUiCommand? = pendingSftpCommand.get()

    /** Clear the latch once [command] has been handled live, so a remount doesn't re-fire it. */
    fun clearPendingSftp(command: AgentUiCommand) {
        pendingSftpCommand.compareAndSet(command, null)
    }

    private fun AgentUiCommand.isSftpTargeted(): Boolean = when (this) {
        is AgentUiCommand.NavigateToSftpPath,
        is AgentUiCommand.EncryptFile,
        is AgentUiCommand.DecryptFile,
        -> true
        else -> false
    }
}
