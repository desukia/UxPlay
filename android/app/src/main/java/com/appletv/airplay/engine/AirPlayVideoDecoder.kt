package com.appletv.airplay.engine

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class AirPlayVideoDecoder {
    private val TAG = "AirPlayVideoDecoder"

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private val isRunning = AtomicBoolean(false)
    private var isConfigured = false

    private var currentWidth = 1920
    private var currentHeight = 1080

    fun start(surface: Surface, width: Int = 1920, height: Int = 1080) {
        this.surface = surface
        this.currentWidth = width
        this.currentHeight = height
        this.isRunning.set(true)
        this.isConfigured = false

        initCodec()

        NativeAirPlay.videoFrameListener = { data, length, timestampNs, _ ->
            if (isRunning.get()) {
                feedFrame(data, length, timestampNs)
            }
        }

        Log.i(TAG, "AirPlayVideoDecoder started for Surface at ${width}x${height}")
    }

    private fun initCodec() {
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            // Ignore
        }

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, currentWidth, currentHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1) // Enable low-latency decoding for realtime AirPlay
                setInteger(MediaFormat.KEY_PUSH_BLANK_BUFFERS_ON_STOP, 1)
            }

            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, surface, null, 0)
                start()
            }
            isConfigured = true
            Log.i(TAG, "Hardware MediaCodec AVC initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaCodec AVC", e)
        }
    }

    private fun feedFrame(data: ByteArray, length: Int, timestampNs: Long) {
        val activeCodec = codec ?: return
        if (!isConfigured) return

        try {
            val inputIndex = activeCodec.dequeueInputBuffer(10000) // 10ms timeout
            if (inputIndex >= 0) {
                val inputBuffer = activeCodec.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(data, 0, length)

                    val isConfigFrame = isSpsOrPps(data, length)
                    val flags = if (isConfigFrame) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0

                    activeCodec.queueInputBuffer(
                        inputIndex,
                        0,
                        length,
                        timestampNs / 1000L, // Microseconds PTS
                        flags
                    )
                }
            }

            // Drain decoded frames to display surface
            drainOutput(activeCodec)
        } catch (e: Exception) {
            Log.e(TAG, "Error feeding video frame to MediaCodec", e)
        }
    }

    private fun drainOutput(activeCodec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var outputIndex = activeCodec.dequeueOutputBuffer(info, 0)

        while (outputIndex >= 0) {
            // true renders directly to the bound Surface at the correct time
            activeCodec.releaseOutputBuffer(outputIndex, true)
            outputIndex = activeCodec.dequeueOutputBuffer(info, 0)
        }
    }

    private fun isSpsOrPps(data: ByteArray, length: Int): Boolean {
        if (length < 5) return false
        for (i in 0 until (length - 4)) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val nalType = (data[i + 4].toInt() and 0x1F)
                if (nalType == 7 || nalType == 8) { // 7 = SPS, 8 = PPS
                    return true
                }
            }
        }
        return false
    }

    fun stop() {
        isRunning.set(false)
        NativeAirPlay.videoFrameListener = null
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaCodec", e)
        }
        codec = null
        isConfigured = false
        Log.i(TAG, "AirPlayVideoDecoder stopped")
    }
}
