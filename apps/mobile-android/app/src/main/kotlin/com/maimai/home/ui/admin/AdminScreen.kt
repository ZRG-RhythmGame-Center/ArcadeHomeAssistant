package com.maimai.home.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maimai.home.data.models.LauncherItemSettingsDto
import com.maimai.home.ui.common.BentoCard
import com.maimai.home.ui.common.BentoCardTitle
import com.maimai.home.ui.common.CurrentDeviceCard
import com.maimai.home.ui.common.MaimaiScreenScaffold
import kotlinx.coroutines.launch

object AdminScreenTags {
    const val REFRESH_BUTTON = "admin.refresh"
    const val SNACKBAR_HOST = "admin.snackbar"
    const val LAUNCHER_ITEM_BUTTON_PREFIX = "admin.launcher.item."
    const val SAVE_BUTTON = "admin.save"
}

@Composable
fun AdminScreen(
    address: String,
    machineName: String,
    onSwitchDevice: () -> Unit,
) {
    val viewModel: AdminViewModel = viewModel(factory = AdminViewModel.factory(address, machineName))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    LaunchedEffect(uiState.errorMessage) {
        val message = uiState.errorMessage
        if (!message.isNullOrBlank()) snackbarHostState.showSnackbar(message)
    }
    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) snackbarHostState.showSnackbar("设置已保存")
    }

    AdminScreenContent(
        state = uiState,
        snackbarHostState = snackbarHostState,
        onRefresh = viewModel::refresh,
        onSwitchDevice = onSwitchDevice,
        onStartItem = viewModel::startLauncherItem,
        onAutoStartChange = viewModel::updateAutoStartEnabled,
        onRemoteShutdownChange = viewModel::updateRemoteShutdownEnabled,
        onLauncherFieldChange = viewModel::updateLauncherField,
        onSave = viewModel::saveCurrentSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdminScreenContent(
    state: AdminUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onRefresh: () -> Unit,
    onSwitchDevice: () -> Unit,
    onStartItem: (String) -> Unit,
    onAutoStartChange: (Boolean) -> Unit = {},
    onRemoteShutdownChange: (Boolean) -> Unit = {},
    onLauncherFieldChange: (String, Any?) -> Unit = { _, _ -> },
    onSave: () -> Unit = {},
) {
    MaimaiScreenScaffold(
        topBarActions = {
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.testTag(AdminScreenTags.REFRESH_BUTTON),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.testTag(AdminScreenTags.SNACKBAR_HOST),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CurrentDeviceCard(
                    machineName = state.machineName,
                    address = state.address,
                    onSwitchDevice = onSwitchDevice,
                    statusText = if (state.isRefreshing) "正在刷新" else "已连接",
                )
            }
            item {
                BasicSettingsCard(state, onAutoStartChange = onAutoStartChange, onLauncherFieldChange = onLauncherFieldChange)
            }
            item {
                LauncherItemsCard(state = state, onStartItem = onStartItem)
            }
            item {
                RemoteShutdownSettingsCard(state, onRemoteShutdownChange = onRemoteShutdownChange)
            }
            item {
                Button(
                    onClick = onSave,
                    enabled = state.settings != null && !state.isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AdminScreenTags.SAVE_BUTTON),
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.height(0.dp))
                    }
                    Text(if (state.isSaving) "保存中…" else "保存设置")
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun BasicSettingsCard(
    state: AdminUiState,
    onAutoStartChange: (Boolean) -> Unit,
    onLauncherFieldChange: (String, Any?) -> Unit,
) {
    val settings = state.settings
    BentoCard {
        BentoCardTitle(text = "基本设置", leadingIcon = Icons.Filled.Settings)
        Spacer(Modifier.height(12.dp))
        if (settings == null) {
            Text("尚未加载设置。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@BentoCard
        }
        SwitchRow("开机自启", settings.autoStartEnabled, onAutoStartChange)
        SwitchRow(
            "启动器自动显示",
            settings.launcher.showOnAgentStart,
        ) { v -> onLauncherFieldChange("showOnAgentStart", v) }
        TextFieldRow(
            "显示延迟秒数",
            settings.launcher.showDelaySeconds.toString(),
        ) { v -> onLauncherFieldChange("showDelaySeconds", v) }
        TextFieldRow(
            "画布宽度",
            settings.launcher.canvasWidth.toString(),
        ) { v -> onLauncherFieldChange("canvasWidth", v) }
        TextFieldRow(
            "画布高度",
            settings.launcher.canvasHeight.toString(),
        ) { v -> onLauncherFieldChange("canvasHeight", v) }
        TextFieldRow(
            "壁纸路径",
            settings.launcher.backgroundImagePath ?: "",
            placeholder = "留空使用默认壁纸",
        ) { v -> onLauncherFieldChange("backgroundImagePath", v.ifBlank { null }) }
        TextFieldRow("左移键", settings.launcher.navigateLeftKey) { v -> onLauncherFieldChange("navigateLeftKey", v) }
        TextFieldRow("右移键", settings.launcher.navigateRightKey) { v -> onLauncherFieldChange("navigateRightKey", v) }
        TextFieldRow("确认键", settings.launcher.confirmKey) { v -> onLauncherFieldChange("confirmKey", v) }
        TextFieldRow("关闭键", settings.launcher.stopKey) { v -> onLauncherFieldChange("stopKey", v) }
    }
}

@Composable
private fun LauncherItemsCard(
    state: AdminUiState,
    onStartItem: (String) -> Unit,
) {
    val items = state.settings?.launcher?.items.orEmpty().sortedBy { it.order }
    BentoCard {
        BentoCardTitle(text = "启动项", leadingIcon = Icons.Filled.PlayArrow)
        Spacer(Modifier.height(12.dp))
        if (items.isEmpty()) {
            Text("未配置启动项。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@BentoCard
        }
        items.forEach { item -> LauncherItemRow(item, onStartItem) }
    }
}

@Composable
private fun LauncherItemRow(
    item: LauncherItemSettingsDto,
    onStartItem: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name.ifBlank { item.title.ifBlank { "(未命名)" } },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!item.note.isNullOrBlank()) {
                Text(
                    text = item.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (item.enabled) "已启用" else "已禁用",
                style = MaterialTheme.typography.labelSmall,
                color = if (item.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = { onStartItem(item.id) },
            enabled = item.enabled,
            modifier = Modifier.testTag(AdminScreenTags.LAUNCHER_ITEM_BUTTON_PREFIX + item.id),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Text("启动")
        }
    }
}

@Composable
private fun RemoteShutdownSettingsCard(
    state: AdminUiState,
    onRemoteShutdownChange: (Boolean) -> Unit,
) {
    val settings = state.settings
    BentoCard {
        BentoCardTitle(text = "远程关机")
        Spacer(Modifier.height(12.dp))
        if (settings == null) {
            Text("尚未加载设置。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@BentoCard
        }
        SwitchRow("启用远程关机", settings.remoteShutdown.enabled, onRemoteShutdownChange)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TextFieldRow(
    label: String,
    value: String,
    placeholder: String? = null,
    onChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.4f))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            placeholder = placeholder?.let { { Text(it) } },
            modifier = Modifier.weight(0.6f),
        )
    }
}
