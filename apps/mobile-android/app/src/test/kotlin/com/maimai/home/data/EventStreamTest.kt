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
        assertEquals("wss://example.com", normalizedWsBase("https://example.com"))
    }

    @Test
    fun bareAddressBecomesWs() {
        assertEquals("ws://192.168.1.1:8765", normalizedWsBase("192.168.1.1:8765"))
    }

    @Test
    fun wssAddressPreserved() {
        assertEquals("wss://secure.example.com", normalizedWsBase("wss://secure.example.com"))
    }

    @Test
    fun wsAddressPreserved() {
        assertEquals("ws://plain.example.com", normalizedWsBase("ws://plain.example.com"))
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

        // disconnect() must cancel the pending reconnect job
        stream.disconnect()

        // The reconnect job should now be cancelled
        val job = stream.reconnectJob
        assertTrue(
            "reconnectJob should be cancelled after disconnect()",
            job == null || job.isCancelled
        )
    }
}