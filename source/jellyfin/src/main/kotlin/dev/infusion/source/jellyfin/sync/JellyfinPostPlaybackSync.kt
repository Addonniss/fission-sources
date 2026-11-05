package dev.infusion.source.jellyfin.sync

import dev.infusion.core.model.watch.WatchKey
import dev.infusion.source.jellyfin.auth.JellyfinSession
import dev.infusion.source.jellyfin.catalog.JellyfinCatalogPort
import dev.infusion.source.jellyfin.catalog.PostPlaybackItemState
import dev.infusion.source.jellyfin.client.ApiResult
import java.time.Instant
import kotlin.math.abs

class JellyfinPostPlaybackSync(
    private val catalog: JellyfinCatalogPort,
    private val sessionProvider: () -> JellyfinSession?,
) {

    suspend fun apply(
        watchKey: WatchKey,
        positionMs: Long,
        durationMs: Long?,
        completed: Boolean,
        occurredAt: Instant,
    ): Outcome {

        val isEpisode = watchKey.isEpisode
        val titleId = (if (isEpisode) watchKey.seriesId else watchKey.titleId)
            ?: return Outcome.Skipped(Reason.NO_CONFIDENT_MATCH)

        val session = sessionProvider() ?: return Outcome.Skipped(Reason.NO_SESSION)
        val requestingUserId = session.userId

        val item: PostPlaybackItemState = if (isEpisode) {
            catalog.postPlaybackEpisodeState(
                seriesTitleId = titleId,
                seasonNumber = watchKey.seasonNumber,
                episodeNumber = watchKey.episodeNumber,
            ) ?: return Outcome.Skipped(Reason.NO_CONFIDENT_MATCH)
        } else {
            val nativeId = catalog.nativeItemIdsForTitles(listOf(titleId))[titleId]
                ?: return Outcome.Skipped(Reason.NO_CONFIDENT_MATCH)
            catalog.serverItemState(nativeId) ?: return Outcome.Skipped(Reason.NO_SERVER_STATE)
        }

        if (sessionProvider()?.userId != requestingUserId) return Outcome.Skipped(Reason.SESSION_CHANGED)

        val serverAt = item.lastPlayedAt
        if (serverAt != null && serverAt.isAfter(occurredAt)) {
            return Outcome.Skipped(Reason.NEWER_SERVER_ACTIVITY)
        }

        if (!runtimeCompatible(localDurationMs = durationMs, serverRuntimeMs = item.runtimeMs)) {
            return Outcome.Skipped(Reason.EDITION_MISMATCH)
        }

        if (completed) {
            if (item.positionMs == 0L && item.played) return Outcome.Delivered(requests = readRequests(watchKey))
        } else if (item.positionMs == positionMs) {
            return Outcome.Delivered(requests = readRequests(watchKey))
        }

        val result = if (completed) {
            catalog.writePostPlaybackState(nativeId = item.nativeId, positionMs = 0L, played = true)
        } else {
            catalog.writePostPlaybackState(nativeId = item.nativeId, positionMs = positionMs, played = null)
        }
        return when (result) {
            is ApiResult.Ok -> Outcome.Delivered(requests = readRequests(watchKey) + 1)
            is ApiResult.Err -> Outcome.Failed(retryable = result.failure.retryable, requests = readRequests(watchKey) + 1)
        }
    }

    fun estimateRequests(): Int = 4

    private fun readRequests(watchKey: WatchKey): Int = if (watchKey.isEpisode) 3 else 1

    private fun runtimeCompatible(localDurationMs: Long?, serverRuntimeMs: Long?): Boolean {

        if (localDurationMs == null || localDurationMs <= 0L) return true
        if (serverRuntimeMs == null || serverRuntimeMs <= 0L) return true
        val tolerance = maxOf(RUNTIME_TOLERANCE_MS, (localDurationMs * RUNTIME_TOLERANCE_FRACTION).toLong())
        return abs(serverRuntimeMs - localDurationMs) <= tolerance
    }

    enum class Reason {

        NO_CONFIDENT_MATCH,
        NO_SESSION,
        NO_SERVER_STATE,
        SESSION_CHANGED,
        NEWER_SERVER_ACTIVITY,
        EDITION_MISMATCH,
    }

    sealed interface Outcome {

        data class Delivered(val requests: Int) : Outcome

        data class Failed(val retryable: Boolean, val requests: Int) : Outcome

        data class Skipped(val reason: Reason) : Outcome
    }

    companion object {

        const val RUNTIME_TOLERANCE_MS: Long = 120_000L

        const val RUNTIME_TOLERANCE_FRACTION: Double = 0.02
    }
}
