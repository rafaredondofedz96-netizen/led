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
import kotlinx.coroutines.launch

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

data class BleUiState(
    val isBluetoothSupported: Boolean = true,
    val isBluetoothEnabled: Boolean = true,
    val isScanning: Boolean = false,
    val status: BleConnectionStatus = BleConnectionStatus.DISCONNECTED,
    val connectedAddress: String? = null,
    val connectedName: String? = null,
    val activeCharUuid: String? = null,
    val detectedProfile: ProtocolProfile = ProtocolProfile.ELK_LED_LAMP,
    val selectedProfile: ProtocolProfile = ProtocolProfile.AUTO,
    val discoveredDevices: List<ScannedBleDevice> = emptyList(),
    val lastTxHex: String = "--",
    val lastWriteLatencyMs: Long = 0L,
    val statusMessage: String? = null,
) {
    val isConnected: Boolean get() = status == BleConnectionStatus.CONNECTED
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

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var scanJob: Job? = null
    private var lastColorBytesSent: ByteArray? = null
    @Volatile
    var userDisconnected: Boolean = false
        private set

    // Two channels: reliable control commands + conflated live color stream for zero-backlog low latency
    private val controlChannel = Channel<ByteArray>(capacity = 8)
    private val colorChannel = Channel<ByteArray>(capacity = Channel.CONFLATED)

    private val preferredCharPrefixes = listOf(
        "0000fff3",
        "0000ffe1",
        "0000fff1",
        "0000ee01",
        "0000ae01",
        "0000ffd9",
        "0000ffe9",
    )

    private val ledKeywords = listOf(
        "CAR", "LED", "ELK", "BLEDOM", "MELK", "BJ_", "SYMPHONY",
        "LAMP", "TRIONES", "SP110", "QHM", "LOTUS", "RGB", "LIGHT",
    )

    private val deviceMap = linkedMapOf<String, ScannedBleDevice>()

    init {
        startWriterLoop()
    }

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

    fun refreshAdapterState() {
        val bt = adapter
        _state.update {
            it.copy(
                isBluetoothSupported = bt != null,
                isBluetoothEnabled = bt?.isEnabled == true,
            )
        }
    }

    fun setSelectedProfile(profile: ProtocolProfile) {
        _state.update { it.copy(selectedProfile = profile) }
    }

    fun effectiveProfile(): ProtocolProfile {
        val current = _state.value
        return if (current.selectedProfile == ProtocolProfile.AUTO) {
            current.detectedProfile
        } else {
            current.selectedProfile
        }
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
            synchronized(deviceMap) {
                deviceMap[device.address] = entry
                val sorted = deviceMap.values.sortedWith(
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
        synchronized(deviceMap) { deviceMap.clear() }
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
        userDisconnected = false
        stopScan()
        closeGattInternal()

        val device: BluetoothDevice = try {
            bt.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            _state.update { it.copy(statusMessage = "Dirección MAC inválida") }
            return
        }

        val name = displayName ?: runCatching { device.name }.getOrNull() ?: "Symphony LED"
        _state.update {
            it.copy(
                status = BleConnectionStatus.CONNECTING,
                connectedAddress = address,
                connectedName = name,
                statusMessage = null,
            )
        }

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnect() {
        userDisconnected = true
        closeGattInternal()
        _state.update {
            it.copy(
                status = BleConnectionStatus.DISCONNECTED,
                activeCharUuid = null,
                statusMessage = null,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGattInternal() {
        writeChar = null
        lastColorBytesSent = null
        val old = gatt
        gatt = null
        if (old != null && hasBleConnectPermission()) {
            runCatching {
                old.disconnect()
                old.close()
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _state.update { it.copy(status = BleConnectionStatus.DISCOVERING) }
                runCatching {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                writeChar = null
                lastColorBytesSent = null
                runCatching { g.close() }
                if (gatt === g) gatt = null
                _state.update {
                    it.copy(
                        status = BleConnectionStatus.DISCONNECTED,
                        activeCharUuid = null,
                        statusMessage = if (status != BluetoothGatt.GATT_SUCCESS) {
                            "Desconectado (código $status)"
                        } else null,
                    )
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.update {
                    it.copy(
                        status = BleConnectionStatus.DISCONNECTED,
                        statusMessage = "Error al descubrir servicios GATT ($status)",
                    )
                }
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
                _state.update {
                    it.copy(
                        status = BleConnectionStatus.DISCONNECTED,
                        statusMessage = "El dispositivo no expone característica LED escribible",
                    )
                }
                closeGattInternal()
                return
            }

            writeChar = chosen
            val uuidStr = chosen.uuid.toString()
            val detected = LedProtocol.detectProfile(_state.value.connectedName, uuidStr)
            _state.update {
                it.copy(
                    status = BleConnectionStatus.CONNECTED,
                    activeCharUuid = uuidStr,
                    detectedProfile = detected,
                    statusMessage = null,
                )
            }
        }
    }

    fun sendControlPacket(bytes: ByteArray) {
        if (!_state.value.isConnected) return
        controlChannel.trySend(bytes)
    }

    fun sendLiveColor(argb: Int, brightnessPercent: Int) {
        if (!_state.value.isConnected) return
        val packet = LedProtocol.buildColorPacket(effectiveProfile(), argb, brightnessPercent)
        val prev = lastColorBytesSent
        if (prev != null && prev.contentEquals(packet)) return
        lastColorBytesSent = packet
        colorChannel.trySend(packet)
    }

    private fun startWriterLoop() {
        scope.launch {
            while (true) {
                val ctrl = controlChannel.tryReceive().getOrNull()
                if (ctrl != null) {
                    writeBytesInternal(ctrl)
                    delay(28L)
                    continue
                }
                val color = colorChannel.tryReceive().getOrNull()
                if (color != null) {
                    writeBytesInternal(color)
                    delay(35L)
                    continue
                }
                delay(16L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeBytesInternal(bytes: ByteArray) {
        val currentGatt = gatt ?: return
        val char = writeChar ?: return
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
            val elapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1L)
            _state.update {
                it.copy(
                    lastTxHex = LedProtocol.toHexString(bytes),
                    lastWriteLatencyMs = elapsed,
                )
            }
        }
    }

    fun shutdown() {
        stopScan()
        closeGattInternal()
    }
}
