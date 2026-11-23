package com.tvcast.airplay

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/** Full-screen view that shows the mirrored iPhone / iPad / Mac screen. */
class MirrorActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private var videoW = 16
    private var videoH = 9

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        setContentView(root)
        hideSystemUi()
        surfaceView.holder.addCallback(this)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
        current = this
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val engine = AirPlayService.engine ?: return
        engine.video.onVideoSize = { w, h -> runOnUiThread { videoW = w; videoH = h; fit() } }
        engine.video.setSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        AirPlayService.engine?.video?.setSurface(null)
    }

    /** Keeps the picture's shape (portrait phone screens get black bars, landscape fills the TV). */
    private fun fit() {
        val pw = root.width
        val ph = root.height
        if (pw == 0 || ph == 0 || videoW <= 0 || videoH <= 0) return
        val scale = minOf(pw.toFloat() / videoW, ph.toFloat() / videoH)
        val w = (videoW * scale).toInt()
        val h = (videoH * scale).toInt()
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            surfaceView.layoutParams = lp
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onDestroy() {
        if (current === this) current = null
        AirPlayService.engine?.video?.onVideoSize = null
        super.onDestroy()
    }

    companion object {
        @Volatile var current: MirrorActivity? = null
    }
}
