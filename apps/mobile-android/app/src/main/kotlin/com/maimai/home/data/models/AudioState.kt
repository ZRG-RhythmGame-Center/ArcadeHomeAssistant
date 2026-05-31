package com.maimai.home.data.models

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

@Keep
@Serializable
data class AudioState(
    val masterVolume: Double,
    val muted: Boolean,
    val defaultDeviceId: String? = null,
)
