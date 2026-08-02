package com.maimai.home.ui.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.maimai.home.ServiceLocator
import com.maimai.home.data.AgentClient
import com.maimai.home.data.EventStream
import com.maimai.home.data.models.AgentRequestException
import com.maimai.home.data.models.AudioDevice
import com.maimai.home.data.models.AudioState
import com.maimai.home.data.models.EventEnvelope
import com.maimai.home.ui.common.maimaiViewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.decodeFromJsonElement

data class AudioUiState(
    val machineName: String,
    val address: String,
    val connectionText: String = "连接中",
    val audioState: AudioState? = null,
    val devices: List<AudioDevice> = emptyList(),
    val isRefreshing: Boolean = false,
    /** True while a setVolume request is in flight. */
    val isVolumeBusy: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Primary constructor takes all dependencies explicitly — used by tests.
 * Production code uses the secondary constructor that wires up a real [EventStream].
 *
 * @param eventFlow       SharedFlow of [EventEnvelope] from the WebSocket.
 * @param connectionStateFlow StateFlow of [EventStream.ConnectionState].
 */
class AudioViewModel(
    private val address: String,
    private val machineName: String,
    private val agentClient: AgentClient,
    private val eventFlow: Flow<EventEnvelope>,
    private val connectionStateFlow: StateFlow<EventStream.ConnectionState>,
) : ViewModel() {

    /** Production secondary constructor — subscribes to the shared event stream. */
    constructor(address: String, machineName: String) : this(
        address = address,
        machineName = machineName,
        agentClient = ServiceLocator.agentClient,
        eventFlow = ServiceLocator.sharedEventStream.events,
        connectionStateFlow = ServiceLocator.sharedEventStream.connectionState,
    )

    private val json = ServiceLocator.json

    /** True while the user is actively dragging the volume slider. */
    private var isDragging = false
    /** Last WS audio.state received while dragging — applied on drag end. */
    private var pendingDragState: AudioState? = null

    private val _uiState = MutableStateFlow(AudioUiState(machineName = machineName, address = address))
    val uiState: StateFlow<AudioUiState> = _uiState.asStateFlow()

    private fun describeError(error: Throwable): String =
        (error as? AgentRequestException)?.apiError?.message
            ?: error.message?.takeIf { it.isNotBlank() }
            ?: "网络错误"

    private fun describeConnectionState(state: EventStream.ConnectionState): String = when (state) {
        EventStream.ConnectionState.Connected -> "已连接"
        EventStream.ConnectionState.Connecting -> "连接中"
        EventStream.ConnectionState.Reconnecting -> "重连中"
        EventStream.ConnectionState.Disconnected -> "已断开"
    }

    init {
        // Subscribe to the injected flows immediately (test path).
        // Production path calls start() which creates the real EventStream.
        subscribeToFlows(eventFlow, connectionStateFlow)
    }

    private fun subscribeToFlows(
        evFlow: Flow<EventEnvelope>,
        connFlow: StateFlow<EventStream.ConnectionState>,
    ) {
        viewModelScope.launch {
            connFlow.collect { state ->
                _uiState.update { it.copy(connectionText = describeConnectionState(state)) }
            }
        }
        viewModelScope.launch {
            evFlow.collect { event ->
                handleAudioEvent(event)
            }
        }
    }

    /** Called by the production screen via DisposableEffect. */
    fun start() {
        refresh()
    }

    private fun handleAudioEvent(event: EventEnvelope) {
        when (event.type) {
            "audio.state" -> {
                val state = runCatching {
                    json.decodeFromJsonElement<AudioState>(event.payload)
                }.getOrNull() ?: return
                if (isDragging) {
                    // Gate: store for later, don't update slider.
                    pendingDragState = state
                } else {
                    _uiState.update { it.copy(audioState = state, errorMessage = null) }
                }
            }
            "audio.device.changed" -> refreshDevices()
        }
    }

    fun stop() {
        // SharedEventStream is managed by ServiceLocator; nothing to disconnect here.
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }

            supervisorScope {
                val stateRequest = async { agentClient.fetchAudioState(address) }
                val devicesRequest = async { agentClient.fetchAudioDevices(address) }

                val stateResult = runCatching { stateRequest.await() }
                stateResult.onSuccess { state ->
                    _uiState.update { it.copy(audioState = state, errorMessage = null) }
                }

                val devicesResult = runCatching { devicesRequest.await() }
                devicesResult.onSuccess { devices ->
                    _uiState.update { it.copy(devices = devices, errorMessage = null) }
                }

                val error = stateResult.exceptionOrNull() ?: devicesResult.exceptionOrNull()
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = error?.let(::describeError),
                    )
                }
            }
        }
    }

    fun refreshDevices() {
        viewModelScope.launch {
            runCatching { agentClient.fetchAudioDevices(address) }
                .onSuccess { devices -> _uiState.update { it.copy(devices = devices) } }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(errorMessage = describeError(error))
                    }
                }
        }
    }

    fun setVolume(percent: Float) {
        viewModelScope.launch {
            _uiState.update { it.copy(isVolumeBusy = true) }
            runCatching { agentClient.setVolume(address, percent.toDouble() / 100.0) }
                .onSuccess { state -> _uiState.update { it.copy(audioState = state, errorMessage = null, isVolumeBusy = false) } }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isVolumeBusy = false,
                            errorMessage = describeError(error),
                        )
                    }
                }
        }
    }

    fun setMuted(muted: Boolean) {
        viewModelScope.launch {
            runCatching { agentClient.setMute(address, muted) }
                .onSuccess { state -> _uiState.update { it.copy(audioState = state, errorMessage = null) } }
                .onFailure { error -> _uiState.update { it.copy(errorMessage = describeError(error)) } }
        }
    }

    fun switchDevice(deviceId: String) {
        viewModelScope.launch {
            runCatching {
                val devices = agentClient.switchDevice(address, deviceId)
                val state = agentClient.fetchAudioState(address)
                state to devices
            }.onSuccess { (state, devices) ->
                _uiState.update { it.copy(audioState = state, devices = devices, errorMessage = null) }
            }.onFailure { error ->
                _uiState.update { it.copy(errorMessage = describeError(error)) }
            }
        }
    }

    /**
     * While dragging, incoming WS audio.state events are buffered, not applied.
     */
    fun onVolumeDragStart() {
        isDragging = true
        pendingDragState = null
    }

    /**
     * Applies any buffered WS audio.state that arrived during the drag.
     */
    fun onVolumeDragEnd() {
        isDragging = false
        pendingDragState?.let { state ->
            _uiState.update { it.copy(audioState = state, errorMessage = null) }
            pendingDragState = null
        }
    }

    override fun onCleared() {
        stop()
    }

    companion object {
        fun factory(address: String, machineName: String): ViewModelProvider.Factory =
            maimaiViewModelFactory { AudioViewModel(address, machineName) }
    }
}
