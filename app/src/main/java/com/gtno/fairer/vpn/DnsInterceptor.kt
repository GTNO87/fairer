package com.gtno.fairer.vpn

import com.gtno.fairer.data.BlocklistManager
import javax.net.ssl.HttpsURLConnection
import java.net.URL

/**
 * Stateless DNS interceptor. Called once per IP packet, potentially from
 * multiple threads concurrently — all state is stack-local or immutable.
 */
internal object DnsInterceptor {

    // Direct IP avoids a chicken-and-egg problem: resolving cloudflare-dns.com
    // would itself require DNS, which we are intercepting. Cloudflare's TLS
    // certificate covers 1.1.1.1 via IP SAN, so hostname validation succeeds.
    private val DOH_URL = URL("https://1.1.1.1/dns-query")
    private const val DOH_MIME       = "application/dns-message"
    private const val DOH_TIMEOUT_MS = 3000

    // RFC 1035 §3.1: total domain name ≤ 253 characters.
    private const val MAX_DOMAIN_LEN = 253

    /**
     * Process one raw IPv4 packet [buf] of [length] bytes.
     *
     * [onBlocked] is called (on a worker thread) when a domain is blocked,
     * receiving the domain name, its blocklist category, and the UDP source
     * port (used for app attribution via /proc/net/udp).
     *
     * Returns the response IP packet to write back to the TUN fd, or null to drop.
     */
    fun handle(
        buf: ByteArray,
        length: Int,
        onBlocked: (domain: String, category: String, srcPort: Int, srcIp: ByteArray) -> Unit,
    ): ByteArray? {
        // ── Require IPv4 ───────────────────────────────────────────────────────
        if (length < 28) return null
        val version = (buf[0].toInt() and 0xF0) ushr 4
        if (version != 4) return null
        val ipHdrLen = (buf[0].toInt() and 0x0F) * 4
        if (length < ipHdrLen + 8) return null

        // ── Require UDP (protocol 17) ──────────────────────────────────────────
        if (buf[9].toInt() and 0xFF != 17) return null

        // ── Require destination port 53 ────────────────────────────────────────
        val udpBase = ipHdrLen
        val srcPort = ((buf[udpBase].toInt() and 0xFF) shl 8) or
                       (buf[udpBase + 1].toInt() and 0xFF)
        val dstPort = ((buf[udpBase + 2].toInt() and 0xFF) shl 8) or
                       (buf[udpBase + 3].toInt() and 0xFF)
        if (dstPort != 53) return null

        // ── Require DNS query (QR bit = 0) ────────────────────────────────────
        val dnsBase = ipHdrLen + 8
        val dnsLen  = length - dnsBase
        if (dnsLen < 12) return null
        if (buf[dnsBase + 2].toInt() and 0x80 != 0) return null

        // ── Source and destination IPs for response routing ───────────────────
        val clientIp = buf.copyOfRange(12, 16)
        val fakeIp   = buf.copyOfRange(16, 20)

        // ── Extract queried domain ────────────────────────────────────────────
        val domain = extractDomain(buf, dnsBase + 12, dnsBase + dnsLen) ?: return null

        // ── Block or forward ──────────────────────────────────────────────────
        return if (BlocklistManager.isBlocked(domain)) {
            onBlocked(domain, BlocklistManager.getCategoryFor(domain), srcPort, clientIp)
            buildPacket(srcPort, clientIp, fakeIp, nxdomainDns(buf, dnsBase, dnsLen))
        } else {
            val dnsQuery = buf.copyOfRange(dnsBase, dnsBase + dnsLen)
            val dnsReply = forwardDns(dnsQuery) ?: return null
            buildPacket(srcPort, clientIp, fakeIp, dnsReply)
        }
    }

    // ── Domain extraction ──────────────────────────────────────────────────────

    private fun extractDomain(buf: ByteArray, pos: Int, limit: Int): String? {
        var p = pos
        val sb = StringBuilder()
        while (p < limit) {
            val len = buf[p++].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 == 0xC0) return null // compression pointer — not in queries
            if (p + len > limit) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until len) sb.append((buf[p++].toInt() and 0xFF).toChar())
            // RFC 1035 §3.1 — total domain name must not exceed 253 characters.
            if (sb.length > MAX_DOMAIN_LEN) return null
        }
        return sb.toString().lowercase().ifEmpty { null }
    }

    // ── DNS response builders ──────────────────────────────────────────────────

    private fun nxdomainDns(query: ByteArray, dnsBase: Int, dnsLen: Int): ByteArray {
        val resp = query.copyOfRange(dnsBase, dnsBase + dnsLen)
        // Flags: QR=1, OPCODE=0, AA=0, TC=0, RD=1 | RA=1, Z=000, RCODE=3 (NXDOMAIN)
        resp[2] = 0x81.toByte()
        resp[3] = 0x83.toByte()
        // Zero answer / authority / additional counts
        resp[6] = 0; resp[7] = 0
        resp[8] = 0; resp[9] = 0
        resp[10] = 0; resp[11] = 0
        return resp
    }

    // ── DNS-over-HTTPS forwarding ──────────────────────────────────────────────

    private fun forwardDns(query: ByteArray): ByteArray? {
        if (query.size < 2) return null

        // Capture the query transaction ID so we can validate the response.
        val queryTxId = ((query[0].toInt() and 0xFF) shl 8) or (query[1].toInt() and 0xFF)

        return try {
            val conn = (DOH_URL.openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", DOH_MIME)
                setRequestProperty("Accept", DOH_MIME)
                connectTimeout = DOH_TIMEOUT_MS
                readTimeout    = DOH_TIMEOUT_MS
                doOutput       = true
                useCaches      = false
            }

            conn.outputStream.use { it.write(query) }

            if (conn.responseCode != 200) {
                conn.errorStream?.close()
                return null
            }

            val response = conn.inputStream.use { it.readBytes() }
            if (response.size < 2) return null

            // Validate that the response transaction ID matches the query.
            val replyTxId = ((response[0].toInt() and 0xFF) shl 8) or (response[1].toInt() and 0xFF)
            if (replyTxId != queryTxId) return null

            response
        } catch (_: Exception) {
            null
        }
    }

    // ── IPv4 + UDP packet builder ──────────────────────────────────────────────

    private fun buildPacket(
        clientPort: Int,
        clientIp: ByteArray,
        fakeIp: ByteArray,
        dnsPay: ByteArray,
    ): ByteArray {
        val udpLen   = 8 + dnsPay.size
        val totalLen = 20 + udpLen
        val out      = ByteArray(totalLen)

        // IPv4 header
        out[0]  = 0x45.toByte()                   // Version=4, IHL=5 (20 bytes)
        out[1]  = 0                                // DSCP/ECN
        out[2]  = (totalLen shr 8).toByte()
        out[3]  = (totalLen and 0xFF).toByte()
        out[4]  = 0; out[5] = 0                   // Identification
        out[6]  = 0x40.toByte()                   // Flags: DF
        out[7]  = 0                                // Fragment offset
        out[8]  = 64                               // TTL
        out[9]  = 17                               // Protocol: UDP
        out[10] = 0; out[11] = 0                  // Checksum placeholder
        fakeIp.copyInto(out, 12)                  // Src = fake DNS IP
        clientIp.copyInto(out, 16)                // Dst = TUN client

        val cksum = ipChecksum(out, 0, 20)
        out[10] = (cksum shr 8).toByte()
        out[11] = (cksum and 0xFF).toByte()

        // UDP header
        out[20] = 0; out[21] = 53                 // Src port = 53
        out[22] = (clientPort shr 8).toByte()
        out[23] = (clientPort and 0xFF).toByte()  // Dst port = client's ephemeral port
        out[24] = (udpLen shr 8).toByte()
        out[25] = (udpLen and 0xFF).toByte()
        out[26] = 0; out[27] = 0                  // Checksum = 0 (optional for IPv4 UDP)

        dnsPay.copyInto(out, 28)
        return out
    }

    private fun ipChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i   = offset
        while (i < offset + length - 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }
}
