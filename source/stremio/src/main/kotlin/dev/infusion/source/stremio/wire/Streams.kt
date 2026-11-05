package dev.infusion.source.stremio.wire

import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.DiagnosticLimitGuard
import dev.infusion.core.network.DiagnosticReason
import dev.infusion.core.network.LocatorKind
import dev.infusion.core.network.LocatorPolicy
import dev.infusion.source.stremio.policy.StremioLocatorPolicy
import dev.infusion.source.stremio.policy.StremioId
import dev.infusion.source.stremio.wire.WireJson.requiredArray

sealed class StremioStreamCandidate {
    abstract val raw: JsonValue.Obj
    abstract val name: String?

    data class Media(
        override val raw: JsonValue.Obj,
        val url: CanonicalUrl,
        override val name: String?,
        val description: String?,
        val subtitles: List<StremioSubtitle>,
        val videoSizeBytes: Long? = null,
    ) : StremioStreamCandidate()

    data class External(
        override val raw: JsonValue.Obj,
        val externalUrl: CanonicalUrl,
        override val name: String?,
        val description: String? = null,
    ) : StremioStreamCandidate()

    data class Notice(
        override val raw: JsonValue.Obj,
        override val name: String?,
        val description: String?,
    ) : StremioStreamCandidate()
}

data class RejectedCandidate(
    val index: Int,
    val reason: DiagnosticReason,
    val kind: String,

    val limitGuard: DiagnosticLimitGuard? = null,
) {
    fun withLimitGuard(guard: DiagnosticLimitGuard): RejectedCandidate =
        if (limitGuard != null) this else copy(limitGuard = guard)
}

data class StremioStreamResponse(
    val raw: JsonValue.Obj,
    val streams: List<StremioStreamCandidate>,
    val rejected: List<RejectedCandidate>,

    val bytesRead: Long = 0,
) {
    init {
        require(bytesRead >= 0) { "bytesRead must be non-negative" }
    }

    companion object {

        internal val INFORMATIONAL_STREAM_TYPES = setOf("statistic", "info")
    }

    val streamArrayCount: Int
        get() = (raw.fields["streams"] as? JsonValue.Arr)?.elements?.size ?: 0

    val acceptedCount: Int get() = streams.size

    val rejectedCount: Int get() = rejected.size

    fun withBytesRead(bytes: Long): StremioStreamResponse = copy(bytesRead = bytes)
}

object StreamsParser {

    private val EXTRA_REJECTED_FIELDS = setOf("fileMustInclude")

    internal const val MAX_EMBEDDED_SUBTITLES = 32

    internal const val MAX_NOTICE_DESCRIPTION_BYTES = 8192

    internal const val MAX_DESCRIPTION_BYTES = 1024

    internal const val MAX_VIDEO_SIZE_BYTES = 1L shl 50

    private fun boundedVideoSize(o: JsonValue.Obj): Long? {
        val hints = o.fields["behaviorHints"] as? JsonValue.Obj ?: return null
        val bytes = when (val v = hints.fields["videoSize"]) {
            is JsonValue.Num.Int -> v.value
            is JsonValue.Num.Dbl -> if (v.value.isFinite()) v.value.toLong() else return null
            else -> return null
        }
        return bytes.takeIf { it in 1..MAX_VIDEO_SIZE_BYTES }
    }

    private fun boundedDescription(o: JsonValue.Obj, maxBytes: Int, label: String): String? =
        WireJson.string(o, "description")?.let {
            StremioId.validateBoundedString(it, maxBytes, label, allowLineBreaks = true)
            it
        }

    private fun informationalNoticeType(o: JsonValue.Obj): String? {
        val streamData = o.fields["streamData"] as? JsonValue.Obj ?: return null
        val type = (streamData.fields["type"] as? JsonValue.Str)?.value ?: return null
        if (type.toByteArray(Charsets.UTF_8).size > StremioId.MAX_NAME_BYTES) return null
        return type.takeIf { it in StremioStreamResponse.INFORMATIONAL_STREAM_TYPES }
    }

    fun parse(root: JsonValue.Obj, locatorPolicy: StremioLocatorPolicy): StremioStreamResponse {
        val entries = requiredArray(root, "streams")
        val streams = ArrayList<StremioStreamCandidate>(entries.size)
        val rejected = ArrayList<RejectedCandidate>(entries.size)
        entries.forEachIndexed { index, v ->
            val o = v as? JsonValue.Obj
            if (o == null) {
                rejected.add(RejectedCandidate(index, DiagnosticReason.SCHEMA_INVALID, "non-object"))
                return@forEachIndexed
            }
            parseItem(o, index, locatorPolicy, streams, rejected)
        }
        return StremioStreamResponse(raw = root, streams = streams, rejected = rejected)
    }

    private fun parseItem(
        o: JsonValue.Obj,
        index: Int,
        locatorPolicy: StremioLocatorPolicy,
        streams: MutableList<StremioStreamCandidate>,
        rejected: MutableList<RejectedCandidate>,
    ) {
        val rawFields = o.fields.mapValues { it.value.toRaw() }
        val kind = LocatorPolicy.classify(rawFields)

        val hasExtraRejected = rawFields.keys.any { it in EXTRA_REJECTED_FIELDS }
        when {
            hasExtraRejected -> {
                rejected.add(RejectedCandidate(index, DiagnosticReason.UNSUPPORTED_LOCATOR, "archive/usenet"))
            }
            kind == LocatorKind.URL -> {
                val urlRaw = WireJson.string(o, "url")
                if (urlRaw == null) {
                    rejected.add(RejectedCandidate(index, DiagnosticReason.SCHEMA_INVALID, "url-not-string"))
                    return
                }
                val canonical = try {
                    locatorPolicy.validateFetchableUrl(urlRaw)
                } catch (e: WireException) {
                    rejected.add(RejectedCandidate(index, e.reason, "url"))
                    return
                }
                val subtitles = parseEmbeddedSubtitles(o, locatorPolicy, index, rejected)
                streams.add(
                    StremioStreamCandidate.Media(
                        raw = o,
                        url = canonical,
                        name = boundedName(WireJson.string(o, "name")),
                        description = boundedDescription(o, MAX_DESCRIPTION_BYTES, "stream description"),
                        subtitles = subtitles,

                        videoSizeBytes = boundedVideoSize(o),
                    ),
                )
            }
            kind == LocatorKind.EXTERNAL_BROWSER -> {
                val extRaw = WireJson.string(o, "externalUrl")
                if (extRaw == null) {
                    rejected.add(RejectedCandidate(index, DiagnosticReason.SCHEMA_INVALID, "externalUrl-not-string"))
                    return
                }

                if (informationalNoticeType(o) != null) {
                    val description = boundedDescription(o, MAX_NOTICE_DESCRIPTION_BYTES, "stream notice description")
                    streams.add(
                        StremioStreamCandidate.Notice(
                            raw = o,
                            name = boundedName(WireJson.string(o, "name")),
                            description = description,
                        ),
                    )
                    return
                }
                val canonical = try {
                    locatorPolicy.validateExternalUrl(extRaw)
                } catch (e: WireException) {
                    rejected.add(RejectedCandidate(index, e.reason, "externalUrl"))
                    return
                }
                streams.add(
                    StremioStreamCandidate.External(
                        raw = o,
                        externalUrl = canonical,
                        name = boundedName(WireJson.string(o, "name")),

                        description = boundedDescription(o, MAX_DESCRIPTION_BYTES, "stream description"),
                    ),
                )
            }
            kind == LocatorKind.UNKNOWN -> {
                rejected.add(RejectedCandidate(index, DiagnosticReason.SCHEMA_INVALID, "no-locator"))
            }
            else -> {
                rejected.add(RejectedCandidate(index, DiagnosticReason.UNSUPPORTED_LOCATOR, kind.name.lowercase()))
            }
        }
    }

    private fun parseEmbeddedSubtitles(
        o: JsonValue.Obj,
        locatorPolicy: StremioLocatorPolicy,
        streamIndex: Int,
        rejected: MutableList<RejectedCandidate>,
    ): List<StremioSubtitle> {
        val entries = WireJson.array(o, "subtitles") ?: return emptyList()

        if (entries.size > MAX_EMBEDDED_SUBTITLES) {
            rejected.add(
                RejectedCandidate(
                    streamIndex,
                    DiagnosticReason.LIMIT_EXCEEDED,
                    "embedded-subtitles",
                ).withLimitGuard(DiagnosticLimitGuard.EMBEDDED_SUBTITLES),
            )
            return emptyList()
        }
        val out = ArrayList<StremioSubtitle>(entries.size)
        val seen = HashSet<String>(entries.size)
        entries.forEachIndexed { i, v ->
            val so = v as? JsonValue.Obj
            if (so == null) return@forEachIndexed
            try {
                val item = SubtitleParser.parseItem(so, locatorPolicy)
                if (seen.add(item.id)) out.add(item)
                else rejected.add(RejectedCandidate(streamIndex, DiagnosticReason.SCHEMA_INVALID, "embedded-subtitle-duplicate"))
            } catch (e: WireException) {
                rejected.add(RejectedCandidate(streamIndex, e.reason, "embedded-subtitle-$i"))
            }
        }
        return out
    }

    private fun boundedName(name: String?): String? = name?.let {
        StremioId.validateBoundedString(
            it,
            StremioId.MAX_NAME_BYTES,
            "stream name",
            allowLineBreaks = true,
        )
        it
    }
}
