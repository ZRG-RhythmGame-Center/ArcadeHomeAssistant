package com.maimai.home.ui.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.maimai.home.ui.common.BentoCard
import com.maimai.home.ui.common.BentoCardTitle
import com.maimai.home.ui.common.CurrentDeviceCard
import com.maimai.home.ui.common.MaimaiScreenScaffold
import kotlinx.coroutines.launch

object LauncherScreenTags {
    const val REFRESH_BUTTON = "launcher.refresh"
    const val SHOW_BUTTON = "launcher.show"
    const val STOP_BUTTON = "launcher.stop"
    const val SNACKBAR_HOST = "launcher.snackbar"
}

@Composable
fun LauncherScreen(
    address: String,
    machineName: String,
    onSwitchDevice: () -> Unit,
) {
    val viewModel: LauncherViewModel = viewModel(factory = LauncherViewModel.factory(address, machineName))
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

    LauncherScreenContent(
        state = uiState,
        snackbarHostState = snackbarHostState,
        onRefresh = viewModel::refresh,
        onShow = viewModel::showLauncher,
        onStop = viewModel::stopLauncherItem,
        onSwitchDevice = onSwitchDevice,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LauncherScreenContent(
    state: LauncherUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onRefresh: () -> Unit,
    onShow: () -> Unit,
    onStop: () -> Unit,
    onSwitchDevice: () -> Unit,
) {
    val busy = state.isRefreshing || state.isActionPending
    MaimaiScreenScaffold(
        topBarActions = {
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.testTag(LauncherScreenTags.REFRESH_BUTTON),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.testTag(LauncherScreenTags.SNACKBAR_HOST),
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
                LauncherControlCard(
                    state = state,
                    busy = busy,
                    onShow = onShow,
                    onStop = onStop,
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun LauncherControlCard(
    state: LauncherUiState,
    busy: Boolean,
    onShow: () -> Unit,
    onStop: () -> Unit,
) {
    BentoCard {
        BentoCardTitle(
            text = "启动器",
            leadingIcon = Icons.Filled.Apps,
            leadingIconTint = if (state.launcherAvailable) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = describeLauncherState(state),
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.launcherStatus?.lastError != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onShow,
            enabled = state.launcherAvailable && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(LauncherScreenTags.SHOW_BUTTON),
        ) {
            Icon(Icons.Filled.Visibility, contentDescription = null)
            Spacer(Modifier.height(0.dp))
            Text("显示启动器")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onStop,
            enabled = state.launcherAvailable && state.launcherStatus?.hasActiveItem == true && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(LauncherScreenTags.STOP_BUTTON),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Icon(Icons.Filled.StopCircle, contentDescription = null)
            Text("关闭当前项")
        }
        if (!state.launcherAvailable) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Agent 未启用启动器或当前会话不持有启动器。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy) {
            Spacer(Modifier.height(8.dp))
            CircularProgressIndicator(modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun describeLauncherState(state: LauncherUiState): String {
    val status = state.launcherStatus
    return when {
        status == null -> "尚未加载启动器状态。"
        status.lastError != null -> "启动器错误：${status.lastError}"
        status.hasActiveItem && status.activeItemName != null ->
            if (status.isVisible) "正在显示，当前运行：${status.activeItemName}"
            else "当前运行：${status.activeItemName}（启动器已隐藏）"
        status.isVisible -> "启动器已显示，未运行任何启动项。"
        else -> "启动器空闲。"
    }
}
