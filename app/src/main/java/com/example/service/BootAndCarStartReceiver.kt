package com.example.service

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.SymphonyApp

/**
 * Handles TS10 car head-unit cold boots, quickboot wakes, package updates,
 * screen unlock, and Bluetooth adapter power-on so SymphonySync opens,
 * reconnects to both LEDCAR-01 controllers, and restores the last saved state.
 */
class BootAndCarStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext as? SymphonyApp ?: return
        val engine = app.syncEngine

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val pending = goAsync()
                engine.onCarStartedEvent(
                    triggerOpenUi = true,
                    reason = action,
                    onComplete = { pending.finish() },
                )
            }

            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val btState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                engine.onBluetoothAdapterStateChanged(btState)
            }

            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_SCREEN_ON -> {
                val pending = goAsync()
                engine.onScreenOrUserWake(
                    isUserPresent = action == Intent.ACTION_USER_PRESENT,
                    onComplete = { pending.finish() },
                )
            }

            Intent.ACTION_SCREEN_OFF -> {
                engine.onScreenOff()
            }
        }
    }
}
