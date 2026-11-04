package app.findus.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun NotConfiguredScreen() {
  Column(
    Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
  ) {
    Text("Almost there", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    Text(
      "This build doesn't include a Firebase configuration yet. Add app/google-services.json " +
        "from your Firebase project and rebuild — the README walks through it step by step."
    )
  }
}

@Composable
fun SetupScreen(
  state: UiState,
  onName: (String) -> Unit,
  onCreate: () -> Unit,
  onJoin: (String) -> Unit,
) {
  var code by remember { mutableStateOf("") }
  val clipboard = LocalClipboardManager.current
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Spacer(Modifier.height(24.dp))
    Icon(
      Icons.Filled.Favorite,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(48.dp).align(Alignment.CenterHorizontally),
    )
    Text(
      "Find Us",
      style = MaterialTheme.typography.headlineMedium,
      modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Text(
      "See each other on one map. Your locations are encrypted on your phones and only the two " +
        "of you can read them.",
      textAlign = TextAlign.Center,
      style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
      value = state.name,
      onValueChange = onName,
      label = { Text("Your name (shown to your partner)") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )

    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("First phone", style = MaterialTheme.typography.titleMedium)
        Text("Start a pair and send the invite code to your partner.")
        Button(
          onClick = onCreate,
          enabled = !state.busy && state.name.isNotBlank(),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Start a new pair")
        }
      }
    }

    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Second phone", style = MaterialTheme.typography.titleMedium)
        Text("Paste the invite code your partner sent you.")
        OutlinedTextField(
          value = code,
          onValueChange = { code = it },
          label = { Text("Invite code") },
          modifier = Modifier.fillMaxWidth(),
          trailingIcon = {
            TextButton(onClick = { clipboard.getText()?.text?.let { code = it } }) { Text("Paste") }
          },
        )
        Button(
          onClick = { onJoin(code) },
          enabled = !state.busy && state.name.isNotBlank() && code.isNotBlank(),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Join")
        }
      }
    }

    if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
  }
}

/** Shows the invite code so it can be sent to the partner. Used while waiting and from the map menu. */
@Composable
fun InviteCard(code: String, waiting: Boolean, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  Card(modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(
        if (waiting) "Send this code to your partner" else "Your invite code",
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        "Anyone with this code could join instead of your partner, so only send it to them " +
          "directly. Once they've joined, the pair is locked to your two phones.",
        style = MaterialTheme.typography.bodySmall,
      )
      Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
      HorizontalDivider()
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = {
            val send =
              Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Join me on Find Us with this code:\n$code")
            context.startActivity(Intent.createChooser(send, "Send invite code"))
          }
        ) {
          Icon(Icons.Filled.Share, null)
          Spacer(Modifier.size(8.dp))
          Text("Send")
        }
        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
          Icon(Icons.Filled.ContentCopy, null)
          Spacer(Modifier.size(8.dp))
          Text("Copy")
        }
      }
    }
  }
}

@Composable
fun WaitingScreen(code: String, onCancel: () -> Unit) {
  Column(
    Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    InviteCard(code, waiting = true)
    CircularProgressIndicator()
    Text("Waiting for your partner to join…")
    TextButton(onClick = onCancel) { Text("Cancel") }
  }
}
