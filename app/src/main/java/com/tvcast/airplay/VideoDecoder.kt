package com.tvcast.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface

/**
 * Hardware H.264 / H.265 decoder for the mirrored screen.
 * Frames are shown the moment they are decoded (lowest possible latency).
 */
class VideoDecoder {

    private val lock = Object()
    private var codec: MediaCodec? = null
    private var outThread: Thread? = null
    @Volatile private var running = false

    private var h265 = false
    private var width = 1920
    private var height = 1080
    private var surface: Surface? = null
    private var lastKeyFrame: ByteArray? = null

    /** Called with the real picture size so the screen can keep the right shape. */
    var onVideoSize: ((Int, Int) -> Unit)? = null

    val hasSurface: Boolean get() = surface != null

    fun setFormat(isH265: Boolean, w: Int, h: Int): Unit = synchronized(lock) {
        if (isH265 != h265 || codec == null) {
            releaseLocked()
        }
        h265 = isH265
        if (w > 0) width = w
        if (h > 0) height = h
    }

    fun setSurface(s: Surface?): Unit = synchronized(lock) {
        releaseLocked()
        surface = s
        // Restart straight away from the last key frame so the picture appears instantly.
        val key = lastKeyFrame
        if (s != null && key != null) {
            startLocked()
            queueLocked(key)
        }
    }

    fun feed(data: ByteArray) {
        synchronized(lock) {
            val key = containsParameterSets(data)
            if (key) lastKeyFrame = data
            if (codec == null) {
                if (!key || surface == null) return
                startLocked()
            }
            queueLocked(data)
        }
    }

    fun stop(): Unit = synchronized(lock) {
        releaseLocked()
        lastKeyFrame = null
    }

    // ------------------------------------------------------------------ internals

    private fun startLocked() {
        val s = surface ?: return
        val mime = if (h265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val c = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) {
            Log.e(TAG, "No decoder for $mime", e); return
        }
        val configured = tryConfigure(c, mime, s, fancy = true) || tryConfigure(c, mime, s, fancy = false)
        if (!configured) { c.release(); return }
        c.start()
        codec = c
        running = true
        outThread = Thread({ drain(c) }, "video-out").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        Log.i(TAG, "Decoder started $mime ${width}x$height")
    }

    private fun tryConfigure(c: MediaCodec, mime: String, s: Surface, fancy: Boolean): Boolean = try {
        val f = MediaFormat.createVideoFormat(mime, width, height)
        if (fancy) {
            val max = maxOf(width, height, 1920)
            f.setInteger(MediaFormat.KEY_MAX_WIDTH, max)
            f.setInteger(MediaFormat.KEY_MAX_HEIGHT, max)
            f.setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        c.configure(f, s, null, 0)
        true
    } catch (e: Exception) {
        Log.w(TAG, "configure(fancy=$fancy) failed: ${e.message}")
        runCatching { c.reset() }
        false
    }

    private fun queueLocked(data: ByteArray) {
        val c = codec ?: return
        try {
            val idx = c.dequeueInputBuffer(20_000)
            if (idx < 0) return // decoder busy: drop this frame instead of adding delay
            val buf = c.getInputBuffer(idx) ?: return
            buf.clear()
            if (data.size > buf.capacity()) {
                c.queueInputBuffer(idx, 0, 0, System.nanoTime() / 1000, 0)
                return
            }
            buf.put(data)
            c.queueInputBuffer(idx, 0, data.size, System.nanoTime() / 1000, 0)
        } catch (e: Exception) {
            Log.w(TAG, "queue failed, restarting decoder", e)
            releaseLocked()
        }
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                val idx = c.dequeueOutputBuffer(info, 20_000)
                when {
                    idx >= 0 -> c.releaseOutputBuffer(idx, true)
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        var w = f.getInteger(MediaFormat.KEY_WIDTH)
                        var h = f.getInteger(MediaFormat.KEY_HEIGHT)
                        if (f.containsKey("crop-right") && f.containsKey("crop-left")) {
                            w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1
                        }
                        if (f.containsKey("crop-bottom") && f.containsKey("crop-top")) {
                            h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
                        }
                        onVideoSize?.invoke(w, h)
                    }
                }
            } catch (e: Exception) {
                break
            }
        }
    }

    private fun releaseLocked() {
        running = false
        val c = codec ?: return
        codec = null
        runCatching { outThread?.join(300) }
        outThread = null
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    /** True if the buffer carries SPS (H.264) or VPS/SPS (H.265), i.e. a decodable starting point. */
    private fun containsParameterSets(d: ByteArray): Boolean {
        var i = 0
        val n = d.size - 4
        while (i < n) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1) {
                val b = d[i + 4].toInt() and 0xff
                if (h265) {
                    val t = (b shr 1) and 0x3f
                    if (t == 32 || t == 33) return true
                } else {
                    if ((b and 0x1f) == 7) return true
                }
                i += 4
            } else {
                i++
            }
        }
        return false
    }

    companion object { private const val TAG = "VideoDecoder" }
}
