package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SignalCellularAlt
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ble.BleConnectionStatus
import com.example.ble.ColorOrder
import com.example.ble.ConnectedDeviceInfo
import com.example.ble.ProtocolProfile
import com.example.ble.ScannedBleDevice
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.CockpitBorder
import com.example.ui.theme.CockpitSurface
import com.example.ui.theme.CockpitSurfaceElevated
import com.example.ui.theme.CoralError
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SpotifyGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActiveControllerItemCard(
    index: Int,
    node: ConnectedDeviceInfo,
    globalSelectedProfile: ProtocolProfile,
    deviceOverrideProfile: ProtocolProfile,
    deviceOverrideColorOrder: ColorOrder?,
    onTestDevice: (String) -> Unit,
    onDisconnectDevice: (String) -> Unit,
    onSelectDeviceProtocol: (String, ProtocolProfile) -> Unit,
    onSelectDeviceColorOrder: (String, ColorOrder?) -> Unit,
) {
    val connected = node.status == BleConnectionStatus.CONNECTED
    val charShort = node.activeCharUuid
        ?.takeIf { it.length >= 8 }
        ?.substring(4, 8)
        ?.uppercase()
        ?: "--"
    val effectiveProfile = when {
        deviceOverrideProfile != ProtocolProfile.AUTO -> deviceOverrideProfile
        globalSelectedProfile != ProtocolProfile.AUTO -> globalSelectedProfile
        else -> node.detectedProfile
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = CockpitSurfaceElevated,
        border = BorderStroke(
            1.dp,
            if (connected) SpotifyGreen.copy(alpha = 0.55f) else AmberWarn.copy(alpha = 0.55f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${node.name} (#${index + 1})",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (connected) SpotifyGreen else AmberWarn,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${node.address} • ${if (connected) "Conectado" else "Conectando…"} • UUID $charShort • ${effectiveProfile.shortChipLabel()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                    if (!node.lastTxHex.isNullOrBlank()) {
                        Text(
                            text = "TX: ${node.lastTxHex}",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonCyan,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedButton(
                        onClick = { onTestDevice(node.address) },
                        enabled = connected,
                        border = BorderStroke(
                            1.dp,
                            if (connected) NeonCyan else CockpitBorder,
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("test_device_${node.address}"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FlashOn,
                            contentDescription = "Probar ${node.name}",
                            tint = if (connected) NeonCyan else TextMuted,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Probar",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (connected) NeonCyan else TextMuted,
                        )
                    }

                    IconButton(
                        onClick = { onDisconnectDevice(node.address) },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("disconnect_${node.address}"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Quitar ${node.name}",
                            tint = CoralError,
                        )
                    }
                }
            }

            Text(
                text = "Protocolo de este controlador:",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ProtocolProfile.entries.forEach { profile ->
                    val label = if (profile == ProtocolProfile.AUTO) {
                        "Auto (${node.detectedProfile.shortChipLabel()})"
                    } else {
                        profile.shortChipLabel()
                    }
                    FilterChip(
                        selected = deviceOverrideProfile == profile,
                        onClick = { onSelectDeviceProtocol(node.address, profile) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("device_protocol_${node.address}_${profile.id}"),
                    )
                }
            }

            Text(
                text = "Orden RGB de este controlador:",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            val perDeviceOrders: List<ColorOrder?> = listOf(
                null,
                ColorOrder.RGB,
                ColorOrder.GRB,
                ColorOrder.BRG,
                ColorOrder.BGR,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                perDeviceOrders.forEach { order ->
                    FilterChip(
                        selected = deviceOverrideColorOrder == order,
                        onClick = { onSelectDeviceColorOrder(node.address, order) },
                        label = {
                            Text(
                                text = order?.label ?: "Global",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        modifier = Modifier.testTag(
                            "device_order_${node.address}_${order?.id ?: "GLOBAL"}",
                        ),
                    )
                }
            }
        }
    }
}

internal fun ProtocolProfile.shortChipLabel(): String = when (this) {
    ProtocolProfile.AUTO -> "Auto"
    ProtocolProfile.LEDCAR_01 -> "LEDCAR Dual 7B+7E"
    ProtocolProfile.LEDCAR_01_ONLY_7B -> "Solo Tiras 7B"
    ProtocolProfile.LEDCAR_01_ALT -> "LEDCAR Alt 04"
    ProtocolProfile.ELK_LED_LAMP -> "ELK / Pies 7E"
    ProtocolProfile.BJ_LED -> "BJ 69 96"
    ProtocolProfile.TRIONES -> "Triones"
    ProtocolProfile.SP110E -> "SP110E"
}

@Composable
internal fun Ts10AutoStartPermissionsCard(
    hasOverlayPermission: Boolean,
    isIgnoringBatteryOpt: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = CockpitSurface,
        border = BorderStroke(
            1.dp,
            if (hasOverlayPermission && isIgnoringBatteryOpt) SpotifyGreen.copy(alpha = 0.55f)
            else AmberWarn.copy(alpha = 0.65f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Permisos Radio TS10 (Auto-Inicio)", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "En Android de coche, estos 2 permisos permiten abrir la app al dar contacto y evitar que el sistema desconecte el Bluetooth en reposo.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Mostrar sobre otras apps", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (hasOverlayPermission) "Concedido (puede abrirse sola)"
                        else "Requerido para abrirse al encender",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasOverlayPermission) SpotifyGreen else AmberWarn,
                    )
                }
                if (!hasOverlayPermission) {
                    OutlinedButton(
                        onClick = onRequestOverlayPermission,
                        border = BorderStroke(1.dp, AmberWarn),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("grant_overlay_button"),
                    ) {
                        Text("Permitir", color = AmberWarn)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sin ahorro de batería", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (isIgnoringBatteryOpt) "Activo (BLE estable en segundo plano)"
                        else "Evita que la radio cierre la app al dormir",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isIgnoringBatteryOpt) SpotifyGreen else AmberWarn,
                    )
                }
                if (!isIgnoringBatteryOpt) {
                    OutlinedButton(
                        onClick = onRequestBatteryExemption,
                        border = BorderStroke(1.dp, AmberWarn),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("grant_battery_button"),
                    ) {
                        Text("Permitir", color = AmberWarn)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DiscoveredDevicesCard(
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
internal fun ColorCalibrationCard(
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
                text = "Orden de canales global (si ves rojo como verde, cambia a GRB):",
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
internal fun ProtocolSelectorCard(
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
            Text("Protocolo Global Tira Symphony", style = MaterialTheme.typography.titleLarge)
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
