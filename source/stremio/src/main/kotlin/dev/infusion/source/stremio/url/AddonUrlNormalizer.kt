package dev.infusion.source.stremio.url

import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.DiagnosticReason
import dev.infusion.core.network.UrlParser
import dev.infusion.source.stremio.wire.WireException

object AddonUrlNormalizer {

    const val MAX_CONFIGURED_URL_LENGTH = UrlParser.MAX_URL_LENGTH

    fun normalizeInput(raw: String): AddonUrlInputResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return AddonUrlInputResult.Invalid(InvalidReason.EMPTY)
        }
        val schemePrefix = "stremio://"
        val withHttps = if (trimmed.startsWith(schemePrefix, ignoreCase = true)) {
            "https://" + trimmed.substring(schemePrefix.length)
        } else {
            trimmed
        }

        if (!withHttps.startsWith("https://") && !withHttps.startsWith("http://")) {
            return AddonUrlInputResult.Invalid(InvalidReason.UNSUPPORTED_SCHEME)
        }

        val parseInput = encodeUriIllegalInData(withHttps)

        val parsed = try {
            UrlParser.parse(parseInput)
        } catch (e: dev.infusion.core.network.NetworkPolicyException) {
            return AddonUrlInputResult.Invalid(when (e.reason) {
                DiagnosticReason.SCHEME_DENIED -> InvalidReason.UNSUPPORTED_SCHEME
                else -> InvalidReason.UNPARSEABLE
            })
        }

        val path = parsed.path
        val query = parsed.query

        val configuredPath = when {
            path == "/manifest.json" -> ""
            path.endsWith("/manifest.json") -> path.removeSuffix("/manifest.json").trimEnd('/')
            else -> path.trimEnd('/')
        }

        val publicBase = parsed.copy(path = configuredPath, query = null)
        val querySecret = query?.let(::AddonQuerySecret)

        return AddonUrlInputResult.Valid(
            ConfiguredAddonUrl(publicBase = publicBase, querySecret = querySecret),
        )
    }

    class AddonQuerySecret internal constructor(
        val raw: String,
    ) {
        override fun toString(): String = "AddonQuerySecret(configured)"

        override fun equals(other: Any?): Boolean =
            other is AddonQuerySecret && other.raw == raw

        override fun hashCode(): Int = raw.hashCode()
    }

    @JvmStatic
    fun querySecretOrNull(query: String?): AddonQuerySecret? = query?.let(::AddonQuerySecret)

    private const val URI_ILLEGAL_IN_PATH_OR_QUERY = "\"<>^`{}|"

    private val ENCODE_HEX = "0123456789ABCDEF".toCharArray()

    private fun encodeUriIllegalInData(input: String): String {
        val schemeEnd = input.indexOf("://")
        if (schemeEnd < 0) return input
        var dataStart = -1
        var i = schemeEnd + 3
        while (i < input.length) {
            val c = input[i]
            if (c == '/' || c == '?' || c == '#') {
                dataStart = i
                break
            }
            i++
        }
        if (dataStart < 0) return input

        var needsEncoding = false
        for (j in dataStart until input.length) {
            if (input[j] in URI_ILLEGAL_IN_PATH_OR_QUERY) {
                needsEncoding = true
                break
            }
        }
        if (!needsEncoding) return input

        val out = StringBuilder(input.length + 16)
        out.append(input, 0, dataStart)
        for (j in dataStart until input.length) {
            val c = input[j]
            if (c in URI_ILLEGAL_IN_PATH_OR_QUERY) {
                val code = c.code
                out.append('%').append(ENCODE_HEX[(code shr 4) and 0xF]).append(ENCODE_HEX[code and 0xF])
            } else {
                out.append(c)
            }
        }
        return out.toString()
    }
}

sealed interface AddonUrlInputResult {
    data class Valid(val configured: ConfiguredAddonUrl) : AddonUrlInputResult

    data class Invalid(val reason: InvalidReason) : AddonUrlInputResult
}

enum class InvalidReason {
    EMPTY,
    UNSUPPORTED_SCHEME,
    UNPARSEABLE,
}
