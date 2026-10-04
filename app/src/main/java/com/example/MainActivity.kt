package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import java.util.Locale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    // Volatile field in OverlayService to check if service is running
    private fun isServiceRunning(): Boolean {
        // Safe check if running
        return try {
            val serviceClass = OverlayService::class.java
            // We can also check if settingsManager says enabled
            SettingsManager(this).overlayEnabled
        } catch (e: Exception) {
            false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("main_screen_scaffold")
                ) { innerPadding ->
                    DashboardScreen(
                        modifier = Modifier.padding(innerPadding),
                        isServiceActiveCheck = { isServiceRunning() },
                        onStartService = { startSpeedService() },
                        onStopService = { stopSpeedService() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val settingsManager = SettingsManager(this)
        if (settingsManager.overlayEnabled && settingsManager.autoRecover && Settings.canDrawOverlays(this)) {
            startSpeedService()
        }
    }

    private fun startSpeedService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopSpeedService() {
        val intent = Intent(this, OverlayService::class.java)
        stopService(intent)
    }
}

@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    isServiceActiveCheck: () -> Boolean,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val scrollState = rememberScrollState()

    // Permissions State
    val hasOverlayPermission = remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val hasNotificationPermission = remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    // Dynamic UI options mapped to settings
    val isServiceRunning = remember { mutableStateOf(isServiceActiveCheck() && hasOverlayPermission.value) }
    val compactMode = remember { mutableStateOf(settingsManager.compactMode) }
    val colorScheme = remember { mutableStateOf(settingsManager.colorScheme) }
    val opacity = remember { mutableStateOf(settingsManager.opacity) }
    val textScale = remember { mutableStateOf(settingsManager.textScale) }
    val useBits = remember { mutableStateOf(settingsManager.useBits) }
    val autoRecover = remember { mutableStateOf(settingsManager.autoRecover) }
    val isLocked = remember { mutableStateOf(settingsManager.isLocked) }
    val updateIntervalMs = remember { mutableStateOf(settingsManager.updateIntervalMs) }

    // Handlers for settings change
    val saveSettings = {
        settingsManager.compactMode = compactMode.value
        settingsManager.colorScheme = colorScheme.value
        settingsManager.opacity = opacity.value
        settingsManager.textScale = textScale.value
        settingsManager.useBits = useBits.value
        settingsManager.autoRecover = autoRecover.value
        settingsManager.isLocked = isLocked.value
        settingsManager.updateIntervalMs = updateIntervalMs.value
    }

    // Overlay Permission Launcher
    val overlayLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasOverlayPermission.value = Settings.canDrawOverlays(context)
        if (hasOverlayPermission.value && settingsManager.overlayEnabled) {
            onStartService()
            isServiceRunning.value = true
        }
    }

    // Notification Permission Launcher
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission.value = isGranted
    }

    // Sync state periodically or when app resumes
    LaunchedEffect(Unit) {
        while (true) {
            hasOverlayPermission.value = Settings.canDrawOverlays(context)
            val active = isServiceActiveCheck() && hasOverlayPermission.value
            isServiceRunning.value = active
            if (settingsManager.overlayEnabled && settingsManager.autoRecover && hasOverlayPermission.value && !active) {
                onStartService()
                isServiceRunning.value = true
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    // Accent color based on selection (Material 3 Clean Minimalism palette)
    val themeColor = when (colorScheme.value) {
        "indigo" -> Color(0xFF6750A4)
        "emerald" -> Color(0xFF0F8F60)
        "amber" -> Color(0xFFE07A5F) // Soft Amber
        "slate" -> Color(0xFF49454F)
        "pink" -> Color(0xFFD01B6A)
        "crimson" -> Color(0xFFB3261E)
        else -> Color(0xFF6750A4)
    }

    val appBackground = Color(0xFFF7F2FA)

    Surface(
        modifier = modifier.fillMaxSize(),
        color = appBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(appBackground)
                .verticalScroll(scrollState)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(themeColor.copy(alpha = 0.12f))
                        .border(1.dp, themeColor.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SignalWifi4Bar,
                        contentDescription = "Net Speed Logo",
                        tint = themeColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = "Net Speed Overlay",
                        color = Color(0xFF1C1B1F),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Real-time bandwidth status indicator",
                        color = Color(0xFF49454F),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Real-Time Preview Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("preview_card"),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE0E0E0))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = "Preview Icon",
                            tint = themeColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Overlay Live Preview",
                            color = Color(0xFF1C1B1F),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))

                    // Simulated Status Bar Background
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF3EDF7))
                            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        // Left-side status bar decorations
                        Row(
                            modifier = Modifier.align(Alignment.CenterStart),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "16:04",
                                color = Color(0xFF1C1B1F),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Previewing the exact layout Composable!
                        Box(
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            OverlayViewContent(
                                downloadBps = 1530920L, // 1.5 MB/s
                                uploadBps = 94208L,     // 92 KB/s
                                compactMode = compactMode.value,
                                colorSchemeName = colorScheme.value,
                                opacity = opacity.value,
                                textScale = textScale.value,
                                useBits = useBits.value
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val previewDlText = if (useBits.value) "12.2 Mbps" else "1.5 MB/s"
                    val previewUlText = if (useBits.value) "753.7 kbps" else "92.0 kB/s"
                    Text(
                        text = "Interactive preview with standard network data ($previewDlText ↓, $previewUlText ↑)",
                        color = Color(0xFF49454F),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Warning / Permission Alert Card if permissions are missing
            AnimatedVisibility(
                visible = !hasOverlayPermission.value,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFCE8E6)),
                    border = BorderStroke(1.dp, Color(0xFFF5C2C0))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Alert icon",
                                tint = Color(0xFFB3261E),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "System Overlay Permission Required",
                                color = Color(0xFF370002),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "To draw the network rate floating window on top of other applications, this app needs the 'Display over other apps' authorization.",
                            color = Color(0xFF370002),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                overlayLauncher.launch(intent)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("grant_overlay_permission_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                "Grant Overlay Permission",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // Notification Permission Card for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission.value) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE0E0E0))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = "Notification alert icon",
                                tint = themeColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Foreground Notification Permission",
                                color = Color(0xFF1C1B1F),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Enabling notification permissions ensures the speed monitoring service runs stably without being terminated by Android's background limits.",
                            color = Color(0xFF49454F),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("grant_notification_permission_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = themeColor),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                "Allow Notifications",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // Primary Toggle Switch Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("service_toggle_card"),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isServiceRunning.value) Color(0xFFEADDFF) else Color.White
                ),
                border = BorderStroke(1.dp, if (isServiceRunning.value) Color(0xFFCAC4D0) else Color(0xFFE0E0E0))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (isServiceRunning.value) Color(0xFF6750A4).copy(alpha = 0.15f) else Color(0xFFE7E0EC)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = "Speed toggle indicator",
                                tint = if (isServiceRunning.value) Color(0xFF21005D) else Color(0xFF49454F),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isServiceRunning.value) "Monitor Service Active" else "Monitor Service Inactive",
                                color = if (isServiceRunning.value) Color(0xFF21005D) else Color(0xFF1C1B1F),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isServiceRunning.value) "Overlay drawing is enabled" else "Turn on to show overlay",
                                color = Color(0xFF49454F),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Switch(
                        checked = isServiceRunning.value,
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                if (!hasOverlayPermission.value) {
                                    // Direct to permission flow first
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    overlayLauncher.launch(intent)
                                } else {
                                    settingsManager.overlayEnabled = true
                                    onStartService()
                                    isServiceRunning.value = true
                                }
                            } else {
                                settingsManager.overlayEnabled = false
                                onStopService()
                                isServiceRunning.value = false
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = themeColor,
                            uncheckedThumbColor = Color(0xFF79747E),
                            uncheckedTrackColor = Color(0xFFE7E0EC)
                        ),
                        modifier = Modifier.testTag("overlay_service_switch")
                    )
                }
            }

            // Customization Options Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("customization_card"),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE0E0E0))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Customization Icon",
                            tint = themeColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Visual Configuration",
                            color = Color(0xFF1C1B1F),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    HorizontalDivider(color = Color(0xFFE7E0EC), thickness = 1.dp)

                    // Overlay Format (Chips)
                    Column {
                        Text(
                            text = "Overlay Layout Style",
                            color = Color(0xFF1C1B1F),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            listOf(true to "Compact (Single Line)", false to "Detailed (Two Lines)").forEach { (isCompact, label) ->
                                val selected = compactMode.value == isCompact
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (selected) themeColor.copy(alpha = 0.12f) else Color.White)
                                        .border(
                                            width = 1.dp,
                                            color = if (selected) themeColor else Color(0xFFCAC4D0),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable {
                                            compactMode.value = isCompact
                                            saveSettings()
                                        }
                                        .padding(vertical = 12.dp)
                                        .testTag(if (isCompact) "style_compact_chip" else "style_detailed_chip"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = if (selected) themeColor else Color(0xFF49454F),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Color Schemes (Circular selections)
                    Column {
                        Text(
                            text = "Color Accent Theme",
                            color = Color(0xFF1C1B1F),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                "indigo" to Color(0xFF6750A4),
                                "emerald" to Color(0xFF0F8F60),
                                "amber" to Color(0xFFE07A5F),
                                "slate" to Color(0xFF49454F),
                                "pink" to Color(0xFFD01B6A),
                                "crimson" to Color(0xFFB3261E)
                            ).forEach { (name, color) ->
                                val selected = colorScheme.value == name
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            width = if (selected) 3.dp else 1.dp,
                                            color = if (selected) Color(0xFF1C1B1F) else Color(0xFFE0E0E0),
                                            shape = CircleShape
                                        )
                                        .clickable {
                                            colorScheme.value = name
                                            saveSettings()
                                        }
                                        .testTag("color_${name}_chip"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(Color.White)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Opacity Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Overlay Opacity",
                                color = Color(0xFF1C1B1F),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${(opacity.value * 100).toInt()}%",
                                color = themeColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Slider(
                            value = opacity.value,
                            onValueChange = {
                                opacity.value = it
                                saveSettings()
                            },
                            valueRange = 0.2f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = themeColor,
                                activeTrackColor = themeColor,
                                inactiveTrackColor = Color(0xFFE7E0EC)
                            ),
                            modifier = Modifier.testTag("opacity_slider")
                        )
                    }

                    // Text Scale Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Text Size Scale",
                                color = Color(0xFF1C1B1F),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = String.format(Locale.US, "%.1fx", textScale.value),
                                color = themeColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Slider(
                            value = textScale.value,
                            onValueChange = {
                                textScale.value = it
                                saveSettings()
                            },
                            valueRange = 0.8f..1.5f,
                            colors = SliderDefaults.colors(
                                thumbColor = themeColor,
                                activeTrackColor = themeColor,
                                inactiveTrackColor = Color(0xFFE7E0EC)
                            ),
                            modifier = Modifier.testTag("text_scale_slider")
                        )
                    }

                    // Update Interval
                    Column {
                        Text(
                            text = "Refresh Rate Interval",
                            color = Color(0xFF1C1B1F),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            listOf(
                                500L to "Fast (0.5s)",
                                1000L to "Medium (1s)",
                                2000L to "Eco (2s)"
                            ).forEach { (ms, label) ->
                                val selected = updateIntervalMs.value == ms
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (selected) themeColor.copy(alpha = 0.12f) else Color.White)
                                        .border(
                                            width = 1.dp,
                                            color = if (selected) themeColor else Color(0xFFCAC4D0),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable {
                                            updateIntervalMs.value = ms
                                            saveSettings()
                                        }
                                        .padding(vertical = 12.dp)
                                        .testTag("interval_${ms}ms_chip"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = if (selected) themeColor else Color(0xFF49454F),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Unit Preference (Mbps vs MB/s)
                    Column {
                        Text(
                            text = "Unit Preference",
                            color = Color(0xFF1C1B1F),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            listOf(
                                true to "Bits (Mbps, kbps)",
                                false to "Bytes (MB/s, kB/s)"
                            ).forEach { (isBits, label) ->
                                val selected = useBits.value == isBits
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (selected) themeColor.copy(alpha = 0.12f) else Color.White)
                                        .border(
                                            width = 1.dp,
                                            color = if (selected) themeColor else Color(0xFFCAC4D0),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable {
                                            useBits.value = isBits
                                            saveSettings()
                                        }
                                        .padding(vertical = 12.dp)
                                        .testTag(if (isBits) "unit_bits_chip" else "unit_bytes_chip"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = if (selected) themeColor else Color(0xFF49454F),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = Color(0xFFE7E0EC), thickness = 1.dp)

                    // Auto-Recovery Switch & Battery Guidance
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Auto recovery icon",
                                tint = themeColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Auto-Recover if Killed",
                                    color = Color(0xFF1C1B1F),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Revives overlay if Android terminates it or after reboot",
                                    color = Color(0xFF49454F),
                                    fontSize = 10.sp
                                )
                            }
                        }
                        Switch(
                            checked = autoRecover.value,
                            onCheckedChange = {
                                autoRecover.value = it
                                saveSettings()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = themeColor,
                                uncheckedThumbColor = Color(0xFF79747E),
                                uncheckedTrackColor = Color(0xFFE7E0EC)
                            ),
                            modifier = Modifier.testTag("auto_recover_switch")
                        )
                    }

                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                    val isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
                    if (!isIgnoringBattery) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFFFF8E1))
                                .border(1.dp, Color(0xFFFFD54F), RoundedCornerShape(12.dp))
                                .clickable {
                                    try {
                                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        try {
                                            val intent = Intent(Settings.ACTION_SETTINGS)
                                            context.startActivity(intent)
                                        } catch (ignored: Exception) {}
                                    }
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Battery warning",
                                tint = Color(0xFFF57C00),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Battery Optimization Active",
                                    color = Color(0xFFE65100),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Tap to exclude app from battery restrictions so Android won't kill it.",
                                    color = Color(0xFF5D4037),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = Color(0xFFE7E0EC), thickness = 1.dp)

                    // Lock Position Switch & Reset position Row
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isLocked.value) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "Lock indicator",
                                    tint = if (isLocked.value) Color(0xFFF59E0B) else Color(0xFF79747E),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Lock Floating Position",
                                        color = Color(0xFF1C1B1F),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Freeze movement across screen",
                                        color = Color(0xFF49454F),
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            Switch(
                                checked = isLocked.value,
                                onCheckedChange = {
                                    isLocked.value = it
                                    saveSettings()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFFF59E0B),
                                    uncheckedThumbColor = Color(0xFF79747E),
                                    uncheckedTrackColor = Color(0xFFE7E0EC)
                                ),
                                modifier = Modifier.testTag("lock_position_switch")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    settingsManager.lastX = 200
                                    settingsManager.lastY = 100
                                    if (isServiceRunning.value) {
                                        onStopService()
                                        onStartService()
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("reset_position_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFF3EDF7),
                                    contentColor = themeColor
                                ),
                                border = BorderStroke(1.dp, themeColor.copy(alpha = 0.3f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    "Reset Position",
                                    color = themeColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }

                            Button(
                                onClick = {
                                    onStopService()
                                    onStartService()
                                    isServiceRunning.value = true
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("restart_service_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = themeColor.copy(alpha = 0.12f),
                                    contentColor = themeColor
                                ),
                                border = BorderStroke(1.dp, themeColor),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Restart Icon",
                                    tint = themeColor,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "Restart Overlay",
                                    color = themeColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }

            // Info Card explaining how the app helps troubleshoot
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                border = BorderStroke(1.dp, Color(0xFFCAC4D0))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Info icon",
                            tint = themeColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Real-time Troubleshooting Guide",
                            color = Color(0xFF1C1B1F),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "• Drag & Position: When unlocked, you can touch and drag the speed indicator to place it anywhere (e.g. adjacent to standard status bar signals or at any edge).\n" +
                               "• Real Network Testing: When waiting for webpages, videos, or files to load, check the live speed indicator. If the speed stays at bps/B/s or very low kbps/kB/s, your signal may be stalled or dropping packets, even if Wi-Fi/LTE shows full bars.\n" +
                               "• Auto-Recovery: The app actively protects itself from background termination. If Android stops the process due to low memory or when swiped, it automatically resurrects the overlay service.",
                        color = Color(0xFF49454F),
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            // Version info footer
            Text(
                text = "v1.0.0 • Local Device Statistics • No Internet Data Shared",
                color = Color(0xFF79747E),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            )
        }
    }
}
