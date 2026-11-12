package com.appletv.airplay

import android.app.Application
import android.util.Log

class AirPlayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("AirPlayApplication", "Apple TV AirPlay Application initialized")
    }
}
