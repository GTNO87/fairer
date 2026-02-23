package com.gtno.fairer

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gtno.fairer.data.BlockLog
import com.gtno.fairer.databinding.ActivityMainBinding
import com.gtno.fairer.vpn.FairerVpnService
import java.text.NumberFormat

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) startVpn()
    }

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

        binding.powerButton.setOnClickListener {
            if (FairerVpnService.isRunning) stopVpn() else requestVpn()
        }

        binding.logButton.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
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
}
