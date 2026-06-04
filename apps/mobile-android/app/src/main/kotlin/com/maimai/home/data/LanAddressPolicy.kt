package com.maimai.home.data

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * Application-level LAN allowlist for cleartext destinations.
 *
 * Android Network Security Config does not support CIDR / IP-range entries
 * inside `<domain>` (only exact hostnames or full IPs), so the manifest
 * simply enables cleartext globally. This policy enforces the original
 * RFC1918-only intent at the HTTP/WebSocket call sites.
 * Allowed hosts:
 * - `127.0.0.0/8` loopback (developer convenience, agent on same device)
 * - `10.0.0.0/8`
 * - `172.16.0.0/12`
 * - `192.168.0.0/16`
 * - `localhost` literal (resolves to loopback)
 * - `.local` mDNS hostnames (NSD discovery returns these)
 *
 * Public IPs and arbitrary hostnames are rejected with [IllegalArgumentException]
 * to keep the surface "LAN-only client".
 */
internal object LanAddressPolicy {

    fun requireLanHost(host: String) {
        require(isLanHost(host)) {
            "Refusing non-LAN address \"$host\": cleartext is only permitted to RFC1918 / loopback / .local hosts"
        }
    }

    fun isLanHost(host: String): Boolean {
        val trimmed = host.trim().lowercase()
        if (trimmed.isEmpty()) return false
        // localhost literal short-circuits without DNS.
        if (trimmed == "localhost") return true
        // mDNS .local names: trust by intent. NSD on Android resolves these,
        // and `.local` is reserved for link-local multicast DNS (RFC 6762);
        // it cannot legally be a public hostname.
        if (trimmed.endsWith(".local")) return true
        // Strip IPv6 brackets for parsing.
        val unbracketed = trimmed.removePrefix("[").removeSuffix("]")
        // Try as a literal IPv4 / IPv6 first – no DNS needed.
        parseLiteralIp(unbracketed)?.let { return isPrivateInet(it) }
        // Hostnames: resolve and require ALL addresses to be private/loopback.
        // Returning true on partial private resolution is unsafe (DNS rebinding /
        // dual-stack attacks), so we require unanimity.
        return try {
            val addresses = InetAddress.getAllByName(unbracketed)
            addresses.isNotEmpty() && addresses.all { isPrivateInet(it) }
        } catch (_: UnknownHostException) {
            // Unresolvable hostnames are rejected. `.local` mDNS names typically
            // fail JVM resolution off-device; on Android the system resolver
            // handles them and the all-private check still applies.
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun parseLiteralIp(host: String): InetAddress? {
        // Reject anything that isn't clearly a numeric literal so we don't
        // accidentally trigger DNS for hostnames like "10-foo.example.com".
        val isIpv4Literal = host.matches(Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$"))
        val isIpv6Literal = host.contains(':')
        if (!isIpv4Literal && !isIpv6Literal) return null
        return runCatching { InetAddress.getByName(host) }.getOrNull()
    }

    internal fun isPrivateInet(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return true
        if (address.isLinkLocalAddress) return true
        if (address.isAnyLocalAddress) return false
        return when (address) {
            is Inet4Address -> {
                val raw = address.address
                val a = raw[0].toInt() and 0xFF
                val b = raw[1].toInt() and 0xFF
                when {
                    a == 10 -> true                          // 10.0.0.0/8
                    a == 172 && b in 16..31 -> true          // 172.16.0.0/12
                    a == 192 && b == 168 -> true             // 192.168.0.0/16
                    a == 169 && b == 254 -> true             // 169.254.0.0/16 link-local
                    else -> false
                }
            }
            is Inet6Address -> {
                // isSiteLocalAddress (fec0::/10) is superseded but still appears on private LANs.
                // Unique-Local (fc00::/7) is the modern replacement; first byte 0xFC or 0xFD.
                if (address.isSiteLocalAddress) return true
                val first = address.address[0].toInt() and 0xFF
                first == 0xFC || first == 0xFD
            }
            else -> false
        }
    }

    /**
     * Extracts the host portion from an address that may be `host:port`,
     * `http://host:port`, `ws://host`, etc. Returns null if unparseable.
     */
    fun extractHost(address: String): String? {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        return runCatching { URI(withScheme).host }.getOrNull()
    }
}
