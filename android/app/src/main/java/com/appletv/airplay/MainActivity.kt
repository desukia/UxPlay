package com.appletv.airplay

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.appletv.airplay.engine.AirPlayAudioPlayer
import com.appletv.airplay.engine.AirPlayVideoDecoder
import com.appletv.airplay.engine.NativeAirPlay
import com.appletv.airplay.model.AirPlayState
import com.appletv.airplay.service.AirPlayService
import com.appletv.airplay.ui.settings.AppleTvSettingsActivity
import com.appletv.airplay.ui.sound.AppleTvSoundManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        @Volatile var isActivityResumed = false
    }

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
    private lateinit var imgDefaultMusicIcon: ImageView
    private lateinit var imgAmbientGlow: ImageView
    private lateinit var txtTrackTitle: TextView
    private lateinit var txtArtistName: TextView
    private lateinit var txtAlbumName: TextView
    private lateinit var btnSettingsShortcut: View

    private val videoDecoder = NativeAirPlay.videoDecoder
    private val audioPlayer = AirPlayAudioPlayer()
    private lateinit var soundManager: AppleTvSoundManager
    private var isMirroringActive = false

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

        soundManager = AppleTvSoundManager(this)
        hideSystemUI()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        hideSystemUI()
        when (NativeAirPlay.stateFlow.value) {
            AirPlayState.MIRRORING -> showMirrorScreen()
            AirPlayState.AUDIO_STREAMING -> showAudioScreen()
            AirPlayState.PIN_REQUIRED -> showPinScreen()
            AirPlayState.IDLE -> showIdleScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        hideSystemUI()
        val prefs = getSharedPreferences("apple_tv_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("device_name", "Apple TV") ?: "Apple TV"
        txtHeaderDeviceName.text = name
        txtInstructionStep3.text = getString(R.string.instruction_step_3, name)
        updateNetworkInfo()

        // If mirroring is active when resuming/opening, ensure decoder is running!
        when (NativeAirPlay.stateFlow.value) {
            AirPlayState.MIRRORING -> showMirrorScreen()
            AirPlayState.AUDIO_STREAMING -> showAudioScreen()
            AirPlayState.PIN_REQUIRED -> showPinScreen()
            AirPlayState.IDLE -> {}
        }
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
        videoDecoder.detachSurface()
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
        imgDefaultMusicIcon = findViewById(R.id.imgDefaultMusicIcon)
        imgAmbientGlow = findViewById(R.id.imgAmbientGlow)
        txtTrackTitle = findViewById(R.id.txtTrackTitle)
        txtArtistName = findViewById(R.id.txtArtistName)
        txtAlbumName = findViewById(R.id.txtAlbumName)
        btnSettingsShortcut = findViewById(R.id.btnSettingsShortcut)

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
        videoDecoder.onResolutionChangedListener = { width, height ->
            runOnUiThread {
                updateSurfaceAspectRatio(width, height)
            }
        }
    }

    private fun setupListeners() {
        btnSettingsShortcut.setOnClickListener {
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
                    txtArtistName.text = metadata.artist.ifEmpty { "Desconhecido" }
                    txtAlbumName.text = metadata.album

                    metadata.coverArtBytes?.let { bytes ->
                        if (bytes.isNotEmpty()) {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                imgDefaultMusicIcon.visibility = View.GONE
                                imgAlbumCover.visibility = View.VISIBLE
                                imgAlbumCover.setImageBitmap(bitmap)
                                applyAmbientGlow(bitmap)
                            } else {
                                resetCoverArt()
                            }
                        } else {
                            resetCoverArt()
                        }
                    } ?: run {
                        resetCoverArt()
                    }
                } else {
                    resetCoverArt()
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

        lifecycleScope.launch {
            NativeAirPlay.videoSizeFlow.collectLatest { (width, height) ->
                videoDecoder.setDimensions(width, height)
                updateSurfaceAspectRatio(width, height)
            }
        }
    }

    private fun updateSurfaceAspectRatio(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        mirrorSurfaceView.post {
            val container = findViewById<View>(R.id.rootContainer) ?: return@post
            val cWidth = container.width
            val cHeight = container.height
            if (cWidth <= 0 || cHeight <= 0) return@post

            val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
            val screenAspect = cWidth.toFloat() / cHeight.toFloat()

            val lp = mirrorSurfaceView.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return@post
            if (videoAspect > screenAspect) {
                // Video is wider than screen (e.g. landscape iPhone 19.5:9):
                // Fit full width of screen, calculate height proportionally (letterbox top & bottom)
                lp.width = cWidth
                lp.height = (cWidth / videoAspect).toInt().coerceAtLeast(1)
            } else {
                // Video is narrower/taller than screen (e.g. portrait iPhone 9:19.5 or iPad 4:3):
                // Fit full height of screen, calculate width proportionally (pillarbox left & right)
                lp.height = cHeight
                lp.width = (cHeight * videoAspect).toInt().coerceAtLeast(1)
            }
            lp.gravity = android.view.Gravity.CENTER
            mirrorSurfaceView.layoutParams = lp
            try {
                mirrorSurfaceView.holder.setFixedSize(videoWidth, videoHeight)
            } catch (e: Exception) {
                // Ignore
            }
            Log.i("MainActivity", "SurfaceView resized to ${lp.width}x${lp.height} for video ${videoWidth}x${videoHeight} (aspect: $videoAspect, screen: $screenAspect)")
        }
    }

    private fun showIdleScreen() {
        isMirroringActive = false
        layoutAmbient.visibility = View.VISIBLE
        layoutMirror.visibility = View.VISIBLE
        layoutNowPlaying.visibility = View.GONE
        layoutPinDialog.visibility = View.GONE
        btnSettingsShortcut.visibility = View.VISIBLE
        videoDecoder.detachSurface()
        audioPlayer.stop()
        resetCoverArt()

        val prefs = getSharedPreferences("apple_tv_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("device_name", "Apple TV") ?: "Apple TV"
        txtHeaderDeviceName.text = name
        btnSettingsShortcut.requestFocus()
    }

    private fun showPinScreen() {
        layoutPinDialog.visibility = View.VISIBLE
        btnSettingsShortcut.visibility = View.GONE
        soundManager.playPinDigitSound()
    }

    private fun showMirrorScreen() {
        isMirroringActive = true
        layoutPinDialog.visibility = View.GONE
        layoutAmbient.visibility = View.GONE
        layoutNowPlaying.visibility = View.GONE
        layoutMirror.visibility = View.VISIBLE
        btnSettingsShortcut.visibility = View.GONE

        val surface = mirrorSurfaceView.holder.surface
        val (w, h) = NativeAirPlay.videoSizeFlow.value
        if (surface.isValid) {
            videoDecoder.attachSurface(surface)
            updateSurfaceAspectRatio(w, h)
        }
        audioPlayer.start()
        soundManager.playConnectSound()
    }

    private fun showAudioScreen() {
        isMirroringActive = false
        layoutPinDialog.visibility = View.GONE
        layoutAmbient.visibility = View.GONE
        // Keep layoutMirror visible underneath so the hardware Surface is never destroyed
        layoutMirror.visibility = View.VISIBLE
        layoutNowPlaying.visibility = View.VISIBLE
        btnSettingsShortcut.visibility = View.GONE

        videoDecoder.detachSurface()
        audioPlayer.start()
        soundManager.playConnectSound()
    }

    private fun applyAmbientGlow(bitmap: Bitmap) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val smallBitmap = Bitmap.createScaledBitmap(bitmap, 128, 128, true)
                imgAmbientGlow.setImageBitmap(smallBitmap)
                val blurEffect = RenderEffect.createBlurEffect(120f, 120f, Shader.TileMode.CLAMP)
                imgAmbientGlow.setRenderEffect(blurEffect)
            } else {
                val blurred = createBlurredBitmap(bitmap, radius = 20, scale = 0.12f)
                imgAmbientGlow.setImageBitmap(blurred)
            }
            imgAmbientGlow.animate().alpha(0.55f).setDuration(400).start()
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to apply ambient glow effect", e)
            imgAmbientGlow.setImageBitmap(bitmap)
            imgAmbientGlow.alpha = 0.45f
        }
    }

    private fun resetCoverArt() {
        imgAlbumCover.setImageDrawable(null)
        imgAlbumCover.visibility = View.INVISIBLE
        imgDefaultMusicIcon.visibility = View.VISIBLE
        imgAmbientGlow.animate().alpha(0f).setDuration(300).withEndAction {
            imgAmbientGlow.setImageDrawable(null)
        }.start()
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.getDefault(), "%d:%02d", min, sec)
    }

    private fun createBlurredBitmap(src: Bitmap, radius: Int, scale: Float): Bitmap {
        val width = (src.width * scale).toInt().coerceAtLeast(16)
        val height = (src.height * scale).toInt().coerceAtLeast(16)
        val small = Bitmap.createScaledBitmap(src, width, height, true)
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        boxBlurPass(pixels, width, height, radius)
        boxBlurPass(pixels, width, height, radius)
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlurPass(pixels: IntArray, w: Int, h: Int, r: Int) {
        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = 2 * r + 1
        val rsum = IntArray(wh)
        val gsum = IntArray(wh)
        val bsum = IntArray(wh)

        for (y in 0 until h) {
            var curR = 0
            var curG = 0
            var curB = 0
            for (i in -r..r) {
                val p = pixels[y * w + i.coerceIn(0, wm)]
                curR += (p shr 16) and 0xFF
                curG += (p shr 8) and 0xFF
                curB += p and 0xFF
            }
            for (x in 0 until w) {
                val idx = y * w + x
                rsum[idx] = curR / div
                gsum[idx] = curG / div
                bsum[idx] = curB / div
                val pOut = pixels[y * w + (x - r).coerceIn(0, wm)]
                val pIn = pixels[y * w + (x + r + 1).coerceIn(0, wm)]
                curR += ((pIn shr 16) and 0xFF) - ((pOut shr 16) and 0xFF)
                curG += ((pIn shr 8) and 0xFF) - ((pOut shr 8) and 0xFF)
                curB += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }

        for (x in 0 until w) {
            var curR = 0
            var curG = 0
            var curB = 0
            for (i in -r..r) {
                val idx = i.coerceIn(0, hm) * w + x
                curR += rsum[idx]
                curG += gsum[idx]
                curB += bsum[idx]
            }
            for (y in 0 until h) {
                val idx = y * w + x
                pixels[idx] = (0xFF shl 24) or ((curR / div) shl 16) or ((curG / div) shl 8) or (curB / div)
                val idxOut = (y - r).coerceIn(0, hm) * w + x
                val idxIn = (y + r + 1).coerceIn(0, hm) * w + x
                curR += rsum[idxIn] - rsum[idxOut]
                curG += gsum[idxIn] - gsum[idxOut]
                curB += bsum[idxIn] - bsum[idxOut]
            }
        }
    }

    private fun showHud(text: String) {
        txtHudText.text = text
        hudOverlay.visibility = View.VISIBLE
        soundManager.playFocusSound()
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
        if (isMirroringActive || NativeAirPlay.stateFlow.value == AirPlayState.MIRRORING) {
            val (w, h) = NativeAirPlay.videoSizeFlow.value
            videoDecoder.attachSurface(holder.surface)
            updateSurfaceAspectRatio(w, h)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        videoDecoder.detachSurface()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (layoutPinDialog.visibility == View.VISIBLE) {
                layoutPinDialog.visibility = View.GONE
                soundManager.playPinDeleteSound()
                return true
            }
            if (layoutAmbient.visibility != View.VISIBLE) {
                showIdleScreen()
                soundManager.playFocusSound()
                return true
            } else {
                // Exit app cleanly, shutting down AirPlay server
                soundManager.playFocusSound()
                finish()
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
        soundManager.release()
        AirPlayService.stop(this)
        NativeAirPlay.stopServer()
    }
}
