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

        val parsed = try {
            UrlParser.parse(withHttps)
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
