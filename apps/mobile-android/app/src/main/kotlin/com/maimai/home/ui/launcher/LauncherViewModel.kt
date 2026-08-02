package com.maimai.home.ui.launcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.maimai.home.ServiceLocator
import com.maimai.home.data.AgentClient
import com.maimai.home.data.EventStream
import com.maimai.home.data.models.AgentRequestException
import com.maimai.home.data.models.AgentStatus
import com.maimai.home.data.models.EventEnvelope
import com.maimai.home.data.models.LauncherStatus
import com.maimai.home.ui.common.maimaiViewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.async

data class LauncherUiState(
    val address: String,
    val machineName: String,
    val agentStatus: AgentStatus? = null,
    val launcherStatus: LauncherStatus? = null,
    val isRefreshing: Boolean = false,
    val isActionPending: Boolean = false,
    val errorMessage: String? = null,
) {
    val launcherAvailable: Boolean
        get() = agentStatus?.capabilities?.launcher == true
}

class LauncherViewModel(
    private val address: String,
    private val machineName: String,
    private val agentClient: AgentClient,
    private val eventFlow: Flow<EventEnvelope>,
) : ViewModel() {

    constructor(address: String, machineName: String) : this(
        address = address,
        machineName = machineName,
        agentClient = ServiceLocator.agentClient,
        eventFlow = ServiceLocator.sharedEventStream.events,
    )

    private val _uiState = MutableStateFlow(LauncherUiState(address = address, machineName = machineName))
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    init {
        subscribeToEvents(eventFlow)
    }

    fun start() {
        refresh()
    }

    fun stop() {
        // SharedEventStream is managed by ServiceLocator; nothing to disconnect.
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            supervisorScope {
                val statusRequest = async { runCatching { agentClient.fetchStatus(address) } }
                val launcherRequest = async { runCatching { agentClient.fetchLauncherStatus(address) } }

                val statusResult = statusRequest.await()
                statusResult.onSuccess { status ->
                    _uiState.update { it.copy(agentStatus = status) }
                }

                val launcherResult = launcherRequest.await()
                launcherResult.onSuccess { status ->
                    _uiState.update { it.copy(launcherStatus = status) }
                }

                val error = statusResult.exceptionOrNull() ?: launcherResult.exceptionOrNull()
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = error?.let(::describeError),
                    )
                }
            }
        }
    }

    fun refreshLauncherStatus() {
        viewModelScope.launch {
            runCatching { agentClient.fetchLauncherStatus(address) }
                .onSuccess { status -> _uiState.update { it.copy(launcherStatus = status, errorMessage = null) } }
                .onFailure { error -> _uiState.update { it.copy(errorMessage = describeError(error)) } }
        }
    }

    fun showLauncher() {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionPending = true, errorMessage = null) }
            runCatching { agentClient.showLauncher(address) }
                .onSuccess { status -> _uiState.update { it.copy(launcherStatus = status, isActionPending = false) } }
                .onFailure { error ->
                    _uiState.update { it.copy(isActionPending = false, errorMessage = describeError(error)) }
                }
        }
    }

    fun startLauncherItem(itemId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionPending = true, errorMessage = null) }
            runCatching { agentClient.startLauncherItem(address, itemId) }
                .onSuccess { status -> _uiState.update { it.copy(launcherStatus = status, isActionPending = false) } }
                .onFailure { error ->
                    _uiState.update { it.copy(isActionPending = false, errorMessage = describeError(error)) }
                }
        }
    }

    fun stopLauncherItem() {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionPending = true, errorMessage = null) }
            runCatching { agentClient.stopLauncherItem(address) }
                .onSuccess { status -> _uiState.update { it.copy(launcherStatus = status, isActionPending = false) } }
                .onFailure { error ->
                    _uiState.update { it.copy(isActionPending = false, errorMessage = describeError(error)) }
                }
        }
    }

    private fun subscribeToEvents(events: Flow<EventEnvelope>) {
        viewModelScope.launch {
            events.collect { event ->
                if (isLauncherEvent(event)) {
                    refreshLauncherStatus()
                }
            }
        }
    }

    private fun isLauncherEvent(event: EventEnvelope): Boolean =
        event.type.startsWith("launcher.")

    private fun describeError(error: Throwable): String =
        (error as? AgentRequestException)?.apiError?.message
            ?: error.message?.takeIf { it.isNotBlank() }
            ?: "网络错误"

    override fun onCleared() {
        stop()
    }

    companion object {
        fun factory(address: String, machineName: String): ViewModelProvider.Factory =
            maimaiViewModelFactory { LauncherViewModel(address, machineName) }
    }
}
