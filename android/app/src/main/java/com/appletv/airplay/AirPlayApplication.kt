package com.appletv.airplay

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.appletv.airplay.engine.NativeAirPlay
import com.appletv.airplay.service.AirPlayService

class AirPlayApplication : Application() {

    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        Log.i("AirPlayApplication", "Apple TV AirPlay Application initialized")

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                if (startedActivityCount == 1) {
                    // App is opened on screen - start AirPlay receiver server
                    Log.i("AirPlayApplication", "App opened on screen, starting AirPlay receiver service")
                    AirPlayService.start(this@AirPlayApplication)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount--
                if (startedActivityCount <= 0) {
                    startedActivityCount = 0
                    // App is closed / minimized to Home screen - stop server completely
                    Log.i("AirPlayApplication", "App closed / in background, stopping AirPlay server")
                    AirPlayService.stop(this@AirPlayApplication)
                    NativeAirPlay.stopServer()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
