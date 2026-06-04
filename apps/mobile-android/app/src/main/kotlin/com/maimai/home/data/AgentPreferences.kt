package com.maimai.home.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.maimai.home.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.agentDataStore by preferencesDataStore(name = "agent_preferences")

/**
 * Persisted-preferences gateway for the LAN agent address.
 *
 * The primary constructor takes an injectable [DataStore] so unit tests can
 * supply a hand-rolled [androidx.datastore.preferences.core.PreferenceDataStoreFactory.create]
 * instance and exercise this class's flow + saveAgentAddress logic without
 * tripping the process-wide singleton at `agent_preferences.preferences_pb`.
 *
 * Production code uses the secondary [Context]-taking constructor, which
 * resolves to the singleton DataStore declared by the
 * [preferencesDataStore] delegate at file scope above.
 */
class AgentPreferences(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.agentDataStore)

    companion object {
        /**
         * Default address surfaced to the user before they pick one. Sourced
         * from `BuildConfig.DEFAULT_AGENT_ADDRESS` so debug builds get a LAN
         * sample (`192.168.1.100:8765`) and release builds start empty.
         */
        val DEFAULT_AGENT_ADDRESS: String = BuildConfig.DEFAULT_AGENT_ADDRESS
        internal val AGENT_ADDRESS_KEY = stringPreferencesKey("agent_address")
    }

    val agentAddressFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[AGENT_ADDRESS_KEY] ?: DEFAULT_AGENT_ADDRESS
    }

    suspend fun saveAgentAddress(address: String) {
        dataStore.edit { prefs ->
            prefs[AGENT_ADDRESS_KEY] = address
        }
    }
}
