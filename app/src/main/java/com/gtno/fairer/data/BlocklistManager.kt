package com.gtno.fairer.data

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

internal object BlocklistManager {

    @Volatile private var blocked: HashSet<String> = hashSetOf()
    @Volatile private var categories: HashMap<String, String> = hashMapOf()

    // Filenames must match those defined in BlocklistUpdater.COMMUNITY_SOURCES.
    // The category string is used as the default for every domain in that file —
    // community lists don't use the Fairer em-dash header convention, so without
    // a default every entry would fall back to "Other".
    private val COMMUNITY_FILES = listOf(
        "community-disconnect.txt"    to "Tracking",
        "community-hagezi.txt"        to "Tracking",
        "community-hagezi-native.txt" to "Native Tracking",
        "community-easyprivacy.txt"   to "Tracking",
        "community-fanboy-social.txt" to "Social",
    )

    fun load(context: Context) {
        val set    = HashSet<String>(131072)
        val catMap = HashMap<String, String>(131072)

        // Fairer unique list — internal storage first, fall back to bundled asset.
        val fairerFile = File(context.filesDir, "blocklists/manipulation-blocklist.txt")
        val fairerStream: InputStream? = if (fairerFile.exists()) {
            fairerFile.inputStream()
        } else {
            try { context.assets.open("blocklists/manipulation-blocklist.txt") }
            catch (_: Exception) { null }
        }
        if (fairerStream != null) parseStream(fairerStream, set, catMap)

        // Community lists — internal storage only (no asset fallback).
        // Only loaded after a successful update has written them to disk.
        for ((name, defaultCategory) in COMMUNITY_FILES) {
            val file = File(context.filesDir, "blocklists/$name")
            if (file.exists()) parseStream(file.inputStream(), set, catMap, defaultCategory)
        }

        blocked    = set
        categories = catMap
    }

    /**
     * Parses domains from [stream] into [set], tracking category comments of the form
     * "# VendorName — description" into [catMap]. Supports both plain-domain and
     * hosts-file ("0.0.0.0 domain.com") formats.
     */
    private fun parseStream(
        stream: InputStream,
        set: HashSet<String>,
        catMap: HashMap<String, String>,
        defaultCategory: String = "Other",
    ) {
        var currentCategory = defaultCategory
        try {
            stream.use { s ->
                BufferedReader(InputStreamReader(s)).use { reader ->
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
                                // Skip storing "Other" — getCategoryFor() returns it as the default,
                                // so persisting it wastes significant heap for large community lists.
                                if (currentCategory != "Other") catMap[domain] = currentCategory
                            }
                        }
                }
            }
        } catch (_: Exception) {
            // Skip unreadable stream — partial results from other files are still used.
        }
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
     * Combines [isBlocked] and [getCategoryFor] into a single hierarchy walk.
     * Returns the category string if the domain (or a parent) is blocked,
     * or null if it is not blocked. Avoids the double traversal of calling
     * both functions separately on the hot path.
     */
    fun blockResultFor(domain: String): String? {
        var d = domain
        while (d.isNotEmpty()) {
            if (blocked.contains(d)) return categories[d] ?: "Other"
            val dot = d.indexOf('.')
            if (dot < 0) break
            d = d.substring(dot + 1)
        }
        return null
    }

    /** Returns the number of domains currently loaded. */
    fun domainCount(): Int = blocked.size

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
