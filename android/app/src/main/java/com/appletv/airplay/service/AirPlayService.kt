package com.appletv.airplay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
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
    private var wifiLock: WifiManager.WifiLock? = null
    private var nsdHelper: AirPlayNsdHelper? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AirPlayService onCreate")

        createNotificationChannels()
        startForeground(NOTIFICATION_ID, buildStatusNotification("AirPlay Pronto", "Aguardando conexões no Google TV..."))

        // Keep CPU awake while streaming
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirPlayService:WakeLock")?.apply {
            setReferenceCounted(false)
            acquire()
        }

        // Keep Wi-Fi chip in high-performance active listening mode for multicast/mDNS packets
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AirPlayService:WifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WifiLock", e)
        }

        // Multicast lock for mDNS discovery (_airplay._tcp, _raop._tcp)
        nsdHelper = AirPlayNsdHelper(this).apply {
            acquireMulticastLock()
        }

        // Observe AirPlay states to update notification
        serviceScope.launch {
            NativeAirPlay.stateFlow.collectLatest { state ->
                when (state) {
                    AirPlayState.MIRRORING -> {
                        updateStatusNotification("AirPlay Ativo", "Espelhando tela no Google TV")
                    }
                    AirPlayState.AUDIO_STREAMING -> {
                        updateStatusNotification("AirPlay Áudio", "Reproduzindo áudio no Google TV")
                    }
                    AirPlayState.PIN_REQUIRED -> {
                        updateStatusNotification("AirPlay PIN", "Código exibido na tela da TV")
                    }
                    AirPlayState.IDLE -> {
                        updateStatusNotification("AirPlay Pronto", "Aguardando conexões no Google TV...")
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            Log.i(TAG, "ACTION_STOP received, stopping AirPlayService and tearing down server")
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

        return START_NOT_STICKY
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return

            val statusChannel = NotificationChannel(
                CHANNEL_ID,
                "AirPlay Receptor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém o receptor AirPlay ativo durante o uso do app"
                setShowBadge(false)
            }
            manager.createNotificationChannel(statusChannel)
        }
    }

    private fun buildStatusNotification(title: String, text: String): Notification {
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

    private fun updateStatusNotification(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildStatusNotification(title, text))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "Task removed (app closed), stopping AirPlayService and shutting down server")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "AirPlayService onDestroy: shutting down native server and releasing locks")
        serviceScope.cancel()
        NativeAirPlay.stopServer()
        nsdHelper?.releaseMulticastLock()
        wifiLock?.let {
            if (it.isHeld) it.release()
        }
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
