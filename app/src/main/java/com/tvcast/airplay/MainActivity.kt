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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var nameView: TextView
    private lateinit var statusView: TextView
    private lateinit var addressView: TextView
    private lateinit var overlayBtn: Button
    private lateinit var updateBtn: Button
    private lateinit var updateText: TextView
    private lateinit var autoSwitch: Switch

    @Volatile private var pendingUpdate: Updater.Update? = null
    @Volatile private var busy = false

    private val refresh = object : Runnable {
        override fun run() {
            nameView.text = AirPlayService.deviceName(this@MainActivity)
            val e = AirPlayService.engine
            statusView.text = when {
                AirPlayService.error != null -> AirPlayService.error
                !AirPlayService.running -> "Starting…"
                e?.mirroring == true -> "Mirroring from ${e?.clientName ?: "Apple device"}"
                (e?.connections ?: 0) > 0 -> "Connected to ${e?.clientName ?: "Apple device"}"
                else -> getString(R.string.ready)
            }
            val ip = AirPlayService.localIp()
            addressView.text = if (ip == null) "Not connected to Wi-Fi"
                else "$ip  •  v${BuildConfig.VERSION_NAME}  •  same Wi-Fi as your iPhone"
            overlayBtn.visibility =
                if (!Settings.canDrawOverlays(this@MainActivity)) View.VISIBLE else View.GONE
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        nameView = findViewById(R.id.name)
        statusView = findViewById(R.id.status)
        addressView = findViewById(R.id.address)
        overlayBtn = findViewById(R.id.overlayBtn)
        updateBtn = findViewById(R.id.updateBtn)
        updateText = findViewById(R.id.updateText)
        autoSwitch = findViewById(R.id.autoUpdate)

        overlayBtn.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                Toast.makeText(this, "This device doesn't have that setting — just keep the app open.",
                    Toast.LENGTH_LONG).show()
            }
        }

        autoSwitch.isChecked = Updater.isAutoUpdate(this)
        autoSwitch.setOnCheckedChangeListener { _, on -> Updater.setAutoUpdate(this, on) }

        updateBtn.setOnClickListener {
            val u = pendingUpdate
            if (u != null) installUpdate(u) else checkForUpdate(userAsked = true)
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        AirPlayService.start(this)
        checkForUpdate(userAsked = false)
    }

    private fun checkForUpdate(userAsked: Boolean) {
        if (busy) return
        busy = true
        updateText.text = "Checking for updates…"
        thread(name = "update-check") {
            val u = Updater.check()
            busy = false
            runOnUiThread {
                pendingUpdate = u
                if (u == null) {
                    updateText.text = "You have the latest version (v${BuildConfig.VERSION_NAME})"
                    updateBtn.text = "Check for updates"
                    if (userAsked) Toast.makeText(this, "Already up to date", Toast.LENGTH_SHORT).show()
                } else {
                    updateText.text = "New version available: v${u.versionName}"
                    updateBtn.text = "Update now"
                    if (Updater.isAutoUpdate(this)) installUpdate(u)
                }
            }
        }
    }

    private fun installUpdate(u: Updater.Update) {
        if (busy) return
        if (!Updater.ensureInstallAllowed(this)) {
            Toast.makeText(this, "Allow this app to install updates, then press Update again.",
                Toast.LENGTH_LONG).show()
            return
        }
        busy = true
        updateText.text = "Downloading v${u.versionName}…"
        thread(name = "update-download") {
            val apk = Updater.download(this, u) { p -> runOnUiThread { updateText.text = "Downloading… $p%" } }
            busy = false
            runOnUiThread {
                if (apk == null) {
                    updateText.text = "Download failed — check the internet connection"
                } else {
                    updateText.text = "Installing v${u.versionName}…"
                    runCatching { startActivity(Updater.installIntent(this, apk)) }
                        .onFailure { updateText.text = "Couldn't open the installer: ${it.message}" }
                }
            }
        }
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
