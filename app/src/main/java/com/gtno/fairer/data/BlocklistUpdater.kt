package com.gtno.fairer.data

import android.content.Context
import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
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

        val blocklistBytes = download(BLOCKLIST_URL)
            ?: return Result.Failure("download failed — check your connection")

        val sigBytes = download(SIG_URL)
            ?: return Result.Failure("signature download failed")

        val signature = parseSig(sigBytes)
            ?: return Result.Failure("malformed signature file")

        if (!verify(blocklistBytes, signature, publicKey)) {
            return Result.Failure("signature verification failed — file may be tampered")
        }

        val dir = File(context.filesDir, "blocklists")
        dir.mkdirs()
        File(dir, "manipulation-blocklist.txt").writeBytes(blocklistBytes)

        BlocklistManager.load(context)
        UpdatePrefs.setLastUpdated(context)

        return Result.Success(BlocklistManager.domainCount())
    }

    private fun download(urlString: String): ByteArray? {
        return try {
            val conn = URL(urlString).openConnection() as HttpsURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout    = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "Fairer-Android")
            if (conn.responseCode != 200) {
                conn.errorStream?.close()
                return null
            }
            conn.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            null
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
