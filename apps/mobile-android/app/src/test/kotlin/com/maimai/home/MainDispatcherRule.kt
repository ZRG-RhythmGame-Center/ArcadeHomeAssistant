package com.maimai.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * JUnit 5 extension that swaps `Dispatchers.Main` for a [TestDispatcher] for the
 * duration of each test method.
 *
 * Usage:
 * ```
 * class MyViewModelTest {
 *     @JvmField
 *     @RegisterExtension
 *     val mainDispatcherRule = MainDispatcherRule()
 * }
 * ```
 *
 * Defaults to [UnconfinedTestDispatcher] so that coroutines launched on
 * `Dispatchers.Main` execute eagerly without `runCurrent()` plumbing — this is
 * what Compose `viewModelScope` consumers usually want. Pass an explicit
 * dispatcher (e.g. `StandardTestDispatcher`) for tests that need controlled
 * dispatch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : BeforeEachCallback, AfterEachCallback {

    override fun beforeEach(context: ExtensionContext) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun afterEach(context: ExtensionContext) {
        Dispatchers.resetMain()
    }
}
