package com.tvcast.airplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread
import kotlin.random.Random

/** Keeps the AirPlay receiver running in the background. */
class AirPlayService : Service() {

    private var advertiser: Advertiser? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private val updateCheck = object : Runnable {
        override fun run() {
            thread(name = "update-check") { Updater.backgroundCheck(applicationContext) }
            handler.postDelayed(this, 6 * 60 * 60 * 1000L) // every 6 hours
        }
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground()

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("airplay").apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "airplay")
            .apply { setReferenceCounted(false); acquire() }
        cpuLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvcast:airplay")
            .apply { setReferenceCounted(false); acquire() }

        val name = deviceName(this)
        val mac = deviceId(this)
        val e = AirPlayEngine(applicationContext)
        val port = e.start(name, mac)
        engine = e
        if (port > 0) {
            running = true
            if (!e.nativeMdnsOk()) {
                Log.w(TAG, "Built-in mDNS unavailable, using Android NSD")
                advertiser = Advertiser(this).apply {
                    register(mac.replace(":", "") + "@" + name, "_raop._tcp", port, e.nativeGetTxt(0))
                    register(name, "_airplay._tcp", port, e.nativeGetTxt(1))
                }
            }
        } else {
            error = "AirPlay engine failed to start (code $port)"
        }
        handler.postDelayed(updateCheck, 30_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        running = false
        advertiser?.unregisterAll()
        engine?.stop()
        engine = null
        runCatching { multicastLock?.release() }
        runCatching { wifiLock?.release() }
        runCatching { cpuLock?.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "AirPlay receiver", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(deviceName(this))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, 1, n, type)
    }

    companion object {
        private const val TAG = "AirPlayService"
        const val CHANNEL = "airplay"

        @Volatile var running = false
        @Volatile var engine: AirPlayEngine? = null
        @Volatile var error: String? = null

        fun start(context: Context) {
            val i = Intent(context, AirPlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun deviceName(context: Context): String {
            val n = runCatching {
                Settings.Global.getString(context.contentResolver, "device_name")
            }.getOrNull()
            return if (n.isNullOrBlank()) "Android TV (${Build.MODEL})" else n
        }

        /** A stable random MAC-style id, so Apple devices remember this receiver. */
        fun deviceId(context: Context): String {
            val prefs = context.getSharedPreferences("airplay", Context.MODE_PRIVATE)
            prefs.getString("deviceid", null)?.let { return it }
            val bytes = Random.nextBytes(6)
            bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte() // locally administered, unicast
            val id = bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
            prefs.edit().putString("deviceid", id).apply()
            return id
        }

        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
