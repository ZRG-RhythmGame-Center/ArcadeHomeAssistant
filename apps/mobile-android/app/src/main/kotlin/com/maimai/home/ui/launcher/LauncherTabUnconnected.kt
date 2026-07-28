package com.maimai.home.ui.launcher

import androidx.compose.runtime.Composable
import com.maimai.home.ui.common.NotConnectedEmptyState

@Composable
fun LauncherTabUnconnected(onGoToConnection: () -> Unit) {
    NotConnectedEmptyState(
        title = "尚未连接 Agent",
        description = "连接 Windows Agent 后才能控制启动器。",
        onGoToConnection = onGoToConnection,
    )
}
