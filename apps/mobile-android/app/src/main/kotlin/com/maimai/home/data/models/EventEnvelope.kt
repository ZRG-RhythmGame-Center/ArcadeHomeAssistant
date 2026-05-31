package com.maimai.home.data.models

import androidx.annotation.Keep
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Keep
@Serializable
data class EventEnvelope(
    val type: String,
    val payload: JsonElement,
    val timestamp: String,
)
