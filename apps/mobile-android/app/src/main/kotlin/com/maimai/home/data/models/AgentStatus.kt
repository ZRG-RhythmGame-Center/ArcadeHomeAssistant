package com.maimai.home.data.models

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

@Keep
@Serializable
data class AgentStatus(
    val machineName: String,
    val version: String,
    val uptimeSeconds: Long,
    val capabilities: Capabilities,
    /** Optional canonical base URL the agent advertises, e.g. http://192.168.1.5:8765. */
    val baseUrl: String? = null,
)

@Keep
@Serializable
data class Capabilities(
    val audioVolume: Boolean = false,
    val audioMute: Boolean = false,
    val audioDeviceSwitch: Boolean = false,
    val fileManagement: Boolean = false,
    val discoveryBroadcast: Boolean = false,
    val remoteShutdown: Boolean = false,
    val settingsManagement: Boolean = false,
    val launcher: Boolean = false,
)
