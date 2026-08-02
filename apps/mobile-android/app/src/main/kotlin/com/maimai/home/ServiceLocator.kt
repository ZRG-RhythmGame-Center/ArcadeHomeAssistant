package com.maimai.home

import android.content.Context
import com.maimai.home.data.AgentClient
import com.maimai.home.data.LanDns
import com.maimai.home.data.AgentPreferences
import com.maimai.home.data.DiscoveryService
import com.maimai.home.data.SharedEventStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object ServiceLocator {
    private lateinit var appContext: Context

    val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(LanDns())
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    val preferences: AgentPreferences by lazy { AgentPreferences(appContext) }
    val agentClient: AgentClient by lazy { AgentClient(okHttpClient, json) }
    val discoveryService: DiscoveryService by lazy { DiscoveryService(appContext) }
    val sharedEventStream: SharedEventStream by lazy { SharedEventStream(okHttpClient, json) }

    /**
     * Cross-screen connection handle. Connection screen writes (address +
     * machineName) on successful test/discovery; Audio and Files read.
     * `null` means no Agent confirmed yet — those tabs will render their
     * empty state and prompt the user back to the Connection tab.
     */
    private val _connectionHandle = MutableStateFlow<ConnectionHandle?>(null)
    val connectionHandle: StateFlow<ConnectionHandle?> = _connectionHandle.asStateFlow()

    fun setConnectionHandle(handle: ConnectionHandle?) {
        _connectionHandle.value = handle
        if (handle != null) {
            sharedEventStream.connect(handle.address)
        } else {
            sharedEventStream.disconnect()
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun appContext(): Context = appContext
}
