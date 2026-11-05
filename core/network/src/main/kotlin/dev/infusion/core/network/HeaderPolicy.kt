package dev.infusion.core.network

object HeaderPolicy {

    private val NEVER_SENT = setOf("host")

    private val HOP_BY_HOP = setOf(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailer", "transfer-encoding", "upgrade",
    )

    val SENSITIVE_REQUEST = setOf(
        "cookie", "authorization", "proxy-authorization", "origin", "referer", "host",
        "x-emby-token", "x-emby-authorization", "x-plex-token", "x-api-key",
        "x-access-token", "x-auth-token", "x-mediaserver-token",
    )

    fun isSensitive(name: String, extra: Set<String> = emptySet()): Boolean {
        val n = name.lowercase()

        return n in SENSITIVE_REQUEST || extra.any { n == it.lowercase() }
    }

    fun isHopByHop(name: String): Boolean = name.lowercase() in HOP_BY_HOP

    fun sanitizeInitial(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { it.lowercase() !in NEVER_SENT && !isHopByHop(it) }

    fun forNextHop(
        previous: CanonicalUrl,
        next: CanonicalUrl,
        headers: Map<String, String>,
        extraSensitive: Set<String> = emptySet(),
    ): Map<String, String> {
        val crossOrigin = !previous.sameOrigin(next)
        return headers.filterKeys { name ->
            val n = name.lowercase()
            !isHopByHop(n) && n !in NEVER_SENT && !(crossOrigin && isSensitive(n, extraSensitive))
        }
    }

    private val TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

    fun validateExternalHeaders(candidate: Map<String, String>): List<String> {
        val rejected = mutableListOf<String>()
        for ((name, value) in candidate) {
            val n = name.lowercase()
            val badName = !TOKEN.matches(name) ||
                n in SENSITIVE_REQUEST || n in HOP_BY_HOP ||
                n == "set-cookie" || n == "content-length" || n == "content-encoding"
            val badValue = value.any { it == '\r' || it == '\n' || it.code == 0x7f || (it.code < 0x20 && it != '\t') }
            if (badName || badValue) rejected.add(name)
        }
        return rejected
    }
}
