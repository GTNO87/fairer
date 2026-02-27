package com.gtno.fairer.data

import android.content.Context

internal object UpdatePrefs {

    private const val PREFS            = "fairer_update"
    private const val KEY_LAST_UPDATED = "last_updated_ms"
    private const val KEY_WIFI_ONLY    = "wifi_only_updates"

    fun getLastUpdated(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_UPDATED, 0L)

    fun setLastUpdated(context: Context, timeMs: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_UPDATED, timeMs)
            .apply()
    }

    /** Whether automatic blocklist updates should be restricted to unmetered (Wi-Fi) connections. Defaults to true. */
    fun getWifiOnly(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIFI_ONLY, true)

    fun setWifiOnly(context: Context, wifiOnly: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WIFI_ONLY, wifiOnly)
            .apply()
    }
}
