package com.maimai.home.ui.files

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maimai.home.R
import com.maimai.home.data.models.FileEntry
import com.maimai.home.data.models.FileRoot
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
    const val UPLOAD_FAB = "files.upload.fab"
    const val BREADCRUMB_ROW = "files.breadcrumb.row"
    const val SNACKBAR_HOST = "files.snackbar"
}

/**
 * Wave 5 tasks 29-35: FilesScreen rewrite.
 *  - ModalBottomSheet action sheet (R2 I5).
 *  - Delete hidden for directories (R1 #8).
 *  - Delete hidden for readOnly roots (R2 B5/I5).
 *  - Rename dialog: autofocus + trim + reject empty + reject same-name (R2 I8).
 *  - Move dialog: title "移动到", helper text, reject empty + same-path (R2 I9).
 *  - SnackBar for mutation results (R2 B3).
 *  - Leading folder/file icons (R1 #9).
 *  - Trailing chevron for directories (R2 P7).
 *  - FAB upload_file icon (R2 P2).
 *  - Breadcrumb chip row (R2 I6).
 *  - PullToRefreshBox (R2 general).
 *  - Red delete confirm button (R2 P3).
 *  - ModalBottomSheet root selector (R2 B2).
 *  - Empty state "未发现任何文件根".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    address: String,
    machineName: String,
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

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.upload(
            uri,
            { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
            { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
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
        onDownload = { entry ->
            viewModel.download(
                entry,
                { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
            )
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
                { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
            )
        },
        onMove = { entry, newPath ->
            viewModel.move(
                entry,
                newPath,
                { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
            )
        },
    )
}

/**
 * Stateless inner composable for tests.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    onDownload: (FileEntry) -> Unit,
    onUpload: () -> Unit,
    onDelete: (FileEntry) -> Unit,
    onRename: (FileEntry, String) -> Unit,
    onMove: (FileEntry, String) -> Unit,
) {
    var selectedEntry by remember { mutableStateOf<FileEntry?>(null) }
    var renameEntry by remember { mutableStateOf<FileEntry?>(null) }
    var moveEntry by remember { mutableStateOf<FileEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<FileEntry?>(null) }
    var showRootPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            R.string.files_title_format,
                            state.machineName,
                        ),
                    )
                },
            )
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Root selector button.
                Button(
                    onClick = { showRootPicker = true },
                    modifier = Modifier.testTag(FilesScreenTags.ROOT_PICKER_BUTTON),
                ) {
                    Text(state.selectedRoot?.name ?: stringResource(R.string.files_select_root))
                }

                state.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                // Breadcrumb chip row (R2 I6).
                BreadcrumbChipRow(
                    rootName = state.selectedRoot?.name ?: "",
                    segments = breadcrumbSegments,
                    onNavigate = onNavigateToPath,
                )

                // Truncation banner (W3.19 / R1 #16).
                state.listing?.takeIf { it.truncated }?.let { listing ->
                    Text(
                        text = stringResource(R.string.files_truncated_format, listing.limit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.listing?.entries ?: emptyList(), key = { it.name }) { entry ->
                        FileEntryRow(
                            entry = entry,
                            canMutate = state.canMutate,
                            onOpen = {
                                if (entry.isDirectory) onOpenFolder(entry)
                                else onDownload(entry)
                            },
                            onLongClick = { selectedEntry = entry },
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
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

    // ── Action ModalBottomSheet (R2 I5) ───────────────────────────────────────
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
                    ) { Text(stringResource(R.string.files_action_download)) }
                }
                // Rename and Move hidden on read-only roots (R2 B5).
                if (state.canMutate) {
                    TextButton(
                        onClick = { renameEntry = entry; selectedEntry = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.files_action_rename)) }
                    TextButton(
                        onClick = { moveEntry = entry; selectedEntry = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.files_action_move)) }
                }
                // Delete hidden for directories (R1 #8) and readOnly roots (R2 B5).
                if (!entry.isDirectory && state.canMutate) {
                    TextButton(
                        onClick = { deleteEntry = entry; selectedEntry = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.files_action_delete)) }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // ── Rename dialog (R2 I8) ─────────────────────────────────────────────────
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

    // ── Move dialog (R2 I9) ───────────────────────────────────────────────────
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
}

// ── File entry row ────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileEntryRow(
    entry: FileEntry,
    canMutate: Boolean,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                // Long-click opens the action sheet only for mutable roots.
                // Read-only roots do not support rename/move/delete, so the
                // action sheet would only show "下载" - we surface that via
                // the trailing chevron + tap navigation instead.
                onLongClick = if (canMutate) onLongClick else null,
            ),
    ) {
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                    contentDescription = if (entry.isDirectory) {
                        stringResource(R.string.files_kind_directory_cd)
                    } else {
                        stringResource(R.string.files_kind_file_cd)
                    },
                )
            },
            headlineContent = { Text(entry.name) },
            supportingContent = {
                Text(
                    listOf(
                        if (entry.isDirectory) stringResource(R.string.files_kind_directory)
                        else FilesViewModel.humanSize(entry.size),
                        FilesViewModel.formatDate(entry.modified),
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                )
            },
            trailingContent = {
                if (entry.isDirectory) {
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = stringResource(R.string.files_open_directory_cd),
                    )
                }
                // Files have no trailing content — download is via long-press action sheet.
            },
        )
    }
}

// ── Breadcrumb chip row (R2 I6) ───────────────────────────────────────────────

@Composable
private fun BreadcrumbChipRow(
    rootName: String,
    segments: List<String>,
    onNavigate: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag(FilesScreenTags.BREADCRUMB_ROW),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = { onNavigate("") },
            label = { Text(stringResource(R.string.files_breadcrumb_root)) },
        )
        var current = ""
        segments.forEach { segment ->
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            current = listOf(current.takeIf { it.isNotBlank() }, segment)
                .filterNotNull()
                .joinToString("/")
            val path = current
            AssistChip(
                onClick = { onNavigate(path) },
                label = { Text(segment) },
            )
        }
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
