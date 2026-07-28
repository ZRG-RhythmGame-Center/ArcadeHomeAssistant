package com.maimai.home.data.models

import kotlinx.serialization.Serializable

@Serializable
data class LauncherStatus(
    val isVisible: Boolean = false,
    val hasActiveItem: Boolean = false,
    val activeItemId: String? = null,
    val activeItemName: String? = null,
    val state: String = "idle",
    val lastError: String? = null,
)
