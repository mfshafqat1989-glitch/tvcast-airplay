package com.tvcast.airplay

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
class PlayerActivity : Activity(), PlaybackBus.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var photoView: ImageView
    private var startFraction = 0.0

    private val ticker = object : Runnable {
        override fun run() {
            val d = player.duration
            PlaybackBus.durationSec = if (d == C.TIME_UNSET || d < 0) 0.0 else d / 1000.0
            PlaybackBus.positionSec = player.currentPosition / 1000.0
            PlaybackBus.bufferedSec = player.bufferedPosition / 1000.0
            PlaybackBus.playing = player.isPlaying
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        photoView = findViewById(R.id.photo)

        // Small start-up buffer = video starts fast.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 1_000, 2_000)
            .build()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("AppleCoreMedia/1.0.0 (Apple TV; U; CPU OS like Mac OS X; en_us)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build()
        playerView.player = player
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        playerView.controllerAutoShow = false

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        PlaybackBus.ready = true
                        if (startFraction > 0 && player.duration > 0) {
                            player.seekTo((player.duration * startFraction).toLong())
                            startFraction = 0.0
                        }
                    }
                    Player.STATE_ENDED -> {
                        AirPlayService.instance?.sendEvent("stopped")
                        finish()
                    }
                    else -> {}
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                PlaybackBus.playing = isPlaying
                AirPlayService.instance?.sendEvent(if (isPlaying) "playing" else "paused")
            }

            override fun onPlayerError(error: PlaybackException) {
                android.widget.Toast.makeText(this@PlayerActivity,
                    "Can't play this video: ${error.errorCodeName}", android.widget.Toast.LENGTH_LONG).show()
                AirPlayService.instance?.sendEvent("stopped")
                finish()
            }
        })

        PlaybackBus.listener = this
        consumePending()
        handler.post(ticker)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumePending()
    }

    override fun consumePending() {
        PlaybackBus.pendingPhoto?.let { bytes ->
            PlaybackBus.pendingPhoto = null
            player.stop()
            PlaybackBus.reset()
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            photoView.setImageBitmap(bmp)
            photoView.visibility = View.VISIBLE
            playerView.visibility = View.GONE
            return
        }
        val url = PlaybackBus.pendingUrl ?: return
        PlaybackBus.pendingUrl = null
        startFraction = PlaybackBus.pendingStart.coerceIn(0.0, 0.99)

        photoView.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        PlaybackBus.reset()

        val item = MediaItem.Builder().setUri(url).apply {
            if (url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    override fun onSeek(seconds: Double) {
        player.seekTo((seconds * 1000).toLong())
    }

    override fun onRate(rate: Float) {
        player.playWhenReady = rate > 0f
    }

    override fun onStopRequested() {
        finish()
    }

    override fun onBackPressed() {
        AirPlayService.instance?.sendEvent("stopped")
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        if (PlaybackBus.listener === this) PlaybackBus.listener = null
        PlaybackBus.reset()
        player.release()
        super.onDestroy()
    }
}
