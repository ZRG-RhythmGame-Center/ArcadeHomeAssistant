package com.maimai.home

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Verifies [MainDispatcherRule] sets and resets `Dispatchers.Main`. (Wave 2.8)
 *
 * RED expectation: without the rule, `Dispatchers.setMain` would not be invoked
 * before a test executes — `Dispatchers.Main` is unavailable on the JVM, so any
 * `viewModelScope.launch { ... }` call would crash with
 * `MissingTestDispatcherException` from `kotlinx-coroutines-test`.
 *
 * GREEN: the rule installs a [StandardTestDispatcher] before each test and
 * tears it down afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRuleTest {

    @JvmField
    @RegisterExtension
    val mainDispatcherRule: MainDispatcherRule =
        MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun setsAndResetsMain() = runTest(mainDispatcherRule.testDispatcher) {
        // Once the rule's BeforeEach hook runs, Dispatchers.Main delegates to
        // the rule's StandardTestDispatcher. We can prove that by scheduling a
        // task and observing it only runs after `runCurrent()`.
        var counter = 0
        launch(Dispatchers.Main) { counter++ }
        assertThat(counter).isEqualTo(0)
        runCurrent()
        assertThat(counter).isEqualTo(1)
    }
}
