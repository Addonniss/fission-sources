package dev.infusion.core.playback.api

object CodecDisplay {

    fun videoLabel(track: VideoTrack?): String? = label(track?.codec, track?.codecDetail)

    fun audioLabel(track: AudioTrack?): String? = label(track?.codec, track?.codecDetail)

    fun label(codec: String?, codecDetail: String?): String? {
        val mime = codec?.trim()?.substringBefore(';')?.trim()?.lowercase()
        if (!mime.isNullOrEmpty()) {
            MIME_NAMES[mime]?.let { return it }
        }
        val detail = codecDetail?.trim()?.lowercase()
        if (!detail.isNullOrEmpty()) {

            val family = detail.substringBefore('.')
            DETAIL_FAMILIES[family]?.let { return it }
        }
        return null
    }

    private val MIME_NAMES: Map<String, String> = mapOf(

        "video/avc" to "AVC (H.264)",
        "video/hevc" to "HEVC (H.265)",
        "video/x-vnd.on2.vp9" to "VP9",
        "video/x-vnd.on2.vp8" to "VP8",
        "video/av01" to "AV1",
        "video/mp4v-es" to "MPEG-4",
        "video/mpeg2" to "MPEG-2",
        "video/mpeg" to "MPEG",
        "video/3gpp" to "H.263",
        "video/dolby-vision" to "HEVC (H.265)",

        "audio/eac3" to "E-AC-3",
        "audio/ac3" to "AC-3",
        "audio/mp4a-latm" to "AAC",
        "audio/aac" to "AAC",
        "audio/opus" to "Opus",
        "audio/vorbis" to "Vorbis",
        "audio/flac" to "FLAC",
        "audio/mpeg" to "MP3",
        "audio/mpeg-l1" to "MP1",
        "audio/mpeg-l2" to "MP2",
        "audio/true-hd" to "TrueHD",
        "audio/vnd.dts" to "DTS",
        "audio/vnd.dts.hd" to "DTS-HD",
        "audio/vnd.dts.uhd" to "DTS:X",
        "audio/alac" to "ALAC",
        "audio/raw" to "PCM",
        "audio/pcm" to "PCM",
        "audio/amr-nb" to "AMR-NB",
        "audio/amr-wb" to "AMR-WB",
    )

    private val DETAIL_FAMILIES: Map<String, String> = mapOf(
        "avc1" to "AVC (H.264)",
        "avc3" to "AVC (H.264)",
        "hvc1" to "HEVC (H.265)",
        "hev1" to "HEVC (H.265)",
        "hev2" to "H.266",
        "lhv1" to "H.266",
        "vvc1" to "H.266",
        "vp08" to "VP8",
        "vp09" to "VP9",
        "av01" to "AV1",

        "dvhe" to "HEVC (H.265)",
        "dvh1" to "HEVC (H.265)",
        "mp4v" to "MPEG-4",
        "mp4a" to "AAC",
        "ac-3" to "AC-3",
        "ec-3" to "E-AC-3",
        "opus" to "Opus",
        "vorb" to "Vorbis",
        "flac" to "FLAC",
        "alac" to "ALAC",
        "dtse" to "DTS",
        "dtsc" to "DTS",
        "dtsh" to "DTS-HD",
        "dtsl" to "DTS-HD",
        "dtsx" to "DTS:X",
        "mp3" to "MP3",
        "sowt" to "PCM",
        "lpcm" to "PCM",
        "twos" to "PCM",
    )
}
