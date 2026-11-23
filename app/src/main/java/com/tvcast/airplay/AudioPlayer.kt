package com.tvcast.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * Plays AirPlay audio: AAC-ELD (screen mirroring), AAC-LC, or PCM (ALAC music decoded natively).
 */
class AudioPlayer {

    private var track: AudioTrack? = null
    private var codec: MediaCodec? = null
    private var outThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var gain = 1f

    @Synchronized
    fun start(ct: Int) {
        stop()
        val t = buildTrack() ?: return
        t.setVolume(gain)
        t.play()
        track = t
        if (ct == 4 || ct == 8) {
            val c = buildAacDecoder(eld = ct == 8) ?: return
            codec = c
            running = true
            outThread = Thread({ drain(c, t) }, "audio-out").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        }
        Log.i(TAG, "Audio started, ct=$ct")
    }

    fun feedCompressed(data: ByteArray) {
        val c = synchronized(this) { codec } ?: return
        try {
            val idx = c.dequeueInputBuffer(10_000)
            if (idx < 0) return
            val buf = c.getInputBuffer(idx) ?: return
            buf.clear()
            buf.put(data, 0, minOf(data.size, buf.capacity()))
            c.queueInputBuffer(idx, 0, minOf(data.size, buf.capacity()), System.nanoTime() / 1000, 0)
        } catch (_: Exception) {
        }
    }

    fun feedPcm(data: ByteArray) {
        val t = synchronized(this) { track } ?: return
        runCatching { t.write(data, 0, data.size) }
    }

    /** AirPlay volume in dB: 0 = max, -30 = min, -144 = mute. */
    fun setVolumeDb(db: Float) {
        gain = when {
            db <= -30f -> 0f
            db >= 0f -> 1f
            else -> 10f.pow(db / 20f)
        }
        synchronized(this) { runCatching { track?.setVolume(gain) } }
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { outThread?.join(300) }
        outThread = null
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        track?.let { runCatching { it.pause(); it.flush() }; runCatching { it.release() } }
        track = null
    }

    // ------------------------------------------------------------------ internals

    private fun buildTrack(): AudioTrack? = try {
        val rate = 44100
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val b = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(maxOf(min * 2, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= 26) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        b.build()
    } catch (e: Exception) {
        Log.e(TAG, "AudioTrack failed", e); null
    }

    private fun buildAacDecoder(eld: Boolean): MediaCodec? = try {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2)
        if (eld) {
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
            f.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)))
        } else {
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            f.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x12, 0x10)))
        }
        MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(f, null, null, 0)
            start()
        }
    } catch (e: Exception) {
        Log.e(TAG, "AAC decoder failed", e); null
    }

    private fun drain(c: MediaCodec, t: AudioTrack) {
        val info = MediaCodec.BufferInfo()
        var pcm = ByteArray(8192)
        while (running) {
            try {
                val idx = c.dequeueOutputBuffer(info, 10_000)
                if (idx >= 0) {
                    val buf = c.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        if (pcm.size < info.size) pcm = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(pcm, 0, info.size)
                        t.write(pcm, 0, info.size)
                    }
                    c.releaseOutputBuffer(idx, false)
                }
            } catch (e: Exception) {
                break
            }
        }
    }

    companion object { private const val TAG = "AudioPlayer" }
}
