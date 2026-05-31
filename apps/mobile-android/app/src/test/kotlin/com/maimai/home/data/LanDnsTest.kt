package com.maimai.home.data

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Unit tests for [LanDns]. Verifies the OkHttp DNS guard rejects any
 * resolution that contains even one non-LAN address (defense against
 * DNS rebinding) while letting clean LAN resolutions through.
 *
 * Uses fake [Dns] delegates rather than network. Closes the TOCTOU gap
 * raised by Oracle round 5.
 */
class LanDnsTest {

    private fun fakeDns(vararg addresses: InetAddress): Dns = object : Dns {
        private val list = addresses.toList()
        override fun lookup(hostname: String): List<InetAddress> = list
    }

    private fun throwingDns(): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            throw UnknownHostException(hostname)
    }

    private fun ip(literal: String): InetAddress = InetAddress.getByName(literal)

    @Test
    fun acceptsAllPrivateResolution() {
        val dns = LanDns(fakeDns(ip("192.168.1.1"), ip("10.0.0.1")))
        val result = dns.lookup("agent.lan")
        assertEquals(2, result.size)
    }

    @Test
    fun acceptsLoopbackResolution() {
        val dns = LanDns(fakeDns(ip("127.0.0.1")))
        val result = dns.lookup("anything.local")
        assertEquals(1, result.size)
    }

    @Test
    fun rejectsAnyPublicAddressInResult() {
        // Single hostile address mixed into LAN result must reject the WHOLE lookup.
        val dns = LanDns(fakeDns(ip("192.168.1.1"), ip("8.8.8.8")))
        try {
            dns.lookup("hostile.example.com")
            fail("expected UnknownHostException")
        } catch (e: UnknownHostException) {
            assertTrue(
                e.message!!.contains("Refusing non-LAN DNS resolution"),
            )
        }
    }

    @Test
    fun rejectsAllPublicResolution() {
        val dns = LanDns(fakeDns(ip("8.8.8.8")))
        try {
            dns.lookup("public.example.com")
            fail("expected UnknownHostException")
        } catch (e: UnknownHostException) {
            assertTrue(e.message!!.contains("Refusing non-LAN DNS resolution"))
        }
    }

    @Test
    fun rejectsEmptyResolution() {
        val dns = LanDns(fakeDns())
        try {
            dns.lookup("nothing.example.com")
            fail("expected UnknownHostException")
        } catch (e: UnknownHostException) {
            assertTrue(e.message!!.contains("Refusing non-LAN DNS resolution"))
        }
    }

    @Test
    fun localhostShortCircuitsToDelegate() {
        // No filter should be applied to localhost - delegate determines result.
        val dns = LanDns(fakeDns(ip("127.0.0.1")))
        val result = dns.lookup("localhost")
        assertEquals(1, result.size)
        assertEquals("127.0.0.1", result[0].hostAddress)
    }

    @Test
    fun delegateUnknownHostPropagates() {
        val dns = LanDns(throwingDns())
        try {
            dns.lookup("does-not-exist.local")
            fail("expected UnknownHostException")
        } catch (_: UnknownHostException) {
            // expected
        }
    }
}
