package com.gtno.fairer.data

import android.content.Context
import java.time.LocalDate

internal object DailyStats {

    private const val PREFS = "fairer_stats"
    private const val KEY_DATE = "date"
    private const val KEY_COUNT = "count"

    fun resetIfNewDay(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (prefs.getString(KEY_DATE, null) != today) {
            prefs.edit().putString(KEY_DATE, today).putInt(KEY_COUNT, 0).apply()
        }
    }

    fun getCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_COUNT, 0)

    fun save(context: Context, count: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_COUNT, count)
            .apply()
    }
}
