package com.gtno.fairer.data

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

internal object BlocklistManager {

    @Volatile private var blocked: HashSet<String> = hashSetOf()
    @Volatile private var categories: HashMap<String, String> = hashMapOf()

    fun load(context: Context) {
        val set    = HashSet<String>(65536)
        val catMap = HashMap<String, String>(65536)
        var currentCategory = "Other"

        try {
            context.assets.open("blocklists/manipulation-blocklist.txt").use { stream ->
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    reader.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { line ->
                            if (line.startsWith('#')) {
                                // Only update the category for lines of the form
                                // "VendorName — description…". Multi-line description
                                // continuations and section-header decorators don't
                                // contain an em-dash and are deliberately skipped so
                                // they don't overwrite the current category.
                                val comment = line.removePrefix("#").trim()
                                if (comment.contains('—')) {
                                    currentCategory = comment
                                        .substringBefore(" —")
                                        .substringBefore("—")
                                        .trim()
                                }
                                return@forEach
                            }

                            // Support both "domain.com" and "0.0.0.0 domain.com" host-file formats
                            val domain = if (line.contains(' ')) {
                                line.substringAfterLast(' ').trim()
                            } else {
                                line
                            }.lowercase()

                            // RFC 1035 §3.1 — skip malformed entries longer than 253 chars.
                            if (domain.isNotEmpty() && domain != "localhost" && domain.length <= 253) {
                                set.add(domain)
                                catMap[domain] = currentCategory
                            }
                        }
                }
            }
        } catch (_: Exception) {
            // No blocklist file — block nothing
        }

        blocked    = set
        categories = catMap
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

    /**
     * Returns the category for a blocked domain, walking up the hierarchy
     * the same way [isBlocked] does. Returns "Other" if not found.
     */
    fun getCategoryFor(domain: String): String {
        var d = domain.lowercase()
        while (d.isNotEmpty()) {
            val cat = categories[d]
            if (cat != null) return cat
            val dot = d.indexOf('.')
            if (dot < 0) break
            d = d.substring(dot + 1)
        }
        return "Other"
    }
}
