package dev.infusion.core.network

import java.net.IDN
import java.net.URI
import java.net.URISyntaxException

data class CanonicalUrl(
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
    val query: String?,
) {
    val isDefaultPort: Boolean get() = port == defaultPortFor(scheme)

    val origin: String
        get() {
            val h = if (host.contains(':')) "[$host]" else host
            return if (isDefaultPort) "$scheme://$h" else "$scheme://$h:$port"
        }

    fun sameOrigin(other: CanonicalUrl): Boolean =
        scheme == other.scheme && host == other.host && port == other.port

    override fun toString(): String {
        val h = if (host.contains(':')) "[$host]" else host
        val portPart = if (isDefaultPort) "" else ":$port"
        val q = if (query == null) "" else "?$query"
        val p = if (path.isEmpty()) "/" else path
        return "$scheme://$h$portPart$p$q"
    }

    companion object {
        fun defaultPortFor(scheme: String): Int = when (scheme) {
            "https" -> 443
            "http" -> 80
            else -> throw IllegalArgumentException("unsupported scheme $scheme")
        }
    }
}

object UrlParser {

    const val MAX_URL_LENGTH = 8192
    const val MAX_PATH_LENGTH = 4096
    const val MAX_QUERY_LENGTH = 4096

    fun parse(raw: String): CanonicalUrl {
        if (raw.isBlank()) throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "empty url")
        if (raw.length > MAX_URL_LENGTH) throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "url too long")
        for (c in raw) {
            if (c.code < 0x20 || c.code == 0x7f) {
                throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "control character in url")
            }
            if (c == '\\') {
                throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "backslash in url")
            }
        }

        val parseTarget = if (raw.any { it.code >= 0x80 }) encodeHostIdna(raw) ?: raw else raw
        val uri = try {
            URI(parseTarget)
        } catch (e: URISyntaxException) {
            throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "malformed url", cause = e)
        }

        val scheme = uri.scheme?.lowercase()
            ?: throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "missing scheme")
        if (scheme != "https" && scheme != "http") {
            throw NetworkPolicyException(DiagnosticReason.SCHEME_DENIED, "scheme not allowed: $scheme")
        }
        if (uri.userInfo != null) {
            throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "userinfo not allowed")
        }
        if (uri.fragment != null) {
            throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "fragment not allowed")
        }

        val host = uri.host
            ?: throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "missing authority")
        var hostRaw = host
        if (hostRaw.startsWith("[") && hostRaw.endsWith("]")) {
            hostRaw = hostRaw.substring(1, hostRaw.length - 1)
        }
        if (hostRaw.contains('%')) {
            throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "zone/encoded host not allowed")
        }

        if (hostRaw.contains(':')) {
            if (!IpAddressClassifier.isValidIpv6Literal(hostRaw)) {
                throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "invalid ipv6 literal")
            }
        } else if (hostRaw.all { it.isDigit() || it == '.' } && !IpAddressClassifier.isCanonicalIpv4Literal(hostRaw)) {

            throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "ambiguous numeric host")
        }

        validateAuthorityPort(uri.rawAuthority)

        val port = when (val p = uri.port) {
            -1 -> CanonicalUrl.defaultPortFor(scheme)
            0 -> throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "port zero not allowed")
            else -> p
        }

        val path = uri.rawPath ?: ""
        if (path.length > MAX_PATH_LENGTH) {
            throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "path too long")
        }
        val query = uri.rawQuery
        if (query != null && query.length > MAX_QUERY_LENGTH) {
            throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "query too long")
        }

        return CanonicalUrl(scheme, canonicalizeHostname(hostRaw), port, path, query)
    }

    fun canonicalizeHostname(host: String): String {
        var h = host
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1)
        if (h.endsWith(".")) h = h.dropLast(1)
        if (h.isEmpty()) throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "empty host")
        if (h.contains(':')) {
            if (!IpAddressClassifier.isValidIpv6Literal(h)) {
                throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "invalid ipv6 literal")
            }
            return h.lowercase()
        }
        return try {
            IDN.toASCII(h.lowercase(), IDN.USE_STD3_ASCII_RULES)
        } catch (e: IllegalArgumentException) {
            throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "invalid hostname", cause = e)
        }
    }

    private fun encodeHostIdna(raw: String): String? {
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd < 0) return null
        var authorityStart = schemeEnd + 3
        var authorityEnd = authorityStart
        while (authorityEnd < raw.length && raw[authorityEnd] != '/' && raw[authorityEnd] != '?' && raw[authorityEnd] != '#') {
            authorityEnd++
        }
        if (authorityEnd == authorityStart) return null
        val authority = raw.substring(authorityStart, authorityEnd)
        if (authority.all { it.code < 0x80 }) return null
        val hostEnd: Int
        val portStart: Int
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            hostEnd = close + 1
            portStart = -1
        } else {
            val colon = authority.indexOf(':')
            hostEnd = if (colon < 0) authority.length else colon
            portStart = colon
        }
        if (hostEnd <= 0) return null
        val host = authority.substring(0, hostEnd)
        if (host.all { it.code < 0x80 }) return null
        val encodedHost = try {
            IDN.toASCII(host.lowercase(), IDN.USE_STD3_ASCII_RULES)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val port = if (portStart >= 0) authority.substring(portStart) else ""
        val rebuilt = raw.substring(0, authorityStart) + encodedHost + port + raw.substring(authorityEnd)
        return rebuilt
    }

    private fun validateAuthorityPort(rawAuthority: String?) {
        if (rawAuthority.isNullOrEmpty()) return
        val portStr: String? = if (rawAuthority.startsWith("[")) {
            val close = rawAuthority.indexOf(']')
            if (close < 0 || close + 1 >= rawAuthority.length || rawAuthority[close + 1] != ':') null
            else rawAuthority.substring(close + 2)
        } else {
            val colon = rawAuthority.indexOf(':')
            if (colon < 0) null else rawAuthority.substring(colon + 1)
        }
        if (portStr == null || portStr.isEmpty()) return
        if (portStr.any { !it.isDigit() } || portStr.length > 5) {
            throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "invalid port")
        }
        val p = portStr.toIntOrNull()
        if (p == null || p < 1 || p > 65535) {
            throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "port out of range")
        }
    }
}
