package dev.infusion.core.playback.api.testing

import dev.infusion.core.playback.api.AudioTrack
import dev.infusion.core.playback.api.PlaybackFailure
import dev.infusion.core.playback.api.SubtitleTrack
import dev.infusion.core.playback.api.TrackId
import dev.infusion.core.playback.api.VideoTrack

data class FakeScript(
    val durationMs: Long? = 60_000L,
    val isSeekable: Boolean = true,
    val isLive: Boolean = false,
    val videoTracks: List<VideoTrack> = DEFAULT_VIDEO_TRACKS,
    val audioTracks: List<AudioTrack> = DEFAULT_AUDIO_TRACKS,
    val subtitleTracks: List<SubtitleTrack> = DEFAULT_SUBTITLE_TRACKS,

    val deferPrepare: Boolean = false,

    val failOnPrepare: PlaybackFailure? = null,

    val failOnPlay: PlaybackFailure? = null,

    val failOnSeek: PlaybackFailure? = null,

    val emitDecoderObservations: Boolean = true,

    val emitDegradedObservation: Boolean = false,

    val dropFramesOnSeek: Int = 0,
) {
    companion object {
        val DEFAULT_VIDEO_TRACKS = listOf(
            VideoTrack(
                id = TrackId("video:0"),
                label = "Main",
                width = 1920,
                height = 1080,
                frameRate = 24f,
                hdr = false,
                codec = "video/avc",
            ),
        )
        val DEFAULT_AUDIO_TRACKS = listOf(
            AudioTrack(TrackId("audio:0"), label = "English", language = "eng", channelCount = 2, sampleRate = 48_000, codec = "audio/mp4a-latm"),
            AudioTrack(TrackId("audio:1"), label = "Español", language = "spa", channelCount = 2, sampleRate = 48_000, codec = "audio/mp4a-latm"),
        )
        val DEFAULT_SUBTITLE_TRACKS = listOf(
            SubtitleTrack(TrackId("subtitle:0"), label = "English", language = "eng", format = "text/ttml", isText = true),
            SubtitleTrack(TrackId("subtitle:1"), label = "Forced", language = "eng", format = "text/ttml", isForced = true, isText = true),
        )
    }
}
