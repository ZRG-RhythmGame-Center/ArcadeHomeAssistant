package com.maimai.home.ui.power

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.maimai.home.data.models.AgentStatus
import com.maimai.home.data.models.Capabilities
import com.maimai.home.data.models.RemoteShutdownStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PowerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val availableState = PowerUiState(
        address = "192.168.1.10:8765",
        machineName = "TestPC",
        agentStatus = AgentStatus(
            machineName = "TestPC",
            version = "1",
            uptimeSeconds = 1,
            capabilities = Capabilities(remoteShutdown = true),
        ),
        shutdownStatus = RemoteShutdownStatus(available = true, state = "idle"),
    )

    @Test
    fun shutdownButtonDisabledWhenCapabilityUnavailable() {
        composeRule.setContent {
            MaterialTheme {
                PowerScreenContent(
                    state = availableState.copy(
                        agentStatus = availableState.agentStatus?.copy(
                            capabilities = Capabilities(remoteShutdown = false),
                        ),
                    ),
                    onOpenDevice = {},
                    onRefresh = {},
                    onShowConfirm = {},
                    onHideConfirm = {},
                    onExecute = {},
                )
            }
        }

        composeRule.onNodeWithTag(PowerScreenTags.SHUTDOWN_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun confirmDialogExecutesImmediatelyWithoutToken() {
        var showConfirmCalled = false
        var executed = false

        composeRule.setContent {
            MaterialTheme {
                PowerScreenContent(
                    state = availableState.copy(confirmVisible = true),
                    onOpenDevice = {},
                    onRefresh = {},
                    onShowConfirm = { showConfirmCalled = true },
                    onHideConfirm = {},
                    onExecute = { executed = true },
                )
            }
        }

        composeRule.onNodeWithText("确认远程关机").assertIsDisplayed()
        composeRule.onNodeWithText("确认后将立即关机", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(PowerScreenTags.CONFIRM_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(PowerScreenTags.CONFIRM_BUTTON).performClick()
        assertThat(executed).isTrue()
        assertThat(showConfirmCalled).isFalse()
    }

    @Test
    fun shutdownButtonEnabledWhenAvailableWithoutTokenEntry() {
        composeRule.setContent {
            MaterialTheme {
                PowerScreenContent(
                    state = availableState,
                    onOpenDevice = {},
                    onRefresh = {},
                    onShowConfirm = {},
                    onHideConfirm = {},
                    onExecute = {},
                )
            }
        }

        // After LAN no-auth refactor, no token field exists; the shutdown
        // button should be enabled whenever the capability is available.
        composeRule.onNodeWithTag(PowerScreenTags.SHUTDOWN_BUTTON).assertIsDisplayed()
    }
}
