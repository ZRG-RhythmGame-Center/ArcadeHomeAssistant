package com.maimai.home.ui.power

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.maimai.home.ServiceLocator
import com.maimai.home.data.AgentClient
import com.maimai.home.data.EventStream
import com.maimai.home.data.models.AgentRequestException
import com.maimai.home.data.models.AgentStatus
import com.maimai.home.data.models.EventEnvelope
import com.maimai.home.data.models.RemoteShutdownStatus
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

data class PowerUiState(
    val address: String,
    val machineName: String,
    val agentStatus: AgentStatus? = null,
    val shutdownStatus: RemoteShutdownStatus? = null,
    val controlToken: String = "",
    val confirmVisible: Boolean = false,
    val isRefreshing: Boolean = false,
    val isExecuting: Boolean = false,
    val errorMessage: String? = null,
) {
    val remoteShutdownAvailable: Boolean
        get() = agentStatus?.capabilities?.remoteShutdown == true &&
            shutdownStatus?.available == true
}

class PowerViewModel(
    private val address: String,
    private val machineName: String,
    private val agentClient: AgentClient,
    private val eventFlow: Flow<EventEnvelope>,
) : ViewModel() {

    constructor(address: String, machineName: String) : this(
        address = address,
        machineName = machineName,
        agentClient = ServiceLocator.agentClient,
        eventFlow = emptyFlow(),
    )

    private val _uiState = MutableStateFlow(PowerUiState(address = address, machineName = machineName))
    val uiState: StateFlow<PowerUiState> = _uiState.asStateFlow()

    private var eventStream: EventStream? = null
    private var eventJob: Job? = null

    init {
        subscribeToEvents(eventFlow)
    }

    fun start() {
        refresh()
        if (eventStream != null) return
        val stream = EventStream(ServiceLocator.okHttpClient, ServiceLocator.json, address) { refresh() }
        eventStream = stream
        eventJob = viewModelScope.launch {
            stream.events.collect { event ->
                if (isPowerShutdownEvent(event)) {
                    refreshShutdownStatus()
                }
            }
        }
        stream.connect()
    }

    fun stop() {
        eventJob?.cancel()
        eventJob = null
        eventStream?.disconnect()
        eventStream = null
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            supervisorScope {
                val statusRequest = async { agentClient.fetchStatus(address) }
                val shutdownRequest = async { agentClient.fetchRemoteShutdownStatus(address) }

                val statusResult = runCatching { statusRequest.await() }
                statusResult.onSuccess { status ->
                    _uiState.update { it.copy(agentStatus = status) }
                }

                val shutdownResult = runCatching { shutdownRequest.await() }
                shutdownResult.onSuccess { status ->
                    _uiState.update { it.copy(shutdownStatus = status) }
                }

                val error = statusResult.exceptionOrNull() ?: shutdownResult.exceptionOrNull()
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = error?.let(::describeError),
                    )
                }
            }
        }
    }

    fun refreshShutdownStatus() {
        viewModelScope.launch {
            runCatching { agentClient.fetchRemoteShutdownStatus(address) }
                .onSuccess { status -> _uiState.update { it.copy(shutdownStatus = status, errorMessage = null) } }
                .onFailure { error -> _uiState.update { it.copy(errorMessage = describeError(error)) } }
        }
    }

    fun updateControlToken(value: String) {
        _uiState.update { it.copy(controlToken = value, errorMessage = null) }
    }

    fun showConfirm() {
        _uiState.update { it.copy(confirmVisible = true, errorMessage = null) }
    }

    fun hideConfirm() {
        _uiState.update { it.copy(confirmVisible = false) }
    }

    fun executeShutdown() {
        val token = uiState.value.controlToken.trim()
        if (token.isBlank()) {
            _uiState.update { it.copy(errorMessage = "请输入控制令牌") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isExecuting = true, errorMessage = null) }
            runCatching { agentClient.executeRemoteShutdown(address, token) }
                .onSuccess { status ->
                    _uiState.update {
                        it.copy(
                            shutdownStatus = status,
                            confirmVisible = false,
                            isExecuting = false,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isExecuting = false, errorMessage = describeError(error))
                    }
                }
        }
    }

    private fun subscribeToEvents(events: Flow<EventEnvelope>) {
        viewModelScope.launch {
            events.collect { event ->
                if (isPowerShutdownEvent(event)) {
                    refreshShutdownStatus()
                }
            }
        }
    }

    private fun isPowerShutdownEvent(event: EventEnvelope): Boolean =
        event.type.startsWith("power.shutdown.")

    private fun describeError(error: Throwable): String =
        (error as? AgentRequestException)?.apiError?.message
            ?: error.message?.takeIf { it.isNotBlank() }
            ?: "网络错误"

    override fun onCleared() {
        stop()
    }

    companion object {
        fun factory(address: String, machineName: String): ViewModelProvider.Factory =
            maimaiViewModelFactory { PowerViewModel(address, machineName) }
    }
}
