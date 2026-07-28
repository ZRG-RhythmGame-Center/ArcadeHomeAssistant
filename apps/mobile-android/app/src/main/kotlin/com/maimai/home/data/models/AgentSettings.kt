package com.maimai.home.data.models

import kotlinx.serialization.Serializable

@Serializable
data class AgentSettingsSnapshot(
    val autoStartEnabled: Boolean = false,
    val launcher: LauncherSettingsDto = LauncherSettingsDto(),
    val fileRoots: List<FileRootSettingsDto> = emptyList(),
    val remoteShutdown: RemoteShutdownSettingsDto = RemoteShutdownSettingsDto(),
)

@Serializable
data class AgentSettingsUpdateRequest(
    val autoStartEnabled: Boolean? = null,
    val launcher: LauncherSettingsDto? = null,
    val fileRoots: List<FileRootSettingsDto>? = null,
    val remoteShutdown: RemoteShutdownSettingsDto? = null,
)

@Serializable
data class LauncherSettingsDto(
    val showOnAgentStart: Boolean = false,
    val showDelaySeconds: Int = 0,
    val canvasWidth: Int = 1080,
    val canvasHeight: Int = 1920,
    val backgroundImagePath: String? = null,
    val navigateLeftKey: String = "Left",
    val navigateRightKey: String = "Right",
    val confirmKey: String = "Enter",
    val stopKey: String = "F11",
    val items: List<LauncherItemSettingsDto> = emptyList(),
)

@Serializable
data class LauncherItemSettingsDto(
    val id: String = "",
    val name: String = "",
    val title: String = "",
    val note: String? = null,
    val iconPath: String? = null,
    val commandLine: String = "",
    val workingDirectory: String? = null,
    val stopCommandLine: String = "",
    val stopWorkingDirectory: String? = null,
    val key: String = "",
    val order: Int = 0,
    val enabled: Boolean = true,
)

@Serializable
data class FileRootSettingsDto(
    val id: String = "",
    val name: String = "",
    val path: String = "",
    val readOnly: Boolean = false,
)

@Serializable
data class RemoteShutdownSettingsDto(
    val enabled: Boolean = false,
)
