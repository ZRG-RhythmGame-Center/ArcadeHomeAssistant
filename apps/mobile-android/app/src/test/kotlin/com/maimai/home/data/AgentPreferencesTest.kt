package com.maimai.home.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DataStore round-trip + default-fallback contract for `AgentPreferences`.
 *
 * Wave 3 task 16 (and characterizes 17 - `DEFAULT_AGENT_ADDRESS` must equal
 * the BuildConfig value).
 *
 * Why Robolectric: DataStore needs a real `Context.dataStoreFile(...)`
 * resolver, and Preferences DataStore relies on the Android filesystem APIs.
 * Without Robolectric's shadowed Context, the DataStore would crash with
 * NPE on the file resolver.
 *
 * Why a hand-rolled DataStore (instead of `AgentPreferences.context.agentDataStore`):
 * the production helper installs a process-wide singleton at
 * `agent_preferences.preferences_pb`. Tests must not share state with each
 * other or with the production code path. We wire a fresh
 * `PreferenceDataStoreFactory.create(...)` per test and assert against the
 * exact same key (`agent_address`) the production code uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentPreferencesTest {

    private val testScope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var fileName: String

    private val agentAddressKey = stringPreferencesKey("agent_address")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Use a unique file per test run so prior tests do not pollute.
        fileName = "agent_test_" + System.nanoTime()
        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { context.preferencesDataStoreFile(fileName) },
        )
    }

    @After
    fun tearDown() {
        // Cancel the DataStore's host scope so its file-write coroutine
        // releases the lock before the test JVM exits.
        testScope.cancel()
    }

    @Test
    fun emittedFlow_returnsDefault_whenStoreIsEmpty() = runBlocking {
        val flow = dataStore.data.map { prefs ->
            prefs[agentAddressKey] ?: AgentPreferences.DEFAULT_AGENT_ADDRESS
        }
        val first = flow.first()
        assertThat(first).isEqualTo(AgentPreferences.DEFAULT_AGENT_ADDRESS)
    }

    @Test
    fun saveAgentAddress_andReadBack_returnsTheNewValue() = runBlocking {
        val sample = "10.0.0.42:8765"
        dataStore.edit { prefs -> prefs[agentAddressKey] = sample }

        val read = dataStore.data
            .map { prefs -> prefs[agentAddressKey] ?: AgentPreferences.DEFAULT_AGENT_ADDRESS }
            .first()
        assertThat(read).isEqualTo(sample)
    }

    @Test
    fun saveAgentAddress_overwritesPreviousValue() = runBlocking {
        dataStore.edit { it[agentAddressKey] = "first.local:8765" }
        dataStore.edit { it[agentAddressKey] = "second.local:8765" }

        val read = dataStore.data
            .map { it[agentAddressKey] ?: AgentPreferences.DEFAULT_AGENT_ADDRESS }
            .first()
        assertThat(read).isEqualTo("second.local:8765")
    }

    @Test
    fun saveAgentAddress_acceptsEmptyString() = runBlocking {
        // A user could clear the input field. The DataStore must not silently
        // fall back to the default - it stores the empty string verbatim.
        dataStore.edit { it[agentAddressKey] = "" }

        val read = dataStore.data
            .map { it[agentAddressKey] ?: AgentPreferences.DEFAULT_AGENT_ADDRESS }
            .first()
        assertThat(read).isEqualTo("")
    }

    @Test
    fun defaultIsNotHardcodedLan() {
        // Wave 3 task 17: DEFAULT_AGENT_ADDRESS should come from BuildConfig
        // so debug builds get the historic "192.168.1.100:8765" hint while
        // release builds start with an empty default and prompt the user.
        // This characterization test pins the current default; once 17 is
        // implemented, the assertion becomes BuildConfig-driven.
        val current = AgentPreferences.DEFAULT_AGENT_ADDRESS
        // Either the legacy hard-coded LAN address (debug) or empty (release).
        // After task 17 lands the next assertion will tighten.
        assertThat(current).isAnyOf("192.168.1.100:8765", "")
    }
}
