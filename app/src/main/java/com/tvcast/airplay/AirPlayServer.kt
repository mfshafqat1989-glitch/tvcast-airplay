package com.tvcast.airplay

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * AirPlay (video + photo) HTTP receiver.
 * Handles: /server-info /play /scrub /rate /stop /playback-info /photo /reverse
 */
class AirPlayServer(private val context: Context, private val deviceId: String) {

    private val pool = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private var serverSocket: ServerSocket? = null

    @Volatile private var reverseOut: OutputStream? = null
    @Volatile private var sessionId: String? = null

    val port: Int get() = serverSocket?.localPort ?: 0

    fun start(preferredPort: Int = 7000) {
        val ss = try { ServerSocket(preferredPort) } catch (e: IOException) { ServerSocket(0) }
        serverSocket = ss
        pool.execute {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: IOException) { break }
                s.tcpNoDelay = true
                pool.execute { handle(s) }
            }
        }
        Log.i(TAG, "Listening on $port")
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        runCatching { reverseOut?.close() }
        pool.shutdownNow()
    }

    // ---------------------------------------------------------------- connection

    private class Response(
        val code: Int = 200,
        val reason: String = "OK",
        val body: ByteArray? = null,
        val contentType: String? = null,
    )

    private fun handle(sock: Socket) {
        try {
            val inp = BufferedInputStream(sock.getInputStream())
            val out = BufferedOutputStream(sock.getOutputStream())
            while (true) {
                val requestLine = readLine(inp) ?: break
                if (requestLine.isBlank()) continue
                val parts = requestLine.split(" ")
                if (parts.size < 2) break
                val method = parts[0].uppercase(Locale.US)
                val uri = parts[1]

                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(inp) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase(Locale.US)] = line.substring(i + 1).trim()
                }
                headers["x-apple-session-id"]?.let { sessionId = it }

                val len = headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = inp.read(body, read, len - read)
                    if (n < 0) break
                    read += n
                }

                val path = uri.substringBefore('?')
                val query = parseQuery(uri.substringAfter('?', ""))

                if (path == "/reverse") {
                    writeRaw(out, 101, "Switching Protocols",
                        listOf("Upgrade" to "PTTH/1.0", "Connection" to "Upgrade"), null)
                    reverseOut = out
                    while (inp.read() != -1) { /* keep the event channel open */ }
                    break
                }

                val r = try { route(method, path, query, headers, body) } catch (e: Exception) {
                    Log.e(TAG, "route $path", e); Response(500, "Internal Server Error")
                }
                val extra = mutableListOf<Pair<String, String>>()
                r.contentType?.let { extra += "Content-Type" to it }
                writeRaw(out, r.code, r.reason, extra, r.body)
            }
        } catch (_: Exception) {
        } finally {
            runCatching { sock.close() }
        }
    }

    // ---------------------------------------------------------------- routes

    private fun route(
        method: String, path: String, q: Map<String, String>,
        headers: Map<String, String>, body: ByteArray,
    ): Response = when (path) {

        "/server-info" -> plist(mapOf(
            "deviceid" to deviceId,
            "features" to FEATURES,
            "model" to MODEL,
            "protovers" to "1.0",
            "srcvers" to SRCVERS,
            "macAddress" to deviceId,
            "vv" to 2,
        ))

        "/play" -> {
            val (url, start) = parsePlay(headers, body)
            if (url.isNullOrBlank()) Response(400, "Bad Request") else {
                Log.i(TAG, "play $url @ $start")
                main.post {
                    PlaybackBus.pendingPhoto = null
                    PlaybackBus.pendingUrl = url
                    PlaybackBus.pendingStart = start
                    openPlayer()
                }
                Response()
            }
        }

        "/scrub" -> if (method == "GET") {
            text(String.format(Locale.US, "duration: %.6f\r\nposition: %.6f\r\n",
                PlaybackBus.durationSec, PlaybackBus.positionSec))
        } else {
            q["position"]?.toDoubleOrNull()?.let { pos -> main.post { PlaybackBus.listener?.onSeek(pos) } }
            Response()
        }

        "/rate" -> {
            val rate = q["value"]?.toFloatOrNull() ?: 1f
            main.post { PlaybackBus.listener?.onRate(rate) }
            Response()
        }

        "/stop" -> {
            main.post {
                PlaybackBus.pendingUrl = null
                PlaybackBus.pendingPhoto = null
                PlaybackBus.listener?.onStopRequested()
            }
            Response()
        }

        "/playback-info" -> {
            val b = PlaybackBus
            if (!b.ready) plist(mapOf("duration" to 0.0, "position" to 0.0, "rate" to 0.0,
                "readyToPlay" to false, "playbackBufferEmpty" to true, "playbackBufferFull" to false,
                "playbackLikelyToKeepUp" to false))
            else plist(mapOf(
                "duration" to b.durationSec,
                "position" to b.positionSec,
                "rate" to if (b.playing) 1.0 else 0.0,
                "readyToPlay" to true,
                "playbackBufferEmpty" to false,
                "playbackBufferFull" to false,
                "playbackLikelyToKeepUp" to true,
                "loadedTimeRanges" to listOf(mapOf("start" to 0.0, "duration" to b.bufferedSec)),
                "seekableTimeRanges" to listOf(mapOf("start" to 0.0, "duration" to b.durationSec)),
            ))
        }

        "/photo" -> {
            if (body.isNotEmpty()) main.post {
                PlaybackBus.pendingUrl = null
                PlaybackBus.pendingPhoto = body
                openPlayer()
            }
            Response()
        }

        "/slideshow-features" -> plist(mapOf("themes" to emptyList<Any>()))

        // Be lenient with everything else (setProperty, getProperty, action, …).
        else -> Response()
    }

    private fun parsePlay(headers: Map<String, String>, body: ByteArray): Pair<String?, Double> {
        if (Plist.isBinary(body)) {
            val m = runCatching { Plist.parseBinary(body) as? Map<*, *> }.getOrNull()
            val url = m?.get("Content-Location") as? String
            val start = (m?.get("Start-Position") as? Number)?.toDouble() ?: 0.0
            return url to start
        }
        var url: String? = null
        var start = 0.0
        String(body, Charsets.UTF_8).lineSequence().forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) {
                val k = line.substring(0, i).trim()
                val v = line.substring(i + 1).trim()
                when (k) {
                    "Content-Location" -> url = v
                    "Start-Position" -> start = v.toDoubleOrNull() ?: 0.0
                }
            }
        }
        return url to start
    }

    private fun openPlayer() {
        val l = PlaybackBus.listener
        if (l != null) { l.consumePending(); return }
        try {
            context.startActivity(Intent(context, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open player (open the app on the TV or allow auto-open)", e)
        }
    }

    /** Tells the iPhone the playback state changed ("playing", "paused", "stopped"). */
    fun sendEvent(state: String) {
        val out = reverseOut ?: return
        pool.execute {
            try {
                val body = Plist.toXml(mapOf("category" to "video", "state" to state)).toByteArray()
                val sb = StringBuilder()
                    .append("POST /event HTTP/1.1\r\n")
                    .append("Content-Type: text/x-apple-plist+xml\r\n")
                    .append("Content-Length: ${body.size}\r\n")
                sessionId?.let { sb.append("X-Apple-Session-ID: $it\r\n") }
                sb.append("\r\n")
                synchronized(out) {
                    out.write(sb.toString().toByteArray())
                    out.write(body)
                    out.flush()
                }
            } catch (e: Exception) {
                reverseOut = null
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun plist(map: Map<String, Any?>) =
        Response(body = Plist.toXml(map).toByteArray(), contentType = "text/x-apple-plist+xml")

    private fun text(s: String) = Response(body = s.toByteArray(), contentType = "text/parameters")

    private fun writeRaw(out: OutputStream, code: Int, reason: String,
                         headers: List<Pair<String, String>>, body: ByteArray?) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        sb.append("Date: ").append(httpDate()).append("\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        if (code != 101) sb.append("Content-Length: ").append(body?.size ?: 0).append("\r\n")
        sb.append("\r\n")
        synchronized(out) {
            out.write(sb.toString().toByteArray())
            if (body != null) out.write(body)
            out.flush()
        }
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16_384) return sb.toString()
        }
    }

    private fun parseQuery(q: String): Map<String, String> =
        if (q.isEmpty()) emptyMap() else q.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else URLDecoder.decode(it.substring(0, i), "UTF-8") to
                URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()

    private fun httpDate(): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date())

    companion object {
        private const val TAG = "AirPlayServer"
        // Video + Photo + VideoFairPlay + HLS + Slideshow (no screen mirroring bit).
        const val FEATURES = 0x77
        const val FEATURES_HEX = "0x77"
        const val MODEL = "AppleTV2,1"
        const val SRCVERS = "130.14"
    }
}
