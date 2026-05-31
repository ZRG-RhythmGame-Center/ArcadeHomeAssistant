package com.maimai.home.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.mockito.Mockito
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for DiscoveryService.
 *
 * Verifies:
 * 1. MulticastLock is acquired before discovery and released after (via injected factory).
 * 2. discover() completes within timeout even when an NSD ResolveListener never
 *    fires — i.e. resolution coroutines are cancelled, not awaited indefinitely.
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
     * Fake NsdManager that:
     *  - Reports discovery started immediately.
     *  - Emits exactly one onServiceFound for a fake service whose serviceType
     *    matches the production filter ("_maimai-home._tcp.").
     *  - Never calls back from resolveService — simulates a hung NSD resolution.
     *
     * This actually exercises the hang path (real call into the resolution
     * coroutine), so the assertion in [discoverDoesNotHangWhenResolutionStalls]
     * is meaningful, not vacuous.
     */
    class HangingNsdManager : FakeNsdManager() {
        override fun discoverServices(
            serviceType: String,
            protocolType: Int,
            listener: android.net.nsd.NsdManager.DiscoveryListener,
        ) {
            listener.onDiscoveryStarted(serviceType)
            // Mockito 5.x defaults to inline-mock-maker, which can mock the
            // final NsdServiceInfo class without extra configuration.
            val info = Mockito.mock(android.net.nsd.NsdServiceInfo::class.java)
            Mockito.`when`(info.serviceType).thenReturn("_maimai-home._tcp.")
            listener.onServiceFound(info)
        }

        override fun resolveService(
            serviceInfo: android.net.nsd.NsdServiceInfo,
            listener: android.net.nsd.NsdManager.ResolveListener,
        ) {
            // intentionally never calls back — simulates a hung resolution
        }
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    fun acquiresAndReleasesMulticastLock() {
        val factory = FakeLockFactory()
        val nsd = FakeNsdManager()

        val service = DiscoveryService(nsdManager = nsd, lockFactory = factory)

        runBlocking { service.discover(timeoutMillis = 200) }

        assertEquals(1, factory.lock.acquireCount, "MulticastLock.acquire() must be called exactly once")
        assertEquals(1, factory.lock.releaseCount, "MulticastLock.release() must be called exactly once in finally")
    }

    /**
     * If a ResolveListener never fires, DiscoveryService.discover() must still
     * return within the outer timeout (we cancel pending resolution jobs once
     * the discovery window elapses). We give an outer ceiling of 7 s with a
     * 500 ms discovery window — if the cancellation is broken, the test hangs
     * far past the ceiling and withTimeoutOrNull returns null.
     */
    @Test
    fun discoverDoesNotHangWhenResolutionStalls() {
        val factory = FakeLockFactory()
        val nsd = HangingNsdManager()

        val service = DiscoveryService(nsdManager = nsd, lockFactory = factory)

        val result = runBlocking {
            withTimeoutOrNull(7_000) {
                service.discover(timeoutMillis = 500)
            }
        }

        assertNotNull(result, "discover() must complete within 7 s even when resolveService never calls back")
        assertEquals(0, result.size, "no services should be returned when resolution hangs")
    }
}
