package com.appletv.airplay.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class AirPlayAudioPlayer {
    private val TAG = "AirPlayAudioPlayer"

    private var audioTrack: AudioTrack? = null
    private val isRunning = AtomicBoolean(false)
    private var volume = 1.0f

    fun start(sampleRate: Int = 44100, channels: Int = 2) {
        if (isRunning.get()) return

        val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(8192)

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.setVolume(volume)
            audioTrack?.play()
            isRunning.set(true)

            NativeAirPlay.audioFrameListener = { data, length, format, _, _ ->
                if (isRunning.get()) {
                    writeAudio(data, length, format)
                }
            }

            Log.i(TAG, "AudioTrack started: ${sampleRate}Hz, $channels channels, buffer: $bufferSize bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
        }
    }

    private fun writeAudio(data: ByteArray, length: Int, format: Int) {
        val track = audioTrack ?: return
        if (length <= 0) return

        try {
            // Format 1 = Linear PCM (S16LE)
            // Format 2 = ALAC (Apple Lossless)
            // Format 4 = AAC
            // Note: UxPlay delivers uncompressed or decoded PCM chunks to audio_renderer_render_buffer
            track.write(data, 0, length, AudioTrack.WRITE_NON_BLOCKING)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio to AudioTrack", e)
        }
    }

    fun setVolume(vol: Float) {
        this.volume = vol.coerceIn(0.0f, 1.0f)
        audioTrack?.setVolume(this.volume)
    }

    fun stop() {
        isRunning.set(false)
        NativeAirPlay.audioFrameListener = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack", e)
        }
        audioTrack = null
        Log.i(TAG, "AudioTrack stopped")
    }
}
