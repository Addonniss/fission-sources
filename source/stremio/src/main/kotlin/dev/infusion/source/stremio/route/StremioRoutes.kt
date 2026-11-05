package dev.infusion.source.stremio.route

import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.DiagnosticReason
import dev.infusion.core.network.NetworkPolicyException
import dev.infusion.core.network.UrlParser
import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.policy.StremioId
import dev.infusion.source.stremio.url.AddonUrlNormalizer.AddonQuerySecret
import dev.infusion.source.stremio.url.ConfiguredAddonUrl
import dev.infusion.source.stremio.wire.WireException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

class StremioRoutes private constructor(
    private val origin: CanonicalUrl,
    private val basePath: String,
    private val configuredQuery: String?,
) {

    val originLabel: String get() = origin.origin

    fun manifest(): CanonicalUrl = buildPath("/manifest.json")

    fun catalog(
        type: StremioContentType,
        catalogId: String,
        extras: List<Pair<String, String>> = emptyList(),
        allowedExtraKeys: Set<String> = DEFAULT_EXTRA_KEYS,
    ): CanonicalUrl {
        rejectUnknownType(type)
        val base = resourcePath("catalog", type, catalogId)
        if (extras.isEmpty()) return buildPath("$base.json")
        val extraComponent = encodeExtras(extras, allowedExtraKeys)
        return buildPath("$base/$extraComponent.json")
    }

    fun meta(type: StremioContentType, id: String): CanonicalUrl {
        rejectUnknownType(type)
        return buildPath("${resourcePath("meta", type, id)}.json")
    }

    fun stream(type: StremioContentType, videoId: String): CanonicalUrl {
        rejectUnknownType(type)
        return buildPath("${resourcePath("stream", type, videoId)}.json")
    }

    fun subtitles(
        type: StremioContentType,
        id: String,
        extras: List<Pair<String, String>> = emptyList(),
    ): CanonicalUrl {
        rejectUnknownType(type)
        val base = resourcePath("subtitles", type, id)
        if (extras.isEmpty()) return buildPath("$base.json")
        val extraComponent = encodeExtras(extras, SUBTITLE_EXTRA_KEYS)
        return buildPath("$base/$extraComponent.json")
    }

    private fun rejectUnknownType(type: StremioContentType) {
        if (type == StremioContentType.UNKNOWN) {
            throw WireException(DiagnosticReason.SCHEMA_INVALID, "unsupported content type")
        }
    }

    private fun resourcePath(resource: String, type: StremioContentType, id: String): String {
        StremioId.validateOrThrow(id)
        return "/$resource/${type.wire}/${encodePathSegment(id)}"
    }

    private fun buildPath(path: String): CanonicalUrl {

        val raw = origin.origin + basePath + path
        val withQuery = if (configuredQuery != null) "$raw?$configuredQuery" else raw
        return try {
            UrlParser.parse(withQuery)
        } catch (e: NetworkPolicyException) {
            throw WireException(
                DiagnosticReason.INVALID_URL,
                "route build failed: ${e.reason.name}",
                cause = e,
            )
        }
    }

    companion object {

        val DEFAULT_EXTRA_KEYS: Set<String> = setOf("search", "skip")

        val SUBTITLE_EXTRA_KEYS: Set<String> = setOf("videoHash", "videoSize", "filename")

        const val MAX_EXTRA_ARGS = 8
        const val MAX_EXTRA_KEY_BYTES = 64
        const val MAX_EXTRA_VALUE_BYTES = 512

        private val EXTRA_KEY = Regex("^[a-zA-Z0-9_-]+$")

        fun forConfiguredAddon(configured: ConfiguredAddonUrl): StremioRoutes {
            val basePath = configured.publicBase.path.trimEnd('/')
            return StremioRoutes(
                origin = configured.publicBase,
                basePath = basePath,
                configuredQuery = configured.querySecret?.raw,
            )
        }

        fun forManifestUrl(manifestUrl: CanonicalUrl): StremioRoutes {
            val configured = when (val result = dev.infusion.source.stremio.url.AddonUrlNormalizer.normalizeInput(manifestUrl.toString())) {
                is dev.infusion.source.stremio.url.AddonUrlInputResult.Valid -> result.configured
                is dev.infusion.source.stremio.url.AddonUrlInputResult.Invalid -> throw WireException(
                    DiagnosticReason.SCHEMA_INVALID,
                    "manifest url must end in /manifest.json",
                )
            }
            return forConfiguredAddon(configured)
        }

        fun encodePathSegment(value: String): String {
            val out = StringBuilder(value.length * 2)
            for (b in value.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF

                val isUnreserved = c in 0x41..0x5A || c in 0x61..0x7A || c in 0x30..0x39 ||
                    c == 0x2D || c == 0x2E || c == 0x5F || c == 0x7E
                if (isUnreserved) {
                    out.append(c.toChar())
                } else {
                    out.append('%').append(HEX[(c ushr 4) and 0xF]).append(HEX[c and 0xF])
                }
            }
            return out.toString()
        }

        fun decodeOnce(value: String): String {
            if ('%' !in value) return value
            val bytes = ByteArrayOutputStream(value.length)
            var i = 0
            var literalStart = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '%') {
                    if (i + 2 >= value.length) {
                        throw WireException(DiagnosticReason.SCHEMA_INVALID, "truncated percent escape in extra value")
                    }
                    val hi = hexValue(value[i + 1]) ?: throw WireException(
                        DiagnosticReason.SCHEMA_INVALID,
                        "invalid percent escape in extra value",
                    )
                    val lo = hexValue(value[i + 2]) ?: throw WireException(
                        DiagnosticReason.SCHEMA_INVALID,
                        "invalid percent escape in extra value",
                    )
                    if (literalStart < i) {
                        bytes.write(value.substring(literalStart, i).toByteArray(Charsets.UTF_8))
                    }
                    bytes.write((hi shl 4) or lo)
                    i += 3
                    literalStart = i
                } else {
                    i++
                }
            }
            if (literalStart < value.length) {
                bytes.write(value.substring(literalStart).toByteArray(Charsets.UTF_8))
            }
            return try {
                UTF8_DECODER.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            } catch (e: CharacterCodingException) {
                throw WireException(DiagnosticReason.SCHEMA_INVALID, "invalid utf-8 in extra value", cause = e)
            }
        }

        private fun hexValue(c: Char): Int? = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> null
        }

        private val HEX = "0123456789ABCDEF".toCharArray()

        private val UTF8_DECODER: java.nio.charset.CharsetDecoder =
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)

        fun encodeExtras(
            extras: List<Pair<String, String>>,
            allowedExtraKeys: Set<String>,
        ): String {
            if (extras.size > MAX_EXTRA_ARGS) {
                throw WireException(DiagnosticReason.LIMIT_EXCEEDED, "too many extra args")
            }
            val seen = HashSet<String>(extras.size)
            val parts = ArrayList<String>(extras.size)
            for ((key, rawValue) in extras) {
                if (key.toByteArray(Charsets.UTF_8).size > MAX_EXTRA_KEY_BYTES || !EXTRA_KEY.matches(key)) {
                    throw WireException(DiagnosticReason.SCHEMA_INVALID, "invalid extra arg key")
                }
                if (key !in allowedExtraKeys) {
                    throw WireException(DiagnosticReason.SCHEMA_INVALID, "extra arg not allowed")
                }
                if (!seen.add(key)) {
                    throw WireException(DiagnosticReason.SCHEMA_INVALID, "duplicate extra arg")
                }
                val decoded = decodeOnce(rawValue)
                if (decoded.toByteArray(Charsets.UTF_8).size > MAX_EXTRA_VALUE_BYTES) {
                    throw WireException(DiagnosticReason.LIMIT_EXCEEDED, "extra arg value too long")
                }
                parts.add("$key=${encodePathSegment(decoded)}")
            }
            return parts.joinToString("&")
        }
    }
}
