package com.tvcast.airplay

/** Shared state between the network server (background threads) and the player screen (main thread). */
object PlaybackBus {

    interface Listener {
        fun consumePending()
        fun onSeek(seconds: Double)
        fun onRate(rate: Float)
        fun onStopRequested()
    }

    // Written by the player every 500 ms, read by the server for /scrub and /playback-info.
    @Volatile var durationSec = 0.0
    @Volatile var positionSec = 0.0
    @Volatile var bufferedSec = 0.0
    @Volatile var playing = false
    @Volatile var ready = false

    // Commands waiting for the player screen to open.
    @Volatile var pendingUrl: String? = null
    @Volatile var pendingStart = 0.0
    @Volatile var pendingPhoto: ByteArray? = null

    /** Set while PlayerActivity is on screen. Only touched on the main thread. */
    var listener: Listener? = null

    fun reset() {
        durationSec = 0.0
        positionSec = 0.0
        bufferedSec = 0.0
        playing = false
        ready = false
    }
}
