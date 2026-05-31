package com.maimai.home

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
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
        // The first rule installs `installedDispatcher` on Dispatchers.Main.
        val installedDispatcher = StandardTestDispatcher()
        val rule = MainDispatcherRule(installedDispatcher)
        val ctx = mockk<ExtensionContext>(relaxed = true)
        rule.beforeEach(ctx)
        rule.afterEach(ctx)

        // After afterEach, `installedDispatcher` MUST no longer drive
        // Dispatchers.Main. Prove that behaviorally by installing a SECOND
        // distinct dispatcher, scheduling a delayed coroutine on
        // Dispatchers.Main, and verifying the SECOND scheduler advanced
        // virtual time while the FIRST stayed put.
        val secondDispatcher = StandardTestDispatcher()
        val secondRule = MainDispatcherRule(secondDispatcher)
        secondRule.beforeEach(ctx)
        try {
            runTest(secondDispatcher) {
                var ranOnSecond = 0
                launch(Dispatchers.Main) {
                    delay(100)
                    ranOnSecond++
                }
                advanceUntilIdle()
                assertThat(ranOnSecond).isEqualTo(1)
            }
            // installedDispatcher must have been reset out of the slot, so
            // its scheduler never advances during the second rule's window.
            assertThat(installedDispatcher.scheduler.currentTime).isEqualTo(0L)
            // secondDispatcher's scheduler advanced past 100 ms because
            // Dispatchers.Main routed the delay through it.
            assertThat(secondDispatcher.scheduler.currentTime)
                .isAtLeast(100L)
        } finally {
            secondRule.afterEach(ctx)
        }
    }
}
