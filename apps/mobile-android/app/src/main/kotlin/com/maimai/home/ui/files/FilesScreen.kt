package com.maimai.home.ui.files

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    address: String,
    machineName: String,
) {
    val context = LocalContext.current.applicationContext as Application
    val viewModel: FilesViewModel = viewModel(factory = FilesViewModel.factory(context, address, machineName))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedEntry by remember { mutableStateOf<com.maimai.home.data.models.FileEntry?>(null) }
    var renameEntry by remember { mutableStateOf<com.maimai.home.data.models.FileEntry?>(null) }
    var moveEntry by remember { mutableStateOf<com.maimai.home.data.models.FileEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<com.maimai.home.data.models.FileEntry?>(null) }
    var renameValue by remember(renameEntry) { mutableStateOf(renameEntry?.name.orEmpty()) }
    var moveValue by remember(moveEntry) { mutableStateOf(moveEntry?.let(viewModel::currentEntryPath).orEmpty()) }
    var rootExpanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.upload(uri, { message = it }, { message = it })
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("文件管理 · $machineName") }) },
        floatingActionButton = {
            if (uiState.selectedRoot?.readOnly == false) {
                FloatingActionButton(onClick = { launcher.launch("*/*") }) { Text("上传") }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = viewModel::refresh) { Text("刷新") }
                Button(onClick = { rootExpanded = true }) { Text(uiState.selectedRoot?.name ?: "选择根目录") }
                DropdownMenu(expanded = rootExpanded, onDismissRequest = { rootExpanded = false }) {
                    uiState.roots.forEach { root ->
                        DropdownMenuItem(
                            text = { Text(root.name) },
                            onClick = {
                                rootExpanded = false
                                viewModel.selectRoot(root)
                            },
                        )
                    }
                }
            }

            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            uiState.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Breadcrumb(rootName = uiState.selectedRoot?.name ?: "", segments = viewModel.breadcrumbSegments(), onNavigate = viewModel::navigateToPath)

            uiState.listing?.takeIf { it.truncated }?.let { listing ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "目录结果已截断，仅显示前 ${listing.limit} 项。",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                items(uiState.listing?.entries ?: emptyList(), key = { it.name }) { entry ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (entry.isDirectory) viewModel.openFolder(entry)
                                    else viewModel.download(entry, { message = "已下载到 $it" }, { message = it })
                                },
                                onLongClick = { selectedEntry = entry },
                            ),
                    ) {
                        ListItem(
                            headlineContent = { Text(entry.name) },
                            supportingContent = {
                                Text(
                                    listOf(
                                        if (entry.isDirectory) "文件夹" else FilesViewModel.humanSize(entry.size),
                                        FilesViewModel.formatDate(entry.modified),
                                    ).filter { it.isNotBlank() }.joinToString(" · "),
                                )
                            },
                            trailingContent = { Text(if (entry.isDirectory) "打开" else "下载") },
                        )
                    }
                }
            }
        }
    }

    selectedEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { selectedEntry = null },
            title = { Text(entry.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!entry.isDirectory) TextButton(onClick = {
                        selectedEntry = null
                        viewModel.download(entry, { message = "已下载到 $it" }, { message = it })
                    }) { Text("下载") }
                    TextButton(onClick = { renameEntry = entry; selectedEntry = null }) { Text("重命名") }
                    TextButton(onClick = { moveEntry = entry; selectedEntry = null }) { Text("移动") }
                    TextButton(onClick = { deleteEntry = entry; selectedEntry = null }) { Text("删除") }
                }
            },
            confirmButton = {},
        )
    }

    renameEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { renameEntry = null },
            title = { Text("重命名") },
            text = { OutlinedTextField(value = renameValue, onValueChange = { renameValue = it }, label = { Text("新名称") }) },
            confirmButton = {
                TextButton(onClick = {
                    renameEntry = null
                    viewModel.rename(entry, renameValue, { message = it }, { message = it })
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameEntry = null }) { Text("取消") } },
        )
    }

    moveEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { moveEntry = null },
            title = { Text("移动") },
            text = { OutlinedTextField(value = moveValue, onValueChange = { moveValue = it }, label = { Text("目标相对路径") }) },
            confirmButton = {
                TextButton(onClick = {
                    moveEntry = null
                    viewModel.move(entry, moveValue, { message = it }, { message = it })
                }) { Text("移动") }
            },
            dismissButton = { TextButton(onClick = { moveEntry = null }) { Text("取消") } },
        )
    }

    deleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除 “${entry.name}” 吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteEntry = null
                    viewModel.delete(entry, { message = it }, { message = it })
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteEntry = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun Breadcrumb(rootName: String, segments: List<String>, onNavigate: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(rootName)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { onNavigate("") }) { Text("/") }
                var current = ""
                segments.forEach { segment ->
                    current = listOf(current.takeIf { it.isNotBlank() }, segment).filterNotNull().joinToString("/")
                    TextButton(onClick = { onNavigate(current) }) { Text(segment) }
                }
            }
        }
    }
}
