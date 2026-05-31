package com.maimai.home.data.models

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

@Keep
@Serializable
data class FileRoot(
    val id: String,
    val name: String,
    val readOnly: Boolean,
)
