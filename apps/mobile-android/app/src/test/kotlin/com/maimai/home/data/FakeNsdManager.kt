package com.maimai.home.data

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Open fake NsdManagerWrapper for unit tests.
 *
 * Default implementations immediately call the "started" callback so
 * discover() can proceed without a real NSD stack. Subclasses can override
 * individual methods to simulate specific behaviours (e.g. a hanging
 * resolveService that never calls back).
 */
open class FakeNsdManager : NsdManagerWrapper {

    override fun discoverServices(
        serviceType: String,
        protocolType: Int,
        listener: NsdManager.DiscoveryListener,
    ) {
        // Immediately signal that discovery started so discover() can proceed.
        listener.onDiscoveryStarted(serviceType)
    }

    override fun stopServiceDiscovery(listener: NsdManager.DiscoveryListener) {
        listener.onDiscoveryStopped("_maimai-home._tcp")
    }

    override fun resolveService(
        serviceInfo: NsdServiceInfo,
        listener: NsdManager.ResolveListener,
    ) {
        // Default: immediately fail resolution (no real NSD stack in unit tests).
        listener.onResolveFailed(serviceInfo, NsdManager.FAILURE_INTERNAL_ERROR)
    }
}
