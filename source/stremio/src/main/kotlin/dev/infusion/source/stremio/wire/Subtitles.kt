package dev.infusion.source.stremio.wire

import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.DiagnosticReason
import dev.infusion.source.stremio.policy.StremioLocatorPolicy
import dev.infusion.source.stremio.policy.StremioId
import dev.infusion.source.stremio.wire.WireJson.requiredArray
import dev.infusion.source.stremio.wire.WireJson.requiredString

data class StremioSubtitle(
    val raw: JsonValue.Obj,
    val id: String,
    val url: CanonicalUrl,
    val lang: String,

    val format: String? = null,
)

data class StremioSubtitlesResponse(
    val raw: JsonValue.Obj,
    val subtitles: List<StremioSubtitle>,
    val rejected: List<RejectedCandidate>,
) {
    companion object {

        const val MAX_SUBTITLES = 128
    }
}

object SubtitleParser {

    private val DECLARED_FILE_NAME_FIELDS = listOf("subtitleFileName", "filename")

    fun parse(root: JsonValue.Obj, locatorPolicy: StremioLocatorPolicy): StremioSubtitlesResponse {
        val entries = requiredArray(root, "subtitles")
        val kept = minOf(entries.size, StremioSubtitlesResponse.MAX_SUBTITLES)
        val subtitles = ArrayList<StremioSubtitle>(kept)
        val rejected = ArrayList<RejectedCandidate>()
        val seen = HashSet<String>(kept)
        entries.forEachIndexed { index, v ->
            if (index >= StremioSubtitlesResponse.MAX_SUBTITLES) {

                if (index == StremioSubtitlesResponse.MAX_SUBTITLES) {
                    rejected.add(
                        RejectedCandidate(index, DiagnosticReason.LIMIT_EXCEEDED, "subtitle-overflow")
                            .withLimitGuard(dev.infusion.core.network.DiagnosticLimitGuard.SUBTITLE_RESPONSE),
                    )
                }
                return@forEachIndexed
            }
            val o = v as? JsonValue.Obj
            if (o == null) {
                rejected.add(RejectedCandidate(index, DiagnosticReason.SCHEMA_INVALID, "non-object"))
                return@forEachIndexed
            }
            try {
                val item = parseItem(o, locatorPolicy)
                if (!seen.add(item.id)) {
                    rejected.add(RejectedCandidate(index, DiagnosticReason.SCHEMA_INVALID, "duplicate-id"))
                } else {
                    subtitles.add(item)
                }
            } catch (e: WireException) {
                rejected.add(RejectedCandidate(index, e.reason, "subtitle"))
            }
        }
        return StremioSubtitlesResponse(raw = root, subtitles = subtitles, rejected = rejected)
    }

    fun parseItem(o: JsonValue.Obj, locatorPolicy: StremioLocatorPolicy): StremioSubtitle {
        val id = requiredString(o, "id")
        StremioId.validateOrThrow(id)
        val lang = requiredString(o, "lang")
        StremioId.validateBoundedString(lang, 64, "subtitle lang")
        val urlRaw = requiredString(o, "url")
        val url = locatorPolicy.validateFetchableUrl(urlRaw)

        val format = declaredFormatToken(o) ?: declaredFileNameFormat(o)
        return StremioSubtitle(raw = o, id = id, url = url, lang = lang, format = format)
    }

    private fun declaredFormatToken(o: JsonValue.Obj): String? =
        WireJson.string(o, "format")?.let { raw ->
            StremioId.validateBoundedString(raw, 64, "subtitle format")
            dev.infusion.core.playback.api.SubtitleMimeAllowlist.normalize(raw)
        }

    private fun declaredFileNameFormat(o: JsonValue.Obj): String? =
        DECLARED_FILE_NAME_FIELDS
            .asSequence()
            .mapNotNull { field -> WireJson.string(o, field) }
            .mapNotNull { name ->
                dev.infusion.core.playback.api.SubtitleMimeAllowlist.normalizeDeclaredFileName(name)
            }
            .firstOrNull()
}
