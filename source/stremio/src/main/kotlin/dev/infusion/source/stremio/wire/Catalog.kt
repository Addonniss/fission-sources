package dev.infusion.source.stremio.wire

import dev.infusion.core.network.DiagnosticReason
import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.policy.StremioId
import dev.infusion.source.stremio.wire.WireJson.requiredArray
import dev.infusion.source.stremio.wire.WireJson.requiredString

data class StremioMetaPreview(
    val raw: JsonValue.Obj,
    val id: String,
    val type: StremioContentType,
    val name: String,
    val poster: String?,
    val posterShape: String?,
    val background: String?,
    val genres: List<String>,
    val description: String?,
    val releaseInfo: String?,
    val imdbRating: String?,
) {

    val posterIsLandscape: Boolean
        get() = posterShape == POSTER_SHAPE_LANDSCAPE
}

data class StremioCatalogResponse(
    val raw: JsonValue.Obj,
    val metas: List<StremioMetaPreview>,
) {
    companion object {
        const val MAX_METAS = 1000
    }
}

object CatalogParser {

    fun parse(root: JsonValue.Obj): StremioCatalogResponse {
        val entries = requiredArray(root, "metas")
        if (entries.size > StremioCatalogResponse.MAX_METAS) {
            throw WireException(DiagnosticReason.LIMIT_EXCEEDED, "catalog has too many metas")
        }
        val seen = HashSet<String>(entries.size)
        val metas = entries.map { v ->
            val o = v as? JsonValue.Obj
                ?: throw WireException(DiagnosticReason.SCHEMA_INVALID, "catalog meta must be an object")
            val id = requiredString(o, "id")
            StremioId.validateOrThrow(id)
            if (!seen.add(id)) {
                throw WireException(DiagnosticReason.SCHEMA_INVALID, "duplicate meta id in catalog")
            }

            val type = StremioContentType.parse(requiredString(o, "type"))
                ?.takeIf { it.isDeclaredContentType }
                ?: throw WireException(DiagnosticReason.SCHEMA_INVALID, "unsupported meta type in catalog")
            val name = requiredString(o, "name")
            StremioId.validateBoundedString(name, StremioId.MAX_NAME_BYTES, "meta name")
            val poster = WireJson.string(o, "poster")?.let { validateOptionalUrl(it, "poster") }

            val background = WireJson.string(o, "background")
                ?.takeIf { it.isNotEmpty() }
                ?.let { validateOptionalUrl(it, "background") }
            val posterShape = WireJson.string(o, "posterShape")?.takeIf { it in POSTER_SHAPES }
            val genres = WireJson.stringArray(o, "genres", maxElements = 64, maxElementBytes = 256) ?: emptyList()

            val description = WireJson.string(o, "description")
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    StremioId.validateBoundedString(it, 8192, "description", allowLineBreaks = true)
                    it
                }
            val releaseInfo = WireJson.string(o, "releaseInfo")?.let {
                StremioId.validateBoundedString(it, 64, "releaseInfo")
                it
            }
            val imdbRating = WireJson.string(o, "imdbRating")
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    StremioId.validateBoundedString(it, 16, "imdbRating")
                    it
                }
            StremioMetaPreview(
                raw = o,
                id = id,
                type = type,
                name = name,
                poster = poster,
                posterShape = posterShape,
                background = background,
                genres = genres,
                description = description,
                releaseInfo = releaseInfo,
                imdbRating = imdbRating,
            )
        }
        return StremioCatalogResponse(raw = root, metas = metas)
    }

    private fun validateOptionalUrl(raw: String, what: String): String {
        try {
            dev.infusion.core.network.UrlParser.parse(raw)
        } catch (e: dev.infusion.core.network.NetworkPolicyException) {
            throw WireException(DiagnosticReason.SCHEMA_INVALID, "invalid $what url: ${e.reason.name}", cause = e)
        }
        return raw
    }

    private val POSTER_SHAPES = setOf(POSTER_SHAPE_SQUARE, POSTER_SHAPE_POSTER, POSTER_SHAPE_LANDSCAPE)
}

const val POSTER_SHAPE_SQUARE = "square"
const val POSTER_SHAPE_POSTER = "poster"
const val POSTER_SHAPE_LANDSCAPE = "landscape"
