package com.maimai.home.ui.connection

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.maimai.home.MainDispatcherRule
import com.maimai.home.data.AgentClient
import com.maimai.home.data.AgentPreferences
import com.maimai.home.data.DiscoveredService
import com.maimai.home.data.DiscoveryService
import com.maimai.home.data.models.AgentRequestException
import com.maimai.home.data.models.AgentStatus
import com.maimai.home.data.models.ApiError
import com.maimai.home.data.models.Capabilities
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Wave 4 task 20 + 21: ConnectionViewModel unit tests.
 *
 * RED commit: useDiscoveredService_triggersSilentVerifyAndNavigate fails because
 * the production code does NOT call fetchStatus. GREEN lands after task 21 fix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var preferences: AgentPreferences
    private lateinit var agentClient: AgentClient
    private lateinit var discoveryService: DiscoveryService
    private lateinit var vm: ConnectionViewModel

    private val fakeAddressFlow = MutableStateFlow(AgentPreferences.DEFAULT_AGENT_ADDRESS)

    private val okStatus = AgentStatus(
        machineName = "TestPC",
        version = "1.0.0",
        uptimeSeconds = 42L,
        capabilities = Capabilities(
            audioVolume = true,
            audioMute = true,
            audioDeviceSwitch = true,
            fileManagement = true,
            discoveryBroadcast = true,
        ),
    )

    @BeforeEach
    fun setUp() {
        preferences = mockk(relaxed = true)
        agentClient = mockk(relaxed = true)
        discoveryService = mockk(relaxed = true)

        // Default: agentAddressFlow emits the default address
        coEvery { preferences.agentAddressFlow } returns fakeAddressFlow

        vm = ConnectionViewModel(preferences, agentClient, discoveryService)
    }

    // ── testConnection ────────────────────────────────────────────────────────

    @Test
    fun testConnection_success_emitsConnectedStatus() = runTest {
        coEvery { agentClient.fetchStatus(any()) } returns okStatus

        vm.testConnection()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.connectedStatus).isEqualTo(okStatus)
        assertThat(state.isTesting).isFalse()
        assertThat(state.errorMessage).isNull()
    }

    @Test
    fun testConnection_savesAddressOnSuccess() = runTest {
        coEvery { agentClient.fetchStatus(any()) } returns okStatus

        vm.updateAddress("192.168.1.50:8765")
        vm.testConnection()
        advanceUntilIdle()

        coVerify { preferences.saveAgentAddress("192.168.1.50:8765") }
    }

    @Test
    fun testConnection_networkError_setsErrorMessage() = runTest {
        coEvery { agentClient.fetchStatus(any()) } throws
            AgentRequestException(ApiError(ApiError.Kind.Network, "网络错误"))

        vm.testConnection()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.errorMessage).isEqualTo("网络错误")
        assertThat(state.connectedStatus).isNull()
        assertThat(state.isTesting).isFalse()
    }

    @Test
    fun testConnection_timeoutError_setsTimeoutMessage() = runTest {
        coEvery { agentClient.fetchStatus(any()) } throws
            AgentRequestException(ApiError(ApiError.Kind.Timeout, "连接超时"))

        vm.testConnection()
        advanceUntilIdle()

        assertThat(vm.uiState.value.errorMessage).isEqualTo("连接超时")
    }

    @Test
    fun testConnection_notFoundError_setsNotFoundMessage() = runTest {
        coEvery { agentClient.fetchStatus(any()) } throws
            AgentRequestException(ApiError(ApiError.Kind.NotFound, "未找到 Agent（404）"))

        vm.testConnection()
        advanceUntilIdle()

        assertThat(vm.uiState.value.errorMessage).isEqualTo("未找到 Agent（404）")
    }

    @Test
    fun testConnection_nonAgentException_fallsBackToNetworkError() = runTest {
        coEvery { agentClient.fetchStatus(any()) } throws RuntimeException("boom")

        vm.testConnection()
        advanceUntilIdle()

        assertThat(vm.uiState.value.errorMessage).isEqualTo("网络错误")
    }

    // ── scanLan ───────────────────────────────────────────────────────────────

    @Test
    fun scanLan_success_populatesDiscoveredList() = runTest {
        val services = listOf(
            DiscoveredService("PC-1", "192.168.1.10", 8765),
            DiscoveredService("PC-2", "192.168.1.11", 8765),
        )
        coEvery { discoveryService.discover(any()) } returns services

        vm.scanLan()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.discovered).isEqualTo(services)
        assertThat(state.isScanning).isFalse()
        assertThat(state.errorMessage).isNull()
    }

    @Test
    fun scanLan_failure_setsErrorMessage() = runTest {
        coEvery { discoveryService.discover(any()) } throws RuntimeException("NSD failed")

        vm.scanLan()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.errorMessage).isEqualTo("NSD failed")
        assertThat(state.isScanning).isFalse()
    }

    // ── useDiscoveredService (Task 21 RED → GREEN) ────────────────────────────

    /**
     * RED: This test fails before task 21 because the production code only saves
     * the address but does NOT call fetchStatus or set connectedStatus.
     * GREEN: After task 21 fix, fetchStatus is called and connectedStatus is set.
     */
    @Test
    fun useDiscoveredService_triggersSilentVerifyAndNavigate() = runTest {
        val service = DiscoveredService("TestPC", "192.168.1.99", 8765)
        coEvery { agentClient.fetchStatus(service.address) } returns okStatus

        vm.useDiscoveredService(service)
        advanceUntilIdle()

        // Must call fetchStatus with the service address
        coVerify { agentClient.fetchStatus(service.address) }

        // connectedStatus must be populated so the screen can navigate
        val state = vm.uiState.value
        assertThat(state.connectedStatus).isEqualTo(okStatus)
        assertThat(state.address).isEqualTo(service.address)
    }

    /**
     * Closes Gate G F1 / M5 / R1#6: silent verify must also emit a one-shot
     * discoveryNavigation event so ConnectionScreen can auto-navigate to the
     * AudioScreen without requiring the user to tap "进入设备".
     */
    @Test
    fun useDiscoveredService_emitsDiscoveryNavigationEvent() = runTest {
        val service = DiscoveredService("PC-Auto", "10.0.0.5", 8765)
        coEvery { agentClient.fetchStatus(service.address) } returns okStatus

        vm.discoveryNavigation.test {
            vm.useDiscoveredService(service)
            advanceUntilIdle()

            val event = awaitItem()
            assertThat(event.address).isEqualTo(service.address)
            assertThat(event.machineName).isEqualTo(okStatus.machineName)
        }
    }

    @Test
    fun useDiscoveredService_fetchStatusFailure_setsErrorMessage() = runTest {
        val service = DiscoveredService("BadPC", "192.168.1.200", 8765)
        coEvery { agentClient.fetchStatus(service.address) } throws
            AgentRequestException(ApiError(ApiError.Kind.Network, "网络错误"))

        vm.useDiscoveredService(service)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.connectedStatus).isNull()
        assertThat(state.errorMessage).isEqualTo("网络错误")
    }

    // ── navigation precondition ───────────────────────────────────────────────

    @Test
    fun connectedStatus_nonNull_impliesNavigationReady() = runTest {
        coEvery { agentClient.fetchStatus(any()) } returns okStatus

        vm.testConnection()
        advanceUntilIdle()

        // connectedStatus != null is the signal for the screen to navigate
        assertThat(vm.uiState.value.connectedStatus).isNotNull()
    }

    @Test
    fun clearConnectedStatus_resetsToNull() = runTest {
        coEvery { agentClient.fetchStatus(any()) } returns okStatus
        vm.testConnection()
        advanceUntilIdle()

        vm.clearConnectedStatus()

        assertThat(vm.uiState.value.connectedStatus).isNull()
    }
}
