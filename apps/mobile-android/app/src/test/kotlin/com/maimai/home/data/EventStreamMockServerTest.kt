package com.maimai.home.data

import com.google.common.truth.Truth.assertThat
import com.maimai.home.data.models.EventEnvelope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Wave 3 task 14: extends Wave 1's EventStream coverage with end-to-end
 * MockWebServer scenarios:
 *
 * - real WebSocket upgrade through MockWebServer
 * - server-pushed text frames are emitted on the events SharedFlow
 * - malformed JSON frames are silently dropped (no crash)
 * - connectionState transitions Connecting -> Connected on upgrade
 *
 * The Wave 1 EventStreamTest already covers scheme normalisation and the
 * reconnect-job-cancel-on-disconnect path; this file does NOT duplicate those
 * cases.
 */
class EventStreamMockServerTest {

    @Test
    fun connectsAndForwardsServerPushedTextFrame() {
        val server = MockWebServer()
        val pushed = """{"type":"audio.state","payload":{"masterVolume":0.42},"timestamp":"2026-01-01T00:00:00Z"}"""

        // The MockWebServer WebSocket listener must be supplied at enqueue
        // time. We use the upgrade-to-websocket helper and capture the
        // server-side WebSocket so we can push a frame after the client
        // connects.
        val serverConnected = CountDownLatch(1)
        val serverSocket = arrayOfNulls<WebSocket>(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    serverSocket[0] = webSocket
                    serverConnected.countDown()
                }
            }),
        )
        server.start()
        try {
            val okHttp = OkHttpClient.Builder()
                .readTimeout(2, TimeUnit.SECONDS)
                .build()
            val baseAddress = server.url("/").toString()
            // Trim the trailing slash so EventStream's `address +
            // "/api/events"` builds a clean ws URL.
            val stream = EventStream(
                okHttpClient = okHttp,
                json = Json { ignoreUnknownKeys = true; explicitNulls = false },
                address = baseAddress.trimEnd('/'),
                onReconnect = {},
            )

            try {
                stream.connect()

                // Wait for the server-side onOpen so we know the upgrade is
                // complete before pushing frames.
                assertTrue(
                    "server-side WebSocket must accept the client upgrade within 2s",
                    serverConnected.await(2, TimeUnit.SECONDS),
                )

                // Push the frame from the server.
                serverSocket[0]!!.send(pushed)

                // Collect the first emission. SharedFlow.replayCache is empty
                // for events (extraBufferCapacity=16 + DROP_OLDEST), so we
                // need a live collector.
                val received = runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(2_000) {
                        stream.events.first()
                    }
                }
                assertTrue(
                    "EventStream must emit the server-pushed frame within 2s",
                    received != null,
                )
                assertEquals("audio.state", received!!.type)
            } finally {
                stream.disconnect()
                serverSocket[0]?.close(1000, "test done")
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun malformedFrame_isSilentlyDropped() {
        val server = MockWebServer()
        val serverConnected = CountDownLatch(1)
        val serverSocket = arrayOfNulls<WebSocket>(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    serverSocket[0] = webSocket
                    serverConnected.countDown()
                }
            }),
        )
        server.start()
        try {
            val okHttp = OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val stream = EventStream(
                okHttpClient = okHttp,
                json = Json { ignoreUnknownKeys = true; explicitNulls = false },
                address = server.url("/").toString().trimEnd('/'),
                onReconnect = {},
            )
            try {
                // Start collecting BEFORE connecting so we don't miss emissions.
                val receivedLatch = CountDownLatch(1)
                val receivedRef = arrayOfNulls<EventEnvelope>(1)
                val collectThread = Thread {
                    runBlocking {
                        kotlinx.coroutines.withTimeoutOrNull(5_000) {
                            stream.events.collect { event ->
                                receivedRef[0] = event
                                receivedLatch.countDown()
                                // Stop after first valid event
                                throw kotlinx.coroutines.CancellationException("got one")
                            }
                        }
                    }
                }
                collectThread.isDaemon = true
                collectThread.start()

                stream.connect()
                assertTrue(serverConnected.await(2, TimeUnit.SECONDS))

                // Push three frames: garbage, valid, garbage. Only the
                // valid one must surface.
                serverSocket[0]!!.send("not json at all")
                serverSocket[0]!!.send("""{"type":"ok","payload":{},"timestamp":"t"}""")
                serverSocket[0]!!.send("{not even close")

                assertTrue(
                    "a valid frame must come through despite garbage neighbours",
                    receivedLatch.await(5, TimeUnit.SECONDS),
                )
                assertEquals("ok", receivedRef[0]!!.type)
            } finally {
                stream.disconnect()
                serverSocket[0]?.close(1000, "test done")
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun connectionState_transitionsToConnected_onUpgrade() {
        val server = MockWebServer()
        val serverConnected = CountDownLatch(1)
        val serverSocket = arrayOfNulls<WebSocket>(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    serverSocket[0] = webSocket
                    serverConnected.countDown()
                }
            }),
        )
        server.start()
        try {
            val okHttp = OkHttpClient.Builder().build()
            val stream = EventStream(
                okHttpClient = okHttp,
                json = Json { ignoreUnknownKeys = true; explicitNulls = false },
                address = server.url("/").toString().trimEnd('/'),
                onReconnect = {},
            )
            try {
                assertEquals(EventStream.ConnectionState.Disconnected, stream.connectionState.value)
                stream.connect()
                assertTrue(serverConnected.await(2, TimeUnit.SECONDS))

                // The client-side onOpen runs on an OkHttp dispatcher thread;
                // poll briefly for the state transition.
                val end = System.currentTimeMillis() + 2_000
                while (
                    System.currentTimeMillis() < end &&
                    stream.connectionState.value != EventStream.ConnectionState.Connected
                ) {
                    Thread.sleep(20)
                }
                assertEquals(EventStream.ConnectionState.Connected, stream.connectionState.value)
            } finally {
                stream.disconnect()
                serverSocket[0]?.close(1000, "test done")
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    @Suppress("UNUSED_PARAMETER")
    fun socketUpgradeFailure_transitionsToReconnecting() {
        // Server replies with a non-101 response - the upgrade fails and
        // EventStream's onFailure schedules a reconnect.
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
        )
        server.start()
        try {
            val okHttp = OkHttpClient.Builder().build()
            val stream = EventStream(
                okHttpClient = okHttp,
                json = Json { ignoreUnknownKeys = true; explicitNulls = false },
                address = server.url("/").toString().trimEnd('/'),
                onReconnect = {},
            )
            try {
                stream.connect()

                // Wait for the failure path to schedule a reconnect.
                val end = System.currentTimeMillis() + 2_000
                while (
                    System.currentTimeMillis() < end &&
                    stream.connectionState.value != EventStream.ConnectionState.Reconnecting
                ) {
                    Thread.sleep(20)
                }
                assertEquals(EventStream.ConnectionState.Reconnecting, stream.connectionState.value)
                // The reconnect job MUST be tracked.
                assertTrue("reconnectJob must be scheduled", stream.reconnectJob != null)
            } finally {
                stream.disconnect()
            }
        } finally {
            server.shutdown()
        }
    }
}
