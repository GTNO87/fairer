package com.gtno.fairer.vpn

import com.gtno.fairer.data.BlocklistManager
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

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

    // DNS response size ceiling. RFC 1035 §4.2.1 caps UDP DNS at 512 bytes; EDNS0 extends
    // this to 64 KB; DoH can carry the full EDNS0 payload. 64 KB is a safe upper bound.
    private const val MAX_DOH_RESPONSE_BYTES = 65_535

    /**
     * Process one raw IPv4 or IPv6 packet [buf] of [length] bytes.
     *
     * [onBlocked] is called (on a worker thread) when a domain is blocked,
     * receiving the domain name, its blocklist category, the UDP source port
     * (used for app attribution via /proc/net/udp), and the source IP bytes
     * (4 bytes for IPv4, 16 bytes for IPv6).
     *
     * Returns the response IP packet to write back to the TUN fd, or null to drop.
     */
    fun handle(
        buf: ByteArray,
        length: Int,
        onBlocked: (domain: String, category: String, srcPort: Int, srcIp: ByteArray) -> Unit,
    ): ByteArray? {
        if (length < 1) return null
        return when ((buf[0].toInt() and 0xF0) ushr 4) {
            4 -> handleV4(buf, length, onBlocked)
            6 -> handleV6(buf, length, onBlocked)
            else -> null
        }
    }

    // ── IPv4 handler ──────────────────────────────────────────────────────────

    private fun handleV4(
        buf: ByteArray,
        length: Int,
        onBlocked: (domain: String, category: String, srcPort: Int, srcIp: ByteArray) -> Unit,
    ): ByteArray? {
        if (length < 28) return null
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
            buildV4Packet(srcPort, clientIp, fakeIp, nxdomainDns(buf, dnsBase, dnsLen))
        } else {
            val dnsQuery = buf.copyOfRange(dnsBase, dnsBase + dnsLen)
            val dnsReply = forwardDns(dnsQuery) ?: return null
            buildV4Packet(srcPort, clientIp, fakeIp, dnsReply)
        }
    }

    // ── IPv6 handler ──────────────────────────────────────────────────────────

    private fun handleV6(
        buf: ByteArray,
        length: Int,
        onBlocked: (domain: String, category: String, srcPort: Int, srcIp: ByteArray) -> Unit,
    ): ByteArray? {
        // IPv6 fixed header is 40 bytes; need at least 40 + 8 (UDP) + 12 (DNS min) = 60.
        if (length < 60) return null

        // Require Next Header = UDP (17). Packets with extension headers are dropped —
        // DNS queries to our fake IP will never carry extension headers.
        if (buf[6].toInt() and 0xFF != 17) return null

        // ── Require destination port 53 ────────────────────────────────────────
        val udpBase = 40
        val srcPort = ((buf[udpBase].toInt() and 0xFF) shl 8) or
                       (buf[udpBase + 1].toInt() and 0xFF)
        val dstPort = ((buf[udpBase + 2].toInt() and 0xFF) shl 8) or
                       (buf[udpBase + 3].toInt() and 0xFF)
        if (dstPort != 53) return null

        // ── Require DNS query (QR bit = 0) ────────────────────────────────────
        val dnsBase = 48
        val dnsLen  = length - dnsBase
        if (dnsLen < 12) return null
        if (buf[dnsBase + 2].toInt() and 0x80 != 0) return null

        // ── Source and destination IPs for response routing ───────────────────
        val clientIp = buf.copyOfRange(8, 24)   // IPv6 source address (16 bytes)
        val fakeIp   = buf.copyOfRange(24, 40)  // IPv6 destination address (16 bytes)

        // ── Extract queried domain ────────────────────────────────────────────
        val domain = extractDomain(buf, dnsBase + 12, dnsBase + dnsLen) ?: return null

        // ── Block or forward ──────────────────────────────────────────────────
        return if (BlocklistManager.isBlocked(domain)) {
            onBlocked(domain, BlocklistManager.getCategoryFor(domain), srcPort, clientIp)
            buildV6Packet(srcPort, clientIp, fakeIp, nxdomainDns(buf, dnsBase, dnsLen))
        } else {
            val dnsQuery = buf.copyOfRange(dnsBase, dnsBase + dnsLen)
            val dnsReply = forwardDns(dnsQuery) ?: return null
            buildV6Packet(srcPort, clientIp, fakeIp, dnsReply)
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

            val response = conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > MAX_DOH_RESPONSE_BYTES) return null  // abort oversized response
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
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

    private fun buildV4Packet(
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

    // ── IPv6 + UDP packet builder ──────────────────────────────────────────────

    private fun buildV6Packet(
        clientPort: Int,
        clientIp: ByteArray,  // 16 bytes
        fakeIp: ByteArray,    // 16 bytes
        dnsPay: ByteArray,
    ): ByteArray {
        val udpLen   = 8 + dnsPay.size
        val totalLen = 40 + udpLen
        val out      = ByteArray(totalLen)

        // IPv6 header (40 bytes); ByteArray is zero-initialised so TC/Flow Label are already 0.
        out[0] = 0x60.toByte()                    // Version=6, TC=0
        out[4] = (udpLen shr 8).toByte()
        out[5] = (udpLen and 0xFF).toByte()       // Payload length
        out[6] = 17                                // Next Header = UDP
        out[7] = 64                                // Hop Limit
        fakeIp.copyInto(out, 8)                   // Src = fake DNS IPv6
        clientIp.copyInto(out, 24)                // Dst = client

        // UDP header (8 bytes at offset 40)
        out[40] = 0; out[41] = 53                 // Src port = 53
        out[42] = (clientPort shr 8).toByte()
        out[43] = (clientPort and 0xFF).toByte()  // Dst port = client's ephemeral port
        out[44] = (udpLen shr 8).toByte()
        out[45] = (udpLen and 0xFF).toByte()
        // out[46..47] = 0 (checksum placeholder)

        dnsPay.copyInto(out, 48)

        // UDP checksum is mandatory in IPv6 (RFC 2460 §8.1)
        val cksum = udpChecksumV6(out, fakeIp, clientIp, udpLen)
        out[46] = (cksum shr 8).toByte()
        out[47] = (cksum and 0xFF).toByte()

        return out
    }

    // ── Checksum helpers ──────────────────────────────────────────────────────

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

    /**
     * Computes the mandatory UDP checksum for an IPv6 packet (RFC 2460 §8.1).
     *
     * The pseudo-header covers: src IP (16) + dst IP (16) + upper-layer length (4) +
     * zeros (3) + next-header (1), followed by the UDP header and payload starting
     * at byte offset 40 in [packet].
     */
    private fun udpChecksumV6(
        packet: ByteArray,
        srcIp: ByteArray,
        dstIp: ByteArray,
        udpLen: Int,
    ): Int {
        var sum = 0

        // Src and dst IPv6 addresses (16 bytes each, summed as 16-bit words)
        for (i in 0 until 16 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }

        // Upper-layer packet length (4 bytes big-endian); upper two bytes are 0 for ≤64 KB
        sum += udpLen

        // Next Header field = 17 (UDP)
        sum += 17

        // UDP header + payload (at byte offset 40 in the packet)
        val udpEnd = 40 + udpLen
        var i = 40
        while (i < udpEnd - 1) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (udpLen % 2 != 0) {
            // Odd trailing byte: pad with a zero byte on the right
            sum += (packet[udpEnd - 1].toInt() and 0xFF) shl 8
        }

        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val result = sum.inv() and 0xFFFF
        // RFC 768: if the computed checksum is zero, transmit 0xFFFF
        return if (result == 0) 0xFFFF else result
    }
}
