package dev.infusion.source.jellyfin.playback

import org.json.JSONObject

internal object JellyfinPlaybackInfoBody {

    const val MAX_STREAMING_BITRATE: Int = 100 * 1024 * 1024

    private const val TICKS_PER_MILLISECOND: Long = 10_000L

    fun build(
        userId: String,
        mediaSourceId: String? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        enableDirectPlay: Boolean = true,
        enableDirectStream: Boolean = true,
        startPositionMs: Long? = null,
    ): JSONObject = JSONObject()
        .put("UserId", userId)
        .put("DeviceProfile", JellyfinDeviceProfile.build())
        .put(
            "StartTimeTicks",
            startPositionMs?.takeIf { it > 0L }?.let { it * TICKS_PER_MILLISECOND } ?: JSONObject.NULL,
        )
        .put("MaxAudioChannels", JSONObject.NULL)
        .put("AudioStreamIndex", audioStreamIndex ?: JSONObject.NULL)
        .put("SubtitleStreamIndex", subtitleStreamIndex ?: JSONObject.NULL)
        .put("MediaSourceId", mediaSourceId?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        .put("AlwaysBurnInSubtitleWhenTranscoding", false)
        .put("MaxStreamingBitrate", MAX_STREAMING_BITRATE)
        .put("EnableDirectPlay", enableDirectPlay)
        .put("EnableDirectStream", enableDirectStream)
        .put("EnableTranscoding", true)
        .put("AllowVideoStreamCopy", enableDirectStream)
        .put("AllowAudioStreamCopy", enableDirectStream)
        .put("AutoOpenLiveStream", true)
}
