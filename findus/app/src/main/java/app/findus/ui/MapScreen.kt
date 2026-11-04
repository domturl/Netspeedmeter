package app.findus.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.findus.data.LocationPayload
import kotlinx.coroutines.delay
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

@Composable
fun MapScreen(
  state: UiState,
  partner: LocationPayload?,
  hasLocationPermission: Boolean,
  onToggleSharing: (Boolean) -> Unit,
  onLeave: () -> Unit,
) {
  val context = LocalContext.current
  val partnerColor = MaterialTheme.colorScheme.primary.toArgb()
  val mapView = remember {
    MapView(context).apply {
      setTileSource(TileSourceFactory.MAPNIK)
      setMultiTouchControls(true)
      zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
      controller.setZoom(4.0)
    }
  }
  val myOverlay = remember(hasLocationPermission) {
    if (!hasLocationPermission) null
    else MyLocationNewOverlay(GpsMyLocationProvider(context), mapView).apply { enableMyLocation() }
  }
  val partnerMarker = remember {
    Marker(mapView).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER) }
  }
  val accuracyCircle = remember {
    Polygon(mapView).apply {
      fillPaint.color = (partnerColor and 0x00FFFFFF) or 0x22000000
      outlinePaint.color = (partnerColor and 0x00FFFFFF) or 0x66000000
      outlinePaint.strokeWidth = 2f
    }
  }

  // Keep the map's lifecycle in step with the screen.
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle, myOverlay) {
    myOverlay?.let { mapView.overlays.add(it) }
    val observer = LifecycleEventObserver { _, e ->
      when (e) {
        Lifecycle.Event.ON_RESUME -> {
          mapView.onResume()
          myOverlay?.enableMyLocation()
        }
        Lifecycle.Event.ON_PAUSE -> {
          mapView.onPause()
          myOverlay?.disableMyLocation()
        }
        else -> Unit
      }
    }
    lifecycle.addObserver(observer)
    onDispose {
      lifecycle.removeObserver(observer)
      myOverlay?.let {
        it.disableMyLocation()
        mapView.overlays.remove(it)
      }
    }
  }
  DisposableEffect(Unit) { onDispose { mapView.onDetach() } }

  // "now" ticks so "last seen" text stays fresh; also poll our own fix for the distance readout.
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  var me by remember { mutableStateOf<GeoPoint?>(null) }
  LaunchedEffect(myOverlay) {
    while (true) {
      now = System.currentTimeMillis()
      me = myOverlay?.myLocation
      delay(5_000)
    }
  }

  // Frame the map once, when we first know where people are.
  var framed by remember { mutableStateOf(false) }
  LaunchedEffect(partner != null, me != null) {
    if (!framed && (partner != null || me != null)) {
      frame(mapView, me, partner?.let { GeoPoint(it.lat, it.lon) })
      framed = partner != null && me != null
    }
  }

  var menuOpen by remember { mutableStateOf(false) }
  var showInvite by remember { mutableStateOf(false) }
  var confirmLeave by remember { mutableStateOf(false) }

  Box(Modifier.fillMaxSize()) {
    AndroidView(
      factory = { mapView },
      modifier = Modifier.fillMaxSize(),
      update = { map ->
        map.overlays.remove(partnerMarker)
        map.overlays.remove(accuracyCircle)
        if (partner != null) {
          val p = GeoPoint(partner.lat, partner.lon)
          partnerMarker.position = p
          partnerMarker.title = partner.name
          partnerMarker.icon = initialBadge(map.context, partner.name, partnerColor)
          accuracyCircle.points = Polygon.pointsAsCircle(p, partner.accuracyMeters.toDouble())
          map.overlays.add(accuracyCircle)
          map.overlays.add(partnerMarker)
        }
        map.invalidate()
      },
    )

    // Partner status card
    Card(
      Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth().align(Alignment.TopCenter)
    ) {
      Row(
        Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Filled.Favorite, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
          if (partner == null) {
            Text("Your partner isn't sharing right now", style = MaterialTheme.typography.titleSmall)
            Text(
              "You'll see them here as soon as they turn sharing on.",
              style = MaterialTheme.typography.bodySmall,
            )
          } else {
            Text(partner.name, style = MaterialTheme.typography.titleMedium)
            Text(describe(partner, me, now), style = MaterialTheme.typography.bodySmall)
          }
        }
        Box {
          IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
          DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
              text = { Text("Show invite code") },
              onClick = {
                menuOpen = false
                showInvite = true
              },
            )
            DropdownMenuItem(
              text = { Text("Leave pair") },
              onClick = {
                menuOpen = false
                confirmLeave = true
              },
            )
          }
        }
      }
    }

    // Map controls
    Column(
      Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      horizontalAlignment = Alignment.End,
    ) {
      if (partner != null) {
        SmallFloatingActionButton(onClick = { openDirections(context, partner) }) {
          Icon(Icons.Filled.Directions, "Directions to ${partner.name}")
        }
        SmallFloatingActionButton(
          onClick = { frame(mapView, me, GeoPoint(partner.lat, partner.lon)) }
        ) {
          Icon(Icons.Filled.People, "Show both of us")
        }
      }
      if (myOverlay != null) {
        SmallFloatingActionButton(onClick = { me?.let { frame(mapView, it, null) } }) {
          Icon(Icons.Filled.MyLocation, "Show me")
        }
      }
      ExtendedFloatingActionButton(
        onClick = { onToggleSharing(!state.sharing) },
        icon = {
          Icon(if (state.sharing) Icons.Filled.LocationOn else Icons.Filled.LocationOff, null)
        },
        text = { Text(if (state.sharing) "Sharing on" else "Share my location") },
        containerColor =
          if (state.sharing) MaterialTheme.colorScheme.primaryContainer
          else MaterialTheme.colorScheme.surfaceVariant,
      )
    }

    Text(
      "© OpenStreetMap contributors",
      style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(8.dp),
    )
  }

  if (showInvite && state.invite != null) {
    AlertDialog(
      onDismissRequest = { showInvite = false },
      confirmButton = { TextButton(onClick = { showInvite = false }) { Text("Close") } },
      text = { InviteCard(state.invite.encode(), waiting = false) },
    )
  }
  if (confirmLeave) {
    AlertDialog(
      onDismissRequest = { confirmLeave = false },
      title = { Text("Leave this pair?") },
      text = {
        Text(
          "Sharing stops and your location is removed. To see each other again you'll need to " +
            "start a new pair."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            confirmLeave = false
            onLeave()
          }
        ) {
          Text("Leave")
        }
      },
      dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
    )
  }
}

private fun frame(map: MapView, a: GeoPoint?, b: GeoPoint?) {
  val points = listOfNotNull(a, b)
  if (points.isEmpty()) return
  map.post {
    if (points.size == 1) {
      map.controller.animateTo(points[0], 16.0, 800L)
    } else {
      val box = BoundingBox.fromGeoPointsSafe(points).increaseByScale(1.6f)
      map.zoomToBoundingBox(box, true, 160)
      if (map.zoomLevelDouble > 17.0) map.controller.setZoom(17.0)
    }
  }
}

private fun describe(p: LocationPayload, me: GeoPoint?, now: Long): String {
  val parts = mutableListOf<String>()
  parts +=
    if (now - p.timeMillis < 60_000) "Updated just now"
    else
      "Updated " +
        DateUtils.getRelativeTimeSpanString(p.timeMillis, now, DateUtils.MINUTE_IN_MILLIS)
    if (me != null) {
    val out = FloatArray(1)
    Location.distanceBetween(me.latitude, me.longitude, p.lat, p.lon, out)
    val d = out[0]
    parts += if (d < 1000) "${d.toInt()} m away" else "%.1f km away".format(d / 1000)
  }
  p.batteryPercent?.let { parts += "battery $it%" }
  return parts.joinToString(" · ")
}

private fun openDirections(context: Context, p: LocationPayload) {
  val uri = Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(${Uri.encode(p.name)})")
  try {
    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
  } catch (_: ActivityNotFoundException) {
    Toast.makeText(context, "No maps app installed", Toast.LENGTH_SHORT).show()
  }
}

/** A round colored badge with the person's initial, used as their map marker. */
private fun initialBadge(context: Context, name: String, color: Int): BitmapDrawable {
  val density = context.resources.displayMetrics.density
  val size = (40 * density).toInt()
  val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
  val c = Canvas(bmp)
  val r = size / 2f
  c.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE })
  c.drawCircle(r, r, r - 3 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
  val text =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      this.color = android.graphics.Color.WHITE
      textSize = 18 * density
      textAlign = Paint.Align.CENTER
      isFakeBoldText = true
    }
  val initial = name.trim().firstOrNull()?.uppercase() ?: "♥"
  c.drawText(initial, r, r - (text.descent() + text.ascent()) / 2, text)
  return BitmapDrawable(context.resources, bmp)
}
