package com.maimai.home.ui.audio

import androidx.compose.runtime.Composable
import com.maimai.home.ui.common.NotConnectedEmptyState

/**
 * Empty state for the Audio tab when no Agent has been verified yet.
 * Used by the bottom-nav graph in [com.maimai.home.ui.nav.MaimaiNavHost].
 */
@Composable
fun AudioTabUnconnected(onGoToConnection: () -> Unit) {
    NotConnectedEmptyState(
        title = "尚未连接 Agent",
        description = "请先在「连接」页选择并测试一台 Agent，再返回这里调节音频。",
        onGoToConnection = onGoToConnection,
    )
}
