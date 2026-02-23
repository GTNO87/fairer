package com.gtno.fairer.data

import android.content.pm.PackageManager
import java.io.File

/**
 * Best-effort resolution of a source UDP port to an app name.
 *
 * Reads /proc/net/udp (and /proc/net/udp6) to find the UID that owns the
 * socket with the given source port. The app's DNS socket is still open at
 * this point because we haven't yet sent the NXDOMAIN response.
 */
internal object AppResolver {

    fun resolve(srcPort: Int, srcIp: ByteArray, pm: PackageManager): Pair<String, String> {
        val uid = getUidForPort(srcPort, srcIp)
        return when {
            uid < 0      -> "Unknown"  to "unknown"
            uid < 10000  -> "System"   to "android"   // kernel / platform process
            else -> {
                val packages = pm.getPackagesForUid(uid)
                if (packages.isNullOrEmpty()) return "Unknown" to "unknown"
                val pkg = packages[0]
                try {
                    val label = pm.getApplicationLabel(
                        pm.getApplicationInfo(pkg, 0)
                    ).toString()
                    label to pkg
                } catch (_: Exception) {
                    pkg to pkg
                }
            }
        }
    }

    private fun getUidForPort(port: Int, ipBytes: ByteArray): Int {
        val portHex = "%04X".format(port)
        // /proc/net/udp stores IPv4 addresses in little-endian hex, e.g. 10.111.111.1 → "016F6F0A"
        val ipHex = "%02X%02X%02X%02X".format(
            ipBytes[3].toInt() and 0xFF,
            ipBytes[2].toInt() and 0xFF,
            ipBytes[1].toInt() and 0xFF,
            ipBytes[0].toInt() and 0xFF,
        )

        // Primary: exact IP+port match in /proc/net/udp (IPv4)
        try {
            File("/proc/net/udp").bufferedReader().use { reader ->
                for (line in reader.lineSequence().drop(1)) {
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size < 8) continue
                    // local_address field is "XXXXXXXX:PPPP"
                    if (parts[1].equals("$ipHex:$portHex", ignoreCase = true)) {
                        return parts[7].toIntOrNull() ?: continue
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback: port-only match in /proc/net/udp6 (IPv6 addresses have a different layout)
        try {
            File("/proc/net/udp6").bufferedReader().use { reader ->
                for (line in reader.lineSequence().drop(1)) {
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size < 8) continue
                    val localPort = parts[1].substringAfterLast(':')
                    if (localPort.equals(portHex, ignoreCase = true)) {
                        return parts[7].toIntOrNull() ?: continue
                    }
                }
            }
        } catch (_: Exception) {}

        return -1
    }
}
