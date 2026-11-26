package com.example.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

enum class BleConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    DISCOVERING,
    CONNECTED,
}

data class ScannedBleDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val isLikelyLedController: Boolean,
)

data class ConnectedDeviceInfo(
    val address: String,
    val name: String,
    val status: BleConnectionStatus,
    val activeCharUuid: String? = null,
    val detectedProfile: ProtocolProfile = ProtocolProfile.LEDCAR_01,
    val lastTxHex: String? = null,
)

data class BleUiState(
    val isBluetoothSupported: Boolean = true,
    val isBluetoothEnabled: Boolean = true,
    val isScanning: Boolean = false,
    val devicesByAddress: Map<String, ConnectedDeviceInfo> = emptyMap(),
    val selectedProfile: ProtocolProfile = ProtocolProfile.AUTO,
    val discoveredDevices: List<ScannedBleDevice> = emptyList(),
    val lastTxHex: String = "--",
    val lastWriteLatencyMs: Long = 0L,
    val statusMessage: String? = null,
) {
    val connectedDevices: List<ConnectedDeviceInfo>
        get() = devicesByAddress.values.filter { it.status == BleConnectionStatus.CONNECTED }

    val connectedCount: Int get() = connectedDevices.size
    val isConnected: Boolean get() = connectedCount > 0

    val status: BleConnectionStatus
        get() = when {
            connectedCount > 0 -> BleConnectionStatus.CONNECTED
            devicesByAddress.values.any { it.status == BleConnectionStatus.DISCOVERING } ->
                BleConnectionStatus.DISCOVERING
            devicesByAddress.values.any { it.status == BleConnectionStatus.CONNECTING } ->
                BleConnectionStatus.CONNECTING
            else -> BleConnectionStatus.DISCONNECTED
        }

    val connectedAddress: String? get() = connectedDevices.firstOrNull()?.address
    val connectedName: String?
        get() = when (connectedCount) {
            0 -> null
            1 -> connectedDevices.first().name
            else -> "$connectedCount tiras LED"
        }
    val activeCharUuid: String? get() = connectedDevices.firstOrNull()?.activeCharUuid
    val detectedProfile: ProtocolProfile
        get() = connectedDevices.firstOrNull()?.detectedProfile ?: ProtocolProfile.LEDCAR_01
}

class SymphonyBleManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val _state = MutableStateFlow(
        BleUiState(
            isBluetoothSupported = bluetoothManager != null && adapter != null,
            isBluetoothEnabled = adapter?.isEnabled == true,
        )
    )
    val state: StateFlow<BleUiState> = _state.asStateFlow()

    private class BleNode(
        val address: String,
        val name: String,
        var gatt: BluetoothGatt? = null,
        var writeChar: BluetoothGattCharacteristic? = null,
        var status: BleConnectionStatus = BleConnectionStatus.CONNECTING,
        var detectedProfile: ProtocolProfile = ProtocolProfile.LEDCAR_01,
        var activeCharUuid: String? = null,
        var lastTxHex: String? = null,
        var lastColorPacket: ByteArray? = null,
        val controlChannel: Channel<ByteArray> = Channel(capacity = 12),
        val colorChannel: Channel<ByteArray> = Channel(capacity = Channel.CONFLATED),
        var writerJob: Job? = null,
        var connectTimeoutJob: Job? = null,
    )

    private val nodes = ConcurrentHashMap<String, BleNode>()
    private val manuallyDisconnectedAddresses = ConcurrentHashMap.newKeySet<String>()
    private val deviceProfileOverrides = ConcurrentHashMap<String, ProtocolProfile>()
    private val deviceColorOrderOverrides = ConcurrentHashMap<String, ColorOrder>()

    private var scanJob: Job? = null

    // Prioritize 0xFFE1 first for LEDCAR-01 / VGETTING / LED LAMP controllers
    private val preferredCharPrefixes = listOf(
        "0000ffe1",
        "0000fff3",
        "0000fff1",
        "0000ee01",
        "0000ae01",
        "0000ffd9",
        "0000ffe9",
    )

    private val ledKeywords = listOf(
        "LEDCAR", "LEDDMX", "CAR", "LED", "ELK", "BLEDOM", "MELK", "BJ_",
        "SYMPHONY", "LAMP", "TRIONES", "SP110", "QHM", "LOTUS", "RGB", "LIGHT",
    )

    private val discoveredMap = linkedMapOf<String, ScannedBleDevice>()

    fun hasBleScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasBleConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isManuallyDisconnected(address: String): Boolean =
        manuallyDisconnectedAddresses.contains(address)

    fun refreshAdapterState() {
        val bt = adapter
        _state.update {
            it.copy(
                isBluetoothSupported = bt != null,
                isBluetoothEnabled = bt?.isEnabled == true,
            )
        }
    }

    fun handleBluetoothAdapterStateChanged(isEnabled: Boolean) {
        if (!isEnabled) {
            // Clear stale GATT nodes without marking manually disconnected so ACC-ON reconnects cleanly
            nodes.keys.toList().forEach { closeNodeInternal(it) }
            publishNodesState()
            _state.update {
                it.copy(
                    isScanning = false,
                    isBluetoothEnabled = false,
                    statusMessage = "Bluetooth apagado (esperando encendido del coche)",
                )
            }
        } else {
            _state.update {
                it.copy(
                    isBluetoothEnabled = true,
                    statusMessage = null,
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun tryAutoEnableBluetooth(): Boolean {
        val bt = adapter ?: return false
        if (bt.isEnabled) {
            refreshAdapterState()
            return true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val canTry = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                hasBleConnectPermission()
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) ==
                    PackageManager.PERMISSION_GRANTED
            }
            if (canTry) {
                val requested = runCatching { bt.enable() }.getOrDefault(false)
                if (requested) {
                    _state.update {
                        it.copy(statusMessage = "Activando Bluetooth automáticamente…")
                    }
                    return true
                }
            }
        }
        return false
    }

    fun setSelectedProfile(profile: ProtocolProfile) {
        _state.update { it.copy(selectedProfile = profile) }
    }

    fun setDeviceOverrides(
        profiles: Map<String, ProtocolProfile>,
        colorOrders: Map<String, ColorOrder>,
    ) {
        deviceProfileOverrides.clear()
        deviceProfileOverrides.putAll(profiles)
        deviceColorOrderOverrides.clear()
        deviceColorOrderOverrides.putAll(colorOrders)
        nodes.values.forEach { it.lastColorPacket = null }
    }

    fun effectiveProfileForAddress(address: String, detected: ProtocolProfile): ProtocolProfile {
        val perDevice = deviceProfileOverrides[address]
        if (perDevice != null && perDevice != ProtocolProfile.AUTO) return perDevice
        val globalSelected = _state.value.selectedProfile
        return if (globalSelected == ProtocolProfile.AUTO) detected else globalSelected
    }

    private fun effectiveProfileFor(node: BleNode): ProtocolProfile =
        effectiveProfileForAddress(node.address, node.detectedProfile)

    private fun effectiveColorOrderFor(node: BleNode, defaultOrder: ColorOrder): ColorOrder =
        deviceColorOrderOverrides[node.address] ?: defaultOrder

    fun effectiveProfile(): ProtocolProfile {
        val selected = _state.value.selectedProfile
        return if (selected == ProtocolProfile.AUTO) _state.value.detectedProfile else selected
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = result.scanRecord?.deviceName ?: device.name ?: "Dispositivo BLE"
            val upper = name.uppercase()
            val isLikelyLed = ledKeywords.any { upper.contains(it) }
            val entry = ScannedBleDevice(
                address = device.address,
                name = name,
                rssi = result.rssi,
                isLikelyLedController = isLikelyLed,
            )
            synchronized(discoveredMap) {
                discoveredMap[device.address] = entry
                val sorted = discoveredMap.values.sortedWith(
                    compareByDescending<ScannedBleDevice> { it.isLikelyLedController }
                        .thenByDescending { it.rssi }
                )
                _state.update { it.copy(discoveredDevices = sorted) }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _state.update {
                it.copy(
                    isScanning = false,
                    statusMessage = "Error al escanear BLE (código $errorCode)",
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan(durationMs: Long = 10_000L) {
        refreshAdapterState()
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            _state.update { it.copy(statusMessage = "Activa el Bluetooth en la radio primero") }
            return
        }
        if (!hasBleScanPermission()) {
            _state.update { it.copy(statusMessage = "Faltan permisos de Bluetooth") }
            return
        }
        val scanner = bt.bluetoothLeScanner ?: run {
            _state.update { it.copy(statusMessage = "Escáner BLE no disponible") }
            return
        }

        stopScan()
        synchronized(discoveredMap) { discoveredMap.clear() }
        _state.update {
            it.copy(
                isScanning = true,
                discoveredDevices = emptyList(),
                statusMessage = null,
            )
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        runCatching {
            scanner.startScan(null, settings, scanCallback)
        }.onFailure { err ->
            _state.update {
                it.copy(isScanning = false, statusMessage = err.localizedMessage)
            }
            return
        }

        scanJob = scope.launch {
            delay(durationMs)
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (_state.value.isScanning && hasBleScanPermission()) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        }
        _state.update { it.copy(isScanning = false) }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String, displayName: String? = null) {
        refreshAdapterState()
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            _state.update { it.copy(statusMessage = "Bluetooth desactivado") }
            return
        }
        if (!hasBleConnectPermission()) {
            _state.update { it.copy(statusMessage = "Permiso BLUETOOTH_CONNECT requerido") }
            return
        }

        val existing = nodes[address]
        if (existing != null &&
            (existing.status == BleConnectionStatus.CONNECTED ||
                existing.status == BleConnectionStatus.CONNECTING ||
                existing.status == BleConnectionStatus.DISCOVERING)
        ) {
            return
        }

        manuallyDisconnectedAddresses.remove(address)
        closeNodeInternal(address)

        val device: BluetoothDevice = try {
            bt.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            _state.update { it.copy(statusMessage = "Dirección MAC inválida") }
            return
        }

        val name = displayName ?: runCatching { device.name }.getOrNull() ?: "LEDCAR"
        val node = BleNode(
            address = address,
            name = name,
            status = BleConnectionStatus.CONNECTING,
            detectedProfile = LedProtocol.detectProfile(name, null),
        )
        nodes[address] = node
        publishNodesState()

        val callback = createGattCallback(address)
        node.gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }
        node.connectTimeoutJob = scope.launch {
            delay(10_000L)
            val current = nodes[address]
            if (current === node && current.status != BleConnectionStatus.CONNECTED) {
                closeNodeInternal(address)
                publishNodesState()
            }
        }
    }

    fun disconnect(address: String) {
        manuallyDisconnectedAddresses.add(address)
        closeNodeInternal(address)
        publishNodesState()
    }

    fun disconnectAll() {
        nodes.keys.toList().forEach { addr ->
            manuallyDisconnectedAddresses.add(addr)
            closeNodeInternal(addr)
        }
        publishNodesState()
    }

    @SuppressLint("MissingPermission")
    private fun closeNodeInternal(address: String) {
        val node = nodes.remove(address) ?: return
        node.connectTimeoutJob?.cancel()
        node.connectTimeoutJob = null
        node.writerJob?.cancel()
        node.writerJob = null
        node.writeChar = null
        node.lastColorPacket = null
        val oldGatt = node.gatt
        node.gatt = null
        if (oldGatt != null && hasBleConnectPermission()) {
            runCatching {
                oldGatt.disconnect()
                oldGatt.close()
            }
        }
    }

    /**
     * Connects to a controller and waits briefly until GATT service discovery finishes
     * (or fails) so dual TS10 connections don't collide on Realtek/Unisoc BLE chips.
     */
    suspend fun connectSequential(address: String, displayName: String? = null, maxWaitMs: Long = 3500L) {
        connect(address, displayName)
        val deadline = SystemClock.elapsedRealtime() + maxWaitMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val st = nodes[address]?.status
            if (st == null || st == BleConnectionStatus.CONNECTED) break
            delay(120L)
        }
        delay(300L)
    }

    private fun publishNodesState(errorMsg: String? = null) {
        val snapshot = nodes.values.associate { n ->
            n.address to ConnectedDeviceInfo(
                address = n.address,
                name = n.name,
                status = n.status,
                activeCharUuid = n.activeCharUuid,
                detectedProfile = n.detectedProfile,
                lastTxHex = n.lastTxHex,
            )
        }
        _state.update {
            it.copy(
                devicesByAddress = snapshot,
                statusMessage = errorMsg ?: it.statusMessage,
            )
        }
    }

    private fun createGattCallback(address: String) = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val node = nodes[address] ?: return
            if (node.gatt !== g) return

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                node.status = BleConnectionStatus.DISCOVERING
                publishNodesState()
                runCatching {
                    // BALANCED keeps dual simultaneous TS10 BLE connections rock-solid without choking
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                    g.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                closeNodeInternal(address)
                publishNodesState(
                    if (status != BluetoothGatt.GATT_SUCCESS && status != 0) {
                        "${node.name} desconectado (código $status)"
                    } else null
                )
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val node = nodes[address] ?: return
            if (node.gatt !== g) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeNodeInternal(address)
                publishNodesState("Error GATT en ${node.name} ($status)")
                return
            }

            val allChars = g.services.flatMap { it.characteristics }
            val writable = allChars.filter { c ->
                (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 ||
                    (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
            }

            val chosen = preferredCharPrefixes.firstNotNullOfOrNull { prefix ->
                writable.firstOrNull { it.uuid.toString().lowercase().startsWith(prefix) }
            } ?: writable.firstOrNull()

            if (chosen == null) {
                closeNodeInternal(address)
                publishNodesState("${node.name} no tiene característica LED escribible")
                return
            }

            val uuidStr = chosen.uuid.toString()
            node.connectTimeoutJob?.cancel()
            node.connectTimeoutJob = null
            node.writeChar = chosen
            node.activeCharUuid = uuidStr
            node.detectedProfile = LedProtocol.detectProfile(node.name, uuidStr)
            node.status = BleConnectionStatus.CONNECTED
            startNodeWriterLoop(node)
            _state.update { it.copy(statusMessage = null) }
            publishNodesState()
        }
    }

    private fun startNodeWriterLoop(node: BleNode) {
        node.writerJob?.cancel()
        node.writerJob = scope.launch {
            while (isActive && node.status == BleConnectionStatus.CONNECTED) {
                val ctrl = node.controlChannel.tryReceive().getOrNull()
                if (ctrl != null) {
                    writeBytesToNode(node, ctrl)
                    // Safe 95ms pacing so cheap LEDCAR-01 microcontrollers never buffer-overflow or reboot
                    delay(95L)
                    continue
                }
                val color = node.colorChannel.tryReceive().getOrNull()
                if (color != null) {
                    writeBytesToNode(node, color)
                    delay(95L)
                    continue
                }
                delay(24L)
            }
        }
    }

    fun sendPowerAll(on: Boolean) {
        nodes.values.forEach { node ->
            if (node.status == BleConnectionStatus.CONNECTED) {
                val profile = effectiveProfileFor(node)
                node.lastColorPacket = null
                LedProtocol.buildPowerPackets(profile, on).forEach { pkt ->
                    node.controlChannel.trySend(pkt)
                }
            }
        }
    }

    fun sendControlPacketsAll(buildForProfile: (ProtocolProfile) -> ByteArray?) {
        nodes.values.forEach { node ->
            if (node.status == BleConnectionStatus.CONNECTED) {
                val pkt = buildForProfile(effectiveProfileFor(node))
                if (pkt != null) {
                    node.controlChannel.trySend(pkt)
                }
            }
        }
    }

    fun sendLiveColorAll(
        argb: Int,
        brightnessPercent: Int,
        colorOrder: ColorOrder,
        pureLedCalibration: Boolean,
    ) {
        sendLiveColors(
            primaryArgb = argb,
            secondaryArgb = argb,
            dualZoneSplit = false,
            brightnessPercent = brightnessPercent,
            defaultColorOrder = colorOrder,
            pureLedCalibration = pureLedCalibration,
        )
    }

    fun sendLiveColors(
        primaryArgb: Int,
        secondaryArgb: Int,
        dualZoneSplit: Boolean,
        brightnessPercent: Int,
        defaultColorOrder: ColorOrder,
        pureLedCalibration: Boolean,
    ) {
        val connectedNodes = nodes.values
            .filter { it.status == BleConnectionStatus.CONNECTED }
            .sortedBy { it.address }

        connectedNodes.forEachIndexed { idx, node ->
            val zoneArgb = if (dualZoneSplit && connectedNodes.size > 1 && idx % 2 == 1) {
                secondaryArgb
            } else {
                primaryArgb
            }
            val packet = LedProtocol.buildColorPacket(
                profile = effectiveProfileFor(node),
                argb = zoneArgb,
                brightnessPercent = brightnessPercent,
                colorOrder = effectiveColorOrderFor(node, defaultColorOrder),
                pureLedCalibration = pureLedCalibration,
            )
            val prev = node.lastColorPacket
            if (prev != null && prev.contentEquals(packet)) return@forEachIndexed
            node.lastColorPacket = packet
            node.colorChannel.trySend(packet)
        }
    }

    fun sendTestFlashToDevice(
        address: String,
        defaultColorOrder: ColorOrder,
        pureLedCalibration: Boolean,
    ) {
        val node = nodes[address] ?: return
        if (node.status != BleConnectionStatus.CONNECTED) return
        val profile = effectiveProfileFor(node)
        val order = effectiveColorOrderFor(node, defaultColorOrder)
        node.lastColorPacket = null
        LedProtocol.buildPowerPackets(profile, true).forEach { node.controlChannel.trySend(it) }
        LedProtocol.buildBrightnessPacket(profile, 100)?.let { node.controlChannel.trySend(it) }
        node.controlChannel.trySend(
            LedProtocol.buildColorPacket(
                profile = profile,
                argb = 0xFF00E5FF.toInt(),
                brightnessPercent = 100,
                colorOrder = order,
                pureLedCalibration = pureLedCalibration,
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun writeBytesToNode(node: BleNode, bytes: ByteArray) {
        val currentGatt = node.gatt ?: return
        val char = node.writeChar ?: return
        if (!hasBleConnectPermission()) return

        val writeType =
            if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

        val start = SystemClock.elapsedRealtime()
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                currentGatt.writeCharacteristic(char, bytes, writeType) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.writeType = writeType
                @Suppress("DEPRECATION")
                char.value = bytes
                @Suppress("DEPRECATION")
                currentGatt.writeCharacteristic(char)
            }
        }.getOrDefault(false)

        if (ok) {
            val hex = LedProtocol.toHexString(bytes)
            node.lastTxHex = hex
            val elapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1L)
            _state.update { state ->
                val updatedMap = state.devicesByAddress[node.address]?.let { existing ->
                    state.devicesByAddress + (node.address to existing.copy(lastTxHex = hex))
                } ?: state.devicesByAddress
                state.copy(
                    devicesByAddress = updatedMap,
                    lastTxHex = hex,
                    lastWriteLatencyMs = elapsed,
                )
            }
        }
    }

    fun shutdown() {
        stopScan()
        nodes.keys.toList().forEach { closeNodeInternal(it) }
    }
}
