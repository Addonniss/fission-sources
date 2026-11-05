package dev.infusion.core.sync.tracking

import dev.infusion.core.domain.sync.WatchOutbox
import dev.infusion.core.domain.tracking.TrackingActivityStamp
import dev.infusion.core.domain.tracking.TrackingInboundCursorStore
import dev.infusion.core.domain.tracking.TrackingInboundIdentityCompletionPort
import dev.infusion.core.domain.tracking.TrackingInboundIdentityPort
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.domain.tracking.TrackingInboundReadPort
import dev.infusion.core.domain.tracking.TrackingInboundSkipReason
import dev.infusion.core.domain.tracking.TrackingInboundSyncResult
import dev.infusion.core.domain.tracking.TrackingPlaybackRequest
import dev.infusion.core.domain.tracking.TrackingRuntimeSourcePort
import dev.infusion.core.domain.tracking.TrackingSavedPlayback
import dev.infusion.core.domain.tracking.TrackingWatchKeyLookup
import dev.infusion.core.model.watch.WatchEvent
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.sync.WatchSyncCoordinator
import java.time.Instant
import kotlin.math.roundToLong

class TrackingInboundImporter(

    private val providerId: String,
    private val reader: TrackingInboundReadPort,
    private val identity: TrackingInboundIdentityPort,

    private val identityCompletion: TrackingInboundIdentityCompletionPort? = null,
    private val runtime: TrackingRuntimeSourcePort,
    private val cursorStore: TrackingInboundCursorStore,
    private val outbox: WatchOutbox,
    private val coordinator: WatchSyncCoordinator,

    private val maxPages: Int = DEFAULT_MAX_PAGES,

    private val pageLimit: Int = DEFAULT_PAGE_LIMIT,

    private val inboundGuard: (WatchKey) -> Boolean = { false },

    private val onPlaybackRead: (startedAt: Instant, completed: Boolean, seen: Set<WatchKey>) -> Unit =
        { _, _, _ -> },
) {

    suspend fun refresh(): TrackingInboundSyncResult {
        val startedAt = Instant.now()
        val gateRead = reader.readActivityStamp()
        val stamp = when (gateRead) {
            is TrackingInboundOutcome.Ok -> gateRead.value

            is TrackingInboundOutcome.Unauthorized -> return gateFailure(gateRead.reason)
            is TrackingInboundOutcome.Unavailable -> return gateFailure(gateRead.reason)
            is TrackingInboundOutcome.Rejected -> return gateFailure(gateRead.reason)
        }
        return refreshPlaybacks(stamp, startedAt)
    }

    private suspend fun refreshPlaybacks(
        stamp: TrackingActivityStamp,
        startedAt: Instant,
    ): TrackingInboundSyncResult {
        val saved = cursorStore.playbackGate(providerId)
        if (!gateMoved(stamp.playback, saved)) {

            return TrackingInboundSyncResult(gateUnchanged = true, gate = saved, watchedActivity = stamp.watched)
        }
        val skipped = LinkedHashMap<TrackingInboundSkipReason, Int>()
        val seen = HashSet<String>()
        val seenUnits = HashSet<WatchKey>()
        var imported = 0
        var pages = 0
        var dateFrom: Instant? = null
        var failure: String? = null
        while (pages < maxPages) {
            val pageRead = reader.readSavedPlaybacks(
                TrackingPlaybackRequest(dateFrom = dateFrom, limit = pageLimit),
            )
            val page = when (pageRead) {
                is TrackingInboundOutcome.Ok -> pageRead.value
                is TrackingInboundOutcome.Unauthorized -> {
                    failure = pageRead.reason
                    break
                }
                is TrackingInboundOutcome.Unavailable -> {
                    failure = pageRead.reason
                    break
                }
                is TrackingInboundOutcome.Rejected -> {
                    failure = pageRead.reason
                    break
                }
            }
            pages++
            count(skipped, TrackingInboundSkipReason.MALFORMED, page.malformed)
            for (item in page.items) {
                if (!seen.add(dedupeKey(item))) {
                    count(skipped, TrackingInboundSkipReason.DUPLICATE, 1)
                    continue
                }
                if (importOne(item, skipped, seenUnits)) imported++
            }
            if (!page.truncated) break
            val newest = page.items.mapNotNull { it.pausedAt }.maxOrNull()
            if (newest == null || newest == dateFrom) {

                break
            }
            dateFrom = newest
        }
        if (failure == null) {

            cursorStore.savePlaybackGate(providerId, stamp.playback)
        }

        if (failure == null) {
            runCatching { onPlaybackRead(startedAt, true, seenUnits) }
        }
        return TrackingInboundSyncResult(
            gateUnchanged = false,
            gate = if (failure == null) stamp.playback ?: saved else saved,
            imported = imported,
            skipped = skipped,
            pages = pages,
            watchedActivity = stamp.watched,
            failure = failure,
        )
    }

    private fun gateMoved(playback: Instant?, saved: Instant?): Boolean {
        if (playback == null) return false
        return saved == null || playback.isAfter(saved)
    }

    private suspend fun importOne(
        item: TrackingSavedPlayback,
        skipped: MutableMap<TrackingInboundSkipReason, Int>,
        seenUnits: MutableSet<WatchKey>,
    ): Boolean {
        val pausedAt = item.pausedAt
        if (pausedAt == null) {
            count(skipped, TrackingInboundSkipReason.NO_OBSERVATION_TIME, 1)
            return false
        }
        val watchKey = when (val lookup = identity.lookUp(
            ids = item.externalIds,
            kind = item.kind,
            seasonNumber = item.seasonNumber,
            episodeNumber = item.episodeNumber,
        )) {
            is TrackingWatchKeyLookup.Resolved -> lookup.watchKey
            is TrackingWatchKeyLookup.Unproven -> {

                val completed = identityCompletion?.complete(
                    ids = item.externalIds,
                    kind = item.kind,
                    seasonNumber = item.seasonNumber,
                    episodeNumber = item.episodeNumber,
                    title = item.title,
                    year = item.year,
                )
                if (completed !is TrackingWatchKeyLookup.Resolved) {
                    count(skipped, TrackingInboundSkipReason.UNPROVEN_IDENTITY, 1)
                    return false
                }
                completed.watchKey
            }
            is TrackingWatchKeyLookup.Ambiguous -> {
                count(skipped, TrackingInboundSkipReason.AMBIGUOUS_IDENTITY, 1)
                return false
            }
        }
        seenUnits += watchKey

        if (inboundGuard(watchKey)) {
            count(skipped, TrackingInboundSkipReason.RESUME_RESET_GUARD, 1)
            return false
        }

        val runtimeMs = runtime.runtimeMsFor(watchKey)?.takeIf { it > 0L }
        if (runtimeMs == null) {
            count(skipped, TrackingInboundSkipReason.UNPROVEN_RUNTIME, 1)
            return false
        }
        val positionMs = ((item.progressPercent / 100.0) * runtimeMs.toDouble())
            .roundToLong()
            .coerceIn(0L, runtimeMs)
        if (positionMs <= 0L) {

            count(skipped, TrackingInboundSkipReason.ZERO_POSITION, 1)
            return false
        }
        if (InboundPrecedence.newerUnsentLocalWork(outbox, providerId, watchKey, pausedAt)) {
            count(skipped, TrackingInboundSkipReason.UNSENT_LOCAL_WORK, 1)
            return false
        }
        coordinator.importObservation(
            connectorId = providerId,
            watchKey = watchKey,
            kind = WatchEvent.Kind.PROGRESS,
            observedAt = pausedAt,
            positionMs = positionMs,
            durationMs = runtimeMs,
            cursor = item.providerRecordId,
        )
        return true
    }

    private fun dedupeKey(item: TrackingSavedPlayback): String = buildString {
        append(item.providerRecordId ?: "-")
        append('|')
        append(item.kind)
        append('|')
        append(item.externalIds.tmdbId)
        append('|')
        append(item.externalIds.imdbId)
        append('|')
        append(item.externalIds.tvdbId)
        append('|')
        append(item.seasonNumber)
        append('x')
        append(item.episodeNumber)
        append('|')
        append(item.progressPercent)
        append('|')
        append(item.pausedAt)
    }

    private fun count(map: MutableMap<TrackingInboundSkipReason, Int>, reason: TrackingInboundSkipReason, by: Int) {
        if (by <= 0) return
        map[reason] = (map[reason] ?: 0) + by
    }

    private fun gateFailure(reason: String): TrackingInboundSyncResult = TrackingInboundSyncResult(
        gate = cursorStore.playbackGate(providerId),
        failure = reason,
    )

    companion object {

        const val DEFAULT_MAX_PAGES: Int = 5

        const val DEFAULT_PAGE_LIMIT: Int = 100
    }
}

internal object InboundPrecedence {

    fun newerUnsentLocalWork(
        outbox: WatchOutbox,
        providerId: String,
        watchKey: WatchKey,
        observedAt: Instant,
    ): Boolean = outbox.allEntries().any { entry ->
        entry.connectorId == providerId &&
            (entry.state == dev.infusion.core.model.sync.OutboxState.PENDING ||
                entry.state == dev.infusion.core.model.sync.OutboxState.FAILED) &&
            (entry.payload as? dev.infusion.core.model.sync.OutboxPayload.WatchAction)
                ?.let { it.watchKey == watchKey && it.occurredAt.isAfter(observedAt) } == true
    }
}
