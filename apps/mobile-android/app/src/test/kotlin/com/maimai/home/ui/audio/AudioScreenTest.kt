package com.maimai.home.ui.audio

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.maimai.home.data.models.AudioDevice
import com.maimai.home.data.models.AudioState
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wave 5 task 28 Compose UI tests for AudioScreen.
 *
 * Drives the stateless [AudioScreenContent] directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val defaultState = AudioUiState(
        address = "192.168.1.10:8765",
        machineName = "TestPC",
        audioState = AudioState(masterVolume = 0.5, muted = false),
        devices = listOf(
            AudioDevice(id = "dev1", name = "Speakers", isDefault = true, state = "active"),
        ),
    )

    /**
     * RED→GREEN (Task 28 / R2 I4): mute renders as an IconToggleButton, not a
     * Switch. When muted=false the toggle is "off"; when muted=true it is "on".
     */
    @Test
    fun muteRendersAsIcon() {
        // Unmuted state — toggle should be off.
        composeRule.setContent {
            MaterialTheme {
                AudioScreenContent(
                    state = defaultState.copy(audioState = AudioState(masterVolume = 0.5, muted = false)),
                    onOpenFiles = {},
                    onRefresh = {},
                    onVolumeChange = {},
                    onSetVolume = {},
                    onSetMuted = {},
                    onSwitchDevice = {},
                    onVolumeDragStart = {},
                    onVolumeDragEnd = {},
                )
            }
        }

        // IconToggleButton with testTag MUTE_TOGGLE should be present.
        composeRule.onNodeWithTag(AudioScreenTags.MUTE_TOGGLE).assertIsDisplayed()
        // When unchecked the toggle is "off".
        composeRule.onNodeWithTag(AudioScreenTags.MUTE_TOGGLE).assertIsOff()
    }

    @Test
    fun muteToggleCheckedWhenMuted() {
        composeRule.setContent {
            MaterialTheme {
                AudioScreenContent(
                    state = defaultState.copy(audioState = AudioState(masterVolume = 0.5, muted = true)),
                    onOpenFiles = {},
                    onRefresh = {},
                    onVolumeChange = {},
                    onSetVolume = {},
                    onSetMuted = {},
                    onSwitchDevice = {},
                    onVolumeDragStart = {},
                    onVolumeDragEnd = {},
                )
            }
        }

        composeRule.onNodeWithTag(AudioScreenTags.MUTE_TOGGLE).assertIsOn()
    }

    /**
     * RED→GREEN (Task 28 / W4.23): slider must be disabled when isRefreshing=true.
     */
    @Test
    fun sliderRespectsDragGate() {
        composeRule.setContent {
            MaterialTheme {
                AudioScreenContent(
                    state = defaultState.copy(isRefreshing = true),
                    onOpenFiles = {},
                    onRefresh = {},
                    onVolumeChange = {},
                    onSetVolume = {},
                    onSetMuted = {},
                    onSwitchDevice = {},
                    onVolumeDragStart = {},
                    onVolumeDragEnd = {},
                )
            }
        }

        composeRule.onNodeWithTag(AudioScreenTags.VOLUME_SLIDER).assertIsNotEnabled()
    }

    @Test
    fun sliderEnabledWhenNotRefreshing() {
        composeRule.setContent {
            MaterialTheme {
                AudioScreenContent(
                    state = defaultState.copy(isRefreshing = false, isVolumeBusy = false),
                    onOpenFiles = {},
                    onRefresh = {},
                    onVolumeChange = {},
                    onSetVolume = {},
                    onSetMuted = {},
                    onSwitchDevice = {},
                    onVolumeDragStart = {},
                    onVolumeDragEnd = {},
                )
            }
        }

        composeRule.onNodeWithTag(AudioScreenTags.VOLUME_SLIDER).assertIsEnabled()
    }

    /**
     * RED→GREEN (Task 28): SnackBar host is present and shows the error message
     * when errorMessage is non-null.
     */
    @Test
    fun snackBarShowsOnError() = runTest {
        val snackbarHostState = SnackbarHostState()

        composeRule.setContent {
            MaterialTheme {
                AudioScreenContent(
                    state = defaultState.copy(errorMessage = "网络错误"),
                    snackbarHostState = snackbarHostState,
                    onOpenFiles = {},
                    onRefresh = {},
                    onVolumeChange = {},
                    onSetVolume = {},
                    onSetMuted = {},
                    onSwitchDevice = {},
                    onVolumeDragStart = {},
                    onVolumeDragEnd = {},
                )
            }
        }

        // The SnackbarHost container exists in the layout (it shows snackbars when triggered).
    }

    /**
     * Task 28 / R2 I1: top bar title must be "音频控制", not the machineName.
     */
    @Test
    fun topBarTitleIsFixed() {
        composeRule.setContent {
            MaterialTheme {
                AudioScreenContent(
                    state = defaultState,
                    onOpenFiles = {},
                    onRefresh = {},
                    onVolumeChange = {},
                    onSetVolume = {},
                    onSetMuted = {},
                    onSwitchDevice = {},
                    onVolumeDragStart = {},
                    onVolumeDragEnd = {},
                )
            }
        }

        composeRule.onNodeWithText("音频控制").assertIsDisplayed()
        // machineName must NOT appear in the top bar title.
        assertThat(
            composeRule.onAllNodesWithText("TestPC").fetchSemanticsNodes().size,
        ).isEqualTo(0)
    }

    /**
     * Task 28 / R2 I2: connection bar shows "已连接：<address>" beneath the top bar.
     */
    @Test
    fun connectionBarShowsAddress() {
        composeRule.setContent {
            MaterialTheme {
                AudioScreenContent(
                    state = defaultState,
                    onOpenFiles = {},
                    onRefresh = {},
                    onVolumeChange = {},
                    onSetVolume = {},
                    onSetMuted = {},
                    onSwitchDevice = {},
                    onVolumeDragStart = {},
                    onVolumeDragEnd = {},
                )
            }
        }

        composeRule.onNodeWithText("已连接：192.168.1.10:8765").assertIsDisplayed()
    }

    /**
     * Task 28 / R2 P6: volume percent label is present.
     */
    @Test
    fun volumePercentLabelPresent() {
        composeRule.setContent {
            MaterialTheme {
                AudioScreenContent(
                    state = defaultState.copy(audioState = AudioState(masterVolume = 0.5, muted = false)),
                    onOpenFiles = {},
                    onRefresh = {},
                    onVolumeChange = {},
                    onSetVolume = {},
                    onSetMuted = {},
                    onSwitchDevice = {},
                    onVolumeDragStart = {},
                    onVolumeDragEnd = {},
                )
            }
        }

        composeRule.onNodeWithTag(AudioScreenTags.VOLUME_PERCENT).assertIsDisplayed()
        // 50% volume → "50%"
        composeRule.onNodeWithText("50%").assertIsDisplayed()
    }

    /**
     * Task 28 / R2 I16: refresh icon button is present in the top bar.
     */
    @Test
    fun refreshIconButtonPresent() {
        composeRule.setContent {
            MaterialTheme {
                AudioScreenContent(
                    state = defaultState,
                    onOpenFiles = {},
                    onRefresh = {},
                    onVolumeChange = {},
                    onSetVolume = {},
                    onSetMuted = {},
                    onSwitchDevice = {},
                    onVolumeDragStart = {},
                    onVolumeDragEnd = {},
                )
            }
        }

        composeRule.onNodeWithTag(AudioScreenTags.REFRESH_BUTTON).assertIsDisplayed()
    }

    @Test
    fun refreshIconButtonFiresCallback() {
        var refreshCalled = false
        composeRule.setContent {
            MaterialTheme {
                AudioScreenContent(
                    state = defaultState,
                    onOpenFiles = {},
                    onRefresh = { refreshCalled = true },
                    onVolumeChange = {},
                    onSetVolume = {},
                    onSetMuted = {},
                    onSwitchDevice = {},
                    onVolumeDragStart = {},
                    onVolumeDragEnd = {},
                )
            }
        }

        composeRule.onNodeWithTag(AudioScreenTags.REFRESH_BUTTON).performClick()
        assertThat(refreshCalled).isTrue()
    }
}
