package com.maimai.home.data

import com.maimai.home.data.models.EventEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class EventStream(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val address: String,
    private val onReconnect: suspend () -> Unit,
) {
    enum class ConnectionState { Connecting, Connected, Reconnecting, Disconnected }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<EventEnvelope>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)

    private var webSocket: WebSocket? = null
    private var closed = false
    private var hasConnectedBefore = false
    private var reconnectDelayMs = 1_000L
    internal var reconnectJob: Job? = null

    val events: SharedFlow<EventEnvelope> = _events.asSharedFlow()
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    fun connect() {
        closed = false
        openSocket(isReconnect = hasConnectedBefore)
    }

    fun disconnect() {
        closed = true
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
        scope.cancel()
    }

    private fun openSocket(isReconnect: Boolean) {
        if (closed) return
        _connectionState.value = if (isReconnect) ConnectionState.Reconnecting else ConnectionState.Connecting
        val request = Request.Builder()
            .url("${normalizedWsBase(address)}/api/events")
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectDelayMs = 1_000L
                _connectionState.value = ConnectionState.Connected
                if (hasConnectedBefore) {
                    scope.launch { onReconnect() }
                }
                hasConnectedBefore = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Agent sends {"type":"ping"} as an application-layer heartbeat every
                // 30s and treats any client frame as liveness (updates LastPongAt).
                // If the ping payload cannot be decoded as an EventEnvelope we reply
                // with a pong text frame so the Agent keeps the session alive.
                if (text.length < 32 && text.contains("\"ping\"")) {
                    webSocket.send("""{"type":"pong"}""")
                    return
                }
                runCatching { json.decodeFromString<EventEnvelope>(text) }
                    .getOrNull()
                    ?.let { scope.launch { _events.emit(it) } }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (closed) return
        _connectionState.value = ConnectionState.Reconnecting
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            try {
                delay(reconnectDelayMs)
                reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000L)
                openSocket(isReconnect = true)
            } catch (_: CancellationException) {
                // graceful disconnect
            }
        }
    }

}


/**
 * Normalises an address string to a WebSocket base URL.
 * - wss:// and ws:// are preserved as-is.
 * - https:// becomes wss://
 * - http:// becomes ws://
 * - bare host:port gets ws:// prepended.
 */
internal fun normalizedWsBase(address: String): String {
    val trimmed = address.trim()
    val wsUrl = when {
        trimmed.startsWith("wss://") -> trimmed
        trimmed.startsWith("ws://") -> trimmed
        trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
        trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
        else -> "ws://$trimmed"
    }
    val host = LanAddressPolicy.extractHost(wsUrl)
        ?: throw IllegalArgumentException("Refusing unparseable address \"$address\"")
    LanAddressPolicy.requireLanHost(host)
    return wsUrl
}
