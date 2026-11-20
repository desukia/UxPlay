package com.appletv.airplay.ui.settings

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.appletv.airplay.R
import com.appletv.airplay.service.AirPlayService
import com.appletv.airplay.ui.sound.AppleTvSoundManager

class AppleTvSettingsActivity : AppCompatActivity() {

    // Category Tabs (Left)
    private lateinit var catAirPlay: TextView
    private lateinit var catVideoAudio: TextView
    private lateinit var catScreensaver: TextView
    private lateinit var catGeneral: TextView

    // Content Panels (Right)
    private lateinit var panelAirPlay: View
    private lateinit var panelVideoAudio: View
    private lateinit var panelScreensaver: View
    private lateinit var panelGeneral: View

    // AirPlay Values
    private lateinit var valDeviceName: TextView
    private lateinit var valRequirePin: TextView
    private lateinit var valAirPlayAccess: TextView
    private lateinit var valResolution: TextView

    // Video & Audio Values
    private lateinit var valVideoFormat: TextView
    private lateinit var valAspectRatio: TextView
    private lateinit var valAudioFormat: TextView
    private lateinit var valSoundEffects: TextView

    // Screensaver Values
    private lateinit var valScreensaverType: TextView
    private lateinit var valScreensaverTimeout: TextView
    private lateinit var valKenBurns: TextView

    // General Values
    private lateinit var valStartBoot: TextView
    private lateinit var valNetworkInfo: TextView
    private lateinit var valAbout: TextView

    private lateinit var prefs: SharedPreferences
    private lateinit var soundManager: AppleTvSoundManager
    private var selectedCategoryIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences("apple_tv_prefs", Context.MODE_PRIVATE)
        soundManager = AppleTvSoundManager(this)

        initViews()
        loadCurrentSettings()
        setupCategoryTabs()
        setupSettingItemListeners()

        // Select first tab by default
        selectCategory(0, playSound = false)
    }

    private fun initViews() {
        // Tabs
        catAirPlay = findViewById(R.id.catAirPlay)
        catVideoAudio = findViewById(R.id.catVideoAudio)
        catScreensaver = findViewById(R.id.catScreensaver)
        catGeneral = findViewById(R.id.catGeneral)

        // Panels
        panelAirPlay = findViewById(R.id.panelAirPlay)
        panelVideoAudio = findViewById(R.id.panelVideoAudio)
        panelScreensaver = findViewById(R.id.panelScreensaver)
        panelGeneral = findViewById(R.id.panelGeneral)

        // AirPlay Panel Views
        valDeviceName = findViewById(R.id.valDeviceName)
        valRequirePin = findViewById(R.id.valRequirePin)
        valAirPlayAccess = findViewById(R.id.valAirPlayAccess)
        valResolution = findViewById(R.id.valResolution)

        // Video & Audio Panel Views
        valVideoFormat = findViewById(R.id.valVideoFormat)
        valAspectRatio = findViewById(R.id.valAspectRatio)
        valAudioFormat = findViewById(R.id.valAudioFormat)
        valSoundEffects = findViewById(R.id.valSoundEffects)

        // Screensaver Panel Views
        valScreensaverType = findViewById(R.id.valScreensaverType)
        valScreensaverTimeout = findViewById(R.id.valScreensaverTimeout)
        valKenBurns = findViewById(R.id.valKenBurns)

        // General Panel Views
        valStartBoot = findViewById(R.id.valStartBoot)
        valNetworkInfo = findViewById(R.id.valNetworkInfo)
        valAbout = findViewById(R.id.valAbout)
    }

    private fun loadCurrentSettings() {
        // AirPlay
        valDeviceName.text = prefs.getString("device_name", "Apple TV")
        valRequirePin.text = when (prefs.getString("pin_mode", "first_time")) {
            "always" -> getString(R.string.pin_always)
            "never" -> getString(R.string.pin_never)
            else -> getString(R.string.pin_first_time)
        }
        valAirPlayAccess.text = prefs.getString("airplay_access", "Mesma Rede (Padrão)")
        valResolution.text = prefs.getString("resolution", "1080p 60Hz (Padrão)")

        // Video & Audio
        valVideoFormat.text = prefs.getString("video_format", "1080p SDR 60Hz")
        valAspectRatio.text = prefs.getString("aspect_ratio_mode", "Manter Original (Letterbox)")
        valAudioFormat.text = "Lossless 44.1kHz Estéreo"
        val soundEffectsEnabled = prefs.getBoolean("sound_effects", true)
        valSoundEffects.text = if (soundEffectsEnabled) "Ativado" else "Desativado"

        // Screensaver
        valScreensaverType.text = prefs.getString("screensaver_type", "Tomadas Aéreas Apple TV")
        valScreensaverTimeout.text = prefs.getString("screensaver_timeout", "2 Minutos")
        val kenBurnsEnabled = prefs.getBoolean("ken_burns", true)
        valKenBurns.text = if (kenBurnsEnabled) "Ativado" else "Desativado"

        // General
        val isBoot = prefs.getBoolean("start_on_boot", false)
        valStartBoot.text = if (isBoot) "Ativado" else "Desativado"

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ssid = wifiManager?.connectionInfo?.ssid?.replace("\"", "") ?: ""
            valNetworkInfo.text = if (ssid.isNotEmpty() && ssid != "<unknown ssid>") ssid else "Wi-Fi Conectado"
        } catch (e: Exception) {
            valNetworkInfo.text = "Wi-Fi Conectado"
        }

        valAbout.text = "tvOS 18.1 (UxPlay Engine)"
    }

    private fun setupCategoryTabs() {
        val tabs = listOf(catAirPlay, catVideoAudio, catScreensaver, catGeneral)

        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener {
                selectCategory(index, playSound = true)
            }

            tab.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    selectCategory(index, playSound = true)
                }
            }
        }
    }

    private fun selectCategory(index: Int, playSound: Boolean) {
        selectedCategoryIndex = index
        if (playSound) {
            soundManager.playFocusSound()
        }

        val tabs = listOf(catAirPlay, catVideoAudio, catScreensaver, catGeneral)
        val panels = listOf(panelAirPlay, panelVideoAudio, panelScreensaver, panelGeneral)

        tabs.forEachIndexed { i, tab ->
            if (i == index) {
                tab.setBackgroundResource(R.drawable.bg_glass_pill)
                tab.setTextColor(ContextCompat.getColor(this, R.color.text_white))
                tab.setTypeface(null, Typeface.BOLD)
                tab.isSelected = true
            } else {
                tab.setBackgroundResource(R.drawable.bg_settings_tab_selector)
                tab.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                tab.setTypeface(null, Typeface.NORMAL)
                tab.isSelected = false
            }
        }

        panels.forEachIndexed { i, panel ->
            panel.visibility = if (i == index) View.VISIBLE else View.GONE
        }
    }

    private fun setupSettingItemListeners() {
        // --- 1. AIRPLAY & HOMEKIT LISTENERS ---
        findViewById<View>(R.id.itemDeviceName).setOnClickListener {
            soundManager.playFocusSound()
            val input = EditText(this).apply {
                setText(valDeviceName.text)
                setSingleLine()
            }
            AlertDialog.Builder(this)
                .setTitle("Nome da Apple TV")
                .setMessage("Digite o nome exibido na Central de Controle dos dispositivos Apple:")
                .setView(input)
                .setPositiveButton("Salvar") { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isNotEmpty()) {
                        prefs.edit().putString("device_name", newName).apply()
                        valDeviceName.text = newName
                        AirPlayService.start(this)
                        Toast.makeText(this, "Nome atualizado!", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        findViewById<View>(R.id.itemRequirePin).setOnClickListener {
            soundManager.playFocusSound()
            val options = arrayOf(
                getString(R.string.pin_first_time),
                getString(R.string.pin_always),
                getString(R.string.pin_never)
            )
            AlertDialog.Builder(this)
                .setTitle(R.string.setting_require_pin)
                .setItems(options) { _, which ->
                    val mode = when (which) {
                        1 -> "always"
                        2 -> "never"
                        else -> "first_time"
                    }
                    prefs.edit()
                        .putString("pin_mode", mode)
                        .putBoolean("require_pin", which != 2)
                        .apply()
                    valRequirePin.text = options[which]
                    AirPlayService.start(this)
                }
                .show()
        }

        findViewById<View>(R.id.itemAirPlayAccess).setOnClickListener {
            soundManager.playFocusSound()
            val options = arrayOf("Mesma Rede (Padrão)", "Qualquer Dispositivo na Rede")
            AlertDialog.Builder(this)
                .setTitle(R.string.setting_airplay_access)
                .setItems(options) { _, which ->
                    val chosen = options[which]
                    prefs.edit().putString("airplay_access", chosen).apply()
                    valAirPlayAccess.text = chosen
                }
                .show()
        }

        findViewById<View>(R.id.itemResolution).setOnClickListener {
            soundManager.playFocusSound()
            val options = arrayOf("1080p 60Hz (Padrão)", "4K 60Hz (Ultra HD)", "720p 60Hz")
            AlertDialog.Builder(this)
                .setTitle(R.string.setting_resolution)
                .setItems(options) { _, which ->
                    val res = when (which) {
                        1 -> "4K"
                        2 -> "720p"
                        else -> "1080p"
                    }
                    prefs.edit().putString("resolution", res).apply()
                    valResolution.text = options[which]
                    AirPlayService.start(this)
                }
                .show()
        }

        // --- 2. VIDEO & AUDIO LISTENERS ---
        findViewById<View>(R.id.itemVideoFormat).setOnClickListener {
            soundManager.playFocusSound()
            val options = arrayOf("1080p SDR 60Hz", "4K HDR 60Hz", "720p 60Hz")
            AlertDialog.Builder(this)
                .setTitle(R.string.setting_video_format)
                .setItems(options) { _, which ->
                    val choice = options[which]
                    prefs.edit().putString("video_format", choice).apply()
                    valVideoFormat.text = choice
                }
                .show()
        }

        findViewById<View>(R.id.itemAspectRatio).setOnClickListener {
            soundManager.playFocusSound()
            val options = arrayOf("Manter Original (Letterbox)", "Preencher Tela (Stretch)")
            AlertDialog.Builder(this)
                .setTitle(R.string.setting_aspect_ratio)
                .setItems(options) { _, which ->
                    val choice = options[which]
                    prefs.edit().putString("aspect_ratio_mode", choice).apply()
                    valAspectRatio.text = choice
                }
                .show()
        }

        findViewById<View>(R.id.itemAudioFormat).setOnClickListener {
            soundManager.playFocusSound()
            AlertDialog.Builder(this)
                .setTitle(R.string.setting_audio_format)
                .setMessage("A Apple TV reproduz áudio estéreo Lossless ALAC a 44.1kHz / 16-bit com baixa latência para Apple Music e AAC-ELD para espelhamento.")
                .setPositiveButton("OK", null)
                .show()
        }

        findViewById<View>(R.id.itemSoundEffects).setOnClickListener {
            soundManager.playFocusSound()
            val current = prefs.getBoolean("sound_effects", true)
            val updated = !current
            prefs.edit().putBoolean("sound_effects", updated).apply()
            valSoundEffects.text = if (updated) "Ativado" else "Desativado"
        }

        // --- 3. SCREENSAVER LISTENERS ---
        findViewById<View>(R.id.itemScreensaverType).setOnClickListener {
            soundManager.playFocusSound()
            val options = arrayOf("Tomadas Aéreas Apple TV", "Preto Puro (OLED Safe)")
            AlertDialog.Builder(this)
                .setTitle(R.string.setting_screensaver_type)
                .setItems(options) { _, which ->
                    val choice = options[which]
                    prefs.edit().putString("screensaver_type", choice).apply()
                    valScreensaverType.text = choice
                }
                .show()
        }

        findViewById<View>(R.id.itemScreensaverTimeout).setOnClickListener {
            soundManager.playFocusSound()
            val options = arrayOf("2 Minutos", "5 Minutos", "15 Minutos", "Nunca")
            AlertDialog.Builder(this)
                .setTitle(R.string.setting_screensaver_timeout)
                .setItems(options) { _, which ->
                    val choice = options[which]
                    prefs.edit().putString("screensaver_timeout", choice).apply()
                    valScreensaverTimeout.text = choice
                }
                .show()
        }

        findViewById<View>(R.id.itemKenBurns).setOnClickListener {
            soundManager.playFocusSound()
            val current = prefs.getBoolean("ken_burns", true)
            val updated = !current
            prefs.edit().putBoolean("ken_burns", updated).apply()
            valKenBurns.text = if (updated) "Ativado" else "Desativado"
        }

        // --- 4. GENERAL LISTENERS ---
        findViewById<View>(R.id.itemStartBoot).setOnClickListener {
            soundManager.playFocusSound()
            val current = prefs.getBoolean("start_on_boot", false)
            val updated = !current
            prefs.edit().putBoolean("start_on_boot", updated).apply()
            valStartBoot.text = if (updated) "Ativado" else "Desativado"
        }

        findViewById<View>(R.id.itemNetworkInfo).setOnClickListener {
            soundManager.playFocusSound()
            try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val info = wifiManager?.connectionInfo
                val ipAddress = info?.ipAddress ?: 0
                val ipString = String.format(
                    java.util.Locale.getDefault(),
                    "%d.%d.%d.%d",
                    ipAddress and 0xff,
                    ipAddress shr 8 and 0xff,
                    ipAddress shr 16 and 0xff,
                    ipAddress shr 24 and 0xff
                )
                AlertDialog.Builder(this)
                    .setTitle("Informações de Rede")
                    .setMessage("SSID: ${valNetworkInfo.text}\nEndereço IP: $ipString\nLink: 5 GHz / 2.4 GHz Ativo")
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this, "Informações de rede indisponíveis", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.itemAbout).setOnClickListener {
            soundManager.playFocusSound()
            AlertDialog.Builder(this)
                .setTitle("Sobre esta Apple TV")
                .setMessage("Apple TV Experience para Google TV & Android TV\nVersão da Interface: tvOS 18.1\nEngine: UxPlay Native RAOP Core v1.70\nDesenvolvido com Decodificação Hardware MediaCodec AVC e Áudio ALAC/AAC-ELD.")
                .setPositiveButton("OK", null)
                .show()
        }

        findViewById<View>(R.id.itemResetSettings).setOnClickListener {
            soundManager.playFocusSound()
            AlertDialog.Builder(this)
                .setTitle("Redefinir Ajustes de Fábrica?")
                .setMessage("Todos os ajustes de nome, segurança e exibição voltarão aos padrões originais da Apple TV.")
                .setPositiveButton("Redefinir") { _, _ ->
                    prefs.edit().clear().apply()
                    loadCurrentSettings()
                    AirPlayService.start(this)
                    Toast.makeText(this, "Ajustes redefinidos para os padrões de fábrica!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val currentFocus = currentFocus
            // If focus is in one of the right panels, return focus to the active left category tab
            if (currentFocus != null && currentFocus != catAirPlay && currentFocus != catVideoAudio &&
                currentFocus != catScreensaver && currentFocus != catGeneral) {
                val tabs = listOf(catAirPlay, catVideoAudio, catScreensaver, catGeneral)
                tabs.getOrNull(selectedCategoryIndex)?.requestFocus()
                soundManager.playFocusSound()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        soundManager.release()
    }
}
