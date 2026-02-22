package com.gtno.fairer

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.gtno.fairer.databinding.ActivityMainBinding
import com.gtno.fairer.vpn.FairerVpnService
import java.text.NumberFormat

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var programmaticToggle = false

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpn()
        } else {
            programmaticToggle = true
            binding.toggle.isChecked = false
            programmaticToggle = false
        }
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

        binding.toggle.setOnCheckedChangeListener { _, isChecked ->
            if (programmaticToggle) return@setOnCheckedChangeListener
            if (isChecked) requestVpn() else stopVpn()
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
        programmaticToggle = true
        binding.toggle.isChecked = FairerVpnService.isRunning
        programmaticToggle = false
        binding.blockedCount.text =
            NumberFormat.getNumberInstance().format(FairerVpnService.todayBlocked.get())
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
