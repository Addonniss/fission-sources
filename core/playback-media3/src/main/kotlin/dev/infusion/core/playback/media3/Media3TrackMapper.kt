package dev.infusion.core.playback.media3

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import dev.infusion.core.playback.api.AudioLanguageTag
import dev.infusion.core.playback.api.AudioTrack
import dev.infusion.core.playback.api.SidecarSubtitleEvidence
import dev.infusion.core.playback.api.SubtitleMode
import dev.infusion.core.playback.api.SubtitleProvenance
import dev.infusion.core.playback.api.SubtitleTitleEvidence
import dev.infusion.core.playback.api.SubtitleTrack
import dev.infusion.core.playback.api.TrackId
import dev.infusion.core.playback.api.TrackInventory
import dev.infusion.core.playback.api.TrackRole
import dev.infusion.core.playback.api.TrackSelection
import dev.infusion.core.playback.api.VideoTrack

object Media3TrackMapper {

    internal fun trackRoles(format: Format): Set<TrackRole> {
        val flags = format.roleFlags
        return buildSet {
            if (flags and C.ROLE_FLAG_COMMENTARY != 0) add(TrackRole.COMMENTARY)
            if (flags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0) add(TrackRole.DESCRIPTIVE)
            if (flags and C.ROLE_FLAG_CAPTION != 0) add(TrackRole.CAPTION)
            if (flags and C.ROLE_FLAG_DUB != 0) add(TrackRole.DUB)
            if (flags and C.ROLE_FLAG_ALTERNATE != 0) add(TrackRole.ALTERNATE)
            if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) add(TrackRole.FORCED)
        }
    }

    const val SIDECAR_ID_PREFIX: String = "infusion-sidecar:"

    fun sidecarId(index: Int): String = "$SIDECAR_ID_PREFIX$index"

    internal fun sidecarIdOf(formatId: String?): String? {
        val id = formatId ?: return null
        val marker = id.lastIndexOf(SIDECAR_ID_PREFIX)
        if (marker < 0) return null
        val mergePrefix = id.substring(0, marker)
        val mergeSegments = if (mergePrefix.isEmpty()) {
            emptyList()
        } else {
            if (!mergePrefix.endsWith(":")) return null
            mergePrefix.dropLast(1).split(':')
        }
        if (mergeSegments.any { segment -> segment.isEmpty() || segment.any { !it.isDigit() } }) return null
        val index = id.substring(marker + SIDECAR_ID_PREFIX.length)
        if (index.isEmpty() || index.any { !it.isDigit() }) return null
        return SIDECAR_ID_PREFIX + index
    }

    fun withInfusionTextBase(base: TrackSelectionParameters): TrackSelectionParameters =
        base.buildUpon()
            .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()

    fun infusionTextBaseParameters(context: Context): TrackSelectionParameters =
        withInfusionTextBase(TrackSelectionParameters.getDefaults(context))

    private const val DISPLAY_MATCHING_TAG = "InfusionDispMatch"

    class TrackIndex(
        val video: Map<TrackId, Pair<TrackGroup, Int>>,
        val audio: Map<TrackId, Pair<TrackGroup, Int>>,
        val subtitle: Map<TrackId, Pair<TrackGroup, Int>>,
    ) {
        fun isEmpty(): Boolean = video.isEmpty() && audio.isEmpty() && subtitle.isEmpty()
    }

    fun mapTracks(
        tracks: Tracks,
        sidecarEvidence: Map<String, SidecarSubtitleEvidence> = emptyMap(),
    ): Pair<TrackInventory, TrackIndex> {
        val videoTracks = mutableListOf<VideoTrack>()
        val audioTracks = mutableListOf<AudioTrack>()
        val subtitleTracks = mutableListOf<SubtitleTrack>()
        val videoIndex = mutableMapOf<TrackId, Pair<TrackGroup, Int>>()
        val audioIndex = mutableMapOf<TrackId, Pair<TrackGroup, Int>>()
        val subtitleIndex = mutableMapOf<TrackId, Pair<TrackGroup, Int>>()

        tracks.groups.forEachIndexed { groupIndex, group ->
            val mediaTrackGroup = group.getMediaTrackGroup()
            val type = mediaTrackGroup.type
            for (index in 0 until group.length) {
                val format: Format = group.getTrackFormat(index)

                val trackId = TrackId("${typeName(type)}:$groupIndex:$index")
                when (type) {
                    C.TRACK_TYPE_VIDEO -> {

                        if (android.util.Log.isLoggable(DISPLAY_MATCHING_TAG, android.util.Log.VERBOSE)) {
                            android.util.Log.v(
                                DISPLAY_MATCHING_TAG,
                                "media3.format video group=$groupIndex index=$index raw=$format",
                            )
                        }
                        videoTracks += VideoTrack(
                            id = trackId,
                            label = format.label ?: format.id,
                            language = format.language,
                            width = format.width.takeIf { it != Format.NO_VALUE },
                            height = format.height.takeIf { it != Format.NO_VALUE },
                            frameRate = format.frameRate.takeIf { it > 0f },
                            hdr = ColorInfo.isTransferHdr(format.colorInfo),

                            colourTransfer = format.colorInfo?.colorTransfer
                                ?.let(::neutralVideoColourTransfer),
                            codec = format.sampleMimeType,

                            codecDetail = format.codecs,

                            rotationDegrees = format.rotationDegrees.takeIf { it != 0 },
                            pixelWidthHeightRatio = format.pixelWidthHeightRatio
                                .takeIf { it > 0f && it != 1f },

                            bitrateBitsPerSecond = format.bitrate
                                .takeIf { it != Format.NO_VALUE && it > 0 },
                        )
                        videoIndex[trackId] = mediaTrackGroup to index
                    }
                    C.TRACK_TYPE_AUDIO -> {
                        audioTracks += AudioTrack(
                            id = trackId,
                            label = format.label ?: format.id,
                            language = format.language,
                            channelCount = format.channelCount.takeIf { it != Format.NO_VALUE },
                            sampleRate = format.sampleRate.takeIf { it != Format.NO_VALUE },
                            codec = format.sampleMimeType,

                            codecDetail = format.codecs,

                            roles = trackRoles(format),

                            supported = group.isTrackSupported(index),
                            isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,

                            isSelected = group.isTrackSelected(index),
                        )
                        audioIndex[trackId] = mediaTrackGroup to index
                    }
                    C.TRACK_TYPE_TEXT -> {

                        val sidecarKey = sidecarIdOf(format.id)
                        val sidecar = if (sidecarKey != null) {
                            sidecarEvidence[sidecarKey] ?: SidecarSubtitleEvidence()
                        } else {
                            null
                        }
                        val provenance = if (sidecar != null) SubtitleProvenance.SIDECAR else SubtitleProvenance.EMBEDDED
                        val declaredLanguage =
                            if (sidecar != null) sidecar.language else format.language
                        val captionRole = format.roleFlags and C.ROLE_FLAG_CAPTION != 0

                        if (android.util.Log.isLoggable(DISPLAY_MATCHING_TAG, android.util.Log.VERBOSE)) {
                            android.util.Log.v(
                                DISPLAY_MATCHING_TAG,
                                "media3.format text group=$groupIndex index=$index raw=$format",
                            )
                        }
                        subtitleTracks += SubtitleTrack(
                            id = trackId,

                            label = if (sidecar != null) sidecar.label else format.label ?: format.id,

                            language = declaredLanguage?.takeIf { AudioLanguageTag.canonicalize(it) != null },
                            format = format.sampleMimeType,
                            isForced = if (sidecar != null) {

                                sidecar.forced
                            } else {
                                format.selectionFlags and C.SELECTION_FLAG_FORCED != 0
                            },
                            isText = format.sampleMimeType != MimeTypes.APPLICATION_PGS &&
                                format.sampleMimeType != MimeTypes.APPLICATION_VOBSUB,

                            roles = trackRoles(format),

                            supported = group.isTrackSupported(index),
                            isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                            isSelected = group.isTrackSelected(index),
                            provenance = provenance,

                            sourceName = sidecar?.source,

                            isSdh = if (captionRole ||
                                (provenance == SubtitleProvenance.EMBEDDED && SubtitleTitleEvidence.isSdhTitle(format.label))
                            ) {
                                true
                            } else {
                                null
                            },
                        )
                        subtitleIndex[trackId] = mediaTrackGroup to index
                    }
                    else -> Unit
                }
            }
        }

        return TrackInventory(videoTracks, audioTracks, subtitleTracks) to
            TrackIndex(videoIndex, audioIndex, subtitleIndex)
    }

    fun buildSelectionParameters(
        context: Context,
        base: TrackSelectionParameters,
        selection: TrackSelection,
        index: TrackIndex,
    ): TrackSelectionParameters {
        val builder = base.buildUpon()

        selection.videoTrackId?.let { id ->
            val (group, trackIndex) = index.video[id]
                ?: throw IllegalArgumentException("unknown video track $id")
            builder.setOverrideForType(TrackSelectionOverride(group, trackIndex))
        }
        selection.audioTrackId?.let { id ->
            val (group, trackIndex) = index.audio[id]
                ?: throw IllegalArgumentException("unknown audio track $id")
            builder.setOverrideForType(TrackSelectionOverride(group, trackIndex))
        }
        when (selection.subtitleMode) {
            SubtitleMode.OFF -> builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            SubtitleMode.SELECTED -> {
                val id = selection.subtitleTrackId
                    ?: throw IllegalArgumentException("subtitleMode=SELECTED requires a subtitleTrackId")
                val (group, trackIndex) = index.subtitle[id]
                    ?: throw IllegalArgumentException("unknown subtitle track $id")
                builder.setOverrideForType(TrackSelectionOverride(group, trackIndex))
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            }
            SubtitleMode.AUTO -> {
                builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            }
        }
        return builder.build()
    }

    private fun typeName(type: Int): String = when (type) {
        C.TRACK_TYPE_VIDEO -> "video"
        C.TRACK_TYPE_AUDIO -> "audio"
        C.TRACK_TYPE_TEXT -> "subtitle"
        else -> "track"
    }
}
