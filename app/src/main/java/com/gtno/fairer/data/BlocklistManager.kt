package com.gtno.fairer.data

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

internal object BlocklistManager {

    @Volatile private var blocked: HashSet<String> = hashSetOf()

    fun load(context: Context) {
        val set = HashSet<String>(65536)
        try {
            context.assets.open("blocklists/manipulation-blocklist.txt").use { stream ->
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    reader.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith('#') }
                        .forEach { line ->
                            // Support both "domain.com" and "0.0.0.0 domain.com" host-file formats
                            val domain = if (line.contains(' ')) {
                                line.substringAfterLast(' ').trim()
                            } else {
                                line
                            }.lowercase()
                            // RFC 1035 §3.1 — skip malformed entries longer than 253 chars.
                            if (domain.isNotEmpty() && domain != "localhost" && domain.length <= 253) {
                                set.add(domain)
                            }
                        }
                }
            }
        } catch (_: Exception) {
            // No blocklist file — block nothing
        }
        blocked = set
    }

    /** Returns true if [domain] or any parent domain is in the blocklist. */
    fun isBlocked(domain: String): Boolean {
        var d = domain
        while (d.isNotEmpty()) {
            if (blocked.contains(d)) return true
            val dot = d.indexOf('.')
            if (dot < 0) break
            d = d.substring(dot + 1)
        }
        return false
    }
}
