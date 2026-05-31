package com.maimai.home.data.models

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

@Keep
@Serializable
data class AudioDevice(
    val id: String,
    val name: String,
    val isDefault: Boolean,
    val state: String,
)
