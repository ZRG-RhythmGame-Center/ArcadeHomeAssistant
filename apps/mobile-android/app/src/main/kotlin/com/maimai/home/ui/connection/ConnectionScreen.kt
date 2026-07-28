package com.maimai.home.ui.connection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maimai.home.ServiceLocator
import com.maimai.home.data.DiscoveredService
import com.maimai.home.data.KnownDevice
import com.maimai.home.data.models.AgentStatus
import com.maimai.home.ui.common.BentoCard
import com.maimai.home.ui.common.BentoCardTitle
import com.maimai.home.ui.common.MaimaiScreenScaffold

/**
 * ConnectionScreen wires the ViewModel and forwards the manual "进入设备"
 * action to [onConnected]. Discovered services auto-verify via the
 * discoveryNavigation channel.
 *
 * Renders the bento-box connection layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    onConnected: (String, String) -> Unit,
    viewModel: ConnectionViewModel = viewModel(factory = ConnectionViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val knownDevices by ServiceLocator.preferences.knownDevicesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(viewModel) {
        viewModel.discoveryNavigation.collect { event ->
            onConnected(event.address, event.machineName)
            viewModel.clearConnectedStatus()
        }
    }
    ConnectionScreenContent(
        state = uiState,
        knownDevices = knownDevices,
        onUpdateAddress = viewModel::updateAddress,
        onTestConnection = viewModel::testConnection,
        onScanLan = viewModel::scanLan,
        onUseDiscoveredService = viewModel::useDiscoveredService,
        onUseKnownDevice = { address ->
            viewModel.updateAddress(address)
            viewModel.testConnection()
        },
        onRemoveKnownDevice = { address ->
            viewModel.removeKnownDevice(address)
        },
        onEnterDevice = { address, machineName ->
            onConnected(address, machineName)
            viewModel.clearConnectedStatus()
        },
    )
}

/**
 * Stateless inner Composable. Test-friendly entry point.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionScreenContent(
    state: ConnectionUiState,
    knownDevices: List<KnownDevice> = emptyList(),
    onUpdateAddress: (String) -> Unit,
    onTestConnection: () -> Unit,
    onScanLan: () -> Unit,
    onUseDiscoveredService: (DiscoveredService) -> Unit,
    onUseKnownDevice: (String) -> Unit = {},
    onRemoveKnownDevice: (String) -> Unit = {},
    onEnterDevice: (String, String) -> Unit,
) {
    MaimaiScreenScaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "设备管理",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
            state.connectedStatus?.let { status ->
                ConnectedStatusCard(
                    status = status,
                    address = state.address,
                    onEnterDevice = onEnterDevice,
                )
            }
            AutoDiscoveryCard(
                isScanning = state.isScanning,
                discovered = state.discovered,
                onScanLan = onScanLan,
                onUseDiscoveredService = onUseDiscoveredService,
            )
            if (knownDevices.isNotEmpty()) {
                KnownDevicesCard(
                    devices = knownDevices,
                    onUseDevice = onUseKnownDevice,
                    onRemoveDevice = onRemoveKnownDevice,
                )
            }
            ManualConnectCard(
                address = state.address,
                isTesting = state.isTesting,
                errorMessage = if (state.address.isBlank()) null else state.errorMessage,
                onUpdateAddress = onUpdateAddress,
                onTestConnection = onTestConnection,
            )
            // Generic error card (when no connectedStatus to show alongside).
            if (state.connectedStatus == null && !state.errorMessage.isNullOrBlank()) {
                BentoCard {
                    Text(
                        state.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AutoDiscoveryCard(
    isScanning: Boolean,
    discovered: List<DiscoveredService>,
    onScanLan: () -> Unit,
    onUseDiscoveredService: (DiscoveredService) -> Unit,
) {
    BentoCard {
        BentoCardTitle(
            text = "发现设备",
            leadingIcon = Icons.Filled.Radar,
            trailing = {
                if (isScanning) {
                    ScanningPill()
                } else {
                    AssistChip(
                        onClick = onScanLan,
                        label = { Text("重新扫描") },
                        leadingIcon = {
                            Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f),
                            labelColor = MaterialTheme.colorScheme.primary,
                            leadingIconContentColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            },
        )
        Spacer(Modifier.height(16.dp))
        if (discovered.isEmpty()) {
            DiscoveryEmptyState(isScanning = isScanning, onScanLan = onScanLan)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                discovered.forEach { service ->
                    DiscoveredDeviceRow(
                        service = service,
                        onClick = { onUseDiscoveredService(service) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanningPill() {
    Row(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.16f),
                shape = CircleShape,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "正在搜索网络…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun DiscoveryEmptyState(
    isScanning: Boolean,
    onScanLan: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
    ) {
        Text(
            text = if (isScanning) "正在扫描局域网…" else "未发现任何 Agent",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isScanning) {
            Text(
                text = "请确认 Agent 已运行，且与本机在同一网段。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            FilledTonalButton(onClick = onScanLan) {
                Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("立即扫描")
            }
        }
    }
}

@Composable
private fun DiscoveredDeviceRow(
    service: DiscoveredService,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                MaterialTheme.shapes.medium,
            )
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.SettingsRemote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                service.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                service.address,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            service.version?.takeIf { it.isNotBlank() }?.let { v ->
                Text(
                    text = "v$v",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(
            onClick = onClick,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Text("连接")
        }
    }
}

@Composable
private fun ManualConnectCard(
    address: String,
    isTesting: Boolean,
    errorMessage: String?,
    onUpdateAddress: (String) -> Unit,
    onTestConnection: () -> Unit,
) {
    BentoCard {
        BentoCardTitle(
            text = "手动添加设备",
            leadingIcon = Icons.Filled.Keyboard,
            leadingIconTint = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = address,
            onValueChange = onUpdateAddress,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("地址") },
            placeholder = { Text("192.168.x.x:8765") },
            singleLine = true,
            isError = !errorMessage.isNullOrBlank(),
            supportingText = if (!errorMessage.isNullOrBlank()) {
                { Text(errorMessage, color = MaterialTheme.colorScheme.error) }
            } else null,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onTestConnection,
            enabled = !isTesting && address.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text("测试中…")
            } else {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("发起连接")
            }
        }
    }
}

@Composable
private fun ConnectedStatusCard(
    status: AgentStatus,
    address: String,
    onEnterDevice: (String, String) -> Unit,
) {
    BentoCard {
        BentoCardTitle(
            text = "当前设备",
            leadingIcon = Icons.Filled.Check,
            leadingIconTint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        InfoRow("设备", status.machineName)
        InfoRow("版本", status.version)
        InfoRow("已运行", "${status.uptimeSeconds} s")
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            CapabilityChip("音量", status.capabilities.audioVolume)
            CapabilityChip("静音", status.capabilities.audioMute)
            CapabilityChip("切设备", status.capabilities.audioDeviceSwitch)
            CapabilityChip("文件", status.capabilities.fileManagement)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            CapabilityChip("发现", status.capabilities.discoveryBroadcast)
            CapabilityChip("关机", status.capabilities.remoteShutdown)
            CapabilityChip("启动器", status.capabilities.launcher)
            CapabilityChip("设置", status.capabilities.settingsManagement)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onEnterDevice(address, status.machineName) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("开始使用")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CapabilityChip(label: String, enabled: Boolean) {
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = {
            Icon(
                imageVector = if (enabled) Icons.Filled.Check else Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        enabled = enabled,
    )
}

// Shared cards used by unconnected tab states across screens.

@Composable
fun LoadingCard(
    text: String = "加载中…",
    modifier: Modifier = Modifier,
) {
    BentoCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyCard(
    text: String = "暂无内容",
    modifier: Modifier = Modifier,
) {
    BentoCard(modifier = modifier) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ErrorCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    BentoCard(modifier = modifier) {
        Text(text, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun KnownDevicesCard(
    devices: List<KnownDevice>,
    onUseDevice: (String) -> Unit,
    onRemoveDevice: (String) -> Unit,
) {
    BentoCard {
        BentoCardTitle(text = "最近设备", leadingIcon = Icons.Filled.History)
        Spacer(Modifier.height(12.dp))
        devices.forEach { device ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name.ifBlank { device.address },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (device.name.isNotBlank() && device.name != device.address) {
                        Text(
                            text = device.address,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(
                    onClick = { onUseDevice(device.address) },
                    modifier = Modifier.testTag("known-device-connect-" + device.address),
                ) {
                    Text("连接")
                }
                IconButton(
                    onClick = { onRemoveDevice(device.address) },
                    modifier = Modifier.testTag("known-device-remove-" + device.address),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "移除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
