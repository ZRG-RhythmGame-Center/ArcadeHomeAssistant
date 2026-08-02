package com.maimai.home.data

import com.maimai.home.data.models.EventEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * A shared event stream that maintains a single WebSocket connection to the
 * agent and fans events out to all subscribers.  ViewModels subscribe to
 * [events] and [connectionState] instead of creating individual
 * [EventStream] instances, avoiding redundant WebSocket connections when
 * multiple tabs are active.
 *
 * Call [connect] when the app establishes a connection to an agent;
 * [disconnect] tears down the underlying socket.
 */
class SharedEventStream(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    private val _events = MutableSharedFlow<EventEnvelope>(extraBufferCapacity = 64)
    val events: SharedFlow<EventEnvelope> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow(EventStream.ConnectionState.Disconnected)
    val connectionState: StateFlow<EventStream.ConnectionState> = _connectionState.asStateFlow()

    private var eventStream: EventStream? = null
    private var currentAddress: String? = null

    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectorJob: Job? = null
    private var stateJob: Job? = null

    /**
     * Connect to the agent at [address].  If a stream for the same address is
     * already active, this is a no-op.  If a stream for a different address is
     * active, it is torn down first.
     */
    fun connect(address: String) {
        if (currentAddress == address && eventStream != null) return
        disconnectInternal()
        currentAddress = address
        val stream = EventStream(okHttpClient, json, address) {
            // On reconnect, each ViewModel re-fetches via the events flow.
            // No global refresh here.
        }
        eventStream = stream
        collectorJob = collectorScope.launch {
            stream.events.collect { _events.emit(it) }
        }
        stateJob = collectorScope.launch {
            stream.connectionState.collect { _connectionState.value = it }
        }
        stream.connect()
    }

    fun disconnect() {
        disconnectInternal()
    }

    private fun disconnectInternal() {
        collectorJob?.cancel()
        collectorJob = null
        stateJob?.cancel()
        stateJob = null
        eventStream?.disconnect()
        eventStream = null
        currentAddress = null
        _connectionState.value = EventStream.ConnectionState.Disconnected
    }
}
