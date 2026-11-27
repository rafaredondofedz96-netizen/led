package com.example

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.AppTab
import com.example.ui.MainUiState
import com.example.ui.MainViewModel
import com.example.ui.screens.BluetoothScreen
import com.example.ui.screens.LiveCockpitScreen
import com.example.ui.screens.SpotifyAccountScreen
import com.example.ui.theme.CockpitSurface
import com.example.ui.theme.CockpitVoid
import com.example.ui.theme.SymphonySyncTheme
import com.example.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        processOAuthIntent(intent)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val liveAccent = if (state.prefs.syncEnabled) {
                Color(state.playback.liveLedArgb)
            } else {
                Color(state.prefs.manualColorArgb)
            }

            SymphonySyncTheme(accentColor = liveAccent) {
                SymphonyCockpitShell(
                    state = state,
                    viewModel = viewModel,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as SymphonyApp).syncEngine.setActivityVisible(true)
    }

    override fun onResume() {
        super.onResume()
        val engine = (application as SymphonyApp).syncEngine
        engine.ensureBackgroundServiceFromForeground()
        engine.reconnectSavedDevicesIfNeeded()
    }

    override fun onPause() {
        (application as SymphonyApp).syncEngine.ensureBackgroundServiceFromForeground()
        super.onPause()
    }

    override fun onStop() {
        (application as SymphonyApp).syncEngine.setActivityVisible(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processOAuthIntent(intent)
    }

    private fun processOAuthIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "symphonysync" && data.host == "callback") {
            viewModel.handleOAuthCallback(data)
        }
    }
}

@Composable
private fun SymphonyCockpitShell(
    state: MainUiState,
    viewModel: MainViewModel,
) {
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.LIVE) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestState by rememberUpdatedState(state)

    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeTick++
                viewModel.refreshBluetoothState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hasOverlayPermission = remember(resumeTick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }
    val isIgnoringBatteryOpt = remember(resumeTick) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    val requestOverlayPermission = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            runCatching { context.startActivity(intent) }
        }
    }

    val requestBatteryExemption = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val direct = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            )
            val launched = runCatching { context.startActivity(direct); true }.getOrDefault(false)
            if (!launched) {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
    }

    // Navigate back from secondary tabs to Live Cockpit first
    BackHandler(enabled = selectedTab != AppTab.LIVE) {
        selectedTab = AppTab.LIVE
    }

    val blePermissions = rememberBlePermissions()
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        viewModel.refreshBluetoothState()
        (context.applicationContext as? SymphonyApp)?.syncEngine?.reconnectSavedDevicesIfNeeded()
    }

    val ensureBluetoothOn = {
        viewModel.refreshBluetoothState()
        if (!latestState.ble.isBluetoothEnabled) {
            val silentlyRequested = viewModel.tryAutoEnableBluetooth()
            if (!silentlyRequested && viewModel.hasBleConnectPermission()) {
                runCatching {
                    enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
            }
        }
    }

    var startScanAfterPerm by remember { mutableStateOf(false) }
    val blePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            ensureBluetoothOn()
            if (startScanAfterPerm) {
                viewModel.startBleScan()
            } else {
                (context.applicationContext as? SymphonyApp)?.syncEngine?.reconnectSavedDevicesIfNeeded()
            }
        }
        startScanAfterPerm = false
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (!viewModel.hasBleConnectPermission() || !viewModel.hasBleScanPermission()) {
            startScanAfterPerm = false
            blePermissionLauncher.launch(blePermissions)
        }
    }

    var autoEnablePromptedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(state.prefs.autoEnableBluetooth, state.ble.isBluetoothEnabled, resumeTick) {
        viewModel.refreshBluetoothState()
        if (state.prefs.autoEnableBluetooth && !state.ble.isBluetoothEnabled) {
            val silentOk = viewModel.tryAutoEnableBluetooth()
            if (!silentOk && !autoEnablePromptedOnce && viewModel.hasBleConnectPermission()) {
                autoEnablePromptedOnce = true
                runCatching {
                    enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
            }
        }
    }

    val requestBleScanWithPermissions = {
        if (viewModel.hasBleScanPermission()) {
            ensureBluetoothOn()
            viewModel.startBleScan()
        } else {
            startScanAfterPerm = true
            blePermissionLauncher.launch(blePermissions)
        }
    }

    val connectBleWithPermissions: (String, String) -> Unit = { addr, name ->
        if (viewModel.hasBleConnectPermission()) {
            ensureBluetoothOn()
            viewModel.connectBleDevice(addr, name)
        } else {
            startScanAfterPerm = false
            blePermissionLauncher.launch(blePermissions)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(CockpitVoid)
    ) {
        val isWidescreen = maxWidth >= 600.dp

        if (isWidescreen) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                NavigationRail(
                    containerColor = CockpitSurface,
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    AppTab.entries.forEach { tab ->
                        val selected = selectedTab == tab
                        NavigationRailItem(
                            selected = selected,
                            onClick = { selectedTab = tab },
                            icon = { TabIcon(tab, selected) },
                            label = { Text(tab.label, maxLines = 1) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary,
                            ),
                            modifier = Modifier.testTag("nav_${tab.name.lowercase()}"),
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    ActiveTabContent(
                        selectedTab = selectedTab,
                        state = state,
                        isWidescreen = true,
                        viewModel = viewModel,
                        hasOverlayPermission = hasOverlayPermission,
                        isIgnoringBatteryOpt = isIgnoringBatteryOpt,
                        onNavigateTab = { selectedTab = it },
                        onRequestScan = requestBleScanWithPermissions,
                        onConnectDevice = connectBleWithPermissions,
                        onEnableBluetoothNow = ensureBluetoothOn,
                        onRequestOverlayPermission = requestOverlayPermission,
                        onRequestBatteryExemption = requestBatteryExemption,
                    )
                }
            }
        } else {
            Scaffold(
                containerColor = CockpitVoid,
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    NavigationBar(
                        containerColor = CockpitSurface,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                    ) {
                        AppTab.entries.forEach { tab ->
                            val selected = selectedTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { selectedTab = tab },
                                icon = { TabIcon(tab, selected) },
                                label = { Text(tab.label, maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary,
                                ),
                                modifier = Modifier.testTag("nav_${tab.name.lowercase()}"),
                            )
                        }
                    }
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    ActiveTabContent(
                        selectedTab = selectedTab,
                        state = state,
                        isWidescreen = false,
                        viewModel = viewModel,
                        hasOverlayPermission = hasOverlayPermission,
                        isIgnoringBatteryOpt = isIgnoringBatteryOpt,
                        onNavigateTab = { selectedTab = it },
                        onRequestScan = requestBleScanWithPermissions,
                        onConnectDevice = connectBleWithPermissions,
                        onEnableBluetoothNow = ensureBluetoothOn,
                        onRequestOverlayPermission = requestOverlayPermission,
                        onRequestBatteryExemption = requestBatteryExemption,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveTabContent(
    selectedTab: AppTab,
    state: MainUiState,
    isWidescreen: Boolean,
    viewModel: MainViewModel,
    hasOverlayPermission: Boolean,
    isIgnoringBatteryOpt: Boolean,
    onNavigateTab: (AppTab) -> Unit,
    onRequestScan: () -> Unit,
    onConnectDevice: (String, String) -> Unit,
    onEnableBluetoothNow: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
) {
    when (selectedTab) {
        AppTab.LIVE -> LiveCockpitScreen(
            state = state,
            isWidescreen = isWidescreen,
            onTogglePower = viewModel::togglePower,
            onToggleSync = viewModel::setSyncEnabled,
            onSelectSyncStyle = viewModel::setCoverSyncStyle,
            onBrightnessChange = viewModel::setBrightness,
            onTransitionSpeedChange = viewModel::setTransitionSpeed,
            onPulseMinChange = viewModel::setPulseMinBrightness,
            onManualColor = viewModel::setManualColor,
            onNavigateTab = onNavigateTab,
        )

        AppTab.BLUETOOTH -> BluetoothScreen(
            state = state,
            isWidescreen = isWidescreen,
            hasOverlayPermission = hasOverlayPermission,
            isIgnoringBatteryOpt = isIgnoringBatteryOpt,
            onRequestScan = onRequestScan,
            onStopScan = viewModel::stopBleScan,
            onConnectDevice = onConnectDevice,
            onDisconnectDevice = viewModel::disconnectBleDevice,
            onDisconnectAll = viewModel::disconnectAllBle,
            onTestDevice = viewModel::testBleDevice,
            onSelectProtocol = viewModel::setProtocolProfile,
            onSelectDeviceProtocol = viewModel::setDeviceProtocol,
            onSelectColorOrder = viewModel::setColorOrder,
            onSelectDeviceColorOrder = viewModel::setDeviceColorOrder,
            onTogglePureCalibration = viewModel::setPureLedCalibration,
            onToggleAutoConnect = viewModel::setAutoConnectBle,
            onToggleAutoOpenOnCarStart = viewModel::setAutoOpenOnCarStart,
            onToggleAutoEnableBluetooth = viewModel::setAutoEnableBluetooth,
            onEnableBluetoothNow = onEnableBluetoothNow,
            onRequestOverlayPermission = onRequestOverlayPermission,
            onRequestBatteryExemption = onRequestBatteryExemption,
        )

        AppTab.ACCOUNT -> SpotifyAccountScreen(
            state = state,
            isWidescreen = isWidescreen,
            onBuildAuthUrl = viewModel::buildSpotifyAuthUrl,
            onSubmitManualCode = viewModel::submitManualCallback,
            onSelectPollInterval = viewModel::setPollIntervalMs,
            onLogout = viewModel::logoutSpotify,
            onCheckGithubUpdates = viewModel::checkForGithubUpdates,
            onToggleAutoOpenOnCarStart = viewModel::setAutoOpenOnCarStart,
        )
    }
}

@Composable
private fun TabIcon(tab: AppTab, selected: Boolean) {
    val icon = when (tab) {
        AppTab.LIVE -> if (selected) Icons.Rounded.GraphicEq else Icons.Outlined.GraphicEq
        AppTab.BLUETOOTH -> if (selected) Icons.Rounded.Bluetooth else Icons.Outlined.Bluetooth
        AppTab.ACCOUNT -> if (selected) Icons.Rounded.AccountCircle else Icons.Outlined.AccountCircle
    }
    Icon(imageVector = icon, contentDescription = tab.label)
}

private fun rememberBlePermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
