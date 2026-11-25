package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.ble.ColorOrder
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
    onDisconnectDevice: (String) -> Unit,
    onDisconnectAll: () -> Unit,
    onSelectProtocol: (ProtocolProfile) -> Unit,
    onSelectColorOrder: (ColorOrder) -> Unit,
    onTogglePureCalibration: (Boolean) -> Unit,
    onToggleAutoConnect: (Boolean) -> Unit,
    onToggleAutoOpenOnCarStart: (Boolean) -> Unit,
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
                    onDisconnectDevice = onDisconnectDevice,
                    onDisconnectAll = onDisconnectAll,
                    onToggleAutoConnect = onToggleAutoConnect,
                    onToggleAutoOpenOnCarStart = onToggleAutoOpenOnCarStart,
                )
                ColorCalibrationCard(
                    colorOrder = state.prefs.colorOrder,
                    pureCalibration = state.prefs.pureLedCalibration,
                    onSelectColorOrder = onSelectColorOrder,
                    onTogglePureCalibration = onTogglePureCalibration,
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
                devicesByAddress = ble.devicesByAddress.mapValues { it.value.status },
                onConnect = onConnectDevice,
                onDisconnectDevice = onDisconnectDevice,
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
                onDisconnectDevice = onDisconnectDevice,
                onDisconnectAll = onDisconnectAll,
                onToggleAutoConnect = onToggleAutoConnect,
                onToggleAutoOpenOnCarStart = onToggleAutoOpenOnCarStart,
            )
            DiscoveredDevicesCard(
                devices = ble.discoveredDevices,
                isScanning = ble.isScanning,
                devicesByAddress = ble.devicesByAddress.mapValues { it.value.status },
                onConnect = onConnectDevice,
                onDisconnectDevice = onDisconnectDevice,
            )
            ColorCalibrationCard(
                colorOrder = state.prefs.colorOrder,
                pureCalibration = state.prefs.pureLedCalibration,
                onSelectColorOrder = onSelectColorOrder,
                onTogglePureCalibration = onTogglePureCalibration,
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
    onDisconnectDevice: (String) -> Unit,
    onDisconnectAll: () -> Unit,
    onToggleAutoConnect: (Boolean) -> Unit,
    onToggleAutoOpenOnCarStart: (Boolean) -> Unit,
) {
    val ble = state.ble
    val activeNodes = ble.devicesByAddress.values.toList()

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
                            text = when {
                                ble.connectedCount > 1 -> "${ble.connectedCount} Controladores LED Conectados"
                                ble.connectedCount == 1 -> "${ble.connectedName ?: "LEDCAR"} conectado"
                                else -> "Controladores Symphony BLE"
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = if (state.prefs.savedBleDevices.isNotEmpty()) {
                                "Guardados: ${state.prefs.savedBleDevices.joinToString(" + ") { it.name }}"
                            } else {
                                "Toca ambos LEDCAR-01 para conectarlos a la vez"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                }
            }

            if (activeNodes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    activeNodes.forEach { node ->
                        val connected = node.status == BleConnectionStatus.CONNECTED
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = CockpitSurfaceElevated,
                            border = BorderStroke(
                                1.dp,
                                if (connected) SpotifyGreen.copy(alpha = 0.55f) else AmberWarn.copy(alpha = 0.55f),
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${node.name} (${node.detectedProfile.id})",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (connected) SpotifyGreen else AmberWarn,
                                    )
                                    Text(
                                        text = "${node.address} • ${
                                            if (connected) "Conectado" else "Conectando…"
                                        }",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted,
                                    )
                                }
                                IconButton(
                                    onClick = { onDisconnectDevice(node.address) },
                                    modifier = Modifier.testTag("disconnect_${node.address}"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Quitar ${node.name}",
                                        tint = CoralError,
                                    )
                                }
                            }
                        }
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
                    text = "Auto-conectar ambos al abrir",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.prefs.autoConnectBle,
                    onCheckedChange = onToggleAutoConnect,
                    modifier = Modifier.testTag("ble_autoconnect_switch"),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Abrir y conectar al arrancar el coche",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Recupera el último modo, brillo y color guardados",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                Switch(
                    checked = state.prefs.autoOpenOnCarStart,
                    onCheckedChange = onToggleAutoOpenOnCarStart,
                    modifier = Modifier.testTag("ble_autoopen_switch"),
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

                if (activeNodes.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onDisconnectAll,
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
    devicesByAddress: Map<String, BleConnectionStatus>,
    onConnect: (String, String) -> Unit,
    onDisconnectDevice: (String) -> Unit,
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
            Text(
                text = "Toca varios dispositivos (ej. ambos LEDCAR-01) para controlar puertas y reposapiés a la vez.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            if (devices.isEmpty()) {
                Text(
                    text = if (isScanning) {
                        "Buscando módulos LED (LEDCAR-01, ELK, BJ_LED)…"
                    } else {
                        "Pulsa \"Buscar Tiras LED\" con las luces del coche encendidas."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            } else {
                devices.forEach { device ->
                    val status = devicesByAddress[device.address]
                    val isThisConnected = status == BleConnectionStatus.CONNECTED
                    val isConnecting = status == BleConnectionStatus.CONNECTING ||
                        status == BleConnectionStatus.DISCOVERING

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ble_device_${device.address}")
                            .clickable {
                                if (isThisConnected || isConnecting) {
                                    onDisconnectDevice(device.address)
                                } else {
                                    onConnect(device.address, device.name)
                                }
                            },
                        shape = MaterialTheme.shapes.medium,
                        color = when {
                            isThisConnected -> SpotifyGreen.copy(alpha = 0.14f)
                            isConnecting -> AmberWarn.copy(alpha = 0.12f)
                            else -> CockpitSurfaceElevated
                        },
                        border = BorderStroke(
                            1.dp,
                            when {
                                isThisConnected -> SpotifyGreen
                                isConnecting -> AmberWarn
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
                                } else if (isConnecting) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = AmberWarn,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorCalibrationCard(
    colorOrder: ColorOrder,
    pureCalibration: Boolean,
    onSelectColorOrder: (ColorOrder) -> Unit,
    onTogglePureCalibration: (Boolean) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = CockpitSurface,
        border = BorderStroke(1.dp, CockpitBorder),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Palette, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Calibración de Color Tiras LED", style = MaterialTheme.typography.titleLarge)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Corrección Rojo/Verde Puro", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Evita que el rojo salga rosa o naranja en las tiras",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
                Switch(
                    checked = pureCalibration,
                    onCheckedChange = onTogglePureCalibration,
                    modifier = Modifier.testTag("pure_led_cal_switch"),
                )
            }

            Text(
                text = "Orden de canales de tus tiras (si ves rojo como verde, cambia a GRB):",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ColorOrder.entries.forEach { order ->
                    FilterChip(
                        selected = colorOrder == order,
                        onClick = { onSelectColorOrder(order) },
                        label = { Text(order.label) },
                        modifier = Modifier.testTag("color_order_${order.id}"),
                    )
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
