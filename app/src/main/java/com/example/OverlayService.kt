package com.example

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.util.Locale

class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "net_speed_monitor_channel"
        const val ACTION_STOP_SERVICE = "com.example.ACTION_STOP_SERVICE"
        
        fun formatSpeed(bytesPerSec: Long, useBits: Boolean = false): Pair<String, String> {
            val value = if (useBits) bytesPerSec * 8 else bytesPerSec
            val capped = if (value < 0) 0L else value
            
            return if (useBits) {
                when {
                    capped >= 1000 * 1000 * 1000 -> {
                        Pair(String.format(Locale.US, "%.1f", capped / (1000.0 * 1000.0 * 1000.0)), "Gbps")
                    }
                    capped >= 1000 * 1000 -> {
                        Pair(String.format(Locale.US, "%.1f", capped / (1000.0 * 1000.0)), "Mbps")
                    }
                    capped >= 1000 -> {
                        Pair(String.format(Locale.US, "%.0f", capped / 1000.0), "kbps")
                    }
                    else -> {
                        Pair(capped.toString(), "bps")
                    }
                }
            } else {
                when {
                    capped >= 1024 * 1024 * 1024 -> {
                        Pair(String.format(Locale.US, "%.1f", capped / (1024.0 * 1024.0 * 1024.0)), "GB/s")
                    }
                    capped >= 1024 * 1024 -> {
                        Pair(String.format(Locale.US, "%.1f", capped / (1024.0 * 1024.0)), "MB/s")
                    }
                    capped >= 1024 -> {
                        Pair(String.format(Locale.US, "%.0f", capped / 1024.0), "kB/s")
                    }
                    else -> {
                        Pair(capped.toString(), "B/s")
                    }
                }
            }
        }
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var settingsManager: SettingsManager
    private var composeView: ComposeView? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val downloadSpeedBps = mutableStateOf(0L)
    private val uploadSpeedBps = mutableStateOf(0L)

    private val compactMode = mutableStateOf(true)
    private val colorScheme = mutableStateOf("indigo")
    private val opacity = mutableStateOf(0.85f)
    private val textScale = mutableStateOf(1.0f)
    private val useBits = mutableStateOf(true)
    private var isLocked = false

    private var lastRxBytes = -1L
    private var lastTxBytes = -1L
    private var lastSampleTime = -1L

    private val handler = Handler(Looper.getMainLooper())
    private val speedMonitorRunnable = object : Runnable {
        override fun run() {
            try {
                ensureOverlayAttached()
                updateSpeed()
            } catch (e: Exception) {
                Log.e(TAG, "Error in speed monitor runnable", e)
            } finally {
                handler.postDelayed(this, settingsManager.updateIntervalMs)
            }
        }
    }

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "compact_mode" -> compactMode.value = settingsManager.compactMode
            "color_scheme" -> colorScheme.value = settingsManager.colorScheme
            "opacity" -> opacity.value = settingsManager.opacity
            "text_scale" -> textScale.value = settingsManager.textScale
            "use_bits" -> useBits.value = settingsManager.useBits
            "is_locked" -> {
                isLocked = settingsManager.isLocked
            }
            "update_interval_ms" -> {
                handler.removeCallbacks(speedMonitorRunnable)
                handler.post(speedMonitorRunnable)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settingsManager = SettingsManager(this)

        // Initialize local states from settings
        compactMode.value = settingsManager.compactMode
        colorScheme.value = settingsManager.colorScheme
        opacity.value = settingsManager.opacity
        textScale.value = settingsManager.textScale
        useBits.value = settingsManager.useBits
        isLocked = settingsManager.isLocked

        settingsManager.registerListener(settingsListener)

        createNotificationChannel()
        val notification = createNotification(0L, 0L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        showFloatingOverlay()
        handler.post(speedMonitorRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            settingsManager.overlayEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }

        ensureOverlayAttached()
        return START_STICKY
    }

    private fun ensureOverlayAttached() {
        if (!settingsManager.overlayEnabled || !Settings.canDrawOverlays(this)) return
        try {
            if (composeView == null) {
                showFloatingOverlay()
            } else if (!composeView!!.isAttachedToWindow) {
                try {
                    windowManager.removeViewImmediate(composeView)
                } catch (ignored: Exception) {}
                windowManager.addView(composeView, layoutParams)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Re-attaching floating overlay view after detachment...", e)
            try {
                composeView = null
                showFloatingOverlay()
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to re-attach overlay view", ex)
            }
        }
    }

    private fun showFloatingOverlay() {
        if (!Settings.canDrawOverlays(this)) return

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = settingsManager.lastX
            y = settingsManager.lastY
        }

        composeView?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (ignored: Exception) {}
        }

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)

            setContent {
                OverlayViewContent(
                    downloadBps = downloadSpeedBps.value,
                    uploadBps = uploadSpeedBps.value,
                    compactMode = compactMode.value,
                    colorSchemeName = colorScheme.value,
                    opacity = opacity.value,
                    textScale = textScale.value,
                    useBits = useBits.value
                )
            }
        }

        setupDragListener(composeView!!)

        try {
            windowManager.addView(composeView, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add composeView to windowManager", e)
        }
    }

    private fun setupDragListener(view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            if (isLocked) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    
                    // Basic boundary checks
                    if (layoutParams.x < 0) layoutParams.x = 0
                    if (layoutParams.y < 0) layoutParams.y = 0
                    
                    try {
                        windowManager.updateViewLayout(view, layoutParams)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating overlay layout during drag", e)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Save the final user position
                    settingsManager.lastX = layoutParams.x
                    settingsManager.lastY = layoutParams.y
                    true
                }
                else -> false
            }
        }
    }

    private fun updateSpeed() {
        val rxBytes = TrafficStats.getTotalRxBytes()
        val txBytes = TrafficStats.getTotalTxBytes()
        val currentTime = System.currentTimeMillis()

        if (lastRxBytes != -1L && lastTxBytes != -1L && lastSampleTime != -1L) {
            val timeDiffMs = currentTime - lastSampleTime
            if (timeDiffMs > 100) { // Avoid division by very small intervals
                val rxDiff = rxBytes - lastRxBytes
                val txDiff = txBytes - lastTxBytes

                val rxSpeed = (rxDiff * 1000) / timeDiffMs
                val txSpeed = (txDiff * 1000) / timeDiffMs

                downloadSpeedBps.value = rxSpeed
                uploadSpeedBps.value = txSpeed

                updateNotification(rxSpeed, txSpeed)
            }
        }

        lastRxBytes = rxBytes
        lastTxBytes = txBytes
        lastSampleTime = currentTime
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Net Speed Overlay Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays real-time network upload and download speed"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(dlBps: Long, ulBps: Long): Notification {
        val (dlStr, dlUnit) = formatSpeed(dlBps, settingsManager.useBits)
        val (ulStr, ulUnit) = formatSpeed(ulBps, settingsManager.useBits)

        val mainActivityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Net Speed Monitor Active")
            .setContentText("DL: $dlStr $dlUnit | UL: $ulStr $ulUnit")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Overlay",
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(dlBps: Long, ulBps: Long) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = createNotification(dlBps, ulBps)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun scheduleServiceRestart(delayMs: Long) {
        if (!settingsManager.autoRecover || !settingsManager.overlayEnabled) return
        try {
            Log.d(TAG, "Scheduling overlay service recovery in ${delayMs}ms...")
            val restartIntent = Intent(applicationContext, RecoveryReceiver::class.java).apply {
                action = RecoveryReceiver.ACTION_RESTART_SPEED_SERVICE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                101,
                restartIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pendingIntent
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule service restart", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved triggered. Checking if service should auto-recover...")
        if (settingsManager.overlayEnabled && settingsManager.autoRecover && Settings.canDrawOverlays(this)) {
            scheduleServiceRestart(1000L)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(speedMonitorRunnable)
        settingsManager.unregisterListener(settingsListener)

        composeView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing composeView on destroy", e)
            }
        }

        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED

        // If overlay is still enabled in settings, the kill was system-forced; schedule immediate recovery
        if (settingsManager.overlayEnabled && settingsManager.autoRecover && Settings.canDrawOverlays(this)) {
            scheduleServiceRestart(1500L)
        }

        super.onDestroy()
    }
}

@Composable
fun OverlayViewContent(
    downloadBps: Long,
    uploadBps: Long,
    compactMode: Boolean,
    colorSchemeName: String,
    opacity: Float,
    textScale: Float,
    useBits: Boolean
) {
    val themeColor = when (colorSchemeName) {
        "indigo" -> Color(0xFF6750A4)
        "emerald" -> Color(0xFF10B981)
        "amber" -> Color(0xFFF59E0B)
        "slate" -> Color(0xFF79747E)
        "pink" -> Color(0xFFEC4899)
        "crimson" -> Color(0xFFEF4444)
        else -> Color(0xFF6750A4)
    }

    val (dlSpeedStr, dlUnit) = OverlayService.formatSpeed(downloadBps, useBits)
    val (ulSpeedStr, ulUnit) = OverlayService.formatSpeed(uploadBps, useBits)

    val baseBgColor = Color(0xFF1C1B1F) // Deep M3 dark slate/charcoal background
    val bgColor = baseBgColor.copy(alpha = opacity)
    val strokeColor = themeColor.copy(alpha = 0.5f)

    Box(
        modifier = Modifier
            .wrapContentSize()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.5.dp, strokeColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        if (compactMode) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "▼",
                        color = Color(0xFF10B981), // Emerald Green for DL
                        fontSize = (10 * textScale).sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$dlSpeedStr $dlUnit",
                        color = Color.White,
                        fontSize = (11 * textScale).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Box(
                    modifier = Modifier
                        .size(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(themeColor.copy(alpha = 0.5f))
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "▲",
                        color = themeColor, // Selected accent color for UL
                        fontSize = (10 * textScale).sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$ulSpeedStr $ulUnit",
                        color = Color.White,
                        fontSize = (11 * textScale).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "▼ DL:",
                        color = Color(0xFF10B981),
                        fontSize = (10 * textScale).sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = dlSpeedStr,
                            color = Color.White,
                            fontSize = (13 * textScale).sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = dlUnit,
                            color = Color.LightGray,
                            fontSize = (9 * textScale).sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "▲ UL:",
                        color = themeColor,
                        fontSize = (10 * textScale).sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = ulSpeedStr,
                            color = Color.White,
                            fontSize = (13 * textScale).sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = ulUnit,
                            color = Color.LightGray,
                            fontSize = (9 * textScale).sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
