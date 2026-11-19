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
    private var surface: Surface? = null
    private val isRunning = AtomicBoolean(false)
    private var isConfigured = false
    private var drainThread: Thread? = null

    private var currentWidth = 1920
    private var currentHeight = 1080
    private var frameCount = 0L
    private var renderedFrames = 0L

    fun start(surface: Surface, width: Int = 1920, height: Int = 1080) {
        if (isRunning.get() && this.surface == surface && isConfigured) {
            return
        }

        stop()

        this.surface = surface
        this.currentWidth = if (width > 0) width else 1920
        this.currentHeight = if (height > 0) height else 1080
        this.isRunning.set(true)
        this.isConfigured = false
        this.frameCount = 0L
        this.renderedFrames = 0L

        initCodec()
        startDrainThread()

        NativeAirPlay.videoFrameListener = { data, length, timestampNs, _ ->
            if (isRunning.get()) {
                feedFrame(data, length, timestampNs)
            }
        }

        Log.i(TAG, "AirPlayVideoDecoder started for Surface at ${currentWidth}x${currentHeight}")
    }

    fun setDimensions(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width != currentWidth || height != currentHeight) {
            Log.i(TAG, "Video resolution change detected: ${currentWidth}x${currentHeight} -> ${width}x${height}")
            currentWidth = width
            currentHeight = height
            val currentSurface = surface
            if (isRunning.get() && currentSurface != null && currentSurface.isValid) {
                initCodec()
            }
        }
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
                // Critical: Explicitly set input buffer size to 2MB to prevent BufferOverflowException on large 1080p/4K I-frames
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
                setInteger(MediaFormat.KEY_PUSH_BLANK_BUFFERS_ON_STOP, 1)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    } catch (e: Exception) {
                        // Optional hint, ignore if unsupported
                    }
                }
            }

            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, surface, null, 0)
                start()
            }
            isConfigured = true
            Log.i(TAG, "Hardware MediaCodec AVC initialized successfully for ${currentWidth}x${currentHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaCodec AVC", e)
        }
    }

    private fun startDrainThread() {
        drainThread = Thread({
            val info = MediaCodec.BufferInfo()
            while (isRunning.get()) {
                val activeCodec = codec ?: break
                if (!isConfigured) {
                    SystemClock.sleep(10)
                    continue
                }

                try {
                    val outputIndex = activeCodec.dequeueOutputBuffer(info, 10000) // 10ms timeout
                    when {
                        outputIndex >= 0 -> {
                            // Render frame directly to the hardware Surface with zero display latency
                            activeCodec.releaseOutputBuffer(outputIndex, true)
                            renderedFrames++
                            if (renderedFrames == 1L || renderedFrames % 120L == 0L) {
                                Log.i(TAG, "Hardware decoded & rendered frame #$renderedFrames to Surface")
                            }
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            Log.i(TAG, "MediaCodec output format changed: ${activeCodec.outputFormat}")
                        }
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // Normal timeout, continue loop
                        }
                    }
                } catch (e: IllegalStateException) {
                    // Codec might be released during stop()
                    break
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.w(TAG, "Exception in output drain loop: ${e.message}")
                    }
                }
            }
        }, "AirPlay-VideoDrain").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun feedFrame(data: ByteArray, length: Int, timestampNs: Long) {
        val activeCodec = codec ?: return
        if (!isConfigured || length <= 0) return

        try {
            val inputIndex = activeCodec.dequeueInputBuffer(10000) // 10ms timeout
            if (inputIndex >= 0) {
                val inputBuffer = activeCodec.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    if (length > inputBuffer.capacity()) {
                        Log.e(TAG, "Frame size ($length bytes) exceeds input buffer capacity (${inputBuffer.capacity()})")
                        return
                    }
                    inputBuffer.clear()
                    inputBuffer.put(data, 0, length)

                    frameCount++
                    val ptsUs = if (timestampNs > 0) {
                        timestampNs / 1000L
                    } else {
                        SystemClock.elapsedRealtimeNanos() / 1000L
                    }

                    // Annex B H.264 streams contain in-band SPS/PPS.
                    // Flags must be 0 so MediaCodec decodes the IDR slice properly.
                    activeCodec.queueInputBuffer(
                        inputIndex,
                        0,
                        length,
                        ptsUs,
                        0
                    )

                    if (frameCount == 1L || frameCount % 120L == 0L) {
                        Log.d(TAG, "Fed video frame #$frameCount ($length bytes) to MediaCodec")
                    }
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
        Log.i(TAG, "AirPlayVideoDecoder stopped (total fed: $frameCount, rendered: $renderedFrames)")
    }
}
