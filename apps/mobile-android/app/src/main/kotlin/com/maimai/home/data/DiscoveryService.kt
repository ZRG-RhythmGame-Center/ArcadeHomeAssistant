package com.maimai.home.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class DiscoveredService(
    val name: String,
    val host: String,
    val port: Int,
) {
    val address: String get() = "$host:$port"
}

class DiscoveryService(
    private val nsdManager: NsdManagerWrapper,
    private val lockFactory: MulticastLockFactory,
) {
    /**
     * Secondary constructor for production use — takes Context and builds
     * the real NsdManager wrapper and WifiManager-backed lock factory.
     */
    constructor(context: Context) : this(
        nsdManager = RealNsdManagerWrapper(
            context.getSystemService(Context.NSD_SERVICE) as NsdManager
        ),
        lockFactory = RealMulticastLockFactory(
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        ),
    )

    suspend fun discover(timeoutMillis: Long = 6_000L): List<DiscoveredService> =
        withContext(Dispatchers.IO) {
            // Acquire MulticastLock before NSD discovery so multicast packets
            // are not filtered by the Wi-Fi driver on Android.
            val lock = lockFactory.createMulticastLock("maimai-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
            try {
                val found = linkedMapOf<String, DiscoveredService>()
                val started = CompletableDeferred<Unit>()
                // Track in-flight resolution jobs so we can cancel any that
                // never received a callback when the outer timeout elapses.
                val pendingResolutions = mutableListOf<Job>()
                lateinit var listener: NsdManager.DiscoveryListener

                coroutineScope {
                    listener = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(serviceType: String) {
                            started.complete(Unit)
                        }

                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                            started.complete(Unit)
                        }

                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

                        override fun onDiscoveryStopped(serviceType: String) {}

                        override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

                        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                            if (serviceInfo.serviceType != "_maimai-home._tcp.") return
                            // Launch resolution on IO dispatcher — no runBlocking needed.
                            val job = launch(Dispatchers.IO) {
                                val resolved = suspendResolve(serviceInfo)
                                if (resolved != null) {
                                    synchronized(found) { found[resolved.address] = resolved }
                                }
                            }
                            synchronized(pendingResolutions) { pendingResolutions.add(job) }
                        }
                    }

                    nsdManager.discoverServices(
                        "_maimai-home._tcp",
                        NsdManager.PROTOCOL_DNS_SD,
                        listener,
                    )
                    started.await()
                    delay(timeoutMillis)
                    runCatching { nsdManager.stopServiceDiscovery(listener) }

                    // Cancel any still-pending resolutions so we never hang
                    // on a service whose ResolveListener never fires. The
                    // outer coroutineScope would otherwise wait forever for
                    // child coroutines stuck in suspendCancellableCoroutine.
                    val snapshot = synchronized(pendingResolutions) { pendingResolutions.toList() }
                    snapshot.forEach { it.cancel() }
                }

                found.values.sortedBy { it.name }
            } finally {
                runCatching { lock.release() }
            }
        }

    /**
     * Resolves a single NsdServiceInfo using suspendCancellableCoroutine.
     * No runBlocking — the coroutine suspends until the NSD callback fires.
     */
    private suspend fun suspendResolve(serviceInfo: NsdServiceInfo): DiscoveredService? =
        suspendCancellableCoroutine { cont ->
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress ?: resolved.host?.hostName
                    if (cont.isActive) {
                        cont.resume(
                            if (host == null) null
                            else DiscoveredService(
                                name = resolved.serviceName ?: host,
                                host = host,
                                port = resolved.port,
                            )
                        )
                    }
                }
            }
            try {
                nsdManager.resolveService(serviceInfo, listener)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
}
