package app.findus

import android.app.Application
import org.osmdroid.config.Configuration

class FindUsApp : Application() {
  override fun onCreate() {
    super.onCreate()
    // OpenStreetMap's tile servers require an identifying user agent.
    Configuration.getInstance().apply {
      load(this@FindUsApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
      userAgentValue = packageName
    }
  }
}
