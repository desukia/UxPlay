package com.appletv.airplay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.appletv.airplay.MainActivity
import com.appletv.airplay.R
import com.appletv.airplay.engine.AirPlayNsdHelper
import com.appletv.airplay.engine.NativeAirPlay
import com.appletv.airplay.model.AirPlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AirPlayService : Service() {
    private val TAG = "AirPlayService"
    private val CHANNEL_ID = "airplay_service_channel"
    private val NOTIFICATION_ID = 1001

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var nsdHelper: AirPlayNsdHelper? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AirPlayService onCreate")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("AirPlay Pronto", "Aguardando conexões no Google TV..."))

        // Keep CPU awake
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirPlayService:WakeLock")?.apply {
            acquire()
        }

        // Multicast lock for mDNS discovery
        nsdHelper = AirPlayNsdHelper(this).apply {
            acquireMulticastLock()
        }

        // Observe AirPlay states to bring MainActivity to front when stream starts
        serviceScope.launch {
            NativeAirPlay.stateFlow.collectLatest { state ->
                when (state) {
                    AirPlayState.MIRRORING, AirPlayState.AUDIO_STREAMING, AirPlayState.PIN_REQUIRED -> {
                        updateNotification("AirPlay Ativo", "Transmitindo para este dispositivo")
                        bringActivityToFront()
                    }
                    AirPlayState.IDLE -> {
                        updateNotification("AirPlay Pronto", "Aguardando conexões no Google TV...")
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences("apple_tv_prefs", Context.MODE_PRIVATE)
        val deviceName = prefs.getString("device_name", "Apple TV") ?: "Apple TV"
        val requirePin = prefs.getBoolean("require_pin", false)
        val fixedPin = prefs.getString("fixed_pin", null)
        val resolution = prefs.getString("resolution", "1080p") ?: "1080p"

        val (width, height) = when (resolution) {
            "4K" -> 3840 to 2160
            "720p" -> 1280 to 720
            else -> 1920 to 1080
        }

        Log.i(TAG, "Starting NativeAirPlay server: '$deviceName' at ${width}x${height}, requirePin=$requirePin")
        NativeAirPlay.startServer(
            deviceName = deviceName,
            width = width,
            height = height,
            fps = 60,
            requirePin = requirePin,
            fixedPin = fixedPin
        )

        return START_STICKY
    }

    private fun bringActivityToFront() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AirPlay Receiver Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém o receptor AirPlay ativo no Google TV"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_airplay)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "AirPlayService onDestroy")
        serviceScope.cancel()
        NativeAirPlay.stopServer()
        nsdHelper?.releaseMulticastLock()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.appletv.airplay.START"
        const val ACTION_STOP = "com.appletv.airplay.STOP"

        fun start(context: Context) {
            val intent = Intent(context, AirPlayService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AirPlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
