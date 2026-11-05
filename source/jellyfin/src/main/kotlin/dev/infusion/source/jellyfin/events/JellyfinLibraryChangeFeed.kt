package dev.infusion.source.jellyfin.events

import dev.infusion.core.domain.refresh.LibraryChangeFeed
import dev.infusion.core.domain.refresh.LibraryChangeHint
import dev.infusion.core.domain.refresh.LibraryFeedStatus
import dev.infusion.core.network.HardenedRequest
import dev.infusion.core.network.HardenedWebSocketFactory
import dev.infusion.core.network.HardenedWebSocketHandle
import dev.infusion.core.network.HardenedWebSocketListener
import dev.infusion.core.network.NetworkLimits
import dev.infusion.source.jellyfin.auth.JellyfinSession
import dev.infusion.source.jellyfin.client.JellyfinClient
import java.util.concurrent.atomic.AtomicBoolean
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

class JellyfinLibraryChangeFeed(
    private val client: JellyfinClient,

    private val serviceInstance: String,
    private val sessionProvider: () -> JellyfinSession?,
    private val tokenProvider: (JellyfinSession) -> String?,
    private val webSocketFactory: () -> HardenedWebSocketFactory?,
    parentScope: CoroutineScope,
    private val limits: NetworkLimits = NetworkLimits.DEFAULT,
    private val onHint: (LibraryChangeHint) -> Unit,
    private val onStatus: (LibraryFeedStatus) -> Unit,
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
    }

    private suspend fun connectLoop() {
        var attempt = 0
        while (feedScope.isActive) {
            val session = sessionProvider()
            val token = session?.let(tokenProvider)
            val factory = webSocketFactory()
            if (session == null || token.isNullOrBlank() || factory == null) {

                report(LibraryFeedStatus.UNSUPPORTED)
                return
            }
            report(LibraryFeedStatus.CONNECTING)

            val frames = Channel<Frame>(capacity = INBOUND_QUEUE_CAPACITY)
            val listener = object : HardenedWebSocketListener {
                override fun onOpen() {
                    frames.trySend(Frame.Open)
                }

                override fun onTextMessage(text: String) {
                    if (text.length > MAX_MESSAGE_CHARS) return
                    if (frames.trySend(Frame.Text(text)).isFailure) overflowed.set(true)
                }

                override fun onClosed(failure: Throwable?) {
                    frames.close()
                }
            }

            val opened = try {
                factory.openWebSocket(
                    HardenedRequest(
                        url = client.socketUrl(),
                        method = "GET",
                        headers = client.authHeaders(token),
                        limits = limits,
                        sensitiveHeaders = JellyfinClient.JELLYFIN_SENSITIVE_HEADERS,
                    ),
                    listener,
                )
            } catch (e: Exception) {
                null
            }

            if (opened == null) {
                report(LibraryFeedStatus.DISCONNECTED)
                attempt += 1
                delay(backoffMs(attempt))
                continue
            }

            handle = opened
            try {
                consume(opened, frames) { attempt = 0 }
            } finally {
                runCatching { opened.cancelNow() }
                handle = null
            }

            if (!feedScope.isActive) return
            report(LibraryFeedStatus.DISCONNECTED)
            attempt += 1
            delay(backoffMs(attempt))
        }
    }

    private suspend fun consume(
        socket: HardenedWebSocketHandle,
        frames: Channel<Frame>,
        onConnected: () -> Unit,
    ) {
        var announcedConnected = false
        while (feedScope.isActive) {
            val result = withTimeoutOrNull(KEEPALIVE_SEND_MS) { frames.receiveCatching() }
            if (result == null) {

                socket.send(KEEPALIVE_TEXT)
            } else if (result.isClosed) {
                return
            } else {
                when (val frame = result.getOrNull()) {
                    null -> return
                    Frame.Open -> {
                        if (!announcedConnected) {
                            announcedConnected = true
                            onConnected()
                            report(LibraryFeedStatus.CONNECTED)
                        }

                        onHint(scopeHint(clock()))
                    }
                    is Frame.Text -> handleMessage(frame.value)
                }
            }
            if (overflowed.getAndSet(false)) {

                onHint(scopeHint(clock()))
            }
        }
    }

    private fun handleMessage(text: String) {
        val envelope = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (envelope.optString("MessageType")) {
            "LibraryChanged" -> emitLibraryChanged(envelope)

            else -> Unit
        }
    }

    private fun emitLibraryChanged(envelope: JSONObject) {
        val data = envelope.optJSONObject("Data")
        val changed = LinkedHashSet<String>()
        val removed = LinkedHashSet<String>()
        if (data != null) {
            addIds(data, "ItemsAdded", changed)
            addIds(data, "ItemsUpdated", changed)
            addIds(data, "ItemsRemoved", removed)
        }

        val folderScope = data != null &&
            (hasEntries(data, "FoldersAddedTo") || hasEntries(data, "FoldersRemovedFrom") ||
                hasEntries(data, "CollectionFolders"))

        val overBound = changed.size > MAX_IDS_PER_HINT || removed.size > MAX_IDS_PER_HINT
        if (changed.size > MAX_IDS_PER_HINT) changed.clear()
        if (removed.size > MAX_IDS_PER_HINT) removed.clear()
        onHint(
            LibraryChangeHint(
                serviceId = SERVICE_ID,
                serviceInstance = serviceInstance,
                changedNativeIds = changed,
                removedNativeIds = removed,
                scopeOnly = folderScope || overBound || (changed.isEmpty() && removed.isEmpty()),
                observedAtMs = clock(),
            ),
        )
    }

    private fun scopeHint(atMs: Long): LibraryChangeHint = LibraryChangeHint(
        serviceId = SERVICE_ID,
        serviceInstance = serviceInstance,
        scopeOnly = true,
        observedAtMs = atMs,
    )

    private fun report(status: LibraryFeedStatus) {
        dev.infusion.source.jellyfin.session.JellyfinForensicsLog.event(
            "live-refresh-feed",
            "service=$SERVICE_ID status=${status.name}",
        )
        onStatus(status)
    }

    private fun addIds(data: JSONObject, key: String, into: MutableSet<String>) {
        val arr = data.optJSONArray(key) ?: return
        for (i in 0 until arr.length()) {
            val value = arr.optString(i)
            if (value.isNotBlank()) into.add(value)
        }
    }

    private fun hasEntries(data: JSONObject, key: String): Boolean =
        (data.optJSONArray(key)?.length() ?: 0) > 0

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
        const val SERVICE_ID = "jellyfin"

        private const val KEEPALIVE_TEXT = "{\"MessageType\":\"KeepAlive\"}"

        private const val KEEPALIVE_SEND_MS = 25_000L

        private const val BACKOFF_BASE_MS = 1_000L
        private const val BACKOFF_CAP_MS = 30_000L
        private const val BACKOFF_JITTER_MS = 250L

        private const val INBOUND_QUEUE_CAPACITY = 128

        private const val MAX_MESSAGE_CHARS = 512 * 1024

        private const val MAX_IDS_PER_HINT = 500
    }
}
