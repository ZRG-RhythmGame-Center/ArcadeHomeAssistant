package com.maimai.home.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
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
    Launcher(
        route = "launcher",
        label = "启动器",
        outlinedIcon = Icons.Outlined.Apps,
        filledIcon = Icons.Filled.Apps,
    ),
    Power(
        route = "power",
        label = "电源",
        outlinedIcon = Icons.Outlined.PowerSettingsNew,
        filledIcon = Icons.Filled.PowerSettingsNew,
    ),
    Admin(
        route = "admin",
        label = "管理",
        outlinedIcon = Icons.Outlined.Settings,
        filledIcon = Icons.Filled.Settings,
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
