package dev.infusion.source.stremio.policy

import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.DiagnosticReason
import dev.infusion.core.network.NetworkPolicyException
import dev.infusion.core.network.TrustClass
import dev.infusion.core.network.UrlParser
import dev.infusion.source.stremio.wire.WireException

class StremioLocatorPolicy(
    private val trust: TrustClass,
    private val approvedLanHost: String?,
) {

    fun validateFetchableUrl(raw: String): CanonicalUrl {
        val canonical = parse(raw)
        if (canonical.scheme == "http" && !isApprovedLan(canonical.host)) {
            throw WireException(DiagnosticReason.SCHEME_DENIED, "http media url without lan trust")
        }
        if (canonical.scheme != "http" && canonical.scheme != "https") {
            throw WireException(DiagnosticReason.SCHEME_DENIED, "unsupported media url scheme")
        }
        return canonical
    }

    fun validateExternalUrl(raw: String): CanonicalUrl = validateFetchableUrl(raw)

    private fun parse(raw: String): CanonicalUrl = try {
        UrlParser.parse(raw)
    } catch (e: NetworkPolicyException) {
        throw WireException(
            if (e.reason == DiagnosticReason.SCHEME_DENIED) DiagnosticReason.SCHEME_DENIED
            else DiagnosticReason.INVALID_URL,
            "invalid locator url: ${e.reason.name}",
            cause = e,
        )
    }

    private fun isApprovedLan(host: String): Boolean {
        if (trust != TrustClass.LAN_TRUSTED || approvedLanHost == null) return false
        return try {
            UrlParser.canonicalizeHostname(host).equals(approvedLanHost, ignoreCase = true)
        } catch (_: NetworkPolicyException) {
            false
        }
    }
}
