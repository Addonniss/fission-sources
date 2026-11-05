package dev.infusion.source.jellyfin.playback

import org.json.JSONArray
import org.json.JSONObject

object JellyfinDeviceProfile {
    private const val VIDEO_CONTAINERS = "asf,dash,hls,m4v,mkv,mov,mp4,ogm,ogv,ts,vob,webm,wmv,xvid"

    private const val VIDEO_CODECS = "h264,hevc,mpeg,mpeg2video,vc1,vp8,vp9"
    private const val AUDIO_CODECS =
        "aac,aac_latm,ac3,alac,dca,dts,eac3,flac,mlp,mp2,mp3,opus,pcm_alaw,pcm_mulaw,pcm_s16le,pcm_s20le,pcm_s24le,truehd,vorbis"

    fun build(
        name: String = "AndroidTV-Default",
        maxStreamingBitrate: Int = 100 * 1024 * 1024,
        maxAudioChannels: Int = 8,
    ): JSONObject {
        val transcodingProfiles = JSONArray()
            .put(
                JSONObject()
                    .put("Container", "ts")
                    .put("Type", "Video")
                    .put("Context", "Streaming")
                    .put("Protocol", "hls")
                    .put("VideoCodec", "hevc,h264")
                    .put("AudioCodec", AUDIO_CODECS)
                    .put("CopyTimestamps", false)
                    .put("EnableSubtitlesInManifest", true),
            )
            .put(
                JSONObject()
                    .put("Container", "ts")
                    .put("Type", "Audio")
                    .put("Context", "Streaming")
                    .put("Protocol", "hls")
                    .put("AudioCodec", "aac"),
            )
        val directPlayProfiles = JSONArray()
            .put(
                JSONObject()
                    .put("Container", VIDEO_CONTAINERS)
                    .put("Type", "Video")
                    .put("VideoCodec", VIDEO_CODECS)
                    .put("AudioCodec", AUDIO_CODECS),
            )
            .put(JSONObject().put("Type", "Audio").put("AudioCodec", AUDIO_CODECS))
        val subtitleProfiles = JSONArray().apply {
            subtitle("vtt", "Embed", "Hls", "External")
            subtitle("webvtt", "Embed", "Hls", "External")
            subtitle("srt", "Embed", "External")
            subtitle("subrip", "Embed", "External")
            subtitle("ttml", "Embed", "External")
            subtitle("dvbsub", "Embed", "Encode")
            subtitle("dvdsub", "Embed", "Encode")
            subtitle("idx", "Embed", "Encode")
            subtitle("pgs", "Encode")
            subtitle("pgssub", "Encode")
            subtitle("ass", "Encode")
            subtitle("ssa", "Encode")
        }
        return JSONObject()
            .put("Name", name)
            .put("MaxStreamingBitrate", maxStreamingBitrate)
            .put("MaxStaticBitrate", maxStreamingBitrate)
            .put("MaxAudioChannels", maxAudioChannels)
            .put("TranscodingProfiles", transcodingProfiles)
            .put("DirectPlayProfiles", directPlayProfiles)
            .put("CodecProfiles", JSONArray())
            .put("SubtitleProfiles", subtitleProfiles)
    }

    private fun JSONArray.subtitle(format: String, vararg methods: String) {
        methods.forEach { method ->
            put(JSONObject().put("Format", format).put("Method", method))
        }
    }
}
