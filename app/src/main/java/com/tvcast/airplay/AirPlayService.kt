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
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.random.Random

/** Keeps the AirPlay receiver alive in the background. */
class AirPlayService : Service() {

    private var server: AirPlayServer? = null
    private var advertiser: Advertiser? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("airplay").apply { setReferenceCounted(false); acquire() }

        val id = deviceId(this)
        val name = deviceName(this)
        val srv = AirPlayServer(applicationContext, id).also { it.start() }
        server = srv
        instance = srv
        port = srv.port

        advertiser = Advertiser(this).apply {
            register(name, "_airplay._tcp", srv.port, mapOf(
                "deviceid" to id,
                "features" to AirPlayServer.FEATURES_HEX,
                "model" to AirPlayServer.MODEL,
                "srcvers" to AirPlayServer.SRCVERS,
                "flags" to "0x4",
                "vv" to "2",
            ))
        }
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        instance = null
        advertiser?.unregisterAll()
        server?.stop()
        runCatching { multicastLock?.release() }
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
        private const val CHANNEL = "airplay"

        @Volatile var running = false
        @Volatile var instance: AirPlayServer? = null
        @Volatile var port = 0

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

        /** A stable random MAC-style id, so the iPhone remembers this TV. */
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
