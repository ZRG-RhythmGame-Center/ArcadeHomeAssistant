package com.maimai.home.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Top-level app destinations. Three tabs sit side-by-side in a bottom nav
 * (matches the design where Connection/Audio/Files are the 3 sibling
 * destinations, not a linear connect-then-drill flow).
 */
internal enum class AppDestination(
    val route: String,
    val label: String,
    val outlinedIcon: ImageVector,
    val filledIcon: ImageVector,
) {
    Connection(
        route = "connection",
        label = "连接",
        outlinedIcon = Icons.Outlined.Wifi,
        filledIcon = Icons.Filled.Wifi,
    ),
    Audio(
        route = "audio",
        label = "音频",
        outlinedIcon = Icons.Outlined.VolumeUp,
        filledIcon = Icons.Filled.VolumeUp,
    ),
    Files(
        route = "files",
        label = "文件",
        outlinedIcon = Icons.Outlined.Folder,
        filledIcon = Icons.Filled.Folder,
    );

    companion object {
        fun fromRoute(route: String?): AppDestination =
            entries.firstOrNull { it.route == route } ?: Connection
    }
}
