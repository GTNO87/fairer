package com.gtno.fairer.data

import android.content.Context
import java.time.LocalDate

internal object BlockLog {

    private const val MAX_EVENTS = 1000

    private val lock = Any()
    private val events = ArrayDeque<BlockEvent>()
    private var totalCount = 0
    private var lastResetDate: LocalDate = LocalDate.now()

    /** Total number of blocked requests since last clear, including those beyond MAX_EVENTS. */
    val count: Int get() = synchronized(lock) { totalCount }

    fun add(event: BlockEvent) {
        synchronized(lock) {
            checkMidnightReset()
            totalCount++
            events.addFirst(event)
            while (events.size > MAX_EVENTS) events.removeLast()
        }
    }

    /** Returns all events, most-recent first (capped at MAX_EVENTS). */
    fun getAll(): List<BlockEvent> = synchronized(lock) { events.toList() }

    /**
     * Increments the total blocked count without storing a full event.
     * Used when the screen is off and detailed logging is suppressed.
     */
    fun incrementCount() = synchronized(lock) {
        checkMidnightReset()
        totalCount++
    }

    /**
     * Restores totalCount from SharedPreferences if the saved date is today.
     * Call once on app start so the count survives process death between VPN sessions.
     */
    fun restore(context: Context) = synchronized(lock) {
        val prefs = context.getSharedPreferences("block_log", Context.MODE_PRIVATE)
        val savedDate = prefs.getString("reset_date", null) ?: return@synchronized
        val today = LocalDate.now()
        if (LocalDate.parse(savedDate) == today) {
            totalCount = prefs.getInt("total_count", 0)
            lastResetDate = today
        }
    }

    /**
     * Persists totalCount and lastResetDate to SharedPreferences.
     * Call when the VPN stops so the count survives process death.
     */
    fun save(context: Context) = synchronized(lock) {
        context.getSharedPreferences("block_log", Context.MODE_PRIVATE)
            .edit()
            .putString("reset_date", lastResetDate.toString())
            .putInt("total_count", totalCount)
            .apply()
    }

    fun clear() = synchronized(lock) {
        events.clear()
        totalCount = 0
        lastResetDate = LocalDate.now()
    }

    private fun checkMidnightReset() {
        val today = LocalDate.now()
        if (today != lastResetDate) {
            events.clear()
            totalCount = 0
            lastResetDate = today
        }
    }
}
