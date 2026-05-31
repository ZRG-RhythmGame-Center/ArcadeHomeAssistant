package com.maimai.home.ui.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maimai.home.R
import com.maimai.home.data.models.AudioDevice

/**
 * Test tags for the AudioScreen — used by Compose UI tests so we don't have
 * to rely on brittle text matching for the slider, mute toggle, and refresh
 * button.
 */
object AudioScreenTags {
    const val MUTE_TOGGLE = "audio.mute.toggle"
    const val VOLUME_SLIDER = "audio.volume.slider"
    const val VOLUME_PERCENT = "audio.volume.percent"
    const val REFRESH_BUTTON = "audio.refresh"
    const val SNACKBAR_HOST = "audio.snackbar"
}

/**
 * Wave 5 task 28: AudioScreen rewrite.
 *  - DisposableEffect keyed by viewModel (stable identity).
 *  - SnackBarHost surfaces transient errorMessage values.
 *  - Mute renders as IconToggleButton(VolumeOff/VolumeUp) (R2 I4).
 *  - Slider gated by isRefreshing + isVolumeBusy + drag flag (W4.23).
 *  - Refresh icon button in the top bar (R2 I16).
 *  - Fixed top-bar title "音频控制" (R2 I1).
 *  - _ConnectionBar showing "已连接：<address>" beneath the top bar (R2 I2).
 *  - 48dp volume-percent column LEFT of the slider (R2 P6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioScreen(
    address: String,
    machineName: String,
    onOpenFiles: (String, String) -> Unit,
    viewModel: AudioViewModel = viewModel(factory = AudioViewModel.factory(address, machineName)),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val updatedError = rememberUpdatedState(uiState.errorMessage)

    // Lifecycle tied to the ViewModel identity, not to Unit. Keeps re-launches
    // from happening on every recomposition.
    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    LaunchedEffect(updatedError.value) {
        val message = updatedError.value
        if (!message.isNullOrBlank()) snackbarHostState.showSnackbar(message)
    }

    AudioScreenContent(
        state = uiState,
        snackbarHostState = snackbarHostState,
        onOpenFiles = { onOpenFiles(address, machineName) },
        onRefresh = viewModel::refresh,
        onVolumeChange = { /* slider drives local state only */ },
        onSetVolume = viewModel::setVolume,
        onSetMuted = viewModel::setMuted,
        onSwitchDevice = viewModel::switchDevice,
        onVolumeDragStart = viewModel::onVolumeDragStart,
        onVolumeDragEnd = viewModel::onVolumeDragEnd,
    )
}

/**
 * Stateless inner composable for tests. Drives [AudioUiState] + callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioScreenContent(
    state: AudioUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onOpenFiles: () -> Unit,
    onRefresh: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onSetVolume: (Float) -> Unit,
    onSetMuted: (Boolean) -> Unit,
    onSwitchDevice: (String) -> Unit,
    onVolumeDragStart: () -> Unit,
    onVolumeDragEnd: () -> Unit,
) {
    val masterVolume = (state.audioState?.masterVolume ?: 0.0).toFloat() * 100f
    var sliderValue by remember(masterVolume) { mutableFloatStateOf(masterVolume) }
    // Local drag flag — true while finger is on the slider track.
    var localDragging by remember { mutableStateOf(false) }
    // Slider is gated when the screen is refreshing OR the VM is busy with a
    // setVolume request. While the user is actively dragging we keep it
    // enabled so the gesture stays responsive (W4.23 + R2 I-3).
    val sliderEnabled = !state.isRefreshing && (!state.isVolumeBusy || localDragging)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.audio_title)) },
                actions = {
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.testTag(AudioScreenTags.REFRESH_BUTTON),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.audio_refresh_cd),
                        )
                    }
                    TextButton(onClick = onOpenFiles) { Text(stringResource(R.string.audio_files_action)) }
                },
            )
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.testTag(AudioScreenTags.SNACKBAR_HOST),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Connection bar (R2 I2): "已连接：<address>" stripe beneath top bar.
            ConnectionBar(state.address)

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    val deviceLabel = state.devices.firstOrNull { it.isDefault }?.name
                        ?: stringResource(R.string.audio_current_device_unknown)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                stringResource(R.string.audio_volume_section_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(stringResource(R.string.audio_current_device_format, deviceLabel))

                            // Volume row: 48dp percent label LEFT of slider (R2 P6).
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.audio_volume_percent_format,
                                        sliderValue.toInt(),
                                    ),
                                    modifier = Modifier
                                        .width(48.dp)
                                        .testTag(AudioScreenTags.VOLUME_PERCENT),
                                    textAlign = TextAlign.End,
                                )
                                Slider(
                                    value = sliderValue,
                                    onValueChange = {
                                        // Started dragging — gate WS pushes via VM.
                                        if (!localDragging) {
                                            localDragging = true
                                            onVolumeDragStart()
                                        }
                                        sliderValue = it
                                        onVolumeChange(it)
                                    },
                                    onValueChangeFinished = {
                                        if (localDragging) {
                                            localDragging = false
                                            onVolumeDragEnd()
                                        }
                                        onSetVolume(sliderValue)
                                    },
                                    valueRange = 0f..100f,
                                    enabled = sliderEnabled,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag(AudioScreenTags.VOLUME_SLIDER),
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.audio_mute_label))
                                val muted = state.audioState?.muted == true
                                IconToggleButton(
                                    checked = muted,
                                    onCheckedChange = onSetMuted,
                                    modifier = Modifier.testTag(AudioScreenTags.MUTE_TOGGLE),
                                ) {
                                    if (muted) {
                                        Icon(
                                            Icons.Filled.VolumeOff,
                                            contentDescription = stringResource(R.string.audio_mute_on_cd),
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.VolumeUp,
                                            contentDescription = stringResource(R.string.audio_mute_off_cd),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.audio_devices_section_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(state.devices, key = { it.id }) { device ->
                    DeviceCard(device, onSwitchDevice)
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ConnectionBar(address: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = stringResource(R.string.audio_connected_format, address),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DeviceCard(device: AudioDevice, onSwitchDevice: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        ListItem(
            headlineContent = { Text(device.name) },
            supportingContent = {
                Text(
                    if (device.isDefault) {
                        stringResource(R.string.audio_default_device_format, describeDeviceState(device.state))
                    } else {
                        describeDeviceState(device.state)
                    },
                )
            },
            trailingContent = {
                if (device.isDefault) {
                    Text(stringResource(R.string.audio_default_marker))
                } else {
                    TextButton(onClick = { onSwitchDevice(device.id) }) {
                        Text(stringResource(R.string.audio_switch_marker))
                    }
                }
            },
        )
    }
}

@Composable
private fun describeDeviceState(state: String): String = when (state.lowercase()) {
    "active" -> stringResource(R.string.audio_state_active)
    "disabled" -> stringResource(R.string.audio_state_disabled)
    "unplugged" -> stringResource(R.string.audio_state_unplugged)
    "notpresent" -> stringResource(R.string.audio_state_notpresent)
    else -> state
}

