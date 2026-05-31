package com.maimai.home.ui.files

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.maimai.home.ServiceLocator
import com.maimai.home.data.AgentClient
import com.maimai.home.data.EventStream
import com.maimai.home.data.FileListingResult
import com.maimai.home.data.models.AgentRequestException
import com.maimai.home.data.models.EventEnvelope
import com.maimai.home.data.models.FileEntry
import com.maimai.home.data.models.FileRoot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FilesUiState(
    val address: String,
    val machineName: String,
    val roots: List<FileRoot> = emptyList(),
    val selectedRoot: FileRoot? = null,
    val path: String = "",
    val listing: FileListingResult? = null,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
) {
    /**
     * Task 25: true iff a root is selected AND it is not read-only.
     * UI uses this to gate mutation buttons (upload FAB, rename, move, delete).
     */
    val canMutate: Boolean get() = selectedRoot != null && !selectedRoot.readOnly
}

/**
 * Primary constructor takes all dependencies explicitly — used by tests.
 * Production code uses the secondary constructor that wires up ServiceLocator.
 *
 * Wave 4 task 24/25: injectable seam + canMutate + WS files.changed subscription.
 *
 * @param eventFlow  SharedFlow of [EventEnvelope] from the WebSocket.
 */
class FilesViewModel(
    application: Application,
    private val address: String,
    private val machineName: String,
    private val agentClient: AgentClient,
    private val eventFlow: Flow<EventEnvelope>,
) : AndroidViewModel(application) {

    /** Production secondary constructor — delegates to ServiceLocator. */
    constructor(
        application: Application,
        address: String,
        machineName: String,
    ) : this(
        application = application,
        address = address,
        machineName = machineName,
        agentClient = ServiceLocator.agentClient,
        eventFlow = kotlinx.coroutines.flow.emptyFlow(),
    )

    private val json: Json = ServiceLocator.json

    /**
     * Production EventStream wired by [start]; null in tests (eventFlow is
     * injected directly).
     */
    private var eventStream: EventStream? = null
    private var eventJob: Job? = null

    private val _uiState = MutableStateFlow(FilesUiState(address = address, machineName = machineName))
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    init {
        loadRoots()
        subscribeToWsEvents()
    }

    /**
     * Task 25 + Gate G F2 B8: subscribe to file.* events emitted by the Windows
     * agent (`file.created`, `file.deleted`, `file.renamed`, `file.moved`).
     * The agent does NOT emit a generic `files.changed` event — the original
     * subscription string was a Wave 4 oversight. Debounce rapid bursts (500 ms),
     * then refresh the listing only if rootId + path match the current view.
     *
     * For renames/moves we also refresh when the event references the current
     * directory as `fromPath` (the source disappeared) or `toPath` (the target
     * gained a child).
     */
    private fun subscribeToWsEvents() {
        viewModelScope.launch {
            eventFlow
                .filter { event ->
                    event.type == "file.created" ||
                        event.type == "file.deleted" ||
                        event.type == "file.renamed" ||
                        event.type == "file.moved"
                }
                .debounce(500L)
                .collect { event -> handleFileEvent(event) }
        }
    }

    /**
     * Refresh the current listing if the event affects the current view.
     * Shared by the constructor-injected eventFlow path (tests) and the
     * production [start] path that wires a real EventStream.
     */
    private fun handleFileEvent(event: EventEnvelope) {
        val payload = runCatching { event.payload.jsonObject }.getOrNull() ?: return
        val rootId = runCatching { payload["rootId"]?.jsonPrimitive?.content }.getOrNull() ?: return
        // Try the common path-bearing fields. file.created/deleted use `path`.
        // file.renamed/moved use `fromPath` and `toPath`.
        val candidatePaths = listOfNotNull(
            runCatching { payload["path"]?.jsonPrimitive?.content }.getOrNull(),
            runCatching { payload["fromPath"]?.jsonPrimitive?.content }.getOrNull(),
            runCatching { payload["toPath"]?.jsonPrimitive?.content }.getOrNull(),
        )

        val current = _uiState.value
        val selectedId = current.selectedRoot?.id ?: return
        if (selectedId != rootId) return

        // Refresh if the current directory matches OR is the parent of any of
        // the affected paths (i.e. the affected entry lives directly inside).
        val currentDir = current.path
        val refreshNeeded = candidatePaths.any { affected ->
            affected == currentDir || parentDirOf(affected) == currentDir
        }
        if (refreshNeeded) {
            loadListing(current.selectedRoot!!, currentDir)
        }
    }

    /**
     * Returns the parent directory of an in-root path. Empty string is the
     * root level. Mirrors the agent's path semantics (forward slashes).
     */
    private fun parentDirOf(path: String): String {
        val normalized = path.trim('/').trim()
        if (normalized.isEmpty()) return ""
        val idx = normalized.lastIndexOf('/')
        return if (idx < 0) "" else normalized.substring(0, idx)
    }

    /**
     * Called by the production screen via DisposableEffect. Creates a real
     * [EventStream] and forwards its events into the WS subscription set up
     * in [subscribeToWsEvents]. The screen calls [stop] on dispose.
     *
     * Tests do NOT call this; they pass `eventFlow` directly via the primary
     * constructor.
     */
    fun start() {
        if (eventStream != null) return
        val stream = EventStream(ServiceLocator.okHttpClient, json, address) {
            // On reconnect, refresh the listing.
            refresh()
        }
        eventStream = stream
        // Forward the real EventStream's events into the same flow the WS
        // subscription consumes. We use a simple relay coroutine that emits
        // every received event into a MutableSharedFlow that subscribeToWsEvents
        // already collects from -- but since the existing subscription was wired
        // to the constructor-injected eventFlow, we instead launch a parallel
        // collector here that mirrors subscribeToWsEvents's logic against the
        // real stream. This keeps tests using the constructor flow unaffected.
        eventJob = viewModelScope.launch {
            stream.events
                .filter { event ->
                    event.type == "file.created" ||
                        event.type == "file.deleted" ||
                        event.type == "file.renamed" ||
                        event.type == "file.moved"
                }
                .debounce(500L)
                .collect { event -> handleFileEvent(event) }
        }
        stream.connect()
    }

    fun stop() {
        eventJob?.cancel()
        eventJob = null
        eventStream?.disconnect()
        eventStream = null
    }

    override fun onCleared() {
        stop()
    }

    fun loadRoots() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            runCatching { agentClient.fetchFileRoots(address) }
                .onSuccess { roots ->
                    val selected = _uiState.value.selectedRoot?.let { current -> roots.firstOrNull { it.id == current.id } } ?: roots.firstOrNull()
                    _uiState.update { it.copy(roots = roots, selectedRoot = selected, isRefreshing = false) }
                    if (selected != null) loadListing(selected, _uiState.value.path)
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isRefreshing = false, errorMessage = (error as? AgentRequestException)?.apiError?.message ?: "网络错误") }
                }
        }
    }

    fun selectRoot(root: FileRoot) {
        _uiState.update { it.copy(selectedRoot = root, path = "") }
        loadListing(root, "")
    }

    fun openFolder(entry: FileEntry) {
        val nextPath = listOf(_uiState.value.path.takeIf { it.isNotBlank() }, entry.name).filterNotNull().joinToString("/")
        _uiState.update { it.copy(path = nextPath) }
        _uiState.value.selectedRoot?.let { loadListing(it, nextPath) }
    }

    fun navigateToPath(path: String) {
        _uiState.update { it.copy(path = path) }
        _uiState.value.selectedRoot?.let { loadListing(it, path) }
    }

    fun refresh() {
        val root = _uiState.value.selectedRoot ?: return
        loadListing(root, _uiState.value.path)
    }

    fun download(entry: FileEntry, onDone: (String) -> Unit, onError: (String) -> Unit) {
        val root = _uiState.value.selectedRoot ?: return
        val path = currentEntryPath(entry)
        viewModelScope.launch {
            runCatching {
                val directory = getApplication<Application>().getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                    ?: throw IllegalStateException("下载目录不可用")
                val target = File(directory, entry.name)
                agentClient.downloadFile(address, root.id, path, target)
                target.absolutePath
            }.onSuccess(onDone)
                .onFailure { error -> onError((error as? AgentRequestException)?.apiError?.message ?: "网络错误") }
        }
    }

    fun upload(uri: Uri, onDone: (String) -> Unit, onError: (String) -> Unit) {
        val root = _uiState.value.selectedRoot ?: return
        viewModelScope.launch {
            runCatching {
                agentClient.uploadFile(address, root.id, _uiState.value.path, getApplication<Application>().contentResolver, uri)
            }.onSuccess {
                refresh()
                onDone("上传成功")
            }.onFailure { error -> onError((error as? AgentRequestException)?.apiError?.message ?: "网络错误") }
        }
    }

    fun delete(entry: FileEntry, onDone: (String) -> Unit, onError: (String) -> Unit) {
        val root = _uiState.value.selectedRoot ?: return
        viewModelScope.launch {
            runCatching { agentClient.deleteFile(address, root.id, currentEntryPath(entry)) }
                .onSuccess { refresh(); onDone("已删除 ${entry.name}") }
                .onFailure { error -> onError((error as? AgentRequestException)?.apiError?.message ?: "网络错误") }
        }
    }

    fun rename(entry: FileEntry, newName: String, onDone: (String) -> Unit, onError: (String) -> Unit) {
        val root = _uiState.value.selectedRoot ?: return
        viewModelScope.launch {
            runCatching { agentClient.renameFile(address, root.id, currentEntryPath(entry), newName) }
                .onSuccess { refresh(); onDone("已重命名为 $newName") }
                .onFailure { error -> onError((error as? AgentRequestException)?.apiError?.message ?: "网络错误") }
        }
    }

    /**
     * I19 fix: after a move, refresh both the SOURCE directory (the entry
     * disappeared) and the TARGET directory (the entry appeared). The current
     * view is the source; if the target differs, we additionally pre-warm it
     * by triggering a fetch so a subsequent navigateToPath shows fresh data.
     */
    fun move(entry: FileEntry, newPath: String, onDone: (String) -> Unit, onError: (String) -> Unit) {
        val root = _uiState.value.selectedRoot ?: return
        val sourcePath = _uiState.value.path
        viewModelScope.launch {
            runCatching { agentClient.moveFile(address, root.id, currentEntryPath(entry), newPath) }
                .onSuccess {
                    // Refresh the current (source) directory listing.
                    refresh()
                    // Pre-warm the target directory cache by fetching it.
                    // The result is discarded because the user has not
                    // navigated yet; the next navigateToPath will trigger a
                    // fresh fetch so this is best-effort warming.
                    val targetDir = parentDirOf(newPath)
                    if (targetDir != sourcePath) {
                        runCatching {
                            agentClient.fetchFiles(address, root.id, targetDir)
                        }
                    }
                    onDone("已移动到 $newPath")
                }
                .onFailure { error -> onError((error as? AgentRequestException)?.apiError?.message ?: "网络错误") }
        }
    }

    fun breadcrumbSegments(): List<String> = _uiState.value.path.split('/').filter { it.isNotBlank() }

    fun currentEntryPath(entry: FileEntry): String = listOf(_uiState.value.path.takeIf { it.isNotBlank() }, entry.name).filterNotNull().joinToString("/")

    private fun loadListing(root: FileRoot, path: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            runCatching { agentClient.fetchFiles(address, root.id, path) }
                .onSuccess { listing -> _uiState.update { it.copy(listing = listing, isRefreshing = false) } }
                .onFailure { error -> _uiState.update { it.copy(isRefreshing = false, errorMessage = (error as? AgentRequestException)?.apiError?.message ?: "网络错误") } }
        }
    }

    companion object {
        fun factory(application: Application, address: String, machineName: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = FilesViewModel(application, address, machineName) as T
        }

        fun humanSize(bytes: Long?): String {
            val value = bytes ?: return ""
            if (value < 1024) return "$value B"
            if (value < 1024 * 1024) return String.format(Locale.US, "%.1f KB", value / 1024.0)
            if (value < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024.0))
            return String.format(Locale.US, "%.2f GB", value / (1024.0 * 1024.0 * 1024.0))
        }

        fun formatDate(value: String): String {
            val date = runCatching { Date.from(java.time.Instant.parse(value)) }.getOrNull() ?: return value
            return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(date)
        }
    }
}
