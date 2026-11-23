package com.tvcast.airplay

import android.content.Context
import android.content.Intent
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Kotlin side of the native AirPlay engine (UxPlay). The native code calls the on* methods
 * from its own threads.
 */
class AirPlayEngine(private val context: Context) {

    val video = VideoDecoder()
    val audio = AudioPlayer()
    private val main = Handler(Looper.getMainLooper())

    @Volatile var clientName: String? = null
        private set
    @Volatile var connections = 0
        private set
    @Volatile var mirroring = false
        private set
    var port = 0
        private set

    private external fun nativeStart(name: String, mac: String, keyFile: String,
                                     width: Int, height: Int, fps: Int, h265: Boolean): Int
    private external fun nativeStop()
    external fun nativeMdnsOk(): Boolean
    external fun nativeGetTxt(which: Int): ByteArray?

    fun start(name: String, mac: String): Int {
        val h265 = hasDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC)
        val key = context.filesDir.resolve("airplay_key.pem").absolutePath
        port = nativeStart(name, mac, key, 1920, 1080, 60, h265)
        Log.i(TAG, "nativeStart -> $port (h265=$h265)")
        return port
    }

    fun stop() {
        nativeStop()
        video.stop()
        audio.stop()
    }

    // ------------------------------------------------------------------ called from native code

    @Suppress("unused")
    fun onClient(name: String, model: String) {
        clientName = name.ifBlank { model }
        Log.i(TAG, "Client: $name ($model)")
    }

    @Suppress("unused")
    fun onVideoFormat(codec: Int, width: Int, height: Int) {
        video.setFormat(codec == 1, width, height)
        mirroring = true
        openMirrorScreen()
    }

    @Suppress("unused")
    fun onVideoData(data: ByteArray, ptsUs: Long) {
        if (!mirroring) {
            mirroring = true
            openMirrorScreen()
        }
        video.feed(data)
    }

    @Suppress("unused")
    fun onAudioFormat(ct: Int) = audio.start(ct)

    @Suppress("unused")
    fun onAudioData(data: ByteArray, ct: Int) = audio.feedCompressed(data)

    @Suppress("unused")
    fun onPcm(data: ByteArray) = audio.feedPcm(data)

    @Suppress("unused")
    fun onVolume(db: Float) = audio.setVolumeDb(db)

    @Suppress("unused")
    fun onVideoStop() {
        mirroring = false
        video.stop()
        main.post { MirrorActivity.current?.finish() }
    }

    @Suppress("unused")
    fun onConnections(n: Int) {
        connections = n
        if (n == 0) {
            clientName = null
            onVideoStop()
            audio.stop()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun openMirrorScreen() = main.post {
        if (MirrorActivity.current != null) return@post
        try {
            context.startActivity(Intent(context, MirrorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open mirror screen (allow 'display over other apps' or open the app)", e)
        }
    }

    private fun hasDecoder(mime: String): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                runCatching {
                    info.getCapabilitiesForType(mime).videoCapabilities.isSizeSupported(1920, 1080)
                }.getOrDefault(false)
        }
    }.getOrDefault(false)

    companion object {
        private const val TAG = "AirPlayEngine"
        init { System.loadLibrary("airplay") }
    }
}
