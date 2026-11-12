package com.appletv.airplay.ui.settings

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.appletv.airplay.R
import com.appletv.airplay.service.AirPlayService

class AppleTvSettingsActivity : AppCompatActivity() {

    private lateinit var valDeviceName: TextView
    private lateinit var valRequirePin: TextView
    private lateinit var valResolution: TextView
    private lateinit var valStartBoot: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        valDeviceName = findViewById(R.id.valDeviceName)
        valRequirePin = findViewById(R.id.valRequirePin)
        valResolution = findViewById(R.id.valResolution)
        valStartBoot = findViewById(R.id.valStartBoot)

        loadCurrentSettings()
        setupListeners()
    }

    private fun loadCurrentSettings() {
        val prefs = getSharedPreferences("apple_tv_prefs", Context.MODE_PRIVATE)
        valDeviceName.text = prefs.getString("device_name", "Apple TV")
        valRequirePin.text = when (prefs.getString("pin_mode", "first_time")) {
            "always" -> getString(R.string.pin_always)
            "never" -> getString(R.string.pin_never)
            else -> getString(R.string.pin_first_time)
        }
        valResolution.text = prefs.getString("resolution", "1080p 60Hz")
        val isBoot = prefs.getBoolean("start_on_boot", true)
        valStartBoot.text = if (isBoot) "Ativado" else "Desativado"
    }

    private fun setupListeners() {
        val prefs = getSharedPreferences("apple_tv_prefs", Context.MODE_PRIVATE)

        findViewById<android.view.View>(R.id.itemDeviceName).setOnClickListener {
            val input = EditText(this).apply {
                setText(valDeviceName.text)
                setSingleLine()
            }
            AlertDialog.Builder(this)
                .setTitle("Nome da Apple TV")
                .setMessage("Digite o nome exibido na Central de Controle dos dispositivos:")
                .setView(input)
                .setPositiveButton("Salvar") { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isNotEmpty()) {
                        prefs.edit().putString("device_name", newName).apply()
                        valDeviceName.text = newName
                        AirPlayService.start(this) // Restart service with new name
                        Toast.makeText(this, "Nome atualizado!", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        findViewById<android.view.View>(R.id.itemRequirePin).setOnClickListener {
            val options = arrayOf(
                getString(R.string.pin_never),
                getString(R.string.pin_first_time),
                getString(R.string.pin_always)
            )
            AlertDialog.Builder(this)
                .setTitle(R.string.setting_require_pin)
                .setItems(options) { _, which ->
                    val mode = when (which) {
                        0 -> "never"
                        2 -> "always"
                        else -> "first_time"
                    }
                    prefs.edit()
                        .putString("pin_mode", mode)
                        .putBoolean("require_pin", which != 0)
                        .apply()
                    valRequirePin.text = options[which]
                    AirPlayService.start(this)
                }
                .show()
        }

        findViewById<android.view.View>(R.id.itemResolution).setOnClickListener {
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

        findViewById<android.view.View>(R.id.itemStartBoot).setOnClickListener {
            val current = prefs.getBoolean("start_on_boot", true)
            val updated = !current
            prefs.edit().putBoolean("start_on_boot", updated).apply()
            valStartBoot.text = if (updated) "Ativado" else "Desativado"
        }
    }
}
