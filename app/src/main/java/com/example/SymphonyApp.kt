package com.example

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.example.ble.SymphonyBleManager
import com.example.core.SyncEngine
import com.example.data.PrefsStore
import com.example.service.BootAndCarStartReceiver
import com.example.spotify.SpotifyRepository

class SymphonyApp : Application() {

    lateinit var prefsStore: PrefsStore
        private set
    lateinit var bleManager: SymphonyBleManager
        private set
    lateinit var spotifyRepository: SpotifyRepository
        private set
    lateinit var syncEngine: SyncEngine
        private set

    private var runtimeCarWakeReceiver: BootAndCarStartReceiver? = null

    override fun onCreate() {
        super.onCreate()
        prefsStore = PrefsStore(this)
        bleManager = SymphonyBleManager(this)
        spotifyRepository = SpotifyRepository(prefsStore)
        syncEngine = SyncEngine(
            appContext = this,
            prefsStore = prefsStore,
            bleManager = bleManager,
            spotifyRepository = spotifyRepository,
        )
        registerCarWakeReceiver()
    }

    private fun registerCarWakeReceiver() {
        val receiver = BootAndCarStartReceiver()
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            runtimeCarWakeReceiver = receiver
        }
    }

    override fun onTerminate() {
        runtimeCarWakeReceiver?.let { runCatching { unregisterReceiver(it) } }
        runtimeCarWakeReceiver = null
        bleManager.shutdown()
        super.onTerminate()
    }
}
