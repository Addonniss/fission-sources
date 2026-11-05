package dev.infusion.source.acquisition

import dev.infusion.core.domain.acquisition.AcquisitionFailureKind
import dev.infusion.core.domain.acquisition.AcquisitionQualityProfile
import dev.infusion.core.domain.acquisition.AcquisitionRootFolder
import dev.infusion.core.domain.acquisition.AcquisitionService
import dev.infusion.core.domain.acquisition.AcquisitionTestOutcome
import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.DiagnosticReason
import dev.infusion.core.network.HardenedHttpClient
import dev.infusion.core.network.HardenedRequest
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

class AcquisitionClient(
    private val origin: CanonicalUrl,
    private val transport: HardenedHttpClient,
    private val apiKey: String,
    private val limits: NetworkLimits = jsonLimits,
) : Closeable {

    suspend fun load(): AcquisitionTestOutcome = withContext(Dispatchers.IO) {
        val status = when (val fetched = get(Endpoint.SYSTEM_STATUS)) {
            is Fetch.Err -> return@withContext fetched.outcome
            is Fetch.Ok -> fetched
        }
        val version = try {
            AcquisitionWire.parseVersion(String(status.body, StandardCharsets.UTF_8))
        } catch (e: AcquisitionWire.MalformedBodyException) {
            return@withContext malformed("system/status", status.status, e)
        }

        val rootsResponse = when (val fetched = get(Endpoint.ROOT_FOLDER)) {
            is Fetch.Err -> return@withContext fetched.outcome
            is Fetch.Ok -> fetched
        }
        val roots: List<AcquisitionRootFolder> = try {
            AcquisitionWire.parseRootFolders(String(rootsResponse.body, StandardCharsets.UTF_8))
        } catch (e: AcquisitionWire.MalformedBodyException) {
            return@withContext malformed("rootfolder", rootsResponse.status, e)
        }

        val profilesResponse = when (val fetched = get(Endpoint.QUALITY_PROFILE)) {
            is Fetch.Err -> return@withContext fetched.outcome
            is Fetch.Ok -> fetched
        }
        val profiles: List<AcquisitionQualityProfile> = try {
            AcquisitionWire.parseQualityProfiles(String(profilesResponse.body, StandardCharsets.UTF_8))
        } catch (e: AcquisitionWire.MalformedBodyException) {
            return@withContext malformed("qualityprofile", profilesResponse.status, e)
        }

        AcquisitionTestOutcome.Ok(serverVersion = version, roots = roots, profiles = profiles)
    }

    override fun close() {
        transport.close()
    }

    private sealed interface Fetch {
        data class Ok(val status: Int, val body: ByteArray) : Fetch
        data class Err(val outcome: AcquisitionTestOutcome.Failed) : Fetch
    }

    private fun get(path: String): Fetch {
        val url = try {
            buildUrl(path)
        } catch (e: NetworkPolicyException) {
            return Fetch.Err(
                AcquisitionTestOutcome.Failed(AcquisitionFailureKind.POLICY, null, "invalid request url"),
            )
        }
        val response = try {
            transport.execute(
                HardenedRequest(
                    url = url,
                    method = "GET",
                    headers = mapOf(API_KEY_HEADER to apiKey),
                    limits = limits,
                    sensitiveHeaders = SENSITIVE_HEADERS,
                ),
            )
        } catch (e: NetworkPolicyException) {
            return Fetch.Err(
                AcquisitionTestOutcome.Failed(
                    kind = policyKind(e.reason),
                    status = null,

                    message = "policy/network failure: ${e.reason.name}",
                ),
            )
        } catch (e: IOException) {
            return Fetch.Err(
                AcquisitionTestOutcome.Failed(
                    kind = AcquisitionFailureKind.NETWORK,
                    status = null,
                    message = "io: ${e::class.simpleName}",
                ),
            )
        }
        return response.use { resp ->
            val status = resp.statusCode
            if (status !in 200..299) {
                return@use Fetch.Err(
                    AcquisitionTestOutcome.Failed(classify(status), status, "http $status"),
                )
            }
            val bytes = try {
                readBounded(resp.body, limits.maxResponseBytes)
            } catch (e: NetworkPolicyException) {
                return@use Fetch.Err(
                    AcquisitionTestOutcome.Failed(policyKind(e.reason), null, "policy/network failure: ${e.reason.name}"),
                )
            } catch (e: IOException) {
                return@use Fetch.Err(
                    AcquisitionTestOutcome.Failed(AcquisitionFailureKind.NETWORK, null, "io: ${e::class.simpleName}"),
                )
            }
            Fetch.Ok(status, bytes)
        }
    }

    private fun buildUrl(path: String): CanonicalUrl {
        val base = origin.toString().trimEnd('/')
        val suffix = if (path.startsWith("/")) path else "/$path"
        return UrlParser.parse(base + suffix)
    }

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

    private fun malformed(label: String, status: Int, cause: Throwable): AcquisitionTestOutcome.Failed =
        AcquisitionTestOutcome.Failed(
            kind = AcquisitionFailureKind.MALFORMED,
            status = status,

            message = "malformed $label response (${cause::class.simpleName})",
        )

    companion object {
        private const val API_KEY_HEADER = "X-Api-Key"

        val SENSITIVE_HEADERS: Set<String> = setOf("x-api-key")

        val jsonLimits: NetworkLimits = NetworkLimits.DEFAULT.copy(retryOnConnectionFailure = true)

        private object Endpoint {
            const val SYSTEM_STATUS = "/api/v3/system/status"
            const val ROOT_FOLDER = "/api/v3/rootfolder"
            const val QUALITY_PROFILE = "/api/v3/qualityprofile"
        }

        private fun classify(status: Int): AcquisitionFailureKind = when (status) {
            401 -> AcquisitionFailureKind.UNAUTHORIZED
            403 -> AcquisitionFailureKind.FORBIDDEN
            404 -> AcquisitionFailureKind.NOT_FOUND
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

fun acquisitionClient(
    service: AcquisitionService,
    rawUrl: String,
    apiKey: String,
    transportFactory: (() -> OkHttpHardenedClient)? = null,
): Result<AcquisitionClient> {
    val trimmed = rawUrl.trim().trimEnd('/')
    val url = try {
        UrlParser.parse(trimmed)
    } catch (e: Exception) {
        return Result.failure(IllegalArgumentException("invalid server url for ${service.id}", e))
    }
    if (url.scheme != "http" && url.scheme != "https") {
        return Result.failure(IllegalArgumentException("only http/https server urls are supported"))
    }
    if (apiKey.isBlank()) {
        return Result.failure(IllegalArgumentException("api key must not be blank"))
    }
    val transport = transportFactory?.invoke() ?: OkHttpHardenedClient(
        trust = TrustClass.LAN_TRUSTED,
        approvedLanHost = url.host,
        networkPolicy = StrictNetworkPolicy,
        limits = AcquisitionClient.jsonLimits,
    )
    return Result.success(AcquisitionClient(origin = url, transport = transport, apiKey = apiKey))
}
