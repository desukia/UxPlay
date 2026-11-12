package com.appletv.airplay.model

data class TrackMetadata(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val coverArtBytes: ByteArray? = null,
    val durationSeconds: Float = 0f,
    val positionSeconds: Float = 0f,
    val isPlaying: Boolean = true
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TrackMetadata
        if (title != other.title) return false
        if (artist != other.artist) return false
        if (album != other.album) return false
        if (coverArtBytes != null) {
            if (other.coverArtBytes == null) return false
            if (!coverArtBytes.contentEquals(other.coverArtBytes)) return false
        } else if (other.coverArtBytes != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + album.hashCode()
        result = 31 * result + (coverArtBytes?.contentHashCode() ?: 0)
        return result
    }
}
