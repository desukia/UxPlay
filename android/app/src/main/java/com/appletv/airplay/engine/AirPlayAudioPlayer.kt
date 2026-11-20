package com.appletv.airplay.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat as AndroidMediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class AirPlayAudioPlayer {
    private val TAG = "AirPlayAudioPlayer"

    private var audioTrack: AudioTrack? = null
    private var aacDecoder: MediaCodec? = null
    private var currentAacFormat = 0
    private val isRunning = AtomicBoolean(false)
    private var volume = 1.0f
    private var audioFramesWritten = 0L

    fun start(sampleRate: Int = 44100, channels: Int = 2) {
        if (isRunning.get() && audioTrack != null && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
            return
        }

        stop()

        val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        val bufferSize = (minBufferSize * 4).coerceAtLeast(16384)

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
            audioFramesWritten++
            if (audioFramesWritten == 1L || audioFramesWritten % 200L == 0L) {
                Log.d(TAG, "AudioTrack wrote frame #$audioFramesWritten ($length bytes, format=$format)")
            }
            when (format) {
                1 -> {
                    // Format 1 = Linear PCM 16-bit (Native ALAC decoded or raw PCM)
                    track.write(data, 0, length, AudioTrack.WRITE_BLOCKING)
                }
                4, 8 -> {
                    // Format 4 = AAC-LC, Format 8 = AAC-ELD (used during screen mirroring)
                    decodeAndPlayAac(data, length, format)
                }
                else -> {
                    // Fallback to direct write
                    track.write(data, 0, length, AudioTrack.WRITE_BLOCKING)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio to AudioTrack", e)
        }
    }

    private fun decodeAndPlayAac(data: ByteArray, length: Int, formatType: Int) {
        try {
            if (aacDecoder == null || currentAacFormat != formatType) {
                releaseAacDecoder()
                currentAacFormat = formatType

                val mediaFormat = AndroidMediaFormat.createAudioFormat(AndroidMediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2)
                if (formatType == 8) {
                    // AAC-ELD (Enhanced Low Delay AAC) used by AirPlay screen mirroring
                    // AudioSpecificConfig (CSD-0) for AAC-ELD 44100Hz 2-channel 480 spf: 0xF8, 0xE8, 0x50, 0x00
                    val csd0 = byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50.toByte(), 0x00.toByte())
                    mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
                    mediaFormat.setInteger(AndroidMediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
                    mediaFormat.setInteger(AndroidMediaFormat.KEY_IS_ADTS, 0)
                } else {
                    // AAC-LC (formatType == 4)
                    val csd0 = byteArrayOf(0x12.toByte(), 0x10.toByte())
                    mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
                    mediaFormat.setInteger(AndroidMediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    mediaFormat.setInteger(AndroidMediaFormat.KEY_IS_ADTS, 0)
                }
                mediaFormat.setInteger(AndroidMediaFormat.KEY_BIT_RATE, 256000)

                aacDecoder = MediaCodec.createDecoderByType(AndroidMediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(mediaFormat, null, null, 0)
                    start()
                }
                Log.i(TAG, "MediaCodec AAC decoder started for format=$formatType (ELD=${formatType == 8})")
            }

            val decoder = aacDecoder ?: return
            val inIndex = decoder.dequeueInputBuffer(10000) // 10ms timeout
            if (inIndex >= 0) {
                val inBuf = decoder.getInputBuffer(inIndex)
                if (inBuf != null) {
                    inBuf.clear()
                    inBuf.put(data, 0, length)
                    decoder.queueInputBuffer(inIndex, 0, length, 0, 0)
                }
            }

            val info = MediaCodec.BufferInfo()
            var outIndex = decoder.dequeueOutputBuffer(info, 2000)
            while (outIndex >= 0) {
                val outBuf = decoder.getOutputBuffer(outIndex)
                if (outBuf != null && info.size > 0) {
                    val pcmBytes = ByteArray(info.size)
                    outBuf.position(info.offset)
                    outBuf.get(pcmBytes)
                    audioTrack?.write(pcmBytes, 0, pcmBytes.size, AudioTrack.WRITE_BLOCKING)
                }
                decoder.releaseOutputBuffer(outIndex, false)
                outIndex = decoder.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "AAC decode error for format=$formatType: ${e.message}")
            // Force recreation on error
            releaseAacDecoder()
        }
    }

    private fun releaseAacDecoder() {
        try {
            aacDecoder?.stop()
            aacDecoder?.release()
        } catch (e: Exception) {
            // Ignore
        }
        aacDecoder = null
        currentAacFormat = 0
    }

    fun setVolume(vol: Float) {
        this.volume = vol.coerceIn(0.0f, 1.0f)
        audioTrack?.setVolume(this.volume)
    }

    fun stop() {
        isRunning.set(false)
        NativeAirPlay.audioFrameListener = null
        releaseAacDecoder()

        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack", e)
        }
        audioTrack = null
        audioFramesWritten = 0L
        Log.i(TAG, "AirPlayAudioPlayer stopped")
    }
}
