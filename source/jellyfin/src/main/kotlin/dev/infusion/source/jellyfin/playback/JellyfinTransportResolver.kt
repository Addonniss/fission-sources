package dev.infusion.source.jellyfin.playback

import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.TransportDescriptor
import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.MediaBrowserAuthorization
import dev.infusion.core.network.NetworkPolicyException
import dev.infusion.core.network.UrlParser
import dev.infusion.core.playback.api.SessionLineage
import dev.infusion.core.playback.api.TransportRequest
import dev.infusion.source.jellyfin.auth.JellyfinAuthenticator
import dev.infusion.source.jellyfin.auth.JellyfinSession
import java.nio.charset.StandardCharsets
import java.util.Base64

object JellyfinTransportCodec {

    private const val PREFIX = "jf1:"

    data class Payload(
        val itemId: String,
        val mediaSourceId: String,
        val route: String,
        val playSessionId: String? = null,

        val serverRoute: String? = null,

        val externalSubtitleUrls: List<String> = emptyList(),

        val externalSubtitleLanguages: List<String?> = emptyList(),

        val externalSubtitleForced: List<Boolean?> = emptyList(),

        val externalSubtitleLabels: List<String?> = emptyList(),
        val playMethod: String? = null,
        val liveStreamId: String? = null,
        val audioStreamIndex: Int? = null,
        val subtitleStreamIndex: Int? = null,
        val eTag: String? = null,
        val isRemote: Boolean = false,
        val remotePath: String? = null,
        val resumePositionMs: Long? = null,

        val serverStartOffsetMs: Long? = null,
    )

    fun encode(payload: Payload): TransportDescriptor {
        val raw = listOf(
            payload.itemId,
            payload.mediaSourceId,
            payload.route,
            payload.playSessionId ?: "",
            payload.serverRoute ?: "",
            payload.externalSubtitleUrls.joinToString(","),
            payload.playMethod ?: "",
            payload.liveStreamId ?: "",
            payload.audioStreamIndex?.toString() ?: "",
            payload.subtitleStreamIndex?.toString() ?: "",
            payload.eTag ?: "",
            payload.isRemote.toString(),
            payload.remotePath ?: "",
            payload.resumePositionMs?.toString() ?: "",

            payload.externalSubtitleLanguages.joinToString(",") { it ?: "" },
            payload.externalSubtitleForced.joinToString(",") { it?.toString() ?: "" },
            payload.externalSubtitleLabels.joinToString("\u0001") { it ?: "" },

            payload.serverStartOffsetMs?.toString() ?: "",
        ).joinToString("\u0000")
        return TransportDescriptor(PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8)))
    }

    fun decode(descriptor: TransportDescriptor): Payload? {
        val value = descriptor.value
        if (!value.startsWith(PREFIX)) return null
        val bytes = try {
            Base64.getUrlDecoder().decode(value.removePrefix(PREFIX))
        } catch (_: IllegalArgumentException) {
            return null
        }
        val parts = String(bytes, StandardCharsets.UTF_8).split("\u0000")
        if (parts.size != 6 && parts.size != 10 && parts.size != 14 && parts.size != 17 && parts.size != 18) return null
        val itemId = parts[0]
        val mediaSourceId = parts[1]
        val route = parts[2]
        val playSessionId = parts[3]
        val serverRoute = parts[4]
        val externalSubtitles = parts[5]
        if (itemId.isBlank() || route !in setOf("direct", "remux", "transcode")) return null
        return Payload(
            itemId = itemId,
            mediaSourceId = mediaSourceId,
            route = route,
            playSessionId = playSessionId.takeIf { it.isNotBlank() },
            serverRoute = serverRoute.takeIf { it.isNotBlank() },
            externalSubtitleUrls = externalSubtitles.split(",").filter { it.isNotBlank() },
            externalSubtitleLanguages = decodeAligned(parts.getOrNull(14), separator = ","),
            externalSubtitleForced = parts.getOrNull(15)
                ?.split(",")
                ?.let { values -> if (values.size == 1 && values[0].isBlank()) emptyList() else values.map { it.toBooleanStrictOrNull() } }
                ?: emptyList(),
            externalSubtitleLabels = decodeAligned(parts.getOrNull(16), separator = "\u0001"),
            playMethod = parts.getOrNull(6)?.takeIf { it.isNotBlank() },
            liveStreamId = parts.getOrNull(7)?.takeIf { it.isNotBlank() },
            audioStreamIndex = parts.getOrNull(8)?.toIntOrNull(),
            subtitleStreamIndex = parts.getOrNull(9)?.toIntOrNull(),
            eTag = parts.getOrNull(10)?.takeIf { it.isNotBlank() },
            isRemote = parts.getOrNull(11)?.toBooleanStrictOrNull() ?: false,
            remotePath = parts.getOrNull(12)?.takeIf { it.isNotBlank() },
            resumePositionMs = parts.getOrNull(13)?.toLongOrNull(),
            serverStartOffsetMs = parts.getOrNull(17)?.toLongOrNull(),
        )
    }

    private fun decodeAligned(part: String?, separator: String): List<String?> {
        if (part == null || part.isEmpty()) return emptyList()
        return part.split(separator).map { value -> value.takeIf { it.isNotBlank() } }
    }
}

class JellyfinTransportResolver(
    private val origin: CanonicalUrl,
    private val authenticator: JellyfinAuthenticator,
    private val sessionProvider: () -> JellyfinSession?,
    private val embyAuthHeader: () -> String = { DEFAULT_EMBY_AUTH_HEADER },
) {

    companion object {

        private const val DEFAULT_EMBY_AUTH_HEADER =
            "MediaBrowser Client=\"Infusion\", Device=\"Android\", DeviceId=\"infusion\", Version=\"0.1.0\""
    }

    fun requestFor(candidate: PlayableCandidate, lineage: SessionLineage): TransportRequest =
        requestFor(
            payload = JellyfinTransportCodec.decode(candidate.transport)
                ?: throw IllegalArgumentException("foreign transport descriptor"),
            requestId = lineage.requestId,
            container = candidate.facts.container,
        )

    fun requestFor(
        payload: JellyfinTransportCodec.Payload,
        requestId: String,
        container: String? = null,
    ): TransportRequest {
        val session = sessionProvider()
            ?: throw IllegalStateException("jellyfin session unavailable")
        val token = authenticator.token(session)
            ?: throw IllegalStateException("jellyfin token missing")

        val remoteDirect = payload.route == "direct" && payload.isRemote && !payload.remotePath.isNullOrBlank()
        val uri = when {
            remoteDirect -> remoteUri(payload.remotePath)
            else -> when (payload.route) {
            "transcode" -> serverRouteUri(payload, fallback = { standardStreamUri(payload, static = false) })
            "remux" -> serverRouteUri(payload, fallback = { directStreamUri(payload) })
            else -> directStreamUri(payload)
            }
        }

        val sidecars = payload.externalSubtitleUrls.map { url ->
            val resolved = if (url.startsWith("/")) origin.toString().trimEnd('/') + url else url
            val parsed = try {
                UrlParser.parse(resolved)
            } catch (e: NetworkPolicyException) {
                throw IllegalArgumentException("external subtitle route rejected by policy: ${e.reason.name}")
            }
            if (!parsed.sameOrigin(origin)) {
                throw IllegalArgumentException("external subtitle route leaves the configured origin")
            }
            parsed.toString()
        }
        return TransportRequest(
            requestId = requestId,
            uri = uri,
            mimeType = containerMimeType(container),
            headers = if (remoteDirect) emptyMap() else MediaBrowserAuthorization.headers(
                authenticatorEmbyHeader(),
                token,
            ),
            expiresAtEpochMs = null,
            sidecarUris = sidecars,

            sidecarLanguages = payload.externalSubtitleLanguages,
            sidecarForced = payload.externalSubtitleForced,
            sidecarLabels = payload.externalSubtitleLabels,
            startPositionMs = payload.resumePositionMs ?: 0L,

            contentOffsetMs = payload.serverStartOffsetMs?.coerceAtLeast(0L) ?: 0L,
        )
    }

    private fun directStreamUri(payload: JellyfinTransportCodec.Payload): String {
        val base = origin.toString().trimEnd('/')
        val tag = payload.eTag?.let { "&tag=${urlEncode(it)}" } ?: ""
        val playSession = payload.playSessionId?.let { "&playSessionId=${urlEncode(it)}" } ?: ""
        return "$base/Videos/${payload.itemId}/stream?static=true&mediaSourceId=${urlEncode(payload.mediaSourceId)}$tag$playSession"
    }

    private fun remoteUri(path: String): String = try {
        UrlParser.parse(path).toString()
    } catch (e: NetworkPolicyException) {
        throw IllegalArgumentException("remote media path rejected by policy: ${e.reason.name}")
    }

    private fun containerMimeType(container: String?): String? = when (container?.lowercase()) {
        "hls", "m3u8" -> "application/x-mpegURL"
        "dash", "mpd" -> "application/dash+xml"
        else -> null
    }

    private fun standardStreamUri(payload: JellyfinTransportCodec.Payload, static: Boolean): String {
        val base = origin.toString().trimEnd('/')
        val staticParam = if (static) "static=true&" else ""
        return "$base/Videos/${payload.itemId}/stream?${staticParam}mediaSourceId=${urlEncode(payload.mediaSourceId)}"
    }

    private fun serverRouteUri(
        payload: JellyfinTransportCodec.Payload,
        fallback: () -> String,
    ): String {
        val serverRoute = payload.serverRoute ?: return fallback()
        val resolved = if (serverRoute.startsWith("/")) {
            origin.toString().trimEnd('/') + serverRoute
        } else {
            serverRoute
        }
        val parsed = try {
            UrlParser.parse(resolved)
        } catch (e: NetworkPolicyException) {
            throw IllegalArgumentException("server route rejected by policy: ${e.reason.name}")
        }

        if (!parsed.sameOrigin(origin)) {
            throw IllegalArgumentException("server route leaves the configured origin")
        }
        return parsed.toString()
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun authenticatorEmbyHeader(): String = embyAuthHeader()
}
