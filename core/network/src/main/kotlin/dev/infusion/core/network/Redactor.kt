package dev.infusion.core.network

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object Redactor {

    private val SHA256: ThreadLocal<MessageDigest> =
        ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

    private val ROUTING_HEADERS = setOf("origin", "referer", "host")

    fun hashFragment(value: String, hexChars: Int = 12): String {
        val digest = SHA256.get()
        digest.reset()
        val bytes = digest.digest(value.toByteArray(StandardCharsets.UTF_8))
        val byteCount = (hexChars + 1) / 2
        val sb = StringBuilder(hexChars)
        for (i in 0 until minOf(byteCount, bytes.size)) {
            sb.append("%02x".format(bytes[i].toInt() and 0xff))
        }
        return sb.toString()
    }

    fun url(canonical: CanonicalUrl): String {
        val pathHash = if (canonical.path.isEmpty() && canonical.query == null) {
            ""
        } else {
            "/" + hashFragment(canonical.path + (canonical.query ?: ""))
        }
        return canonical.origin + pathHash
    }

    fun url(raw: String): String = try {
        url(UrlParser.parse(raw))
    } catch (_: NetworkPolicyException) {
        "<invalid-url>"
    }

    fun headerValue(name: String, value: String): String {
        val n = name.lowercase()
        val credential = (n in HeaderPolicy.SENSITIVE_REQUEST && n !in ROUTING_HEADERS) ||
            n == "set-cookie"
        return if (credential) "[REDACTED]" else value
    }

    fun secret(value: String?): String = if (value == null) "<null>" else "[REDACTED]"

    fun token(value: String, visibleTail: Int = 0): String {
        if (value.isEmpty()) return "[REDACTED]"
        return if (visibleTail <= 0) "[REDACTED]" else "[REDACTED…${value.takeLast(visibleTail)}]"
    }
}
