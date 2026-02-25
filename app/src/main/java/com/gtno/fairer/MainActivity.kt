package com.gtno.fairer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gtno.fairer.data.BlockLog
import com.gtno.fairer.data.BlocklistUpdater
import com.gtno.fairer.data.UpdatePrefs
import com.gtno.fairer.databinding.ActivityMainBinding
import com.gtno.fairer.vpn.FairerVpnService
import com.gtno.fairer.worker.BlocklistUpdateWorker
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isUpdating = false

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) startVpn()
    }

    // Handles the POST_NOTIFICATIONS permission result on Android 13+.
    // No UI update needed — the VPN foreground notification will appear automatically
    // if permission was granted.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refreshUi()
            refreshHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermission()

        binding.powerButton.setOnClickListener {
            if (FairerVpnService.isRunning) stopVpn() else requestVpn()
        }

        binding.logButton.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        binding.updateButton.setOnClickListener { startManualUpdate() }

        scheduleWeeklyUpdate()
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshTask)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshTask)
    }

    private fun refreshUi() {
        val active = FairerVpnService.isRunning

        if (active) {
            binding.powerButton.setBackgroundResource(R.drawable.bg_power_button_active)
            binding.powerButton.imageTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.teal_primary))
        } else {
            binding.powerButton.setBackgroundResource(R.drawable.bg_power_button_inactive)
            binding.powerButton.imageTintList =
                ColorStateList.valueOf(Color.WHITE)
        }

        binding.statusText.text = getString(
            if (active) R.string.status_active else R.string.status_inactive
        )

        binding.blockedCount.text =
            NumberFormat.getNumberInstance().format(BlockLog.count)

        val lastMs = UpdatePrefs.getLastUpdated(this)
        binding.lastUpdatedText.text = if (lastMs == 0L) {
            getString(R.string.last_updated_never)
        } else {
            getString(
                R.string.last_updated_date,
                SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(lastMs))
            )
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestVpn() {
        val intent = VpnService.prepare(this)
        if (intent == null) startVpn() else vpnLauncher.launch(intent)
    }

    private fun startVpn() {
        startForegroundService(
            android.content.Intent(this, FairerVpnService::class.java).apply {
                action = FairerVpnService.ACTION_START
            }
        )
    }

    private fun stopVpn() {
        startService(
            android.content.Intent(this, FairerVpnService::class.java).apply {
                action = FairerVpnService.ACTION_STOP
            }
        )
    }

    private fun startManualUpdate() {
        if (isUpdating) return
        isUpdating = true
        binding.updateButton.isEnabled = false
        binding.updateStatusText.visibility = View.VISIBLE
        binding.updateStatusText.text = getString(R.string.update_checking)

        Thread {
            val result = BlocklistUpdater.update(this)
            runOnUiThread {
                isUpdating = false
                binding.updateButton.isEnabled = true
                binding.updateStatusText.text = when (result) {
                    is BlocklistUpdater.Result.Success ->
                        getString(
                            R.string.update_success,
                            NumberFormat.getNumberInstance().format(result.domainCount)
                        )
                    is BlocklistUpdater.Result.Failure ->
                        getString(R.string.update_failed, result.reason)
                }
                refreshUi()
            }
        }.start()
    }

    private fun scheduleWeeklyUpdate() {
        val request = PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(7, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "blocklist_update",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
