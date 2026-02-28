package com.gtno.fairer.data

import android.content.Context
import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal object BlocklistUpdater {

    private const val BLOCKLIST_URL =
        "https://raw.githubusercontent.com/GTNO87/fairer/main/app/src/main/assets/blocklists/manipulation-blocklist.txt"
    private const val SIG_URL =
        "https://raw.githubusercontent.com/GTNO87/fairer/main/app/src/main/assets/blocklists/manipulation-blocklist.txt.sig"

    private const val PUBLIC_KEY_B64 = "JbSCKU9B07jsB2NgsdtKOyo7W6ItPgZhfoeq0eThdic="

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS    = 30_000

    // Response size caps — enforced during streaming to prevent OOM from oversized responses.
    private const val MAX_BYTES_FAIRER    =  5_242_880  // 5 MB  — Fairer list is small by design
    private const val MAX_BYTES_COMMUNITY = 52_428_800  // 50 MB — hosts files can be legitimately large

    // Community-maintained lists — no signature verification required.
    // Failures are non-fatal: a list that fails to download is skipped for this update cycle.
    private val COMMUNITY_SOURCES = listOf(
        "community-disconnect.txt"    to "https://s3.amazonaws.com/lists.disconnect.me/simple_tracking.txt",
        "community-hagezi.txt"        to "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/hosts/pro.txt",
        "community-hagezi-native.txt" to "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/hosts/native.txt",
        "community-easyprivacy.txt"   to "https://v.firebog.net/hosts/Easyprivacy.txt",
        "community-fanboy-social.txt" to "https://secure.fanboy.co.nz/fanboy-social.txt"
    )

    sealed class Result {
        data class Success(val domainCount: Int) : Result()
        data class Failure(val reason: String)  : Result()
    }

    fun update(context: Context): Result {
        val publicKey = try {
            Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)
                .also { require(it.size == 32) { "public key must be 32 bytes" } }
        } catch (e: Exception) {
            return Result.Failure("invalid public key — rebuild with the correct key")
        }

        val blocklistBytes = download(BLOCKLIST_URL, MAX_BYTES_FAIRER)
            ?: return Result.Failure("download failed — check your connection")

        val sigBytes = download(SIG_URL, MAX_BYTES_FAIRER)
            ?: return Result.Failure("signature download failed")

        val signature = parseSig(sigBytes)
            ?: return Result.Failure("malformed signature file")

        if (!verify(blocklistBytes, signature, publicKey)) {
            return Result.Failure("signature verification failed — file may be tampered")
        }

        val dir = File(context.filesDir, "blocklists")
        dir.mkdirs()
        writeAtomic(dir, "manipulation-blocklist.txt", blocklistBytes)

        // Download community lists (no signature verification).
        // Written atomically so a failed mid-write never corrupts the cached copy.
        // Individual failures are non-fatal.
        for ((filename, url) in COMMUNITY_SOURCES) {
            val bytes = download(url, MAX_BYTES_COMMUNITY)
            if (bytes != null) {
                try { writeAtomic(dir, filename, bytes) } catch (_: Exception) { }
            }
        }

        BlocklistManager.load(context)
        UpdatePrefs.setLastUpdated(context)

        return Result.Success(BlocklistManager.domainCount())
    }

    /**
     * Downloads [urlString] over HTTPS, streaming into a buffer.
     * Returns null if the response is not HTTP 200, exceeds [maxBytes], or any error occurs.
     * The size cap is enforced during streaming — the JVM never buffers more than [maxBytes].
     */
    private fun download(urlString: String, maxBytes: Int): ByteArray? {
        return try {
            val conn = URL(urlString).openConnection() as HttpsURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout    = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "Fairer-Android")
            if (conn.responseCode != 200) {
                conn.errorStream?.close()
                return null
            }
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) return null  // response too large — abort
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Writes [bytes] to [filename] inside [dir] atomically via a temp file + rename.
     * If [filename] already exists and the rename fails, falls back to a direct overwrite
     * of the already-buffered bytes.
     */
    private fun writeAtomic(dir: File, filename: String, bytes: ByteArray) {
        val dest = File(dir, filename)
        val tmp  = File(dir, "$filename.tmp")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(dest)) {
                // renameTo can fail on some filesystems — fall back to direct write
                dest.writeBytes(bytes)
            }
        } finally {
            // Always remove the tmp file — even if an exception propagates — so
            // a disk-full or mid-write failure never leaves an orphan on disk.
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun parseSig(bytes: ByteArray): ByteArray? {
        return try {
            val fields = bytes.toString(Charsets.UTF_8)
                .lineSequence()
                .filter { ':' in it }
                .associate { line ->
                    val idx = line.indexOf(':')
                    line.substring(0, idx).trim().lowercase() to
                            line.substring(idx + 1).trim()
                }
            if (fields["algorithm"] != "ed25519") return null
            val sigB64 = fields["signature"] ?: return null
            Base64.decode(sigB64, Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }
    }

    private fun verify(data: ByteArray, signature: ByteArray, publicKeyBytes: ByteArray): Boolean {
        return try {
            val params   = Ed25519PublicKeyParameters(publicKeyBytes, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, params)
            verifier.update(data, 0, data.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }
}
