package com.appletv.airplay.service

import android.app.ActivityOptions
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
    private val POPUP_CHANNEL_ID = "airplay_connect_popup_channel"
    private val NOTIFICATION_ID = 1001
    private val NOTIFICATION_ID_POPUP = 2002

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var nsdHelper: AirPlayNsdHelper? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AirPlayService onCreate")

        createNotificationChannels()
        startForeground(NOTIFICATION_ID, buildStatusNotification("AirPlay Pronto", "Aguardando conexões no Google TV..."))

        // Keep CPU awake
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

        // Observe AirPlay states to automatically launch/bring MainActivity to front when a connection starts
        serviceScope.launch {
            NativeAirPlay.stateFlow.collectLatest { state ->
                when (state) {
                    AirPlayState.MIRRORING, AirPlayState.AUDIO_STREAMING, AirPlayState.PIN_REQUIRED -> {
                        updateStatusNotification("AirPlay Ativo", "Transmitindo para este dispositivo")
                        wakeAndLaunchMainActivity()
                    }
                    AirPlayState.IDLE -> {
                        updateStatusNotification("AirPlay Pronto", "Aguardando conexões no Google TV...")
                    }
                }
            }
        }

        // Also trigger on incoming client negotiation
        serviceScope.launch {
            NativeAirPlay.clientInfoFlow.collectLatest { info ->
                if (info.deviceName.isNotEmpty()) {
                    wakeAndLaunchMainActivity()
                }
            }
        }

        // Also trigger when a PIN challenge is displayed
        serviceScope.launch {
            NativeAirPlay.pinCodeFlow.collectLatest { pin ->
                if (!pin.isNullOrEmpty()) {
                    wakeAndLaunchMainActivity()
                }
            }
        }

        // Also trigger on the first incoming video frame if UI is not yet open
        NativeAirPlay.onIncomingVideoFrameListener = {
            if (!MainActivity.isActivityResumed) {
                wakeAndLaunchMainActivity()
            }
        }
    }

    private fun wakeScreen() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            @Suppress("DEPRECATION")
            val screenLock = powerManager?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "AirPlayService:ScreenWake"
            )
            screenLock?.acquire(8000)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to wake screen: ${e.message}")
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

    /**
     * Wakes the Google TV screen and launches MainActivity automatically,
     * overcoming Android background activity start restrictions via Full-Screen Intent.
     */
    private fun wakeAndLaunchMainActivity() {
        Log.i(TAG, "Waking screen and launching MainActivity automatically on AirPlay connection")
        wakeScreen()

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } ?: Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            2001,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 1. Direct startActivity with Background Activity Allowed flag (Android 14+)
        try {
            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }.toBundle()
            } else null
            startActivity(launchIntent, options)
        } catch (e: Exception) {
            Log.w(TAG, "Direct startActivity encountered exception: ${e.message}")
        }

        // 2. Direct PendingIntent execution
        try {
            pendingIntent.send()
        } catch (e: Exception) {
            // Ignore if already launched
        }

        // 3. Full-Screen Intent Notification (Official Android TV / Android 10+ standard for waking apps)
        try {
            val popupNotification = NotificationCompat.Builder(this, POPUP_CHANNEL_ID)
                .setContentTitle("AirPlay Conectado")
                .setContentText("Iniciando transmissão na tela...")
                .setSmallIcon(R.drawable.ic_airplay)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pendingIntent, true)
                .setAutoCancel(true)
                .build()

            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID_POPUP, popupNotification)
        } catch (e: Exception) {
            Log.w(TAG, "Full-screen notification error: ${e.message}")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return

            // Channel 1: Persistent Low-Importance Status Notification
            val statusChannel = NotificationChannel(
                CHANNEL_ID,
                "AirPlay Receptor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém o receptor AirPlay ativo em segundo plano"
                setShowBadge(false)
            }
            manager.createNotificationChannel(statusChannel)

            // Channel 2: High-Importance Popup Channel for Instant Full-Screen Wake-up
            val popupChannel = NotificationChannel(
                POPUP_CHANNEL_ID,
                "AirPlay Conexão Automática",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Abre a tela do AirPlay automaticamente ao conectar dispositivo"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(popupChannel)
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
        Log.i(TAG, "Task removed (app closed/swiped), maintaining AirPlayService active in foreground")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "AirPlayService onDestroy")
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
