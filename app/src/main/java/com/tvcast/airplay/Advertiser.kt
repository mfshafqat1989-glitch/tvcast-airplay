package com.tvcast.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Backup Bonjour announcer using Android's own NSD service.
 * Only used if the engine's built-in mDNS responder couldn't open the network port.
 */
class Advertiser(context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val active = mutableListOf<NsdManager.RegistrationListener>()

    fun register(name: String, type: String, port: Int, txt: ByteArray?) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            this.port = port
            parseTxt(txt).forEach { (k, v) -> runCatching { setAttribute(k, v) } }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { Log.i(TAG, "Registered $type") }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "Failed $type: $errorCode") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            active += listener
        } catch (e: Exception) {
            Log.e(TAG, "registerService", e)
        }
    }

    fun unregisterAll() {
        active.forEach { runCatching { nsd.unregisterService(it) } }
        active.clear()
    }

    /** DNS TXT record: a list of length-prefixed "key=value" strings. */
    private fun parseTxt(txt: ByteArray?): List<Pair<String, String>> {
        if (txt == null) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < txt.size) {
            val len = txt[i].toInt() and 0xff
            i++
            if (len == 0 || i + len > txt.size) break
            val s = String(txt, i, len, Charsets.UTF_8)
            i += len
            val eq = s.indexOf('=')
            if (eq > 0) out += s.substring(0, eq) to s.substring(eq + 1)
        }
        return out
    }

    companion object { private const val TAG = "Advertiser" }
}
