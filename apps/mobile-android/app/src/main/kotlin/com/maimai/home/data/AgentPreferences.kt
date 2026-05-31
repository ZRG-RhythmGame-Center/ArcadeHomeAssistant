package com.maimai.home.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.maimai.home.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.agentDataStore by preferencesDataStore(name = "agent_preferences")

class AgentPreferences(private val context: Context) {
    companion object {
        /**
         * Default address surfaced to the user before they pick one. Sourced
         * from `BuildConfig.DEFAULT_AGENT_ADDRESS` so debug builds get a LAN
         * sample (`192.168.1.100:8765`) and release builds start empty.
         * Closes plan task 17 / R1 #17.
         */
        val DEFAULT_AGENT_ADDRESS: String = BuildConfig.DEFAULT_AGENT_ADDRESS
        private val AGENT_ADDRESS_KEY = stringPreferencesKey("agent_address")
    }

    val agentAddressFlow: Flow<String> = context.agentDataStore.data.map { prefs ->
        prefs[AGENT_ADDRESS_KEY] ?: DEFAULT_AGENT_ADDRESS
    }

    suspend fun saveAgentAddress(address: String) {
        context.agentDataStore.edit { prefs ->
            prefs[AGENT_ADDRESS_KEY] = address
        }
    }
}
