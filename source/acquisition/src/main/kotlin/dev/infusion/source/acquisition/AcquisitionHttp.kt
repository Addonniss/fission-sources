package dev.infusion.source.acquisition

import dev.infusion.core.domain.acquisition.AcquisitionFailureKind
import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.DiagnosticReason
import dev.infusion.core.network.HardenedHttpClient
import dev.infusion.core.network.HardenedRequest
import dev.infusion.core.network.HardenedWebSocketFactory
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.network.NetworkPolicyException
import dev.infusion.core.network.OkHttpHardenedClient
import dev.infusion.core.network.StrictNetworkPolicy
import dev.infusion.core.network.TrustClass
import dev.infusion.core.network.UrlParser
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AcquisitionHttp(
    private val origin: CanonicalUrl,
    private val transport: HardenedHttpClient,
    private val apiKey: String,
    private val limits: NetworkLimits = AcquisitionClient.jsonLimits,
) : Closeable {

    sealed interface Result {
        data class Ok(val status: Int, val body: String) : Result
        data class Err(val kind: AcquisitionFailureKind, val status: Int?, val message: String) : Result
    }

    suspend fun get(path: String): Result = execute("GET", path, null)

    suspend fun post(path: String, body: String): Result = execute("POST", path, body)

    suspend fun put(path: String, body: String): Result = execute("PUT", path, body)

    override fun close() = transport.close()

    private suspend fun execute(method: String, path: String, body: String?): Result =
        withContext(Dispatchers.IO) {
            val url = try {
                buildUrl(path)
            } catch (e: NetworkPolicyException) {
                return@withContext Result.Err(AcquisitionFailureKind.POLICY, null, "invalid request url")
            }
            val headers = buildMap {
                put(API_KEY_HEADER, apiKey)
                if (body != null) put("Content-Type", "application/json")
            }
            val response = try {
                transport.execute(
                    HardenedRequest(
                        url = url,
                        method = method,
                        headers = headers,
                        limits = limits,
                        body = body?.toByteArray(StandardCharsets.UTF_8),
                        sensitiveHeaders = AcquisitionClient.SENSITIVE_HEADERS,
                    ),
                )
            } catch (e: NetworkPolicyException) {
                return@withContext Result.Err(
                    policyKind(e.reason),
                    null,
                    "policy/network failure: ${e.reason.name}",
                )
            } catch (e: IOException) {
                return@withContext Result.Err(AcquisitionFailureKind.NETWORK, null, "io: ${e::class.simpleName}")
            }
            response.use { resp ->
                val status = resp.statusCode
                val text = try {
                    String(readBounded(resp.body, limits.maxResponseBytes), StandardCharsets.UTF_8)
                } catch (e: NetworkPolicyException) {
                    return@use Result.Err(policyKind(e.reason), status, "policy/network failure: ${e.reason.name}")
                } catch (e: IOException) {
                    return@use Result.Err(AcquisitionFailureKind.NETWORK, status, "io: ${e::class.simpleName}")
                }
                if (status !in 200..299) {
                    Result.Err(classify(status), status, "http $status")
                } else {
                    Result.Ok(status, text)
                }
            }
        }

    private fun buildUrl(path: String): CanonicalUrl {
        val base = origin.toString().trimEnd('/')
        val suffix = if (path.startsWith("/")) path else "/$path"
        return UrlParser.parse(base + suffix)
    }

    fun urlFor(path: String, query: Map<String, String> = emptyMap()): CanonicalUrl {
        val base = origin.toString().trimEnd('/')
        val suffix = if (path.startsWith("/")) path else "/$path"
        val q = if (query.isEmpty()) {
            ""
        } else {
            "?" + query.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        }
        return UrlParser.parse(base + suffix + q)
    }

    fun webSockets(): HardenedWebSocketFactory? = transport as? HardenedWebSocketFactory

    fun authHeader(): Pair<String, String> = API_KEY_HEADER to apiKey

    suspend fun resolveFinalUrl(path: String): CanonicalUrl? = withContext(Dispatchers.IO) {
        val url = try {
            buildUrl(path)
        } catch (e: NetworkPolicyException) {
            return@withContext null
        }
        try {
            transport.execute(
                HardenedRequest(
                    url = url,
                    method = "GET",
                    headers = mapOf(API_KEY_HEADER to apiKey),
                    limits = limits,
                    sensitiveHeaders = AcquisitionClient.SENSITIVE_HEADERS,
                ),
            ).use { it.finalUrl }
        } catch (e: Exception) {
            null
        }
    }

    fun withQuery(url: CanonicalUrl, key: String, value: String): CanonicalUrl {
        val base = url.toString()
        val sep = if (base.contains('?')) "&" else "?"
        return UrlParser.parse(base + sep + enc(key) + "=" + enc(value))
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun readBounded(stream: InputStream, max: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (total < max) {
            val n = stream.read(buf, 0, minOf(buf.size.toLong(), max - total).toInt())
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            total += n
        }
        if (stream.read() >= 0) {
            throw NetworkPolicyException(DiagnosticReason.LIMIT_EXCEEDED, "response exceeds limit")
        }
        return out.toByteArray()
    }

    companion object {
        const val API_KEY_HEADER = "X-Api-Key"

        fun classify(status: Int): AcquisitionFailureKind = when (status) {
            401 -> AcquisitionFailureKind.UNAUTHORIZED
            403 -> AcquisitionFailureKind.FORBIDDEN
            404 -> AcquisitionFailureKind.NOT_FOUND
            409 -> AcquisitionFailureKind.BAD_REQUEST
            429 -> AcquisitionFailureKind.RATE_LIMITED
            in 400..499 -> AcquisitionFailureKind.BAD_REQUEST
            in 500..599 -> AcquisitionFailureKind.SERVER_ERROR
            else -> AcquisitionFailureKind.UNKNOWN
        }

        private fun policyKind(reason: DiagnosticReason): AcquisitionFailureKind = when (reason) {
            DiagnosticReason.TIMEOUT -> AcquisitionFailureKind.TIMEOUT
            DiagnosticReason.DNS_FAILED -> AcquisitionFailureKind.DNS
            DiagnosticReason.CLEARTEXT_BLOCKED -> AcquisitionFailureKind.CLEARTEXT_BLOCKED
            DiagnosticReason.CONNECTION_FAILED -> AcquisitionFailureKind.NETWORK
            else -> AcquisitionFailureKind.POLICY
        }
    }
}

internal fun acquisitionTransport(
    rawUrl: String,
    transportFactory: (() -> OkHttpHardenedClient)? = null,
): Result<Pair<CanonicalUrl, OkHttpHardenedClient>> {
    val trimmed = rawUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("server url must not be blank"))
    val url = try {
        UrlParser.parse(trimmed)
    } catch (e: Exception) {
        return Result.failure(IllegalArgumentException("invalid server url", e))
    }
    if (url.scheme != "http" && url.scheme != "https") {
        return Result.failure(IllegalArgumentException("only http/https server urls are supported"))
    }
    val transport = transportFactory?.invoke() ?: OkHttpHardenedClient(
        trust = TrustClass.LAN_TRUSTED,
        approvedLanHost = url.host,
        networkPolicy = StrictNetworkPolicy,
        limits = AcquisitionClient.jsonLimits,
    )
    return Result.success(url to transport)
}
