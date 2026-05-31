package com.maimai.home.ui.connection

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maimai.home.R
import com.maimai.home.data.DiscoveredService
import com.maimai.home.data.models.AgentStatus

/**
 * Public ConnectionScreen wires the ViewModel and forwards the manual
 * "进入设备" action to [onConnected]. No auto-navigation: the user must
 * tap the button on the success card (Wave 5 task 26 / R2 B-1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    onConnected: (String, String) -> Unit,
    viewModel: ConnectionViewModel = viewModel(factory = ConnectionViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // M5 / R1#6: discovered service tap silently verifies and navigates.
    // Manual testConnection still requires the explicit "进入设备" button.
    LaunchedEffect(viewModel) {
        viewModel.discoveryNavigation.collect { event ->
            onConnected(event.address, event.machineName)
            viewModel.clearConnectedStatus()
        }
    }
    ConnectionScreenContent(
        state = uiState,
        onUpdateAddress = viewModel::updateAddress,
        onTestConnection = viewModel::testConnection,
        onScanLan = viewModel::scanLan,
        onUseDiscoveredService = viewModel::useDiscoveredService,
        onEnterDevice = { address, machineName ->
            onConnected(address, machineName)
            viewModel.clearConnectedStatus()
        },
    )
}

/**
 * Stateless inner Composable. Takes state + callbacks so Compose UI tests
 * can drive it without spinning up a ViewModel + ServiceLocator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionScreenContent(
    state: ConnectionUiState,
    onUpdateAddress: (String) -> Unit,
    onTestConnection: () -> Unit,
    onScanLan: () -> Unit,
    onUseDiscoveredService: (DiscoveredService) -> Unit,
    onEnterDevice: (String, String) -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.connection_title)) }) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.address,
                    onValueChange = onUpdateAddress,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.connection_address_label)) },
                    placeholder = { Text(stringResource(R.string.connection_address_placeholder)) },
                    singleLine = true,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onTestConnection,
                        enabled = !state.isTesting,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (state.isTesting) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.connection_test_button_loading))
                            }
                        } else {
                            Text(stringResource(R.string.connection_test_button))
                        }
                    }
                    OutlinedButton(
                        onClick = onScanLan,
                        enabled = !state.isScanning,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (state.isScanning) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.connection_scan_button_loading))
                            }
                        } else {
                            Text(stringResource(R.string.connection_scan_button))
                        }
                    }
                }
            }
            item {
                ConnectionInfoCard(
                    error = state.errorMessage,
                    status = state.connectedStatus,
                    address = state.address,
                    onEnterDevice = onEnterDevice,
                )
            }
            item { Text(stringResource(R.string.connection_discovery_title), style = MaterialTheme.typography.titleMedium) }
            if (state.discovered.isEmpty()) {
                item {
                    if (state.isScanning) {
                        LoadingCard(text = stringResource(R.string.connection_discovery_scanning))
                    } else {
                        EmptyCard(text = stringResource(R.string.connection_discovery_empty))
                    }
                }
            } else {
                items(state.discovered) { service ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onUseDiscoveredService(service) }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(service.name, style = MaterialTheme.typography.titleMedium)
                            Text(service.address)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ConnectionInfoCard(
    error: String?,
    status: AgentStatus?,
    address: String,
    onEnterDevice: (String, String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.connection_info_title), style = MaterialTheme.typography.titleMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            status?.let { s ->
                Text(stringResource(R.string.connection_machine_format, s.machineName))
                Text(stringResource(R.string.connection_version_format, s.version))
                Text(stringResource(R.string.connection_uptime_format, s.uptimeSeconds))
                Text(
                    stringResource(
                        R.string.connection_capabilities_format,
                        flag(s.capabilities.audioVolume),
                        flag(s.capabilities.audioMute),
                        flag(s.capabilities.audioDeviceSwitch),
                        flag(s.capabilities.fileManagement),
                        flag(s.capabilities.discoveryBroadcast),
                    ),
                )
                // Wave 5 task 26: explicit "进入设备" button. No auto-navigation.
                Button(
                    onClick = { onEnterDevice(address, s.machineName) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.connection_open_device)) }
            }
        }
    }
}

/**
 * Reusable LoadingCard / EmptyCard / ErrorCard composables (Wave 5 task 26).
 *
 * Public so other screens (Audio, Files) can compose them too.
 */
@Composable
fun LoadingCard(
    text: String = stringResource(R.string.card_loading),
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
            Text(text)
        }
    }
}

@Composable
fun EmptyCard(
    text: String = stringResource(R.string.card_empty_default),
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ErrorCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text(text, color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun flag(value: Boolean): String = if (value) "\u2713" else "\u2717"
