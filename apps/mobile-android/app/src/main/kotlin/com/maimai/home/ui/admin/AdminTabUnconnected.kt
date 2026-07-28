package com.maimai.home.ui.admin

import androidx.compose.runtime.Composable
import com.maimai.home.ui.common.NotConnectedEmptyState

@Composable
fun AdminTabUnconnected(onGoToConnection: () -> Unit) {
    NotConnectedEmptyState(
        title = "尚未连接 Agent",
        description = "连接 Windows Agent 后才能管理设置。",
        onGoToConnection = onGoToConnection,
    )
}
