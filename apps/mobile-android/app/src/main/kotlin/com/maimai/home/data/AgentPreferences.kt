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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.agentDataStore by preferencesDataStore(name = "agent_preferences")

/**
 * A persisted, user-visible entry for a previously connected Agent.
 *
 * [address] is the canonical `host:port` used to reconnect.
 * [name] is the machineName reported by the Agent at connect time;
 * the user can rename it locally.
 */
@Serializable
data class KnownDevice(
    val address: String,
    val name: String,
)

/**
 * Persisted-preferences gateway for the LAN agent address and the list of
 * previously connected devices.
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
        internal val KNOWN_DEVICES_KEY = stringPreferencesKey("known_devices_json")
        private val json = Json { ignoreUnknownKeys = true }
    }

    val agentAddressFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[AGENT_ADDRESS_KEY] ?: DEFAULT_AGENT_ADDRESS
    }

    suspend fun saveAgentAddress(address: String) {
        dataStore.edit { prefs ->
            prefs[AGENT_ADDRESS_KEY] = address
        }
    }

    /**
     * Flow of previously connected devices, ordered most-recent-first.
     * Duplicates by address are deduped (latest entry wins).
     */
    val knownDevicesFlow: Flow<List<KnownDevice>> = dataStore.data.map { prefs ->
        val raw = prefs[KNOWN_DEVICES_KEY]
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<KnownDevice>>(raw) }.getOrDefault(emptyList())
    }

    /**
     * Add (or refresh) a device entry. If an entry with the same address
     * already exists, its name is updated and it is moved to the front
     * (most-recent-first ordering). The list is capped at 20 entries.
     */
    suspend fun addKnownDevice(address: String, name: String) {
        dataStore.edit { prefs ->
            val current = prefs[KNOWN_DEVICES_KEY]
                ?.let { runCatching { json.decodeFromString<List<KnownDevice>>(it) }.getOrDefault(emptyList()) }
                ?: emptyList()
            val updated = listOf(KnownDevice(address, name)) +
                current.filterNot { it.address == address }
            prefs[KNOWN_DEVICES_KEY] = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(KnownDevice.serializer()),
                updated.take(20),
            )
        }
    }

    suspend fun removeKnownDevice(address: String) {
        dataStore.edit { prefs ->
            val current = prefs[KNOWN_DEVICES_KEY]
                ?.let { runCatching { json.decodeFromString<List<KnownDevice>>(it) }.getOrDefault(emptyList()) }
                ?: emptyList()
            prefs[KNOWN_DEVICES_KEY] = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(KnownDevice.serializer()),
                current.filterNot { it.address == address },
            )
        }
    }

    suspend fun renameKnownDevice(address: String, newName: String) {
        dataStore.edit { prefs ->
            val current = prefs[KNOWN_DEVICES_KEY]
                ?.let { runCatching { json.decodeFromString<List<KnownDevice>>(it) }.getOrDefault(emptyList()) }
                ?: emptyList()
            val updated = current.map { if (it.address == address) it.copy(name = newName) else it }
            prefs[KNOWN_DEVICES_KEY] = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(KnownDevice.serializer()),
                updated,
            )
        }
    }
}
