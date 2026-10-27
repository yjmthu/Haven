package sh.haven.feature.sftp

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import sh.haven.core.ssh.SshIoException
import sh.haven.core.ssh.sftp.SftpSession
import sh.haven.core.ssh.sftp.SftpWriteMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.et.EtSessionManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.security.posixShellQuote
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.ssh.SshSessionManager.SessionState
import sh.haven.feature.sftp.transport.FileBackend
import sh.haven.feature.sftp.transport.LocalFileBackend
import sh.haven.feature.sftp.transport.SftpTransport
import java.io.OutputStream
import javax.inject.Inject

private const val TAG = "SftpViewModel"

/** How many bytes must flow through a transfer before we update the queue row's
 *  `bytesTransferred` cursor. Smaller = tighter resume resolution at the cost of
 *  more DB writes. 1 MB strikes a balance — negligible replay on crash, cheap DB
 *  traffic on multi-GB SFTP uploads. */
private const val QUEUE_PROGRESS_PERSIST_BYTES = 1L * 1024 * 1024

/** Per-row retry budget for transient failures (connection drops, broken pipes).
 *  Permanent failures — missing source, permission denied — don't retry. */
private const val QUEUE_RETRY_ATTEMPTS = 3

/** First backoff in ms between retries; doubled each attempt (1s / 2s / 4s). */
private const val QUEUE_RETRY_BACKOFF_MS = 1_000L

data class SftpEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedTime: Long,
    val permissions: String,
    val mimeType: String = "",
    /** Owner name (SCP `ls -la`) or numeric UID (SFTP `SftpATTRS`). Empty if unknown. */
    val owner: String = "",
    /** Group name (SCP `ls -la`) or numeric GID (SFTP `SftpATTRS`). Empty if unknown. */
    val group: String = "",
    /**
     * The entry is a symlink. Directory symlinks are still presented as
     * navigable ([isDirectory] reflects the resolved target where the
     * backend supports it), but the paste walker never recurses through a
     * symlink discovered mid-walk — link cycles must terminate.
     */
    val isSymlink: Boolean = false,
)


enum class BackendType { SFTP, LOCAL }

/** Clipboard for cross-filesystem copy/move. */
data class FileClipboard(
    val entries: List<SftpEntry>,
    val sourceProfileId: String,
    val sourceBackendType: BackendType,
    val isCut: Boolean,
    /** Cached SFTP session from source — survives profile switch. */
    @Transient val sourceSftpSession: SftpSession? = null,
)

enum class SortMode {
    NAME_ASC, NAME_DESC, SIZE_ASC, SIZE_DESC, DATE_ASC, DATE_DESC
}

/**
 * Action to take when a paste target file already exists at the destination.
 *
 * Modelled on Windows Explorer's "File in Use" / "Replace or Skip" dialog,
 * driven from [SftpViewModel.conflictPrompt] and surfaced by the UI as a
 * single dialog with an "Apply to all" checkbox.
 *
 * [RESUME] is only offered when the destination is SFTP or LOCAL *and* the
 * existing file is smaller than the source — the only shape where an append-
 * from-offset actually recovers the partial transfer.
 */
enum class ConflictAction { RESUME, OVERWRITE, SKIP, RENAME }

/** One pending conflict-resolution prompt. UI calls [onChoice] exactly once. */
data class ConflictPrompt(
    val fileName: String,
    val sourceSize: Long,
    val destSize: Long,
    val canResume: Boolean,
    val onChoice: (action: ConflictAction, applyToAll: Boolean) -> Unit,
)

/** Transfer progress for download/upload operations. */
/** Columns [scanDocumentTree] needs — the whole point is fetching them in ONE query per directory. */
internal val SCAN_PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_SIZE,
)

/** One file found by [scanDocumentTree]. `docId` is a SAF document id, not a URI. */
internal data class ScannedFile(val docId: String, val relativePath: String, val length: Long)

/**
 * Walk a SAF document tree, issuing exactly ONE [queryChildren] call per directory
 * (#273). [queryChildren] must return a cursor over [SCAN_PROJECTION] for a document
 * id, or null; it is also where the caller checks for cancellation. Iterative, so a
 * deep tree can't overflow the stack. Directories are not emitted, only leaf files.
 */
internal suspend fun scanDocumentTree(
    rootDocId: String,
    rootName: String,
    queryChildren: suspend (docId: String) -> android.database.Cursor?,
    onProgress: (fileCount: Int) -> Unit = {},
): List<ScannedFile> {
    val files = mutableListOf<ScannedFile>()
    val pending = ArrayDeque<Pair<String, String>>() // docId -> relative prefix
    pending.addLast(rootDocId to rootName)
    while (pending.isNotEmpty()) {
        val (docId, prefix) = pending.removeLast()
        queryChildren(docId)?.use { c ->
            while (c.moveToNext()) {
                val childId = c.getString(0) ?: continue
                val childName = c.getString(1) ?: continue
                val childPath = "$prefix/$childName"
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    pending.addLast(childId to childPath)
                } else {
                    files.add(ScannedFile(childId, childPath, if (c.isNull(3)) 0L else c.getLong(3)))
                    if (files.size % 25 == 0) onProgress(files.size)
                }
            }
        }
    }
    return files
}

data class TransferProgress(
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (transferredBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

@HiltViewModel
class SftpViewModel @Inject constructor(
    private val sessionManager: SshSessionManager,
    private val moshSessionManager: MoshSessionManager,
    private val etSessionManager: EtSessionManager,
    private val repository: ConnectionRepository,
    private val ageIdentityRepository: sh.haven.core.data.repository.AgeIdentityRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val transportSelector: sh.haven.feature.sftp.transport.TransportSelector,
    private val sftpStreamServer: SftpStreamServer,
    private val pasteQueueDao: sh.haven.core.data.db.PasteQueueDao,
    private val agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
    private val attachCoordinator: sh.haven.feature.sftp.attach.TerminalAttachCoordinator,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    /**
     * Re-exposes [TerminalAttachCoordinator.pendingRequest] so the screen
     * can switch into folder-pick mode while the terminal awaits a
     * destination choice.
     */
    val attachRequest: StateFlow<sh.haven.feature.sftp.attach.TerminalAttachCoordinator.AttachRequest?> =
        attachCoordinator.pendingRequest

    val attachProgress: StateFlow<sh.haven.feature.sftp.attach.TerminalAttachCoordinator.Progress?> =
        attachCoordinator.progress

    /**
     * "Use this folder" — confirms the current path on the active profile
     * as the attach destination.
     */
    fun confirmAttachFolder() {
        val profileId = _activeProfileId.value ?: return
        attachCoordinator.confirmFolder(
            profileId = profileId,
            folderPath = _currentPath.value,
        )
    }

    /** Cancel the pending attach request — completes the deferred with null. */
    fun cancelAttach() {
        attachCoordinator.cancel()
    }

    init {
        // Subscribe to agent-driven UI commands so an MCP-issued navigation
        // verb can drive the file browser to a new profile/path. The
        // matching pager switch happens in HavenNavHost; both collectors
        // run in parallel against the same SharedFlow.
        viewModelScope.launch {
            agentUiCommandBus.commands
                // Cold-start race: an SFTP command emitted before this collector
                // subscribed (the Files screen hadn't mounted yet) is held in the
                // bus latch. onSubscription runs once we're subscribed — drain it.
                // It must NOT be handled inline: onSubscription executes
                // synchronously inside SftpViewModel.<init> (viewModelScope is
                // Main.immediate), before fields declared below the init block —
                // e.g. _activeProfileId — are initialized, and possibly during the
                // pager's forced remeasure when nav has just switched to Files.
                // Handle it on a FRESH dispatch — launch on the non-immediate
                // Dispatchers.Main (whose dispatch() always posts to the Looper, so
                // the body runs after construction unwinds) rather than the
                // viewModelScope default (Main.immediate, which would run it inline).
                // (Re-emitting is NOT enough — the buffered value is collected
                // synchronously within the same init run.) The live collect path
                // below is safe: nothing is buffered for it during init, so it only
                // ever runs post-construction. clearPendingSftp keeps the latch from
                // re-firing on a remount. See AgentUiCommandBus.
                .onSubscription {
                    agentUiCommandBus.takePendingSftpCommand()?.let { cmd ->
                        viewModelScope.launch(Dispatchers.Main) { handleAgentCommand(cmd) }
                    }
                }
                .collect { command ->
                    agentUiCommandBus.clearPendingSftp(command)
                    handleAgentCommand(command)
                }
        }
    }

    /**
     * Apply an agent-issued UI command to this file browser. Called live from
     * the bus collector and once on subscription to drain a cold-start command
     * the bus latched before this ViewModel existed (see AgentUiCommandBus).
     */
    private fun handleAgentCommand(command: sh.haven.core.data.agent.AgentUiCommand) {
                    when (command) {
                    is sh.haven.core.data.agent.AgentUiCommand.NavigateToSftpPath -> {
                        if (_activeProfileId.value != command.profileId) {
                            // Pass the target path INTO selectProfile so the
                            // session-open lands there directly. Calling a
                            // separate navigateTo() after selectProfile races
                            // the async home-probe/list and usually loses — the
                            // tab would open on home (e.g. /root) instead of the
                            // requested path (e.g. a USB mount's /mnt/sda1).
                            selectProfile(command.profileId, initialPath = command.path)
                        } else {
                            navigateTo(command.path)
                        }
                    }
                    is sh.haven.core.data.agent.AgentUiCommand.EncryptFile -> {
                        if (_activeProfileId.value != command.profileId) {
                            selectProfile(command.profileId)
                        }
                        encryptFile(partialEntry(command.path), command.recipients)
                    }
                    is sh.haven.core.data.agent.AgentUiCommand.DecryptFile -> {
                        if (_activeProfileId.value != command.profileId) {
                            selectProfile(command.profileId)
                        }
                        decryptFile(partialEntry(command.path))
                    }
                    else -> Unit
                }
    }

    private val _connectedProfiles = MutableStateFlow<List<ConnectionProfile>>(emptyList())
    val connectedProfiles: StateFlow<List<ConnectionProfile>> = _connectedProfiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()


    private val _currentPath = MutableStateFlow("/")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    /** age encryption identities, for the per-file Encrypt picker (recipient is public). */
    val ageIdentities: StateFlow<List<sh.haven.core.data.db.entities.AgeIdentityEntity>> =
        ageIdentityRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _allEntries = MutableStateFlow<List<SftpEntry>>(emptyList())
    private val _entries = MutableStateFlow<List<SftpEntry>>(emptyList())
    val entries: StateFlow<List<SftpEntry>> = _entries.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.NAME_ASC)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    private val _showHidden = MutableStateFlow(false)
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()

    enum class FilterMode { GLOB, REGEX }
    private val _fileFilter = MutableStateFlow("")
    val fileFilter: StateFlow<String> = _fileFilter.asStateFlow()
    private val _filterMode = MutableStateFlow(FilterMode.GLOB)
    val filterMode: StateFlow<FilterMode> = _filterMode.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _transferProgress = MutableStateFlow<TransferProgress?>(null)
    val transferProgress: StateFlow<TransferProgress?> = _transferProgress.asStateFlow()

    /** Non-null while a paste-time file conflict is awaiting the user's choice. */
    private val _conflictPrompt = MutableStateFlow<ConflictPrompt?>(null)
    val conflictPrompt: StateFlow<ConflictPrompt?> = _conflictPrompt.asStateFlow()

    /**
     * Resolution the user chose with "Apply to all" for the current paste batch.
     * Reset to null at the start of every [pasteFromClipboard] call. When set,
     * subsequent conflicts in this batch silently apply this action instead of
     * prompting again.
     */
    private var batchResolution: ConflictAction? = null

    /**
     * Partial wake lock held for the duration of an active paste. Without this,
     * the CPU can drop to a low-power state mid-transfer (phone locked, screen
     * off) and the simultaneous USB + network IO becomes unreliable — which on
     * some devices escalates all the way to a kernel watchdog reboot.
     */
    private var pasteWakeLock: android.os.PowerManager.WakeLock? = null

    /**
     * Observable count of files still pending in the persistent paste queue.
     * Drives the "Unfinished paste: N files remaining" banner at the top of
     * the SFTP screen. Non-zero means a previous paste was interrupted
     * (network drop, process death, reboot) and the user can tap Resume.
     */
    val pastePendingCount: StateFlow<Int> =
        pasteQueueDao.observeCountByStatus(sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_PENDING)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Observable total bytes remaining across pending queue rows — the
     * banner shows this in a human-readable form.
     */
    val pastePendingBytes: StateFlow<Long> =
        pasteQueueDao.observePendingBytes(sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_PENDING)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** ID of the queue row currently being transferred, so the progress monitor
     *  can throttle `bytesTransferred` updates back to the DB. Null when no
     *  transfer is in flight. */
    @Volatile
    private var currentQueueRowId: Long? = null
    @Volatile
    private var lastQueueRowBytesPersist: Long = 0L

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Emitted after a successful download with the destination URI for "Open" action. */
    data class DownloadResult(val fileName: String, val uri: Uri)
    private val _lastDownload = MutableStateFlow<DownloadResult?>(null)
    val lastDownload: StateFlow<DownloadResult?> = _lastDownload.asStateFlow()
    fun clearLastDownload() { _lastDownload.value = null }

    /**
     * Label of the transport currently servicing the active SSH profile —
     * `"SFTP"`, `"SCP"`, or null while non-SSH backends are active. Shown
     * as a badge in the path bar so users always know what they're on.
     */
    private val _activeTransportLabel = MutableStateFlow<String?>(null)
    val activeTransportLabel: StateFlow<String?> = _activeTransportLabel.asStateFlow()

    /** Upload conflict resolution. */
    enum class ConflictChoice { SKIP, REPLACE, REPLACE_ALL, SKIP_ALL }
    data class UploadConflict(
        val fileName: String,
        val deferred: CompletableDeferred<ConflictChoice>,
    )
    private val _uploadConflict = MutableStateFlow<UploadConflict?>(null)
    val uploadConflict: StateFlow<UploadConflict?> = _uploadConflict.asStateFlow()

    fun resolveConflict(choice: ConflictChoice) {
        _uploadConflict.value?.deferred?.complete(choice)
        _uploadConflict.value = null
    }

    /** Cross-filesystem clipboard. */
    private val _clipboard = MutableStateFlow<FileClipboard?>(null)
    val clipboard: StateFlow<FileClipboard?> = _clipboard.asStateFlow()

    fun copyToClipboard(entries: List<SftpEntry>, isCut: Boolean) {
        val profileId = _activeProfileId.value ?: return
        Log.d(TAG, "copyToClipboard: ${entries.size} entries, isCut=$isCut, profile=$profileId, " +
            "sftpSession=${sftpSession?.isConnected}")
        val backendType = if (_isLocalProfile.value) BackendType.LOCAL else BackendType.SFTP
        // Open a dedicated SFTP session for copy (separate from browse session)
        val copySession = if (backendType == BackendType.SFTP) {
            sessionManager.openSftpSession(profileId)
        } else null
        Log.d(TAG, "copyToClipboard: dedicated copy session=${copySession?.isConnected}")
        _clipboard.value = FileClipboard(
            entries = entries,
            sourceProfileId = profileId,
            sourceBackendType = backendType,
            isCut = isCut,
            sourceSftpSession = copySession,
        )
        _message.value = "${entries.size} item${if (entries.size > 1) "s" else ""} ${if (isCut) "cut" else "copied"}"
    }

    fun clearClipboard() {
        _clipboard.value = null
    }

    private var sftpSession: SftpSession? = null

    /**
     * Build an [SftpStreamServer.Opener] for [entry] on the currently
     * active profile. The opener captures [profileId] (not a channel) so
     * it re-resolves against whichever browse channel is live at read
     * time — important across reconnects.
     */
    private fun sftpOpener(profileId: String, path: String): SftpStreamServer.Opener =
        SftpStreamServer.Opener { offset ->
            val session = getOrOpenSession(profileId)
                ?: throw java.io.IOException("SFTP not connected")
            // SftpStreamServer.Opener.open is a non-suspend functional
            // interface (called from the HTTP server's worker thread) —
            // bridge to SftpSession's suspend API with runBlocking. The
            // calling thread is already happy to block on I/O.
            kotlinx.coroutines.runBlocking {
                session.openInputStream(path, if (offset > 0) offset else 0L)
            }
        }

    private fun guessContentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "ts" -> "video/mp2t"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/aac"
        "ogg", "oga" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "opus" -> "audio/opus"
        else -> "application/octet-stream"
    }

    /**
     * Best-effort MIME type for an "Open with…" hand-off (issue #173). Tries
     * the platform extension map first (covers documents/images/pdf), falls
     * back to the media table, then a wildcard so the chooser still appears
     * for types neither knows (e.g. .cbz).
     */
    private fun mimeTypeForName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        val media = guessContentType(name)
        return if (media != "application/octet-stream") media else "*/*"
    }

    /** Tracks whether the active profile is the local filesystem. */
    private val _isLocalProfile = MutableStateFlow(false)

    /** Synthetic profile for the always-present "Local" tab. */
    private val localProfile = ConnectionProfile(
        id = "local",
        label = "Local",
        host = "",
        username = "",
        connectionType = "LOCAL",
    )

    /** Per-profile state cache so tab switching preserves path and entries. */
    private data class ProfileBrowseState(
        val path: String,
        val allEntries: List<SftpEntry>,
    )
    private val profileStateCache = mutableMapOf<String, ProfileBrowseState>()

    init {
        // Restore persisted sort mode
        viewModelScope.launch {
            val saved = preferencesRepository.sftpSortMode.first()
            _sortMode.value = try {
                SortMode.valueOf(saved)
            } catch (_: IllegalArgumentException) {
                SortMode.NAME_ASC
            }
        }
    }

    fun syncConnectedProfiles() {
        viewModelScope.launch {
            // Collect profile IDs from SSH sessions
            val sshProfileIds = sessionManager.sessions.value.values
                .filter { it.status == SessionState.Status.CONNECTED }
                .map { it.profileId }
                .toSet()

            // Collect profile IDs from mosh sessions whose bootstrap SSH client is
            // still ALIVE — mosh's own UDP transport can't carry SFTP, so file
            // browsing rides the SSH client kept alive from bootstrap. Some servers
            // close that SSH once mosh-server takes over; JSch's session.isConnected
            // keeps reporting true until an RST lands (no round-trip), so a stale
            // isConnected check surfaced a Files tab that shows but never lists (#413,
            // reported by dkoppenh). Probe the transport for real (isAlive opens a
            // bounded exec channel and waits for the server's confirmation) — in
            // parallel, so a dead session's timeout doesn't serialise with the rest.
            val moshProfileIds = coroutineScope {
                moshSessionManager.sessions.value.values
                    .filter { it.status == MoshSessionManager.SessionState.Status.CONNECTED }
                    .map { s ->
                        async {
                            if ((s.sshClient as? SshClient)?.isAlive(3_000L) == true) s.profileId else null
                        }
                    }
                    .awaitAll()
                    .filterNotNull()
                    .toSet()
            }

            // Collect profile IDs from ET sessions that have a live SSH client
            val etProfileIds = etSessionManager.sessions.value.values
                .filter {
                    it.status == EtSessionManager.SessionState.Status.CONNECTED &&
                        it.sshClient != null
                }
                .map { it.profileId }
                .toSet()

            val connectedProfileIds = sshProfileIds + moshProfileIds + etProfileIds

            val profiles = withContext(Dispatchers.IO) { repository.getAll() }
            val remoteProfiles = profiles.filter { it.id in connectedProfileIds }
            _connectedProfiles.value = listOf(localProfile) + remoteProfiles

            if (connectedProfileIds.isEmpty() && _activeProfileId.value == null) {
                // No remote connections — auto-select local
                selectProfile("local")
                return@launch
            }

            // Auto-select first connected profile if none selected. The
            // synthetic "local" tab is a valid active selection even though it's
            // never in connectedProfileIds (no session to connect).
            val selectableIds = connectedProfileIds + "local"
            if (_activeProfileId.value == null || _activeProfileId.value !in selectableIds) {
                _connectedProfiles.value.firstOrNull()?.let { selectProfile(it.id) }
            }
        }
    }

    fun selectProfile(profileId: String, initialPath: String? = null) {
        Log.d(TAG, "selectProfile: $profileId (prev=${_activeProfileId.value}, clipboard=${_clipboard.value != null}, initialPath=$initialPath)")

        // Save current profile's browse state before switching
        _activeProfileId.value?.let { prevId ->
            profileStateCache[prevId] = ProfileBrowseState(
                path = _currentPath.value,
                allEntries = _allEntries.value,
            )
        }

        val isLocal = profileId == "local"
        _isLocalProfile.value = isLocal
        _activeProfileId.value = profileId
        sftpSession = null

        // Restore cached state if available — but when an explicit initialPath
        // is requested (NavigateToSftpPath to a specific dir), bypass the cache
        // so we land on the requested path, not the last-browsed one.
        val cached = if (initialPath != null) null else profileStateCache[profileId]
        if (cached != null) {
            _currentPath.value = cached.path
            _allEntries.value = cached.allEntries
            // Re-derive the visible list from the CURRENT global filter (show-hidden /
            // name filter) rather than the list cached when this tab was last active —
            // otherwise the toolbar's show-hidden icon (global) and this tab's contents
            // disagree after you toggle it on another tab.
            applyFilter()
            // Still need to re-establish the backend connection
            when {
                isLocal -> { /* no connection needed */ }
                else -> {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            sftpSession = sessionManager.openSftpSession(profileId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to pre-warm SFTP session for $profileId", e)
                        }
                    }
                }
            }
        } else {
            val landing = initialPath ?: "/"
            _currentPath.value = landing
            _allEntries.value = emptyList()
            _entries.value = emptyList()
            when {
                isLocal -> loadDirectoryEntries(landing)
                else -> openSftpAndList(profileId, landing)
            }
        }
    }

    fun navigateTo(path: String) {
        _activeProfileId.value ?: return
        _currentPath.value = path
        _selectedPaths.value = emptySet()
        loadDirectoryEntries(path)
    }

    fun navigateUp() {
        val current = _currentPath.value
        if (current == "/") return
        val parent = current.trimEnd('/').substringBeforeLast('/', "/")
        val target = if (parent.isEmpty()) "/" else parent
        // For local files, skip unreadable parent directories and jump to root
        if (_isLocalProfile.value && target != "/" && !java.io.File(target).canRead()) {
            navigateTo("/")
        } else {
            navigateTo(target)
        }
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
        _allEntries.value = sortEntries(_allEntries.value, mode)
        applyFilter()
        // Persist the choice
        viewModelScope.launch {
            preferencesRepository.setSftpSortMode(mode.name)
        }
    }

    fun toggleShowHidden() {
        _showHidden.value = !_showHidden.value
        applyFilter()
    }

    fun setFileFilter(pattern: String) {
        _fileFilter.value = pattern
        applyFilter()
    }

    fun setFilterMode(mode: FilterMode) {
        _filterMode.value = mode
        applyFilter()
    }

    private fun applyFilter() {
        val all = _allEntries.value
        var filtered = if (_showHidden.value) all else all.filter { !it.name.startsWith(".") }

        val pattern = _fileFilter.value
        if (pattern.isNotEmpty()) {
            val regex = try {
                when (_filterMode.value) {
                    FilterMode.REGEX -> Regex(pattern, RegexOption.IGNORE_CASE)
                    FilterMode.GLOB -> globToRegex(pattern)
                }
            } catch (_: Exception) {
                null
            }
            if (regex != null) {
                filtered = filtered.filter { regex.containsMatchIn(it.name) }
            }
        }

        _entries.value = filtered
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            when (glob[i]) {
                '*' -> sb.append(".*")
                '?' -> sb.append(".")
                '.' -> sb.append("\\.")
                '[' -> {
                    sb.append('[')
                    i++
                    if (i < glob.length && glob[i] == '!') {
                        sb.append('^')
                        i++
                    }
                    while (i < glob.length && glob[i] != ']') {
                        if (glob[i] == '\\' && i + 1 < glob.length) {
                            sb.append("\\${glob[i + 1]}")
                            i++
                        } else {
                            sb.append(glob[i])
                        }
                        i++
                    }
                    sb.append(']')
                }
                '\\' -> {
                    i++
                    if (i < glob.length) sb.append(Regex.escape(glob[i].toString()))
                }
                else -> {
                    if (glob[i] in "(){}+|^$") sb.append("\\")
                    sb.append(glob[i])
                }
            }
            i++
        }
        sb.append("$")
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    fun refresh() {
        _activeProfileId.value ?: return
        loadDirectoryEntries(_currentPath.value)
    }

    /** Whether the active profile is the local filesystem. */
    fun isLocalProfile(): Boolean = _isLocalProfile.value

    /** True when the local file browser needs MANAGE_EXTERNAL_STORAGE permission. */
    val needsStoragePermission: Boolean
        get() = _isLocalProfile.value &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            !android.os.Environment.isExternalStorageManager()

    fun downloadFile(entry: SftpEntry, destinationUri: Uri) {
        val profileId = _activeProfileId.value ?: return
        viewModelScope.launch {
            try {
                _loading.value = true
                _transferProgress.value = TransferProgress(entry.name, entry.size, 0)
                val outputStream: OutputStream = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(destinationUri)
                        ?: throw IllegalStateException("Cannot open output stream")
                }
                outputStream.use { out ->
                    val transport = currentSshTransport() ?: throw IllegalStateException("Not connected")
                    transport.download(entry.path, out, entry.size) { transferred, total ->
                        _transferProgress.value = TransferProgress(entry.name, total, transferred)
                    }
                }
                _lastDownload.value = DownloadResult(entry.name, destinationUri)
                _message.value = appContext.getString(R.string.sftp_downloaded_file, entry.name)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _error.value = "Download failed: ${e.message}"
            } finally {
                _loading.value = false
                _transferProgress.value = null
            }
        }
    }

    // ── age file encryption (VISION §2) ──────────────────────────────────
    //
    // Encrypt/decrypt compose on a download → transform → upload
    // scaffolding via the shared [resolveInputFile] / [uploadCacheFile]
    // helpers, so they light up every backend at once.

    /** Build a minimal [SftpEntry] from a path for agent-driven (bus) encrypt/decrypt — size/mtime probed as needed by the op. */
    private fun partialEntry(path: String): SftpEntry = SftpEntry(
        name = path.substringAfterLast('/').ifEmpty { path },
        path = path,
        isDirectory = false,
        size = 0L,
        modifiedTime = 0L,
        permissions = "",
    )

    /** Encrypt [entry] to `<name>.age` in the same folder, for [recipients] (`age1…` strings). Non-destructive. */
    fun encryptFile(entry: SftpEntry, recipients: List<String>) {
        if (recipients.isEmpty()) {
            _error.value = appContext.getString(R.string.sftp_age_no_identity)
            return
        }
        val profileId = _activeProfileId.value ?: return
        val outName = "${entry.name}.age"
        runAgeJob(entry, profileId, outName, "🔒 Encrypting ${entry.name}") { input, output ->
            sh.haven.core.security.AgeFile.encrypt(input, output, recipients)
        }
    }

    /** Decrypt a `.age` [entry] in place (strips `.age`) using any stored identity. */
    fun decryptFile(entry: SftpEntry) {
        val profileId = _activeProfileId.value ?: return
        val outName = entry.name.removeSuffix(".age").ifBlank { "${entry.name}.decrypted" }
        viewModelScope.launch {
            val secrets = ageIdentityRepository.getAll().mapNotNull { ageIdentityRepository.fetchSecret(it.id) }
            if (secrets.isEmpty()) {
                _error.value = appContext.getString(R.string.sftp_age_no_identity)
                return@launch
            }
            runAgeJobSuspending(entry, profileId, outName, "🔓 Decrypting ${entry.name}") { input, output ->
                sh.haven.core.security.AgeFile.decrypt(input, output, secrets)
            }
        }
    }

    private fun runAgeJob(
        entry: SftpEntry,
        profileId: String,
        outName: String,
        progressLabel: String,
        transform: (java.io.InputStream, java.io.OutputStream) -> Unit,
    ) {
        viewModelScope.launch { runAgeJobSuspending(entry, profileId, outName, progressLabel, transform) }
    }

    private suspend fun runAgeJobSuspending(
        entry: SftpEntry,
        profileId: String,
        outName: String,
        progressLabel: String,
        transform: (java.io.InputStream, java.io.OutputStream) -> Unit,
    ) {
        val sourceDir = entry.path.substringBeforeLast('/', "").ifEmpty { "/" }
        val destPath = if (sourceDir == "/") "/$outName" else "$sourceDir/$outName"
        val cacheIn = java.io.File(appContext.cacheDir, "age_in_${entry.name}")
        val cacheOut = java.io.File(appContext.cacheDir, outName)
        try {
            _loading.value = true
            val srcFile = resolveInputFile(entry, profileId, cacheIn, "⬇ Reading ${entry.name}")
            _transferProgress.value = TransferProgress(progressLabel, 0, 0)
            withContext(Dispatchers.IO) {
                srcFile.inputStream().use { input ->
                    cacheOut.outputStream().use { output -> transform(input, output) }
                }
            }
            uploadCacheFile(cacheOut, destPath, profileId, "⬆ Uploading $outName")
            _message.value = appContext.getString(R.string.sftp_saved_file_to, outName, sourceDir)
            if (_currentPath.value == sourceDir) refresh()
        } catch (e: sh.haven.core.security.AgeFile.AgeException) {
            Log.w(TAG, "age job failed", e)
            _error.value = appContext.getString(R.string.sftp_age_failed)
        } catch (e: Exception) {
            Log.e(TAG, "age job failed", e)
            _error.value = appContext.getString(R.string.sftp_age_failed)
        } finally {
            _loading.value = false
            _transferProgress.value = null
            cacheIn.delete() // a no-op for local (srcFile is the original, never in cacheDir)
            cacheOut.delete()
        }
    }

    /** Return a readable [java.io.File] for [entry]: the original for local, else a downloaded cache copy at [cacheTarget]. */
    private suspend fun resolveInputFile(
        entry: SftpEntry,
        profileId: String,
        cacheTarget: java.io.File,
        label: String,
    ): java.io.File {
        if (_isLocalProfile.value) return java.io.File(entry.path)
        _transferProgress.value = TransferProgress(label, entry.size, 0)
        withContext(Dispatchers.IO) {
            when {
                else -> {
                    val session = getOrOpenSession(profileId) ?: throw IllegalStateException("Not connected")
                    cacheTarget.outputStream().use { out ->
                        session.download(entry.path, out) { transferred, total ->
                            _transferProgress.value = TransferProgress(label, total, transferred)
                        }
                    }
                }
            }
        }
        return cacheTarget
    }

    /** Upload [cacheFile] to [destPath] on the active backend. */
    private suspend fun uploadCacheFile(
        cacheFile: java.io.File,
        destPath: String,
        profileId: String,
        label: String,
    ) {
        when {
            _isLocalProfile.value -> withContext(Dispatchers.IO) {
                cacheFile.copyTo(java.io.File(destPath), overwrite = true)
            }
            else -> {
                val session = getOrOpenSession(profileId) ?: throw IllegalStateException("Not connected")
                val total = cacheFile.length()
                _transferProgress.value = TransferProgress(label, total, 0)
                cacheFile.inputStream().use { input ->
                    session.upload(input, total, destPath) { transferred, _ ->
                        _transferProgress.value = TransferProgress(label, total, transferred)
                    }
                }
            }
        }
    }

    fun uploadFile(fileName: String, sourceUri: Uri) {
        val profileId = _activeProfileId.value ?: return
        val destPath = _currentPath.value.trimEnd('/') + "/" + fileName
        Log.d(TAG, "Upload: '$fileName' -> '$destPath' (source: $sourceUri)")
        viewModelScope.launch {
            try {
                // Check for conflict before uploading
                val (proceed, _) = checkConflict(fileName, null)
                if (!proceed) {
                    _message.value = "Skipped $fileName"
                    return@launch
                }
                _loading.value = true
                // Get source file size for progress
                val fileSize = appContext.contentResolver.query(sourceUri, null, null, null, null)?.use { cursor ->
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (cursor.moveToFirst() && sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L
                } ?: -1L
                _transferProgress.value = TransferProgress(fileName, fileSize, 0)
                withContext(Dispatchers.IO) {
                    val inputStream = appContext.contentResolver.openInputStream(sourceUri)
                        ?: throw IllegalStateException("Cannot open input stream")
                    inputStream.use { input ->
                        val transport = currentSshTransport() ?: throw IllegalStateException("Not connected")
                        transport.upload(input, fileSize, destPath) { transferred, total ->
                            _transferProgress.value = TransferProgress(fileName, total, transferred)
                        }
                    }
                    Log.d(TAG, "Upload complete: '$destPath'")
                }
                _message.value = "Uploaded $fileName"
                refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed", e)
                _error.value = "Upload failed: ${e.message}"
            } finally {
                _loading.value = false
                _transferProgress.value = null
            }
        }
    }

    fun deleteEntry(entry: SftpEntry) {
        val profileId = _activeProfileId.value ?: return
        viewModelScope.launch {
            try {
                _loading.value = true
                val backend = currentFileBackend() ?: throw IllegalStateException("Not connected")
                backend.delete(entry.path, entry.isDirectory)
                _message.value = "Deleted ${entry.name}"
                refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Delete failed", e)
                _error.value = "Delete failed: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    fun renameEntry(entry: SftpEntry, newName: String) {
        val profileId = _activeProfileId.value ?: return
        viewModelScope.launch {
            try {
                _loading.value = true
                val parentPath = _currentPath.value
                val newPath = if (parentPath.isEmpty() || parentPath == "/") newName
                    else "${parentPath.trimEnd('/')}/$newName"
                val backend = currentFileBackend() ?: throw IllegalStateException("Not connected")
                backend.rename(entry.path, newPath)
                _message.value = "Renamed to $newName"
                refresh()
            } catch (e: UnsupportedOperationException) {
                // #415: some DocumentsProviders don't implement rename (Termux's is
                // one — create/read/write/delete work, rename doesn't). Surface that
                // plainly instead of a raw "Rename failed: Rename not supported".
                Log.w(TAG, "Rename not supported by this backend", e)
                _error.value = appContext.getString(R.string.sftp_rename_not_supported)
            } catch (e: Exception) {
                Log.e(TAG, "Rename failed", e)
                _error.value = "Rename failed: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    // ===== Multi-select =====

    /**
     * Paths (absolute, matching [SftpEntry.path]) currently selected in the
     * file list. Non-empty means the UI is in selection mode — the top bar
     * switches to a contextual action bar and taps toggle selection instead
     * of navigating / opening.
     */
    private val _selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedPaths: StateFlow<Set<String>> = _selectedPaths.asStateFlow()

    /**
     * Target for the permissions dialog. Non-null opens the dialog. When
     * a single entry is supplied the dialog edits that entry's mode and
     * writes it back via [chmodEntry]; when [batch] is true it applies the
     * mode to the full selection via [chmodSelected].
     */
    data class ChmodRequest(val entry: SftpEntry?, val currentMode: Int, val batch: Boolean)

    private val _chmodRequest = MutableStateFlow<ChmodRequest?>(null)
    val chmodRequest: StateFlow<ChmodRequest?> = _chmodRequest.asStateFlow()

    /** Open the permissions dialog for a single entry. */
    fun openChmodDialog(entry: SftpEntry) {
        val mode = permissionsStringToMode(entry.permissions) ?: MODE_0644
        _chmodRequest.value = ChmodRequest(entry = entry, currentMode = mode, batch = false)
    }

    /**
     * Open the permissions dialog for the current multi-selection. The
     * seed mode is the first selected entry's mode if unambiguous, else
     * 0644 as a neutral default.
     */
    fun openChmodDialogForSelection() {
        val targets = selectedEntries()
        if (targets.isEmpty()) return
        val modes = targets.mapNotNull { permissionsStringToMode(it.permissions) }.toSet()
        val seed = if (modes.size == 1) modes.single() else MODE_0644
        _chmodRequest.value = ChmodRequest(entry = null, currentMode = seed, batch = true)
    }

    fun dismissChmodDialog() { _chmodRequest.value = null }

    /**
     * Target for the chown dialog. When [batch] is true, applies the
     * entered `user` / `user:group` string to every selected entry;
     * otherwise just to [entry]. [currentOwner] is a pre-fill for the
     * text field — parsed from the first 8 chars of the permissions
     * string is too fragile, so we leave it blank unless we can parse
     * it cheaply.
     */
    data class ChownRequest(val entry: SftpEntry?, val currentOwner: String, val batch: Boolean)

    private val _chownRequest = MutableStateFlow<ChownRequest?>(null)
    val chownRequest: StateFlow<ChownRequest?> = _chownRequest.asStateFlow()

    fun openChownDialog(entry: SftpEntry) {
        val seed = formatOwnerGroup(entry.owner, entry.group)
        _chownRequest.value = ChownRequest(entry = entry, currentOwner = seed, batch = false)
        // Over the SFTP subsystem JSch only exposes numeric UID/GID. Resolve
        // to human-readable names asynchronously via a remote `ls -ld`
        // so the dialog first appears with "1000:1000" and then swaps in
        // "ian:ian" once the lookup returns.
        if (looksNumeric(seed) && !_isLocalProfile.value) {
            viewModelScope.launch {
                resolveOwnerName(entry.path)?.let { resolved ->
                    if (_chownRequest.value?.entry == entry) {
                        _chownRequest.value = ChownRequest(entry = entry, currentOwner = resolved, batch = false)
                    }
                }
            }
        }
    }

    fun openChownDialogForSelection() {
        val targets = selectedEntries()
        if (targets.isEmpty()) return
        // Pre-fill only if the whole selection shares the same owner:group,
        // otherwise leave blank so the user has to type it explicitly.
        val owners = targets.map { formatOwnerGroup(it.owner, it.group) }.toSet()
        val seed = if (owners.size == 1) owners.single() else ""
        _chownRequest.value = ChownRequest(entry = null, currentOwner = seed, batch = true)
        if (owners.size == 1 && looksNumeric(seed)) {
            viewModelScope.launch {
                resolveOwnerName(targets.first().path)?.let { resolved ->
                    if (_chownRequest.value?.batch == true) {
                        _chownRequest.value = ChownRequest(entry = null, currentOwner = resolved, batch = true)
                    }
                }
            }
        }
    }

    private fun looksNumeric(s: String): Boolean =
        s.isNotEmpty() && s.all { it.isDigit() || it == ':' }

    /**
     * Portable remote lookup: `ls -ld -- PATH` columns 3 and 4 are user
     * and group. Works on GNU, BSD, busybox, macOS. Returns null if the
     * command fails or the output doesn't parse — in that case the
     * dialog keeps its numeric placeholder.
     */
    private suspend fun resolveOwnerName(path: String): String? {
        val profileId = _activeProfileId.value ?: return null
        val ssh = sessionManager.getSshClientForProfile(profileId) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val cmd = "LC_ALL=C ls -ld -- ${posixShellQuote(path)}"
                val r = ssh.execCommand(cmd)
                if (r.exitStatus != 0) return@withContext null
                val parts = r.stdout.trim().split(Regex("\\s+"))
                if (parts.size < 4) null else "${parts[2]}:${parts[3]}"
            } catch (e: Exception) {
                Log.w(TAG, "resolveOwnerName failed for $path: ${e.message}")
                null
            }
        }
    }

    private fun formatOwnerGroup(owner: String, group: String): String = when {
        owner.isEmpty() && group.isEmpty() -> ""
        group.isEmpty() -> owner
        else -> "$owner:$group"
    }

    fun dismissChownDialog() { _chownRequest.value = null }

    /** True while at least one entry is selected. */
    val selectionMode: StateFlow<Boolean> = _selectedPaths
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleSelection(entry: SftpEntry) {
        _selectedPaths.value = _selectedPaths.value.toMutableSet().apply {
            if (contains(entry.path)) remove(entry.path) else add(entry.path)
        }
    }

    fun selectAll() {
        _selectedPaths.value = _entries.value.map { it.path }.toSet()
    }

    fun clearSelection() {
        _selectedPaths.value = emptySet()
    }

    /** Resolve current selection to the matching [SftpEntry] objects, preserving list order. */
    private fun selectedEntries(): List<SftpEntry> {
        val selected = _selectedPaths.value
        return _entries.value.filter { it.path in selected }
    }

    /**
     * Delete every selected entry. Keeps going on individual failures and
     * reports aggregate success/failure counts through [_message] / [_error]
     * at the end. Clears the selection and refreshes the listing on
     * completion.
     */
    fun deleteSelected() {
        val targets = selectedEntries()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _loading.value = true
            var deleted = 0
            val failures = mutableListOf<String>()
            for (entry in targets) {
                try {
                    deleteOne(entry)
                    deleted++
                } catch (e: Exception) {
                    Log.e(TAG, "Delete failed for ${entry.path}", e)
                    failures.add("${entry.name}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            clearSelection()
            if (failures.isEmpty()) {
                _message.value = "Deleted $deleted item${if (deleted != 1) "s" else ""}"
            } else {
                _error.value = "Deleted $deleted, failed ${failures.size}: ${failures.first()}"
            }
            _loading.value = false
            refresh()
        }
    }

    /**
     * Chmod every selected entry to [mode]. Reports how many were
     * applied vs skipped. Directories are chmod'd non-recursively —
     * entries inside a selected directory keep their existing modes.
     */
    fun chmodSelected(mode: Int) {
        val targets = selectedEntries()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _loading.value = true
            var applied = 0
            val failures = mutableListOf<String>()
            for (entry in targets) {
                try {
                    chmodOne(entry, mode)
                    applied++
                } catch (e: UnsupportedOperationException) {
                    failures.add("${entry.name}: permissions not supported on this backend")
                    break // same answer for every entry on this backend
                } catch (e: Exception) {
                    Log.e(TAG, "chmod failed for ${entry.path}", e)
                    failures.add("${entry.name}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            clearSelection()
            if (failures.isEmpty()) {
                _message.value = "Permissions set on $applied item${if (applied != 1) "s" else ""}"
            } else {
                _error.value = "Applied $applied, failed ${failures.size}: ${failures.first()}"
            }
            _loading.value = false
            refresh()
        }
    }

    fun chownSelected(owner: String) {
        val targets = selectedEntries()
        if (targets.isEmpty() || owner.isBlank()) return
        viewModelScope.launch {
            _loading.value = true
            var applied = 0
            val failures = mutableListOf<String>()
            for (entry in targets) {
                try {
                    chownOne(entry, owner)
                    applied++
                } catch (e: UnsupportedOperationException) {
                    failures.add("${entry.name}: ownership not supported on this backend")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "chown failed for ${entry.path}", e)
                    failures.add("${entry.name}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            clearSelection()
            if (failures.isEmpty()) {
                _message.value = "Owner set on $applied item${if (applied != 1) "s" else ""}"
            } else {
                _error.value = "Applied $applied, failed ${failures.size}: ${failures.first()}"
            }
            _loading.value = false
            refresh()
        }
    }

    /** chown a single entry via shell `chown user:group -- path` on the remote. */
    fun chownEntry(entry: SftpEntry, owner: String) {
        if (owner.isBlank()) return
        viewModelScope.launch {
            try {
                _loading.value = true
                chownOne(entry, owner)
                _message.value = "Owner updated on ${entry.name}"
                refresh()
            } catch (e: UnsupportedOperationException) {
                _error.value = "Ownership not supported on this backend"
            } catch (e: Exception) {
                Log.e(TAG, "chown failed", e)
                _error.value = "chown failed: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    /** chmod a single entry (used by both single-entry and batch paths). */
    fun chmodEntry(entry: SftpEntry, mode: Int) {
        viewModelScope.launch {
            try {
                _loading.value = true
                chmodOne(entry, mode)
                _message.value = "Permissions updated on ${entry.name}"
                refresh()
            } catch (e: UnsupportedOperationException) {
                _error.value = "Permissions not supported on this backend"
            } catch (e: Exception) {
                Log.e(TAG, "chmod failed", e)
                _error.value = "chmod failed: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    private suspend fun deleteOne(entry: SftpEntry) {
        val backend = currentFileBackend() ?: throw IllegalStateException("Not connected")
        backend.delete(entry.path, entry.isDirectory)
    }

    private suspend fun chmodOne(entry: SftpEntry, mode: Int) {
        when {
            _isLocalProfile.value -> withContext(Dispatchers.IO) {
                android.system.Os.chmod(entry.path, mode)
            }
            else -> {
                val transport = currentSshTransport() ?: throw IllegalStateException("Not connected")
                transport.chmod(entry.path, mode)
            }
        }
    }

    /** Whether the active backend understands POSIX permissions. */
    fun supportsPermissions(): Boolean = true

    /**
     * Whether the active backend supports changing ownership. Same set
     * as [supportsPermissions] minus local files — an unrooted Android
     * app can't chown outside its own UID.
     */
    fun supportsOwnership(): Boolean = !_isLocalProfile.value

    private suspend fun chownOne(entry: SftpEntry, owner: String) {
        when {
            _isLocalProfile.value ->
                throw UnsupportedOperationException("chown not supported on local files")
            else -> {
                val transport = currentSshTransport() ?: throw IllegalStateException("Not connected")
                transport.chown(entry.path, owner)
            }
        }
    }

    fun uploadFolder(folderUri: Uri) {
        val profileId = _activeProfileId.value ?: return
        val destBase = _currentPath.value
        viewModelScope.launch {
            try {
                _loading.value = true

                // Enumerate the picked tree OFF the main thread (#273). DocumentFile
                // is the trap here, not just the thread: listFiles() throws away the
                // cursor's columns and rebuilds each child from its document id, so
                // every subsequent child.name / child.isDirectory / child.length()
                // costs a separate ContentResolver.query() — ~3 cross-process round
                // trips per file. Query each directory ONCE with a full projection
                // instead, and report scan progress so a slow provider looks busy
                // rather than hung.
                //
                // Measured against Termux's DocumentsProvider (OnePlus 13, flat tree):
                //   400 files:  1628 ms -> 155 ms
                //   4000 files: see #273 — the old walk is ~linear in file count,
                //               the new one is ~flat (one query per directory).
                data class FileItem(val uri: Uri, val relativePath: String, val length: Long)
                val enumerated = withContext(Dispatchers.IO) {
                    val rootId = runCatching { DocumentsContract.getTreeDocumentId(folderUri) }
                        .getOrNull() ?: return@withContext null
                    val rootName = DocumentFile.fromTreeUri(appContext, folderUri)?.name ?: "upload"
                    val scanned = scanDocumentTree(
                        rootDocId = rootId,
                        rootName = rootName,
                        queryChildren = { docId ->
                            currentCoroutineContext().ensureActive() // cancellable mid-scan
                            appContext.contentResolver.query(
                                DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, docId),
                                SCAN_PROJECTION, null, null, null,
                            )
                        },
                        onProgress = { n ->
                            _transferProgress.value = TransferProgress("Scanning… $n files", 0, 0)
                        },
                    )
                    val files = scanned.map {
                        FileItem(
                            DocumentsContract.buildDocumentUriUsingTree(folderUri, it.docId),
                            it.relativePath,
                            it.length,
                        )
                    }
                    Triple(rootName, files, files.sumOf { it.length })
                } ?: return@launch
                val (folderName, files, initialTotalBytes) = enumerated

                // Check if the folder already exists in the destination
                val (proceed, _) = checkConflict(folderName, null)
                if (!proceed) {
                    _message.value = "Skipped $folderName"
                    return@launch
                }

                val totalFiles = files.size
                var completedFiles = 0
                var totalBytes = initialTotalBytes
                var transferredBytes = 0L

                // Destination directories we've already created. Folder upload used
                // to call the backend's single-level mkdir per file, so the first
                // file inside a nested directory died with SSH_FX_NO_SUCH_FILE (its
                // grandparent didn't exist) — i.e. uploading ANY folder with
                // subdirectories to SFTP failed outright (#273, found on-device).
                // Route through ensureDestParent(), the mkdir-p helper the
                // copy/paste path already uses, once per directory rather than
                // once per file.
                val destType = BackendType.SFTP
                val ensuredDirs = mutableSetOf<String>()

                for (item in files) {
                    val destPath = destBase.trimEnd('/') + "/" + item.relativePath
                    val destDir = destPath.substringBeforeLast('/')
                    val fileName = item.relativePath.substringAfterLast('/')
                    val fileSize = item.length

                    if (ensuredDirs.add(destDir)) {
                        withContext(Dispatchers.IO) {
                            ensureDestParent(destType, profileId, destPath)
                        }
                    }

                    _transferProgress.value = TransferProgress(
                        "${completedFiles + 1}/$totalFiles: $fileName",
                        totalBytes,
                        transferredBytes,
                    )

                    withContext(Dispatchers.IO) {
                        val session = getOrOpenSession(profileId) ?: throw IllegalStateException("Not connected")
                        appContext.contentResolver.openInputStream(item.uri)?.use { input ->
                            session.upload(input, fileSize, destPath) { _, _ -> }
                        }
                    }

                    completedFiles++
                    transferredBytes += fileSize
                }

                _message.value = "Uploaded $totalFiles files from $folderName"
                refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Folder upload failed", e)
                _error.value = "Folder upload failed: ${e.message}"
            } finally {
                _loading.value = false
                _transferProgress.value = null
            }
        }
    }

    fun createDirectory(name: String) {
        val profileId = _activeProfileId.value ?: return
        val parentPath = _currentPath.value
        val fullPath = parentPath.trimEnd('/') + "/" + name
        viewModelScope.launch {
            try {
                _loading.value = true
                val backend = currentFileBackend() ?: throw IllegalStateException("Not connected")
                backend.mkdir(fullPath)
                _message.value = "Created $name"
                refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Create directory failed", e)
                _error.value = "Create folder failed: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Check if a file exists in the current directory listing.
     * Returns true if upload should proceed, false if skipped.
     * Shows a conflict dialog if the file already exists.
     */
    private suspend fun checkConflict(
        fileName: String,
        bulkChoice: ConflictChoice?,
    ): Pair<Boolean, ConflictChoice?> {
        // If user already chose Replace All or Skip All, use that
        if (bulkChoice == ConflictChoice.REPLACE_ALL) return true to bulkChoice
        if (bulkChoice == ConflictChoice.SKIP_ALL) return false to bulkChoice

        val existingNames = _allEntries.value.map { it.name }.toSet()
        if (fileName !in existingNames) return true to bulkChoice

        // File exists — ask the user
        val deferred = CompletableDeferred<ConflictChoice>()
        _uploadConflict.value = UploadConflict(fileName, deferred)
        val choice = deferred.await()
        return when (choice) {
            ConflictChoice.REPLACE, ConflictChoice.REPLACE_ALL -> true to choice
            ConflictChoice.SKIP, ConflictChoice.SKIP_ALL -> false to choice
        }
    }

    /** Shared counter for recursive copy progress. */
    private val pasteFileCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val pasteInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Pre-walked total number of files in the paste selection, or 0 when unknown. */
    private val pasteTotalFiles = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Called as each file transfer *starts*, before bytes move. Shows
     * "Uploading N of M: filename" when we know the total (pre-walked),
     * otherwise "Uploading N: filename". Replaces the old post-completion
     * progress update so the user sees live movement through the batch
     * instead of a long "Preparing..." on the first (often largest) file.
     */
    private fun beforePasteFile(fileName: String, fileSize: Long = 0, resumeFrom: Long = 0) {
        val current = pasteFileCount.incrementAndGet()
        val total = pasteTotalFiles.get()
        val label = if (total > 0) {
            "Uploading $current of $total: $fileName"
        } else {
            "Uploading $current: $fileName"
        }
        _transferProgress.value = TransferProgress(label, fileSize, resumeFrom)
    }

    /**
     * Resolve a [FileBackend] for a paste destination described by queue-row
     * coordinates: LOCAL needs no session, SFTP reuses/opens the cached
     * session (with mosh/ET fallback).
     */
    private suspend fun destFileBackend(
        destType: BackendType,
        destProfileId: String,
    ): FileBackend? = when (destType) {
        BackendType.LOCAL -> LocalFileBackend(appContext)
        BackendType.SFTP -> getOrOpenSession(destProfileId)?.let { s -> SftpTransport({ s }) }
    }

    /**
     * Probe the destination backend for an existing file at [destPath].
     *
     * Returns the size in bytes if a regular file exists, null if the path is
     * absent, a directory, or the probe itself failed (treat as "not known to
     * exist" so we default to the existing silent-overwrite behaviour rather
     * than blocking the paste on a flaky backend).
     */
    private suspend fun probeDestFile(
        destType: BackendType,
        destProfileId: String,
        destPath: String,
    ): Long? = try {
        destFileBackend(destType, destProfileId)
            ?.stat(destPath)
            ?.takeIf { !it.isDirectory }
            ?.size
    } catch (_: Exception) {
        null // stat throws for missing entries; fail open on probe errors
    }

    /**
     * Generate a Windows-style unique destination path by appending `(1)`, `(2)`
     * etc. before any extension, looping until [probeDestFile] returns null.
     * Falls back to the original path after 999 attempts (which would also be
     * a sign something else is wrong).
     */
    private suspend fun findUniqueName(
        destType: BackendType,
        destProfileId: String,
        destPath: String,
    ): String {
        for (i in 1..999) {
            val candidate = uniqueNameCandidate(destPath, i)
            if (probeDestFile(destType, destProfileId, candidate) == null) return candidate
        }
        return destPath
    }

    /**
     * If [destPath] already exists, suspend until the user picks a [ConflictAction]
     * (unless [batchResolution] is already set by "Apply to all"). Returns null
     * when the destination is free and the caller should proceed as normal.
     *
     * Must be called from a coroutine — suspends on the UI via a
     * [CompletableDeferred] completed by the dialog.
     */
    private suspend fun resolveConflictIfExists(
        entry: SftpEntry,
        destType: BackendType,
        destProfileId: String,
        destPath: String,
    ): Pair<ConflictAction, Long>? {
        val destSize = withContext(Dispatchers.IO) {
            probeDestFile(destType, destProfileId, destPath)
        } ?: return null

        batchResolution?.let { return it to destSize }

        val deferred = CompletableDeferred<Pair<ConflictAction, Boolean>>()
        _conflictPrompt.value = ConflictPrompt(
            fileName = entry.name,
            sourceSize = entry.size,
            destSize = destSize,
            canResume = (destType == BackendType.SFTP || destType == BackendType.LOCAL) &&
                destSize in 1 until entry.size,
            onChoice = { action, applyToAll -> deferred.complete(action to applyToAll) },
        )
        val (choice, applyToAll) = deferred.await()
        _conflictPrompt.value = null
        if (applyToAll) batchResolution = choice
        return choice to destSize
    }

    private fun acquirePasteWakeLock() {
        if (pasteWakeLock?.isHeld == true) return
        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pasteWakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "sh.haven.app:paste",
            ).apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L) // 1 hour safety cap; released in finally regardless
            }
        } catch (e: SecurityException) {
            // WAKE_LOCK permission missing (pre-v5.24.11 builds didn't declare
            // it in the manifest). Paste should still work — the wake lock is
            // a stability-on-device nice-to-have, not a correctness dependency.
            Log.w(TAG, "Could not acquire paste wake lock: ${e.message}")
            pasteWakeLock = null
        }
    }

    private fun releasePasteWakeLock() {
        try {
            pasteWakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Releasing paste wake lock failed: ${e.message}")
        }
        pasteWakeLock = null
    }

    /**
     * Listing function for the clipboard's source backend, used by the
     * paste walker. LOCAL stays on raw [java.io.File] listing —
     * [LocalFileBackend.list] falls back to the synthetic storage-roots
     * view for unreadable paths, which must never leak into a recursive
     * copy. An unavailable session skips the subtree (empty list),
     * matching the legacy walker.
     */
    private fun sourceLister(cb: FileClipboard): suspend (String) -> List<SftpEntry> =
        when (cb.sourceBackendType) {
            BackendType.LOCAL -> { path: String ->
                java.io.File(path).listFiles()?.map { f ->
                    SftpEntry(f.name, f.absolutePath, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified() / 1000, "")
                } ?: emptyList()
            }
            BackendType.SFTP -> { path: String ->
                val session = cb.sourceSftpSession ?: sessionManager.openSftpSession(cb.sourceProfileId)
                session?.let { SftpTransport({ it }).list(path) } ?: emptyList()
            }
        }

    /**
     * Pre-walk the clipboard entries to count total leaf files. Best-effort:
     * any listing error yields a partial count (or zero for that subtree),
     * which is fine — the UI falls back to "Uploading N:" without a total.
     */
    private suspend fun countPasteFiles(cb: FileClipboard): Int {
        val lister = sourceLister(cb)
        return cb.entries.sumOf { entry ->
            try {
                walkPasteLeaves(listOf(entry), "", lister).size
            } catch (e: Exception) {
                Log.w(TAG, "Pre-walk failed for ${entry.path}: ${e.message}")
                0
            }
        }
    }

    /** Flatten the clipboard into ordered leaf paste operations (see [walkPasteLeaves]). */
    private suspend fun enumerateLeaves(cb: FileClipboard, destRootPath: String): List<PasteLeaf> =
        walkPasteLeaves(cb.entries, destRootPath, sourceLister(cb))

    /**
     * mkdir-p for the parent of [destPath] on the destination backend.
     * Best-effort — a failure is logged and left for the copy itself to
     * surface, matching the legacy behaviour.
     */
    private suspend fun ensureDestParent(
        destType: BackendType,
        destProfileId: String,
        destPath: String,
    ) {
        val parent = destPath.substringBeforeLast('/', "").takeIf { it.isNotEmpty() && it != "/" }
            ?: return
        try {
            destFileBackend(destType, destProfileId)?.mkdir(parent)
        } catch (e: Exception) {
            Log.w(TAG, "ensureDestParent failed for $parent: ${e.message}")
        }
    }

    /**
     * Delete a single leaf file at the source after a cut+paste has copied
     * it to the destination. Directories left behind by cut operations are
     * not rmdir'd — the queue works on leaves only and a future pass can
     * plumb per-batch directory cleanup.
     */
    private fun deleteSourceLeaf(
        sourceType: BackendType,
        sourceProfileId: String,
        sourcePath: String,
    ) {
        try {
            when (sourceType) {
                BackendType.LOCAL -> java.io.File(sourcePath).delete()
                BackendType.SFTP -> {
                    val session = sessionManager.openSftpSession(sourceProfileId)
                    if (session != null) kotlinx.coroutines.runBlocking { session.rm(sourcePath) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "deleteSourceLeaf failed for $sourcePath: ${e.message}")
        }
    }

    fun pasteFromClipboard() {
        val cb = _clipboard.value ?: return
        val destProfileId = _activeProfileId.value ?: return
        val destPath = _currentPath.value
        Log.d(TAG, "pasteFromClipboard: ${cb.entries.size} entries from ${cb.sourceBackendType}(${cb.sourceProfileId}) " +
            "to dest=$destProfileId, destPath=$destPath")

        val destType = if (_isLocalProfile.value) BackendType.LOCAL else BackendType.SFTP

        viewModelScope.launch {
            try {
                _loading.value = true
                pasteInProgress.set(true)
                pasteFileCount.set(0)
                pasteTotalFiles.set(0)
                batchResolution = null
                acquirePasteWakeLock()
                _transferProgress.value = TransferProgress("Scanning selection…", 0, 0)

                val leaves = withContext(Dispatchers.IO) {
                    enumerateLeaves(cb, destPath)
                }

                // Apply conflict resolution to top-level leaves (anything a
                // user directly put on the clipboard). Deeper descendants
                // inherit the batch-resolution if set, otherwise default to
                // OVERWRITE — matching the old crossCopyDir behaviour.
                val resolved = mutableListOf<PasteLeaf>()
                val actions = mutableListOf<ConflictAction>()
                val resumeBytes = mutableListOf<Long>()
                var skipped = 0
                for (leaf in leaves) {
                    if (!leaf.isTopLevel) {
                        resolved.add(leaf)
                        actions.add(batchResolution ?: ConflictAction.OVERWRITE)
                        resumeBytes.add(0L)
                        continue
                    }
                    val virtualEntry = SftpEntry(leaf.sourceName, leaf.sourcePath, false, leaf.size, 0, "")
                    val check = resolveConflictIfExists(virtualEntry, destType, destProfileId, leaf.destPath)
                    if (check == null) {
                        resolved.add(leaf)
                        actions.add(ConflictAction.OVERWRITE)
                        resumeBytes.add(0L)
                        continue
                    }
                    val (action, destSize) = check
                    when (action) {
                        ConflictAction.SKIP -> skipped++
                        ConflictAction.RENAME -> {
                            val newPath = withContext(Dispatchers.IO) {
                                findUniqueName(destType, destProfileId, leaf.destPath)
                            }
                            resolved.add(leaf.copy(destPath = newPath))
                            actions.add(ConflictAction.RENAME)
                            resumeBytes.add(0L)
                        }
                        ConflictAction.RESUME -> {
                            resolved.add(leaf)
                            actions.add(ConflictAction.RESUME)
                            resumeBytes.add(destSize)
                        }
                        ConflictAction.OVERWRITE -> {
                            resolved.add(leaf)
                            actions.add(ConflictAction.OVERWRITE)
                            resumeBytes.add(0L)
                        }
                    }
                }

                val rows = resolved.mapIndexed { i, leaf ->
                    sh.haven.core.data.db.entities.PasteQueueEntry(
                        indexInBatch = i,
                        sourceBackendType = cb.sourceBackendType.name,
                        sourceProfileId = cb.sourceProfileId,
                        sourceRemoteName = null,
                        sourcePath = leaf.sourcePath,
                        sourceName = leaf.sourceName,
                        sourceSize = leaf.size,
                        destBackendType = destType.name,
                        destProfileId = destProfileId,
                        destRemote = null,
                        destPath = leaf.destPath,
                        isCut = cb.isCut,
                        conflictAction = actions[i].name,
                        bytesTransferred = resumeBytes[i],
                    )
                }

                withContext(Dispatchers.IO) {
                    pasteQueueDao.clear()
                    pasteQueueDao.insertAll(rows)
                }
                _clipboard.value = null

                pasteTotalFiles.set(rows.size)
                executePasteQueue()

                val copied = pasteFileCount.get()
                val stillPending = withContext(Dispatchers.IO) {
                    pasteQueueDao.countByStatus(sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_PENDING)
                }
                val verb = if (cb.isCut) "Moved" else "Copied"
                _message.value = when {
                    stillPending > 0 -> "$verb $copied — $stillPending still pending (Resume to retry)"
                    skipped > 0 -> "$verb $copied files — skipped $skipped"
                    else -> "$verb $copied files"
                }
                if (stillPending == 0) {
                    withContext(Dispatchers.IO) { pasteQueueDao.clear() }
                }
                refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Paste failed", e)
                _error.value = "Paste failed: ${e.message}"
            } finally {
                pasteInProgress.set(false)
                _loading.value = false
                _transferProgress.value = null
                _conflictPrompt.value = null
                batchResolution = null
                releasePasteWakeLock()
            }
        }
    }

    /**
     * Drive the persisted queue end-to-end. Called from both the initial
     * paste and [resumePasteQueue]. Continues across per-row failures — a
     * single flaky file doesn't block the rest of the batch.
     */
    private suspend fun executePasteQueue() {
        while (true) {
            val row = withContext(Dispatchers.IO) {
                pasteQueueDao.getByStatus(sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_PENDING).firstOrNull()
            } ?: break
            withContext(Dispatchers.IO) { executeQueueRow(row) }
        }
    }

    /**
     * Execute a single paste-queue row. Retries transient failures (connection
     * drops, per-call IO errors) up to [QUEUE_RETRY_ATTEMPTS] times with
     * exponential backoff, then marks the row failed-but-pending so the user
     * can retry via the resume banner.
     */
    private suspend fun executeQueueRow(row: sh.haven.core.data.db.entities.PasteQueueEntry) {
        val sourceType = BackendType.valueOf(row.sourceBackendType)
        val destType = BackendType.valueOf(row.destBackendType)
        val virtualCb = FileClipboard(
            entries = emptyList(),
            sourceProfileId = row.sourceProfileId,
            sourceBackendType = sourceType,
            isCut = row.isCut,
        )
        val virtualEntry = SftpEntry(
            name = row.sourceName, path = row.sourcePath, isDirectory = false,
            size = row.sourceSize, modifiedTime = 0, permissions = "",
        )
        val action = try { ConflictAction.valueOf(row.conflictAction) } catch (_: Exception) { ConflictAction.OVERWRITE }
        var resumeFromByte = if (action == ConflictAction.RESUME) row.bytesTransferred else 0L

        currentQueueRowId = row.id
        lastQueueRowBytesPersist = resumeFromByte
        var lastError: Throwable? = null
        try {
            ensureDestParent(destType, row.destProfileId, row.destPath)
            var attempt = 0
            while (true) {
                try {
                    crossCopyFile(
                        virtualCb, virtualEntry,
                        destType, row.destProfileId,
                        row.destPath, resumeFromByte,
                    )
                    break
                } catch (e: Exception) {
                    if (!isTransientTransferError(e) || attempt >= QUEUE_RETRY_ATTEMPTS - 1) throw e
                    attempt++
                    lastError = e
                    val delayMs = QUEUE_RETRY_BACKOFF_MS * (1L shl (attempt - 1)) // 1s, 3s, 9s-ish
                    Log.w(TAG, "Paste row ${row.id} attempt $attempt failed (${e.message}); retrying in ${delayMs}ms")
                    kotlinx.coroutines.delay(delayMs)
                    // Resume from whatever the monitor last persisted — gives us
                    // a free mid-file retry via ChannelSftp.RESUME / append.
                    val current = withContext(Dispatchers.IO) {
                        pasteQueueDao.getByStatus(sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_PENDING)
                            .firstOrNull { it.id == row.id }
                    }
                    resumeFromByte = current?.bytesTransferred ?: resumeFromByte
                }
            }
            // Success
            pasteQueueDao.updateStatus(
                row.id, sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_DONE, row.sourceSize, null,
            )
            if (row.isCut) {
                deleteSourceLeaf(sourceType, row.sourceProfileId, row.sourcePath)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Paste row ${row.id} failed permanently: ${e.message}")
            val latest = withContext(Dispatchers.IO) {
                pasteQueueDao.getByStatus(sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_PENDING)
                    .firstOrNull { it.id == row.id }
            }
            pasteQueueDao.updateStatus(
                row.id, sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_PENDING,
                latest?.bytesTransferred ?: resumeFromByte,
                (lastError ?: e).message ?: "failed",
            )
        } finally {
            currentQueueRowId = null
        }
    }

    /**
     * Heuristic: which exceptions warrant a same-row retry? Network drops and
     * mid-stream IO failures retry; everything else (FileNotFoundException for
     * missing source, permission errors) surfaces to the resume banner so the
     * user can fix the root cause.
     */
    private fun isTransientTransferError(e: Throwable): Boolean {
        return when (e) {
            is SshIoException,
            is java.net.SocketException,
            is java.net.SocketTimeoutException,
            is javax.net.ssl.SSLException,
            -> true
            is java.io.IOException -> {
                val msg = e.message.orEmpty()
                // Fresh channel timeout, SSH closure, and "broken pipe" — all
                // transient. FileNotFoundException is a subclass but shouldn't
                // retry (source is gone).
                e !is java.io.FileNotFoundException && (
                    msg.contains("broken pipe", ignoreCase = true) ||
                        msg.contains("reset", ignoreCase = true) ||
                        msg.contains("closed", ignoreCase = true) ||
                        msg.contains("timeout", ignoreCase = true)
                    )
            }
            else -> false
        }
    }

    /** Resume a persisted paste queue — typically invoked from the banner's Resume button. */
    fun resumePasteQueue() {
        viewModelScope.launch {
            try {
                _loading.value = true
                pasteInProgress.set(true)
                batchResolution = null
                acquirePasteWakeLock()
                val pending = withContext(Dispatchers.IO) {
                    pasteQueueDao.countByStatus(sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_PENDING)
                }
                val done = withContext(Dispatchers.IO) {
                    pasteQueueDao.countByStatus(sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_DONE)
                }
                pasteTotalFiles.set(pending + done)
                pasteFileCount.set(done)
                _transferProgress.value = TransferProgress("Resuming: $pending pending…", 0, 0)

                executePasteQueue()

                val stillPending = withContext(Dispatchers.IO) {
                    pasteQueueDao.countByStatus(sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_PENDING)
                }
                val nowDone = withContext(Dispatchers.IO) {
                    pasteQueueDao.countByStatus(sh.haven.core.data.db.entities.PasteQueueEntry.STATUS_DONE)
                }
                _message.value = when {
                    stillPending > 0 -> "Resumed $nowDone — $stillPending still pending"
                    else -> "Resume complete: $nowDone files"
                }
                if (stillPending == 0) {
                    withContext(Dispatchers.IO) { pasteQueueDao.clear() }
                }
                refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Resume failed", e)
                _error.value = "Resume failed: ${e.message}"
            } finally {
                pasteInProgress.set(false)
                _loading.value = false
                _transferProgress.value = null
                releasePasteWakeLock()
            }
        }
    }

    /** Explicitly drop the paste queue — banner's Discard button. */
    fun discardPasteQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            pasteQueueDao.clear()
        }
    }

    /**
     * Copy a single file between backends.
     *
     * Fast path: when the source is LOCAL, stream directly from the on-disk
     * source file to the destination backend. Avoids a pointless copy to
     * `appContext.cacheDir/cross_copy_...` that used to run before every
     * upload — expensive for large files (fills internal storage with a
     * temporary duplicate) and especially wasteful when the source is on
     * an external volume like a USB SD card.
     *
     * Slow path: for remote sources (SFTP) we still stage
     * through a cache file, because the source stream can't be held open
     * for an unbounded time while we open a destination channel and the
     * cached file lets us query size and reopen cleanly.
     */
    private suspend fun crossCopyFile(
        cb: FileClipboard, entry: SftpEntry,
        destType: BackendType, destProfileId: String,
        destPath: String,
        resumeFromByte: Long = 0,
    ) {
        if (cb.sourceBackendType == BackendType.LOCAL) {
            val srcFile = java.io.File(entry.path)
            val total = srcFile.length()
            beforePasteFile(entry.name, total, resumeFromByte)
            val label = _transferProgress.value?.fileName ?: entry.name
            when (destType) {
                BackendType.LOCAL -> {
                    // LOCAL → LOCAL paste: progress and transfer offsets
                    // coincide — the queue cursor counts both bytes
                    // already shown to the user *and* bytes already on
                    // disk at the destination.
                    writeLocalFileWithProgress(
                        source = srcFile,
                        destPath = destPath,
                        total = total,
                        progressOffset = resumeFromByte,
                        transferOffset = resumeFromByte,
                        label = label,
                    )
                }
                BackendType.SFTP -> {
                    val session = getOrOpenSession(destProfileId) ?: throw IllegalStateException("SFTP not connected")
                    srcFile.inputStream().use { input ->
                        val mode = if (resumeFromByte > 0) SftpWriteMode.RESUME else SftpWriteMode.OVERWRITE
                        // RESUME mode: the underlying library skips source up to
                        // destination size and appends the remainder, so we pass
                        // the full stream (no caller-side skip).
                        session.upload(
                            input,
                            sizeHint = total,
                            destPath = destPath,
                            mode = mode,
                            onBytes = sftpProgressCallback(label, total, resumeFromByte),
                        )
                    }
                }
            }
            return
        }

        // Remote source — two-phase copy via a cache file. Progress bar covers
        // download (phase 1) and upload (phase 2) with a combined label so the
        // user sees continuous movement rather than two jumps.
        val tempFile = java.io.File(appContext.cacheDir, "cross_copy_${entry.name}")
        val knownSize = if (entry.size > 0) entry.size else 0L
        beforePasteFile(entry.name, knownSize * 2, 0) // doubled: download + upload phases
        val baseLabel = _transferProgress.value?.fileName ?: entry.name
        try {
            when (cb.sourceBackendType) {
                BackendType.LOCAL -> {
                    // unreachable — handled by fast-path above
                }
                BackendType.SFTP -> {
                    val session = cb.sourceSftpSession
                        ?: sessionManager.openSftpSession(cb.sourceProfileId)
                        ?: throw IllegalStateException("SFTP not connected")
                    tempFile.outputStream().use { out ->
                        session.download(
                            srcPath = entry.path,
                            output = out,
                            onBytes = sftpProgressCallback("\u2B07 $baseLabel", knownSize * 2, 0),
                        )
                    }
                }
            }

            // Phase 2: upload from cache. Downloaded size seeds the bar's starting
            // point so progress continues rather than restarting.
            val downloaded = tempFile.length()
            when (destType) {
                BackendType.LOCAL -> {
                    // Phase 2 of REMOTE \u2192 LOCAL paste: the temp file
                    // already holds the full source. Seed the progress
                    // bar past the download half (`progressOffset =
                    // downloaded`) but write the temp file's full
                    // contents to the destination
                    // (`transferOffset = 0`). GH#142.
                    writeLocalFileWithProgress(
                        source = tempFile,
                        destPath = destPath,
                        total = downloaded * 2,
                        progressOffset = downloaded,
                        transferOffset = 0L,
                        label = "\u2B06 $baseLabel",
                    )
                }
                BackendType.SFTP -> {
                    val session = getOrOpenSession(destProfileId) ?: throw IllegalStateException("SFTP not connected")
                    tempFile.inputStream().use { input ->
                        val mode = if (resumeFromByte > 0) SftpWriteMode.RESUME else SftpWriteMode.OVERWRITE
                        session.upload(
                            input,
                            sizeHint = downloaded,
                            destPath = destPath,
                            mode = mode,
                            onBytes = sftpProgressCallback("\u2B06 $baseLabel", downloaded * 2, downloaded),
                        )
                    }
                }
            }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Lambda for [SftpSession.upload] / [SftpSession.download] `onBytes` that
     * updates [_transferProgress] with the same label/total throughout a
     * single put or get call. Pre-seeds the running byte counter with
     * [initialTransferred] so resume-mode transfers show a continuous bar
     * from destination-already-has-this-many-bytes rather than restarting
     * at zero.
     */
    private fun sftpProgressCallback(
        label: String,
        total: Long,
        initialTransferred: Long,
    ): (Long, Long) -> Unit = { transferred, _ ->
        val effective = initialTransferred + transferred
        _transferProgress.value = TransferProgress(label, total, effective)
        maybePersistQueueProgress(effective)
    }

    /**
     * Throttled `bytesTransferred` writer for the in-flight queue row. Writes
     * at most once per [QUEUE_PROGRESS_PERSIST_BYTES] so we cap DB traffic
     * during fast local transfers while still keeping the resume cursor close
     * to current — a crash costs at most that many bytes of replay.
     */
    private fun maybePersistQueueProgress(transferred: Long) {
        val rowId = currentQueueRowId ?: return
        if (transferred - lastQueueRowBytesPersist < QUEUE_PROGRESS_PERSIST_BYTES) return
        lastQueueRowBytesPersist = transferred
        // Fire-and-forget on the DB thread. Dropping a write is fine — the
        // next one picks up the latest value.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                pasteQueueDao.updateBytesTransferred(rowId, transferred)
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    /**
     * LOCAL-to-LOCAL copy with per-chunk progress reporting and support for
     * resume (append from [resumeFromByte]). Falls back to the MediaStore
     * delete-and-reinsert path if the direct write fails with a permissions
     * error under Downloads/.
     */
    /**
     * Stream [source] into [destPath] with progress updates. The two
     * offset parameters cover separate concerns:
     *
     * - [progressOffset] seeds the progress bar past bytes that an
     *   earlier phase already accounted for (e.g. the download phase
     *   of a cross-backend copy). Display only.
     * - [transferOffset] is a real resume cursor: when non-zero the
     *   source stream is skipped past it and the destination opens in
     *   append mode, so a crashed paste retried from the queue picks
     *   up where it left off.
     *
     * GH#142 was the conflation of the two: the phase-2 call site
     * passed the temp file's size as `resumeFromByte`, which got
     * interpreted as a transfer-skip offset and dropped every byte of
     * the payload on the floor.
     */
    private fun writeLocalFileWithProgress(
        source: java.io.File,
        destPath: String,
        total: Long,
        progressOffset: Long,
        transferOffset: Long,
        label: String,
    ) {
        _transferProgress.value = TransferProgress(label, total, progressOffset)
        if (transferOffset == 0L) {
            try {
                writeFileWithOptionalResume(source, destPath, 0L) { written ->
                    val displayed = progressOffset + written
                    _transferProgress.value = TransferProgress(label, total, displayed)
                    maybePersistQueueProgress(displayed)
                }
                return
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Direct write to $destPath failed (${e.message}); falling back to MediaStore path")
                writeLocalFileWithMediaStoreFallback(source, destPath)
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "Direct write to $destPath denied; falling back to MediaStore path")
                writeLocalFileWithMediaStoreFallback(source, destPath)
                return
            }
        }
        // Resume case: append mode + source skip. MediaStore append
        // isn't supported, so this path doesn't fall back — only the
        // first attempt for a path can hit MediaStore.
        writeFileWithOptionalResume(source, destPath, transferOffset) { written ->
            val displayed = progressOffset + written
            _transferProgress.value = TransferProgress(label, total, displayed)
            maybePersistQueueProgress(displayed)
        }
    }

    /**
     * Write [source] to [destPath] on the local filesystem. If a direct
     * write fails (typically because the destination is a MediaStore-owned
     * file under /storage/emulated/0/Download/ that the app can no longer
     * delete on Android Q+), fall back to writing via the MediaStore
     * Downloads collection — deleting the existing row through the content
     * resolver first.
     */
    // Caller is expected to be on Dispatchers.IO — crossCopyFile is not a
    // suspend function and already runs inside a withContext(Dispatchers.IO)
    // block at its caller (pasteFromClipboard).
    private fun writeLocalFileWithMediaStoreFallback(source: java.io.File, destPath: String) {
        val destFile = java.io.File(destPath)
        // Try streaming copy — this avoids Kotlin's File.copyTo, which
        // calls target.delete() under the hood.
        try {
            destFile.parentFile?.mkdirs()
            java.io.FileOutputStream(destFile, false).use { out ->
                source.inputStream().use { it.copyTo(out) }
            }
            return
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Direct write to $destPath failed (${e.message}); trying MediaStore", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Direct write to $destPath denied; trying MediaStore", e)
        }

        // MediaStore fallback — only applies under Download/.
        val downloads = "/storage/emulated/0/Download/"
        if (!destFile.absolutePath.startsWith(downloads)) {
            throw java.io.IOException("Cannot write to $destPath and path is not under Downloads/")
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            throw java.io.IOException("Cannot overwrite $destPath on this Android version")
        }

        val resolver = appContext.contentResolver
        val displayName = destFile.name
        // Delete any pre-existing entry under Downloads with this display name.
        // Scoped storage + MediaStore ownership means a direct File.delete()
        // fails, but the content-resolver path works for files the app owns.
        val queryUri = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.query(
            queryUri,
            arrayOf(android.provider.MediaStore.Downloads._ID),
            "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(displayName),
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(android.provider.MediaStore.Downloads._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val existing = android.content.ContentUris.withAppendedId(queryUri, id)
                try {
                    resolver.delete(existing, null, null)
                } catch (_: Exception) { /* keep going */ }
            }
        }

        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, guessContentType(displayName))
        }
        val newUri = resolver.insert(queryUri, values)
            ?: throw java.io.IOException("Failed to create MediaStore entry for $displayName")
        resolver.openOutputStream(newUri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: throw java.io.IOException("Failed to open output stream for $newUri")
    }

    fun dismissError() { _error.value = null }
    fun dismissMessage() { _message.value = null }

    private fun openSftpAndList(profileId: String, path: String) {
        viewModelScope.launch {
            try {
                if (!pasteInProgress.get()) _loading.value = true

                // Probe active sessions and reconnect if stale/dead — a
                // socket can die silently while the app stays foregrounded
                // (NAT/carrier timeout with no lifecycle transition to
                // trigger the return-to-foreground probe in
                // SshConnectionService), and reusing it here throws
                // "session is down" while the empty-state text still reads
                // "Empty directory". Probe first so this is transparent.
                sessionManager.probeAndReconnectStale(profileId = profileId)

                // A profile whose SSH/Mosh/ET session just opened for the
                // first time can still be CONNECTING when this runs (e.g.
                // right after tapping the connection card) — wait briefly
                // for the handshake to resolve on whichever transport the
                // profile actually uses, instead of racing it and landing
                // on an empty/home fallback.
                val profile = repository.getById(profileId)
                withTimeoutOrNull(5_000) {
                    when {
                        profile?.isMosh == true -> {
                            moshSessionManager.sessions.first { sessions ->
                                sessions.values.filter { it.profileId == profileId }
                                    .let { forProfile -> forProfile.isEmpty() || forProfile.any { it.status != MoshSessionManager.SessionState.Status.CONNECTING } }
                            }
                        }
                        profile?.isEternalTerminal == true -> {
                            etSessionManager.sessions.first { sessions ->
                                sessions.values.filter { it.profileId == profileId }
                                    .let { forProfile -> forProfile.isEmpty() || forProfile.any { it.status != EtSessionManager.SessionState.Status.CONNECTING } }
                            }
                        }
                        else -> {
                            sessionManager.sessions.first { sessions ->
                                sessions.values.filter { it.profileId == profileId }
                                    .let { forProfile -> forProfile.isEmpty() || forProfile.any { it.status != SessionState.Status.CONNECTING } }
                            }
                        }
                    }
                }

                // SSH/SFTP calls below open or write to channels over the
                // network, so they must run off the Main dispatcher.
                //
                // Probe for a home directory: SFTP exposes it cheaply via
                // SftpSession.home(); when SFTP is unavailable (SCP-only
                // servers) we shell out to `echo "$HOME"` over exec instead.
                // Fall back to "/" as a last resort.
                val home: String = withContext(Dispatchers.IO) {
                    val sftpSessionForHome = sessionManager.openSftpSession(profileId)
                        ?: openMoshSftpSession(profileId)
                    if (sftpSessionForHome != null) {
                        sftpSession = sftpSessionForHome
                        sftpSessionForHome.home()
                    } else {
                        val ssh = sessionManager.getSshClientForProfile(profileId)
                            ?: throw IllegalStateException("Session not connected")
                        val probe = ssh.execCommand("echo \"\$HOME\"")
                        probe.stdout.trim().ifEmpty { "/" }
                    }
                }

                // Land on the requested path when one was supplied (e.g.
                // NavigateToSftpPath to a USB mount's /mnt/sda1); otherwise the
                // SFTP/SCP home directory. The `path` arg was previously dead —
                // this is what lets a freshly-selected profile open directly on
                // a target dir instead of home.
                val landing = path.takeIf { it.isNotBlank() && it != "/" } ?: home
                _currentPath.value = landing
                val transport = currentSshTransport() ?: throw IllegalStateException("Session not connected")
                val results = transport.list(landing)
                _allEntries.value = sortEntries(results, _sortMode.value)
                applyFilter()
            } catch (e: SshIoException) {
                Log.w(TAG, "SFTP open failed; resetting channel and retrying", e)
                resetSftpChannel()
                try {
                    val transport = currentSshTransport()
                        ?: throw IllegalStateException("Session not connected after reset")
                    val landing = _currentPath.value
                    val results = transport.list(landing)
                    _allEntries.value = sortEntries(results, _sortMode.value)
                    applyFilter()
                } catch (e2: Exception) {
                    Log.e(TAG, "SFTP open failed after retry", e2)
                    _error.value = "SFTP connection lost — please try again"
                }
            } catch (e: Exception) {
                Log.e(TAG, "SFTP open failed", e)
                _error.value = "File browser failed: ${e.message}"
            } finally {
                if (!pasteInProgress.get()) _loading.value = false
            }
        }
    }

    /**
     * Pick a transport (SFTP or SCP) for the active SSH profile, honouring
     * the per-profile `fileTransport` preference plus Auto fallback. Emits
     * the one-shot snackbar announcement on first fallback and updates
     * [activeTransportLabel] so the UI can show a badge.
     *
     * Returns null when:
     *  - no profile is active
     *  - the profile cannot be loaded from the DB
     *  - neither SFTP nor SCP can be opened (no connected SSH session)
     */
    private suspend fun currentSshTransport(): sh.haven.feature.sftp.transport.RemoteFileTransport? {
        val profileId = _activeProfileId.value ?: return null
        // Do the DB read AND the channel-opening on IO — transportSelector.resolve
        // calls into JSch which blocks a socket to open the SFTP channel, so
        // it must not run on the Main dispatcher.
        val resolution = withContext(Dispatchers.IO) {
            val profile = repository.getById(profileId) ?: return@withContext null
            transportSelector.resolve(profile)
        } ?: return null
        resolution.announceFallback?.let { _message.value = it }
        _activeTransportLabel.value = resolution.transport.label
        return resolution.transport
    }

    /**
     * Resolve the [FileBackend] for the active profile — local or SSH — and
     * surface the SFTP→SCP fallback announcement / transport
     * badge as a side effect. Listing dispatches through here regardless of
     * backend; per-backend code paths only exist for upload, download,
     * mkdir, rename, delete, chmod and chown (issue #126 stages 2+).
     */
    private suspend fun currentFileBackend(): sh.haven.feature.sftp.transport.FileBackend? {
        val profileId = _activeProfileId.value ?: return null
        val resolution = withContext(Dispatchers.IO) {
            transportSelector.resolveFileBackend(profileId)
        } ?: return null
        resolution.announceFallback?.let { _message.value = it }
        _activeTransportLabel.value = resolution.backend.label
        return resolution.backend
    }

    /**
     * Mutex serialising directory listings so two concurrent navigations
     * don't share the underlying [ChannelSftp]. JSch's `ChannelSftp` is
     * not thread-safe — overlapping `ls` calls corrupt the internal
     * packet-buffer state, surfacing as
     * `IndexOutOfBoundsException` from `PipedInputStream.read`. Rapid
     * back-navigation (three taps in a row hitting `navigateUp`) is the
     * common trigger: the user reported it via #144.
     */
    private val listMutex = Mutex()

    /** Latest in-flight listing job; cancelled when a new navigation arrives. */
    private var listJob: Job? = null

    private fun loadDirectoryEntries(path: String) {
        // Cancel anything queued on the mutex from a previous tap so
        // we don't accumulate stale work behind the in-flight call.
        listJob?.cancel()
        listJob = viewModelScope.launch {
            listMutex.withLock {
                try {
                    if (!pasteInProgress.get()) _loading.value = true
                    val backend = currentFileBackend() ?: throw IllegalStateException("Not connected")
                    val results = backend.list(path)
                    _allEntries.value = sortEntries(results, _sortMode.value)
                    applyFilter()
                } catch (e: CancellationException) {
                    throw e // propagate normal cancellation
                } catch (e: IndexOutOfBoundsException) {
                    // JSch's ChannelSftp.ls surfaces buffer corruption as
                    // IOOB (PipedInputStream.read with a negative length).
                    // Once the packet stream is desynchronised, every
                    // subsequent op on the same channel fails — close it
                    // so getOrOpenChannel rebuilds a fresh one next time.
                    Log.w(TAG, "SFTP channel corrupt at path='$path'; resetting", e)
                    resetSftpChannel()
                    _error.value = "SFTP channel error — please try again"
                } catch (e: SshIoException) {
                    // JSch's ChannelSftp.fill() throws IOException("inputstream is closed")
                    // when the underlying stream read returns <= 0 — a transient
                    // connection glitch or a slow server (mwiede/jsch#858). Reset the
                    // channel and retry once before surfacing the error to the user.
                    Log.w(TAG, "SFTP list failed at path='$path'; resetting and retrying", e)
                    resetSftpChannel()
                    try {
                        val backend = currentFileBackend()
                            ?: throw IllegalStateException("Not connected after reset")
                        val results = backend.list(path)
                        _allEntries.value = sortEntries(results, _sortMode.value)
                        applyFilter()
                    } catch (e2: Exception) {
                        Log.e(TAG, "List directory failed after retry: path='$path'", e2)
                        _error.value = "SFTP connection lost — please try again"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "List directory failed: path='$path'", e)
                    _error.value = "Failed to list: ${e.message}"
                } finally {
                    if (!pasteInProgress.get()) _loading.value = false
                }
            }
        }
    }

    private fun resetSftpChannel() {
        sftpSession?.close()
        sftpSession = null
    }

    private fun getOrOpenSession(profileId: String): SftpSession? {
        sftpSession?.let { if (it.isConnected) return it }
        // Try SSH session first, then mosh/ET bootstrap SSH client
        val session = sessionManager.openSftpSession(profileId)
            ?: openMoshSftpSession(profileId)
            ?: openEtSftpSession(profileId)
            ?: return null
        sftpSession = session
        return session
    }

    private fun openMoshSftpSession(profileId: String): SftpSession? {
        val client = moshSessionManager.getSshClientForProfile(profileId) as? SshClient
            ?: return null
        return try {
            client.openSftpSession()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open SFTP session via mosh SSH client", e)
            null
        }
    }

    private fun openEtSftpSession(profileId: String): SftpSession? {
        val client = etSessionManager.getSshClientForProfile(profileId) as? SshClient
            ?: return null
        return try {
            client.openSftpSession()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open SFTP session via ET SSH client", e)
            null
        }
    }

    private fun sortEntries(entries: List<SftpEntry>, mode: SortMode): List<SftpEntry> {
        val dirs = entries.filter { it.isDirectory }
        val files = entries.filter { !it.isDirectory }
        val sortedDirs = when (mode) {
            SortMode.NAME_ASC -> dirs.sortedBy { it.name.lowercase() }
            SortMode.NAME_DESC -> dirs.sortedByDescending { it.name.lowercase() }
            SortMode.SIZE_ASC -> dirs.sortedBy { it.name.lowercase() }
            SortMode.SIZE_DESC -> dirs.sortedByDescending { it.name.lowercase() }
            SortMode.DATE_ASC -> dirs.sortedBy { it.modifiedTime }
            SortMode.DATE_DESC -> dirs.sortedByDescending { it.modifiedTime }
        }
        val sortedFiles = when (mode) {
            SortMode.NAME_ASC -> files.sortedBy { it.name.lowercase() }
            SortMode.NAME_DESC -> files.sortedByDescending { it.name.lowercase() }
            SortMode.SIZE_ASC -> files.sortedBy { it.size }
            SortMode.SIZE_DESC -> files.sortedByDescending { it.size }
            SortMode.DATE_ASC -> files.sortedBy { it.modifiedTime }
            SortMode.DATE_DESC -> files.sortedByDescending { it.modifiedTime }
        }
        return sortedDirs + sortedFiles
    }

    /**
     * Hand a file off to another installed app via an ACTION_VIEW chooser
     * ("Open with…", issue #173). The file's *raw original bytes* are served
     * over the loopback HTTP bridge, so a player like VLC/mpv can decode
     * containers Haven's built-in MediaCodec path can't, and non-media files
     * (.pdf/.epub/.cbz) open in their own viewer. The file is streamed
     * on-demand (HTTP Range) — never fully downloaded first.
     *
     * The loopback URL we publish carries [SftpStreamServer]'s secret token in
     * its path; we hand the external app the full URL, so it replays the token
     * on its own GET and the server accepts it (other apps can't guess it).
     */
    fun openWithExternalApp(entry: SftpEntry) {
        if (entry.isDirectory) return
        viewModelScope.launch {
            try {
                val mime = mimeTypeForName(entry.name)
                val data: android.net.Uri
                var grantRead = false
                when {
                    _isLocalProfile.value -> {
                        // Local backend has a real file → content:// via FileProvider.
                        val file = java.io.File(entry.path)
                        data = androidx.core.content.FileProvider.getUriForFile(
                            appContext, "${appContext.packageName}.fileprovider", file,
                        )
                        grantRead = true
                    }
                    else -> {
                        // SFTP via the loopback HTTP bridge (raw bytes, Range-capable).
                        val profileId = _activeProfileId.value
                            ?: throw IllegalStateException("No active profile")
                        val port = withContext(Dispatchers.IO) { sftpStreamServer.start() }
                        val urlPath = sftpStreamServer.publish(
                            path = entry.path,
                            size = entry.size,
                            contentType = mime,
                            opener = sftpOpener(profileId, entry.path),
                        )
                        data = android.net.Uri.parse("http://127.0.0.1:$port$urlPath")
                    }
                }
                Log.d(TAG, "openWithExternalApp: ${entry.name} -> $data ($mime)")
                val view = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(data, mime)
                    if (grantRead) addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = android.content.Intent
                    .createChooser(view, appContext.getString(R.string.sftp_open_with))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                if (grantRead) chooser.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                appContext.startActivity(chooser)
            } catch (e: android.content.ActivityNotFoundException) {
                Log.w(TAG, "openWithExternalApp: no activity to handle ${entry.name}", e)
                _error.value = appContext.getString(R.string.sftp_no_app_to_open)
            } catch (e: Exception) {
                Log.e(TAG, "openWithExternalApp failed for ${entry.name}", e)
                _error.value = "Open with… failed: ${e.message}"
            }
        }
    }

    companion object {
        // Kotlin has no octal literal syntax, so POSIX mode bits are
        // declared as hex with their octal meaning in the name.
        private const val MODE_SETUID  = 0x800 // 04000
        private const val MODE_SETGID  = 0x400 // 02000
        private const val MODE_STICKY  = 0x200 // 01000
        private const val MODE_O_READ  = 0x100 // 00400
        private const val MODE_O_WRITE = 0x080 // 00200
        private const val MODE_O_EXEC  = 0x040 // 00100
        private const val MODE_G_READ  = 0x020 // 00040
        private const val MODE_G_WRITE = 0x010 // 00020
        private const val MODE_G_EXEC  = 0x008 // 00010
        private const val MODE_R_READ  = 0x004 // 00004
        private const val MODE_R_WRITE = 0x002 // 00002
        private const val MODE_R_EXEC  = 0x001 // 00001
        const val MODE_MASK = 0xFFF            // 07777
        const val MODE_0644 = MODE_O_READ or MODE_O_WRITE or MODE_G_READ or MODE_R_READ

        /**
         * Parse the 10-char Unix permissions string ("-rwxr-xr-x",
         * "drwx------", "-rwsr-xr-x") into the numeric mode bits used by
         * chmod. Returns null for strings that don't look like the
         * expected format (e.g. JSch occasionally returns an empty
         * string for symlinked entries). Only the low 12 bits are set —
         * setuid/setgid/sticky from s/S/t/T are preserved, the file-type
         * nibble is not.
         */
        fun permissionsStringToMode(perms: String): Int? {
            if (perms.length < 10) return null
            var mode = 0
            fun bit(ch: Char, on: Char, value: Int) {
                if (ch == on) mode = mode or value
            }
            bit(perms[1], 'r', MODE_O_READ)
            bit(perms[2], 'w', MODE_O_WRITE)
            when (perms[3]) {
                'x' -> mode = mode or MODE_O_EXEC
                's' -> mode = mode or MODE_O_EXEC or MODE_SETUID
                'S' -> mode = mode or MODE_SETUID
            }
            bit(perms[4], 'r', MODE_G_READ)
            bit(perms[5], 'w', MODE_G_WRITE)
            when (perms[6]) {
                'x' -> mode = mode or MODE_G_EXEC
                's' -> mode = mode or MODE_G_EXEC or MODE_SETGID
                'S' -> mode = mode or MODE_SETGID
            }
            bit(perms[7], 'r', MODE_R_READ)
            bit(perms[8], 'w', MODE_R_WRITE)
            when (perms[9]) {
                'x' -> mode = mode or MODE_R_EXEC
                't' -> mode = mode or MODE_R_EXEC or MODE_STICKY
                'T' -> mode = mode or MODE_STICKY
            }
            return mode
        }
    }
}
