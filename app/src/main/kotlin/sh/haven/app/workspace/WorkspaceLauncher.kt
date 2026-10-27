package sh.haven.app.workspace

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import sh.haven.core.data.agent.AgentUiCommand
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.WorkspaceItem
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.WorkspaceRepository
import sh.haven.core.data.repository.WorkspaceWithItems
import sh.haven.core.ssh.SessionManager
import sh.haven.core.ssh.SshSessionAttacher
import sh.haven.core.ssh.SshSessionManager
import javax.inject.Inject
import javax.inject.Singleton
import sh.haven.core.redact.LogRedact

private const val TAG = "WorkspaceLauncher"

/**
 * Restores a [sh.haven.core.data.db.entities.WorkspaceProfile].
 *
 * ### Terminals: per-profile plans, no UI bus
 *
 * Terminal items are grouped by connection profile into plans that run
 * **concurrently** — a slow or interactive host delays only its own
 * items, never the rest of the workspace. Within a plan, sessions
 * attach sequentially through [SshSessionAttacher]:
 *
 *  - a live terminal already on the item's session name → reused
 *    (relaunching a workspace is idempotent, no duplicate tabs);
 *  - a live/in-flight connection → the session attaches as another
 *    channel on it, directly — no `AgentUiCommand` hop, so nothing
 *    depends on which screens happen to be composed;
 *  - no connection at all → ONE interactive dial per plan via
 *    [AgentUiCommand.ConnectProfile] (auth prompts — password, FIDO
 *    tap, host key — surface on the Connections screen the user is
 *    already on), then the remaining items attach once CONNECTED.
 *    A dial that doesn't come up fails that plan's remaining items
 *    with a message; relaunching the workspace retries just those.
 *
 * Reticulum/Local terminals keep the bus path
 * ([AgentUiCommand.OpenTerminalSession]) — single-session transports
 * with their own managers.
 *
 * After the terminal plans settle, one
 * [AgentUiCommand.FocusTerminalSession] navigates to the Terminal
 * screen when at least one session came up.
 *
 * ### Non-terminal kinds
 *
 * FILE_BROWSER / DESKTOP dispatch over the bus as before,
 * strictly after the terminal plans settle so tunneled desktops attach
 * to already-up SSH sessions (`tunnelDependents`). Same caveat as the
 * existing `NavigateToSftpPath` verb: emissions for a screen the user
 * has never visited may drop because the page-scoped ViewModel isn't
 * alive yet. `DesktopViewModel` is nav-scoped and always alive.
 *
 * ### Cancellation
 *
 * Cooperative — [cancel] flips a flag checked before each item; items
 * not yet started mark `Skipped`.
 */
@Singleton
class WorkspaceLauncher @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val connectionRepository: ConnectionRepository,
    private val agentUiCommandBus: AgentUiCommandBus,
    private val sshSessionManager: SshSessionManager,
    private val sshSessionAttacher: SshSessionAttacher,
) {
    private val _state = MutableStateFlow<WorkspaceLaunchState>(WorkspaceLaunchState.Idle)
    val state: StateFlow<WorkspaceLaunchState> = _state.asStateFlow()

    @Volatile
    private var cancelRequested = false

    /**
     * Launch every item of [workspaceId]. Suspends until the launch
     * settles (terminal plans + non-terminal dispatch); the UI observes
     * [state] for live progress.
     */
    suspend fun launch(workspaceId: String) {
        cancelRequested = false

        val workspace = workspaceRepository.getWorkspace(workspaceId)
        if (workspace == null) {
            _state.value = WorkspaceLaunchState.Failed(
                workspaceId = workspaceId,
                workspaceName = "(unknown)",
                reason = "workspace not found",
                items = emptyList(),
            )
            Log.w(TAG, "launch($workspaceId) — not found")
            return
        }

        val sortedItems = sortByKindPriority(workspace.items)
        val sessionNames = resolveSessionNames(sortedItems)

        val progress = ProgressBoard(workspace.profile.id, workspace.profile.name, sortedItems)
        _state.value = progress.snapshot()
        Log.d(TAG, "launch ${workspace.profile.name} — ${sortedItems.size} items")

        // Terminal plans: one per profile, concurrent across profiles.
        val terminals = sortedItems.filter { it.kind == WorkspaceItem.Kind.TERMINAL }
        val liveSessionIds = coroutineScope {
            terminals.groupBy { it.connectionProfileId }.map { (profileId, items) ->
                async { runProfilePlan(profileId, items, sessionNames, progress) }
            }.awaitAll()
        }
        if (!cancelRequested) {
            liveSessionIds.firstOrNull { it != null }?.let {
                // Navigate to the Terminal screen now that sessions exist. The
                // tab-select part drops harmlessly if the screen isn't mounted
                // yet — adoption on mount shows the new tabs anyway.
                agentUiCommandBus.emit(AgentUiCommand.FocusTerminalSession(it))
            }
        }

        // Non-terminal kinds after the terminal plans settle, in priority
        // order, so tunneled desktops find their SSH session up.
        for (item in sortedItems.filter { it.kind != WorkspaceItem.Kind.TERMINAL }) {
            if (cancelRequested) {
                progress.update(item.id, ItemProgress.Status.Skipped, "cancelled")
                continue
            }
            progress.update(item.id, ItemProgress.Status.Running)
            val outcome = dispatchNonTerminal(item)
            progress.update(
                item.id,
                if (outcome.success) ItemProgress.Status.Succeeded else ItemProgress.Status.Failed,
                outcome.message,
            )
        }

        val final = progress.snapshot()
        _state.value = if (cancelRequested) {
            WorkspaceLaunchState.Cancelled(final.workspaceId, final.workspaceName, final.items)
        } else {
            // Mixed success counts as completed-with-failures rather than
            // overall Failed — partial workspaces are useful. The Snackbar
            // reads the items and shows the count.
            WorkspaceLaunchState.Completed(final.workspaceId, final.workspaceName, final.items)
        }
        Log.d(
            TAG,
            "launch ${workspace.profile.name} done: " +
                "${final.items.count { it.status == ItemProgress.Status.Succeeded }}/${final.items.size}",
        )

        writeBackResolvedNames(workspace, sessionNames, final.items)
    }

    /**
     * Self-heal pre-v76 workspaces: an item saved without a session name
     * that just launched under a fallback-resolved one gets that name
     * written back, so the next restore pins it explicitly instead of
     * re-deriving from [ConnectionProfile.lastSessionName] (which shifts
     * with whatever the user last had open). Only items that actually
     * succeeded are pinned; a failed dial proves nothing about the name.
     */
    private suspend fun writeBackResolvedNames(
        workspace: WorkspaceWithItems,
        sessionNames: Map<String, String?>,
        final: List<ItemProgress>,
    ) {
        val succeeded = final
            .filter { it.status == ItemProgress.Status.Succeeded }
            .map { it.itemId }
            .toSet()
        val patched = workspace.items.map { item ->
            val resolved = sessionNames[item.id]
            if (item.kind == WorkspaceItem.Kind.TERMINAL &&
                item.sessionName == null &&
                resolved != null &&
                item.id in succeeded
            ) {
                item.copy(sessionName = resolved)
            } else {
                item
            }
        }
        if (patched == workspace.items) return
        try {
            workspaceRepository.save(workspace.profile, patched.sortedBy { it.sortOrder })
            Log.i(TAG, "pinned ${patched.count { it !in workspace.items }} resolved session name(s) onto ${workspace.profile.name}")
        } catch (e: Exception) {
            // Best-effort: the launch already succeeded; a failed write-back
            // just means the next restore re-derives the names again.
            Log.w(TAG, "session-name write-back failed for ${workspace.profile.name}: ${e.message}")
        }
    }

    /** Cooperative cancel — items not yet started mark Skipped. */
    fun cancel() {
        cancelRequested = true
    }

    /**
     * Reset state to [WorkspaceLaunchState.Idle]. Called by the UI when
     * the snackbar dismisses, so a subsequent launch starts clean.
     */
    fun acknowledge() {
        if (_state.value !is WorkspaceLaunchState.Launching) {
            _state.value = WorkspaceLaunchState.Idle
        }
    }

    /**
     * Run one profile's terminal items sequentially. Returns a live
     * sessionId when at least one session attached (used to focus the
     * Terminal screen afterwards).
     */
    private suspend fun runProfilePlan(
        profileId: String?,
        items: List<WorkspaceItem>,
        sessionNames: Map<String, String?>,
        progress: ProgressBoard,
    ): String? {
        val profile = profileId?.let { connectionRepository.getById(it) }
        if (profile == null) {
            items.forEach { progress.update(it.id, ItemProgress.Status.Failed, "profile missing") }
            return null
        }
        if (!profile.isTerminal) {
            items.forEach {
                progress.update(it.id, ItemProgress.Status.Failed, "${profile.label} is not a terminal profile")
            }
            return null
        }
        val isPlainSsh = profile.isSsh

        var dialed = false
        var liveSessionId: String? = null
        // Ground truth from the host's own list command, fetched once as soon
        // as a connection is available (null until then / when unknowable). A
        // saved name missing from it still attaches — `new-session -A`
        // recreates it — but gets reported as recreated rather than silently
        // handing back an empty session (stale workspace vs. live server).
        var remoteNames: Set<String>? = null
        for (item in items) {
            if (cancelRequested) {
                progress.update(item.id, ItemProgress.Status.Skipped, "cancelled")
                continue
            }
            progress.update(item.id, ItemProgress.Status.Running)
            val name = sessionNames[item.id]
            if (isPlainSsh && name != null && remoteNames == null) {
                remoteNames = sshSessionAttacher.listRemoteSessions(profile.id)
                    ?.map { SessionManager.sanitizeSessionName(it) }
                    ?.toSet()
            }
            val wasGone = name != null &&
                remoteNames?.let { SessionManager.sanitizeSessionName(name) !in it } == true

            if (!isPlainSsh) {
                // Reticulum/Local: single-session transports, tab-only
                // path through their own session managers.
                val ok = emitWithRetry(AgentUiCommand.OpenTerminalSession(profile.id, sessionName = name))
                progress.update(
                    item.id,
                    if (ok) ItemProgress.Status.Succeeded else ItemProgress.Status.Failed,
                    if (ok) null else "ui bus overflow",
                )
                continue
            }

            when (val r = sshSessionAttacher.ensureAttached(profile.id, name)) {
                is SshSessionAttacher.Result.AlreadyLive -> {
                    liveSessionId = liveSessionId ?: r.sessionId
                    progress.update(item.id, ItemProgress.Status.Succeeded)
                }
                is SshSessionAttacher.Result.Attached -> {
                    liveSessionId = liveSessionId ?: r.sessionId
                    if (wasGone) {
                        Log.i(TAG, "session '$name' was gone on ${LogRedact.of(profile.label)} — recreated")
                    }
                    progress.update(
                        item.id,
                        ItemProgress.Status.Succeeded,
                        if (wasGone) "session '$name' was gone — recreated" else null,
                    )
                }
                is SshSessionAttacher.Result.Failed ->
                    progress.update(item.id, ItemProgress.Status.Failed, r.message)
                is SshSessionAttacher.Result.NoLiveConnection -> {
                    if (dialed) {
                        // The one dial this plan gets didn't produce a
                        // connection — don't stack another.
                        progress.update(item.id, ItemProgress.Status.Failed, "connection unavailable")
                        continue
                    }
                    dialed = true
                    // Interactive dial: prompts (password / FIDO / host key /
                    // session picker) surface on the Connections screen. The
                    // connect opens this item's tab itself, attached to [name].
                    if (!emitWithRetry(AgentUiCommand.ConnectProfile(profile.id, sessionName = name))) {
                        progress.update(item.id, ItemProgress.Status.Failed, "ui bus overflow")
                        continue
                    }
                    if (name == null) {
                        // No name to auto-attach: the connect shows the session
                        // picker, which never reaches CONNECTED until the user
                        // picks — report the dial and don't block on it.
                        progress.update(item.id, ItemProgress.Status.Succeeded)
                        continue
                    }
                    if (awaitProfileConnected(profile.id, CONNECT_TIMEOUT_MS)) {
                        progress.update(item.id, ItemProgress.Status.Succeeded)
                    } else {
                        progress.update(
                            item.id,
                            ItemProgress.Status.Failed,
                            "connection did not come up — relaunch the workspace to retry",
                        )
                    }
                }
            }
        }
        return liveSessionId
    }

    private suspend fun dispatchNonTerminal(item: WorkspaceItem): DispatchOutcome {
        val command = when (item.kind) {
            WorkspaceItem.Kind.FILE_BROWSER -> {
                val profile = item.connectionProfileId?.let { connectionRepository.getById(it) }
                    ?: return DispatchOutcome(false, "profile missing")
                if (!profile.isFileBrowser()) {
                    return DispatchOutcome(false, "${profile.label} is not a file-browser profile")
                }
                AgentUiCommand.NavigateToSftpPath(
                    profileId = profile.id,
                    path = item.path ?: "/",
                )
            }
            WorkspaceItem.Kind.DESKTOP -> {
                val profile = item.connectionProfileId?.let { connectionRepository.getById(it) }
                    ?: return DispatchOutcome(false, "profile missing")
                if (!profile.isDesktop) {
                    return DispatchOutcome(false, "${profile.label} is not a desktop profile")
                }
                AgentUiCommand.OpenRemoteDesktop(profile.id)
            }
            WorkspaceItem.Kind.TERMINAL ->
                error("terminal items go through runProfilePlan")
        }
        return if (emitWithRetry(command)) {
            DispatchOutcome(true, null)
        } else {
            DispatchOutcome(false, "ui bus overflow")
        }
    }

    /**
     * The bus has a 1-element buffer; concurrent plans can race it. Retry
     * a rejected emit briefly before reporting overflow.
     */
    private suspend fun emitWithRetry(command: AgentUiCommand): Boolean {
        repeat(EMIT_RETRIES) { attempt ->
            if (agentUiCommandBus.emit(command)) return true
            if (attempt < EMIT_RETRIES - 1) delay(EMIT_RETRY_DELAY_MS)
        }
        return false
    }

    /**
     * The tmux/zellij session each terminal item should reattach to, keyed by
     * item id. A workspace saved with the session-name feature carries it on
     * the item; older ones don't, so we fall back to the profile's remembered
     * open sessions ([ConnectionProfile.lastSessionName], pipe-delimited),
     * handed out in order to that profile's un-named terminal items — so an
     * existing workspace reattaches its sessions without a re-save. Names a
     * later item already claims explicitly are removed from the fallback pool
     * to avoid handing the same session to two tabs; the pool is also
     * de-duplicated (a `lastSessionName` that lists the same session more than
     * once must not hand it to two items — they'd collapse to one via the
     * attacher's AlreadyLive check, silently dropping a tab the user expects).
     */
    private suspend fun resolveSessionNames(items: List<WorkspaceItem>): Map<String, String?> {
        val terminals = items.filter { it.kind == WorkspaceItem.Kind.TERMINAL }
        val fallback = mutableMapOf<String, ArrayDeque<String>>()
        for (profileId in terminals.mapNotNull { it.connectionProfileId }.distinct()) {
            val explicit = terminals
                .filter { it.connectionProfileId == profileId }
                .mapNotNull { it.sessionName }
                .toSet()
            val remembered = connectionRepository.getById(profileId)?.lastSessionName
                ?.split("|")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && it !in explicit }
                ?.distinct()
                ?: emptyList()
            fallback[profileId] = ArrayDeque(remembered)
        }
        return terminals.associate { item ->
            val name = item.sessionName
                ?: item.connectionProfileId?.let { fallback[it]?.removeFirstOrNull() }
            item.id to name
        }
    }

    /**
     * Suspend until a CONNECTED session exists for [profileId], or [timeoutMs]
     * elapses. Watches the sessions flow directly (not
     * [SshSessionManager.awaitConnected], which returns false the instant it is
     * called before the just-emitted ConnectProfile has moved the session into
     * CONNECTING). Returns false on timeout so a failed dial fails the plan's
     * remaining items rather than hanging the launch.
     */
    private suspend fun awaitProfileConnected(profileId: String, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            sshSessionManager.sessions.first { sessions ->
                sessions.values.any {
                    it.profileId == profileId &&
                        it.status == SshSessionManager.SessionState.Status.CONNECTED
                }
            }
            true
        } ?: false

    /**
     * Thread-safe per-item progress, published to [_state] on every update.
     * Plans run concurrently, so updates arrive from several coroutines.
     */
    private inner class ProgressBoard(
        private val workspaceId: String,
        private val workspaceName: String,
        private val order: List<WorkspaceItem>,
    ) {
        private val lock = Any()
        private val map = order.associateTo(LinkedHashMap()) { item ->
            item.id to ItemProgress(
                itemId = item.id,
                kind = item.kind,
                connectionProfileId = item.connectionProfileId,
                status = ItemProgress.Status.Pending,
            )
        }

        fun update(itemId: String, status: ItemProgress.Status, message: String? = null) {
            val next = synchronized(lock) {
                map[itemId] = map.getValue(itemId).copy(status = status, message = message)
                snapshotLocked()
            }
            _state.value = next
        }

        fun snapshot(): WorkspaceLaunchState.Launching = synchronized(lock) { snapshotLocked() }

        private fun snapshotLocked() = WorkspaceLaunchState.Launching(
            workspaceId = workspaceId,
            workspaceName = workspaceName,
            items = order.map { map.getValue(it.id) },
        )
    }

    private data class DispatchOutcome(val success: Boolean, val message: String?)

    companion object {
        /**
         * How long a plan waits for its one from-cold dial to reach CONNECTED
         * before failing its remaining items. Generous enough to cover an
         * interactive host-key / password / FIDO step on the connect it
         * triggers. Only that profile's plan waits — the rest of the
         * workspace proceeds concurrently.
         */
        private const val CONNECT_TIMEOUT_MS = 45_000L

        private const val EMIT_RETRIES = 5
        private const val EMIT_RETRY_DELAY_MS = 100L

        /**
         * Rank items by kind so SSH terminals come up before tunneled
         * desktops attach. Stable within a kind: ties broken by the
         * user's stored [WorkspaceItem.sortOrder], then by id.
         */
        internal fun sortByKindPriority(items: List<WorkspaceItem>): List<WorkspaceItem> =
            items.sortedWith(
                compareBy(
                    { kindPriority(it.kind) },
                    { it.sortOrder },
                    { it.id },
                ),
            )

        private fun kindPriority(kind: WorkspaceItem.Kind): Int = when (kind) {
            WorkspaceItem.Kind.TERMINAL -> 0
            WorkspaceItem.Kind.FILE_BROWSER -> 2
            WorkspaceItem.Kind.DESKTOP -> 3
        }
    }
}

/**
 * True when the profile resolves to a file-browser tab. Mirrors the
 * `isTerminal` / `isDesktop` helpers but covers the SFTP / Local
 * file-browser kinds explicitly so workspace items don't have to
 * duplicate the predicate.
 */
private fun ConnectionProfile.isFileBrowser(): Boolean =
    isSsh || isLocal
