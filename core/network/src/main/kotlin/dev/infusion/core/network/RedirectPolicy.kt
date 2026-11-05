package dev.infusion.core.network

import java.net.URI
import java.net.URISyntaxException

object RedirectSemantics {

    data class Hop(val method: String, val body: ByteArray?)

    fun nextHop(status: Int, crossOrigin: Boolean, method: String, body: ByteArray?): Hop = when (status) {
        301, 302 -> when {

            body != null -> Hop("GET", null)

            method == "HEAD" -> Hop("HEAD", null)

            else -> Hop(method, null)
        }

        303 -> Hop(if (method == "HEAD") "HEAD" else "GET", null)
        307, 308 -> {
            if (crossOrigin && body != null) {
                throw NetworkPolicyException(
                    DiagnosticReason.REDIRECT_POLICY,
                    "cross-origin $status with a request body denied",
                )
            }
            Hop(method, body)
        }
        else -> Hop(method, body)
    }
}

class RedirectPolicy(
    private val networkPolicy: NetworkPolicy = StrictNetworkPolicy,
    private val resolver: HostResolver = SystemHostResolver,
    private val allowHttpToHttps: Boolean = false,
) {

    fun validateRedirect(
        original: CanonicalUrl,
        location: String,
        trust: TrustClass,
        approvedLanHost: String?,
    ): CanonicalUrl {
        val next = try {
            val resolved = resolveLocation(original, location)
            UrlParser.parse(resolved)
        } catch (e: URISyntaxException) {
            throw NetworkPolicyException(
                DiagnosticReason.REDIRECT_POLICY,
                "invalid redirect location: INVALID_URL",
            )
        } catch (e: NetworkPolicyException) {
            throw NetworkPolicyException(
                DiagnosticReason.REDIRECT_POLICY,
                "invalid redirect location: ${e.reason.name}",
                cause = e,
            )
        }
        if (original.scheme == "https" && next.scheme == "http" ||
            original.scheme == "http" && next.scheme == "https" && !allowHttpToHttps
        ) {
            throw NetworkPolicyException(
                DiagnosticReason.REDIRECT_POLICY,
                "redirect scheme downgrade denied",
                origin = original.origin,
            )
        }
        val decision = networkPolicy.decide(
            next.host,
            resolver.resolve(next.host),
            next.scheme,
            trust,
            approvedLanHost,
        )
        if (!decision.allowed) {
            throw NetworkPolicyException(
                decision.reason ?: DiagnosticReason.REDIRECT_POLICY,
                "redirect destination denied",
                origin = original.origin,
            )
        }
        return next
    }

    private fun resolveLocation(original: CanonicalUrl, location: String): String {
        if (location.isEmpty()) return original.toString()
        val base = URI(original.toString())
        val reference = URI(location)
        if (reference.scheme == null && reference.rawAuthority == null && reference.rawPath.isEmpty() && reference.rawQuery != null) {
            return URI(base.scheme, base.rawAuthority, base.rawPath, reference.rawQuery, null).toString()
        }
        return base.resolve(reference).toString()
    }
}
