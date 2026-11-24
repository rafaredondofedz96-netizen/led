package com.example

import android.app.Application
import com.example.ble.SymphonyBleManager
import com.example.core.SyncEngine
import com.example.data.PrefsStore
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
    }

    override fun onTerminate() {
        bleManager.shutdown()
        super.onTerminate()
    }
}
