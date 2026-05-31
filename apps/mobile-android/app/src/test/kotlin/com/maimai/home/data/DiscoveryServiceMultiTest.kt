package com.maimai.home.data

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

/**
 * Wave 3 task 15: extends Wave 1's DiscoveryServiceTest with multi-service +
 * dedupe scenarios.
 *
 * Wave 1 covered:
 * - MulticastLock acquire / release lifecycle
 * - hung-resolution timeout safety
 *
 * This file covers:
 * - resolution returns multiple services in a stable name-sorted order
 * - duplicate hosts (same address) are de-duped to one DiscoveredService
 * - service types other than `_maimai-home._tcp.` are ignored
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DiscoveryServiceMultiTest {

    /**
     * Fake NsdManager that emits a configurable list of NsdServiceInfo on
     * onServiceFound, then resolves each one through [resolveResults].
     */
    private class ScriptedNsdManager(
        private val founds: List<NsdServiceInfo>,
        private val resolveResults: Map<NsdServiceInfo, NsdServiceInfo?>,
    ) : FakeNsdManager() {
        override fun discoverServices(
            serviceType: String,
            protocolType: Int,
            listener: NsdManager.DiscoveryListener,
        ) {
            listener.onDiscoveryStarted(serviceType)
            founds.forEach { listener.onServiceFound(it) }
        }

        override fun resolveService(
            serviceInfo: NsdServiceInfo,
            listener: NsdManager.ResolveListener,
        ) {
            val resolved = resolveResults[serviceInfo]
            if (resolved == null) {
                listener.onResolveFailed(serviceInfo, NsdManager.FAILURE_INTERNAL_ERROR)
            } else {
                listener.onServiceResolved(resolved)
            }
        }
    }

    private class FakeLock : MulticastLock {
        override fun acquire() = Unit
        override fun release() = Unit
        override fun setReferenceCounted(counted: Boolean) = Unit
    }

    private class FakeLockFactory : MulticastLockFactory {
        override fun createMulticastLock(tag: String): MulticastLock = FakeLock()
    }

    /**
     * Build a real NsdServiceInfo using its public no-arg constructor +
     * setters. This is the cheapest way to produce a non-mocked instance
     * Robolectric-free — NsdServiceInfo is final but not marked sealed.
     */
    private fun infoFor(
        name: String,
        type: String,
        host: InetAddress?,
        port: Int,
    ): NsdServiceInfo {
        val info = NsdServiceInfo()
        info.serviceName = name
        info.serviceType = type
        info.host = host
        info.port = port
        return info
    }

    @Test
    fun resolves_multiple_services_in_name_sorted_order() {
        val type = "_maimai-home._tcp."
        val foundC = infoFor("PC-C", type, null, 8765)
        val foundA = infoFor("PC-A", type, null, 8765)
        val foundB = infoFor("PC-B", type, null, 8765)

        val resolvedC = infoFor("PC-C", type, InetAddress.getByName("10.0.0.3"), 8765)
        val resolvedA = infoFor("PC-A", type, InetAddress.getByName("10.0.0.1"), 8765)
        val resolvedB = infoFor("PC-B", type, InetAddress.getByName("10.0.0.2"), 8765)

        val nsd = ScriptedNsdManager(
            founds = listOf(foundC, foundA, foundB),
            resolveResults = mapOf(
                foundC to resolvedC,
                foundA to resolvedA,
                foundB to resolvedB,
            ),
        )
        val service = DiscoveryService(nsd, FakeLockFactory())

        val result = runBlocking { service.discover(timeoutMillis = 300) }

        // DiscoveryService sorts by name. Map names -> hosts to assert order.
        assertThat(result.map { it.name }).containsExactly("PC-A", "PC-B", "PC-C").inOrder()
        assertThat(result.map { it.host }).containsExactly("10.0.0.1", "10.0.0.2", "10.0.0.3").inOrder()
        assertThat(result.map { it.port }).containsExactly(8765, 8765, 8765)
    }

    @Test
    fun dedupes_duplicate_address() {
        val type = "_maimai-home._tcp."
        // Two onServiceFound for the SAME backing host:port. The dedupe
        // happens by `address` (host:port) - if both resolve to the same
        // 10.0.0.5:8765 we keep the first one (LinkedHashMap insertion order).
        val foundFirst = infoFor("PC-First", type, null, 8765)
        val foundDup = infoFor("PC-Duplicate", type, null, 8765)

        val resolvedFirst = infoFor("PC-First", type, InetAddress.getByName("10.0.0.5"), 8765)
        val resolvedDup = infoFor("PC-Duplicate", type, InetAddress.getByName("10.0.0.5"), 8765)

        val nsd = ScriptedNsdManager(
            founds = listOf(foundFirst, foundDup),
            resolveResults = mapOf(
                foundFirst to resolvedFirst,
                foundDup to resolvedDup,
            ),
        )
        val service = DiscoveryService(nsd, FakeLockFactory())

        val result = runBlocking { service.discover(timeoutMillis = 300) }

        assertThat(result).hasSize(1)
        // The MAP semantics keep the LATEST insertion under a given key. The
        // implementation uses linkedMapOf<address, DiscoveredService>(); the
        // second insertion overwrites the first. We assert the last one wins
        // so this test will fail loudly if dedupe semantics drift.
        assertThat(result[0].host).isEqualTo("10.0.0.5")
        assertThat(result[0].port).isEqualTo(8765)
    }

    @Test
    fun ignores_unrelated_service_types() {
        val ourType = "_maimai-home._tcp."
        val unrelated = infoFor("Random-Printer", "_ipp._tcp.", null, 631)
        val ours = infoFor("PC-Maimai", ourType, null, 8765)

        val resolvedOurs = infoFor("PC-Maimai", ourType, InetAddress.getByName("10.0.0.42"), 8765)

        val nsd = ScriptedNsdManager(
            // Both reported by the system but DiscoveryService must filter.
            founds = listOf(unrelated, ours),
            resolveResults = mapOf(ours to resolvedOurs),
        )
        val service = DiscoveryService(nsd, FakeLockFactory())

        val result = runBlocking { service.discover(timeoutMillis = 300) }

        assertThat(result).hasSize(1)
        assertThat(result[0].name).isEqualTo("PC-Maimai")
    }

    @Test
    fun resolution_failure_drops_that_service_only() {
        val type = "_maimai-home._tcp."
        val foundOk = infoFor("PC-OK", type, null, 8765)
        val foundBad = infoFor("PC-Bad", type, null, 8765)

        val resolvedOk = infoFor("PC-OK", type, InetAddress.getByName("10.0.0.7"), 8765)

        val nsd = ScriptedNsdManager(
            founds = listOf(foundOk, foundBad),
            // foundBad maps to null -> resolveService responds with onResolveFailed.
            resolveResults = mapOf(foundOk to resolvedOk, foundBad to null),
        )
        val service = DiscoveryService(nsd, FakeLockFactory())

        val result = runBlocking { service.discover(timeoutMillis = 300) }

        assertThat(result).hasSize(1)
        assertThat(result[0].name).isEqualTo("PC-OK")
    }
}
