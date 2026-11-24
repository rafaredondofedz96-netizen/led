package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ble.BleConnectionStatus
import com.example.ble.ProtocolProfile
import com.example.ble.ScannedBleDevice
import com.example.ui.MainUiState
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.CockpitBorder
import com.example.ui.theme.CockpitSurface
import com.example.ui.theme.CockpitSurfaceElevated
import com.example.ui.theme.CoralError
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SpotifyGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary

@Composable
fun BluetoothScreen(
    state: MainUiState,
    isWidescreen: Boolean,
    onRequestScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectDevice: (String, String) -> Unit,
    onDisconnect: () -> Unit,
    onSelectProtocol: (ProtocolProfile) -> Unit,
    onToggleAutoConnect: (Boolean) -> Unit,
) {
    val ble = state.ble
    if (isWidescreen) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConnectionStatusCard(
                    state = state,
                    onRequestScan = onRequestScan,
                    onStopScan = onStopScan,
                    onDisconnect = onDisconnect,
                    onToggleAutoConnect = onToggleAutoConnect,
                )
                ProtocolSelectorCard(
                    selected = ble.selectedProfile,
                    detected = ble.detectedProfile,
                    onSelect = onSelectProtocol,
                )
            }
            DiscoveredDevicesCard(
                devices = ble.discoveredDevices,
                isScanning = ble.isScanning,
                connectedAddress = ble.connectedAddress,
                isConnected = ble.isConnected,
                onConnect = onConnectDevice,
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ConnectionStatusCard(
                state = state,
                onRequestScan = onRequestScan,
                onStopScan = onStopScan,
                onDisconnect = onDisconnect,
                onToggleAutoConnect = onToggleAutoConnect,
            )
            DiscoveredDevicesCard(
                devices = ble.discoveredDevices,
                isScanning = ble.isScanning,
                connectedAddress = ble.connectedAddress,
                isConnected = ble.isConnected,
                onConnect = onConnectDevice,
            )
            ProtocolSelectorCard(
                selected = ble.selectedProfile,
                detected = ble.detectedProfile,
                onSelect = onSelectProtocol,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    state: MainUiState,
    onRequestScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleAutoConnect: (Boolean) -> Unit,
) {
    val ble = state.ble
    Surface(
        shape = MaterialTheme.shapes.large,
        color = CockpitSurface,
        border = BorderStroke(
            1.dp,
            if (ble.isConnected) SpotifyGreen.copy(alpha = 0.6f) else CockpitBorder,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            ble.isConnected -> Icons.Rounded.BluetoothConnected
                            ble.isScanning -> Icons.Rounded.BluetoothSearching
                            else -> Icons.Rounded.Bluetooth
                        },
                        contentDescription = null,
                        tint = if (ble.isConnected) SpotifyGreen else NeonCyan,
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = ble.connectedName ?: state.prefs.lastBleName.ifBlank { "Controlador Symphony BLE" },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = when (ble.status) {
                                BleConnectionStatus.CONNECTED ->
                                    "Conectado • UUID ${ble.activeCharUuid?.take(8) ?: "---"}"
                                BleConnectionStatus.CONNECTING -> "Conectando GATT…"
                                BleConnectionStatus.DISCOVERING -> "Buscando característica LED…"
                                BleConnectionStatus.DISCONNECTED ->
                                    if (state.prefs.lastBleAddress.isNotBlank()) {
                                        "Guardado: ${state.prefs.lastBleAddress}"
                                    } else "Sin dispositivo emparejado"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                }
            }

            if (ble.isConnected) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = CockpitSurfaceElevated,
                    border = BorderStroke(1.dp, CockpitBorder),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "TX: ${ble.lastTxHex}",
                            style = MaterialTheme.typography.labelMedium,
                            color = NeonCyan,
                        )
                        Text(
                            text = "${ble.lastWriteLatencyMs} ms",
                            style = MaterialTheme.typography.labelMedium,
                            color = SpotifyGreen,
                        )
                    }
                }
            }

            if (!ble.statusMessage.isNullOrBlank()) {
                Text(
                    text = ble.statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CoralError,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Auto-conectar al abrir la radio",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(
                    checked = state.prefs.autoConnectBle,
                    onCheckedChange = onToggleAutoConnect,
                    modifier = Modifier.testTag("ble_autoconnect_switch"),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = if (ble.isScanning) onStopScan else onRequestScan,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (ble.isScanning) AmberWarn else NeonCyan,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("ble_scan_button"),
                ) {
                    if (ble.isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.stop_scan), color = Color.Black, fontWeight = FontWeight.Bold)
                    } else {
                        Text(stringResource(R.string.scan_devices), color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }

                if (ble.isConnected || ble.status == BleConnectionStatus.CONNECTING) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        border = BorderStroke(1.dp, CoralError),
                        modifier = Modifier
                            .height(50.dp)
                            .testTag("ble_disconnect_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LinkOff,
                            contentDescription = null,
                            tint = CoralError,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.disconnect), color = CoralError)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveredDevicesCard(
    devices: List<ScannedBleDevice>,
    isScanning: Boolean,
    connectedAddress: String?,
    isConnected: Boolean,
    onConnect: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = CockpitSurface,
        border = BorderStroke(1.dp, CockpitBorder),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Dispositivos BLE Cercanos (${devices.size})",
                style = MaterialTheme.typography.titleLarge,
            )
            if (devices.isEmpty()) {
                Text(
                    text = if (isScanning) {
                        "Buscando módulos LED (CAR, ELK-BLEDOM, BJ_LED, Symphony)…"
                    } else {
                        "Pulsa \"Buscar Tiras LED\" con las luces del coche encendidas."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            } else {
                devices.forEach { device ->
                    val isThisConnected = isConnected && connectedAddress == device.address
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ble_device_${device.address}")
                            .clickable { onConnect(device.address, device.name) },
                        shape = MaterialTheme.shapes.medium,
                        color = if (isThisConnected) SpotifyGreen.copy(alpha = 0.14f) else CockpitSurfaceElevated,
                        border = BorderStroke(
                            1.dp,
                            when {
                                isThisConnected -> SpotifyGreen
                                device.isLikelyLedController -> NeonCyan.copy(alpha = 0.55f)
                                else -> CockpitBorder
                            },
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = device.name,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    if (device.isLikelyLedController) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            shape = MaterialTheme.shapes.small,
                                            color = NeonCyan.copy(alpha = 0.16f),
                                        ) {
                                            Text(
                                                text = "LED",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = NeonCyan,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = device.address,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextMuted,
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.SignalCellularAlt,
                                    contentDescription = null,
                                    tint = if (device.rssi > -75) SpotifyGreen else AmberWarn,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${device.rssi}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary,
                                )
                                if (isThisConnected) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = "Conectado",
                                        tint = SpotifyGreen,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtocolSelectorCard(
    selected: ProtocolProfile,
    detected: ProtocolProfile,
    onSelect: (ProtocolProfile) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = CockpitSurface,
        border = BorderStroke(1.dp, CockpitBorder),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Protocolo Tira Symphony", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Auto detectado: ${detected.displayName}",
                style = MaterialTheme.typography.bodyMedium,
                color = NeonCyan,
            )
            ProtocolProfile.entries.forEach { profile ->
                val isSelected = selected == profile
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("protocol_${profile.id}")
                        .clickable { onSelect(profile) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Rounded.RadioButtonChecked
                        else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSelected) NeonCyan else TextMuted,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(profile.subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    }
                }
            }
        }
    }
}
