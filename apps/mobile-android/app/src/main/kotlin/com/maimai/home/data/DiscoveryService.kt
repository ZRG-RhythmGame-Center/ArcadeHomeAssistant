package com.maimai.home.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

class DiscoveryService internal constructor(
    private val nsdManager: NsdManagerWrapper,
    private val lockFactory: MulticastLockFactory,
) {
    constructor(context: Context) : this(
        nsdManager = RealNsdManagerWrapper(
            context.getSystemService(Context.NSD_SERVICE) as NsdManager
        ),
        lockFactory = RealMulticastLockFactory(
            context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        ),
    )

    suspend fun discover(timeoutMillis: Long = 6_000L): List<DiscoveredService> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, DiscoveredService>()
        val started = CompletableDeferred<Unit>()
        lateinit var listener: NsdManager.DiscoveryListener

        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                started.complete(Unit)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                started.complete(Unit)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            }

            override fun onDiscoveryStopped(serviceType: String) {
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != "_maimai-home._tcp.") return
                resolveService(serviceInfo)?.let { resolved ->
                    found[resolved.address] = resolved
                }
            }
        }

        nsdManager.discoverServices("_maimai-home._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        started.await()
        delay(timeoutMillis)
        runCatching { nsdManager.stopServiceDiscovery(listener) }
        found.values.sortedBy { it.name }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo): DiscoveredService? {
        val result = CompletableDeferred<DiscoveredService?>()
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                result.complete(null)
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = resolved.host?.hostAddress ?: resolved.host?.hostName
                if (host == null) {
                    result.complete(null)
                    return
                }
                result.complete(
                    DiscoveredService(
                        name = resolved.serviceName ?: host,
                        host = host,
                        port = resolved.port,
                    ),
                )
            }
        }

        return try {
            suspendResolve(serviceInfo, callback)
        } catch (_: Exception) {
            null
        }
    }

    private fun suspendResolve(
        serviceInfo: NsdServiceInfo,
        callback: NsdManager.ResolveListener,
    ): DiscoveredService? = kotlinx.coroutines.runBlocking {
        suspendCancellableCoroutine { cont ->
            val wrapped = object : NsdManager.ResolveListener by callback {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    callback.onResolveFailed(serviceInfo, errorCode)
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    callback.onServiceResolved(resolved)
                    val host = resolved.host?.hostAddress ?: resolved.host?.hostName
                    if (cont.isActive) {
                        cont.resume(
                            if (host == null) null else DiscoveredService(
                                name = resolved.serviceName ?: host,
                                host = host,
                                port = resolved.port,
                            ),
                        )
                    }
                }
            }

            nsdManager.resolveService(serviceInfo, wrapped)
        }
    }
}
