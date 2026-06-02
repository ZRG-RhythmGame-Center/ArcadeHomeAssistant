package com.maimai.home.ui.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maimai.home.data.models.AudioDevice
import com.maimai.home.ui.common.BentoCard
import com.maimai.home.ui.common.BentoCardTitle
import com.maimai.home.ui.common.CurrentDeviceCard
import com.maimai.home.ui.common.MaimaiScreenScaffold

/**
 * Test tags preserved for Compose UI tests.
 */
object AudioScreenTags {
    const val MUTE_TOGGLE = "audio.mute.toggle"
    const val VOLUME_SLIDER = "audio.volume.slider"
    const val VOLUME_PERCENT = "audio.volume.percent"
    const val REFRESH_BUTTON = "audio.refresh"
    const val SNACKBAR_HOST = "audio.snackbar"
}

/**
 * Wave 8 redesign matching apps/design/2.html.
 *  - Status card: agent name + IP + sync indicator + latency.
 *  - Volume card: large display-lg percent + mute toggle + slider.
 *  - Output devices: card list with leading icon + state label + radio dot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioScreen(
    address: String,
    machineName: String,
    onOpenDevice: () -> Unit,
    onOpenFiles: (String, String) -> Unit,
    viewModel: AudioViewModel = viewModel(factory = AudioViewModel.factory(address, machineName)),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val updatedError = rememberUpdatedState(uiState.errorMessage)

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
        machineName = machineName,
        onOpenDevice = onOpenDevice,
    )
}

/**
 * Stateless inner Composable for tests.
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
    machineName: String = "",
    onOpenDevice: () -> Unit = {},
) {
    val masterVolume = (state.audioState?.masterVolume ?: 0.0).toFloat() * 100f
    var sliderValue by remember(masterVolume) { mutableFloatStateOf(masterVolume) }
    var localDragging by remember { mutableStateOf(false) }
    val sliderEnabled = !state.isRefreshing && (!state.isVolumeBusy || localDragging)

    MaimaiScreenScaffold(
        topBarActions = {
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.testTag(AudioScreenTags.REFRESH_BUTTON),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.testTag(AudioScreenTags.SNACKBAR_HOST),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CurrentDeviceCard(
                    machineName = machineName,
                    address = state.address,
                    onSwitchDevice = onOpenDevice,
                    statusText = if (state.isRefreshing) "正在刷新" else "已连接",
                )
            }
            item {
                MasterVolumeCard(
                    sliderValue = sliderValue,
                    enabled = sliderEnabled,
                    muted = state.audioState?.muted == true,
                    onSliderChange = {
                        if (!localDragging) {
                            localDragging = true
                            onVolumeDragStart()
                        }
                        sliderValue = it
                        onVolumeChange(it)
                    },
                    onSliderChangeFinished = {
                        if (localDragging) {
                            localDragging = false
                            onVolumeDragEnd()
                        }
                        onSetVolume(sliderValue)
                    },
                    onSetMuted = onSetMuted,
                )
            }
            item {
                Text(
                    "输出设备",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
            items(state.devices, key = { it.id }) { device ->
                DeviceCard(device = device, onSwitchDevice = onSwitchDevice)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun StatusCard(
    machineName: String,
    address: String,
    isLive: Boolean,
) {
    BentoCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (machineName.isNotBlank()) "$machineName ($address)" else address,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (isLive) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "实时同步",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "刷新中…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MasterVolumeCard(
    sliderValue: Float,
    enabled: Boolean,
    muted: Boolean,
    onSliderChange: (Float) -> Unit,
    onSliderChangeFinished: () -> Unit,
    onSetMuted: (Boolean) -> Unit,
) {
    BentoCard(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "主音量",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${sliderValue.toInt()}%",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag(AudioScreenTags.VOLUME_PERCENT),
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconToggleButton(
                    checked = muted,
                    onCheckedChange = onSetMuted,
                    modifier = Modifier.testTag(AudioScreenTags.MUTE_TOGGLE),
                ) {
                    Icon(
                        imageVector = if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        contentDescription = if (muted) "已静音" else "未静音",
                        tint = if (muted) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Slider(
                    value = sliderValue,
                    onValueChange = onSliderChange,
                    onValueChangeFinished = onSliderChangeFinished,
                    valueRange = 0f..100f,
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(AudioScreenTags.VOLUME_SLIDER),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
                Icon(
                    imageVector = Icons.Filled.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(device: AudioDevice, onSwitchDevice: (String) -> Unit) {
    val isDisconnected = device.state.equals("unplugged", ignoreCase = true) ||
        device.state.equals("notpresent", ignoreCase = true)
    val clickable = !device.isDefault && !isDisconnected

    BentoCard(
        modifier = Modifier
            .fillMaxWidth()
            .let {
                if (clickable) {
                    it.clickable { onSwitchDevice(device.id) }
                } else {
                    it
                }
            }
            .let {
                if (device.isDefault) {
                    it.border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large)
                } else {
                    it
                }
            },
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = if (device.isDefault) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = iconForDevice(device),
                    contentDescription = null,
                    tint = if (device.isDefault) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isDisconnected) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = describeDeviceState(device),
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        device.isDefault -> MaterialTheme.colorScheme.primary
                        isDisconnected -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            // Radio dot indicator
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .border(
                        width = 2.dp,
                        color = when {
                            device.isDefault -> MaterialTheme.colorScheme.primary
                            isDisconnected -> MaterialTheme.colorScheme.outlineVariant
                            else -> MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (device.isDefault) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }
        }
    }
}

private fun iconForDevice(device: AudioDevice): ImageVector {
    val name = device.name.lowercase()
    return when {
        "headset" in name || "headphone" in name || "耳机" in device.name -> Icons.Filled.Headset
        "virtual" in name || "cast" in name -> Icons.Filled.Cast
        else -> Icons.Filled.Speaker
    }
}

@Composable
private fun describeDeviceState(device: AudioDevice): String = when {
    device.isDefault -> "默认设备"
    device.state.equals("active", ignoreCase = true) -> "就绪"
    device.state.equals("disabled", ignoreCase = true) -> "已禁用"
    device.state.equals("unplugged", ignoreCase = true) -> "已断开"
    device.state.equals("notpresent", ignoreCase = true) -> "不可用"
    else -> device.state
}
