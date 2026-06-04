package com.maimai.home.ui.files

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.maimai.home.MainDispatcherRule
import com.maimai.home.data.AgentClient
import com.maimai.home.data.FileListingResult
import com.maimai.home.data.models.AgentRequestException
import com.maimai.home.data.models.ApiError
import com.maimai.home.data.models.EventEnvelope
import com.maimai.home.data.models.FileEntry
import com.maimai.home.data.models.FileRoot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * FilesViewModel unit tests for root mutability, file operations, and
 * WebSocket-driven listing refresh.
 *
 * Uses JUnit5 (Jupiter) + MockK. No Robolectric needed; Application is mocked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var agentClient: AgentClient
    private lateinit var fakeEvents: MutableSharedFlow<EventEnvelope>
    private lateinit var application: Application
    private lateinit var vm: FilesViewModel

    private val address = "192.168.1.10:8765"
    private val machineName = "TestPC"

    private val writableRoot = FileRoot(id = "root1", name = "Documents", readOnly = false)
    private val readOnlyRoot = FileRoot(id = "root2", name = "System", readOnly = true)

    private val sampleEntries = listOf(
        FileEntry(name = "file.txt", kind = "file", size = 1024L, modified = "2026-01-01T00:00:00Z"),
        FileEntry(name = "subdir", kind = "dir", size = null, modified = "2026-01-01T00:00:00Z"),
    )
    private val sampleListing = FileListingResult(entries = sampleEntries, total = 2, truncated = false, limit = 200)
    private val emptyListing = FileListingResult(entries = emptyList(), total = 0, truncated = false, limit = 200)

    /** Creates a fresh VM with the given root list. Resets mock stubs. */
    private fun makeVm(roots: List<FileRoot> = listOf(writableRoot, readOnlyRoot)): FilesViewModel {
        coEvery { agentClient.fetchFileRoots(address) } returns roots
        coEvery { agentClient.fetchFiles(address, any(), any(), any(), any()) } returns sampleListing
        return FilesViewModel(
            application = application,
            address = address,
            machineName = machineName,
            agentClient = agentClient,
            eventFlow = fakeEvents,
        )
    }

    @BeforeEach
    fun setUp() {
        agentClient = mockk(relaxed = true)
        fakeEvents = MutableSharedFlow(extraBufferCapacity = 16)
        application = mockk(relaxed = true)

        // Return a real temp dir so download tests can write a file
        val tempDir = kotlin.io.path.createTempDirectory("test-downloads").toFile()
        every { application.getExternalFilesDir(any()) } returns tempDir
        every { application.contentResolver } returns mockk(relaxed = true)

        coEvery { agentClient.fetchFileRoots(address) } returns listOf(writableRoot, readOnlyRoot)
        coEvery { agentClient.fetchFiles(address, any(), any(), any(), any()) } returns sampleListing

        vm = FilesViewModel(
            application = application,
            address = address,
            machineName = machineName,
            agentClient = agentClient,
            eventFlow = fakeEvents,
        )
    }

    // ── loadRoots ─────────────────────────────────────────────────────────────

    @Test
    fun loadRoots_success_populatesRootsAndSelectsFirst() = runTest {
        advanceUntilIdle() // let init's loadRoots() complete

        val state = vm.uiState.value
        assertThat(state.roots).containsExactly(writableRoot, readOnlyRoot).inOrder()
        assertThat(state.selectedRoot).isEqualTo(writableRoot)
        assertThat(state.isRefreshing).isFalse()
        assertThat(state.errorMessage).isNull()
    }

    @Test
    fun loadRoots_failure_setsErrorMessage() = runTest {
        coEvery { agentClient.fetchFileRoots(address) } throws
            AgentRequestException(ApiError(ApiError.Kind.Network, "网络错误"))

        vm.loadRoots()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.errorMessage).isEqualTo("网络错误")
        assertThat(state.isRefreshing).isFalse()
    }

    @Test
    fun loadRoots_preservesSelectedRootIfStillPresent() = runTest {
        advanceUntilIdle() // init selects writableRoot
        vm.selectRoot(readOnlyRoot)
        advanceUntilIdle()

        vm.loadRoots()
        advanceUntilIdle()

        assertThat(vm.uiState.value.selectedRoot).isEqualTo(readOnlyRoot)
    }

    // ── selectRoot ────────────────────────────────────────────────────────────

    @Test
    fun selectRoot_updatesSelectedRootAndResetsPath() = runTest {
        advanceUntilIdle()

        vm.selectRoot(readOnlyRoot)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.selectedRoot).isEqualTo(readOnlyRoot)
        assertThat(state.path).isEmpty()
    }

    // ── openFolder ────────────────────────────────────────────────────────────

    @Test
    fun openFolder_appendsToPath() = runTest {
        advanceUntilIdle()

        val dir = FileEntry(name = "subdir", kind = "dir", modified = "2026-01-01T00:00:00Z")
        vm.openFolder(dir)
        advanceUntilIdle()

        assertThat(vm.uiState.value.path).isEqualTo("subdir")
    }

    @Test
    fun openFolder_nestedPath_appendsCorrectly() = runTest {
        advanceUntilIdle()

        vm.openFolder(FileEntry(name = "a", kind = "dir", modified = "2026-01-01T00:00:00Z"))
        advanceUntilIdle()
        vm.openFolder(FileEntry(name = "b", kind = "dir", modified = "2026-01-01T00:00:00Z"))
        advanceUntilIdle()

        assertThat(vm.uiState.value.path).isEqualTo("a/b")
    }

    // ── navigateToPath ────────────────────────────────────────────────────────

    @Test
    fun navigateToPath_setsPathAndLoadsListing() = runTest {
        advanceUntilIdle()

        vm.navigateToPath("docs/reports")
        advanceUntilIdle()

        assertThat(vm.uiState.value.path).isEqualTo("docs/reports")
        coVerify { agentClient.fetchFiles(address, writableRoot.id, "docs/reports", any(), any()) }
    }

    // ── refresh ───────────────────────────────────────────────────────────────

    @Test
    fun refresh_reloadsCurrentListing() = runTest {
        advanceUntilIdle() // init = 1 call

        vm.refresh()
        advanceUntilIdle()

        coVerify(atLeast = 2) { agentClient.fetchFiles(address, writableRoot.id, "", any(), any()) }
    }

    // ── canMutate ─────────────────────────────────────────────────────────────

    @Test
    fun canMutate_trueWhenRootIsWritable() = runTest {
        advanceUntilIdle() // init selects writableRoot

        assertThat(vm.uiState.value.canMutate).isTrue()
    }

    @Test
    fun canMutate_falseWhenRootIsReadOnly() = runTest {
        advanceUntilIdle()

        vm.selectRoot(readOnlyRoot)
        advanceUntilIdle()

        assertThat(vm.uiState.value.canMutate).isFalse()
    }

    @Test
    fun canMutate_falseWhenNoRootSelected() = runTest {
        // Fresh VM with empty roots — selectedRoot stays null
        val freshVm = makeVm(roots = emptyList())
        advanceUntilIdle()

        assertThat(freshVm.uiState.value.selectedRoot).isNull()
        assertThat(freshVm.uiState.value.canMutate).isFalse()
    }

    // ── Defense-in-depth: ViewModel mutation guards ───────────────────────────

    @Test
    fun delete_onReadOnlyRoot_callsOnErrorWithoutHittingAgent() = runTest {
        advanceUntilIdle()
        vm.selectRoot(readOnlyRoot)
        advanceUntilIdle()

        var errorMessage: String? = null
        var doneCalled = false
        vm.delete(
            entry = FileEntry(name = "x.txt", kind = "file", size = 0L, modified = ""),
            onDone = { doneCalled = true },
            onError = { errorMessage = it },
        )
        advanceUntilIdle()

        assertThat(errorMessage).isEqualTo("该根目录为只读，不允许修改")
        assertThat(doneCalled).isFalse()
        coVerify(exactly = 0) { agentClient.deleteFile(any(), any(), any()) }
    }

    @Test
    fun rename_onReadOnlyRoot_callsOnErrorWithoutHittingAgent() = runTest {
        advanceUntilIdle()
        vm.selectRoot(readOnlyRoot)
        advanceUntilIdle()

        var errorMessage: String? = null
        vm.rename(
            entry = FileEntry(name = "x.txt", kind = "file", size = 0L, modified = ""),
            newName = "y.txt",
            onDone = {},
            onError = { errorMessage = it },
        )
        advanceUntilIdle()

        assertThat(errorMessage).isEqualTo("该根目录为只读，不允许修改")
        coVerify(exactly = 0) { agentClient.renameFile(any(), any(), any(), any()) }
    }

    @Test
    fun move_onReadOnlyRoot_callsOnErrorWithoutHittingAgent() = runTest {
        advanceUntilIdle()
        vm.selectRoot(readOnlyRoot)
        advanceUntilIdle()

        var errorMessage: String? = null
        vm.move(
            entry = FileEntry(name = "x.txt", kind = "file", size = 0L, modified = ""),
            newPath = "/elsewhere/x.txt",
            onDone = {},
            onError = { errorMessage = it },
        )
        advanceUntilIdle()

        assertThat(errorMessage).isEqualTo("该根目录为只读，不允许修改")
        coVerify(exactly = 0) { agentClient.moveFile(any(), any(), any(), any()) }
    }

    @Test
    fun delete_withNoRoot_callsOnErrorWithoutHittingAgent() = runTest {
        val freshVm = makeVm(roots = emptyList())
        advanceUntilIdle()

        var errorMessage: String? = null
        freshVm.delete(
            entry = FileEntry(name = "x.txt", kind = "file", size = 0L, modified = ""),
            onDone = {},
            onError = { errorMessage = it },
        )
        advanceUntilIdle()

        assertThat(errorMessage).isEqualTo("未选择根目录")
        coVerify(exactly = 0) { agentClient.deleteFile(any(), any(), any()) }
    }

    @Test
    fun upload_onReadOnlyRoot_callsOnErrorWithoutHittingAgent() = runTest {
        advanceUntilIdle()
        vm.selectRoot(readOnlyRoot)
        advanceUntilIdle()

        var errorMessage: String? = null
        var doneCalled = false
        vm.upload(
            uri = mockk<android.net.Uri>(relaxed = true),
            onDone = { doneCalled = true },
            onError = { errorMessage = it },
        )
        advanceUntilIdle()

        assertThat(errorMessage).isEqualTo("该根目录为只读，不允许修改")
        assertThat(doneCalled).isFalse()
        coVerify(exactly = 0) { agentClient.uploadFile(any(), any(), any(), any<android.content.ContentResolver>(), any<android.net.Uri>()) }
    }

    @Test
    fun upload_withNoRoot_callsOnErrorWithoutHittingAgent() = runTest {
        val freshVm = makeVm(roots = emptyList())
        advanceUntilIdle()

        var errorMessage: String? = null
        freshVm.upload(
            uri = mockk<android.net.Uri>(relaxed = true),
            onDone = {},
            onError = { errorMessage = it },
        )
        advanceUntilIdle()

        assertThat(errorMessage).isEqualTo("未选择根目录")
        coVerify(exactly = 0) { agentClient.uploadFile(any(), any(), any(), any<android.content.ContentResolver>(), any<android.net.Uri>()) }
    }

    // ── download ──────────────────────────────────────────────────────────────

    @Test
    fun download_success_callsOnDone() = runTest {
        advanceUntilIdle()

        var doneMsg: String? = null
        var errorMsg: String? = null
        val entry = FileEntry(name = "file.txt", kind = "file", size = 100L, modified = "2026-01-01T00:00:00Z")

        vm.download(entry, { doneMsg = it }, { errorMsg = it })
        advanceUntilIdle()

        assertThat(errorMsg).isNull()
        assertThat(doneMsg).isNotNull()
    }

    @Test
    fun download_failure_callsOnError() = runTest {
        advanceUntilIdle()

        coEvery { agentClient.downloadFile(any(), any(), any(), any()) } throws
            AgentRequestException(ApiError(ApiError.Kind.NotFound, "文件不存在"))

        var errorMsg: String? = null
        val entry = FileEntry(name = "missing.txt", kind = "file", size = null, modified = "2026-01-01T00:00:00Z")

        vm.download(entry, {}, { errorMsg = it })
        advanceUntilIdle()

        assertThat(errorMsg).isEqualTo("文件不存在")
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    fun delete_file_success_callsOnDoneAndRefreshes() = runTest {
        advanceUntilIdle()

        var doneMsg: String? = null
        val entry = FileEntry(name = "file.txt", kind = "file", size = 100L, modified = "2026-01-01T00:00:00Z")

        vm.delete(entry, { doneMsg = it }, {})
        advanceUntilIdle()

        assertThat(doneMsg).contains("file.txt")
        coVerify { agentClient.deleteFile(address, writableRoot.id, "file.txt") }
    }

    @Test
    fun delete_failure_callsOnError() = runTest {
        advanceUntilIdle()

        coEvery { agentClient.deleteFile(any(), any(), any()) } throws
            AgentRequestException(ApiError(ApiError.Kind.Unknown, "删除失败"))

        var errorMsg: String? = null
        val entry = FileEntry(name = "file.txt", kind = "file", size = 100L, modified = "2026-01-01T00:00:00Z")

        vm.delete(entry, {}, { errorMsg = it })
        advanceUntilIdle()

        assertThat(errorMsg).isEqualTo("删除失败")
    }

    // ── rename ────────────────────────────────────────────────────────────────

    @Test
    fun rename_success_callsOnDone() = runTest {
        advanceUntilIdle()

        var doneMsg: String? = null
        val entry = FileEntry(name = "old.txt", kind = "file", size = 100L, modified = "2026-01-01T00:00:00Z")

        vm.rename(entry, "new.txt", { doneMsg = it }, {})
        advanceUntilIdle()

        assertThat(doneMsg).contains("new.txt")
        coVerify { agentClient.renameFile(address, writableRoot.id, "old.txt", "new.txt") }
    }

    @Test
    fun rename_failure_callsOnError() = runTest {
        advanceUntilIdle()

        coEvery { agentClient.renameFile(any(), any(), any(), any()) } throws
            AgentRequestException(ApiError(ApiError.Kind.Conflict, "文件已存在"))

        var errorMsg: String? = null
        val entry = FileEntry(name = "old.txt", kind = "file", size = 100L, modified = "2026-01-01T00:00:00Z")

        vm.rename(entry, "existing.txt", {}, { errorMsg = it })
        advanceUntilIdle()

        assertThat(errorMsg).isEqualTo("文件已存在")
    }

    // ── move ──────────────────────────────────────────────────────────────────

    @Test
    fun move_success_callsOnDone() = runTest {
        advanceUntilIdle()

        var doneMsg: String? = null
        val entry = FileEntry(name = "file.txt", kind = "file", size = 100L, modified = "2026-01-01T00:00:00Z")

        vm.move(entry, "archive/file.txt", { doneMsg = it }, {})
        advanceUntilIdle()

        assertThat(doneMsg).isNotNull()
        coVerify { agentClient.moveFile(address, writableRoot.id, "file.txt", "archive/file.txt") }
    }

    // ── server truncation limit ───────────────────────────────────────────────

    @Test
    fun listing_truncated_limitFlowsToUiState() = runTest {
        val truncatedListing = FileListingResult(
            entries = sampleEntries,
            total = 500,
            truncated = true,
            limit = 200,
        )
        coEvery { agentClient.fetchFiles(address, any(), any(), any(), any()) } returns truncatedListing

        vm.loadRoots()
        advanceUntilIdle()

        val listing = vm.uiState.value.listing
        assertThat(listing).isNotNull()
        assertThat(listing!!.truncated).isTrue()
        assertThat(listing.limit).isEqualTo(200)
    }

    // ── WebSocket files.changed subscription ──────────────────────────────────

    @Test
    fun wsFilesChanged_refreshesListingForCurrentPath() = runTest {
        advanceUntilIdle() // init loadRoots = 1 call to fetchFiles

        val envelope = EventEnvelope(
            type = "file.created",
            payload = buildJsonObject {
                put("rootId", writableRoot.id)
                put("path", "")
            },
            timestamp = "2026-01-01T00:00:00Z",
        )
        fakeEvents.emit(envelope)
        advanceTimeBy(600L) // past 500ms debounce
        advanceUntilIdle()

        // At least 2 total: 1 from init + 1 from WS event
        coVerify(atLeast = 2) {
            agentClient.fetchFiles(address, writableRoot.id, "", any(), any())
        }
    }

    @Test
    fun wsFilesChanged_ignoresDifferentRoot() = runTest {
        advanceUntilIdle() // init = 1 call

        val envelope = EventEnvelope(
            type = "file.created",
            payload = buildJsonObject {
                put("rootId", "other-root")
                put("path", "")
            },
            timestamp = "2026-01-01T00:00:00Z",
        )
        fakeEvents.emit(envelope)
        advanceTimeBy(600L)
        advanceUntilIdle()

        // Only the initial loadRoots call — no extra refresh
        coVerify(exactly = 1) { agentClient.fetchFiles(address, writableRoot.id, "", any(), any()) }
    }

    @Test
    fun wsFilesChanged_ignoresDifferentPath() = runTest {
        advanceUntilIdle() // init = 1 call, current path = ""

        val envelope = EventEnvelope(
            type = "file.created",
            payload = buildJsonObject {
                put("rootId", writableRoot.id)
                put("path", "some/other/path")
            },
            timestamp = "2026-01-01T00:00:00Z",
        )
        fakeEvents.emit(envelope)
        advanceTimeBy(600L)
        advanceUntilIdle()

        // Only the initial loadRoots call — no extra refresh
        coVerify(exactly = 1) { agentClient.fetchFiles(address, writableRoot.id, "", any(), any()) }
    }

    @Test
    fun wsFilesChanged_debouncesRapidEvents() = runTest {
        advanceUntilIdle() // init = 1 call

        val envelope = EventEnvelope(
            type = "file.created",
            payload = buildJsonObject {
                put("rootId", writableRoot.id)
                put("path", "")
            },
            timestamp = "2026-01-01T00:00:00Z",
        )

        // Fire 5 rapid events — debounce should collapse to 1 refresh
        repeat(5) { fakeEvents.emit(envelope) }
        advanceTimeBy(600L) // past debounce
        advanceUntilIdle()

        // 1 (init) + 1 (debounced WS) = exactly 2
        coVerify(exactly = 2) { agentClient.fetchFiles(address, writableRoot.id, "", any(), any()) }
    }

    /**
     * When the user navigates away from a directory mid-debounce, the in-flight
     * files.changed event must not refresh the old directory's listing.
     */
    @Test
    fun wsFilesChanged_pathChangesMidDebounce_doesNotRefreshOldPath() = runTest {
        advanceUntilIdle() // init: fetchFiles(rootId, "") = 1 call

        // Emit an event for the original path ("").
        fakeEvents.emit(
            EventEnvelope(
                type = "file.created",
                payload = buildJsonObject {
                    put("rootId", writableRoot.id)
                    put("path", "")
                },
                timestamp = "2026-01-01T00:00:00Z",
            ),
        )
        advanceTimeBy(200L) // partway through the 500ms debounce window

        // User navigates into a subdirectory mid-debounce.
        coEvery { agentClient.fetchFiles(address, writableRoot.id, "sub", any(), any()) } returns emptyListing
        vm.navigateToPath("sub")
        advanceUntilIdle()

        // Now let the debounce window expire. The WS event was for path ""
        // but the current path is now "sub", so the filter rejects it.
        advanceTimeBy(600L)
        advanceUntilIdle()

        // fetchFiles for path "" should NOT be called again. Total calls
        // for "" = 1 (init only).
        coVerify(exactly = 1) { agentClient.fetchFiles(address, writableRoot.id, "", any(), any()) }
    }

    /**
     * When the user switches roots mid-debounce, the in-flight files.changed
     * event must not refresh the old root's listing.
     */
    @Test
    fun wsFilesChanged_rootChangesMidDebounce_doesNotRefreshOldRoot() = runTest {
        advanceUntilIdle()

        fakeEvents.emit(
            EventEnvelope(
                type = "file.created",
                payload = buildJsonObject {
                    put("rootId", writableRoot.id)
                    put("path", "")
                },
                timestamp = "2026-01-01T00:00:00Z",
            ),
        )
        advanceTimeBy(200L)

        // Switch to the read-only root mid-debounce.
        coEvery { agentClient.fetchFiles(address, readOnlyRoot.id, "", any(), any()) } returns emptyListing
        vm.selectRoot(readOnlyRoot)
        advanceUntilIdle()

        advanceTimeBy(600L)
        advanceUntilIdle()

        // The original writableRoot should only have its initial fetch.
        coVerify(exactly = 1) { agentClient.fetchFiles(address, writableRoot.id, "", any(), any()) }
    }
}
