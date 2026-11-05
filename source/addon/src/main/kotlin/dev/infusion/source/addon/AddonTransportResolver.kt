package dev.infusion.source.addon

import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.playback.api.SessionLineage
import dev.infusion.core.playback.api.TransportRequest

object AddonTransportResolver {

    fun requestFor(candidate: PlayableCandidate, lineage: SessionLineage): TransportRequest {
        val mediaUrl = candidate.transport.value
        if (!mediaUrl.startsWith("http://") && !mediaUrl.startsWith("https://")) {
            throw IllegalArgumentException("add-on transport descriptor is not a validated http(s) URL")
        }
        val subtitles = candidate.embeddedSubtitles
        return TransportRequest(
            requestId = lineage.requestId,
            uri = mediaUrl,
            mimeType = null,
            headers = emptyMap(),
            expiresAtEpochMs = null,
            sidecarUris = subtitles.map { it.url },

            sidecarMimeTypes = subtitles.map {
                dev.infusion.core.playback.api.SubtitleMimeAllowlist.normalize(it.format)
            },

            sidecarLanguages = subtitles.map { it.language },
            sidecarForced = subtitles.map { null },
            sidecarLabels = subtitles.map { null },

            sidecarSources = subtitles.map { it.sourceName },
        )
    }
}
