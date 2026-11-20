package com.appletv.airplay.engine

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class AirPlayVideoDecoder {
    private val TAG = "AirPlayVideoDecoder"

    private var codec: MediaCodec? = null
    private var displaySurface: Surface? = null

    private val isRunning = AtomicBoolean(false)
    @Volatile private var isConfigured = false
    private var drainThread: Thread? = null

    private var currentWidth = 1920
    private var currentHeight = 1080
    private var frameCount = 0L
    private var renderedFrames = 0L

    private var cachedSpsPps: ByteArray? = null
    private var hasFedSps = false

    var onResolutionChangedListener: ((width: Int, height: Int) -> Unit)? = null

    /**
     * Start hardware video decoding directly to the provided Surface.
     */
    fun start(surface: Surface, width: Int = 1920, height: Int = 1080) {
        if (isRunning.get() && this.displaySurface == surface && codec != null && isConfigured) {
            Log.d(TAG, "AirPlayVideoDecoder already initialized on this Surface")
            return
        }

        stop()

        this.displaySurface = surface
        this.currentWidth = if (width > 0) width else 1920
        this.currentHeight = if (height > 0) height else 1080
        this.isRunning.set(true)
        this.isConfigured = false
        this.frameCount = 0L
        this.renderedFrames = 0L
        this.hasFedSps = false

        initCodec()
        startDrainThread()

        // If we have cached SPS/PPS from initial connection, pre-feed them to configure the decoder immediately
        val ptsUs = SystemClock.elapsedRealtimeNanos() / 1000L
        val cachedSps = NativeAirPlay.cachedSpsPps ?: cachedSpsPps
        if (cachedSps != null) {
            codec?.let { activeCodec ->
                if (enqueueBuffer(activeCodec, cachedSps, cachedSps.size, ptsUs)) {
                    hasFedSps = true
                    Log.i(TAG, "Pre-fed cached SPS/PPS (${cachedSps.size} bytes) on decoder start")
                }
            }
        }

        NativeAirPlay.videoFrameListener = { data, length, timestampNs, _ ->
            if (isRunning.get()) {
                feedFrame(data, length, timestampNs)
            }
        }

        Log.i(TAG, "AirPlayVideoDecoder started for ${currentWidth}x${currentHeight} directly on Surface")
    }

    fun attachSurface(surface: Surface) {
        start(surface, currentWidth, currentHeight)
    }

    fun detachSurface() {
        stop()
    }

    fun setDimensions(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        this.currentWidth = width
        this.currentHeight = height
        onResolutionChangedListener?.invoke(width, height)
    }

    private fun initCodec() {
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            // Ignore
        }
        codec = null
        isConfigured = false

        val targetSurface = displaySurface
        if (targetSurface == null || !targetSurface.isValid) {
            Log.e(TAG, "No valid display surface available for MediaCodec AVC")
            return
        }

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, currentWidth, currentHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 3 * 1024 * 1024)
                setInteger(MediaFormat.KEY_PUSH_BLANK_BUFFERS_ON_STOP, 1)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    } catch (e: Exception) {
                        // Optional hint
                    }
                }
            }

            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, targetSurface, null, 0)
                try {
                    setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                } catch (e: Exception) {
                    // Ignore if unsupported
                }
                start()
            }
            isConfigured = true
            Log.i(TAG, "Hardware MediaCodec AVC initialized successfully for ${currentWidth}x${currentHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaCodec AVC", e)
        }
    }

    private fun startDrainThread() {
        if (drainThread?.isAlive == true) return

        drainThread = Thread({
            val info = MediaCodec.BufferInfo()
            while (isRunning.get()) {
                val activeCodec = codec
                if (activeCodec == null || !isConfigured) {
                    SystemClock.sleep(10)
                    continue
                }

                try {
                    val outputIndex = activeCodec.dequeueOutputBuffer(info, 10000)
                    when {
                        outputIndex >= 0 -> {
                            activeCodec.releaseOutputBuffer(outputIndex, true)
                            renderedFrames++
                            if (renderedFrames == 1L || renderedFrames % 120L == 0L) {
                                Log.i(TAG, "Hardware decoded & rendered frame #$renderedFrames to Surface")
                            }
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = activeCodec.outputFormat
                            Log.i(TAG, "MediaCodec output format changed: $newFormat")
                            val w = newFormat.getInteger(MediaFormat.KEY_WIDTH)
                            val h = newFormat.getInteger(MediaFormat.KEY_HEIGHT)
                            val cropL = if (newFormat.containsKey("crop-left")) newFormat.getInteger("crop-left") else 0
                            val cropR = if (newFormat.containsKey("crop-right")) newFormat.getInteger("crop-right") else w - 1
                            val cropT = if (newFormat.containsKey("crop-top")) newFormat.getInteger("crop-top") else 0
                            val cropB = if (newFormat.containsKey("crop-bottom")) newFormat.getInteger("crop-bottom") else h - 1
                            val realW = (cropR - cropL + 1).coerceAtLeast(1)
                            val realH = (cropB - cropT + 1).coerceAtLeast(1)
                            onResolutionChangedListener?.invoke(realW, realH)
                        }
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // Normal timeout
                        }
                    }
                } catch (e: IllegalStateException) {
                    SystemClock.sleep(10)
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.w(TAG, "Exception in output drain loop: ${e.message}")
                    }
                    SystemClock.sleep(10)
                }
            }
            Log.i(TAG, "AirPlay-VideoDrain thread exited cleanly")
        }, "AirPlay-VideoDrain").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun extractSpsPps(data: ByteArray, length: Int): ByteArray? {
        var spsStart = -1
        var ppsEnd = -1
        var i = 0
        while (i < length - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                val startCodeLen = if (data[i + 2] == 1.toByte()) 3 else if (i + 3 < length && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) 4 else 0
                if (startCodeLen > 0) {
                    val nalStart = i + startCodeLen
                    val nalType = data[nalStart].toInt() and 0x1F
                    if (nalType == 7 && spsStart == -1) {
                        spsStart = i
                    } else if (nalType != 7 && nalType != 8 && spsStart != -1) {
                        ppsEnd = i
                        break
                    }
                    i = nalStart
                }
            }
            i++
        }
        if (spsStart != -1 && ppsEnd > spsStart) {
            return data.copyOfRange(spsStart, ppsEnd)
        }
        return null
    }

    private fun containsSps(data: ByteArray, length: Int): Boolean {
        for (i in 0 until (length - 4).coerceAtLeast(0)) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                val nalStart = when {
                    data[i + 2] == 1.toByte() -> i + 3
                    i + 3 < length && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> i + 4
                    else -> -1
                }
                if (nalStart != -1 && nalStart < length) {
                    val nalType = data[nalStart].toInt() and 0x1F
                    if (nalType == 7) return true
                }
            }
        }
        return false
    }

    private fun enqueueBuffer(activeCodec: MediaCodec, data: ByteArray, length: Int, ptsUs: Long): Boolean {
        val inputIndex = activeCodec.dequeueInputBuffer(10000)
        if (inputIndex >= 0) {
            val inputBuffer = activeCodec.getInputBuffer(inputIndex) ?: return false
            if (length > inputBuffer.capacity()) {
                Log.e(TAG, "Frame size ($length bytes) exceeds input buffer capacity (${inputBuffer.capacity()})")
                return false
            }
            inputBuffer.clear()
            inputBuffer.put(data, 0, length)
            activeCodec.queueInputBuffer(inputIndex, 0, length, ptsUs, 0)
            return true
        }
        return false
    }

    private fun feedFrame(data: ByteArray, length: Int, timestampNs: Long) {
        val activeCodec = codec ?: return
        if (!isConfigured || length <= 0) return

        try {
            val ptsUs = if (timestampNs > 0) {
                timestampNs / 1000L
            } else {
                SystemClock.elapsedRealtimeNanos() / 1000L
            }

            val extractedSps = extractSpsPps(data, length)
            if (extractedSps != null) {
                cachedSpsPps = extractedSps
                NativeAirPlay.cachedSpsPps = extractedSps
                hasFedSps = true
            } else if (containsSps(data, length)) {
                cachedSpsPps = data.copyOf(length)
                NativeAirPlay.cachedSpsPps = cachedSpsPps
                hasFedSps = true
            } else if (!hasFedSps) {
                val spsData = NativeAirPlay.cachedSpsPps ?: cachedSpsPps
                if (spsData != null && enqueueBuffer(activeCodec, spsData, spsData.size, ptsUs)) {
                    hasFedSps = true
                    Log.i(TAG, "Pre-fed cached SPS/PPS (${spsData.size} bytes) to ensure decoder synchronization")
                }
            }

            if (enqueueBuffer(activeCodec, data, length, ptsUs)) {
                frameCount++
                if (frameCount == 1L || frameCount % 120L == 0L) {
                    Log.d(TAG, "Fed video frame #$frameCount ($length bytes) to MediaCodec")
                }
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "Error feeding video frame to MediaCodec", e)
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        NativeAirPlay.videoFrameListener = null

        try {
            drainThread?.join(500)
        } catch (e: Exception) {
            // Ignore
        }
        drainThread = null

        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaCodec", e)
        }
        codec = null
        isConfigured = false
        displaySurface = null
        Log.i(TAG, "AirPlayVideoDecoder stopped (total fed: $frameCount, rendered: $renderedFrames)")
    }
}
