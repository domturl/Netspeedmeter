package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restarts the speed monitor after boot, app update, or when the system killed it. */
class RecoveryReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RESTART_SPEED_SERVICE = "com.example.ACTION_RESTART_SPEED_SERVICE"
        private const val TAG = "RecoveryReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "Received ${intent?.action}")
        val settings = SettingsManager(context)
        if (settings.monitorEnabled && settings.autoRecover) {
            OverlayService.start(context)
        }
    }
}
