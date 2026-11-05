package dev.infusion.source.stremio.wire

import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.DiagnosticReason
import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.policy.StremioId
import dev.infusion.source.stremio.policy.StremioLocatorPolicy
import dev.infusion.source.stremio.wire.WireJson.requiredString

data class StremioVideo(
    val raw: JsonValue.Obj,
    val id: String,
    val title: String?,
    val name: String?,
    val released: String?,
    val season: Int?,
    val episode: Int?,
    val overview: String?,
    val thumbnail: String?,
) {

    val label: String?
        get() = title?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }
}

data class StremioMeta(
    val raw: JsonValue.Obj,
    val id: String,
    val type: StremioContentType,
    val name: String,
    val description: String?,
    val genres: List<String>,
    val poster: String?,
    val background: String?,
    val logo: String?,
    val releaseInfo: String?,
    val imdbRating: String?,
    val videos: List<StremioVideo>,
    val links: List<StremioMetaLink>,
) {
    companion object {
        const val MAX_VIDEOS = 512
        const val MAX_LINKS = 128
    }
}

data class StremioMetaLink(
    val raw: JsonValue.Obj,
    val name: String,
    val category: String,
    val url: CanonicalUrl?,
    val deepLinkRef: String?,
)

data class StremioMetaResponse(
    val raw: JsonValue.Obj,
    val meta: StremioMeta,
)

object MetaParser {

    fun parse(
        root: JsonValue.Obj,
        requestedType: StremioContentType,
        requestedId: String,
        locatorPolicy: StremioLocatorPolicy,
    ): StremioMetaResponse {
        val metaObj = WireJson.requiredObj(root, "meta")
        val id = requiredString(metaObj, "id")
        StremioId.validateOrThrow(id)
        val type = StremioContentType.parse(requiredString(metaObj, "type"))
            ?: throw WireException(DiagnosticReason.SCHEMA_INVALID, "unsupported meta type")

        if (id != requestedId) {
            throw WireException(DiagnosticReason.SCHEMA_INVALID, "meta id does not match request")
        }
        if (type != requestedType) {
            throw WireException(DiagnosticReason.SCHEMA_INVALID, "meta type does not match request")
        }
        val name = requiredString(metaObj, "name")
        StremioId.validateBoundedString(name, StremioId.MAX_NAME_BYTES, "meta name")

        val videos = parseVideos(metaObj)
        val links = parseLinks(metaObj, locatorPolicy)
        return StremioMetaResponse(
            raw = root,
            meta = StremioMeta(
                raw = metaObj,
                id = id,
                type = type,
                name = name,
                description = optionalString(metaObj, "description", 8192, freeText = true),
                genres = WireJson.stringArray(metaObj, "genres", maxElements = 64, maxElementBytes = 256) ?: emptyList(),
                poster = optionalString(metaObj, "poster", 4096),
                background = optionalString(metaObj, "background", 4096),
                logo = optionalString(metaObj, "logo", 4096),
                releaseInfo = optionalString(metaObj, "releaseInfo", 64),
                imdbRating = optionalString(metaObj, "imdbRating", 16),
                videos = videos,
                links = links,
            ),
        )
    }

    private fun optionalString(o: JsonValue.Obj, key: String, maxBytes: Int, freeText: Boolean = false): String? {
        val value = WireJson.string(o, key)?.takeIf { it.isNotEmpty() } ?: return null
        StremioId.validateBoundedString(value, maxBytes, key, allowLineBreaks = freeText)
        return value
    }

    private fun parseVideos(o: JsonValue.Obj): List<StremioVideo> {
        val entries = WireJson.array(o, "videos") ?: return emptyList()
        if (entries.size > StremioMeta.MAX_VIDEOS) {
            throw WireException(DiagnosticReason.LIMIT_EXCEEDED, "meta has too many videos")
        }
        val seen = HashSet<String>(entries.size)
        return entries.map { v ->
            val vo = v as? JsonValue.Obj
                ?: throw WireException(DiagnosticReason.SCHEMA_INVALID, "video must be an object")
            val id = requiredString(vo, "id")
            StremioId.validateOrThrow(id)
            if (!seen.add(id)) {
                throw WireException(DiagnosticReason.SCHEMA_INVALID, "duplicate video id in meta")
            }

            val title = optionalString(vo, "title", StremioId.MAX_NAME_BYTES)
            val name = optionalString(vo, "name", StremioId.MAX_NAME_BYTES)
            val released = optionalString(vo, "released", 64)
            val season = WireJson.intOrNull(vo, "season", max = 100_000)?.toInt()
            val episode = WireJson.intOrNull(vo, "episode", max = 100_000)?.toInt()
            val overview = optionalString(vo, "overview", 8192, freeText = true)

            val thumbnail = optionalString(vo, "thumbnail", 4096)
            StremioVideo(
                raw = vo,
                id = id,
                title = title,
                name = name,
                released = released,
                season = season,
                episode = episode,
                overview = overview,
                thumbnail = thumbnail,
            )
        }
    }

    private fun parseLinks(o: JsonValue.Obj, locatorPolicy: StremioLocatorPolicy): List<StremioMetaLink> {
        val entries = WireJson.array(o, "links") ?: return emptyList()
        if (entries.size > StremioMeta.MAX_LINKS) {
            throw WireException(DiagnosticReason.LIMIT_EXCEEDED, "meta has too many links")
        }
        return entries.map { v ->
            val lo = v as? JsonValue.Obj
                ?: throw WireException(DiagnosticReason.SCHEMA_INVALID, "link must be an object")
            val name = requiredString(lo, "name")
            StremioId.validateBoundedString(name, 256, "link name")
            val category = requiredString(lo, "category")
            StremioId.validateBoundedString(category, 128, "link category")
            val url = requiredString(lo, "url")
            StremioId.validateBoundedString(url, 4096, "link url")

            if (url.startsWith("stremio://", ignoreCase = true)) {

                StremioMetaLink(
                    raw = lo,
                    name = name,
                    category = category,
                    url = null,
                    deepLinkRef = url.take(4096),
                )
            } else {
                val canonical = locatorPolicy.validateExternalUrl(url)
                StremioMetaLink(
                    raw = lo,
                    name = name,
                    category = category,
                    url = canonical,
                    deepLinkRef = null,
                )
            }
        }
    }
}
