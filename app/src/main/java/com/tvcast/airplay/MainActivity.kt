package com.tvcast.airplay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var nameView: TextView
    private lateinit var addressView: TextView
    private lateinit var overlayBtn: Button

    private val refresh = object : Runnable {
        override fun run() {
            nameView.text = AirPlayService.deviceName(this@MainActivity)
            val ip = AirPlayService.localIp()
            addressView.text = when {
                !AirPlayService.running -> "Starting…"
                ip == null -> "Not connected to a network"
                else -> "$ip:${AirPlayService.port}  •  same Wi-Fi as your iPhone"
            }
            overlayBtn.visibility =
                if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this@MainActivity)) View.VISIBLE
                else View.GONE
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        nameView = findViewById(R.id.name)
        addressView = findViewById(R.id.address)
        overlayBtn = findViewById(R.id.overlayBtn)

        overlayBtn.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                Toast.makeText(this, "This TV doesn't have that setting — just keep the app open.",
                    Toast.LENGTH_LONG).show()
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        AirPlayService.start(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}
