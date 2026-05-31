package com.maimai.home.ui.audio

import com.google.common.truth.Truth.assertThat
import com.maimai.home.MainDispatcherRule
import com.maimai.home.data.AgentClient
import com.maimai.home.data.models.AgentRequestException
import com.maimai.home.data.models.ApiError
import com.maimai.home.data.models.AudioDevice
import com.maimai.home.data.models.AudioState
import com.maimai.home.data.models.EventEnvelope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Wave 4 task 22 + 23: AudioViewModel unit tests.
 *
 * RED tests (fail before task 23 fixes):
 *  - refreshDevices_failure_surfacesErrorMessage  (currently swallowed)
 *  - setVolume_setsIsVolumeBusyAroundRequest       (isVolumeBusy not in state)
 *  - dragGate_wsAudioStateIgnoredWhileDragging     (drag gate not implemented)
 *  - dragEnd_appliesNextWsAudioState               (drag gate not implemented)
 *
 * GREEN tests (pass once task 23 lands):
 *  - All of the above + the happy-path cases below.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var agentClient: AgentClient
    private lateinit var fakeEvents: MutableSharedFlow<EventEnvelope>
    private lateinit var fakeConnectionState: MutableStateFlow<com.maimai.home.data.EventStream.ConnectionState>
    private lateinit var vm: AudioViewModel

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val address = "192.168.1.10:8765"
    private val machineName = "TestPC"

    private val defaultState = AudioState(masterVolume = 0.5, muted = false, defaultDeviceId = "dev1")
    private val defaultDevices = listOf(
        AudioDevice(id = "dev1", name = "Speakers", isDefault = true, state = "active"),
        AudioDevice(id = "dev2", name = "Headphones", isDefault = false, state = "active"),
    )

    @BeforeEach
    fun setUp() {
        agentClient = mockk(relaxed = true)
        fakeEvents = MutableSharedFlow(extraBufferCapacity = 16)
        fakeConnectionState = MutableStateFlow(com.maimai.home.data.EventStream.ConnectionState.Disconnected)

        coEvery { agentClient.fetchAudioState(address) } returns defaultState
        coEvery { agentClient.fetchAudioDevices(address) } returns defaultDevices

        vm = AudioViewModel(
            address = address,
            machineName = machineName,
            agentClient = agentClient,
            eventFlow = fakeEvents,
            connectionStateFlow = fakeConnectionState,
        )
    }

    // ── refresh happy path ────────────────────────────────────────────────────

    @Test
    fun refresh_success_updatesAudioStateAndDevices() = runTest {
        vm.refresh()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.audioState).isEqualTo(defaultState)
        assertThat(state.devices).isEqualTo(defaultDevices)
        assertThat(state.isRefreshing).isFalse()
        assertThat(state.errorMessage).isNull()
    }

    @Test
    fun refresh_failure_setsErrorMessage() = runTest {
        coEvery { agentClient.fetchAudioState(address) } throws
            AgentRequestException(ApiError(ApiError.Kind.Network, "网络错误"))

        vm.refresh()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.errorMessage).isEqualTo("网络错误")
        assertThat(state.isRefreshing).isFalse()
    }

    // ── refreshDevices error surface (RED before task 23) ─────────────────────

    /**
     * RED: Currently refreshDevices() only has .onSuccess — failures are silently
     * swallowed. After task 23 fix, this test goes GREEN.
     */
    @Test
    fun refreshDevices_failure_surfacesErrorMessage() = runTest {
        coEvery { agentClient.fetchAudioDevices(address) } throws
            AgentRequestException(ApiError(ApiError.Kind.Network, "设备列表获取失败"))

        vm.refreshDevices()
        advanceUntilIdle()

        assertThat(vm.uiState.value.errorMessage).isEqualTo("设备列表获取失败")
    }

    @Test
    fun refreshDevices_success_updatesDeviceList() = runTest {
        val newDevices = listOf(AudioDevice("dev3", "HDMI", false, "active"))
        coEvery { agentClient.fetchAudioDevices(address) } returns newDevices

        vm.refreshDevices()
        advanceUntilIdle()

        assertThat(vm.uiState.value.devices).isEqualTo(newDevices)
    }

    // ── isVolumeBusy (RED before task 23) ─────────────────────────────────────

    /**
     * Asserts isVolumeBusy is TRUE while the setVolume request is in-flight
     * AND becomes FALSE after the request completes. The test holds the
     * mocked agentClient.setVolume call open with a CompletableDeferred so
     * we can sample the busy state mid-flight; if a future regression deletes
     * the `isVolumeBusy = true` update at the start of setVolume, this test
     * fails because the mid-flight assertion sees `false` instead of `true`.
     */
    @Test
    fun setVolume_setsIsVolumeBusyAroundRequest() = runTest {
        val newState = AudioState(masterVolume = 0.8, muted = false)
        val gate = kotlinx.coroutines.CompletableDeferred<AudioState>()
        coEvery { agentClient.setVolume(address, any()) } coAnswers {
            gate.await()
        }

        vm.setVolume(80f)
        // Pump the coroutines that have run synchronously up to the suspend.
        // The `coAnswers { gate.await() }` parks the call; setVolume's
        // `_uiState.update { it.copy(isVolumeBusy = true) }` has already
        // executed because it ran before agentClient.setVolume.
        runCurrent()
        assertThat(vm.uiState.value.isVolumeBusy).isTrue()

        // Now release the gate and let setVolume complete.
        gate.complete(newState)
        advanceUntilIdle()

        // After completion, isVolumeBusy must flip back to false.
        assertThat(vm.uiState.value.isVolumeBusy).isFalse()
        assertThat(vm.uiState.value.audioState).isEqualTo(newState)
    }

    @Test
    fun setVolume_failure_clearsIsVolumeBusyAndSetsError() = runTest {
        coEvery { agentClient.setVolume(address, any()) } throws
            AgentRequestException(ApiError(ApiError.Kind.Busy, "服务忙，请稍后重试"))

        vm.setVolume(50f)
        advanceUntilIdle()

        assertThat(vm.uiState.value.isVolumeBusy).isFalse()
        assertThat(vm.uiState.value.errorMessage).isEqualTo("服务忙，请稍后重试")
    }

    // ── drag gate (RED before task 23) ────────────────────────────────────────

    /**
     * RED: onVolumeDragStart/onVolumeDragEnd don't exist yet. After task 23 adds
     * them, WS audio.state events must NOT update audioState while dragging.
     */
    @Test
    fun dragGate_wsAudioStateIgnoredWhileDragging() = runTest {
        // Establish initial state
        vm.refresh()
        advanceUntilIdle()
        val initialState = vm.uiState.value.audioState

        // Start drag
        vm.onVolumeDragStart()

        // Push a WS audio.state event
        val wsState = AudioState(masterVolume = 0.9, muted = false)
        val envelope = EventEnvelope(
            type = "audio.state",
            payload = json.encodeToJsonElement(wsState),
            timestamp = "2026-01-01T00:00:00Z",
        )
        fakeEvents.emit(envelope)
        advanceUntilIdle()

        // audioState must NOT have changed while dragging
        assertThat(vm.uiState.value.audioState).isEqualTo(initialState)
    }

    @Test
    fun dragEnd_appliesNextWsAudioState() = runTest {
        vm.refresh()
        advanceUntilIdle()

        vm.onVolumeDragStart()

        val wsState = AudioState(masterVolume = 0.9, muted = false)
        val envelope = EventEnvelope(
            type = "audio.state",
            payload = json.encodeToJsonElement(wsState),
            timestamp = "2026-01-01T00:00:00Z",
        )
        fakeEvents.emit(envelope)
        advanceUntilIdle()

        // End drag — the pending WS state should now be applied
        vm.onVolumeDragEnd()
        advanceUntilIdle()

        assertThat(vm.uiState.value.audioState).isEqualTo(wsState)
    }

    // ── switchDevice ──────────────────────────────────────────────────────────

    @Test
    fun switchDevice_success_updatesStateAndDevices() = runTest {
        val newState = AudioState(masterVolume = 0.5, muted = false, defaultDeviceId = "dev2")
        val newDevices = listOf(
            AudioDevice("dev1", "Speakers", false, "active"),
            AudioDevice("dev2", "Headphones", true, "active"),
        )
        coEvery { agentClient.switchDevice(address, "dev2") } returns newDevices
        coEvery { agentClient.fetchAudioState(address) } returns newState

        vm.switchDevice("dev2")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.audioState).isEqualTo(newState)
        assertThat(state.devices).isEqualTo(newDevices)
        assertThat(state.errorMessage).isNull()
    }

    @Test
    fun switchDevice_failure_setsErrorMessage() = runTest {
        coEvery { agentClient.switchDevice(address, any()) } throws
            AgentRequestException(ApiError(ApiError.Kind.DeviceUnavailable, "设备不可用"))

        vm.switchDevice("dev2")
        advanceUntilIdle()

        assertThat(vm.uiState.value.errorMessage).isEqualTo("设备不可用")
    }

    // ── WebSocket audio.state event ───────────────────────────────────────────

    @Test
    fun wsAudioStateEvent_updatesAudioState_whenNotDragging() = runTest {
        vm.refresh()
        advanceUntilIdle()

        val wsState = AudioState(masterVolume = 0.3, muted = true)
        val envelope = EventEnvelope(
            type = "audio.state",
            payload = json.encodeToJsonElement(wsState),
            timestamp = "2026-01-01T00:00:00Z",
        )
        fakeEvents.emit(envelope)
        advanceUntilIdle()

        assertThat(vm.uiState.value.audioState).isEqualTo(wsState)
        assertThat(vm.uiState.value.errorMessage).isNull()
    }

    @Test
    fun wsAudioStateEvent_malformedPayload_isIgnored() = runTest {
        vm.refresh()
        advanceUntilIdle()
        val initialState = vm.uiState.value.audioState

        val envelope = EventEnvelope(
            type = "audio.state",
            payload = JsonPrimitive("not-an-object"),
            timestamp = "2026-01-01T00:00:00Z",
        )
        fakeEvents.emit(envelope)
        advanceUntilIdle()

        // State must be unchanged
        assertThat(vm.uiState.value.audioState).isEqualTo(initialState)
    }

    // ── WebSocket audio.device.changed event ─────────────────────────────────

    @Test
    fun wsAudioDeviceChangedEvent_triggersRefreshDevices() = runTest {
        vm.refresh()
        advanceUntilIdle()

        val newDevices = listOf(AudioDevice("dev3", "HDMI", true, "active"))
        coEvery { agentClient.fetchAudioDevices(address) } returns newDevices

        val envelope = EventEnvelope(
            type = "audio.device.changed",
            payload = JsonObject(emptyMap()),
            timestamp = "2026-01-01T00:00:00Z",
        )
        fakeEvents.emit(envelope)
        advanceUntilIdle()

        coVerify(atLeast = 1) { agentClient.fetchAudioDevices(address) }
        assertThat(vm.uiState.value.devices).isEqualTo(newDevices)
    }

    // ── connectionState propagation ───────────────────────────────────────────

    @Test
    fun connectionStateChange_updatesConnectionText() = runTest {
        fakeConnectionState.value = com.maimai.home.data.EventStream.ConnectionState.Connected
        advanceUntilIdle()

        assertThat(vm.uiState.value.connectionText).isEqualTo("Connected")
    }
}
