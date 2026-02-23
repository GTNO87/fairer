package com.gtno.fairer.data

internal object BlockLog {

    private const val MAX_EVENTS = 1000

    private val lock = Any()
    private val events = ArrayDeque<BlockEvent>()
    private var totalCount = 0

    /** Total number of blocked requests since last clear, including those beyond MAX_EVENTS. */
    val count: Int get() = synchronized(lock) { totalCount }

    fun add(event: BlockEvent) {
        synchronized(lock) {
            totalCount++
            events.addFirst(event)
            while (events.size > MAX_EVENTS) events.removeLast()
        }
    }

    /** Returns all events, most-recent first (capped at MAX_EVENTS). */
    fun getAll(): List<BlockEvent> = synchronized(lock) { events.toList() }

    fun clear() = synchronized(lock) {
        events.clear()
        totalCount = 0
    }
}
