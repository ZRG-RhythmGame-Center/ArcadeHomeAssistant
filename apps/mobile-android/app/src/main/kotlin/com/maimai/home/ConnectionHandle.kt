package com.maimai.home

/**
 * Lightweight connection identity shared across all three top-level tabs.
 *
 * Connection screen produces this on a successful Agent verification.
 * Audio/Files screens consume it to drive their per-screen ViewModels.
 */
data class ConnectionHandle(
    val address: String,
    val machineName: String,
)
