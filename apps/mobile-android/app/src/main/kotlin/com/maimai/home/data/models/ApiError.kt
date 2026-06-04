package com.maimai.home.data.models

import androidx.annotation.Keep

@Keep
data class ApiError(
    val kind: Kind,
    val message: String,
    val statusCode: Int? = null,
    val code: String? = null,
) {
    enum class Kind {
        Network,
        Timeout,
        Unauthorized,
        NotFound,
        Busy,
        DeviceUnavailable,
        FileTooLarge,
        Conflict,
        Unknown,
    }
}

class AgentRequestException(val apiError: ApiError) : Exception(apiError.message)
