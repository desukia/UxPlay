package com.appletv.airplay

import android.app.Application
import android.content.Context
import android.util.Log
import com.appletv.airplay.service.AirPlayService

class AirPlayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("AirPlayApplication", "Apple TV AirPlay Application initialized")
        val prefs = getSharedPreferences("apple_tv_prefs", Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean("start_on_boot", true)
        if (autoStart) {
            AirPlayService.start(this)
        }
    }
}
