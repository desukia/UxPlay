package com.appletv.airplay.engine

import android.util.Log
import com.appletv.airplay.model.AirPlayClientInfo
import com.appletv.airplay.model.AirPlayState
import com.appletv.airplay.model.TrackMetadata
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object NativeAirPlay {
    private const val TAG = "NativeAirPlay"

    init {
        try {
            System.loadLibrary("airplay_native")
            Log.i(TAG, "Native library 'airplay_native' loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load 'airplay_native' library", e)
        }
    }

    // State flows for UI observations
    private val _stateFlow = MutableStateFlow(AirPlayState.IDLE)
    val stateFlow = _stateFlow.asStateFlow()

    private val _pinCodeFlow = MutableStateFlow<String?>(null)
    val pinCodeFlow = _pinCodeFlow.asStateFlow()

    private val _clientInfoFlow = MutableStateFlow(AirPlayClientInfo())
    val clientInfoFlow = _clientInfoFlow.asStateFlow()

    private val _metadataFlow = MutableStateFlow(TrackMetadata())
    val metadataFlow = _metadataFlow.asStateFlow()

    private val _volumeFlow = MutableStateFlow(1.0f)
    val volumeFlow = _volumeFlow.asStateFlow()

    private val _videoSizeFlow = MutableStateFlow(Pair(1920, 1080))
    val videoSizeFlow = _videoSizeFlow.asStateFlow()

    data class AudioProgress(
        val positionMs: Long = 0L,
        val durationMs: Long = 0L
    )

    private val _progressFlow = MutableStateFlow(AudioProgress())
    val progressFlow = _progressFlow.asStateFlow()

    // Raw frame listeners for Decoders
    var videoFrameListener: ((data: ByteArray, length: Int, timestampNs: Long, nalCount: Int) -> Unit)? = null
    var audioFrameListener: ((data: ByteArray, length: Int, format: Int, timestampNs: Long, seqNum: Int) -> Unit)? = null

    // Shared hardware video decoder instance
    val videoDecoder = AirPlayVideoDecoder()

    // Persistent video configuration cache (persists even if Activity is recreated or closed)
    @Volatile var cachedSpsPps: ByteArray? = null
    @Volatile var cachedKeyFrame: ByteArray? = null

    // Callback invoked when incoming video frame arrives (used by AirPlayService to detect live streaming)
    var onIncomingVideoFrameListener: (() -> Unit)? = null

    fun extractSpsPpsFromData(data: ByteArray, length: Int): ByteArray? {
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

    fun isIdrFrame(data: ByteArray, length: Int): Boolean {
        for (i in 0 until (length - 4).coerceAtLeast(0)) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                val nalStart = when {
                    data[i + 2] == 1.toByte() -> i + 3
                    i + 3 < length && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> i + 4
                    else -> -1
                }
                if (nalStart != -1 && nalStart < length) {
                    val nalType = data[nalStart].toInt() and 0x1F
                    if (nalType == 5) return true
                }
            }
        }
        return false
    }

    interface NativeCallbacks {
        fun onStateChanged(stateCode: Int)
        fun onPinCodeRequired(pin: String)
        fun onPinCodeDismissed()
        fun onClientConnected(clientName: String, deviceModel: String)
        fun onClientDisconnected()
        fun onVideoFrame(data: ByteArray, length: Int, timestampNs: Long, nalCount: Int)
        fun onVideoSizeChanged(width: Int, height: Int)
        fun onAudioFrame(data: ByteArray, length: Int, format: Int, timestampNs: Long, seqNum: Int)
        fun onAudioProgress(positionMs: Long, durationMs: Long)
        fun onMetadata(title: String, artist: String, album: String)
        fun onCoverArt(jpegData: ByteArray)
        fun onVolumeChanged(volume: Float)
    }

    private val callbacks = object : NativeCallbacks {
        override fun onStateChanged(stateCode: Int) {
            val newState = AirPlayState.fromCode(stateCode)
            Log.i(TAG, "Native state changed: $newState")
            _stateFlow.value = newState
            if (newState == AirPlayState.IDLE) {
                _pinCodeFlow.value = null
            }
        }

        override fun onPinCodeRequired(pin: String) {
            Log.i(TAG, "PIN Code required: $pin")
            _pinCodeFlow.value = pin
            _stateFlow.value = AirPlayState.PIN_REQUIRED
        }

        override fun onPinCodeDismissed() {
            Log.i(TAG, "PIN Code dismissed")
            _pinCodeFlow.value = null
            if (_stateFlow.value == AirPlayState.PIN_REQUIRED) {
                _stateFlow.value = AirPlayState.IDLE
            }
        }

        override fun onClientConnected(clientName: String, deviceModel: String) {
            Log.i(TAG, "Client connected: $clientName ($deviceModel)")
            _clientInfoFlow.value = AirPlayClientInfo(clientName, deviceModel)
        }

        override fun onClientDisconnected() {
            Log.i(TAG, "Client disconnected")
            videoDecoder.stop()
            cachedSpsPps = null
            cachedKeyFrame = null
            _clientInfoFlow.value = AirPlayClientInfo()
            _metadataFlow.value = TrackMetadata()
            _progressFlow.value = AudioProgress()
            _pinCodeFlow.value = null
            _stateFlow.value = AirPlayState.IDLE
        }

        override fun onVideoFrame(data: ByteArray, length: Int, timestampNs: Long, nalCount: Int) {
            val sps = extractSpsPpsFromData(data, length)
            if (sps != null) {
                cachedSpsPps = sps
            }
            if (isIdrFrame(data, length)) {
                cachedKeyFrame = data.copyOf(length)
            }
            onIncomingVideoFrameListener?.invoke()
            videoFrameListener?.invoke(data, length, timestampNs, nalCount)
        }

        override fun onVideoSizeChanged(width: Int, height: Int) {
            Log.i(TAG, "Stream resolution negotiated: ${width}x${height}")
            _videoSizeFlow.value = Pair(width, height)
            videoDecoder.setDimensions(width, height)
        }

        override fun onAudioFrame(data: ByteArray, length: Int, format: Int, timestampNs: Long, seqNum: Int) {
            audioFrameListener?.invoke(data, length, format, timestampNs, seqNum)
        }

        override fun onAudioProgress(positionMs: Long, durationMs: Long) {
            _progressFlow.value = AudioProgress(positionMs, durationMs)
        }

        override fun onMetadata(title: String, artist: String, album: String) {
            Log.i(TAG, "Metadata received: $title - $artist ($album)")
            _metadataFlow.value = _metadataFlow.value.copy(
                title = title,
                artist = artist,
                album = album
            )
        }

        override fun onCoverArt(jpegData: ByteArray) {
            Log.i(TAG, "Cover art received: ${jpegData.size} bytes")
            _metadataFlow.value = _metadataFlow.value.copy(
                coverArtBytes = jpegData
            )
        }

        override fun onVolumeChanged(volume: Float) {
            _volumeFlow.value = volume
        }
    }

    fun init() {
        try {
            nativeInit(callbacks)
        } catch (e: Exception) {
            Log.e(TAG, "nativeInit failed", e)
        }
    }

    fun startServer(
        deviceName: String = "Apple TV",
        width: Int = 1920,
        height: Int = 1080,
        fps: Int = 60,
        requirePin: Boolean = false,
        fixedPin: String? = null
    ): Int {
        init()
        return nativeStartServer(deviceName, width, height, fps, requirePin, fixedPin)
    }

    fun stopServer() {
        nativeStopServer()
    }

    fun setVolume(volume: Float) {
        nativeSetVolume(volume)
    }

    // JNI Native Methods
    private external fun nativeInit(callbacks: NativeCallbacks)
    private external fun nativeStartServer(
        deviceName: String,
        width: Int,
        height: Int,
        fps: Int,
        requirePin: Boolean,
        fixedPin: String?
    ): Int
    private external fun nativeStopServer()
    private external fun nativeSetVolume(volume: Float)
}
