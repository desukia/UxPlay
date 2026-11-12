package com.appletv.airplay.ui.theme

import android.graphics.Color

object AppleTvTheme {
    // Authentic Apple TV tvOS Colors
    val BackgroundObsidian = Color.parseColor("#0A0A0C")
    val FrostedGlassBackground = Color.parseColor("#331E1E24")
    val FrostedGlassBorder = Color.parseColor("#33FFFFFF")
    val CardBackground = Color.parseColor("#262C2C35")
    val CardBackgroundFocused = Color.parseColor("#4DFFFFFF")

    val TextPrimary = Color.parseColor("#FFFFFFFF")
    val TextSecondary = Color.parseColor("#99EBEBF5")
    val TextTertiary = Color.parseColor("#4DEBEBF5")

    val AppleTvAccent = Color.parseColor("#0071E3")
    val AppleTvGreen = Color.parseColor("#34C759")

    // Aerial Screensaver Wallpapers / Video URLs (Dynamic Apple TV Aerial flyover loops)
    val AerialPresets = listOf(
        AerialTheme("San Francisco", "https://sylvan.apple.com/Aerials/2x/videos/b1-1.mov", "#1A2536"),
        AerialTheme("Hawaii Coast", "https://sylvan.apple.com/Aerials/2x/videos/b2-1.mov", "#1B3B36"),
        AerialTheme("London Night", "https://sylvan.apple.com/Aerials/2x/videos/b3-1.mov", "#201D2D"),
        AerialTheme("Earth from Space", "https://sylvan.apple.com/Aerials/2x/videos/b4-1.mov", "#0D111A"),
        AerialTheme("Greenland Ice", "https://sylvan.apple.com/Aerials/2x/videos/b5-1.mov", "#2B3C4F")
    )
}

data class AerialTheme(
    val name: String,
    val videoUrl: String,
    val dominantColorHex: String
)
