package com.appletv.airplay.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.appletv.airplay.service.AirPlayService

class BootReceiver : BroadcastReceiver {
    constructor() : super()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = context.getSharedPreferences("apple_tv_prefs", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("start_on_boot", true)

            if (autoStart) {
                Log.i("BootReceiver", "Starting AirPlayService automatically after Google TV boot")
                AirPlayService.start(context)
            }
        }
    }
}
