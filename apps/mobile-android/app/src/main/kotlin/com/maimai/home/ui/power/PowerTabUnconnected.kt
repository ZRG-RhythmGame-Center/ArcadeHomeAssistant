package com.maimai.home.ui.power

import androidx.compose.runtime.Composable
import com.maimai.home.ui.common.NotConnectedEmptyState

@Composable
fun PowerTabUnconnected(onGoToConnection: () -> Unit) {
    NotConnectedEmptyState(
        title = "尚未连接设备",
        description = "连接 Windows Agent 后才能执行远程关机。",
        onGoToConnection = onGoToConnection,
    )
}
