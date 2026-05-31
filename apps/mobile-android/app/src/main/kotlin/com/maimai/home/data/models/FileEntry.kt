package com.maimai.home.data.models

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

@Keep
@Serializable
data class FileEntry(
    val name: String,
    val kind: String,
    val size: Long? = null,
    val modified: String,
) {
    val isDirectory: Boolean get() = kind == "dir"
}
