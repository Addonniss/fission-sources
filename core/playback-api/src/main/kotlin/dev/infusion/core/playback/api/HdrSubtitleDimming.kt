package dev.infusion.core.playback.api

enum class HdrSubtitleDimming {

    ON,

    OFF,
    ;

    companion object {

        val DEFAULT: HdrSubtitleDimming = ON

        fun fromStored(stored: String?): HdrSubtitleDimming =
            entries.firstOrNull { it.name == stored } ?: DEFAULT
    }
}

object SubtitleHdrDimming {

    const val MAX_CHANNEL: Int = 179

    fun dimForegroundArgb(argb: Int): Int {
        val alpha = (argb ushr 24) and 0xFF
        val red = (argb ushr 16) and 0xFF
        val green = (argb ushr 8) and 0xFF
        val blue = argb and 0xFF
        val maximum = maxOf(red, green, blue)

        if (maximum <= MAX_CHANNEL) return argb
        return (alpha shl 24) or
            (scale(red, maximum) shl 16) or
            (scale(green, maximum) shl 8) or
            scale(blue, maximum)
    }

    private fun scale(channel: Int, maximum: Int): Int =
        (channel * MAX_CHANNEL + maximum / 2) / maximum

    fun effectiveHdr(video: VideoTrack?): Boolean = PlayingMediaInfo.dynamicRangeLabel(video) != null

    fun isDimmingActive(dimming: HdrSubtitleDimming, video: VideoTrack?): Boolean =
        dimming == HdrSubtitleDimming.ON && effectiveHdr(video)
}
