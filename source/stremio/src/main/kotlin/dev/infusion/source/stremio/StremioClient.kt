package dev.infusion.source.stremio

import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.ConcurrencyBudget
import dev.infusion.core.network.ConcurrencyWorkClass
import dev.infusion.core.network.DiagnosticDetailKey
import dev.infusion.core.network.DiagnosticLimitGuard
import dev.infusion.core.network.DiagnosticReason
import dev.infusion.core.network.DiagnosticResource
import dev.infusion.core.network.DiagnosticsCollector
import dev.infusion.core.network.HardenedHttpClient
import dev.infusion.core.network.HardenedRequest
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.network.NetworkPolicyException
import dev.infusion.core.network.RedactedDiagnostic
import dev.infusion.core.network.RequestCancellation
import dev.infusion.core.network.TrustClass
import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.policy.StremioLocatorPolicy
import dev.infusion.source.stremio.route.StremioRoutes
import dev.infusion.source.stremio.url.ConfiguredAddonUrl
import dev.infusion.source.stremio.wire.CatalogParser
import dev.infusion.source.stremio.wire.JsonErrorCode
import dev.infusion.source.stremio.wire.JsonLimits
import dev.infusion.source.stremio.wire.JsonValue
import dev.infusion.source.stremio.wire.ManifestParser
import dev.infusion.source.stremio.wire.MetaParser
import dev.infusion.source.stremio.wire.StremioCatalogResponse
import dev.infusion.source.stremio.wire.StremioManifest
import dev.infusion.source.stremio.wire.StremioMetaResponse
import dev.infusion.source.stremio.wire.StremioStreamResponse
import dev.infusion.source.stremio.wire.StremioSubtitlesResponse
import dev.infusion.source.stremio.wire.StreamsParser
import dev.infusion.source.stremio.wire.StrictJson
import dev.infusion.source.stremio.wire.StrictJsonException
import dev.infusion.source.stremio.wire.SubtitleParser
import dev.infusion.source.stremio.wire.WireException
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StremioLimits(
    val manifest: NetworkLimits = NetworkLimits.manifest(),
    val catalogMeta: NetworkLimits = NetworkLimits.DEFAULT.copy(maxResponseBytes = 1L * 1024 * 1024),
    val streamSubtitles: NetworkLimits = NetworkLimits.resource().copy(

        maxResponseBytes = Long.MAX_VALUE,
        maxBufferedResponseBytes = NetworkLimits.DEFAULT.maxDecompressedBytes,
    ),
) {
    companion object {
        val DEFAULT = StremioLimits()
    }
}

class StremioClient(
    private val configured: ConfiguredAddonUrl,
    private val trust: TrustClass,
    private val transport: HardenedHttpClient,
    private val approvedLanHost: String? = null,
    private val budget: ConcurrencyBudget = ConcurrencyBudget(
        DEFAULT_BUDGET_PERMITS,
        reservedForRequested = REQUESTED_RESERVE_PERMITS,
    ),
    private val limits: StremioLimits = StremioLimits.DEFAULT,
) : java.io.Closeable {

    private val routes: StremioRoutes = StremioRoutes.forConfiguredAddon(configured)

    private val locatorPolicy = StremioLocatorPolicy(trust, approvedLanHost)

    val originLabel: String get() = configured.origin

    val manifestUrl: CanonicalUrl get() = configured.manifestUrl

    val configuredUrl: ConfiguredAddonUrl get() = configured

    val hardenedTransport: HardenedHttpClient get() = transport

    val budgetPermits: Int get() = budget.budgetPermits

    suspend fun fetchManifest(cancellation: RequestCancellation? = null): StremioResult<StremioManifest> =
        execute(routes.manifest(), limits.manifest, DiagnosticResource.MANIFEST, cancellation) { obj ->
            ManifestParser.parse(obj)
        }

    suspend fun catalog(
        type: StremioContentType,
        catalogId: String,
        extras: List<Pair<String, String>> = emptyList(),
        allowedExtraKeys: Set<String> = StremioRoutes.DEFAULT_EXTRA_KEYS,
        cancellation: RequestCancellation? = null,
    ): StremioResult<StremioCatalogResponse> {
        val route = buildRoute(DiagnosticResource.CATALOG) {
            routes.catalog(type, catalogId, extras, allowedExtraKeys)
        }
        return when (route) {
            is RouteBuild.Rejected -> route.failure
            is RouteBuild.Built -> execute(route.url, limits.catalogMeta, DiagnosticResource.CATALOG, cancellation) { obj ->
                CatalogParser.parse(obj)
            }
        }
    }

    suspend fun meta(
        type: StremioContentType,
        id: String,
        cancellation: RequestCancellation? = null,
        speculative: Boolean = false,
    ): StremioResult<StremioMetaResponse> {
        val route = buildRoute(DiagnosticResource.META) { routes.meta(type, id) }
        return when (route) {
            is RouteBuild.Rejected -> route.failure
            is RouteBuild.Built -> execute(
                route.url,
                limits.catalogMeta,
                DiagnosticResource.META,
                cancellation,
                speculative = speculative,
            ) { obj -> MetaParser.parse(obj, type, id, locatorPolicy) }
        }
    }

    suspend fun streams(
        type: StremioContentType,
        videoId: String,
        cancellation: RequestCancellation? = null,
    ): StremioResult<StremioStreamResponse> {
        val route = buildRoute(DiagnosticResource.STREAM) { routes.stream(type, videoId) }
        return when (route) {
            is RouteBuild.Rejected -> route.failure
            is RouteBuild.Built -> execute(route.url, limits.streamSubtitles, DiagnosticResource.STREAM, cancellation) { obj ->
                StreamsParser.parse(obj, locatorPolicy)
            }
        }
    }

    suspend fun subtitles(
        type: StremioContentType,
        id: String,
        extras: List<Pair<String, String>> = emptyList(),
        cancellation: RequestCancellation? = null,
    ): StremioResult<StremioSubtitlesResponse> {
        val route = buildRoute(DiagnosticResource.SUBTITLE) { routes.subtitles(type, id, extras) }
        return when (route) {
            is RouteBuild.Rejected -> route.failure
            is RouteBuild.Built -> execute(route.url, limits.streamSubtitles, DiagnosticResource.SUBTITLE, cancellation) { obj ->
                SubtitleParser.parse(obj, locatorPolicy)
            }
        }
    }

    private sealed class RouteBuild {
        data class Built(val url: CanonicalUrl) : RouteBuild()

        data class Rejected(val failure: StremioResult<Nothing>) : RouteBuild()
    }

    private fun buildRoute(resource: DiagnosticResource, block: () -> CanonicalUrl): RouteBuild = try {
        RouteBuild.Built(block())
    } catch (e: WireException) {
        val diag = DiagnosticsCollector(origin = configured.origin, resource = resource)
            .finish(e.reason)
        RouteBuild.Rejected(
            StremioResult.Err(
                StremioFailure(e.reason, null, e.message ?: "route rejected", retryable = false, diagnostic = diag),
            ),
        )
    }

    private suspend fun <T> execute(
        url: CanonicalUrl,
        limits: NetworkLimits,
        resource: DiagnosticResource,
        cancellation: RequestCancellation?,
        speculative: Boolean = false,
        parse: (JsonValue.Obj) -> T,
    ): StremioResult<T> = withContext(Dispatchers.IO) {
        if (cancellation?.isCancelled == true) {
            return@withContext err(
                DiagnosticReason.CANCELLED,
                null,
                "request cancelled",
                resource,
                retryable = false,
                collector = null,
            )
        }

        val workClass = if (speculative) {
            ConcurrencyWorkClass.SPECULATIVE
        } else {
            ConcurrencyWorkClass.REQUESTED
        }
        if (!budget.tryAcquire(workClass)) {
            return@withContext err(
                DiagnosticReason.CONCURRENCY_EXCEEDED,
                null,
                "concurrency budget exceeded",
                resource,
                retryable = false,
                collector = null,
            )
        }
        val collector = DiagnosticsCollector(origin = configured.origin, resource = resource)
        var status: Int? = null
        val startNanos = System.nanoTime()
        try {
            val response = transport.execute(
                HardenedRequest(url = url, limits = limits, cancellation = cancellation),
            )
            try {
                status = response.statusCode
                if (status !in 200..299) {
                    val reason = classifyHttp(status)
                    return@withContext err(
                        reason,
                        status,
                        "http $status",
                        resource,
                        retryable = reason == DiagnosticReason.RATE_LIMITED ||
                            reason == DiagnosticReason.SERVER_ERROR ||

                            reason == DiagnosticReason.TIMEOUT,
                        collector = collector,
                    )
                }
                val contentType = response.headers["content-type"] ?: ""
                if (!isJsonContentType(contentType)) {
                    return@withContext err(
                        DiagnosticReason.SCHEMA_INVALID,
                        status,
                        "wrong content type",
                        resource,
                        retryable = false,
                        collector = collector,
                    )
                }

                if (resource == DiagnosticResource.MANIFEST && !response.finalUrl.sameOrigin(configured.manifestUrl)) {
                    return@withContext err(
                        DiagnosticReason.REDIRECT_POLICY,
                        status,
                        "manifest redirect left the add-on origin",
                        resource,
                        retryable = false,
                        collector = collector,
                    )
                }
                val bufferedLimit = limits.maxBufferedResponseBytes ?: limits.maxResponseBytes
                val bounded = readBounded(response.body, bufferedLimit)
                when (bounded) {
                    is BoundedRead.Overflow -> return@withContext err(
                        DiagnosticReason.LIMIT_EXCEEDED,
                        status,
                        "response exceeds byte limit",
                        resource,
                        retryable = false,
                        collector = collector,
                        limitGuard = if (limits.maxResponseBytes == Long.MAX_VALUE) {
                            DiagnosticLimitGuard.DECOMPRESSED_BYTES
                        } else {
                            DiagnosticLimitGuard.RESPONSE_BODY_BYTES
                        },
                    )
                    is BoundedRead.Io -> {
                        if (cancellation?.isCancelled == true) {
                            return@withContext err(
                                DiagnosticReason.CANCELLED,
                                status,
                                "request cancelled",
                                resource,
                                retryable = false,
                                collector = collector,
                            )
                        }

                        val overBudget = System.nanoTime() - startNanos >= limits.totalTimeoutMs * 1_000_000
                        return@withContext err(
                            if (overBudget) DiagnosticReason.TIMEOUT else DiagnosticReason.CONNECTION_FAILED,
                            status,
                            "response body read failed",
                            resource,
                            retryable = true,
                            collector = collector,
                        )
                    }
                    is BoundedRead.Ok -> {
                        val doc = try {
                            StrictJson.parse(bounded.bytes, JsonLimits.forDocument(bufferedLimit))
                        } catch (e: StrictJsonException) {
                            return@withContext err(
                                mapJsonError(e),
                                status,
                                "invalid json: ${e.code.name}",
                                resource,
                                retryable = false,
                                collector = collector,
                                limitGuard = if (mapJsonError(e) == DiagnosticReason.LIMIT_EXCEEDED) {
                                    e.code.limitGuard
                                } else {
                                    null
                                },
                            )
                        }
                        val obj = doc.asObject()
                            ?: return@withContext err(
                                DiagnosticReason.SCHEMA_INVALID,
                                status,
                                "response is not a json object",
                                resource,
                                retryable = false,
                                collector = collector,
                            )
                        val value = try {
                            parse(obj)
                        } catch (e: WireException) {
                            return@withContext err(
                                e.reason,
                                status,
                                e.message ?: "schema invalid",
                                resource,
                                retryable = false,
                                collector = collector,
                                limitGuard = e.limitGuard,
                            )
                        }
                        collector.recordDetail(DiagnosticDetailKey.BYTES_READ, bounded.bytes.size.toLong())

                        val valueWithBytes = if (value is StremioStreamResponse) {
                            value.withBytesRead(bounded.bytes.size.toLong()) as T
                        } else {
                            value
                        }
                        StremioResult.Ok(valueWithBytes, status)
                    }
                }
            } finally {
                response.close()
            }
        } catch (e: NetworkPolicyException) {
            if (cancellation?.isCancelled == true) {
                return@withContext err(DiagnosticReason.CANCELLED, status, "request cancelled", resource, retryable = false, collector = collector)
            }
            return@withContext err(
                e.reason,
                status,
                "policy failure: ${e.reason.name}",
                resource,
                retryable = e.reason in RETRYABLE_REASONS,
                collector = collector,
            )
        } catch (e: IOException) {
            if (cancellation?.isCancelled == true) {
                return@withContext err(DiagnosticReason.CANCELLED, status, "request cancelled", resource, retryable = false, collector = collector)
            }

            val overBudget = System.nanoTime() - startNanos >= limits.totalTimeoutMs * 1_000_000
            return@withContext err(
                if (overBudget) DiagnosticReason.TIMEOUT else DiagnosticReason.CONNECTION_FAILED,
                status,
                "io: ${e::class.simpleName}",
                resource,
                retryable = true,
                collector = collector,
            )
        } finally {
            budget.release(workClass)
        }
    }

    private fun err(
        reason: DiagnosticReason,
        status: Int?,
        message: String,
        resource: DiagnosticResource,
        retryable: Boolean,
        collector: DiagnosticsCollector?,
        limitGuard: dev.infusion.core.network.DiagnosticLimitGuard? = null,
    ): StremioResult<Nothing> {
        val diagnostic: RedactedDiagnostic =
            collector?.finish(reason, status)
                ?: DiagnosticsCollector(origin = configured.origin, resource = resource)
                    .finish(reason, status)
        return if (limitGuard != null && diagnostic.limitGuard == null) {
            StremioResult.Err(
                StremioFailure(
                    reason = reason,
                    status = status,
                    message = message,
                    retryable = retryable,
                    diagnostic = diagnostic.copy(limitGuard = limitGuard),
                ),
            )
        } else {
            StremioResult.Err(StremioFailure(reason, status, message, retryable, diagnostic))
        }
    }

    private fun classifyHttp(status: Int): DiagnosticReason = when (status) {
        401 -> DiagnosticReason.UNAUTHORIZED
        403 -> DiagnosticReason.FORBIDDEN
        404 -> DiagnosticReason.NOT_FOUND
        408 -> DiagnosticReason.TIMEOUT
        429 -> DiagnosticReason.RATE_LIMITED
        in 500..599 -> DiagnosticReason.SERVER_ERROR
        in 400..499 -> DiagnosticReason.SCHEMA_INVALID

        in 300..399 -> DiagnosticReason.REDIRECT_POLICY
        else -> DiagnosticReason.CONNECTION_FAILED
    }

    private fun mapJsonError(e: StrictJsonException): DiagnosticReason = when (e.code) {
        JsonErrorCode.NESTING_TOO_DEEP,
        JsonErrorCode.TOO_MANY_KEYS,
        JsonErrorCode.TOO_MANY_ELEMENTS,
        JsonErrorCode.TOO_MANY_NODES,
        -> DiagnosticReason.LIMIT_EXCEEDED
        else -> DiagnosticReason.SCHEMA_INVALID
    }

    private fun isJsonContentType(contentType: String): Boolean {
        val mediaType = contentType.substringBefore(';').trim().lowercase()
        return mediaType == "application/json" ||
            (mediaType.startsWith("application/") && mediaType.endsWith("+json"))
    }

    private fun readBounded(stream: java.io.InputStream, max: Long): BoundedRead {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        try {
            while (total < max) {
                val n = stream.read(buf, 0, minOf(buf.size, (max - total).toInt()))
                if (n < 0) return BoundedRead.Ok(out.toByteArray())
                out.write(buf, 0, n)
                total += n
            }
            val extra = stream.read()
            return if (extra >= 0) BoundedRead.Overflow else BoundedRead.Ok(out.toByteArray())
        } catch (e: IOException) {
            var t: Throwable? = e
            while (t != null) {
                if (t is NetworkPolicyException && t.reason == DiagnosticReason.LIMIT_EXCEEDED) {
                    return BoundedRead.Overflow
                }
                t = t.cause
            }
            return BoundedRead.Io
        }
    }

    private sealed class BoundedRead {
        data class Ok(val bytes: ByteArray) : BoundedRead()
        data object Overflow : BoundedRead()
        data object Io : BoundedRead()
    }

    override fun close() {
        transport.close()
    }

    companion object {

        const val DEFAULT_BUDGET_PERMITS = 4

        const val REQUESTED_RESERVE_PERMITS = 2
    }
}

typealias AddonClientFactory = (configured: ConfiguredAddonUrl, trust: TrustClass) -> StremioClient
