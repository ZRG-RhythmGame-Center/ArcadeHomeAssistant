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
        eventFlow = ServiceLocator.sharedEventStream.events,
    )

    private val _uiState = MutableStateFlow(AdminUiState(address = address, machineName = machineName))
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

    private var isDirty: Boolean = false

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
                .onSuccess { settings ->
                    isDirty = false
                    _uiState.update { it.copy(settings = settings, errorMessage = null) }
                }
                .onFailure { error -> _uiState.update { it.copy(errorMessage = describeError(error)) } }
        }
    }

    fun saveSettings(request: AgentSettingsUpdateRequest) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null, saveSuccess = false) }
            runCatching { agentClient.updateSettings(address, request) }
                .onSuccess { settings ->
                    isDirty = false
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

    /**
     * Convenience: build an update request from the current snapshot and
     * call [saveSettings]. UI edits mutate the snapshot in place via the
     * update* methods below; this packages the result for PUT /api/settings.
     */
    fun saveCurrentSettings() {
        val current = _uiState.value.settings ?: return
        saveSettings(
            AgentSettingsUpdateRequest(
                autoStartEnabled = current.autoStartEnabled,
                launcher = current.launcher,
                fileRoots = current.fileRoots,
                remoteShutdown = current.remoteShutdown,
            ),
        )
    }

    fun updateAutoStartEnabled(value: Boolean) {
        isDirty = true
        _uiState.update { it.copy(settings = it.settings?.copy(autoStartEnabled = value)) }
    }

    fun updateRemoteShutdownEnabled(value: Boolean) {
        isDirty = true
        _uiState.update {
            it.copy(
                settings = it.settings?.copy(
                    remoteShutdown = it.settings.remoteShutdown.copy(enabled = value),
                ),
            )
        }
    }

    fun updateLauncherShowOnAgentStart(value: Boolean) {
        isDirty = true
        updateLauncher { it.copy(showOnAgentStart = value) }
    }

    fun updateLauncherShowDelaySeconds(value: String) {
        isDirty = true
        updateLauncher { l -> l.copy(showDelaySeconds = value.toIntOrNull() ?: l.showDelaySeconds) }
    }

    fun updateLauncherCanvasWidth(value: String) {
        isDirty = true
        updateLauncher { l -> l.copy(canvasWidth = value.toIntOrNull() ?: l.canvasWidth) }
    }

    fun updateLauncherCanvasHeight(value: String) {
        isDirty = true
        updateLauncher { l -> l.copy(canvasHeight = value.toIntOrNull() ?: l.canvasHeight) }
    }

    fun updateLauncherBackgroundImagePath(value: String?) {
        isDirty = true
        updateLauncher { it.copy(backgroundImagePath = value) }
    }

    fun updateLauncherNavigateLeftKey(value: String) {
        isDirty = true
        updateLauncher { it.copy(navigateLeftKey = value) }
    }

    fun updateLauncherNavigateRightKey(value: String) {
        isDirty = true
        updateLauncher { it.copy(navigateRightKey = value) }
    }

    fun updateLauncherConfirmKey(value: String) {
        isDirty = true
        updateLauncher { it.copy(confirmKey = value) }
    }

    fun updateLauncherStopKey(value: String) {
        isDirty = true
        updateLauncher { it.copy(stopKey = value) }
    }

    private inline fun updateLauncher(transform: (com.maimai.home.data.models.LauncherSettingsDto) -> com.maimai.home.data.models.LauncherSettingsDto) {
        _uiState.update { state ->
            val s = state.settings ?: return@update state
            state.copy(settings = s.copy(launcher = transform(s.launcher)))
        }
    }

    fun startLauncherItem(itemId: String) {
        viewModelScope.launch {
            runCatching { agentClient.startLauncherItem(address, itemId) }
                .onFailure { error -> _uiState.update { it.copy(errorMessage = describeError(error)) } }
        }
    }

    fun addLauncherItem() {
        isDirty = true
        val current = _uiState.value.settings ?: return
        val newItem = com.maimai.home.data.models.LauncherItemSettingsDto(
            id = java.util.UUID.randomUUID().toString().take(8),
            name = "新启动项",
            title = "新启动项",
            note = null,
            iconPath = null,
            commandLine = "",
            workingDirectory = null,
            stopCommandLine = "",
            stopWorkingDirectory = null,
            key = "",
            order = (current.launcher.items.maxOfOrNull { it.order } ?: 0) + 1,
            enabled = true,
        )
        _uiState.update {
            it.copy(settings = current.copy(launcher = current.launcher.copy(items = current.launcher.items + newItem)))
        }
    }

    fun updateLauncherItem(itemId: String, field: String, value: Any?) {
        isDirty = true
        _uiState.update { state ->
            val s = state.settings ?: return@update state
            val items = s.launcher.items.map { item ->
                if (item.id != itemId) return@map item
                when (field) {
                    "name" -> item.copy(name = value as String)
                    "commandLine" -> item.copy(commandLine = value as String)
                    "workingDirectory" -> item.copy(workingDirectory = value as String?)
                    "stopCommandLine" -> item.copy(stopCommandLine = value as String)
                    "stopWorkingDirectory" -> item.copy(stopWorkingDirectory = value as String?)
                    "enabled" -> item.copy(enabled = value as Boolean)
                    "key" -> item.copy(key = value as String)
                    "note" -> item.copy(note = value as String?)
                    "iconPath" -> item.copy(iconPath = value as String?)
                    else -> item
                }
            }
            state.copy(settings = s.copy(launcher = s.launcher.copy(items = items)))
        }
    }

    fun removeLauncherItem(itemId: String) {
        isDirty = true
        _uiState.update { state ->
            val s = state.settings ?: return@update state
            state.copy(settings = s.copy(launcher = s.launcher.copy(items = s.launcher.items.filterNot { it.id == itemId })))
        }
    }

    fun addFileRoot() {
        isDirty = true
        val current = _uiState.value.settings ?: return
        val newRoot = com.maimai.home.data.models.FileRootSettingsDto(
            id = java.util.UUID.randomUUID().toString().take(8),
            name = "新目录",
            path = "",
            readOnly = false,
        )
        _uiState.update {
            it.copy(settings = current.copy(fileRoots = current.fileRoots + newRoot))
        }
    }

    fun updateFileRoot(rootId: String, field: String, value: Any?) {
        isDirty = true
        _uiState.update { state ->
            val s = state.settings ?: return@update state
            val roots = s.fileRoots.map { root ->
                if (root.id != rootId) return@map root
                when (field) {
                    "name" -> root.copy(name = value as String)
                    "path" -> root.copy(path = value as String)
                    "readOnly" -> root.copy(readOnly = value as Boolean)
                    else -> root
                }
            }
            state.copy(settings = s.copy(fileRoots = roots))
        }
    }

    fun removeFileRoot(rootId: String) {
        isDirty = true
        _uiState.update { state ->
            val s = state.settings ?: return@update state
            state.copy(settings = s.copy(fileRoots = s.fileRoots.filterNot { it.id == rootId }))
        }
    }

    private fun subscribeToEvents(events: Flow<EventEnvelope>) {
        viewModelScope.launch {
            events.collect { event ->
                if (event.type == "settings.updated") {
                    if (isDirty) {
                        _uiState.update { it.copy(errorMessage = "设置已被其他设备更新，保存后会覆盖远端修改。") }
                    } else {
                        loadSettings()
                    }
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
