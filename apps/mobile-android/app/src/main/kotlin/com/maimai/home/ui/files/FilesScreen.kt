package com.maimai.home.ui.files

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maimai.home.R
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maimai.home.data.models.FileEntry
import com.maimai.home.data.models.FileRoot
import com.maimai.home.ui.common.BentoCard
import com.maimai.home.ui.common.BentoCardTitle
import com.maimai.home.ui.common.CurrentDeviceCard
import com.maimai.home.ui.common.MaimaiScreenScaffold
import com.maimai.home.ui.connection.EmptyCard
import com.maimai.home.ui.connection.ErrorCard
import com.maimai.home.ui.connection.LoadingCard
import kotlinx.coroutines.launch

/**
 * Test tags for FilesScreen — used by Compose UI tests.
 */
object FilesScreenTags {
    const val ROOT_PICKER_BUTTON = "files.root.picker.button"
    const val ROOT_PICKER_SHEET = "files.root.picker.sheet"
    const val ACTION_SHEET = "files.action.sheet"
    const val RENAME_DIALOG = "files.rename.dialog"
    const val MOVE_DIALOG = "files.move.dialog"
    const val DELETE_DIALOG = "files.delete.dialog"
    const val DELETE_CONFIRM_BUTTON = "files.delete.confirm"
    const val CONFIRM_OVERWRITE_DIALOG = "files.overwrite.dialog"
    const val CONFIRM_OVERWRITE_BUTTON = "files.overwrite.confirm"
    const val UPLOAD_FAB = "files.upload.fab"
    const val LOAD_MORE_BUTTON = "files.load.more"
    const val BREADCRUMB_ROW = "files.breadcrumb.row"
    const val SNACKBAR_HOST = "files.snackbar"
    const val EMPTY_DIRECTORY = "files.empty.directory"
    const val ENTRY_ACTION_PREFIX = "files.entry.action."
}

/**
 * File management screen with root selection, breadcrumb navigation, refresh,
 * upload/download, and mutation dialogs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    address: String,
    machineName: String,
    onSwitchDevice: () -> Unit,
) {
    val context = LocalContext.current.applicationContext as Application
    val viewModel: FilesViewModel = viewModel(factory = FilesViewModel.factory(context, address, machineName))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Production-only: keyed by viewModel so re-creation properly tears down.
    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    var pendingConflict by remember { mutableStateOf<(() -> Unit)?>(null) }
    val showConflict: (() -> Unit) -> Unit = { retry ->
        pendingConflict = { pendingConflict = null; retry() }
    }

    // SAF download: keep the entry the user wants, then ask the system to
    // create a document the user can see (Downloads, SD card, cloud, etc).
    var pendingDownloadEntry by remember { mutableStateOf<FileEntry?>(null) }
    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val entry = pendingDownloadEntry
        pendingDownloadEntry = null
        if (uri != null && entry != null) {
            viewModel.download(
                entry,
                uri,
                { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
            )
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.upload(
            uri,
            overwrite = false,
            onDone = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
            onError = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
            onConflict = { showConflict { viewModel.upload(uri, overwrite = true, onDone = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }, onError = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }) } },
        )
    }

    FilesScreenContent(
        state = uiState,
        snackbarHostState = snackbarHostState,
        breadcrumbSegments = viewModel.breadcrumbSegments(),
        currentEntryPath = viewModel::currentEntryPath,
        onRefresh = viewModel::refresh,
        onSelectRoot = viewModel::selectRoot,
        onOpenFolder = viewModel::openFolder,
        onNavigateToPath = viewModel::navigateToPath,
        onLoadMore = viewModel::loadMore,
        onDownload = { entry ->
            // Defer the actual download until the user picks a destination Uri.
            pendingDownloadEntry = entry
            createDocumentLauncher.launch(entry.name)
        },
        onUpload = { launcher.launch("*/*") },
        onDelete = { entry ->
            viewModel.delete(
                entry,
                { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
            )
        },
        onRename = { entry, newName ->
            viewModel.rename(
                entry,
                newName,
                overwrite = false,
                onDone = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                onError = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                onConflict = { showConflict { viewModel.rename(entry, newName, overwrite = true, onDone = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }, onError = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }) } },
            )
        },
        onMove = { entry, newPath ->
            viewModel.move(
                entry,
                newPath,
                overwrite = false,
                onDone = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                onError = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                onConflict = { showConflict { viewModel.move(entry, newPath, overwrite = true, onDone = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }, onError = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }) } },
            )
        },
        machineName = machineName,
        address = address,
        onSwitchDevice = onSwitchDevice,
        showCurrentDeviceCard = true,
        pendingConflict = pendingConflict,
        onConfirmConflict = { pendingConflict?.invoke() },
        onDismissConflict = { pendingConflict = null },
    )
}

/**
 * Stateless inner composable for tests.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilesScreenContent(
    state: FilesUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    breadcrumbSegments: List<String>,
    currentEntryPath: (FileEntry) -> String,
    onRefresh: () -> Unit,
    onSelectRoot: (FileRoot) -> Unit,
    onOpenFolder: (FileEntry) -> Unit,
    onNavigateToPath: (String) -> Unit,
    onLoadMore: () -> Unit,
    onDownload: (FileEntry) -> Unit,
    onUpload: () -> Unit,
    onDelete: (FileEntry) -> Unit,
    onRename: (FileEntry, String) -> Unit,
    onMove: (FileEntry, String) -> Unit,
    machineName: String = state.machineName,
    address: String = state.address,
    onSwitchDevice: () -> Unit = {},
    showCurrentDeviceCard: Boolean = false,
    pendingConflict: (() -> Unit)? = null,
    onConfirmConflict: () -> Unit = {},
    onDismissConflict: () -> Unit = {},
) {
    var selectedEntry by remember { mutableStateOf<FileEntry?>(null) }
    var renameEntry by remember { mutableStateOf<FileEntry?>(null) }
    var moveEntry by remember { mutableStateOf<FileEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<FileEntry?>(null) }
    var showRootPicker by remember { mutableStateOf(false) }

    MaimaiScreenScaffold(
        topBarActions = {
            IconButton(
                onClick = { showRootPicker = true },
                modifier = Modifier.testTag(FilesScreenTags.ROOT_PICKER_BUTTON),
            ) {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = stringResource(R.string.files_select_root),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.testTag(FilesScreenTags.SNACKBAR_HOST),
            )
        },
        floatingActionButton = {
            if (state.canMutate) {
                FloatingActionButton(
                    onClick = onUpload,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.testTag(FilesScreenTags.UPLOAD_FAB),
                ) {
                    Icon(
                        imageVector = Icons.Filled.UploadFile,
                        contentDescription = stringResource(R.string.files_upload_cd),
                    )
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (showCurrentDeviceCard) {
                    item(key = "current-device") {
                        CurrentDeviceCard(
                            machineName = machineName,
                            address = address,
                            onSwitchDevice = onSwitchDevice,
                        )
                    }
                }
                // Storage roots header + horizontal card scroll
                item(key = "storage-title") {
                    Text(
                        "存储位置",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                item(key = "storage-roots") {
                    if (state.roots.isEmpty()) {
                        EmptyCard(text = "尚未检测到可用的存储位置")
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            state.roots.forEach { root ->
                                StorageRootCard(
                                    root = root,
                                    selected = root.id == state.selectedRoot?.id,
                                    onClick = { onSelectRoot(root) },
                                )
                            }
                        }
                    }
                }

                // Breadcrumb capsule
                item(key = "breadcrumb") {
                    BreadcrumbCapsule(
                        rootName = state.selectedRoot?.name ?: "",
                        segments = breadcrumbSegments,
                        onNavigate = onNavigateToPath,
                    )
                }

                state.listing?.takeIf { it.truncated }?.let { listing ->
                    item(key = "truncated-banner") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.files_truncated_format, state.loadedOffset, listing.total),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            FilledTonalButton(
                                onClick = onLoadMore,
                                enabled = !state.isLoadingMore,
                                modifier = Modifier.testTag(FilesScreenTags.LOAD_MORE_BUTTON),
                            ) {
                                if (state.isLoadingMore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.files_load_more))
                            }
                        }
                    }
                }

                if (state.listing == null && state.isRefreshing) {
                    item(key = "loading") { LoadingCard(modifier = Modifier.fillMaxWidth()) }
                }
                if (state.errorMessage != null && state.listing == null) {
                    item(key = "error") {
                        ErrorCard(
                            text = state.errorMessage,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                val entries = state.listing?.entries ?: emptyList()
                if (state.listing != null && entries.isEmpty() && !state.isRefreshing) {
                    item(key = "empty-directory") {
                        EmptyCard(
                            text = stringResource(R.string.files_empty_directory),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(FilesScreenTags.EMPTY_DIRECTORY),
                        )
                    }
                }

                // File list bento card
                if (entries.isNotEmpty()) {
                    item(key = "file-list") {
                        FileListCard(
                            entries = entries,
                            onOpen = { entry ->
                                if (entry.isDirectory) onOpenFolder(entry)
                                else selectedEntry = entry
                            },
                            onShowActions = { entry -> selectedEntry = entry },
                        )
                    }
                }
                item(key = "bottom-spacer") { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    // ── Root picker ModalBottomSheet (R2 B2) ─────────────────────────────────
    if (showRootPicker) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showRootPicker = false },
            sheetState = sheetState,
            modifier = Modifier.testTag(FilesScreenTags.ROOT_PICKER_SHEET),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.files_root_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.roots.isEmpty()) {
                    Text(
                        stringResource(R.string.files_roots_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.roots.forEach { root ->
                        TextButton(
                            onClick = {
                                showRootPicker = false
                                onSelectRoot(root)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    root.name,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Action ModalBottomSheet.
    selectedEntry?.let { entry ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { selectedEntry = null },
            sheetState = sheetState,
            modifier = Modifier.testTag(FilesScreenTags.ACTION_SHEET),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                if (!entry.isDirectory) {
                    TextButton(
                        onClick = {
                            selectedEntry = null
                            onDownload(entry)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ActionSheetButtonContent(
                            icon = Icons.Filled.Download,
                            label = stringResource(R.string.files_action_download),
                        )
                    }
                }
                // Rename and Move are hidden on read-only roots.
                if (state.canMutate) {
                    TextButton(
                        onClick = { renameEntry = entry; selectedEntry = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ActionSheetButtonContent(
                            icon = Icons.Filled.Edit,
                            label = stringResource(R.string.files_action_rename),
                        )
                    }
                    TextButton(
                        onClick = { moveEntry = entry; selectedEntry = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ActionSheetButtonContent(
                            icon = Icons.AutoMirrored.Filled.DriveFileMove,
                            label = stringResource(R.string.files_action_move),
                        )
                    }
                }
                // Delete is hidden for directories and read-only roots.
                if (!entry.isDirectory && state.canMutate) {
                    TextButton(
                        onClick = { deleteEntry = entry; selectedEntry = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ActionSheetButtonContent(
                            icon = Icons.Filled.Delete,
                            label = stringResource(R.string.files_action_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Rename dialog.
    renameEntry?.let { entry ->
        var renameValue by remember(entry) { mutableStateOf(entry.name) }
        var renameError by remember { mutableStateOf<String?>(null) }
        val focusRequester = remember { FocusRequester() }
        // Pre-capture strings so they can be used inside onClick lambdas.
        val errEmpty = stringResource(R.string.files_dialog_rename_empty_error)
        val errSame = stringResource(R.string.files_dialog_rename_same_error)

        LaunchedEffect(entry) { focusRequester.requestFocus() }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameEntry = null },
            modifier = Modifier.testTag(FilesScreenTags.RENAME_DIALOG),
            title = { Text(stringResource(R.string.files_dialog_rename_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = renameValue,
                        onValueChange = { renameValue = it; renameError = null },
                        label = { Text(stringResource(R.string.files_dialog_rename_label)) },
                        isError = renameError != null,
                        modifier = Modifier.focusRequester(focusRequester),
                        singleLine = true,
                    )
                    renameError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = renameValue.trim()
                    when {
                        trimmed.isEmpty() -> renameError = errEmpty
                        trimmed == entry.name -> renameError = errSame
                        else -> {
                            renameEntry = null
                            onRename(entry, trimmed)
                        }
                    }
                }) { Text(stringResource(R.string.files_action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { renameEntry = null }) {
                    Text(stringResource(R.string.files_action_cancel))
                }
            },
        )
    }

    // Move dialog.
    moveEntry?.let { entry ->
        val originalPath = currentEntryPath(entry)
        var moveValue by remember(entry) { mutableStateOf(originalPath) }
        var moveError by remember { mutableStateOf<String?>(null) }
        val focusRequester = remember { FocusRequester() }
        // Pre-capture strings so they can be used inside onClick lambdas.
        val errEmpty = stringResource(R.string.files_dialog_move_empty_error)
        val errSame = stringResource(R.string.files_dialog_move_same_error)

        LaunchedEffect(entry) { focusRequester.requestFocus() }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { moveEntry = null },
            modifier = Modifier.testTag(FilesScreenTags.MOVE_DIALOG),
            title = { Text(stringResource(R.string.files_dialog_move_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.files_dialog_move_helper),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = moveValue,
                        onValueChange = { moveValue = it; moveError = null },
                        label = { Text(stringResource(R.string.files_dialog_move_label)) },
                        isError = moveError != null,
                        modifier = Modifier.focusRequester(focusRequester),
                        singleLine = true,
                    )
                    moveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = moveValue.trim()
                    when {
                        trimmed.isEmpty() -> moveError = errEmpty
                        trimmed == originalPath -> moveError = errSame
                        else -> {
                            moveEntry = null
                            onMove(entry, trimmed)
                        }
                    }
                }) { Text(stringResource(R.string.files_action_move)) }
            },
            dismissButton = {
                TextButton(onClick = { moveEntry = null }) {
                    Text(stringResource(R.string.files_action_cancel))
                }
            },
        )
    }

    // ── Delete confirm dialog (R2 P3 — red confirm button) ───────────────────
    deleteEntry?.let { entry ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteEntry = null },
            modifier = Modifier.testTag(FilesScreenTags.DELETE_DIALOG),
            title = { Text(stringResource(R.string.files_dialog_delete_title)) },
            text = {
                Text(stringResource(R.string.files_dialog_delete_text_format, entry.name))
            },
            confirmButton = {
                Button(
                    onClick = {
                        deleteEntry = null
                        onDelete(entry)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.testTag(FilesScreenTags.DELETE_CONFIRM_BUTTON),
                ) { Text(stringResource(R.string.files_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteEntry = null }) {
                    Text(stringResource(R.string.files_action_cancel))
                }
            },
        )
    }

    if (pendingConflict != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissConflict,
            modifier = Modifier.testTag(FilesScreenTags.CONFIRM_OVERWRITE_DIALOG),
            title = { Text(stringResource(R.string.files_dialog_overwrite_title)) },
            text = { Text(stringResource(R.string.files_dialog_overwrite_text)) },
            confirmButton = {
                Button(
                    onClick = onConfirmConflict,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.testTag(FilesScreenTags.CONFIRM_OVERWRITE_BUTTON),
                ) { Text(stringResource(R.string.files_action_overwrite)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissConflict) {
                    Text(stringResource(R.string.files_action_cancel))
                }
            },
        )
    }
}

// ── Storage root card (3.html style) ───────────────────────────────────────

@Composable
private fun StorageRootCard(
    root: FileRoot,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.10f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Column(
        modifier = Modifier
            .width(160.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = iconForRoot(root),
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            RootBadge(readOnly = root.readOnly)
        }
        Column {
            Text(
                text = root.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = root.id,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RootBadge(readOnly: Boolean) {
    val (text, container, content) = if (readOnly) {
        Triple("只读", MaterialTheme.colorScheme.surfaceDim, MaterialTheme.colorScheme.outline)
    } else {
        Triple(
            "可写",
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            MaterialTheme.colorScheme.primary,
        )
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
    }
}

private fun iconForRoot(root: FileRoot): ImageVector {
    val name = root.name.lowercase()
    return when {
        "download" in name || "下载" in root.name -> Icons.Filled.Download
        "music" in name || "音乐" in root.name || "media" in name -> Icons.Filled.MusicNote
        root.readOnly -> Icons.Filled.Lock
        else -> Icons.Filled.Work
    }
}

// ── Breadcrumb capsule (3.html style) ──────────────────────────────────────

@Composable
private fun BreadcrumbCapsule(
    rootName: String,
    segments: List<String>,
    onNavigate: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                RoundedCornerShape(12.dp),
            )
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(FilesScreenTags.BREADCRUMB_ROW),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onNavigate("") },
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Home,
                contentDescription = stringResource(R.string.files_breadcrumb_root),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (rootName.isNotBlank()) {
            BreadcrumbDivider()
            Text(
                rootName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (segments.isEmpty()) {
                    MaterialTheme.colorScheme.onBackground
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (segments.isEmpty()) FontWeight.Medium else FontWeight.Normal,
            )
        }
        var current = ""
        segments.forEachIndexed { index, segment ->
            BreadcrumbDivider()
            current = listOf(current.takeIf { it.isNotBlank() }, segment)
                .filterNotNull()
                .joinToString("/")
            val path = current
            val isLast = index == segments.lastIndex
            Text(
                text = segment,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isLast) {
                    MaterialTheme.colorScheme.onBackground
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (isLast) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.clickable(onClick = { onNavigate(path) }),
            )
        }
    }
}

@Composable
private fun BreadcrumbDivider() {
    Text(
        text = "/",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

// ── File list card (3.html style) ────────────────────────────────────────

@Composable
internal fun FileListCard(
    entries: List<FileEntry>,
    onOpen: (FileEntry) -> Unit,
    onShowActions: (FileEntry) -> Unit,
) {
    BentoCard(contentPadding = PaddingValues(0.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            FileListHeader()
            entries.forEachIndexed { index, entry ->
                FileEntryRow(
                    entry = entry,
                    onOpen = { onOpen(entry) },
                    onShowActions = { onShowActions(entry) },
                    showDivider = index != entries.lastIndex,
                )
            }
        }
    }
}

@Composable
private fun FileListHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(36.dp)) // matches leading icon width
        Text(
            "名称",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        Text(
            "大小",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
private fun FileEntryRow(
    entry: FileEntry,
    onOpen: () -> Unit,
    onShowActions: () -> Unit,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = iconForEntry(entry),
                    contentDescription = if (entry.isDirectory) {
                        stringResource(R.string.files_kind_directory_cd)
                    } else {
                        stringResource(R.string.files_kind_file_cd)
                    },
                    tint = if (entry.isDirectory) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                    },
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = if (entry.isDirectory) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                    )
                    Text(
                        text = FilesViewModel.formatDate(entry.modified),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (entry.isDirectory) "--" else FilesViewModel.humanSize(entry.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(80.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
                if (entry.isDirectory) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = stringResource(R.string.files_open_directory_cd),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onShowActions,
                modifier = Modifier.testTag(FilesScreenTags.ENTRY_ACTION_PREFIX + entry.name),
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.files_action_menu_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showDivider) {
            androidx.compose.material3.HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 1.dp,
            )
        }
    }
}

private fun iconForEntry(entry: FileEntry): ImageVector {
    if (entry.isDirectory) return Icons.Filled.Folder
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp" -> Icons.Filled.Image
        "pdf", "txt", "md", "doc", "docx" -> Icons.Filled.Description
        "json", "xml", "yaml", "yml", "toml", "kt", "java", "py", "js", "ts", "cs", "go", "rs" -> Icons.Filled.Code
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

/**
 * Internal composable for testing the rename dialog in isolation.
 * Exposed so Compose UI tests can drive it without going through the ModalBottomSheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RenameDialogForTest(
    entryName: String,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    onConfirm: (String) -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val entry = com.maimai.home.data.models.FileEntry(name = entryName, kind = "file", modified = "")
    var renameValue by remember(entry) { mutableStateOf(entry.name) }
    var renameError by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val errEmpty = stringResource(R.string.files_dialog_rename_empty_error)
    val errSame = stringResource(R.string.files_dialog_rename_same_error)

    LaunchedEffect(entry) { focusRequester.requestFocus() }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(FilesScreenTags.RENAME_DIALOG),
        title = { Text(stringResource(R.string.files_dialog_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it; renameError = null },
                    label = { Text(stringResource(R.string.files_dialog_rename_label)) },
                    isError = renameError != null,
                    modifier = Modifier.focusRequester(focusRequester),
                    singleLine = true,
                )
                renameError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = renameValue.trim()
                when {
                    trimmed.isEmpty() -> renameError = errEmpty
                    trimmed == entry.name -> renameError = errSame
                    else -> onConfirm(trimmed)
                }
            }) { Text(stringResource(R.string.files_action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.files_action_cancel))
            }
        },
    )
}

/**
 * Internal composable for testing the delete confirm dialog in isolation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeleteDialogForTest(
    entryName: String,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    onConfirm: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(FilesScreenTags.DELETE_DIALOG),
        title = { Text(stringResource(R.string.files_dialog_delete_title)) },
        text = { Text(stringResource(R.string.files_dialog_delete_text_format, entryName)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.testTag(FilesScreenTags.DELETE_CONFIRM_BUTTON),
            ) { Text(stringResource(R.string.files_action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.files_action_cancel))
            }
        },
    )
}

/**
 * Internal composable for testing the move dialog in isolation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoveDialogForTest(
    entryName: String,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    onConfirm: (String) -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    var moveValue by remember { mutableStateOf(entryName) }
    var moveError by remember { mutableStateOf<String?>(null) }
    val errEmpty = stringResource(R.string.files_dialog_move_empty_error)
    val errSame = stringResource(R.string.files_dialog_move_same_error)

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(FilesScreenTags.MOVE_DIALOG),
        title = { Text(stringResource(R.string.files_dialog_move_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.files_dialog_move_helper),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = moveValue,
                    onValueChange = { moveValue = it; moveError = null },
                    label = { Text(stringResource(R.string.files_dialog_move_label)) },
                    isError = moveError != null,
                    singleLine = true,
                )
                moveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = moveValue.trim()
                when {
                    trimmed.isEmpty() -> moveError = errEmpty
                    trimmed == entryName -> moveError = errSame
                    else -> onConfirm(trimmed)
                }
            }) { Text(stringResource(R.string.files_action_move)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.files_action_cancel))
            }
        },
    )
}

/**
 * I7 fix: action-sheet rows render an icon + label so the Files action
 * sheet visually mirrors the Flutter parity sheet (download/rename/move/
 * delete each carry their own glyph).
 */
@Composable
private fun ActionSheetButtonContent(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.LocalContentColor.current,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint)
        Text(text = label, color = tint, modifier = Modifier.weight(1f))
    }
}
