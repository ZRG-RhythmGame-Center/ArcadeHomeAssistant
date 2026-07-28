package com.maimai.home.ui.power

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
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maimai.home.ui.common.BentoCard
import com.maimai.home.ui.common.BentoCardTitle
import com.maimai.home.ui.common.CurrentDeviceCard
import com.maimai.home.ui.common.MaimaiScreenScaffold

object PowerScreenTags {
    const val SHUTDOWN_BUTTON = "power.shutdown"
    const val CONFIRM_BUTTON = "power.confirm"
    const val REFRESH_BUTTON = "power.refresh"
    const val SNACKBAR_HOST = "power.snackbar"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerScreen(
    address: String,
    machineName: String,
    onOpenDevice: () -> Unit,
    viewModel: PowerViewModel = viewModel(factory = PowerViewModel.factory(address, machineName)),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val updatedError = rememberUpdatedState(uiState.errorMessage)

    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    LaunchedEffect(updatedError.value) {
        val message = updatedError.value
        if (!message.isNullOrBlank()) snackbarHostState.showSnackbar(message)
    }

    PowerScreenContent(
        state = uiState,
        snackbarHostState = snackbarHostState,
        onOpenDevice = onOpenDevice,
        onRefresh = viewModel::refresh,
        onShowConfirm = viewModel::showConfirm,
        onHideConfirm = viewModel::hideConfirm,
        onExecute = viewModel::executeShutdown,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PowerScreenContent(
    state: PowerUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onOpenDevice: () -> Unit,
    onRefresh: () -> Unit,
    onShowConfirm: () -> Unit,
    onHideConfirm: () -> Unit,
    onExecute: () -> Unit,
) {
    val busy = state.isRefreshing || state.isExecuting
    MaimaiScreenScaffold(
        topBarActions = {
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.testTag(PowerScreenTags.REFRESH_BUTTON),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.testTag(PowerScreenTags.SNACKBAR_HOST),
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
                    onSwitchDevice = onOpenDevice,
                    statusText = if (state.isRefreshing) "正在刷新" else "已连接",
                )
            }
            item {
                RemoteShutdownCard(
                    state = state,
                    busy = busy,
                    onShowConfirm = onShowConfirm,
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (state.confirmVisible) {
        AlertDialog(
            onDismissRequest = onHideConfirm,
            icon = { Icon(Icons.Filled.PowerSettingsNew, contentDescription = null) },
            title = { Text("确认远程关机") },
            text = {
                Text(
                    "将关闭 ${state.machineName}（${state.address}）。确认后将立即关机。",
                )
            },
            confirmButton = {
                Button(
                    onClick = onExecute,
                    enabled = !state.isExecuting,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag(PowerScreenTags.CONFIRM_BUTTON),
                ) {
                    Text("确认关机")
                }
            },
            dismissButton = {
                TextButton(onClick = onHideConfirm) {
                    Text("返回")
                }
            },
        )
    }
}

@Composable
private fun RemoteShutdownCard(
    state: PowerUiState,
    busy: Boolean,
    onShowConfirm: () -> Unit,
) {
    BentoCard {
        BentoCardTitle(
            text = "远程关机",
            leadingIcon = Icons.Filled.PowerSettingsNew,
            leadingIconTint = if (state.remoteShutdownAvailable) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = describePowerState(state),
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.shutdownStatus?.state == "failed") {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onShowConfirm,
            enabled = state.remoteShutdownAvailable && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(PowerScreenTags.SHUTDOWN_BUTTON),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Icon(Icons.Filled.PowerSettingsNew, contentDescription = null)
            Text("远程关机")
        }
        if (!state.remoteShutdownAvailable) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Agent 未启用远程关机。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun describePowerState(state: PowerUiState): String {
    val shutdown = state.shutdownStatus
    return when {
        shutdown?.state == "failed" -> "上次关机失败：${shutdown.error ?: "未知错误"}"
        shutdown?.state == "executing" -> "已开始关机"
        state.remoteShutdownAvailable -> "远程关机可用，确认后会立即关机。"
        else -> "远程关机不可用。"
    }
}
