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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
                BasicSettingsCard(state)
            }
            item {
                LauncherItemsCard(state = state, onStartItem = onStartItem)
            }
            item {
                RemoteShutdownSettingsCard(state)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun BasicSettingsCard(state: AdminUiState) {
    val settings = state.settings
    BentoCard {
        BentoCardTitle(text = "基本设置", leadingIcon = Icons.Filled.Settings)
        Spacer(Modifier.height(12.dp))
        if (settings == null) {
            Text("尚未加载设置。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@BentoCard
        }
        SettingRow("开机自启", if (settings.autoStartEnabled) "已启用" else "未启用")
        SettingRow("启动器自动显示", if (settings.launcher.showOnAgentStart) "是" else "否")
        SettingRow("显示延迟秒数", settings.launcher.showDelaySeconds.toString())
        SettingRow("画布尺寸", "${settings.launcher.canvasWidth}×${settings.launcher.canvasHeight}")
        SettingRow("壁纸路径", settings.launcher.backgroundImagePath ?: "（默认）")
        SettingRow("左移键", settings.launcher.navigateLeftKey)
        SettingRow("右移键", settings.launcher.navigateRightKey)
        SettingRow("确认键", settings.launcher.confirmKey)
        SettingRow("关闭键", settings.launcher.stopKey)
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
private fun RemoteShutdownSettingsCard(state: AdminUiState) {
    val settings = state.settings
    BentoCard {
        BentoCardTitle(text = "远程关机")
        Spacer(Modifier.height(12.dp))
        if (settings == null) {
            Text("尚未加载设置。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@BentoCard
        }
        SettingRow("启用远程关机", if (settings.remoteShutdown.enabled) "已启用" else "未启用")
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
