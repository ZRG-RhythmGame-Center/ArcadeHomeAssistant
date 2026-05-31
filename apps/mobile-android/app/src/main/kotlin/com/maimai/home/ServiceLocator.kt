package com.maimai.home

import android.content.Context
import com.maimai.home.data.AgentClient
import com.maimai.home.data.AgentPreferences
import com.maimai.home.data.DiscoveryService
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
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    val preferences: AgentPreferences by lazy { AgentPreferences(appContext) }
    val agentClient: AgentClient by lazy { AgentClient(okHttpClient, json) }
    val discoveryService: DiscoveryService by lazy { DiscoveryService(appContext) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun appContext(): Context = appContext
}
