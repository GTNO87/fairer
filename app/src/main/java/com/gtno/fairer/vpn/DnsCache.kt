package com.gtno.fairer.vpn

/**
 * Thread-safe, TTL-aware DNS response cache.
 *
 * Keyed on "domain:qtype". A cached response is returned immediately
 * (with the caller's transaction ID patched in) so [DnsInterceptor]
 * never needs to open a DoH connection for a recently-seen query.
 *
 * Battery rationale: every DoH round-trip wakes the network radio and
 * keeps it alive for several seconds. Apps hammer the same tracker
 * domains on every network event. Caching even 30–60 seconds of responses
 * eliminates the vast majority of repeat DoH calls.
 */
internal object DnsCache {

    private const val MAX_ENTRIES   = 1_024
    private const val MIN_TTL_MS    = 10_000L   // 10 s floor — respect app needs
    private const val MAX_TTL_MS    = 300_000L  // 5 min ceiling — cap stale risk
    private const val FALLBACK_TTL  = 30_000L   // used when TTL cannot be parsed

    private class Entry(val response: ByteArray, val expiresAt: Long)

    // Access-ordered LRU: eldest entry evicted when MAX_ENTRIES is reached.
    @Suppress("serial")
    private val cache = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Entry>) = size > MAX_ENTRIES
    }
    private val lock = Any()

    /** Build a cache key from a normalised domain name and DNS qtype integer. */
    fun key(domain: String, qtype: Int): String = "$domain:$qtype"

    /**
     * Returns a cached DNS response with [txId] spliced into bytes 0–1, or null on
     * a cache miss or if the cached entry has already expired.
     */
    fun get(key: String, txId: Int): ByteArray? {
        val entry = synchronized(lock) {
            val e = cache[key] ?: return null
            if (System.currentTimeMillis() >= e.expiresAt) {
                cache.remove(key)
                return null
            }
            e
        }
        // Patch the transaction ID into a defensive copy — never mutate the cached bytes.
        val out = entry.response.copyOf()
        out[0] = (txId ushr 8).toByte()
        out[1] = (txId and 0xFF).toByte()
        return out
    }

    /**
     * Stores a DNS response. The TTL is extracted from the first Answer RR;
     * [FALLBACK_TTL] is used when parsing fails or there are no answers.
     */
    fun put(key: String, response: ByteArray) {
        val ttlMs = parseTtlMs(response).coerceIn(MIN_TTL_MS, MAX_TTL_MS)
        val entry = Entry(response.copyOf(), System.currentTimeMillis() + ttlMs)
        synchronized(lock) { cache[key] = entry }
    }

    fun clear() = synchronized(lock) { cache.clear() }

    // ── TTL extraction ─────────────────────────────────────────────────────────

    /**
     * Walks the DNS response to read the TTL of the first Answer RR.
     * Returns the TTL in milliseconds, or [FALLBACK_TTL] on any parse error.
     */
    private fun parseTtlMs(buf: ByteArray): Long {
        return try {
            if (buf.size < 12) return FALLBACK_TTL
            val anCount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
            if (anCount == 0) return FALLBACK_TTL  // NXDOMAIN etc. — use fallback

            // Skip Question section: QNAME + QTYPE(2) + QCLASS(2)
            var pos = skipName(buf, 12) ?: return FALLBACK_TTL
            pos += 4
            if (pos > buf.size) return FALLBACK_TTL

            // First Answer RR: NAME + TYPE(2) + CLASS(2) + TTL(4) + RDLENGTH(2) + RDATA
            pos = skipName(buf, pos) ?: return FALLBACK_TTL
            // pos now points at TYPE; TTL is at pos+4
            if (pos + 8 > buf.size) return FALLBACK_TTL

            val ttlSec = ((buf[pos + 4].toInt() and 0xFF) shl 24) or
                         ((buf[pos + 5].toInt() and 0xFF) shl 16) or
                         ((buf[pos + 6].toInt() and 0xFF) shl 8)  or
                          (buf[pos + 7].toInt() and 0xFF)
            if (ttlSec <= 0) return FALLBACK_TTL

            ttlSec * 1000L
        } catch (_: Exception) {
            FALLBACK_TTL
        }
    }

    /**
     * Advances past a DNS wire-format name (labels or a compression pointer).
     * Returns the index of the first byte after the name, or null if malformed.
     */
    private fun skipName(buf: ByteArray, startPos: Int): Int? {
        var pos = startPos
        while (pos < buf.size) {
            val b = buf[pos].toInt() and 0xFF
            when {
                b == 0             -> return pos + 1   // root label — end of name
                b and 0xC0 == 0xC0 -> return pos + 2   // compression pointer — fixed 2 bytes
                else               -> pos += b + 1     // regular label: skip length + bytes
            }
        }
        return null // ran off end of buffer — malformed
    }
}
