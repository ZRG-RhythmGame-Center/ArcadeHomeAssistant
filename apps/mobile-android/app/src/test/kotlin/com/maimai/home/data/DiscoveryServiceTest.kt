package com.maimai.home.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * RED phase tests for DiscoveryService.
 *
 * Tests verify:
 * 1. MulticastLock is acquired before discovery and released after (via injected factory)
 * 2. discover() completes within timeout — no runBlocking hang in resolution path
 *
 * RED: Both tests fail because DiscoveryService doesn't accept MulticastLockFactory yet.
 */
class DiscoveryServiceTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Fake lock that records acquire/release calls. */
    class FakeLock : MulticastLock {
        var acquireCount = 0
        var releaseCount = 0
        override fun acquire() { acquireCount++ }
        override fun release() { releaseCount++ }
        override fun setReferenceCounted(counted: Boolean) = Unit
    }

    /** Fake factory that hands out a single FakeLock. */
    class FakeLockFactory : MulticastLockFactory {
        val lock = FakeLock()
        override fun createMulticastLock(tag: String): MulticastLock = lock
    }

    /**
     * Fake NsdManager that never calls back on resolveService — simulates a
     * hung resolution. With runBlocking this would block forever; with
     * suspendCancellableCoroutine + timeout it returns gracefully.
     */
    class HangingNsdManager : FakeNsdManager() {
        override fun resolveService(
            serviceInfo: android.net.nsd.NsdServiceInfo,
            listener: android.net.nsd.NsdManager.ResolveListener,
        ) {
            // intentionally never calls back
        }
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * RED: fails because DiscoveryService(nsdManager, lockFactory) constructor
     * doesn't exist yet.
     */
    @Test
    fun acquiresAndReleasesMulticastLock() {
        val factory = FakeLockFactory()
        val nsd = FakeNsdManager()

        // RED: this constructor overload doesn't exist yet → compile error
        val service = DiscoveryService(nsdManager = nsd, lockFactory = factory)

        runBlocking { service.discover(timeoutMillis = 200) }

        assertEquals(1, factory.lock.acquireCount, "MulticastLock.acquire() must be called exactly once")
        assertEquals(1, factory.lock.releaseCount, "MulticastLock.release() must be called exactly once in finally")
    }

    /**
     * RED: fails because DiscoveryService(nsdManager, lockFactory) constructor
     * doesn't exist yet.
     *
     * With runBlocking in suspendResolve this would hang; with
     * suspendCancellableCoroutine it returns within the outer timeout.
     */
    @Test
    fun doesNotRunBlockingInside() {
        val factory = FakeLockFactory()
        val nsd = HangingNsdManager()

        // RED: this constructor overload doesn't exist yet → compile error
        val service = DiscoveryService(nsdManager = nsd, lockFactory = factory)

        val result = runBlocking {
            withTimeoutOrNull(7_000) {
                service.discover(timeoutMillis = 500)
            }
        }

        assertNotNull(result, "discover() must complete within 7 s — runBlocking in resolution would hang")
    }
}
