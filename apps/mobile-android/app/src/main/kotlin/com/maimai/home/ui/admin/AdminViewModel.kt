package com.maimai.home.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.maimai.home.ServiceLocator
import com.maimai.home.data.AgentClient
import com.maimai.home.data.EventStream
import com.maimai.home.data.models.AgentRequestException
import com.maimai.home.data.models.AgentSettingsSnapshot
import com.maimai.home.data.models.AgentSettingsUpdateRequest
import com.maimai.home.data.models.AgentStatus
import com.maimai.home.data.models.EventEnvelope
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

data class AdminUiState(
    val address: String,
    val machineName: String,
    val agentStatus: AgentStatus? = null,
    val settings: AgentSettingsSnapshot? = null,
    val isRefreshing: Boolean = false,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val errorMessage: String? = null,
) {
    val settingsAvailable: Boolean
        get() = agentStatus?.capabilities?.settingsManagement == true
}

class AdminViewModel(
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

    private val _uiState = MutableStateFlow(AdminUiState(address = address, machineName = machineName))
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

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
                if (event.type == "settings.updated") {
                    loadSettings()
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
                val statusRequest = async { runCatching { agentClient.fetchStatus(address) } }
                val settingsRequest = async { runCatching { agentClient.fetchSettings(address) } }

                val statusResult = statusRequest.await()
                statusResult.onSuccess { status -> _uiState.update { it.copy(agentStatus = status) } }

                val settingsResult = settingsRequest.await()
                settingsResult.onSuccess { settings ->
                    _uiState.update { it.copy(settings = settings) }
                }

                val error = statusResult.exceptionOrNull() ?: settingsResult.exceptionOrNull()
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = error?.let(::describeError),
                    )
                }
            }
        }
    }

    fun loadSettings() {
        viewModelScope.launch {
            runCatching { agentClient.fetchSettings(address) }
                .onSuccess { settings -> _uiState.update { it.copy(settings = settings, errorMessage = null) } }
                .onFailure { error -> _uiState.update { it.copy(errorMessage = describeError(error)) } }
        }
    }

    fun saveSettings(request: AgentSettingsUpdateRequest) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null, saveSuccess = false) }
            runCatching { agentClient.updateSettings(address, request) }
                .onSuccess { settings ->
                    _uiState.update {
                        it.copy(
                            settings = settings,
                            isSaving = false,
                            saveSuccess = true,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isSaving = false, saveSuccess = false, errorMessage = describeError(error))
                    }
                }
        }
    }

    fun startLauncherItem(itemId: String) {
        viewModelScope.launch {
            runCatching { agentClient.startLauncherItem(address, itemId) }
                .onFailure { error -> _uiState.update { it.copy(errorMessage = describeError(error)) } }
        }
    }

    private fun subscribeToEvents(events: Flow<EventEnvelope>) {
        viewModelScope.launch {
            events.collect { event ->
                if (event.type == "settings.updated") {
                    loadSettings()
                }
            }
        }
    }

    private fun describeError(error: Throwable): String =
        (error as? AgentRequestException)?.apiError?.message
            ?: error.message?.takeIf { it.isNotBlank() }
            ?: "网络错误"

    override fun onCleared() {
        stop()
    }

    companion object {
        fun factory(address: String, machineName: String): ViewModelProvider.Factory =
            maimaiViewModelFactory { AdminViewModel(address, machineName) }
    }
}
