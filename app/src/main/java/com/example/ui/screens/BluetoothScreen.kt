package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.LinkOff
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
import com.example.ble.ColorOrder
import com.example.ble.ProtocolProfile
import com.example.ui.MainUiState
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.CockpitBorder
import com.example.ui.theme.CockpitSurface
import com.example.ui.theme.CockpitSurfaceElevated
import com.example.ui.theme.CoralError
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SpotifyGreen
import com.example.ui.theme.TextSecondary

@Composable
fun BluetoothScreen(
    state: MainUiState,
    isWidescreen: Boolean,
    hasOverlayPermission: Boolean,
    isIgnoringBatteryOpt: Boolean,
    onRequestScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectDevice: (String, String) -> Unit,
    onDisconnectDevice: (String) -> Unit,
    onDisconnectAll: () -> Unit,
    onTestDevice: (String) -> Unit,
    onSelectProtocol: (ProtocolProfile) -> Unit,
    onSelectDeviceProtocol: (String, ProtocolProfile) -> Unit,
    onSelectColorOrder: (ColorOrder) -> Unit,
    onSelectDeviceColorOrder: (String, ColorOrder?) -> Unit,
    onTogglePureCalibration: (Boolean) -> Unit,
    onToggleAutoConnect: (Boolean) -> Unit,
    onToggleAutoOpenOnCarStart: (Boolean) -> Unit,
    onToggleAutoEnableBluetooth: (Boolean) -> Unit,
    onEnableBluetoothNow: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
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
                    onTestDevice = onTestDevice,
                    onSelectDeviceProtocol = onSelectDeviceProtocol,
                    onSelectDeviceColorOrder = onSelectDeviceColorOrder,
                    onToggleAutoConnect = onToggleAutoConnect,
                    onToggleAutoOpenOnCarStart = onToggleAutoOpenOnCarStart,
                    onToggleAutoEnableBluetooth = onToggleAutoEnableBluetooth,
                    onEnableBluetoothNow = onEnableBluetoothNow,
                )
                Ts10AutoStartPermissionsCard(
                    hasOverlayPermission = hasOverlayPermission,
                    isIgnoringBatteryOpt = isIgnoringBatteryOpt,
                    onRequestOverlayPermission = onRequestOverlayPermission,
                    onRequestBatteryExemption = onRequestBatteryExemption,
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
                onTestDevice = onTestDevice,
                onSelectDeviceProtocol = onSelectDeviceProtocol,
                onSelectDeviceColorOrder = onSelectDeviceColorOrder,
                onToggleAutoConnect = onToggleAutoConnect,
                onToggleAutoOpenOnCarStart = onToggleAutoOpenOnCarStart,
                onToggleAutoEnableBluetooth = onToggleAutoEnableBluetooth,
                onEnableBluetoothNow = onEnableBluetoothNow,
            )
            Ts10AutoStartPermissionsCard(
                hasOverlayPermission = hasOverlayPermission,
                isIgnoringBatteryOpt = isIgnoringBatteryOpt,
                onRequestOverlayPermission = onRequestOverlayPermission,
                onRequestBatteryExemption = onRequestBatteryExemption,
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
    onTestDevice: (String) -> Unit,
    onSelectDeviceProtocol: (String, ProtocolProfile) -> Unit,
    onSelectDeviceColorOrder: (String, ColorOrder?) -> Unit,
    onToggleAutoConnect: (Boolean) -> Unit,
    onToggleAutoOpenOnCarStart: (Boolean) -> Unit,
    onToggleAutoEnableBluetooth: (Boolean) -> Unit,
    onEnableBluetoothNow: () -> Unit,
) {
    val ble = state.ble
    val activeNodes = ble.devicesByAddress.values.toList()

    Surface(
        shape = MaterialTheme.shapes.large,
        color = CockpitSurface,
        border = BorderStroke(
            1.dp,
            when {
                !ble.isBluetoothEnabled -> AmberWarn.copy(alpha = 0.7f)
                ble.isConnected -> SpotifyGreen.copy(alpha = 0.6f)
                else -> CockpitBorder
            },
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
                            !ble.isBluetoothEnabled -> Icons.Rounded.BluetoothDisabled
                            ble.isConnected -> Icons.Rounded.BluetoothConnected
                            ble.isScanning -> Icons.Rounded.BluetoothSearching
                            else -> Icons.Rounded.Bluetooth
                        },
                        contentDescription = null,
                        tint = when {
                            !ble.isBluetoothEnabled -> AmberWarn
                            ble.isConnected -> SpotifyGreen
                            else -> NeonCyan
                        },
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = when {
                                !ble.isBluetoothEnabled -> "Bluetooth Apagado"
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

            if (!ble.isBluetoothEnabled) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = AmberWarn.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, AmberWarn.copy(alpha = 0.65f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "Activa el Bluetooth de la radio para conectar automáticamente puertas y reposapiés.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AmberWarn,
                        )
                        Button(
                            onClick = onEnableBluetoothNow,
                            colors = ButtonDefaults.buttonColors(containerColor = AmberWarn),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("ble_enable_now_button"),
                        ) {
                            Text(
                                text = "Activar Bluetooth ahora",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            if (activeNodes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    activeNodes.forEachIndexed { index, node ->
                        ActiveControllerItemCard(
                            index = index,
                            node = node,
                            globalSelectedProfile = ble.selectedProfile,
                            deviceOverrideProfile = state.prefs.deviceProfiles[node.address] ?: ProtocolProfile.AUTO,
                            deviceOverrideColorOrder = state.prefs.deviceColorOrders[node.address],
                            onTestDevice = onTestDevice,
                            onDisconnectDevice = onDisconnectDevice,
                            onSelectDeviceProtocol = onSelectDeviceProtocol,
                            onSelectDeviceColorOrder = onSelectDeviceColorOrder,
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Activar Bluetooth con la app",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Lo enciende automáticamente en Android ≤ 12 o lo solicita en 1 toque",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                Switch(
                    checked = state.prefs.autoEnableBluetooth,
                    onCheckedChange = onToggleAutoEnableBluetooth,
                    modifier = Modifier.testTag("ble_autoenable_switch"),
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
