package com.tvcast.airplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Starts the receiver when the TV boots (newer Android versions may block this; opening the app always works). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            AirPlayService.start(context)
        } catch (e: Exception) {
            Log.w("BootReceiver", "Auto-start blocked by the system", e)
        }
    }
}
