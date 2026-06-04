package com.maimai.home.data.models

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

@Keep
@Serializable
data class RemoteShutdownStatus(
    val available: Boolean = false,
    val state: String = "idle",
    val error: String? = null,
)
