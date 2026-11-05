package dev.infusion.source.jellyfin.session

import dev.infusion.core.network.NetworkLimits
import dev.infusion.source.jellyfin.auth.JellyfinAuthenticator
import dev.infusion.source.jellyfin.auth.JellyfinSession
import dev.infusion.source.jellyfin.client.ApiResult

import dev.infusion.source.jellyfin.client.FailureKind
import dev.infusion.source.jellyfin.client.JellyfinClient
import dev.infusion.source.jellyfin.client.JellyfinFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class JellyfinSessionReporter(
    private val client: JellyfinClient,
    private val authenticator: JellyfinAuthenticator,
    private val limits: NetworkLimits = NetworkLimits.DEFAULT,
) {
    suspend fun playing(session: JellyfinSession, itemId: String, mediaSourceId: String, playSessionId: String,
        positionMs: Long, isPaused: Boolean = false, canSeek: Boolean = true, playMethod: String? = null,
        liveStreamId: String? = null, audioStreamIndex: Int? = null, subtitleStreamIndex: Int? = null): ApiResult<Unit> =
        event(session, dev.infusion.source.jellyfin.client.Endpoint.SESSIONS_PLAYING, base(itemId, mediaSourceId, playSessionId, positionMs, isPaused, canSeek,
            playMethod, liveStreamId, audioStreamIndex, subtitleStreamIndex))

    suspend fun progress(session: JellyfinSession, itemId: String, mediaSourceId: String, playSessionId: String,
        positionMs: Long, durationMs: Long?, isPaused: Boolean = false, canSeek: Boolean = true,
        playMethod: String? = null, liveStreamId: String? = null, audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null): ApiResult<Unit> =
        event(session, dev.infusion.source.jellyfin.client.Endpoint.SESSIONS_PROGRESS, base(itemId, mediaSourceId, playSessionId, positionMs, isPaused, canSeek,
            playMethod, liveStreamId, audioStreamIndex, subtitleStreamIndex))

    suspend fun ping(session: JellyfinSession, playSessionId: String): ApiResult<Unit> {
        val token = authenticator.token(session) ?: return unauthorized()
        JellyfinForensicsLog.event("ping-attempt", "attempt=${JellyfinForensicsLog.opaque(playSessionId)} endpoint=${dev.infusion.source.jellyfin.client.Endpoint.SESSIONS_PING}")
        return client.ping(playSessionId, token, limits).also { result ->
            JellyfinForensicsLog.event("ping-result", resultSummary(result))
        }
    }

    suspend fun stopped(session: JellyfinSession, itemId: String, mediaSourceId: String, playSessionId: String,
        positionMs: Long, durationMs: Long?, failed: Boolean = false, liveStreamId: String? = null): ApiResult<Unit> {
        val body = JSONObject().put("ItemId", itemId).put("MediaSourceId", mediaSourceId)
            .put("PlaySessionId", playSessionId).put("PositionTicks", ticks(positionMs)).put("Failed", failed)
        if (liveStreamId != null) body.put("LiveStreamId", liveStreamId)
        JellyfinForensicsLog.event(
            "stopped-call",
            "attempt=${JellyfinForensicsLog.opaque(playSessionId)} item=${JellyfinForensicsLog.opaque(itemId)} " +
                "source=${JellyfinForensicsLog.opaque(mediaSourceId)} positionMs=$positionMs " +
                "positionTicks=${ticks(positionMs)} durationMs=$durationMs failed=$failed",
        )
        return event(session, dev.infusion.source.jellyfin.client.Endpoint.SESSIONS_STOPPED, body).also { result ->
            JellyfinForensicsLog.event("stopped-result", resultSummary(result))
        }
    }

    private suspend fun event(session: JellyfinSession, endpoint: String, body: JSONObject): ApiResult<Unit> {
        val token = authenticator.token(session) ?: return unauthorized()
        return client.sessionEvent(endpoint, token, body, limits)
    }

    private fun resultSummary(result: ApiResult<Unit>): String = when (result) {
        is ApiResult.Ok -> "endpoint-result=status:${result.status} class=Ok"
        is ApiResult.Err -> "endpoint-result=status:${result.failure.status ?: "none"} class=Err failure=${result.failure.kind}"
    }

    private fun base(itemId: String, mediaSourceId: String, playSessionId: String, positionMs: Long,
        paused: Boolean, canSeek: Boolean, playMethod: String?, liveStreamId: String?, audio: Int?, subtitle: Int?): JSONObject =
        JSONObject().put("ItemId", itemId).put("MediaSourceId", mediaSourceId).put("PlaySessionId", playSessionId)
            .put("PositionTicks", ticks(positionMs)).put("IsPaused", paused).put("IsMuted", false).put("CanSeek", canSeek)
            .apply { if (playMethod != null) put("PlayMethod", playMethod) }
            .apply { if (liveStreamId != null) put("LiveStreamId", liveStreamId) }
            .apply { if (audio != null) put("AudioStreamIndex", audio) }
            .apply { if (subtitle != null) put("SubtitleStreamIndex", subtitle) }

    internal fun ticks(positionMs: Long): Long = positionMs * 10_000L
    private fun unauthorized(): ApiResult.Err<Unit> = ApiResult.Err(
        JellyfinFailure(FailureKind.UNAUTHORIZED, null, "stored token missing", retryable = false),
    )
}

data class JellyfinActiveSessionIdentity(
    val itemId: String,
    val mediaSourceId: String,
    val playSessionId: String,
    val playMethod: String,
    val liveStreamId: String? = null,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
)

data class JellyfinPlayerSnapshot(val positionMs: Long, val isPaused: Boolean, val canSeek: Boolean, val durationMs: Long? = null)

class JellyfinActivePlaybackReporter(
    private val reporter: JellyfinSessionReporter,
    private val session: JellyfinSession,
    private val identity: JellyfinActiveSessionIdentity,
    private val scope: CoroutineScope,
    private val progressIntervalMs: Long = 5_000L,
    private val pingIntervalMs: Long = 60_000L,
) {
    private val mutex = Mutex()
    private var started = false
    private var terminal = false
    private var pingJob: Job? = null
    private var lastProgressMs: Long? = null

    suspend fun onActive(snapshot: JellyfinPlayerSnapshot) = mutex.withLock {
        if (terminal) {
            JellyfinForensicsLog.event("progress-after-terminal", "attempt=${JellyfinForensicsLog.opaque(identity.playSessionId)} kind=active")
            return
        }
        if (!started) {
            reporter.playing(session, snapshot.asArgs(), paused = snapshot.isPaused)
            started = true
            startPing()
        }
        if (lastProgressMs == null || snapshot.positionMs - (lastProgressMs ?: 0L) >= progressIntervalMs) {
            progressLocked(snapshot)
        }
    }

    suspend fun onPause(snapshot: JellyfinPlayerSnapshot) = reportImmediate(snapshot.copy(isPaused = true))
    suspend fun onUnpause(snapshot: JellyfinPlayerSnapshot) = reportImmediate(snapshot.copy(isPaused = false))
    suspend fun onSeek(snapshot: JellyfinPlayerSnapshot) = reportImmediate(snapshot)

    suspend fun stop(snapshot: JellyfinPlayerSnapshot, failed: Boolean = false) = mutex.withLock {
        JellyfinForensicsLog.event(
            "terminal-begin",
            "attempt=${JellyfinForensicsLog.opaque(identity.playSessionId)} positionMs=${snapshot.positionMs} " +
                "paused=${snapshot.isPaused} terminalAlready=$terminal pingCancelled=${pingJob != null}",
        )
        if (terminal) return
        terminal = true
        pingJob?.cancel(); pingJob = null
        JellyfinForensicsLog.event("terminal-retired", "attempt=${JellyfinForensicsLog.opaque(identity.playSessionId)} pingCancelled=true started=$started")
        if (started) {

            JellyfinForensicsLog.event(
                "terminal-stop-payload",
                "attempt=${JellyfinForensicsLog.opaque(identity.playSessionId)} " +
                    "item=${JellyfinForensicsLog.opaque(identity.itemId)} " +
                    "source=${JellyfinForensicsLog.opaque(identity.mediaSourceId)} " +
                    "positionMs=${snapshot.positionMs} positionTicks=${reporter.ticks(snapshot.positionMs)} " +
                    "durationMs=${snapshot.durationMs} failed=$failed lastProgressMs=$lastProgressMs started=$started",
            )
            reporter.stopped(session, identity.itemId, identity.mediaSourceId, identity.playSessionId,
                snapshot.positionMs, snapshot.durationMs, failed, identity.liveStreamId)
        }
    }

    private suspend fun reportImmediate(snapshot: JellyfinPlayerSnapshot) = mutex.withLock {
        if (terminal) {
            JellyfinForensicsLog.event("progress-after-terminal", "attempt=${JellyfinForensicsLog.opaque(identity.playSessionId)} kind=immediate")
            return
        }
        if (!started) return
        progressLocked(snapshot)
    }

    private suspend fun progressLocked(snapshot: JellyfinPlayerSnapshot) {
        reporter.progress(session, snapshot.asArgs(), paused = snapshot.isPaused)
        lastProgressMs = snapshot.positionMs
    }

    private fun startPing() {
        pingJob = scope.launch {
            while (isActive) {
                delay(pingIntervalMs)
                mutex.withLock {
                    if (!terminal && started) reporter.ping(session, identity.playSessionId)
                    else if (terminal) JellyfinForensicsLog.event("ping-after-terminal", "attempt=${JellyfinForensicsLog.opaque(identity.playSessionId)}")
                }
            }
        }
    }

    private fun JellyfinPlayerSnapshot.asArgs(): ReporterArgs = ReporterArgs(
        identity, positionMs, durationMs, canSeek,
    )
    private suspend fun JellyfinSessionReporter.playing(session: JellyfinSession, args: ReporterArgs, paused: Boolean) =
        playing(session, identity.itemId, identity.mediaSourceId, identity.playSessionId, args.positionMs, paused,
            args.canSeek, identity.playMethod, identity.liveStreamId, identity.audioStreamIndex, identity.subtitleStreamIndex)
    private suspend fun JellyfinSessionReporter.progress(session: JellyfinSession, args: ReporterArgs, paused: Boolean) =
        progress(session, identity.itemId, identity.mediaSourceId, identity.playSessionId, args.positionMs, args.durationMs, paused,
            args.canSeek, identity.playMethod, identity.liveStreamId, identity.audioStreamIndex, identity.subtitleStreamIndex)
    private data class ReporterArgs(val identity: JellyfinActiveSessionIdentity, val positionMs: Long, val durationMs: Long?, val canSeek: Boolean)
}
