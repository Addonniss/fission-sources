package dev.infusion.source.acquisition.events

import dev.infusion.core.domain.acquisition.AcquisitionConnection
import dev.infusion.core.domain.acquisition.AcquisitionFailureKind
import dev.infusion.core.domain.refresh.LibraryChangeFeed
import dev.infusion.core.domain.refresh.LibraryChangeHint
import dev.infusion.core.domain.refresh.LibraryFeedStatus
import dev.infusion.core.network.HardenedRequest
import dev.infusion.core.network.HardenedWebSocketFactory
import dev.infusion.core.network.HardenedWebSocketHandle
import dev.infusion.core.network.HardenedWebSocketListener
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.network.NetworkPolicyException
import dev.infusion.core.network.OkHttpHardenedClient
import dev.infusion.source.acquisition.AcquisitionClient
import dev.infusion.source.acquisition.AcquisitionHttp
import dev.infusion.source.acquisition.acquisitionTransport
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal class SignalRLibraryChangeFeed(
    private val serviceId: String,
    private val serviceInstance: String,
    private val http: AcquisitionHttp,
    private val webSocketFactory: () -> HardenedWebSocketFactory?,
    parentScope: CoroutineScope,
    private val limits: NetworkLimits = AcquisitionClient.jsonLimits,
    private val onHint: (LibraryChangeHint) -> Unit,
    private val onStatus: (LibraryFeedStatus) -> Unit,

    private val onDiagnostic: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,

    private val backoffBaseMs: Long = BACKOFF_BASE_MS,
    private val backoffCapMs: Long = BACKOFF_CAP_MS,
) : LibraryChangeFeed {

    private val feedJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val feedScope = CoroutineScope(parentScope.coroutineContext + feedJob)

    private val started = AtomicBoolean(false)

    private val overflowed = AtomicBoolean(false)

    @Volatile
    private var handle: HardenedWebSocketHandle? = null

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        feedScope.launch(Dispatchers.IO) { connectLoop() }
    }

    override fun close() {
        runCatching { handle?.cancelNow() }
        handle = null
        feedJob.cancel()

        runCatching { http.close() }
    }

    private suspend fun connectLoop() {
        var attempt = 0
        while (feedScope.isActive) {
            val factory = webSocketFactory()
            if (factory == null) {
                onDiagnostic("no websocket transport")
                onStatus(LibraryFeedStatus.UNSUPPORTED)
                return
            }
            onStatus(LibraryFeedStatus.CONNECTING)

            val connected = when (val negotiated = negotiate()) {
                Negotiation.Unsupported -> {
                    onDiagnostic("negotiate unsupported")
                    onStatus(LibraryFeedStatus.UNSUPPORTED)
                    return
                }
                Negotiation.Failed -> {
                    onDiagnostic("negotiate failed")
                    onStatus(LibraryFeedStatus.DISCONNECTED)
                    false
                }
                is Negotiation.Ok -> {

                    val hubUrl = http.resolveFinalUrl(HUB_PATH) ?: http.urlFor(HUB_PATH)
                    connectHub(factory, hubUrl, negotiated.token)
                }
            }

            if (!feedScope.isActive) return
            if (connected) attempt = 0
            attempt += 1
            delay(backoffMs(attempt))
        }
    }

    private suspend fun connectHub(
        factory: HardenedWebSocketFactory,
        hubUrl: dev.infusion.core.network.CanonicalUrl,
        connectionToken: String,
    ): Boolean {
        val frames = Channel<Frame>(capacity = INBOUND_QUEUE_CAPACITY)
        val closeFailure = AtomicReference<Throwable?>(null)
        val listener = object : HardenedWebSocketListener {
            override fun onOpen() {
                frames.trySend(Frame.Open)
            }

            override fun onTextMessage(text: String) {
                if (text.length > MAX_MESSAGE_CHARS) return
                if (frames.trySend(Frame.Text(text)).isFailure) overflowed.set(true)
            }

            override fun onClosed(failure: Throwable?) {
                closeFailure.set(failure)
                frames.close()
            }
        }
        val opened = try {
            factory.openWebSocket(
                HardenedRequest(
                    url = http.withQuery(hubUrl, "id", connectionToken),
                    method = "GET",
                    headers = mapOf(http.authHeader()),
                    limits = limits,
                    sensitiveHeaders = AcquisitionClient.SENSITIVE_HEADERS,
                ),
                listener,
            )
        } catch (e: Exception) {
            onDiagnostic("open failed ${diagnosticOf(e)}")
            null
        }
        if (opened == null) {
            onStatus(LibraryFeedStatus.DISCONNECTED)
            return false
        }
        handle = opened
        val connected = try {
            consume(opened, frames)
        } finally {
            runCatching { opened.cancelNow() }
            handle = null
        }
        if (!connected) {
            onDiagnostic("closed ${diagnosticOf(closeFailure.get())}")
        }
        onStatus(LibraryFeedStatus.DISCONNECTED)
        return connected
    }

    private suspend fun consume(
        socket: HardenedWebSocketHandle,
        frames: Channel<Frame>,
    ): Boolean {
        val buffer = StringBuilder()
        var handshakeDone = false
        var announced = false
        while (feedScope.isActive) {
            val result = withTimeoutOrNull(PING_INTERVAL_MS) { frames.receiveCatching() }
            if (result == null) {

                if (handshakeDone) socket.send(PING_TEXT)
            } else if (result.isClosed) {
                return announced
            } else {
                when (val frame = result.getOrNull()) {
                    null -> return announced
                    Frame.Open -> socket.send(HANDSHAKE_TEXT)
                    is Frame.Text -> {
                        buffer.append(frame.value)
                        while (true) {
                            val sep = buffer.indexOf(RECORD_SEPARATOR)
                            if (sep < 0) break
                            val segment = buffer.substring(0, sep)
                            buffer.delete(0, sep + 1)
                            if (segment.isBlank()) continue
                            when (handleSegment(segment)) {
                                Segment.Closed -> return announced
                                Segment.Handshake -> {
                                    if (!handshakeDone) {
                                        handshakeDone = true
                                        if (!announced) {
                                            announced = true
                                            onStatus(LibraryFeedStatus.CONNECTED)
                                        }
                                        onHint(scopeHint())
                                    }
                                }
                                Segment.HandshakeError -> return announced
                                Segment.Event -> onHint(scopeHint())
                                Segment.Ignored -> Unit
                            }
                        }
                    }
                }
            }
            if (overflowed.getAndSet(false)) onHint(scopeHint())
        }
        return announced
    }

    private enum class Segment { Handshake, HandshakeError, Event, Ignored, Closed }

    private fun handleSegment(segment: String): Segment {
        val obj = runCatching { JSONObject(segment) }.getOrNull()
            ?:
            return Segment.Ignored
        if (!obj.has("type")) {

            return if (obj.has("error")) Segment.HandshakeError else Segment.Handshake
        }
        return when (obj.optInt("type", -1)) {
            TYPE_INVOCATION -> Segment.Event
            TYPE_CLOSE -> Segment.Closed
            TYPE_PING, TYPE_COMPLETION -> Segment.Ignored
            else -> Segment.Ignored
        }
    }

    private sealed interface Negotiation {
        data class Ok(val token: String) : Negotiation

        data object Unsupported : Negotiation

        data object Failed : Negotiation
    }

    private suspend fun negotiate(): Negotiation =
        when (val result = http.post(NEGOTIATE_PATH, "")) {
            is AcquisitionHttp.Result.Err -> when (result.kind) {
                AcquisitionFailureKind.NOT_FOUND,
                AcquisitionFailureKind.UNAUTHORIZED,
                AcquisitionFailureKind.FORBIDDEN,
                AcquisitionFailureKind.POLICY,
                -> Negotiation.Unsupported
                else -> Negotiation.Failed
            }
            is AcquisitionHttp.Result.Ok -> {
                val obj = runCatching { JSONObject(result.body) }.getOrNull()
                    ?: return Negotiation.Failed
                val transports = obj.optJSONArray("availableTransports")
                val supportsWebSockets = transports == null ||
                    (0 until transports.length()).any {
                        transports.optJSONObject(it)?.optString("transport") == "WebSockets"
                    }
                if (!supportsWebSockets) return Negotiation.Unsupported
                val token = obj.optString("connectionToken").ifBlank { obj.optString("connectionId") }
                if (token.isBlank()) Negotiation.Failed else Negotiation.Ok(token)
            }
        }

    private fun scopeHint(): LibraryChangeHint = LibraryChangeHint(
        serviceId = serviceId,
        serviceInstance = serviceInstance,
        scopeOnly = true,
        observedAtMs = clock(),
    )

    private fun diagnosticOf(failure: Throwable?): String {
        if (failure == null) return "none"
        val reason = (failure as? NetworkPolicyException)?.let { ":${it.reason.name}" }.orEmpty()
        val name = failure::class.simpleName ?: "error"
        val message = failure.message?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return "$name$reason$message"
    }

    private fun backoffMs(attempt: Int): Long {
        val exponential = backoffBaseMs shl min(attempt, 5)
        val bounded = min(exponential, backoffCapMs)
        return bounded + random.nextLong(0L, BACKOFF_JITTER_MS + 1)
    }

    private sealed interface Frame {
        data object Open : Frame
        data class Text(val value: String) : Frame
    }

    companion object {
        const val HUB_PATH = "/signalr/messages"

        private const val NEGOTIATE_PATH = "$HUB_PATH/negotiate?negotiateVersion=1"

        private const val RECORD_SEPARATOR = '\u001e'
        private const val HANDSHAKE_TEXT = "{\"protocol\":\"json\",\"version\":1}\u001e"
        private const val PING_TEXT = "{\"type\":6}\u001e"

        private const val TYPE_INVOCATION = 1
        private const val TYPE_COMPLETION = 3
        private const val TYPE_PING = 6
        private const val TYPE_CLOSE = 7

        private const val PING_INTERVAL_MS = 15_000L

        private const val BACKOFF_BASE_MS = 1_000L
        private const val BACKOFF_CAP_MS = 30_000L
        private const val BACKOFF_JITTER_MS = 250L

        private const val INBOUND_QUEUE_CAPACITY = 128
        private const val MAX_MESSAGE_CHARS = 512 * 1024
    }
}

fun signalRLibraryChangeFeed(
    connection: AcquisitionConnection,
    apiKey: String,
    serviceInstance: String,
    parentScope: CoroutineScope,
    onHint: (LibraryChangeHint) -> Unit,
    onStatus: (LibraryFeedStatus) -> Unit,
    onDiagnostic: ((String) -> Unit)? = null,
    transportFactory: (() -> OkHttpHardenedClient)? = null,
    backoffBaseMs: Long = 1_000L,
    backoffCapMs: Long = 30_000L,
): Result<LibraryChangeFeed> {
    if (apiKey.isBlank()) return Result.failure(IllegalArgumentException("api key must not be blank"))
    return acquisitionTransport(connection.url, transportFactory).map { (url, transport) ->
        SignalRLibraryChangeFeed(
            serviceId = connection.serviceId,
            serviceInstance = serviceInstance,
            http = AcquisitionHttp(url, transport, apiKey),
            webSocketFactory = { transport as? HardenedWebSocketFactory },
            parentScope = parentScope,
            onHint = onHint,
            onStatus = onStatus,
            onDiagnostic = onDiagnostic ?: {},
            backoffBaseMs = backoffBaseMs,
            backoffCapMs = backoffCapMs,
        )
    }
}
