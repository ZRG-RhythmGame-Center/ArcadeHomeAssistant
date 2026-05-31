package com.maimai.home.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.maimai.home.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-instance unit tests for [AgentPreferences]. Wave 3 task 16 (and
 * characterizes 17 - `DEFAULT_AGENT_ADDRESS` reads `BuildConfig`).
 *
 * Tests build a hand-rolled [DataStore] file under Robolectric's storage and
 * inject it through the test-only constructor, so this exercises the
 * production class's `agentAddressFlow.map { ... }` and
 * `saveAgentAddress(...) { dataStore.edit { ... } }` codepaths directly.
 *
 * Why Robolectric + a fresh DataStore per test:
 * - DataStore needs `Context.preferencesDataStoreFile(...)` to resolve a real
 *   filesystem path; raw android.jar stubs return null and the DataStore
 *   crashes at first read.
 * - Reusing the production singleton (`Context.agentDataStore` delegate)
 *   would leak state across tests and across the JVM lifetime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentPreferencesTest {

    private val testScope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var preferences: AgentPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Unique file per test method so prior runs cannot pollute.
        val fileName = "agent_test_" + System.nanoTime()
        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { context.preferencesDataStoreFile(fileName) },
        )
        preferences = AgentPreferences(dataStore)
    }

    @After
    fun tearDown() {
        // Cancel the DataStore's host scope so its file-write coroutine
        // releases its lock before the test JVM exits.
        testScope.cancel()
    }

    @Test
    fun agentAddressFlow_emitsDefault_whenStoreIsEmpty() = runBlocking {
        val first = preferences.agentAddressFlow.first()
        assertThat(first).isEqualTo(AgentPreferences.DEFAULT_AGENT_ADDRESS)
    }

    @Test
    fun saveAgentAddress_andReadBack_returnsTheNewValue() = runBlocking {
        val sample = "10.0.0.42:8765"
        preferences.saveAgentAddress(sample)

        val read = preferences.agentAddressFlow.first()
        assertThat(read).isEqualTo(sample)
    }

    @Test
    fun saveAgentAddress_overwritesPreviousValue() = runBlocking {
        preferences.saveAgentAddress("first.local:8765")
        preferences.saveAgentAddress("second.local:8765")

        val read = preferences.agentAddressFlow.first()
        assertThat(read).isEqualTo("second.local:8765")
    }

    @Test
    fun saveAgentAddress_acceptsEmptyString() = runBlocking {
        // A user could clear the input field. The DataStore must store the
        // empty string verbatim - the default fallback only kicks in when
        // the key is missing entirely, not when it is set to "".
        preferences.saveAgentAddress("")

        val read = preferences.agentAddressFlow.first()
        assertThat(read).isEqualTo("")
    }

    @Test
    fun defaultAgentAddress_equalsBuildConfigField() {
        // Wave 3 task 17: DEFAULT_AGENT_ADDRESS must be sourced from the
        // BuildConfig field that Gradle wires per-variant. A regression that
        // re-introduced the hardcoded "192.168.1.100:8765" literal would make
        // the constant diverge from BuildConfig.DEFAULT_AGENT_ADDRESS and
        // this test would flip RED.
        assertThat(AgentPreferences.DEFAULT_AGENT_ADDRESS)
            .isEqualTo(BuildConfig.DEFAULT_AGENT_ADDRESS)
    }

    @Test
    fun debugBuild_defaultIsHistoricLanSample() {
        // Tests run against the debug variant. The Gradle config wires the
        // debug BuildConfig.DEFAULT_AGENT_ADDRESS to the historic LAN sample
        // so developers can sideload + connect immediately. Asserting the
        // debug-side value also implicitly verifies the buildConfigField
        // wiring landed on the debug variant.
        if (BuildConfig.DEBUG) {
            assertThat(AgentPreferences.DEFAULT_AGENT_ADDRESS)
                .isEqualTo("192.168.1.100:8765")
        } else {
            // Release builds start empty so the input shows the placeholder.
            assertThat(AgentPreferences.DEFAULT_AGENT_ADDRESS).isEmpty()
        }
    }
}
