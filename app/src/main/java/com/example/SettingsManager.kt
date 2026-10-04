package com.example

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("overlay_settings", Context.MODE_PRIVATE)

    var overlayEnabled: Boolean
        get() = prefs.getBoolean("overlay_enabled", false)
        set(value) = prefs.edit().putBoolean("overlay_enabled", value).apply()

    var compactMode: Boolean
        get() = prefs.getBoolean("compact_mode", true)
        set(value) = prefs.edit().putBoolean("compact_mode", value).apply()

    var speedUnitAuto: Boolean
        get() = prefs.getBoolean("speed_unit_auto", true)
        set(value) = prefs.edit().putBoolean("speed_unit_auto", value).apply()

    var useBits: Boolean
        get() = prefs.getBoolean("use_bits", true)
        set(value) = prefs.edit().putBoolean("use_bits", value).apply()

    var autoRecover: Boolean
        get() = prefs.getBoolean("auto_recover", true)
        set(value) = prefs.edit().putBoolean("auto_recover", value).apply()

    var updateIntervalMs: Long
        get() = prefs.getLong("update_interval_ms", 1000L)
        set(value) = prefs.edit().putLong("update_interval_ms", value).apply()

    var colorScheme: String
        get() = prefs.getString("color_scheme", "indigo") ?: "indigo"
        set(value) = prefs.edit().putString("color_scheme", value).apply()

    var opacity: Float
        get() = prefs.getFloat("opacity", 0.85f)
        set(value) = prefs.edit().putFloat("opacity", value).apply()

    var textScale: Float
        get() = prefs.getFloat("text_scale", 1.0f)
        set(value) = prefs.edit().putFloat("text_scale", value).apply()

    var isLocked: Boolean
        get() = prefs.getBoolean("is_locked", false)
        set(value) = prefs.edit().putBoolean("is_locked", value).apply()

    var lastX: Int
        get() = prefs.getInt("last_x", 200)
        set(value) = prefs.edit().putInt("last_x", value).apply()

    var lastY: Int
        get() = prefs.getInt("last_y", 100)
        set(value) = prefs.edit().putInt("last_y", value).apply()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
