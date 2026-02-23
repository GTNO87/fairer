package com.gtno.fairer.data

import android.content.Context

internal object UpdatePrefs {

    private const val PREFS            = "fairer_update"
    private const val KEY_LAST_UPDATED = "last_updated_ms"

    fun getLastUpdated(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_UPDATED, 0L)

    fun setLastUpdated(context: Context, timeMs: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_UPDATED, timeMs)
            .apply()
    }
}
