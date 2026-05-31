package com.maimai.home

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Verifies [MainDispatcherRule] sets AND resets `Dispatchers.Main`. (Wave 2.8)
 *
 * Two tests, exercising both halves of the rule's contract independently so
 * that a regression in either half (e.g. dropping the `resetMain()` call in
 * `afterEach`) flips this suite red on its own.
 *
 * Both tests drive the rule's lifecycle hooks by hand instead of relying on
 * `@RegisterExtension`. That avoids cross-test pollution: a removed
 * `resetMain()` would still appear to work between consecutive Jupiter tests
 * because `setMain` stacks, and the next test's `beforeEach` would just
 * push another override on top.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRuleTest {

    @Test
    fun beforeEach_installsTestDispatcherOnMain() {
        val rule = MainDispatcherRule(StandardTestDispatcher())
        val ctx = mockk<ExtensionContext>(relaxed = true)
        rule.beforeEach(ctx)
        try {
            runTest(rule.testDispatcher) {
                // Once installed, Dispatchers.Main delegates to the rule's
                // StandardTestDispatcher. Prove by scheduling work and
                // observing it only runs after runCurrent().
                var counter = 0
                launch(Dispatchers.Main) { counter++ }
                assertThat(counter).isEqualTo(0)
                runCurrent()
                assertThat(counter).isEqualTo(1)
            }
        } finally {
            rule.afterEach(ctx)
        }
    }

    @Test
    fun afterEach_actuallyCallsResetMain() {
        val dispatcher = StandardTestDispatcher()
        val rule = MainDispatcherRule(dispatcher)
        val ctx = mockk<ExtensionContext>(relaxed = true)

        rule.beforeEach(ctx)
        rule.afterEach(ctx)

        // After afterEach, the test override is gone. On JVM unit tests with
        // no platform Main dispatcher (Android Looper), kotlinx-coroutines-test
        // 1.10+ throws IllegalStateException synchronously the moment any code
        // touches Dispatchers.Main's dispatch path. Force that touch by
        // calling `isDispatchNeeded` on the property. This is the cheapest
        // synchronous probe; runBlocking + withContext(Dispatchers.Main) can
        // deadlock instead of throwing because the missing dispatcher swallows
        // the continuation rather than dispatching it.
        //
        // If MainDispatcherRule.afterEach were a no-op (resetMain() removed),
        // Dispatchers.Main would still resolve to the test override and the
        // call would succeed instead of throwing - the assertion would fail.
        val ex = assertFailsWith<IllegalStateException> {
            Dispatchers.Main.isDispatchNeeded(kotlin.coroutines.EmptyCoroutineContext)
        }
        // Defensive sanity: the failure must reference the missing Main
        // dispatcher, not be a generic ISE leaked from somewhere else.
        assertThat(ex.message ?: "").contains("Dispatchers.Main")
        assertThat(ex.message ?: "").contains("resetMain")
    }
}
