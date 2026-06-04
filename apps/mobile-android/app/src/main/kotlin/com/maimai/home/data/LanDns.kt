package com.maimai.home.data

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * OkHttp [Dns] implementation that rejects any lookup whose result is not
 * fully LAN (RFC1918 / loopback / link-local / IPv6 ULA / `.local` mDNS).
 *
 * Prevents the TOCTOU gap between [LanAddressPolicy.requireLanHost] (called at
 * URL-construction time) and OkHttp's own DNS resolution at connect time.
 *
 * This is the *only* DNS path used by [okhttp3.OkHttpClient] for both HTTP
 * calls and WebSocket upgrades, so a non-LAN address resolved at connect
 * time will still throw [UnknownHostException] before any socket opens.
 */
internal class LanDns(private val delegate: Dns = SYSTEM) : Dns {

    @Throws(UnknownHostException::class)
    override fun lookup(hostname: String): List<InetAddress> {
        // localhost short-circuits with no DNS query.
        if (hostname.equals("localhost", ignoreCase = true)) {
            return delegate.lookup(hostname)
        }
        val resolved = try {
            delegate.lookup(hostname)
        } catch (e: UnknownHostException) {
            // Some `.local` mDNS names will not resolve via the JVM resolver
            // off-device. We still trust them per RFC 6762 (mDNS reserved),
            // but we have no IP to return, so propagate the original error.
            throw e
        }
        // Reject the WHOLE lookup if any resolved address is not private.
        // Partial-private results are treated as hostile (DNS rebinding).
        if (resolved.isEmpty() || resolved.any { !LanAddressPolicy.isPrivateInet(it) }) {
            throw UnknownHostException(
                "Refusing non-LAN DNS resolution of \"$hostname\": " +
                    "OkHttp DNS guard requires every resolved address to be private."
            )
        }
        return resolved
    }

    companion object {
        val SYSTEM: Dns = Dns.SYSTEM
    }
}
