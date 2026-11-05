package dev.infusion.core.network

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class RequestCancellation {
    private val lock = Any()

    private val calls = HashSet<okhttp3.Call>()

    @Volatile
    private var cancelled = false

    val isCancelled: Boolean get() = cancelled

    fun cancel() {
        val inFlight: List<okhttp3.Call>
        synchronized(lock) {
            cancelled = true
            inFlight = calls.toList()
            calls.clear()
        }

        inFlight.forEach { it.cancel() }
    }

    fun throwIfCancelled() {
        if (cancelled) {
            throw NetworkPolicyException(DiagnosticReason.CANCELLED, "request cancelled")
        }
    }

    fun register(call: okhttp3.Call) {
        val abort = synchronized(lock) {
            if (cancelled) {
                true
            } else {
                calls.add(call)
                false
            }
        }
        if (abort) call.cancel()
    }

    fun unregister(call: okhttp3.Call) {
        synchronized(lock) {
            calls.remove(call)
        }
    }
}

data class HardenedRequest(
    val url: CanonicalUrl,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val limits: NetworkLimits = NetworkLimits.DEFAULT,

    val body: ByteArray? = null,

    val sensitiveHeaders: Set<String> = emptySet(),

    val cancellation: RequestCancellation? = null,
)

data class HardenedResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: InputStream,
    val finalUrl: CanonicalUrl,
    private val closeAction: () -> Unit,
) : Closeable {
    override fun close() {
        body.close()
        closeAction()
    }

    override fun toString(): String =
        "HardenedResponse(statusCode=$statusCode, headers=$headers, body=$body, finalUrl=${finalUrl.origin})"
}

interface HardenedHttpClient : Closeable {

    fun execute(request: HardenedRequest): HardenedResponse

    override fun close() = Unit
}

class OkHttpHardenedClient(
    private val trust: TrustClass = TrustClass.PUBLIC,
    approvedLanHost: String? = null,
    private val networkPolicy: NetworkPolicy = StrictNetworkPolicy,
    private val resolver: HostResolver = SystemHostResolver,
    redirectPolicy: RedirectPolicy? = null,
    private val budget: ConcurrencyBudget = ConcurrencyBudget.unlimited(),
    limits: NetworkLimits = NetworkLimits.DEFAULT,
) : HardenedHttpClient, HardenedWebSocketFactory {

    val budgetPermits: Int get() = budget.budgetPermits

    private val approvedHost: String? = approvedLanHost?.let { UrlParser.canonicalizeHostname(it) }
    private val redirect: RedirectPolicy = redirectPolicy ?: RedirectPolicy(
        networkPolicy,
        resolver,
        limits.allowHttpToHttpsRedirect,
    )
    private val dns: Dns = ValidatingDns(resolver, networkPolicy, trust, approvedHost)

    private val ok: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(limits.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(limits.readTimeoutMs, TimeUnit.MILLISECONDS)

        .callTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(limits.retryOnConnectionFailure)
        .dns(dns)
        .build()

    override fun execute(request: HardenedRequest): HardenedResponse {
        request.cancellation?.throwIfCancelled()
        if (!budget.tryAcquire()) {
            throw NetworkPolicyException(
                DiagnosticReason.CONCURRENCY_EXCEEDED,
                "concurrency budget exhausted",
                origin = request.url.origin,
            )
        }
        val body = request.body
        if (body != null && body.size.toLong() > request.limits.maxRequestBytes) {
            budget.release()
            throw NetworkPolicyException(
                DiagnosticReason.LIMIT_EXCEEDED,
                "request body exceeds limit",
                origin = request.url.origin,
            )
        }
        var response: Response? = null
        try {

            val initial = networkPolicy.decide(
                request.url.host,
                resolver.resolve(request.url.host),
                request.url.scheme,
                trust,
                approvedHost,
            )
            if (!initial.allowed) {
                throw NetworkPolicyException(
                    initial.reason ?: DiagnosticReason.PRIVATE_DESTINATION,
                    "destination denied by policy",
                    origin = request.url.origin,
                )
            }

            var current = request.url
            var headers = HeaderPolicy.sanitizeInitial(request.headers)
            var method = request.method
            var body = request.body
            var hops = 0
            while (true) {
                request.cancellation?.throwIfCancelled()
                val call = ok.newCall(buildRequest(method, current, headers, body))
                request.cancellation?.register(call)
                var ownedByResponse = false
                try {
                    if (request.limits.totalTimeoutMs > 0) {
                        call.timeout().timeout(request.limits.totalTimeoutMs, TimeUnit.MILLISECONDS)
                    }
                    val resp = call.execute()
                    response = resp

                    if (resp.isRedirect) {
                        if (hops >= request.limits.maxRedirects) {
                            resp.close()
                            response = null
                            throw NetworkPolicyException(
                                DiagnosticReason.REDIRECT_POLICY,
                                "redirect limit exceeded",
                                origin = current.origin,
                            )
                        }
                        val location = resp.header("Location")
                        if (location != null) {
                            val next = redirect.validateRedirect(current, location, trust, approvedHost)
                            val nextHeaders = HeaderPolicy.forNextHop(current, next, headers, request.sensitiveHeaders)

                            val hop = RedirectSemantics.nextHop(
                                status = resp.code,
                                crossOrigin = !current.sameOrigin(next),
                                method = method,
                                body = body,
                            )
                            resp.close()
                            response = null
                            current = next
                            headers = nextHeaders
                            method = hop.method
                            body = hop.body
                            hops++
                            continue
                        }

                    }

                    ownedByResponse = true
                    return toResponse(resp, request.limits, current) {
                        request.cancellation?.unregister(call)
                    }
                } finally {
                    if (!ownedByResponse) {
                        request.cancellation?.unregister(call)
                    }
                }
            }
        } catch (e: NetworkPolicyException) {
            response?.closeQuietly()
            throw e
        } catch (e: IOException) {
            response?.closeQuietly()
            throw mapIOException(e)
        } finally {
            budget.release()
        }
    }

    private fun buildRequest(method: String, url: CanonicalUrl, headers: Map<String, String>, body: ByteArray? = null): Request {
        try {
            val builder = Request.Builder()
                .url(url.toString())
                .header("Accept-Encoding", "identity")
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                builder.header("User-Agent", DEFAULT_PLAYBACK_USER_AGENT)
            }
            headers.forEach { (k, v) -> builder.header(k, v) }
            return if (method == "GET" && body == null) {
                builder.get().build()
            } else {
                builder.method(method, RequestBody.create(null, body ?: ByteArray(0))).build()
            }
        } catch (e: IllegalArgumentException) {
            throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "url rejected by transport", cause = e)
        }
    }

    private fun toResponse(
        resp: Response,
        limits: NetworkLimits,
        finalUrl: CanonicalUrl,
        onClose: () -> Unit = {},
    ): HardenedResponse {
        val rawBody = resp.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
        val guarded = DecompressionGuard.wrap(rawBody, resp.header("Content-Encoding"), limits)
        val headers = resp.headers.toMultimap()
            .mapKeys { it.key.lowercase() }
            .mapValues { (_, v) -> v.firstOrNull() ?: "" }
        return HardenedResponse(
            statusCode = resp.code,
            headers = headers,
            body = PolicyBodyInputStream(guarded),
            finalUrl = finalUrl,
            closeAction = {
                resp.close()
                onClose()
            },
        )
    }

    private class PolicyBodyInputStream(delegate: InputStream) : java.io.FilterInputStream(delegate) {

        override fun read(): Int = try {
            super.read()
        } catch (e: IOException) {
            throw mapBodyException(e)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = try {
            super.read(b, off, len)
        } catch (e: IOException) {
            throw mapBodyException(e)
        }

        private fun mapBodyException(e: IOException): IOException {
            var t: Throwable? = e
            while (t != null) {
                if (t is NetworkPolicyException) return t
                t = t.cause
            }
            return if (e is InterruptedIOException || e is SocketTimeoutException) {
                NetworkPolicyException(DiagnosticReason.TIMEOUT, "response read timed out", cause = e)
            } else {
                e
            }
        }
    }

    internal fun mapIOException(e: IOException): NetworkPolicyException {
        var t: Throwable? = e
        while (t != null) {
            if (t is NetworkPolicyException) return t
            t = t.cause
        }
        val reason = when (e) {
            is SocketTimeoutException -> DiagnosticReason.TIMEOUT
            is InterruptedIOException -> DiagnosticReason.TIMEOUT
            is UnknownHostException -> DiagnosticReason.DNS_FAILED
            is ConnectException -> DiagnosticReason.CONNECTION_FAILED

            is java.net.UnknownServiceException -> if (isCleartextBlocked(e)) {
                DiagnosticReason.CLEARTEXT_BLOCKED
            } else {
                DiagnosticReason.CONNECTION_FAILED
            }
            else -> DiagnosticReason.CONNECTION_FAILED
        }
        return NetworkPolicyException(reason, "request failed: ${e::class.simpleName}", cause = e)
    }

    private fun isCleartextBlocked(e: java.net.UnknownServiceException): Boolean {
        val message = e.message ?: return false
        return message.contains("not permitted by network security policy", ignoreCase = true) ||
            message.contains("CLEARTEXT communication", ignoreCase = true)
    }

    private companion object {
        const val DEFAULT_PLAYBACK_USER_AGENT =
            "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
    }

    private fun Response.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {

        }
    }

    override fun openWebSocket(
        request: HardenedRequest,
        listener: HardenedWebSocketListener,
    ): HardenedWebSocketHandle {
        val addresses = try {
            resolver.resolve(request.url.host)
        } catch (e: NetworkPolicyException) {
            throw e
        } catch (e: IOException) {
            throw NetworkPolicyException(DiagnosticReason.DNS_FAILED, "websocket host unresolvable", cause = e)
        }
        val decision = networkPolicy.decide(
            request.url.host,
            addresses,
            request.url.scheme,
            trust,
            approvedHost,
        )
        if (!decision.allowed) {
            throw NetworkPolicyException(
                decision.reason ?: DiagnosticReason.PRIVATE_DESTINATION,
                "websocket destination denied by policy",
                origin = request.url.origin,
            )
        }
        val sanitized = HeaderPolicy.sanitizeInitial(request.headers)
        val socketRequest = try {
            val builder = Request.Builder()
                .url(request.url.toString())
                .get()
            if (sanitized.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                builder.header("User-Agent", DEFAULT_PLAYBACK_USER_AGENT)
            }
            sanitized.forEach { (name, value) -> builder.header(name, value) }
            builder.build()
        } catch (e: IllegalArgumentException) {
            throw NetworkPolicyException(DiagnosticReason.INVALID_URL, "url rejected by transport", cause = e)
        }
        return OkHttpWebSocketHandle(ok.newWebSocket(socketRequest, OkHttpWebSocketBridge(listener)))
    }

    override fun close() {
        ok.dispatcher.executorService.shutdown()
        ok.connectionPool.evictAll()
    }
}

class ValidatingDns(
    private val resolver: HostResolver,
    private val policy: NetworkPolicy,
    private val trust: TrustClass,
    private val approvedLanHost: String?,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = resolver.resolve(hostname)
        if (addresses.isEmpty()) throw UnknownHostException(hostname)

        val decision = policy.decide(hostname, addresses, "https", trust, approvedLanHost)
        if (!decision.allowed) {
            throw NetworkPolicyException(
                decision.reason ?: DiagnosticReason.PRIVATE_DESTINATION,
                "destination resolves to denied address class",
            )
        }
        return addresses
    }
}
