package com.maimai.home.ui.connection

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import com.maimai.home.data.DiscoveredService
import com.maimai.home.data.models.AgentStatus
import com.maimai.home.data.models.Capabilities
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wave 5 task 26 + 27 Compose UI tests for ConnectionScreen.
 *
 * Uses createComposeRule + Robolectric so the suite stays in :app:testDebugUnitTest.
 * Drives the stateless [ConnectionScreenContent] directly to avoid pulling in
 * the ServiceLocator + DataStore production wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConnectionScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val okStatus = AgentStatus(
        machineName = "DESKTOP-PC",
        version = "1.2.3",
        uptimeSeconds = 42L,
        capabilities = Capabilities(
            audioVolume = true,
            audioMute = true,
            audioDeviceSwitch = true,
            fileManagement = true,
            discoveryBroadcast = true,
        ),
    )

    /**
     * RED→GREEN (Task 26): success card must show "进入设备" button and tapping it
     * must invoke onEnterDevice with the active address + machineName. Auto-
     * navigation must NOT happen — the button is the ONLY navigation trigger.
     */
    @Test
    fun successCardShowsAndManualNavigate() {
        var capturedAddress: String? = null
        var capturedMachineName: String? = null

        composeRule.setContent {
            MaterialTheme {
                ConnectionScreenContent(
                    state = ConnectionUiState(
                        address = "192.168.1.42:8765",
                        connectedStatus = okStatus,
                    ),
                    onUpdateAddress = {},
                    onTestConnection = {},
                    onScanLan = {},
                    onUseDiscoveredService = {},
                    onEnterDevice = { addr, mn ->
                        capturedAddress = addr
                        capturedMachineName = mn
                    },
                )
            }
        }

        // Success card metadata is shown.
        composeRule.onNodeWithText("机器名：DESKTOP-PC").assertIsDisplayed()

        // The "进入设备" button is present and enabled.
        // Scroll to it first (it's inside a LazyColumn).
        composeRule.onNodeWithText("进入设备").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("进入设备").performClick()

        // Manual navigate fired with the active address + machineName.
        assertThat(capturedAddress).isEqualTo("192.168.1.42:8765")
        assertThat(capturedMachineName).isEqualTo("DESKTOP-PC")
    }

    /**
     * Without a connectedStatus there is no "进入设备" button — the user must
     * test or scan first. Pins the absence of auto-navigation.
     */
    @Test
    fun enterDeviceButtonHiddenUntilStatusArrives() {
        composeRule.setContent {
            MaterialTheme {
                ConnectionScreenContent(
                    state = ConnectionUiState(address = "1.2.3.4:8765"),
                    onUpdateAddress = {},
                    onTestConnection = {},
                    onScanLan = {},
                    onUseDiscoveredService = {},
                    onEnterDevice = { _, _ -> },
                )
            }
        }

        assertThat(composeRule.onAllNodesWithText("进入设备").fetchSemanticsNodes()).isEmpty()
    }

    /**
     * Task 27: while testing/scanning the buttons swap to inline progress copy
     * "连接中…" / "扫描中…".
     */
    @Test
    fun loadingButtonsShowInlineProgressCopy() {
        composeRule.setContent {
            MaterialTheme {
                ConnectionScreenContent(
                    state = ConnectionUiState(
                        address = "x:1",
                        isTesting = true,
                        isScanning = true,
                    ),
                    onUpdateAddress = {},
                    onTestConnection = {},
                    onScanLan = {},
                    onUseDiscoveredService = {},
                    onEnterDevice = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("连接中…").assertIsDisplayed()
        composeRule.onNodeWithText("扫描中…").assertIsDisplayed()

        // The buttons that wrap these labels are disabled while loading.
        composeRule.onNodeWithText("连接中…").assertIsNotEnabled()
        composeRule.onNodeWithText("扫描中…").assertIsNotEnabled()
    }

    @Test
    fun idleButtonsRenderTheirRestingCopy() {
        composeRule.setContent {
            MaterialTheme {
                ConnectionScreenContent(
                    state = ConnectionUiState(address = "x:1"),
                    onUpdateAddress = {},
                    onTestConnection = {},
                    onScanLan = {},
                    onUseDiscoveredService = {},
                    onEnterDevice = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("测试连接").assertIsEnabled()
        composeRule.onNodeWithText("扫描局域网").assertIsEnabled()
    }

    @Test
    fun emptyDiscoveryShowsEmptyCardCopy() {
        composeRule.setContent {
            MaterialTheme {
                ConnectionScreenContent(
                    state = ConnectionUiState(address = "x:1", discovered = emptyList(), isScanning = false),
                    onUpdateAddress = {},
                    onTestConnection = {},
                    onScanLan = {},
                    onUseDiscoveredService = {},
                    onEnterDevice = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("暂无发现结果", substring = true).assertIsDisplayed()
    }

    @Test
    fun discoveryListClickFiresUseDiscoveredService() {
        var captured: DiscoveredService? = null
        val service = DiscoveredService("BIG-PC", "10.0.0.5", 8765)

        composeRule.setContent {
            MaterialTheme {
                ConnectionScreenContent(
                    state = ConnectionUiState(address = "x:1", discovered = listOf(service)),
                    onUpdateAddress = {},
                    onTestConnection = {},
                    onScanLan = {},
                    onUseDiscoveredService = { captured = it },
                    onEnterDevice = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("BIG-PC").performClick()
        assertThat(captured).isEqualTo(service)
    }

    /**
     * Task 26 helpers: LoadingCard / EmptyCard / ErrorCard render their text.
     */
    @Test
    fun loadingCardRendersText() {
        composeRule.setContent {
            MaterialTheme {
                LoadingCard(text = "loading-cd")
            }
        }
        composeRule.onNodeWithText("loading-cd").assertIsDisplayed()
    }

    @Test
    fun emptyCardRendersText() {
        composeRule.setContent {
            MaterialTheme {
                EmptyCard(text = "empty-cd")
            }
        }
        composeRule.onNodeWithText("empty-cd").assertIsDisplayed()
    }

    @Test
    fun errorCardRendersText() {
        composeRule.setContent {
            MaterialTheme {
                ErrorCard(text = "error-cd")
            }
        }
        composeRule.onNodeWithText("error-cd").assertIsDisplayed()
    }
}

