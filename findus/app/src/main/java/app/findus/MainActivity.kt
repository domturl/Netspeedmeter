package app.findus

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.findus.ui.MainViewModel
import app.findus.ui.MapScreen
import app.findus.ui.NotConfiguredScreen
import app.findus.ui.SetupScreen
import app.findus.ui.WaitingScreen

class MainActivity : ComponentActivity() {
  private val vm: MainViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    vm.resumeSharingIfWanted(hasLocationPermission())
    setContent { FindUsTheme { Surface(Modifier.fillMaxSize()) { App(vm) } } }
  }

  private fun hasLocationPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED
}

@Composable
private fun App(vm: MainViewModel) {
  val ui by vm.ui.collectAsStateWithLifecycle()
  val pair by vm.pair.collectAsStateWithLifecycle()
  val context = androidx.compose.ui.platform.LocalContext.current

  fun granted() =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED

  var hasLocation by remember { mutableStateOf(granted()) }
  var startSharingAfterGrant by remember { mutableStateOf(false) }

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      hasLocation = granted()
      if (startSharingAfterGrant) {
        startSharingAfterGrant = false
        if (hasLocation) vm.setSharing(true)
        else
          Toast.makeText(context, "Location permission is needed to share", Toast.LENGTH_LONG).show()
      }
    }
  val permissions = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
  }

  LaunchedEffect(ui.error) {
    ui.error?.let {
      // Setup screen shows errors inline; elsewhere surface them as a toast.
      if (ui.invite != null) {
        Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        vm.clearError()
      }
    }
  }

  val invite = ui.invite
  when {
    !vm.repo.isConfigured -> NotConfiguredScreen()
    invite == null ->
      SetupScreen(ui, onName = vm::setName, onCreate = vm::createPair, onJoin = vm::joinPair)
    pair != null && pair!!.memberCount < 2 && !pair!!.removed ->
      WaitingScreen(invite.encode(), onCancel = vm::leavePair)
    else -> {
      LaunchedEffect(Unit) { if (!hasLocation) permissionLauncher.launch(permissions.toTypedArray()) }
      MapScreen(
        state = ui,
        partner = pair?.partner,
        hasLocationPermission = hasLocation,
        onToggleSharing = { on ->
          if (on && !granted()) {
            startSharingAfterGrant = true
            permissionLauncher.launch(permissions.toTypedArray())
          } else vm.setSharing(on)
        },
        onLeave = vm::leavePair,
      )
    }
  }
}

@Composable
private fun FindUsTheme(content: @Composable () -> Unit) {
  val rose = Color(0xFFD0436E)
  val scheme =
    if (isSystemInDarkTheme())
      darkColorScheme(primary = Color(0xFFFFB1C5), primaryContainer = Color(0xFF8E2049))
    else lightColorScheme(primary = rose, primaryContainer = Color(0xFFFFD9E1))
  MaterialTheme(colorScheme = scheme, content = content)
}
