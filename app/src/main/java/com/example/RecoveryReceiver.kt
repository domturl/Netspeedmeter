package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

class RecoveryReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RESTART_SPEED_SERVICE = "com.example.ACTION_RESTART_SPEED_SERVICE"
        private const val TAG = "RecoveryReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.d(TAG, "Received broadcast intent with action: $action")

        val settingsManager = SettingsManager(context)
        if (!settingsManager.autoRecover) {
            Log.d(TAG, "Auto-recovery disabled in user settings, skipping restart.")
            return
        }

        if (settingsManager.overlayEnabled && Settings.canDrawOverlays(context)) {
            Log.d(TAG, "Overlay was previously active. Recovering OverlayService now...")
            val serviceIntent = Intent(context, OverlayService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start OverlayService during recovery", e)
            }
        }
    }
}
