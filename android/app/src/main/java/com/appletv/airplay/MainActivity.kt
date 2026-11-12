package com.appletv.airplay

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.appletv.airplay.engine.AirPlayAudioPlayer
import com.appletv.airplay.engine.AirPlayVideoDecoder
import com.appletv.airplay.engine.NativeAirPlay
import com.appletv.airplay.model.AirPlayState
import com.appletv.airplay.service.AirPlayService
import com.appletv.airplay.ui.settings.AppleTvSettingsActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var layoutAmbient: View
    private lateinit var layoutMirror: View
    private lateinit var layoutNowPlaying: View
    private lateinit var layoutPinDialog: View
    private lateinit var mirrorSurfaceView: android.view.SurfaceView

    // Ambient Views
    private lateinit var txtClock: TextView
    private lateinit var txtWifiSsid: TextView
    private lateinit var txtHeaderDeviceName: TextView
    private lateinit var txtInstructionStep3: TextView
    private lateinit var imgAerialBackdrop: ImageView

    // PIN Views
    private lateinit var txtPinDigit1: TextView
    private lateinit var txtPinDigit2: TextView
    private lateinit var txtPinDigit3: TextView
    private lateinit var txtPinDigit4: TextView

    // Mirror Views & HUD
    private lateinit var hudOverlay: View
    private lateinit var txtHudText: TextView

    // Now Playing Views
    private lateinit var imgAlbumCover: ImageView
    private lateinit var imgAmbientGlow: ImageView
    private lateinit var txtTrackTitle: TextView
    private lateinit var txtArtistName: TextView
    private lateinit var txtAlbumName: TextView
    private lateinit var audioProgressBar: ProgressBar
    private lateinit var txtElapsedTime: TextView
    private lateinit var txtRemainingTime: TextView

    private val videoDecoder = AirPlayVideoDecoder()
    private val audioPlayer = AirPlayAudioPlayer()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hideSystemUI()
        initViews()
        setupSurface()
        setupListeners()
        startClock()
        updateNetworkInfo()

        // Start AirPlay Background Receiver Service
        AirPlayService.start(this)

        // Observe AirPlay engine states
        observeAirPlayEngine()
    }

    private fun initViews() {
        layoutAmbient = findViewById(R.id.layoutAmbient)
        layoutMirror = findViewById(R.id.layoutMirror)
        layoutNowPlaying = findViewById(R.id.layoutNowPlaying)
        layoutPinDialog = findViewById(R.id.layoutPinDialog)
        mirrorSurfaceView = findViewById(R.id.mirrorSurfaceView)

        // Ambient Views
        txtClock = findViewById(R.id.txtClock)
        txtWifiSsid = findViewById(R.id.txtWifiSsid)
        txtHeaderDeviceName = findViewById(R.id.txtHeaderDeviceName)
        txtInstructionStep3 = findViewById(R.id.txtInstructionStep3)
        imgAerialBackdrop = findViewById(R.id.imgAerialBackdrop)

        // PIN Views
        txtPinDigit1 = findViewById(R.id.txtPinDigit1)
        txtPinDigit2 = findViewById(R.id.txtPinDigit2)
        txtPinDigit3 = findViewById(R.id.txtPinDigit3)
        txtPinDigit4 = findViewById(R.id.txtPinDigit4)

        // HUD
        hudOverlay = findViewById(R.id.hudOverlay)
        txtHudText = findViewById(R.id.txtHudText)

        // Now Playing
        imgAlbumCover = findViewById(R.id.imgAlbumCover)
        imgAmbientGlow = findViewById(R.id.imgAmbientGlow)
        txtTrackTitle = findViewById(R.id.txtTrackTitle)
        txtArtistName = findViewById(R.id.txtArtistName)
        txtAlbumName = findViewById(R.id.txtAlbumName)
        audioProgressBar = findViewById(R.id.audioProgressBar)
        txtElapsedTime = findViewById(R.id.txtElapsedTime)
        txtRemainingTime = findViewById(R.id.txtRemainingTime)

        // Apply Ken Burns subtle zoom effect to ambient background
        startKenBurnsEffect()
    }

    private fun startKenBurnsEffect() {
        val scaleX = ObjectAnimator.ofFloat(imgAerialBackdrop, "scaleX", 1.0f, 1.15f).apply {
            duration = 30000
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
        }
        val scaleY = ObjectAnimator.ofFloat(imgAerialBackdrop, "scaleY", 1.0f, 1.15f).apply {
            duration = 30000
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
        }
        scaleX.start()
        scaleY.start()
    }

    private fun setupSurface() {
        mirrorSurfaceView.holder.addCallback(this)
    }

    private fun setupListeners() {
        findViewById<View>(R.id.btnSettingsShortcut).setOnClickListener {
            startActivity(Intent(this, AppleTvSettingsActivity::class.java))
        }
    }

    private fun observeAirPlayEngine() {
        lifecycleScope.launch {
            NativeAirPlay.stateFlow.collectLatest { state ->
                when (state) {
                    AirPlayState.IDLE -> showIdleScreen()
                    AirPlayState.PIN_REQUIRED -> showPinScreen()
                    AirPlayState.MIRRORING -> showMirrorScreen()
                    AirPlayState.AUDIO_STREAMING -> showAudioScreen()
                }
            }
        }

        lifecycleScope.launch {
            NativeAirPlay.pinCodeFlow.collectLatest { pin ->
                if (!pin.isNullOrEmpty() && pin.length >= 4) {
                    txtPinDigit1.text = pin[0].toString()
                    txtPinDigit2.text = pin[1].toString()
                    txtPinDigit3.text = pin[2].toString()
                    txtPinDigit4.text = pin[3].toString()
                }
            }
        }

        lifecycleScope.launch {
            NativeAirPlay.clientInfoFlow.collectLatest { info ->
                if (info.deviceName.isNotEmpty()) {
                    showHud("${info.deviceName} conectado")
                }
            }
        }

        lifecycleScope.launch {
            NativeAirPlay.metadataFlow.collectLatest { metadata ->
                if (metadata.title.isNotEmpty()) {
                    txtTrackTitle.text = metadata.title
                    txtArtistName.text = metadata.artist
                    txtAlbumName.text = metadata.album

                    metadata.coverArtBytes?.let { bytes ->
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            imgAlbumCover.setImageBitmap(bitmap)
                            imgAmbientGlow.setImageBitmap(bitmap)
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            NativeAirPlay.volumeFlow.collectLatest { vol ->
                audioPlayer.setVolume(vol)
                val pct = (vol * 100).toInt()
                showHud("Volume: $pct%")
            }
        }
    }

    private fun showIdleScreen() {
        layoutAmbient.visibility = View.VISIBLE
        layoutMirror.visibility = View.GONE
        layoutNowPlaying.visibility = View.GONE
        layoutPinDialog.visibility = View.GONE
        videoDecoder.stop()
        audioPlayer.stop()

        val prefs = getSharedPreferences("apple_tv_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("device_name", "Apple TV") ?: "Apple TV"
        txtHeaderDeviceName.text = name
        txtInstructionStep3.text = getString(R.string.instruction_step_3, name)
    }

    private fun showPinScreen() {
        layoutPinDialog.visibility = View.VISIBLE
    }

    private fun showMirrorScreen() {
        layoutPinDialog.visibility = View.GONE
        layoutAmbient.visibility = View.GONE
        layoutNowPlaying.visibility = View.GONE
        layoutMirror.visibility = View.VISIBLE

        val surface = mirrorSurfaceView.holder.surface
        if (surface.isValid) {
            videoDecoder.start(surface)
        }
        audioPlayer.start()
    }

    private fun showAudioScreen() {
        layoutPinDialog.visibility = View.GONE
        layoutAmbient.visibility = View.GONE
        layoutMirror.visibility = View.GONE
        layoutNowPlaying.visibility = View.VISIBLE

        videoDecoder.stop()
        audioPlayer.start()
    }

    private fun showHud(text: String) {
        txtHudText.text = text
        hudOverlay.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideHudRunnable)
        mainHandler.postDelayed(hideHudRunnable, 3000)
    }

    private val hideHudRunnable = Runnable {
        hudOverlay.visibility = View.GONE
    }

    private fun startClock() {
        mainHandler.post(clockRunnable)
    }

    private fun updateClock() {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        txtClock.text = sdf.format(Date())
    }

    private fun updateNetworkInfo() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifiManager?.connectionInfo
            val ssid = info?.ssid?.replace("\"", "") ?: ""
            if (ssid.isNotEmpty() && ssid != "<unknown ssid>") {
                txtWifiSsid.text = ssid
            } else {
                txtWifiSsid.text = "Rede Conectada"
            }
        } catch (e: Exception) {
            txtWifiSsid.text = "Wi-Fi Ativo"
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (NativeAirPlay.stateFlow.value == AirPlayState.MIRRORING) {
            videoDecoder.start(holder.surface)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        videoDecoder.stop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (layoutPinDialog.visibility == View.VISIBLE) {
                layoutPinDialog.visibility = View.GONE
                return true
            }
            if (layoutMirror.visibility == View.VISIBLE || layoutNowPlaying.visibility == View.VISIBLE) {
                showIdleScreen()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(clockRunnable)
        videoDecoder.stop()
        audioPlayer.stop()
    }
}
