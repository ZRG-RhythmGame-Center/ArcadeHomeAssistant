package com.maimai.home.data

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unit tests for EventStream — scheme normalisation and reconnect-job tracking.
 *
 * Scheme tests exercise [normalizedWsBase] directly (extracted as internal top-level).
 * Reconnect-job test uses a hand-rolled fake OkHttpClient that immediately fires
 * onClosed so we can verify the job is cancelled after disconnect().
 */
class EventStreamTest {

    // ── Scheme normalisation ──────────────────────────────────────────────────

    @Test
    fun httpAddressBecomesWs() {
        assertEquals("ws://192.168.1.1:8765", normalizedWsBase("http://192.168.1.1:8765"))
    }

    @Test
    fun httpsAddressBecomesWss() {
        assertEquals("wss://10.0.0.5", normalizedWsBase("https://10.0.0.5"))
    }

    @Test
    fun bareAddressBecomesWs() {
        assertEquals("ws://192.168.1.1:8765", normalizedWsBase("192.168.1.1:8765"))
    }

    @Test
    fun wssAddressPreserved() {
        assertEquals("wss://192.168.0.42", normalizedWsBase("wss://192.168.0.42"))
    }

    @Test
    fun wsAddressPreserved() {
        assertEquals("ws://maimai-host.local", normalizedWsBase("ws://maimai-host.local"))
    }

    // ── LAN allowlist (Wave 1 Gate A C1 follow-up) ───────────────────────────

    @Test
    fun rejectsPublicHostname() {
        try {
            normalizedWsBase("http://example.com:8765")
            org.junit.Assert.fail("expected IllegalArgumentException for non-LAN host")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Refusing non-LAN address"))
        }
    }

    @Test
    fun rejectsPublicIpv4() {
        try {
            normalizedWsBase("http://8.8.8.8:8765")
            org.junit.Assert.fail("expected IllegalArgumentException for public IPv4")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Refusing non-LAN address"))
        }
    }

    @Test
    fun acceptsLoopback() {
        assertEquals("ws://127.0.0.1:8765", normalizedWsBase("127.0.0.1:8765"))
    }

    @Test
    fun acceptsMdnsLocal() {
        assertEquals("ws://maimai-host.local:8765", normalizedWsBase("http://maimai-host.local:8765"))
    }

    @Test
    fun acceptsAllRfc1918Octets() {
        assertEquals("ws://10.1.2.3:8765", normalizedWsBase("10.1.2.3:8765"))
        assertEquals("ws://172.20.0.1:8765", normalizedWsBase("172.20.0.1:8765"))
        assertEquals("ws://192.168.255.1:8765", normalizedWsBase("192.168.255.1:8765"))
    }

    @Test
    fun rejectsBoundary172Outside1631() {
        // 172.15.x.x and 172.32.x.x are NOT in 172.16.0.0/12
        try {
            normalizedWsBase("http://172.15.0.1:8765")
            org.junit.Assert.fail("expected IllegalArgumentException for 172.15.x.x")
        } catch (_: IllegalArgumentException) {}
        try {
            normalizedWsBase("http://172.32.0.1:8765")
            org.junit.Assert.fail("expected IllegalArgumentException for 172.32.x.x")
        } catch (_: IllegalArgumentException) {}
    }

    // ── Reconnect job tracking ────────────────────────────────────────────────

    /**
     * Verifies that calling disconnect() cancels a pending reconnect job.
     *
     * Strategy:
     *  1. Build a fake OkHttpClient whose newWebSocket() immediately fires
     *     onClosed on the listener — this triggers scheduleReconnect().
     *  2. scheduleReconnect() launches a coroutine with a delay; we capture
     *     the internal reconnectJob via the internal accessor.
     *  3. Call disconnect() and assert the job is cancelled.
     */
    @Test
    fun reconnectJobCancelledOnDisconnect() {
        val listenerHolder = arrayOfNulls<WebSocketListener>(1)
        val listenerCaptured = CountDownLatch(1)

        val fakeWebSocket = object : WebSocket {
            override fun request(): Request = Request.Builder().url("ws://localhost/api/events").build()
            override fun queueSize(): Long = 0L
            override fun send(text: String): Boolean = true
            override fun send(bytes: okio.ByteString): Boolean = true
            override fun close(code: Int, reason: String?): Boolean = true
            override fun cancel() {}
        }

        val fakeClient = object : OkHttpClient() {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                listenerHolder[0] = listener
                listenerCaptured.countDown()
                return fakeWebSocket
            }
        }

        val stream = EventStream(
            okHttpClient = fakeClient,
            json = Json,
            address = "http://192.168.1.1:8765",
            onReconnect = {},
        )

        // connect() → openSocket() → fakeClient.newWebSocket() captures listener
        stream.connect()
        assertTrue("listener captured within 1s", listenerCaptured.await(1, TimeUnit.SECONDS))

        // Trigger onClosed → scheduleReconnect() → reconnectJob is set
        listenerHolder[0]!!.onClosed(fakeWebSocket, 1000, "test")

        // Give the coroutine scheduler a moment to set reconnectJob
        Thread.sleep(100)

        // Capture the reconnect job BEFORE disconnect() so production code
        // is free to null the field on disconnect; we still hold the
        // reference and can verify cancellation occurred.
        val capturedJob = stream.reconnectJob
            ?: error("reconnect job must be scheduled before disconnect()")

        // disconnect() must cancel the pending reconnect job
        stream.disconnect()

        assertTrue(
            "reconnectJob captured pre-disconnect must be cancelled after disconnect()",
            capturedJob.isCancelled,
        )
    }
}