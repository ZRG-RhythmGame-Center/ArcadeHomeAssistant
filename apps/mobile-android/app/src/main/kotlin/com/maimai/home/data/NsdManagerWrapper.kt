package com.maimai.home.data

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Thin interface over NsdManager so DiscoveryService can be unit-tested
 * without a real Android NSD stack.
 */
interface NsdManagerWrapper {
    fun discoverServices(
        serviceType: String,
        protocolType: Int,
        listener: NsdManager.DiscoveryListener,
    )

    fun stopServiceDiscovery(listener: NsdManager.DiscoveryListener)

    fun resolveService(
        serviceInfo: NsdServiceInfo,
        listener: NsdManager.ResolveListener,
    )
}

/**
 * Production implementation that delegates to the real NsdManager.
 */
class RealNsdManagerWrapper(private val nsdManager: NsdManager) : NsdManagerWrapper {
    override fun discoverServices(
        serviceType: String,
        protocolType: Int,
        listener: NsdManager.DiscoveryListener,
    ) = nsdManager.discoverServices(serviceType, protocolType, listener)

    override fun stopServiceDiscovery(listener: NsdManager.DiscoveryListener) =
        nsdManager.stopServiceDiscovery(listener)

    override fun resolveService(
        serviceInfo: NsdServiceInfo,
        listener: NsdManager.ResolveListener,
    ) = nsdManager.resolveService(serviceInfo, listener)
}
