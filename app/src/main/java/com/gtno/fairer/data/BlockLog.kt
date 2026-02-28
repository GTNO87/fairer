package com.gtno.fairer.data

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
