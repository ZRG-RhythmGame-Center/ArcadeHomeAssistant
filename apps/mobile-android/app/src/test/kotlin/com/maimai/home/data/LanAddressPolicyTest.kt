package com.maimai.home.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for [LanAddressPolicy]'s RFC1918 enforcement.
 */
class LanAddressPolicyTest {

    // ── isLanHost: positive cases ────────────────────────────────────────────

    @Test fun acceptsLoopbackIpv4() = assertTrue(LanAddressPolicy.isLanHost("127.0.0.1"))
    @Test fun acceptsLoopbackHigh()  = assertTrue(LanAddressPolicy.isLanHost("127.255.255.254"))
    @Test fun acceptsLocalhostLiteral() = assertTrue(LanAddressPolicy.isLanHost("localhost"))
    @Test fun acceptsLocalhostUppercase() = assertTrue(LanAddressPolicy.isLanHost("LOCALHOST"))
    @Test fun acceptsMdnsLocal() = assertTrue(LanAddressPolicy.isLanHost("maimai-host.local"))
    @Test fun acceptsTen()          = assertTrue(LanAddressPolicy.isLanHost("10.0.0.1"))
    @Test fun acceptsTenHigh()      = assertTrue(LanAddressPolicy.isLanHost("10.255.255.254"))
    @Test fun acceptsOneSeventyTwoLow()   = assertTrue(LanAddressPolicy.isLanHost("172.16.0.1"))
    @Test fun acceptsOneSeventyTwoHigh()  = assertTrue(LanAddressPolicy.isLanHost("172.31.255.254"))
    @Test fun acceptsOneNinetyTwoOneSixtyEight() = assertTrue(LanAddressPolicy.isLanHost("192.168.1.1"))
    @Test fun acceptsIpv6Loopback() = assertTrue(LanAddressPolicy.isLanHost("::1"))
    @Test fun acceptsIpv6LinkLocal() = assertTrue(LanAddressPolicy.isLanHost("fe80::1"))

    // ── isLanHost: negative cases ────────────────────────────────────────────

    @Test fun rejectsPublicDns()    = assertFalse(LanAddressPolicy.isLanHost("example.com"))
    @Test fun rejectsGoogleDns()    = assertFalse(LanAddressPolicy.isLanHost("8.8.8.8"))
    @Test fun rejectsCloudflare()   = assertFalse(LanAddressPolicy.isLanHost("1.1.1.1"))
    @Test fun rejects172Below16()   = assertFalse(LanAddressPolicy.isLanHost("172.15.0.1"))
    @Test fun rejects172At32()      = assertFalse(LanAddressPolicy.isLanHost("172.32.0.1"))
    @Test fun rejectsBlank()        = assertFalse(LanAddressPolicy.isLanHost(""))
    @Test fun rejectsMalformedIpv4() = assertFalse(LanAddressPolicy.isLanHost("999.999.999.999"))
    @Test fun rejectsTextThatLooksLikeIp() = assertFalse(LanAddressPolicy.isLanHost("not.an.ip.really"))

    // ── requireLanHost throws ────────────────────────────────────────────────

    @Test
    fun requireThrowsOnPublic() {
        try {
            LanAddressPolicy.requireLanHost("evil.example.com")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Refusing non-LAN address"))
        }
    }

    @Test
    fun requireSilentOnLan() {
        // No exception
        LanAddressPolicy.requireLanHost("192.168.1.1")
        LanAddressPolicy.requireLanHost("localhost")
    }

    // ── extractHost ──────────────────────────────────────────────────────────

    @Test fun extractsBareHost() = assertTrue(LanAddressPolicy.extractHost("192.168.1.1") == "192.168.1.1")
    @Test fun extractsHostFromHttp() = assertTrue(LanAddressPolicy.extractHost("http://10.0.0.1:8765") == "10.0.0.1")
    @Test fun extractsHostFromHttps() = assertTrue(LanAddressPolicy.extractHost("https://maimai-host.local") == "maimai-host.local")
    @Test fun extractsHostFromWs() = assertTrue(LanAddressPolicy.extractHost("ws://172.16.5.5:8765") == "172.16.5.5")
    @Test fun extractsHostWithPort() = assertTrue(LanAddressPolicy.extractHost("192.168.1.1:8765") == "192.168.1.1")
    @Test fun extractHostNullOnBlank() = assertNull(LanAddressPolicy.extractHost(""))
}
