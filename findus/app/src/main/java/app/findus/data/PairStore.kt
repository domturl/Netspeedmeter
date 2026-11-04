package app.findus.data

import android.content.Context
import java.util.Base64

/** Local, on-device settings. App backup is disabled so the pair key never leaves the phone. */
class PairStore(context: Context) {
  private val prefs = context.getSharedPreferences("findus", Context.MODE_PRIVATE)

  var displayName: String
    get() = prefs.getString("name", "") ?: ""
    set(v) = prefs.edit().putString("name", v).apply()

  var invite: InviteCode?
    get() {
      val id = prefs.getString("pairId", null) ?: return null
      val key = prefs.getString("key", null) ?: return null
      return InviteCode(id, Base64.getDecoder().decode(key))
    }
    set(v) {
      prefs
        .edit()
        .apply {
          if (v == null) {
            remove("pairId")
            remove("key")
          } else {
            putString("pairId", v.pairId)
            putString("key", Base64.getEncoder().encodeToString(v.key))
          }
        }
        .apply()
    }

  /** Whether the user wants their location shared; the service is restarted from this. */
  var sharingEnabled: Boolean
    get() = prefs.getBoolean("sharing", false)
    set(v) = prefs.edit().putBoolean("sharing", v).apply()
}
