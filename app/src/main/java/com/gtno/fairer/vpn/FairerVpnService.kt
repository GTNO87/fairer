package com.gtno.fairer.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.gtno.fairer.MainActivity
import com.gtno.fairer.data.AppResolver
import com.gtno.fairer.data.BlockEvent
import com.gtno.fairer.data.BlockLog
import com.gtno.fairer.data.BlocklistManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

class FairerVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.gtno.fairer.START"
        const val ACTION_STOP  = "com.gtno.fairer.STOP"

        @Volatile var isRunning = false

        private const val VPN_ADDRESS    = "10.111.111.1"
        private const val VPN_SUBNET     = "10.111.111.0"
        private const val FAKE_DNS_IP    = "10.111.111.111"
        private const val SUBNET_PREFIX  = 24

        // IPv6: route only the fake DNS server address through the TUN so all other
        // IPv6 traffic stays on the real network (same strategy as IPv4).
        private const val VPN_ADDRESS_V6 = "fdab:fab0::1"
        private const val FAKE_DNS_IP_V6 = "fdab:fab0::111"
        private const val PREFIX_V6      = 128

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID      = "fairer_vpn"

        // Thread pool: core=0 so threads die after 60 s idle rather than sitting
        // parked forever. With a bounded queue, the executor still creates a worker
        // immediately for the first queued task, so there is no latency penalty.
        // Max=8 handles ~80 concurrent DoH round-trips; 128-task queue absorbs bursts.
        private const val POOL_CORE    = 0
        private const val POOL_MAX     = 8
        private const val POOL_QUEUE   = 128
    }

    private var pfd: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    @Volatile private var dnsExecutor: ThreadPoolExecutor? = null

    // Screen state — updated by a BroadcastReceiver registered in startVpn().
    // When false, AppResolver and BlockLog event writes are skipped to save battery.
    @Volatile private var screenOn = true
    private var screenReceiver: BroadcastReceiver? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        val established = Builder()
            .setSession("Fairer")
            .addAddress(VPN_ADDRESS, SUBNET_PREFIX)
            .addRoute(VPN_SUBNET, SUBNET_PREFIX)
            .addDnsServer(FAKE_DNS_IP)
            .addAddress(VPN_ADDRESS_V6, PREFIX_V6)
            .addRoute(FAKE_DNS_IP_V6, PREFIX_V6)
            .addDnsServer(FAKE_DNS_IP_V6)
            .setMtu(1500)
            .establish()

        if (established == null) {
            stopSelf()
            return
        }
        pfd = established

        BlocklistManager.load(applicationContext)

        dnsExecutor = ThreadPoolExecutor(
            POOL_CORE, POOL_MAX,
            60L, TimeUnit.SECONDS,
            ArrayBlockingQueue(POOL_QUEUE),
            { r -> Thread(r, "fairer-dns").also { it.isDaemon = true } },
            ThreadPoolExecutor.DiscardPolicy(), // silently drop tasks beyond queue capacity
        )

        isRunning = true
        registerScreenReceiver()
        vpnThread = Thread({ runLoop(established) }, "fairer-vpn").apply {
            isDaemon = true
            start()
        }
    }

    private fun runLoop(pfd: ParcelFileDescriptor) {
        val buf    = ByteArray(32767)
        val input  = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val writeLock = Any()

        try {
            while (!Thread.currentThread().isInterrupted) {
                val len = input.read(buf)
                if (len < 0) break   // TUN fd closed — exit cleanly
                if (len == 0) continue

                val packet = buf.copyOf(len)

                try {
                    dnsExecutor?.execute {
                        val response = DnsInterceptor.handle(
                            buf       = packet,
                            length    = packet.size,
                            onBlocked = { domain, category, srcPort, srcIp ->
                                if (screenOn) {
                                    // Screen on: resolve app name and store full event.
                                    try {
                                        val (appName, pkg) = AppResolver.resolve(srcPort, srcIp, packageManager)
                                        BlockLog.add(BlockEvent(domain, category, appName, pkg))
                                    } catch (_: Exception) {
                                        BlockLog.add(BlockEvent(domain, category, "Unknown", "unknown"))
                                    }
                                } else {
                                    // Screen off: skip /proc/net/udp read and event allocation;
                                    // just increment the count so the tally stays accurate.
                                    BlockLog.incrementCount()
                                }
                            },
                        ) ?: return@execute

                        synchronized(writeLock) {
                            try { output.write(response) } catch (_: IOException) { }
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    // Executor shut down mid-flight; drop this packet.
                }
            }
        } catch (_: IOException) {
            // pfd was closed — normal shutdown path.
        } finally {
            isRunning = false
        }
    }

    private fun stopVpn() {
        isRunning = false
        unregisterScreenReceiver()
        dnsExecutor?.shutdownNow()
        dnsExecutor = null
        DnsCache.clear()
        AppResolver.clearCache()
        pfd?.close()
        pfd = null
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    override fun onRevoke() = stopVpn()

    override fun onDestroy() {
        if (isRunning || pfd != null) {
            isRunning = false
            dnsExecutor?.shutdownNow()
            dnsExecutor = null
            pfd?.close()
            pfd = null
        }
        unregisterScreenReceiver()
        super.onDestroy()
    }

    // ── Screen state ───────────────────────────────────────────────────────────

    private fun registerScreenReceiver() {
        // Seed initial state from PowerManager so we're correct if the VPN
        // starts while the screen is already off.
        screenOn = getSystemService(PowerManager::class.java).isInteractive

        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                screenOn = intent.action == Intent.ACTION_SCREEN_ON
            }
        }
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) { }
            screenReceiver = null
        }
    }

    // ── Notification ───────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Fairer VPN",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Fairer is active" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fairer is active")
            .setContentText("Blocking commercial tracking")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
