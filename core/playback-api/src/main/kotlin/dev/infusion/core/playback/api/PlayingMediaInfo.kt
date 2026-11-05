package dev.infusion.core.playback.api

object PlayingMediaInfo {

    const val Separator: String = " · "

    const val RESOLUTION_FULL_HD: String = "Full HD"

    const val RESOLUTION_4K: String = "4K"

    const val RESOLUTION_HD: String = "HD"

    const val RESOLUTION_SD: String = "SD"

    const val DYNAMIC_RANGE_DOLBY_VISION: String = "Dolby Vision"

    const val DYNAMIC_RANGE_HDR10: String = "HDR10"

    const val DYNAMIC_RANGE_HLG: String = "HLG"

    const val DYNAMIC_RANGE_HDR: String = "HDR"

    const val BITRATE_UNIT: String = "Mbps"

    fun resolutionLabel(video: VideoTrack?): String? {

        val width = video?.width ?: return null
        val height = video?.height ?: return null
        if (width <= 0 || height <= 0) return null
        val family = DisplayResolutionFamily.forContent(width, height) ?: return null
        return when (family) {
            DisplayResolutionFamily.SD_480,
            DisplayResolutionFamily.SD_576,
            -> RESOLUTION_SD

            DisplayResolutionFamily.HD_720 -> RESOLUTION_HD
            DisplayResolutionFamily.FHD_1080 -> RESOLUTION_FULL_HD
            DisplayResolutionFamily.UHD_2160 -> RESOLUTION_4K

            DisplayResolutionFamily.QHD_1440,
            DisplayResolutionFamily.UHD_4320,
            -> null
        }
    }

    fun videoCodecLabel(video: VideoTrack?): String? = CodecDisplay.videoLabel(video)

    fun dynamicRangeLabel(video: VideoTrack?): String? {
        if (video == null) return null
        if (isDolbyVision(video)) return DYNAMIC_RANGE_DOLBY_VISION
        return when (video.colourTransfer) {
            VideoColourTransfer.PQ -> DYNAMIC_RANGE_HDR10
            VideoColourTransfer.HLG -> DYNAMIC_RANGE_HLG
            VideoColourTransfer.SDR -> null
            VideoColourTransfer.OTHER, null -> if (video.hdr == true) DYNAMIC_RANGE_HDR else null
        }
    }

    fun audioCodecLabel(audio: AudioTrack?): String? = CodecDisplay.audioLabel(audio)

    fun bitrateLabel(bitrate: PlayingMediaBitrate?): String? = bitrate?.label

    fun line(
        video: VideoTrack?,
        audio: AudioTrack?,
        bitrate: PlayingMediaBitrate? = null,
    ): String? {
        val facts = listOfNotNull(
            resolutionLabel(video),
            videoCodecLabel(video),
            dynamicRangeLabel(video),
            audioCodecLabel(audio),
            bitrateLabel(bitrate),
        )
        if (facts.isEmpty()) return null
        return facts.joinToString(Separator)
    }

    private fun isDolbyVision(video: VideoTrack): Boolean {
        val mime = video.codec?.trim()?.substringBefore(';')?.trim()?.lowercase()
        if (mime == MimeDolbyVision) return true
        val family = video.codecDetail?.trim()?.lowercase()?.substringBefore('.')
        return family == "dvhe" || family == "dvh1"
    }

    private const val MimeDolbyVision: String = "video/dolby-vision"
}
