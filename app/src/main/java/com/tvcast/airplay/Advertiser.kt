package com.tvcast.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/** Announces the TV on the local network (Bonjour / mDNS) so iPhones see it in the AirPlay list. */
class Advertiser(context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val active = mutableListOf<NsdManager.RegistrationListener>()

    fun register(name: String, type: String, port: Int, txt: Map<String, String>) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            this.port = port
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Registered ${info.serviceName} $type")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Registration failed $type: $errorCode")
            }
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

    companion object { private const val TAG = "Advertiser" }
}
