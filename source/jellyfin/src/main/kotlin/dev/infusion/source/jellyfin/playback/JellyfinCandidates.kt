package dev.infusion.source.jellyfin.playback

import dev.infusion.core.domain.candidate.CandidateFamily
import dev.infusion.core.model.CandidateFacts
import dev.infusion.core.model.CandidateId
import dev.infusion.core.model.CapabilityAssessment
import dev.infusion.core.model.CapabilityState
import dev.infusion.core.model.ExpectedMode
import dev.infusion.core.model.FactConfidence
import dev.infusion.core.model.NativeItemId
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.Reachability
import dev.infusion.core.model.SourceId
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TransportDescriptor
import dev.infusion.source.jellyfin.wire.MediaSource
import dev.infusion.source.jellyfin.wire.MediaStream
import java.time.Instant
import java.util.UUID

class JellyfinCandidateFactory(
    private val sourceId: SourceId,
    private val titleId: TitleId,
    private val nativeItemId: NativeItemId,
    private val serverVersion: String,
    private val now: () -> Instant = Instant::now,
) {

    fun candidates(
        mediaSources: List<MediaSource>,
        playSessionId: String?,
        editionId: dev.infusion.core.model.EditionId? = null,
        resumePositionMs: Long? = null,

        serverStartOffsetMs: Long? = null,
    ): List<PlayableCandidate> {
        val out = mutableListOf<PlayableCandidate>()
        for (source in mediaSources) {
            val selectedMode = when {
                source.supportsDirectPlay == true -> ExpectedMode.DIRECT
                source.supportsDirectStream == true -> ExpectedMode.REMUX
                source.supportsTranscoding == true -> ExpectedMode.TRANSCODE
                else -> null
            }
            if (selectedMode != null) {
                out += candidate(source, playSessionId, selectedMode, editionId, resumePositionMs, serverStartOffsetMs)
            }
        }
        return out
    }

    private fun candidate(
        source: MediaSource,
        playSessionId: String?,
        mode: ExpectedMode,
        editionId: dev.infusion.core.model.EditionId?,
        resumePositionMs: Long?,
        serverStartOffsetMs: Long?,
    ): PlayableCandidate {
        val nativeId = nativeItemId.nativeId
        val mediaSourceId = source.id ?: ""
        val familyId = CandidateFamily.derive(nativeItemId, versionId = mediaSourceId.ifBlank { null })
        val facts = JellyfinTrackMapper.factsFrom(source)
        val candidateId = CandidateId(UUID.nameUUIDFromBytes("$sourceId:$nativeId:$mediaSourceId:$mode".toByteArray()))
        val route = when (mode) {
            ExpectedMode.DIRECT -> "direct"
            ExpectedMode.REMUX -> "remux"
            ExpectedMode.TRANSCODE -> "transcode"
            else -> "transcode"
        }

        val serverRoute = when (mode) {
            ExpectedMode.TRANSCODE -> source.transcodingUrl
            ExpectedMode.REMUX -> source.transcodingUrl
            else -> null
        }
        val audioStreamIndex = source.mediaStreams.firstOrNull { it.type == "Audio" && it.isDefault == true }?.index
            ?: source.mediaStreams.firstOrNull { it.type == "Audio" }?.index
        val subtitleStreamIndex = source.mediaStreams.firstOrNull { it.type == "Subtitle" && it.isDefault == true }?.index

        val externalSubtitleStreams = JellyfinTrackMapper.externalSubtitleStreams(source)
        val externalSubtitleUrls = externalSubtitleStreams
            .mapNotNull { stream ->
                stream.deliveryUrl?.takeIf { it.isNotBlank() }
                    ?: JellyfinTrackMapper.externalSubtitleRoute(nativeId, mediaSourceId, stream)
            }

        val externalSubtitleStreamsWithRoutes = externalSubtitleStreams
            .filter { stream ->
                val url = stream.deliveryUrl?.takeIf { it.isNotBlank() }
                    ?: JellyfinTrackMapper.externalSubtitleRoute(nativeId, mediaSourceId, stream)
                url != null
            }
        return PlayableCandidate(
            candidateId = candidateId,
            titleId = titleId,
            editionId = editionId,
            sourceId = sourceId,
            nativeItemId = nativeItemId,
            familyId = familyId,
            providerName = "Jellyfin",
            originalLabel = source.name ?: "${nativeId} (${source.container ?: "unknown"})",
            transport = JellyfinTransportCodec.encode(
                JellyfinTransportCodec.Payload(
                    itemId = nativeId,
                    mediaSourceId = mediaSourceId,
                    route = route,
                    playSessionId = playSessionId,
                    serverRoute = serverRoute,
                    externalSubtitleUrls = externalSubtitleUrls,
                    externalSubtitleLanguages = externalSubtitleStreamsWithRoutes.map { it.language },

                    externalSubtitleForced = externalSubtitleStreamsWithRoutes.map { it.isForced },
                    externalSubtitleLabels = externalSubtitleStreamsWithRoutes.map { it.displayTitle },
                    playMethod = when (mode) {
                        ExpectedMode.DIRECT -> "DirectPlay"
                        ExpectedMode.REMUX -> "DirectStream"
                        else -> "Transcode"
                    },
                    liveStreamId = source.liveStreamId,
                    audioStreamIndex = audioStreamIndex,
                    subtitleStreamIndex = subtitleStreamIndex,
                    eTag = source.eTag,
                    isRemote = source.isRemote,
                    remotePath = source.path,
                    resumePositionMs = resumePositionMs
                        ?: source.startPositionTicks?.div(10_000L)?.takeIf { it > 0L },

                    serverStartOffsetMs = serverStartOffsetMs?.takeIf {
                        it > 0L && mode != ExpectedMode.DIRECT
                    },
                ),
            ),
            facts = facts,
            expectedMode = mode,

            capability = CapabilityAssessment(
                state = CapabilityState.UNKNOWN,
                reasons = listOf(
                    "server-negotiated ${mode.name.lowercase()} for media source $mediaSourceId; " +
                        "physical device decode/render not yet assessed",
                ),
                assessedAt = now(),
            ),
            expiresAt = null,
            reachability = Reachability.REACHABLE,
            rankExplanation = "server-negotiated ${mode.name.lowercase()}",
            createdAt = now(),

            durationMs = source.runTimeTicks?.let { it / 10_000L }?.takeIf { it > 0L },
        )
    }
}

object JellyfinTrackMapper {

    fun factsFrom(source: MediaSource): CandidateFacts {
        val video = source.mediaStreams.firstOrNull { it.type == "Video" }
        val audio = source.mediaStreams.firstOrNull { it.type == "Audio" }
        val subtitles = source.mediaStreams.filter { it.type == "Subtitle" }
        return CandidateFacts(
            container = source.container,
            videoCodec = video?.codec,
            videoProfile = null,
            resolution = video?.let { "${it.width}x${it.height}" }.takeIf { it != "nullxnull" },
            dynamicRange = null,

            frameRate = video?.realFrameRate ?: video?.averageFrameRate,
            audioCodec = audio?.codec,
            audioChannels = audio?.channels,
            subtitleFormats = subtitles.mapNotNull { it.codec }.toSet(),
            language = audio?.language,
            sizeBytes = null,
            bitrateBps = source.bitrate,
            confidence = FactConfidence.CONFIRMED,
            provenance = null,
        )
    }

    fun isExternalSubtitle(stream: MediaStream): Boolean =
        stream.type == "Subtitle" &&
            (stream.isExternal == true || stream.deliveryMethod == "External")

    fun externalSubtitleRoute(
        itemId: String,
        mediaSourceId: String,
        stream: MediaStream,
    ): String? {
        if (!isExternalSubtitle(stream)) return null
        val index = stream.index ?: return null
        val codec = stream.codec?.takeIf { it.isNotBlank() } ?: return null
        if (itemId.isBlank() || mediaSourceId.isBlank()) return null
        return "/Videos/$itemId/$mediaSourceId/Subtitles/$index/stream.$codec"
    }

    fun externalSubtitleStreams(source: MediaSource): List<MediaStream> =
        source.mediaStreams.filter(::isExternalSubtitle)
}
