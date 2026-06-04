package com.maimai.home.ui.power

import com.google.common.truth.Truth.assertThat
import com.maimai.home.MainDispatcherRule
import com.maimai.home.data.AgentClient
import com.maimai.home.data.models.AgentRequestException
import com.maimai.home.data.models.AgentStatus
import com.maimai.home.data.models.ApiError
import com.maimai.home.data.models.Capabilities
import com.maimai.home.data.models.EventEnvelope
import com.maimai.home.data.models.RemoteShutdownStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class PowerViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcherRule = MainDispatcherRule()

    private val address = "192.168.1.10:8765"
    private val machineName = "TestPC"
    private lateinit var agentClient: AgentClient
    private lateinit var fakeEvents: MutableSharedFlow<EventEnvelope>
    private lateinit var vm: PowerViewModel

    private val availableStatus = AgentStatus(
        machineName = machineName,
        version = "1",
        uptimeSeconds = 1,
        capabilities = Capabilities(remoteShutdown = true),
    )
    private val idleShutdown = RemoteShutdownStatus(available = true, state = "idle")

    @BeforeEach
    fun setUp() {
        agentClient = mockk(relaxed = true)
        fakeEvents = MutableSharedFlow(extraBufferCapacity = 16)
        coEvery { agentClient.fetchStatus(address) } returns availableStatus
        coEvery { agentClient.fetchRemoteShutdownStatus(address) } returns idleShutdown
        vm = PowerViewModel(
            address = address,
            machineName = machineName,
            agentClient = agentClient,
            eventFlow = fakeEvents,
        )
    }

    @Test
    fun refresh_setsCapabilityAndShutdownStatus() = runTest {
        vm.refresh()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.remoteShutdownAvailable).isTrue()
        assertThat(state.shutdownStatus?.state).isEqualTo("idle")
        assertThat(state.isRefreshing).isFalse()
    }

    @Test
    fun executeShutdown_sendsTrimmedTokenAndUpdatesStatus() = runTest {
        val executing = RemoteShutdownStatus(available = true, state = "executing")
        coEvery { agentClient.executeRemoteShutdown(address, "secret-token") } returns executing

        vm.updateControlToken(" secret-token ")
        vm.showConfirm()
        vm.executeShutdown()
        advanceUntilIdle()

        coVerify { agentClient.executeRemoteShutdown(address, "secret-token") }
        assertThat(vm.uiState.value.shutdownStatus).isEqualTo(executing)
        assertThat(vm.uiState.value.confirmVisible).isFalse()
    }

    @Test
    fun executeShutdown_withoutToken_setsError() = runTest {
        vm.executeShutdown()
        advanceUntilIdle()

        assertThat(vm.uiState.value.errorMessage).isEqualTo("请输入控制令牌")
    }

    @Test
    fun powerEvent_refreshesShutdownStatus() = runTest {
        vm.refresh()
        advanceUntilIdle()
        val executing = RemoteShutdownStatus(available = true, state = "executing")
        coEvery { agentClient.fetchRemoteShutdownStatus(address) } returns executing

        fakeEvents.emit(EventEnvelope("power.shutdown.executing", JsonObject(emptyMap()), "2026-01-01T00:00:00Z"))
        advanceUntilIdle()

        coVerify(atLeast = 2) { agentClient.fetchRemoteShutdownStatus(address) }
        assertThat(vm.uiState.value.shutdownStatus).isEqualTo(executing)
    }

    @Test
    fun requestFailure_surfacesApiErrorMessage() = runTest {
        coEvery { agentClient.fetchStatus(address) } throws
            AgentRequestException(ApiError(ApiError.Kind.Network, "网络错误"))

        vm.refresh()
        advanceUntilIdle()

        assertThat(vm.uiState.value.errorMessage).isEqualTo("网络错误")
    }
}
