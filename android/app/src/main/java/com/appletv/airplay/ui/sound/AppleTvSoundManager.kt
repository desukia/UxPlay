package com.appletv.airplay.ui.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import com.appletv.airplay.R

class AppleTvSoundManager(context: Context) {
    private val TAG = "AppleTvSoundManager"

    private val soundPool: SoundPool
    private val soundConnectId: Int
    private val soundFocusId: Int
    private val soundPinDigitId: Int
    private val soundPinDeleteId: Int

    init {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(audioAttributes)
            .build()

        soundConnectId = soundPool.load(context, R.raw.apple_tv_connect, 1)
        soundFocusId = soundPool.load(context, R.raw.apple_tv_focus, 1)
        soundPinDigitId = soundPool.load(context, R.raw.apple_tv_pin_digit, 1)
        soundPinDeleteId = soundPool.load(context, R.raw.apple_tv_pin_delete, 1)
    }

    fun playConnectSound() {
        try {
            soundPool.play(soundConnectId, 1.0f, 1.0f, 1, 0, 1.0f)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play connect sound", e)
        }
    }

    fun playFocusSound() {
        try {
            soundPool.play(soundFocusId, 0.7f, 0.7f, 1, 0, 1.0f)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play focus sound", e)
        }
    }

    fun playPinDigitSound() {
        try {
            soundPool.play(soundPinDigitId, 1.0f, 1.0f, 1, 0, 1.0f)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play pin digit sound", e)
        }
    }

    fun playPinDeleteSound() {
        try {
            soundPool.play(soundPinDeleteId, 0.8f, 0.8f, 1, 0, 1.0f)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play pin delete sound", e)
        }
    }

    fun release() {
        try {
            soundPool.release()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
