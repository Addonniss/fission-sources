package dev.infusion.core.playback.api

@JvmInline
value class TrackId(val value: String) {
    init {
        require(value.isNotBlank()) { "TrackId must not be blank" }
    }
}

data class VideoTrack(
    val id: TrackId,
    val label: String? = null,
    val language: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,

    val hdr: Boolean? = null,

    val colourTransfer: VideoColourTransfer? = null,
    val codec: String? = null,

    val codecDetail: String? = null,

    val rotationDegrees: Int? = null,

    val pixelWidthHeightRatio: Float? = null,

    val bitrateBitsPerSecond: Int? = null,
)

enum class TrackRole {

    COMMENTARY,

    DESCRIPTIVE,

    CAPTION,

    FORCED,

    DUB,

    ALTERNATE,
}

data class AudioTrack(
    val id: TrackId,
    val label: String? = null,
    val language: String? = null,
    val channelCount: Int? = null,
    val sampleRate: Int? = null,
    val codec: String? = null,

    val codecDetail: String? = null,

    val roles: Set<TrackRole> = emptySet(),

    val supported: Boolean? = null,

    val isDefault: Boolean? = null,

    val isSelected: Boolean? = null,
)

enum class SubtitleProvenance {

    EMBEDDED,

    SIDECAR,
}

data class SidecarSubtitleEvidence(

    val language: String? = null,

    val forced: Boolean? = null,

    val label: String? = null,

    val source: String? = null,
)

data class SubtitleTrack(
    val id: TrackId,
    val label: String? = null,
    val language: String? = null,
    val format: String? = null,

    val isForced: Boolean? = null,

    val isText: Boolean? = null,

    val roles: Set<TrackRole> = emptySet(),

    val supported: Boolean? = null,

    val isDefault: Boolean? = null,

    val isSelected: Boolean? = null,

    val provenance: SubtitleProvenance? = null,

    val isSdh: Boolean? = null,

    val sourceName: String? = null,
)

data class TrackInventory(
    val videoTracks: List<VideoTrack> = emptyList(),
    val audioTracks: List<AudioTrack> = emptyList(),
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
) {
    val isEmpty: Boolean
        get() = videoTracks.isEmpty() && audioTracks.isEmpty() && subtitleTracks.isEmpty()
}

enum class SubtitleMode {

    AUTO,

    OFF,

    SELECTED,
}

data class TrackSelection(
    val videoTrackId: TrackId? = null,
    val audioTrackId: TrackId? = null,
    val subtitleTrackId: TrackId? = null,
    val subtitleMode: SubtitleMode = SubtitleMode.AUTO,
) {
    companion object {
        val DEFAULT = TrackSelection()
    }
}
