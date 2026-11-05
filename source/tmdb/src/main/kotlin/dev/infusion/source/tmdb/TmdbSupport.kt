package dev.infusion.source.tmdb

import dev.infusion.core.domain.metadata.MetadataFailureException
import dev.infusion.core.domain.metadata.MetadataFailureReason
import dev.infusion.core.network.DiagnosticReason
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.network.NetworkPolicyException
import org.json.JSONArray
import org.json.JSONObject

@JvmInline
value class TmdbApiKey private constructor(private val raw: String) {

    val isPresent: Boolean get() = raw.isNotEmpty()

    internal fun reveal(): String = raw

    override fun toString(): String = if (isPresent) "TmdbApiKey(redacted)" else "TmdbApiKey(absent)"

    companion object {

        fun of(raw: String?): TmdbApiKey = TmdbApiKey(raw?.trim().orEmpty())

        val Absent: TmdbApiKey = TmdbApiKey("")
    }
}

object TmdbEndpoints {
    const val API_ORIGIN = "https://api.themoviedb.org"
    const val API_BASE_PATH = "/3"

    const val IMAGE_PROVIDER = "tmdb"

    private const val IMAGE_ORIGIN = "https://image.tmdb.org/t/p"

    fun imageUrl(size: String, path: String): String {
        val cleanSize = size.trim().trim('/').ifEmpty { "original" }
        val cleanPath = path.trim().let { if (it.startsWith("/")) it else "/$it" }
        return "$IMAGE_ORIGIN/$cleanSize$cleanPath"
    }
}

internal val TMDB_LIMITS: NetworkLimits = NetworkLimits(
    maxResponseBytes = 2L * 1024 * 1024,
    maxDecompressedBytes = 4L * 1024 * 1024,
    connectTimeoutMs = 10_000,
    readTimeoutMs = 20_000,
    totalTimeoutMs = 30_000,
    maxRedirects = 2,
)

internal object TmdbJson {

    fun objectOrNull(parent: JSONObject?, key: String): JSONObject? {
        if (parent == null || !parent.has(key) || parent.isNull(key)) return null
        return parent.optJSONObject(key)
    }

    fun arrayOrNull(parent: JSONObject?, key: String): JSONArray? {
        if (parent == null || !parent.has(key) || parent.isNull(key)) return null
        return parent.optJSONArray(key)
    }

    fun string(parent: JSONObject?, key: String): String? {
        if (parent == null || !parent.has(key) || parent.isNull(key)) return null
        return parent.optString(key).trim().takeIf { it.isNotEmpty() }
    }

    fun stringOrNumber(parent: JSONObject?, key: String): String? {
        if (parent == null || !parent.has(key) || parent.isNull(key)) return null
        val value = parent.opt(key)
        return when (value) {
            is Number -> value.toString()
            else -> (value as? String)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    fun int(parent: JSONObject?, key: String): Int? {
        if (parent == null || !parent.has(key) || parent.isNull(key)) return null
        val value = parent.opt(key)
        return when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    fun long(parent: JSONObject?, key: String): Long? {
        if (parent == null || !parent.has(key) || parent.isNull(key)) return null
        val value = parent.opt(key)
        return when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }

    fun double(parent: JSONObject?, key: String): Double? {
        if (parent == null || !parent.has(key) || parent.isNull(key)) return null
        val value = parent.opt(key)
        val parsed = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }

        return parsed?.takeIf { it.isFinite() }
    }

    fun bool(parent: JSONObject?, key: String): Boolean? {
        if (parent == null || !parent.has(key) || parent.isNull(key)) return null
        val value = parent.opt(key)
        return value as? Boolean
    }

    fun stringList(parent: JSONObject?, key: String): List<String> {
        val array = arrayOrNull(parent, key) ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val entry = array.opt(i)
                when (entry) {
                    is String -> entry.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                    is Number -> add(entry.toString())
                }
            }
        }
    }

    fun forEach(array: JSONArray?, action: (JSONObject) -> Unit) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            action(entry)
        }
    }

    fun parseDocument(body: String): JSONObject = try {
        JSONObject(body)
    } catch (_: org.json.JSONException) {
        throw MetadataFailureException(MetadataFailureReason.MALFORMED, "provider document is not valid JSON")
    }
}

internal fun NetworkPolicyException.toMetadataFailure(): MetadataFailureException {
    val reason = when (this.reason) {
        DiagnosticReason.TIMEOUT -> MetadataFailureReason.TIMEOUT
        DiagnosticReason.CANCELLED -> MetadataFailureReason.UNAVAILABLE
        DiagnosticReason.DNS_FAILED,
        DiagnosticReason.CONNECTION_FAILED,
        DiagnosticReason.CLEARTEXT_BLOCKED,
        DiagnosticReason.PRIVATE_DESTINATION,
        DiagnosticReason.SCHEME_DENIED,
        DiagnosticReason.REDIRECT_POLICY,
        DiagnosticReason.SERVER_ERROR,
        -> MetadataFailureReason.UNAVAILABLE
        DiagnosticReason.UNAUTHORIZED -> MetadataFailureReason.UNAUTHORIZED
        DiagnosticReason.FORBIDDEN -> MetadataFailureReason.UNAUTHORIZED
        DiagnosticReason.NOT_FOUND -> MetadataFailureReason.NOT_FOUND
        DiagnosticReason.RATE_LIMITED -> MetadataFailureReason.RATE_LIMITED
        DiagnosticReason.SCHEMA_INVALID,
        DiagnosticReason.LIMIT_EXCEEDED,
        DiagnosticReason.UNSUPPORTED_ENCODING,
        DiagnosticReason.UNSUPPORTED_LOCATOR,
        DiagnosticReason.CONCURRENCY_EXCEEDED,
        DiagnosticReason.INVALID_URL,
        DiagnosticReason.INTERNAL,
        -> MetadataFailureReason.MALFORMED
    }
    return MetadataFailureException(reason, "metadata transport failure: ${this.reason}", cause = this)
}

internal fun httpStatusFailure(status: Int): MetadataFailureException {
    val reason = when (status) {
        401, 403 -> MetadataFailureReason.UNAUTHORIZED
        404 -> MetadataFailureReason.NOT_FOUND
        429 -> MetadataFailureReason.RATE_LIMITED
        in 500..599 -> MetadataFailureReason.UNAVAILABLE
        else -> MetadataFailureReason.MALFORMED
    }
    return MetadataFailureException(reason, "metadata provider answered with an error status")
}
