package com.maimai.home.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Top-level app destinations. Audio and Files are primary task surfaces;
 * Device keeps connection setup, switching, and troubleshooting discoverable.
 */
internal enum class AppDestination(
    val route: String,
    val label: String,
    val outlinedIcon: ImageVector,
    val filledIcon: ImageVector,
) {
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
    ),
    Device(
        route = "connection",
        label = "设备",
        outlinedIcon = Icons.Outlined.Devices,
        filledIcon = Icons.Filled.Devices,
    );

    companion object {
        fun fromRoute(route: String?): AppDestination =
            entries.firstOrNull { it.route == route } ?: Device
    }
}
