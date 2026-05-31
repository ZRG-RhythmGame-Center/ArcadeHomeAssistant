package com.maimai.home.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.maimai.home.ServiceLocator
import com.maimai.home.data.AgentClient
import com.maimai.home.data.AgentPreferences
import com.maimai.home.data.DiscoveredService
import com.maimai.home.data.DiscoveryService
import com.maimai.home.data.models.AgentStatus
import com.maimai.home.data.models.AgentRequestException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectionUiState(
    val address: String = AgentPreferences.DEFAULT_AGENT_ADDRESS,
    val isTesting: Boolean = false,
    val isScanning: Boolean = false,
    val discovered: List<DiscoveredService> = emptyList(),
    val connectedStatus: AgentStatus? = null,
    val errorMessage: String? = null,
)

/**
 * Primary constructor takes all dependencies explicitly — used by tests and
 * the companion [Factory] (which delegates to [ServiceLocator] for production).
 *
 * Wave 4 task 20/21: injectable seam + useDiscoveredService fix.
 */
class ConnectionViewModel(
    private val preferences: AgentPreferences,
    private val agentClient: AgentClient,
    private val discoveryService: DiscoveryService,
) : ViewModel() {

    /** Production no-arg secondary constructor — delegates to ServiceLocator. */
    constructor() : this(
        preferences = ServiceLocator.preferences,
        agentClient = ServiceLocator.agentClient,
        discoveryService = ServiceLocator.discoveryService,
    )

    private val _uiState = MutableStateFlow(ConnectionUiState())
    val uiState: StateFlow<ConnectionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.agentAddressFlow.collect { address ->
                _uiState.update { it.copy(address = address) }
            }
        }
    }

    fun updateAddress(address: String) {
        _uiState.update { it.copy(address = address, errorMessage = null) }
    }

    fun testConnection() {
        viewModelScope.launch {
            val address = uiState.value.address.trim()
            _uiState.update { it.copy(isTesting = true, errorMessage = null, connectedStatus = null) }
            runCatching { agentClient.fetchStatus(address) }
                .onSuccess { status ->
                    preferences.saveAgentAddress(address)
                    _uiState.update { it.copy(isTesting = false, connectedStatus = status) }
                }
                .onFailure { error ->
                    val message = (error as? AgentRequestException)?.apiError?.message ?: "网络错误"
                    _uiState.update { it.copy(isTesting = false, errorMessage = message) }
                }
        }
    }

    fun scanLan() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, errorMessage = null, discovered = emptyList()) }
            runCatching { discoveryService.discover() }
                .onSuccess { list ->
                    _uiState.update { it.copy(isScanning = false, discovered = list) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isScanning = false, errorMessage = error.message ?: "网络错误") }
                }
        }
    }

    /**
     * Task 21 fix: silently verify the discovered service by calling
     * [AgentClient.fetchStatus]. On success, populate [ConnectionUiState.connectedStatus]
     * so the screen can navigate automatically (closes R1#5/R2).
     */
    fun useDiscoveredService(service: DiscoveredService) {
        viewModelScope.launch {
            preferences.saveAgentAddress(service.address)
            _uiState.update { it.copy(address = service.address, errorMessage = null, connectedStatus = null) }
            runCatching { agentClient.fetchStatus(service.address) }
                .onSuccess { status ->
                    _uiState.update { it.copy(connectedStatus = status) }
                }
                .onFailure { error ->
                    val message = (error as? AgentRequestException)?.apiError?.message ?: "网络错误"
                    _uiState.update { it.copy(errorMessage = message) }
                }
        }
    }

    fun clearConnectedStatus() {
        _uiState.update { it.copy(connectedStatus = null) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ConnectionViewModel() as T
        }
    }
}
