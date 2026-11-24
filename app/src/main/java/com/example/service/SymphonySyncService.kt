package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.SymphonyApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class SymphonySyncService : Service() {

    companion object {
        const val CHANNEL_ID = "symphony_sync_channel"
        const val NOTIF_ID = 401
        const val ACTION_STOP = "com.example.action.STOP_SYNC"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifyElapsedMs: Long = 0L

    private data class NotifSnapshot(
        val title: String,
        val bleConnected: Boolean,
        val bleName: String?,
        val syncEnabled: Boolean,
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as SymphonyApp
        if (intent?.action == ACTION_STOP) {
            app.syncEngine.setSyncEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()

        val initialNotif = buildNotification("SymphonySync en segundo plano", "Sincronización LED activa")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                initialNotif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIF_ID, initialNotif)
        }

        if (observerJob == null) {
            observerJob = serviceScope.launch {
                combine(
                    app.syncEngine.playback,
                    app.syncEngine.bleState,
                    app.syncEngine.prefs,
                ) { playback, ble, prefs ->
                    val title = playback.track?.let { "${it.title} — ${it.artist}" }
                        ?: if (prefs.syncEnabled) "Esperando música en Spotify" else "Control manual LED activo"
                    NotifSnapshot(
                        title = title,
                        bleConnected = ble.isConnected,
                        bleName = ble.connectedName,
                        syncEnabled = prefs.syncEnabled,
                    )
                }
                    .distinctUntilChanged()
                    .collect { snap ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastNotifyElapsedMs < 800L && lastNotifyElapsedMs != 0L) {
                            // Keep binder traffic minimal on TS10 SystemUI
                            return@collect
                        }
                        lastNotifyElapsedMs = now
                        val sub = if (snap.bleConnected) {
                            "${snap.bleName ?: "LED Symphony"} • ${if (snap.syncEnabled) "Sync en vivo" else "Manual"}"
                        } else {
                            "Buscando tira LED…"
                        }
                        val nm = getSystemService(NotificationManager::class.java)
                        nm.notify(NOTIF_ID, buildNotification(snap.title, sub))
                    }
            }
        }

        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SymphonySync::LedSyncLock").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L) // 6 hours max safety timeout per drive session
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun buildNotification(title: String, subtitle: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, SymphonySyncService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentPending)
            .addAction(0, getString(R.string.notif_stop_action), stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        observerJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
