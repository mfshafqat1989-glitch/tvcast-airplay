package com.tvcast.airplay

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases for a newer APK, downloads it and opens the installer. */
object Updater {

    data class Update(val versionCode: Int, val versionName: String, val apkUrl: String)

    private const val TAG = "Updater"
    private const val PREFS = "updater"

    fun isAutoUpdate(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto", true)

    fun setAutoUpdate(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto", on).apply()

    /** Network call: run on a background thread. Returns null when already up to date. */
    fun check(): Update? = try {
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        val tag = json.getString("tag_name")                      // e.g. v2.0.12
        val code = tag.substringAfterLast('.').toIntOrNull() ?: 0
        val assets = json.getJSONArray("assets")
        var url: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
        }
        if (url != null && code > BuildConfig.VERSION_CODE) Update(code, tag.removePrefix("v"), url) else null
    } catch (e: Exception) {
        Log.w(TAG, "Update check failed: ${e.message}")
        null
    }

    /** Network call: run on a background thread. */
    fun download(ctx: Context, update: Update, onProgress: (Int) -> Unit = {}): File? = try {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "TVCast-AirPlay-${update.versionCode}.apk")
        var conn = URL(update.apkUrl).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        // Follow GitHub's redirect to its download server manually (works on every Android version).
        var hops = 0
        while (conn.responseCode in 300..399 && hops++ < 5) {
            val next = conn.getHeaderField("Location")
            conn.disconnect()
            conn = URL(next).openConnection() as HttpURLConnection
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress((done * 100 / total).toInt())
                }
            }
        }
        out
    } catch (e: Exception) {
        Log.e(TAG, "Download failed", e)
        null
    }

    fun installIntent(ctx: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** True if Android lets this app open the installer; otherwise opens the setting to allow it. */
    fun ensureInstallAllowed(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()) return true
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return false
    }

    /** Background path (service): download and show a "tap to install" notification. */
    fun backgroundCheck(ctx: Context) {
        if (!isAutoUpdate(ctx)) return
        val u = check() ?: return
        val apk = download(ctx, u) ?: return
        val pi = PendingIntent.getActivity(ctx, 7, installIntent(ctx, apk),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, AirPlayService.CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Update ready: v${u.versionName}")
            .setContentText("Tap to install the new TV Cast AirPlay")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(2, n)
    }
}
