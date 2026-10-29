package sh.haven.feature.sftp

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SftpScreen(
    /**
     * Applied to the screen's Scaffold. The NavHost uses this to install a
     * single pager-level gesture handler that both drives tab-swipe and
     * dispatches a fast-flick → navigate-up action (#89), so all the
     * horizontal-drag decision-making lives in one place instead of
     * competing layers.
     */
    sftpModifier: Modifier = Modifier,
    /**
     * Fired after the user resolves a pending terminal-attach folder pick
     * (either confirm or cancel). The host uses it to scroll back to the
     * Terminal page so the user lands where the path will be injected
     * instead of being stranded on the SFTP screen.
     */
    onAttachFinished: () -> Unit = {},
    /** Open a phone-attached USB drive in a VM (#287); the resulting "USB: …"
     * connection then appears here as a tab. Wired by the host to the same flow
     * as Desktop → Manage → "Open USB drive…". */
    viewModel: SftpViewModel = hiltViewModel(),
) {
    val connectedProfiles by viewModel.connectedProfiles.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    val showHidden by viewModel.showHidden.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val transferProgress by viewModel.transferProgress.collectAsState()
    val error by viewModel.error.collectAsState()
    val message by viewModel.message.collectAsState()
    val lastDownload by viewModel.lastDownload.collectAsState()
    val uploadConflict by viewModel.uploadConflict.collectAsState()
    val pasteConflict by viewModel.conflictPrompt.collectAsState()
    val pastePendingCount by viewModel.pastePendingCount.collectAsState()
    val pastePendingBytes by viewModel.pastePendingBytes.collectAsState()
    val fileClipboard by viewModel.clipboard.collectAsState()
    val activeTransportLabel by viewModel.activeTransportLabel.collectAsState()

    val fileFilter by viewModel.fileFilter.collectAsState()
    val filterMode by viewModel.filterMode.collectAsState()

    val selectedPaths by viewModel.selectedPaths.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val attachRequest by viewModel.attachRequest.collectAsState()
    val attachProgress by viewModel.attachProgress.collectAsState()
    val attachActive = attachRequest != null
    val chmodRequest by viewModel.chmodRequest.collectAsState()
    val chownRequest by viewModel.chownRequest.collectAsState()

    var showRenameDialog by remember { mutableStateOf<SftpEntry?>(null) }
    var showEncryptSheet by remember { mutableStateOf<SftpEntry?>(null) }

    viewModel.syncConnectedProfiles()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(lastDownload) {
        val dl = lastDownload ?: return@LaunchedEffect
        viewModel.dismissMessage() // clear the plain message so it doesn't double-show

        // Auto-install APK files directly
        if (dl.fileName.endsWith(".apk", ignoreCase = true)) {
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(dl.uri, "application/vnd.android.package-archive")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                @Suppress("LocalContextGetResourceValueCall")
                snackbarHostState.showSnackbar("Install failed: ${e.message}")
            }
            viewModel.clearLastDownload()
            return@LaunchedEffect
        }

        @Suppress("LocalContextGetResourceValueCall")
        val downloadedMessage = context.getString(R.string.sftp_downloaded, dl.fileName)
        @Suppress("LocalContextGetResourceValueCall")
        val openLabel = context.getString(R.string.sftp_open)
        val result = snackbarHostState.showSnackbar(
            message = downloadedMessage,
            actionLabel = openLabel,
            withDismissAction = true,
            duration = androidx.compose.material3.SnackbarDuration.Long,
        )
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
            try {
                val mimeType = context.contentResolver.getType(dl.uri) ?: "*/*"
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(dl.uri, mimeType)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                @Suppress("LocalContextGetResourceValueCall")
                snackbarHostState.showSnackbar(context.getString(R.string.sftp_no_app_to_open))
            }
        }
        viewModel.clearLastDownload()
    }

    LaunchedEffect(message) {
        // Only show plain messages when there's no download result (download has its own snackbar)
        val msg = message ?: return@LaunchedEffect
        if (lastDownload == null) {
            snackbarHostState.showSnackbar(msg)
        }
        viewModel.dismissMessage()
    }

    // File picker for upload
    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // Query the actual display name from the content resolver
            val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "upload"
            viewModel.uploadFile(fileName, uri)
        }
    }

    // New folder dialog state
    var showNewFolderDialog by remember { mutableStateOf(false) }

    // Folder upload picker
    val folderUploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.uploadFolder(uri)
        }
    }

    // Directory picker for download
    var pendingDownload by remember { mutableStateOf<SftpEntry?>(null) }
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            pendingDownload?.let { entry ->
                viewModel.downloadFile(entry, uri)
            }
        }
        pendingDownload = null
    }

    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var showSortMenu by remember { mutableStateOf(false) }
    var showFilterBar by rememberSaveable { mutableStateOf(false) }

    // Back press while in selection mode clears selection instead of
    // navigating up / exiting the screen.
    androidx.activity.compose.BackHandler(enabled = selectionMode) {
        viewModel.clearSelection()
    }

    // Back press while a terminal-attach folder pick is pending cancels
    // it (resolving the coordinator's deferred with null) so the terminal
    // unblocks instead of leaving the picker active across navigation.
    androidx.activity.compose.BackHandler(enabled = attachActive) {
        viewModel.cancelAttach()
        onAttachFinished()
    }

    // When the picker opens, pre-select the terminal's active SSH session's
    // profile so the user lands in the right host's filesystem instead of
    // wherever they last browsed. Keyed on the request reference so a
    // subsequent attach with the same initialProfileId re-triggers; manual
    // profile switches mid-pick aren't fought because the key doesn't
    // change while one request is active.
    // Keyed on activeProfileId too: when the pick request arrives the tab's
    // own profile sync may still be mid-flight (activeProfileId null, only
    // settling on "local" a frame later), and a request-only key would miss
    // that. Manual profile switches mid-pick aren't fought: switching between
    // remotes leaves the condition false, and "local" is never a valid pick
    // destination, so snapping off it is the banner's own rule.
    LaunchedEffect(attachRequest, activeProfileId) {
        val req = attachRequest ?: return@LaunchedEffect
        val initialId = req.initialProfileId
        if (initialId != null) {
            if (activeProfileId != initialId) {
                viewModel.selectProfile(initialId)
            }
        } else if (activeProfileId == null || activeProfileId == "local") {
            // No carrier profile (e.g. attach from a local shell tab — the
            // terminal strips local profiles because the banner can't confirm
            // a local destination) and the tab is parked on no profile or the
            // synthetic Local one: land on the first connected remote instead,
            // which is usually the SSH host the upload will ride. With nothing
            // remote connected the tab stays put and the banner's
            // pick-a-profile state is the only honest offer.
            viewModel.connectedProfiles.value
                .firstOrNull { it.id != "local" }
                ?.let { viewModel.selectProfile(it.id) }
        }
    }

    Scaffold(
        modifier = sftpModifier,
        // Defer to the app Scaffold background so the global background-opacity
        // (wallpaper see-through) applies here too.
        containerColor = Color.Transparent,
        // contentColorFor(Transparent) has no scheme match and resolves to
        // Unspecified, so any Text without an explicit colour falls back to
        // black — invisible in dark theme. Pin it to onSurface.
        contentColor = MaterialTheme.colorScheme.onSurface,
        // The outer pager already has .imePadding() on its modifier; letting
        // this inner Scaffold's contentWindowInsets also include WindowInsets.ime
        // (the default) creates a double-source: both the outer imePadding and
        // the inner Scaffold track the same IME animation, generating redundant
        // IME-driven nested-scroll events. Those events reach the pager's
        // DefaultPagerNestedScrollConnection (which is active even with
        // userScrollEnabled=false) and amplify a horizontal swipe by ~1 IME-height
        // when the keyboard dismisses mid-drag. Excluding the IME here removes
        // the second source and restores 1:1 swipe tracking.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.ime),
        topBar = {
            if (selectionMode) {
                SelectionTopBar(
                    count = selectedPaths.size,
                    totalVisible = entries.size,
                    onClear = { viewModel.clearSelection() },
                    onSelectAll = { viewModel.selectAll() },
                    onDelete = { viewModel.deleteSelected() },
                    onCopy = {
                        val targets = entries.filter { it.path in selectedPaths }
                        viewModel.copyToClipboard(targets, isCut = false)
                        viewModel.clearSelection()
                    },
                    onCut = {
                        val targets = entries.filter { it.path in selectedPaths }
                        viewModel.copyToClipboard(targets, isCut = true)
                        viewModel.clearSelection()
                    },
                    onPermissions = { viewModel.openChmodDialogForSelection() },
                    supportsPermissions = viewModel.supportsPermissions(),
                    onOwnership = { viewModel.openChownDialogForSelection() },
                    supportsOwnership = viewModel.supportsOwnership(),
                )
            } else TopAppBar(
                title = {
                    var editingPath by remember { mutableStateOf(false) }
                    var pathText by remember(currentPath) { mutableStateOf(currentPath) }
                    if (editingPath) {
                        BasicTextField(
                            value = pathText,
                            onValueChange = { pathText = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                editingPath = false
                                viewModel.navigateTo(pathText)
                            }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                currentPath,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .clickable { editingPath = true },
                            )
                            // Show a transport badge when SCP is active so
                            // users always know why browsing is shell-based
                            // and uploads go through spool files.
                            if (activeTransportLabel == "SCP") {
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Text(
                                        "SCP",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (currentPath != "/" && activeProfileId != null) {
                        IconButton(onClick = { viewModel.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.sftp_navigate_up))
                        }
                    }
                },
                actions = {
                    if (activeProfileId != null) {
                        IconButton(onClick = {
                            showFilterBar = !showFilterBar
                            if (!showFilterBar) viewModel.setFileFilter("")
                        }) {
                            Icon(
                                Icons.Filled.FilterList,
                                contentDescription = stringResource(R.string.sftp_filter),
                                tint = if (fileFilter.isNotEmpty()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        IconButton(onClick = { viewModel.toggleShowHidden() }) {
                            Icon(
                                if (showHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (showHidden) stringResource(R.string.sftp_hide_hidden_files) else stringResource(R.string.sftp_show_hidden_files),
                            )
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sftp_sort))
                            }
                            SortDropdown(
                                expanded = showSortMenu,
                                currentMode = sortMode,
                                onDismiss = { showSortMenu = false },
                                onSelect = { mode ->
                                    viewModel.setSortMode(mode)
                                    showSortMenu = false
                                },
                            )
                        }
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Filled.Refresh, stringResource(R.string.sftp_refresh))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (activeProfileId != null) {
                var fabExpanded by remember { mutableStateOf(false) }
                Column(horizontalAlignment = Alignment.End) {
                    AnimatedVisibility(visible = fabExpanded) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 8.dp),
                        ) {
                            SmallFloatingActionButton(onClick = {
                                fabExpanded = false
                                showNewFolderDialog = true
                            }) {
                                Icon(Icons.Filled.CreateNewFolder, stringResource(R.string.sftp_new_folder))
                            }
                            // Upload targets a connected remote — the synthetic Local tab
                            // is a browse/source only and these error "Not connected"
                            // there (same reason activeProfileId=="local" blocks a paste
                            // dest, sftp_attach_local_no_upload).
                            if (activeProfileId != "local") {
                                SmallFloatingActionButton(onClick = {
                                    fabExpanded = false
                                    folderUploadLauncher.launch(null)
                                }) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Folder,
                                            stringResource(R.string.sftp_upload_folder),
                                            modifier = Modifier.size(24.dp),
                                        )
                                        Icon(
                                            Icons.Filled.ArrowUpward,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(14.dp)
                                                .padding(top = 2.dp),
                                            tint = MaterialTheme.colorScheme.primaryContainer,
                                        )
                                    }
                                }
                                SmallFloatingActionButton(onClick = {
                                    fabExpanded = false
                                    uploadLauncher.launch(arrayOf("*/*"))
                                }) {
                                    Icon(Icons.Filled.Upload, stringResource(R.string.sftp_upload_file))
                                }
                            }
                        }
                    }
                    if (fileClipboard != null) {
                        FloatingActionButton(onClick = { viewModel.pasteFromClipboard() }) {
                            Icon(Icons.Filled.ContentPaste, stringResource(R.string.sftp_paste))
                        }
                    } else {
                        FloatingActionButton(onClick = { fabExpanded = !fabExpanded }) {
                            Icon(
                                if (fabExpanded) Icons.Filled.CreateNewFolder else Icons.Filled.Upload,
                                if (fabExpanded) stringResource(R.string.sftp_fab_close) else stringResource(R.string.sftp_fab_actions),
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Folder-pick banner — visible while the terminal's attach
            // flow is awaiting a destination choice. The user navigates
            // freely (including switching profiles) and taps "Use this
            // folder" once happy, or backs out via Cancel.
            attachRequest?.let { req ->
                // The "local" pseudo-profile is the device's own filesystem;
                // letting the user "upload" to it would just copy a file the
                // app already has on-device, so it's not a valid attach
                // destination. Block confirm and explain on the banner.
                val isLocalDest = activeProfileId == "local"
                val canConfirm = activeProfileId != null && !isLocalDest
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = stringResource(R.string.sftp_attach_choose_folder, req.fileName),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Text(
                            text = when {
                                activeProfileId == null -> stringResource(R.string.sftp_attach_pick_profile)
                                isLocalDest -> stringResource(R.string.sftp_attach_local_no_upload)
                                else -> stringResource(R.string.sftp_attach_will_upload_into, currentPath)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = {
                                viewModel.cancelAttach()
                                onAttachFinished()
                            }) {
                                Text(stringResource(R.string.common_cancel))
                            }
                            Spacer(Modifier.width(4.dp))
                            Button(
                                onClick = {
                                    viewModel.confirmAttachFolder()
                                    onAttachFinished()
                                },
                                enabled = canConfirm,
                            ) {
                                Text(stringResource(R.string.sftp_attach_use_folder))
                            }
                        }
                    }
                }
            }
            attachProgress?.let { p ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (p.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { p.transferredBytes.toFloat() / p.totalBytes.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        stringResource(R.string.sftp_attach_sending, p.fileName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
            }
            AnimatedVisibility(visible = showFilterBar && activeProfileId != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = fileFilter,
                        onValueChange = { viewModel.setFileFilter(it) },
                        placeholder = {
                            Text(
                                if (filterMode == SftpViewModel.FilterMode.GLOB) "*.txt, config*" else "\\.(txt|md)$",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall,
                        trailingIcon = if (fileFilter.isNotEmpty()) {
                            {
                                IconButton(onClick = { viewModel.setFileFilter("") }) {
                                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                        } else null,
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = filterMode == SftpViewModel.FilterMode.REGEX,
                        onClick = {
                            viewModel.setFilterMode(
                                if (filterMode == SftpViewModel.FilterMode.GLOB) SftpViewModel.FilterMode.REGEX
                                else SftpViewModel.FilterMode.GLOB,
                            )
                        },
                        label = {
                            Text(
                                if (filterMode == SftpViewModel.FilterMode.GLOB) stringResource(R.string.sftp_filter_mode_glob) else stringResource(R.string.sftp_filter_mode_regex),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }
            if (pastePendingCount > 0 && !loading) {
                // Persistent resume banner — a previous paste was interrupted
                // (process death, connection drop, explicit cancel) and the
                // queue still has PENDING rows. Stays visible across tab
                // switches and app restarts until the user resumes or
                // discards.
                var confirmDiscard by remember { mutableStateOf(false) }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.sftp_paste_unfinished),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                "$pastePendingCount ${if (pastePendingCount == 1) "file" else "files"} · " +
                                    Formatter.formatFileSize(context, pastePendingBytes) + " remaining",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        TextButton(onClick = { confirmDiscard = true }) {
                            Text(stringResource(R.string.sftp_paste_discard))
                        }
                        Spacer(Modifier.width(4.dp))
                        Button(onClick = { viewModel.resumePasteQueue() }) {
                            Text(stringResource(R.string.sftp_paste_resume))
                        }
                    }
                }
                if (confirmDiscard) {
                    AlertDialog(
                        onDismissRequest = { confirmDiscard = false },
                        title = { Text(stringResource(R.string.sftp_paste_discard_title)) },
                        text = { Text(stringResource(R.string.sftp_paste_discard_body)) },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.discardPasteQueue()
                                confirmDiscard = false
                            }) { Text(stringResource(R.string.sftp_paste_discard)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.sftp_paste_keep)) }
                        },
                    )
                }
            } else if (loading) {
                val progress = transferProgress
                if (progress != null && (progress.totalBytes > 0 || progress.fileName.isNotEmpty())) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (progress.totalBytes > 0) {
                            LinearProgressIndicator(
                                progress = { progress.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                progress.fileName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (progress.totalBytes > 0) {
                                Text(
                                    "${Formatter.formatFileSize(context, progress.transferredBytes)} / ${Formatter.formatFileSize(context, progress.totalBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (connectedProfiles.isEmpty()) {
            } else {
                // Server tabs
                if (connectedProfiles.size > 1) {
                    val activeIndex = connectedProfiles.indexOfFirst { it.id == activeProfileId }
                        .coerceAtLeast(0)
                    PrimaryScrollableTabRow(
                        selectedTabIndex = activeIndex,
                        modifier = Modifier.fillMaxWidth(),
                        edgePadding = 8.dp,
                    ) {
                        connectedProfiles.forEach { profile ->
                            Tab(
                                selected = profile.id == activeProfileId,
                                onClick = { viewModel.selectProfile(profile.id) },
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        if (profile.isLocal) {
                                            Icon(
                                                Icons.Filled.Folder,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                        Text(profile.label, maxLines = 1)
                                    }
                                },
                            )
                        }
                    }
                }

                // Storage permission banner for local file browser
                if (viewModel.needsStoragePermission) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val intent = android.content.Intent(
                                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}"),
                                    )
                                    context.startActivity(intent)
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Folder,
                                null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.sftp_storage_permission_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    stringResource(R.string.sftp_storage_permission_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                }

                // Clipboard banner
                fileClipboard?.let { cb ->
                    Surface(tonalElevation = 2.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (cb.isCut) Icons.Filled.ContentCut else Icons.Filled.FileCopy,
                                null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (cb.isCut) {
                                    stringResource(
                                        if (cb.entries.size > 1) R.string.sftp_items_cut_plural else R.string.sftp_items_cut,
                                        cb.entries.size,
                                    )
                                } else {
                                    stringResource(
                                        if (cb.entries.size > 1) R.string.sftp_items_copied_plural else R.string.sftp_items_copied,
                                        cb.entries.size,
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { viewModel.clearClipboard() }) {
                                Text(stringResource(R.string.common_cancel))
                            }
                        }
                    }
                }

                // File list — always renders the LazyColumn so the parent-dir
                // "..\" row stays available even when the directory is empty.
                // Horizontal-drag handling (tab-swipe + fast-flick→up, #89)
                // lives at the NavHost level via `pagerSwipeOverride`; this
                // composable just focuses on list rendering.
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // ".." parent directory entry
                    if (currentPath != "/" && currentPath.isNotEmpty()) {
                        item(key = "__parent__") {
                            ListItem(
                                headlineContent = { Text("..") },
                                supportingContent = { Text(stringResource(R.string.sftp_parent_directory)) },
                                leadingContent = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.sftp_navigate_up_icon),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.navigateUp() },
                            )
                        }
                    }
                    if (entries.isEmpty() && !loading) {
                        item(key = "__empty__") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(R.string.sftp_empty_directory),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    itemsIndexed(entries, key = { index, entry -> "${activeProfileId}:${index}:${entry.path}" }) { _, entry ->
                            FileListItem(
                                entry = entry,
                                selected = entry.path in selectedPaths,
                                selectionActive = selectionMode,
                                onToggleSelect = { viewModel.toggleSelection(entry) },
                                onEnterSelection = { viewModel.toggleSelection(entry) },
                                onPermissions = if (viewModel.supportsPermissions()) {
                                    { viewModel.openChmodDialog(entry) }
                                } else null,
                                onOwnership = if (viewModel.supportsOwnership()) {
                                    { viewModel.openChownDialog(entry) }
                                } else null,
                                onTap = {
                                    if (selectionMode) {
                                        viewModel.toggleSelection(entry)
                                    } else if (attachActive) {
                                        // Attach picker mode: only folder-navigation taps do
                                        // anything; file taps are no-op so the user can't
                                        // accidentally trigger a download / open.
                                        if (entry.isDirectory) viewModel.navigateTo(entry.path)
                                    } else if (entry.isDirectory) {
                                        viewModel.navigateTo(entry.path)
                                    } else if (viewModel.isLocalProfile()) {
                                        // Open local file with system app
                                        try {
                                            val file = java.io.File(entry.path)
                                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                                context, "${context.packageName}.fileprovider", file
                                            )
                                            val ext = file.extension.lowercase()
                                            val mime = android.webkit.MimeTypeMap.getSingleton()
                                                .getMimeTypeFromExtension(ext) ?: "*/*"
                                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, mime)
                                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            scope.launch {
                                                @Suppress("LocalContextGetResourceValueCall")
                                                snackbarHostState.showSnackbar(context.getString(R.string.sftp_no_app_to_open))
                                            }
                                        }
                                    } else {
                                        // Hand off to the "Open with…" chooser — the user picks
                                        // the app (or cancels) there.
                                        viewModel.openWithExternalApp(entry)
                                    }
                                },
                                onDownload = {
                                    pendingDownload = entry
                                    downloadLauncher.launch(entry.name)
                                },
                                onDelete = { viewModel.deleteEntry(entry) },
                                onCopyPath = {
                                    clipboardManager.setText(AnnotatedString(entry.path))
                                    @Suppress("LocalContextGetResourceValueCall")
                                    val pathCopiedMsg = context.getString(R.string.sftp_path_copied)
                                    scope.launch {
                                        snackbarHostState.showSnackbar(pathCopiedMsg)
                                    }
                                },
                                onCopy = { viewModel.copyToClipboard(listOf(entry), isCut = false) },
                                onCut = { viewModel.copyToClipboard(listOf(entry), isCut = true) },
                                onOpenWith = if (!entry.isDirectory) {
                                    { viewModel.openWithExternalApp(entry) }
                                } else null,
                                onRename = { showRenameDialog = entry },
                                onEncrypt = if (!entry.isDirectory && !entry.name.endsWith(".age")) {
                                    { showEncryptSheet = entry }
                                } else null,
                                onDecrypt = if (!entry.isDirectory && entry.name.endsWith(".age")) {
                                    { viewModel.decryptFile(entry) }
                                } else null,
                            )
                        }
                    }  // LazyColumn
            }
        }
    }

    // Permissions dialog — opened either for a single entry (context
    // menu → Permissions…) or for the current multi-selection (selection
    // top bar → shield icon).
    chmodRequest?.let { req ->
        val title = if (req.batch) {
            stringResource(R.string.sftp_permissions_dialog_title_batch, selectedPaths.size)
        } else {
            req.entry?.name ?: stringResource(R.string.sftp_permissions_dialog_title)
        }
        ChmodDialog(
            initialMode = req.currentMode,
            title = title,
            onDismiss = { viewModel.dismissChmodDialog() },
            onApply = { mode ->
                if (req.batch) viewModel.chmodSelected(mode)
                else req.entry?.let { viewModel.chmodEntry(it, mode) }
                viewModel.dismissChmodDialog()
            },
        )
    }

    // Ownership dialog — single entry via context menu, or batch via
    // selection top bar → person icon.
    chownRequest?.let { req ->
        val title = if (req.batch) {
            stringResource(R.string.sftp_ownership_dialog_title_batch, selectedPaths.size)
        } else {
            req.entry?.name ?: stringResource(R.string.sftp_ownership_dialog_title)
        }
        ChownDialog(
            title = title,
            initialOwner = req.currentOwner,
            onDismiss = { viewModel.dismissChownDialog() },
            onApply = { owner ->
                if (req.batch) viewModel.chownSelected(owner)
                else req.entry?.let { viewModel.chownEntry(it, owner) }
                viewModel.dismissChownDialog()
            },
        )
    }

    // New Folder dialog
    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text(stringResource(R.string.sftp_new_folder_title)) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(stringResource(R.string.sftp_folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNewFolderDialog = false
                        viewModel.createDirectory(folderName)
                    },
                    enabled = folderName.isNotBlank(),
                ) { Text(stringResource(R.string.sftp_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // Upload conflict dialog
    uploadConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = { viewModel.resolveConflict(SftpViewModel.ConflictChoice.SKIP) },
            title = { Text(stringResource(R.string.sftp_file_already_exists)) },
            text = { Text(stringResource(R.string.sftp_file_exists_message, conflict.fileName)) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { viewModel.resolveConflict(SftpViewModel.ConflictChoice.REPLACE) }) {
                        Text(stringResource(R.string.sftp_replace))
                    }
                    TextButton(onClick = { viewModel.resolveConflict(SftpViewModel.ConflictChoice.REPLACE_ALL) }) {
                        Text(stringResource(R.string.sftp_replace_all))
                    }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { viewModel.resolveConflict(SftpViewModel.ConflictChoice.SKIP) }) {
                        Text(stringResource(R.string.sftp_skip))
                    }
                    TextButton(onClick = { viewModel.resolveConflict(SftpViewModel.ConflictChoice.SKIP_ALL) }) {
                        Text(stringResource(R.string.sftp_skip_all))
                    }
                }
            },
        )
    }

    // Paste-time conflict dialog (Windows Explorer-style: Resume / Overwrite /
    // Skip / Rename, with an "Apply to all" checkbox for batch paste).
    pasteConflict?.let { prompt ->
        PasteConflictDialog(prompt = prompt)
    }

    // Rename dialog
    showRenameDialog?.let { entry ->
        var newName by remember(entry) { mutableStateOf(entry.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text(stringResource(R.string.sftp_rename_title)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.sftp_new_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRenameDialog = null
                        viewModel.renameEntry(entry, newName)
                    },
                    enabled = newName.isNotBlank() && newName != entry.name,
                ) { Text(stringResource(R.string.common_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // age encrypt dialog — pick a stored identity and/or paste a recipient
    showEncryptSheet?.let { entry ->
        val identities by viewModel.ageIdentities.collectAsState()
        AgeEncryptDialog(
            entry = entry,
            identities = identities,
            onDismiss = { showEncryptSheet = null },
            onEncrypt = { recipients ->
                showEncryptSheet = null
                viewModel.encryptFile(entry, recipients)
            },
        )
    }
}

/**
 * Pick the age recipient(s) to encrypt a file to: any stored identity
 * (radio-selected; defaults to the first) and/or a pasted `age1…`
 * recipient. v1 of VISION §2 age encryption.
 */
@Composable
private fun AgeEncryptDialog(
    entry: SftpEntry,
    identities: List<sh.haven.core.data.db.entities.AgeIdentityEntity>,
    onDismiss: () -> Unit,
    onEncrypt: (List<String>) -> Unit,
) {
    var selectedId by remember(identities) { mutableStateOf(identities.firstOrNull()?.id) }
    var pasted by remember { mutableStateOf("") }
    val recipients = buildList {
        identities.firstOrNull { it.id == selectedId }?.let { add(it.recipient) }
        pasted.trim().takeIf { it.startsWith("age1") }?.let { add(it) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sftp_encrypt_title)) },
        text = {
            Column {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                identities.forEach { id ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selectedId == id.id, onClick = { selectedId = id.id }),
                    ) {
                        RadioButton(selected = selectedId == id.id, onClick = { selectedId = id.id })
                        Spacer(Modifier.width(4.dp))
                        Column {
                            Text(id.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                id.recipient,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    label = { Text(stringResource(R.string.sftp_encrypt_recipient_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onEncrypt(recipients) },
                enabled = recipients.isNotEmpty(),
            ) { Text(stringResource(R.string.sftp_encrypt_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListItem(
    entry: SftpEntry,
    onTap: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onCopyPath: () -> Unit,
    selected: Boolean = false,
    selectionActive: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onEnterSelection: () -> Unit = {},
    onCopy: () -> Unit = {},
    onCut: () -> Unit = {},
    onPermissions: (() -> Unit)? = null,
    onOwnership: (() -> Unit)? = null,
    onOpenWith: (() -> Unit)? = null,
    onRename: () -> Unit = {},
    onEncrypt: (() -> Unit)? = null,
    onDecrypt: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "yMdjm"), Locale.getDefault()) }

    Box {
        ListItem(
            headlineContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                }
            },
            supportingContent = {
                @Suppress("LocalContextGetResourceValueCall")
                val sizeText = if (entry.isDirectory) context.getString(R.string.sftp_directory) else Formatter.formatFileSize(context, entry.size)
                val dateText = dateFormat.format(Date(entry.modifiedTime * 1000))
                val perms = entry.permissions.takeIf { it.isNotEmpty() }
                val onPerms = onPermissions
                if (perms != null && onPerms != null && !selectionActive) {
                    // Tapping the permission string opens the chmod dialog directly (#414).
                    // The long-press menu still works; this just makes it discoverable —
                    // users look at the perms text when they want to change them.
                    val linkColor = MaterialTheme.colorScheme.primary
                    Text(
                        buildAnnotatedString {
                            append("$sizeText  $dateText  ")
                            withLink(
                                LinkAnnotation.Clickable(
                                    tag = "chmod",
                                    styles = TextLinkStyles(
                                        style = SpanStyle(color = linkColor, fontFamily = FontFamily.Monospace),
                                    ),
                                ) { onPerms() },
                            ) {
                                append(perms)
                            }
                        },
                    )
                } else {
                    val extra = perms?.let { "  $it" } ?: ""
                    Text("$sizeText  $dateText$extra")
                }
            },
            leadingContent = {
                if (selectionActive) {
                    Icon(
                        if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = stringResource(
                            if (selected) R.string.sftp_selection_selected else R.string.sftp_selection_unselected
                        ),
                        tint = if (selected) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Icon(
                        if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
                        contentDescription = stringResource(if (entry.isDirectory) R.string.sftp_directory_icon else R.string.sftp_file_icon),
                        tint = if (entry.isDirectory) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            },
            modifier = Modifier
                .then(
                    if (selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                    else Modifier
                )
                .combinedClickable(
                    onClick = onTap,
                    onLongClick = {
                        if (selectionActive) onToggleSelect()
                        else showMenu = true
                    },
                ),
        )

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sftp_selection_enter)) },
                leadingIcon = { Icon(Icons.Filled.Check, null) },
                onClick = { showMenu = false; onEnterSelection() },
            )
            if (onPermissions != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sftp_permissions)) },
                    leadingIcon = { Icon(Icons.Filled.Security, null) },
                    onClick = { showMenu = false; onPermissions() },
                )
            }
            if (onOwnership != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sftp_ownership)) },
                    leadingIcon = { Icon(Icons.Filled.Person, null) },
                    onClick = { showMenu = false; onOwnership() },
                )
            }
            if (!entry.isDirectory) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sftp_download)) },
                    leadingIcon = { Icon(Icons.Filled.Download, null) },
                    onClick = { showMenu = false; onDownload() },
                )
            }
            if (onOpenWith != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sftp_open_with)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                    onClick = { showMenu = false; onOpenWith() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_copy)) },
                leadingIcon = { Icon(Icons.Filled.FileCopy, null) },
                onClick = { showMenu = false; onCopy() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sftp_cut)) },
                leadingIcon = { Icon(Icons.Filled.ContentCut, null) },
                onClick = { showMenu = false; onCut() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_rename)) },
                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                onClick = { showMenu = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sftp_copy_path)) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                onClick = { showMenu = false; onCopyPath() },
            )
            if (onEncrypt != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sftp_encrypt)) },
                    leadingIcon = { Icon(Icons.Filled.Lock, null) },
                    onClick = { showMenu = false; onEncrypt() },
                )
            }
            if (onDecrypt != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sftp_decrypt)) },
                    leadingIcon = { Icon(Icons.Filled.LockOpen, null) },
                    onClick = { showMenu = false; onDecrypt() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_delete)) },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { showMenu = false; onDelete() },
            )
        }
    }
}

/**
 * Contextual app bar shown while at least one file is selected. Replaces
 * the browse-mode TopAppBar — tapping the close icon clears the selection
 * and restores the regular bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    totalVisible: Int,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPermissions: () -> Unit,
    supportsPermissions: Boolean,
    onOwnership: () -> Unit,
    supportsOwnership: Boolean,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(R.string.sftp_selection_count, count)) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, stringResource(R.string.sftp_selection_clear))
            }
        },
        actions = {
            if (count < totalVisible) {
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Filled.SelectAll, stringResource(R.string.sftp_selection_select_all))
                }
            }
            IconButton(onClick = onCopy) {
                Icon(Icons.Filled.FileCopy, stringResource(R.string.common_copy))
            }
            IconButton(onClick = onCut) {
                Icon(Icons.Filled.ContentCut, stringResource(R.string.sftp_cut))
            }
            if (supportsPermissions) {
                IconButton(onClick = onPermissions) {
                    Icon(Icons.Filled.Security, stringResource(R.string.sftp_permissions))
                }
            }
            if (supportsOwnership) {
                IconButton(onClick = onOwnership) {
                    Icon(Icons.Filled.Person, stringResource(R.string.sftp_ownership))
                }
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Filled.Delete, stringResource(R.string.common_delete))
            }
        },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.sftp_delete_selection_title)) },
            text = { Text(stringResource(R.string.sftp_delete_selection_message, count)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/**
 * Permissions editor dialog. The 9-checkbox grid (owner/group/other ×
 * r/w/x) and the octal text field are kept in sync — editing either
 * updates the other. Special bits (setuid/setgid/sticky) are not
 * exposed in the grid but survive a round-trip through the octal field.
 */
@Composable
private fun ChmodDialog(
    initialMode: Int,
    title: String,
    onDismiss: () -> Unit,
    onApply: (Int) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(initialMode and SftpViewModel.MODE_MASK) }
    var octalText by rememberSaveable { mutableStateOf("%04o".format(initialMode and SftpViewModel.MODE_MASK)) }

    // Keep text and mode in sync when a checkbox changes.
    fun setMode(newMode: Int) {
        mode = newMode and SftpViewModel.MODE_MASK
        octalText = "%04o".format(mode)
    }

    fun flipBit(bit: Int, on: Boolean) {
        setMode(if (on) mode or bit else mode and bit.inv())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = octalText,
                    onValueChange = { text ->
                        octalText = text.take(5).filter { it.isDigit() }
                        val parsed = octalText.toIntOrNull(8)
                        if (parsed != null) mode = parsed and SftpViewModel.MODE_MASK
                    },
                    label = { Text(stringResource(R.string.sftp_permissions_octal)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(0.9f))
                    Text(stringResource(R.string.sftp_permissions_read),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(stringResource(R.string.sftp_permissions_write),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(stringResource(R.string.sftp_permissions_execute),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                PermissionRow(stringResource(R.string.sftp_permissions_owner),
                    readBit = 0x100, writeBit = 0x080, execBit = 0x040, mode = mode, onFlip = ::flipBit)
                PermissionRow(stringResource(R.string.sftp_permissions_group),
                    readBit = 0x020, writeBit = 0x010, execBit = 0x008, mode = mode, onFlip = ::flipBit)
                PermissionRow(stringResource(R.string.sftp_permissions_other),
                    readBit = 0x004, writeBit = 0x002, execBit = 0x001, mode = mode, onFlip = ::flipBit)
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(mode) }) {
                Text(stringResource(R.string.sftp_permissions_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * Ownership editor. Single text field accepting `user`, `group`, or
 * `user:group` — same syntax as the remote `chown` command. Server
 * does the name→UID translation.
 */
@Composable
private fun ChownDialog(
    title: String,
    initialOwner: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var owner by rememberSaveable(initialOwner) { mutableStateOf(initialOwner) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    stringResource(R.string.sftp_ownership_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = owner,
                    onValueChange = { owner = it },
                    label = { Text(stringResource(R.string.sftp_ownership_label)) },
                    placeholder = { Text("user:group") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(owner.trim()) },
                enabled = owner.isNotBlank(),
            ) { Text(stringResource(R.string.sftp_ownership_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun PermissionRow(
    label: String,
    readBit: Int,
    writeBit: Int,
    execBit: Int,
    mode: Int,
    onFlip: (bit: Int, on: Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.9f),
        )
        for (bit in listOf(readBit, writeBit, execBit)) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Checkbox(
                    checked = (mode and bit) != 0,
                    onCheckedChange = { onFlip(bit, it) },
                )
            }
        }
    }
}

@Composable
private fun SortDropdown(
    expanded: Boolean,
    currentMode: SortMode,
    onDismiss: () -> Unit,
    onSelect: (SortMode) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        SortMode.entries.forEach { mode ->
            val label = when (mode) {
                SortMode.NAME_ASC -> stringResource(R.string.sftp_sort_name_asc)
                SortMode.NAME_DESC -> stringResource(R.string.sftp_sort_name_desc)
                SortMode.SIZE_ASC -> stringResource(R.string.sftp_sort_size_asc)
                SortMode.SIZE_DESC -> stringResource(R.string.sftp_sort_size_desc)
                SortMode.DATE_ASC -> stringResource(R.string.sftp_sort_date_asc)
                SortMode.DATE_DESC -> stringResource(R.string.sftp_sort_date_desc)
            }
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = {
                    RadioButton(
                        selected = mode == currentMode,
                        onClick = null,
                    )
                },
                onClick = { onSelect(mode) },
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.sftp_file_browser_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            stringResource(R.string.sftp_connect_to_browse),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Format seconds as M:SS or H:MM:SS. */
/**
 * Compact dropdown selector for encoder/container choices.
 * Shows the selected label with a dropdown on tap.
 */
@Composable
private fun DropdownSelector(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: selected
    Box {
        Surface(
            onClick = { expanded = true },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(selectedLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onSelect(key); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun PasteConflictDialog(prompt: ConflictPrompt) {
    val context = LocalContext.current
    var applyToAll by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { prompt.onChoice(ConflictAction.SKIP, applyToAll) },
        title = { Text(stringResource(R.string.sftp_conflict_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${prompt.fileName}", style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(
                        R.string.sftp_conflict_size_summary,
                        Formatter.formatFileSize(context, prompt.destSize),
                        Formatter.formatFileSize(context, prompt.sourceSize),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (prompt.canResume) {
                    Text(
                        stringResource(R.string.sftp_conflict_resume_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Checkbox(
                        checked = applyToAll,
                        onCheckedChange = { applyToAll = it },
                    )
                    Text(
                        stringResource(R.string.sftp_conflict_apply_to_all),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (prompt.canResume) {
                    TextButton(onClick = { prompt.onChoice(ConflictAction.RESUME, applyToAll) }) {
                        Text(stringResource(R.string.sftp_paste_resume))
                    }
                }
                TextButton(onClick = { prompt.onChoice(ConflictAction.OVERWRITE, applyToAll) }) {
                    Text(stringResource(R.string.sftp_conflict_overwrite))
                }
                TextButton(onClick = { prompt.onChoice(ConflictAction.RENAME, applyToAll) }) {
                    Text(stringResource(R.string.sftp_conflict_keep_both))
                }
                TextButton(onClick = { prompt.onChoice(ConflictAction.SKIP, applyToAll) }) {
                    Text(stringResource(R.string.sftp_conflict_skip))
                }
            }
        },
    )
}

