package com.appletv.airplay.model

enum class AirPlayState(val code: Int) {
    IDLE(0),
    PIN_REQUIRED(1),
    MIRRORING(2),
    AUDIO_STREAMING(3);

    companion object {
        fun fromCode(code: Int): AirPlayState =
            entries.find { it.code == code } ?: IDLE
    }
}

data class AirPlayClientInfo(
    val deviceName: String = "",
    val deviceModel: String = ""
)
