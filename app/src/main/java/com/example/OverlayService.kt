package com.example

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Foreground service that samples the device-wide traffic counters and shows the live
 * download/upload rate:
 *  1. directly in the status bar, by drawing the numbers into the notification's small icon
 *     (always on, needs no special permission), and
 *  2. optionally as a small draggable floating pill on top of other apps.
 */
class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 2001

        // New channel id: channel importance can't be changed after creation, and the status
        // bar icon must not be hidden as a "silent" notification.
        const val CHANNEL_ID = "net_speed_status_bar"
        private const val LEGACY_CHANNEL_ID = "net_speed_monitor_channel"
        const val ACTION_STOP_SERVICE = "com.example.ACTION_STOP_SERVICE"

        fun formatSpeed(bytesPerSec: Long, useBits: Boolean = true): Pair<String, String> =
            SpeedFormatter.format(bytesPerSec, useBits)

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to start speed service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var powerManager: PowerManager
    private lateinit var settingsManager: SettingsManager
    private val iconRenderer = StatusBarIconRenderer()

    private var composeView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val downloadSpeedBps = mutableLongStateOf(0L)
    private val uploadSpeedBps = mutableLongStateOf(0L)

    private val compactMode = mutableStateOf(true)
    private val colorScheme = mutableStateOf("indigo")
    private val opacity = mutableStateOf(0.85f)
    private val textScale = mutableStateOf(1.0f)
    private val useBits = mutableStateOf(true)
    private var isLocked = false

    private var lastRxBytes = -1L
    private var lastTxBytes = -1L
    private var lastSampleTime = -1L
    private var stoppedByUser = false

    private val contentIntent by lazy {
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
    private val stopIntent by lazy {
        PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP_SERVICE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val speedMonitorRunnable = object : Runnable {
        override fun run() {
            try {
                updateSpeed()
                ensureOverlayState()
            } catch (e: Exception) {
                Log.e(TAG, "Error in speed monitor runnable", e)
            } finally {
                // Nobody can see the numbers while the screen is off; stop sampling to save battery.
                if (powerManager.isInteractive) {
                    handler.postDelayed(this, settingsManager.updateIntervalMs)
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> handler.removeCallbacks(speedMonitorRunnable)
                Intent.ACTION_SCREEN_ON -> restartSampling()
            }
        }
    }

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "compact_mode" -> compactMode.value = settingsManager.compactMode
            "color_scheme" -> colorScheme.value = settingsManager.colorScheme
            "opacity" -> opacity.value = settingsManager.opacity
            "text_scale" -> textScale.value = settingsManager.textScale
            "use_bits" -> {
                useBits.value = settingsManager.useBits
                postNotification(downloadSpeedBps.longValue, uploadSpeedBps.longValue)
            }
            "is_locked" -> isLocked = settingsManager.isLocked
            "last_x", "last_y" -> moveOverlayToSavedPosition()
            SettingsManager.KEY_FLOATING_OVERLAY -> ensureOverlayState()
            "update_interval_ms" -> restartSampling()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        settingsManager = SettingsManager(this)

        compactMode.value = settingsManager.compactMode
        colorScheme.value = settingsManager.colorScheme
        opacity.value = settingsManager.opacity
        textScale.value = settingsManager.textScale
        useBits.value = settingsManager.useBits
        isLocked = settingsManager.isLocked

        settingsManager.registerListener(settingsListener)

        createNotificationChannel()
        val notification = buildNotification(0L, 0L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        ensureOverlayState()
        restartSampling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stoppedByUser = true
            settingsManager.monitorEnabled = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureOverlayState()
        return START_STICKY
    }

    private fun restartSampling() {
        handler.removeCallbacks(speedMonitorRunnable)
        // Reset the baseline so the first reading after a pause isn't an average over the gap.
        lastRxBytes = -1L
        lastTxBytes = -1L
        lastSampleTime = -1L
        handler.post(speedMonitorRunnable)
    }

    private fun updateSpeed() {
        val rxBytes = TrafficStats.getTotalRxBytes()
        val txBytes = TrafficStats.getTotalTxBytes()
        val now = SystemClock.elapsedRealtime()
        if (rxBytes == TrafficStats.UNSUPPORTED.toLong() || txBytes == TrafficStats.UNSUPPORTED.toLong()) {
            return
        }

        if (lastSampleTime >= 0) {
            val elapsedMs = now - lastSampleTime
            if (elapsedMs < 100) return // too soon for a meaningful rate; keep the old baseline
            // Counters can reset (e.g. interface restart); treat that as zero instead of negative.
            val rxSpeed = ((rxBytes - lastRxBytes).coerceAtLeast(0L) * 1000L) / elapsedMs
            val txSpeed = ((txBytes - lastTxBytes).coerceAtLeast(0L) * 1000L) / elapsedMs
            downloadSpeedBps.longValue = rxSpeed
            uploadSpeedBps.longValue = txSpeed
            postNotification(rxSpeed, txSpeed)
        }

        lastRxBytes = rxBytes
        lastTxBytes = txBytes
        lastSampleTime = now
    }

    // ---------------------------------------------------------------------------------------
    // Status bar indicator (notification)
    // ---------------------------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            // IMPORTANCE_DEFAULT (with no sound/vibration) keeps the icon in the status bar;
            // LOW/MIN notifications are hidden from the status bar on many devices.
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.channel_description)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(dlBps: Long, ulBps: Long): Notification {
        val bits = settingsManager.useBits
        val dl = SpeedFormatter.formatFull(dlBps, bits)
        val ul = SpeedFormatter.formatFull(ulBps, bits)
        val icon = IconCompat.createWithBitmap(iconRenderer.render(dlBps, ulBps, bits))

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle("↓ $dl    ↑ $ul")
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun postNotification(dlBps: Long, ulBps: Long) {
        if (stoppedByUser) return
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(dlBps, ulBps))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update status bar notification", e)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Optional floating overlay
    // ---------------------------------------------------------------------------------------

    private fun ensureOverlayState() {
        val wanted = settingsManager.floatingOverlayEnabled && Settings.canDrawOverlays(this)
        if (!wanted) {
            removeFloatingOverlay()
            return
        }
        val view = composeView
        if (view == null) {
            showFloatingOverlay()
        } else if (!view.isAttachedToWindow) {
            removeFloatingOverlay()
            showFloatingOverlay()
        }
    }

    private fun showFloatingOverlay() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = settingsManager.lastX
            y = settingsManager.lastY
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                OverlayViewContent(
                    downloadBps = downloadSpeedBps.longValue,
                    uploadBps = uploadSpeedBps.longValue,
                    compactMode = compactMode.value,
                    colorSchemeName = colorScheme.value,
                    opacity = opacity.value,
                    textScale = textScale.value,
                    useBits = useBits.value
                )
            }
        }
        setupDragListener(view, params)

        try {
            windowManager.addView(view, params)
            composeView = view
            layoutParams = params
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating overlay", e)
        }
    }

    private fun removeFloatingOverlay() {
        composeView?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (ignored: Exception) {
            }
        }
        composeView = null
        layoutParams = null
    }

    private fun moveOverlayToSavedPosition() {
        val view = composeView ?: return
        val params = layoutParams ?: return
        if (params.x == settingsManager.lastX && params.y == settingsManager.lastY) return
        params.x = settingsManager.lastX
        params.y = settingsManager.lastY
        try {
            windowManager.updateViewLayout(view, params)
        } catch (ignored: Exception) {
        }
    }

    private fun setupDragListener(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            if (isLocked) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (initialX + (event.rawX - initialTouchX).toInt()).coerceAtLeast(0)
                    params.y = (initialY + (event.rawY - initialTouchY).toInt()).coerceAtLeast(0)
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating overlay layout during drag", e)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    settingsManager.lastX = params.x
                    settingsManager.lastY = params.y
                    true
                }
                else -> false
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle / recovery
    // ---------------------------------------------------------------------------------------

    private fun scheduleServiceRestart(delayMs: Long) {
        if (stoppedByUser || !settingsManager.autoRecover || !settingsManager.monitorEnabled) return
        try {
            val restartIntent = Intent(applicationContext, RecoveryReceiver::class.java)
                .setAction(RecoveryReceiver.ACTION_RESTART_SPEED_SERVICE)
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext, 101, restartIntent,
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
        scheduleServiceRestart(1000L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(speedMonitorRunnable)
        settingsManager.unregisterListener(settingsListener)
        try {
            unregisterReceiver(screenReceiver)
        } catch (ignored: Exception) {
        }
        removeFloatingOverlay()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED

        // Still enabled in settings means the system killed us, not the user: come back.
        scheduleServiceRestart(1500L)
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
