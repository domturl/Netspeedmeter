package app.findus.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.findus.MainActivity
import app.findus.R
import app.findus.data.LocationPayload
import app.findus.data.PairRepository
import app.findus.data.PairStore
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps sending this phone's (encrypted) location to the pair while sharing
 * is on — including when the app is closed. A persistent notification makes it obvious that
 * sharing is active and lets you stop it in one tap.
 */
class SharingService : Service() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private lateinit var store: PairStore
  private lateinit var repo: PairRepository
  private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

  private val callback =
    object : LocationCallback() {
      override fun onLocationResult(result: LocationResult) {
        result.lastLocation?.let(::send)
      }
    }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    store = PairStore(this)
    repo = PairRepository(this)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stop(this)
      return START_NOT_STICKY
    }
    if (
      ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
        PackageManager.PERMISSION_GRANTED || store.invite == null || !repo.isConfigured
    ) {
      stopSelf()
      return START_NOT_STICKY
    }

    ServiceCompat.startForeground(
      this,
      NOTIFICATION_ID,
      buildNotification(),
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
      else 0,
    )

    val request =
      LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
        .setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
        .build()
    try {
      fused.removeLocationUpdates(callback)
      fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
      fused.lastLocation.addOnSuccessListener { it?.let(::send) }
    } catch (e: SecurityException) {
      Log.w(TAG, "Location permission revoked", e)
      stopSelf()
    }
    return START_STICKY
  }

  private fun send(location: Location) {
    val invite = store.invite ?: return
    val payload =
      LocationPayload(
        name = store.displayName.ifBlank { "Partner" },
        lat = location.latitude,
        lon = location.longitude,
        accuracyMeters = location.accuracy,
        timeMillis = location.time,
        batteryPercent = batteryPercent(),
      )
    scope.launch {
      runCatching { repo.publish(invite, payload) }.onFailure { Log.w(TAG, "Publish failed", it) }
    }
  }

  private fun batteryPercent(): Int? =
    (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
      .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
      .takeIf { it in 0..100 }

  override fun onDestroy() {
    fused.removeLocationUpdates(callback)
    scope.cancel()
    super.onDestroy()
  }

  private fun buildNotification(): Notification {
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
    )
    val open =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
      )
    val stop =
      PendingIntent.getService(
        this,
        1,
        Intent(this, SharingService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE,
      )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(getString(R.string.notification_title))
      .setContentText(getString(R.string.notification_text))
      .setContentIntent(open)
      .addAction(0, getString(R.string.stop_sharing), stop)
      .setOngoing(true)
      .build()
  }

  companion object {
    private const val TAG = "SharingService"
    private const val CHANNEL_ID = "sharing"
    private const val NOTIFICATION_ID = 1
    private const val ACTION_STOP = "app.findus.STOP"
    private const val UPDATE_INTERVAL_MS = 30_000L
    private const val FASTEST_INTERVAL_MS = 10_000L

    fun start(context: Context) {
      PairStore(context).sharingEnabled = true
      ContextCompat.startForegroundService(context, Intent(context, SharingService::class.java))
    }

    /** Stops sharing and deletes this phone's last location from the server. */
    fun stop(context: Context) {
      val store = PairStore(context)
      store.sharingEnabled = false
      context.stopService(Intent(context, SharingService::class.java))
      val invite = store.invite ?: return
      CoroutineScope(Dispatchers.IO).launch {
        runCatching { PairRepository(context.applicationContext).clearMyLocation(invite) }
      }
    }
  }
}
