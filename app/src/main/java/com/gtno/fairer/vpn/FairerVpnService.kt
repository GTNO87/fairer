package com.gtno.fairer.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.gtno.fairer.MainActivity
import com.gtno.fairer.data.BlocklistManager
import com.gtno.fairer.data.DailyStats
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

class FairerVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.gtno.fairer.START"
        const val ACTION_STOP  = "com.gtno.fairer.STOP"

        @Volatile var isRunning = false

        // AtomicInteger ensures increment from concurrent DNS worker threads is
        // never lost — @Volatile Int with ++ is a non-atomic read-modify-write.
        val todayBlocked = AtomicInteger(0)

        private const val VPN_ADDRESS   = "10.111.111.1"
        private const val VPN_SUBNET    = "10.111.111.0"
        private const val FAKE_DNS_IP   = "10.111.111.111"
        private const val SUBNET_PREFIX = 24

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID      = "fairer_vpn"

        // Thread pool bounds: 8 threads handles ~80 concurrent DNS queries/s
        // (each ~100 ms round-trip to 1.1.1.1). A 128-task bounded queue absorbs
        // short bursts. Tasks beyond the queue are dropped — the resolver retries.
        // This prevents unbounded thread spawning under a DNS flood.
        private const val POOL_CORE    = 4
        private const val POOL_MAX     = 8
        private const val POOL_QUEUE   = 128
    }

    private var pfd: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var dnsExecutor: ThreadPoolExecutor? = null

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
            .setMtu(1500)
            .establish()

        if (established == null) {
            stopSelf()
            return
        }
        pfd = established

        DailyStats.resetIfNewDay(applicationContext)
        todayBlocked.set(DailyStats.getCount(applicationContext))
        BlocklistManager.load(applicationContext)

        dnsExecutor = ThreadPoolExecutor(
            POOL_CORE, POOL_MAX,
            60L, TimeUnit.SECONDS,
            ArrayBlockingQueue(POOL_QUEUE),
            { r -> Thread(r, "fairer-dns").also { it.isDaemon = true } },
            ThreadPoolExecutor.DiscardPolicy(), // silently drop tasks beyond queue capacity
        )

        isRunning = true
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
                if (len <= 0) continue

                val packet = buf.copyOf(len)

                try {
                    dnsExecutor?.execute {
                        val response = DnsInterceptor.handle(
                            buf       = packet,
                            length    = packet.size,
                            onBlocked = { todayBlocked.incrementAndGet() },
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
        DailyStats.save(applicationContext, todayBlocked.get())
        dnsExecutor?.shutdownNow()
        dnsExecutor = null
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
            DailyStats.save(applicationContext, todayBlocked.get())
            dnsExecutor?.shutdownNow()
            dnsExecutor = null
            pfd?.close()
            pfd = null
        }
        super.onDestroy()
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
