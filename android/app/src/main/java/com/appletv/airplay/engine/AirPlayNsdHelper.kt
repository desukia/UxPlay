package com.appletv.airplay.engine

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

class AirPlayNsdHelper(private val context: Context) {
    private val TAG = "AirPlayNsdHelper"

    private var multicastLock: WifiManager.MulticastLock? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun acquireMulticastLock() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("AirPlayMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.i(TAG, "Wi-Fi MulticastLock acquired successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire MulticastLock", e)
        }
    }

    fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i(TAG, "Wi-Fi MulticastLock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release MulticastLock", e)
        }
        multicastLock = null
    }

    fun registerAirPlayService(serviceName: String, port: Int) {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = "_airplay._tcp."
            this.port = port
            // Apple TV model identifier for authentic display on iOS / macOS devices
            setAttribute("model", "AppleTV14,1") // Apple TV 4K (3rd generation)
            setAttribute("srcvers", "377.17.24.6")
            setAttribute("flags", "0x4")
            setAttribute("features", "0x5A7FFFF7,0x1E")
            setAttribute("deviceid", "00:11:22:33:44:55")
            setAttribute("pk", "3b6a27bcceb6a42d62a3a8d02a6f0d73653215771de243a63ac048a18b59da29")
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                Log.i(TAG, "NSD Service registered: ${NsdServiceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD Service registration failed: $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.i(TAG, "NSD Service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD Service unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering NSD service", e)
        }
    }

    fun unregisterAirPlayService() {
        try {
            registrationListener?.let {
                nsdManager?.unregisterService(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering NSD service", e)
        }
        registrationListener = null
        releaseMulticastLock()
    }
}
