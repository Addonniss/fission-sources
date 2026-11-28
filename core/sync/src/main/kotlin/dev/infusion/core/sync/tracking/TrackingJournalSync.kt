package dev.infusion.core.sync.tracking

import dev.infusion.core.domain.tracking.TrackingChangeFeedCategory
import dev.infusion.core.domain.tracking.TrackingChangeFeedCheckpoint
import dev.infusion.core.domain.tracking.TrackingChangeFeedCheckpointStore
import dev.infusion.core.domain.tracking.TrackingChangeFeedEntry
import dev.infusion.core.domain.tracking.TrackingChangeFeedOutcome
import dev.infusion.core.domain.tracking.TrackingChangeFeedPage
import dev.infusion.core.domain.tracking.TrackingChangeFeedPort
import dev.infusion.core.domain.tracking.TrackingChangeFeedRemoval
import dev.infusion.core.domain.tracking.TrackingChangeFeedRequest
import dev.infusion.core.domain.tracking.TrackingChangeFeedStatus
import dev.infusion.core.domain.tracking.TrackingInboundIdentityCompletionPort
import dev.infusion.core.domain.tracking.TrackingInboundIdentityPort
import dev.infusion.core.domain.tracking.TrackingMediaKind
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryCoverageRecord
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryCoverageStore
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryCursorStore
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryUnit
import dev.infusion.core.domain.tracking.TrackingWatchKeyLookup
import dev.infusion.core.model.watch.WatchEvent
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.sync.WatchSyncCoordinator
import java.time.Instant

class TrackingJournalSync(

    private val providerId: String,

    private val feed: TrackingChangeFeedPort,
    private val identity: TrackingInboundIdentityPort,

    private val identityCompletion: TrackingInboundIdentityCompletionPort? = null,

    private val cursorStore: TrackingWatchedHistoryCursorStore,

    private val checkpointStore: TrackingChangeFeedCheckpointStore,
    private val coordinator: WatchSyncCoordinator,

    private val isAlreadyImported: (WatchKey, Instant, String?) -> Boolean = { _, _, _ -> false },

    private val coverageStore: TrackingWatchedHistoryCoverageStore? = null,

    private val accountFingerprint: (() -> String?)? = null,

    private val maxPages: Int = TrackingChangeFeedRequest.MAX_PAGES_PER_SWEEP,

    private val pageSize: Int? = null,

    private val now: () -> Instant = Instant::now,
) {

    suspend fun sync(
        units: List<TrackingWatchedHistoryUnit> = listOf(
            TrackingWatchedHistoryUnit.MOVIE,
            TrackingWatchedHistoryUnit.EPISODE,
        ),
    ): TrackingJournalSyncResult {
        val served = units.filter { it in feed.servesUnits }.toSet()
        val owed = units.filterNot { it in served }.toSet()
        if (!feed.accountConnected()) {
            return TrackingJournalSyncResult(
                providerId = providerId,
                state = TrackingJournalSyncState.UNAUTHORIZED,
                failure = NO_AUTHORIZATION,
                unitsServedByJournal = served,

                unitsOwedToHistory = owed,
            )
        }
        if (served.isEmpty()) {

            return TrackingJournalSyncResult(
                providerId = providerId,
                state = TrackingJournalSyncState.NOT_ADOPTED,
                failure = NOTHING_TO_SERVE,
                unitsServedByJournal = emptySet(),
                unitsOwedToHistory = units.toSet(),
            )
        }

        val fingerprint = runCatching { accountFingerprint?.invoke() }.getOrNull()
        var checkpoint = runCatching { checkpointStore.checkpoint(providerId) }.getOrNull()

        if (checkpoint != null && fingerprint != null &&
            checkpoint.accountFingerprint != null && checkpoint.accountFingerprint != fingerprint
        ) {
            runCatching { checkpointStore.clearCheckpoint(providerId) }
            checkpoint = null
        }

        var position: TrackingChangeFeedCheckpoint = checkpoint ?: run {
            val floor = adoptionFloor(served)
            if (floor == null) {
                return TrackingJournalSyncResult(
                    providerId = providerId,
                    state = TrackingJournalSyncState.NOT_ADOPTED,
                    failure = ADOPTION_FLOOR_UNKNOWN,
                    unitsServedByJournal = served,

                    unitsOwedToHistory = units.toSet(),
                )
            }
            TrackingChangeFeedCheckpoint(
                fromDate = floor,
                accountFingerprint = fingerprint,
                adoptedAt = now(),
                updatedAt = now(),
                lastReason = TrackingChangeFeedCheckpoint.REASON_ADOPTED,
            ).also { adopted -> runCatching { checkpointStore.save(providerId, adopted) } }
        }

        val limit = (pageSize ?: feed.pageSize)
            .coerceIn(1, TrackingChangeFeedRequest.MAX_LIMIT)
        val handled = LinkedHashSet(position.handledEntryIds)
        var dropped = position.droppedEntryIds
        val removals = ArrayList(position.removals)
        var removedObserved = position.removedObserved
        var fromDate = position.fromDate
        var retentionDays = position.retentionDays
        var pages = 0
        var entries = 0
        val tally = Tally()
        var truncated = false
        var visibleUntil: Instant? = null
        var state = TrackingJournalSyncState.APPLIED
        var failure: String? = null
        var oldestServeable: Instant? = null
        var needsHistoryRecovery = false

        while (pages < maxPages) {
            val outcome = feed.readPage(
                TrackingChangeFeedRequest(fromDate = fromDate, limit = limit),
            )
            pages++
            val page = when (outcome) {
                is TrackingChangeFeedOutcome.Ok -> outcome.page
                is TrackingChangeFeedOutcome.Expired -> {

                    state = TrackingJournalSyncState.EXPIRED
                    failure = outcome.reason
                    oldestServeable = outcome.oldest
                    needsHistoryRecovery = true
                    break
                }
                is TrackingChangeFeedOutcome.Unauthorized -> {
                    state = TrackingJournalSyncState.UNAUTHORIZED
                    failure = outcome.reason
                    break
                }
                is TrackingChangeFeedOutcome.Unavailable -> {
                    state = TrackingJournalSyncState.UNAVAILABLE
                    failure = outcome.reason
                    break
                }
                is TrackingChangeFeedOutcome.Rejected -> {
                    state = TrackingJournalSyncState.REJECTED
                    failure = outcome.reason

                    needsHistoryRecovery = true
                    break
                }
            }
            entries += page.entries.size
            if (page.retentionDays != null) retentionDays = page.retentionDays
            visibleUntil = page.visibleUntil
            var handledInPage = 0
            var lastAppliedActionAt: Instant? = null
            var lastAppliedEntryId: String? = null
            var lastExamined: TrackingChangeFeedEntry? = null

            for (entry in page.entries.sortedWith(ENTRY_ORDER)) {
                lastExamined = entry
                if (!handled.add(entry.entryId)) {
                    tally.alreadyHandled++
                    continue
                }
                handledInPage++
                when (handle(entry, tally)) {
                    Handle.APPLIED -> {
                        lastAppliedActionAt = entry.actionAt
                        lastAppliedEntryId = entry.entryId

                        if (entry.status == TrackingChangeFeedStatus.REMOVED) {
                            if (removals.size >= TrackingChangeFeedCheckpoint.MAX_REMOVALS) {
                                removals.removeAt(0)
                            }
                            removals += TrackingChangeFeedRemoval(
                                entryId = entry.entryId,
                                category = entry.category,
                                actionAt = entry.actionAt,
                            )
                            removedObserved++
                        }
                    }

                    Handle.REPORTED -> Unit
                }
            }

            while (handled.size > TrackingChangeFeedCheckpoint.MAX_APPLIED_ENTRY_IDS) {
                val oldest = handled.first()
                handled.remove(oldest)
                dropped++
            }

            val anchor = lastAppliedActionAt ?: lastExamined?.actionAt
            val next = if (page.truncating) {

                truncated = true
                anchor?.minusMillis(TrackingChangeFeedRequest.REPLAY_OVERLAP_MS)
                    ?.takeIf { it.isAfter(fromDate) } ?: fromDate
            } else {

                maxOf(page.visibleUntil, anchor ?: page.visibleUntil)
                    .let { if (it.isAfter(fromDate)) it else fromDate }
            }
            position = position.copy(
                fromDate = next,
                lastAppliedActionAt = lastAppliedActionAt ?: position.lastAppliedActionAt,
                lastAppliedEntryId = lastAppliedEntryId ?: position.lastAppliedEntryId,
                handledEntryIds = handled.toList(),
                droppedEntryIds = dropped,
                removals = removals.toList(),
                removedObserved = removedObserved,
                accountFingerprint = fingerprint ?: position.accountFingerprint,
                retentionDays = retentionDays,
                updatedAt = now(),
                lastReason = TrackingChangeFeedCheckpoint.REASON_ADVANCED,
            )

            val unappliable = tally.unappliable.toList()
            if (unappliable.isNotEmpty()) {
                tally.unappliable.clear()
                val evidence = coverageStore
                if (evidence != null) {
                    for (unit in unappliable) {
                        runCatching {
                            evidence.invalidate(
                                providerId,
                                unit,
                                TrackingWatchedHistoryCoverageRecord.REASON_RESIDUE_STALE,
                                now(),
                            )
                        }
                    }
                }
            }
            runCatching { checkpointStore.save(providerId, position) }
            fromDate = next
            if (!page.truncating) break
            if (handledInPage == 0) {

                state = TrackingJournalSyncState.NO_PROGRESS
                failure = NO_PROGRESS
                break
            }
        }

        return TrackingJournalSyncResult(
            providerId = providerId,
            state = state,
            adopted = true,
            fromDate = position.fromDate,
            visibleUntil = visibleUntil,
            pages = pages,
            entries = entries,
            imported = tally.imported,
            alreadyHeld = tally.alreadyHeld,
            alreadyHandled = tally.alreadyHandled,
            removedRecorded = tally.removedRecorded,
            unconsumed = tally.unconsumed,
            showLevelOnly = tally.showLevelOnly,
            undated = tally.undated,
            seriesIdentityUnstated = tally.seriesIdentityUnstated,
            skippedIdentity = tally.skippedIdentity,
            malformed = tally.malformed,
            truncated = truncated,
            failure = failure,
            oldestServeable = oldestServeable,
            needsHistoryRecovery = needsHistoryRecovery,
            unitsServedByJournal = served,
            unitsOwedToHistory = owed,
            checkpoint = position,
        )
    }

    fun reestablishAfterRecovery(
        oldest: Instant?,
        units: List<TrackingWatchedHistoryUnit> = listOf(
            TrackingWatchedHistoryUnit.MOVIE,
            TrackingWatchedHistoryUnit.EPISODE,
        ),
    ): Boolean {
        val floor = oldest ?: return false
        if (units.none { it in feed.servesUnits }) return false
        val fingerprint = runCatching { accountFingerprint?.invoke() }.getOrNull()
        runCatching {
            checkpointStore.save(
                providerId,
                TrackingChangeFeedCheckpoint(
                    fromDate = floor,
                    accountFingerprint = fingerprint,
                    retentionDays = runCatching { checkpointStore.checkpoint(providerId) }
                        .getOrNull()?.retentionDays,
                    adoptedAt = now(),
                    updatedAt = now(),
                    lastReason = TrackingChangeFeedCheckpoint.REASON_RECOVERED_EXPIRED,
                ),
            )
        }.getOrNull() ?: return false
        return true
    }

    private fun adoptionFloor(served: Set<TrackingWatchedHistoryUnit>): Instant? {
        val marks = ArrayList<Instant>(served.size * 2)
        for (unit in served) {
            val watermark = runCatching { cursorStore.cursor(providerId, unit) }
                .getOrNull()
                ?.watermark
                ?: return null
            marks += watermark
            val evidence = coverageStore?.let { runCatching { it.coverage(providerId, unit) }.getOrNull() }
            evidence?.walkStartedAt?.let { marks += it }
        }
        return marks.minOrNull()
    }

    private suspend fun handle(entry: TrackingChangeFeedEntry, tally: Tally): Handle {

        if (entry.category != TrackingChangeFeedCategory.WATCHED) {
            tally.unconsumed++
            return Handle.REPORTED
        }

        if (entry.status == TrackingChangeFeedStatus.REMOVED) {
            tally.removedRecorded++
            return Handle.APPLIED
        }
        val unit = entry.unit
        if (unit == null) {

            tally.showLevelOnly++

            tally.unappliable += TrackingWatchedHistoryUnit.MOVIE
            tally.unappliable += TrackingWatchedHistoryUnit.EPISODE
            return Handle.REPORTED
        }
        if (unit == TrackingWatchedHistoryUnit.EPISODE && entry.seriesExternalIds.isEmpty) {

            tally.seriesIdentityUnstated++
            tally.unappliable += unit
            return Handle.REPORTED
        }

        if (entry.watchedAtUnknown) {
            tally.undated++
            tally.unappliable += unit
            return Handle.REPORTED
        }
        val watchedAt: Instant = entry.watchedAt ?: run {
            tally.undated++
            tally.unappliable += unit
            return Handle.REPORTED
        }
        val watchKey = resolve(entry) ?: run {
            tally.skippedIdentity++
            tally.unappliable += unit
            return Handle.REPORTED
        }
        if (isAlreadyImported(watchKey, watchedAt, entry.providerPlayId)) {

            tally.alreadyHeld++
            return Handle.APPLIED
        }
        coordinator.importObservation(
            connectorId = providerId,
            watchKey = watchKey,
            kind = WatchEvent.Kind.COMPLETED,

            observedAt = watchedAt,

            positionMs = null,

            providerPlayId = entry.providerPlayId,
        )
        tally.imported++
        return Handle.APPLIED
    }

    private suspend fun resolve(entry: TrackingChangeFeedEntry): WatchKey? {
        val kind = when (entry.unit) {
            TrackingWatchedHistoryUnit.MOVIE -> TrackingMediaKind.MOVIE
            TrackingWatchedHistoryUnit.EPISODE -> TrackingMediaKind.SHOW
            null -> return null
        }
        val ids = entry.identityIds
        if (ids.isEmpty) return null
        when (
            val lookup = identity.lookUp(
                ids = ids,
                kind = kind,
                seasonNumber = entry.seasonNumber,
                episodeNumber = entry.episodeNumber,
            )
        ) {
            is TrackingWatchKeyLookup.Resolved -> return lookup.watchKey
            is TrackingWatchKeyLookup.Ambiguous -> return null
            TrackingWatchKeyLookup.Unproven -> Unit
        }
        val completion = identityCompletion ?: return null
        return when (
            val completed = completion.complete(
                ids = ids,
                kind = kind,
                seasonNumber = entry.seasonNumber,
                episodeNumber = entry.episodeNumber,

                title = entry.title,
                year = entry.year,
            )
        ) {
            is TrackingWatchKeyLookup.Resolved -> completed.watchKey
            else -> null
        }
    }

    private class Tally {
        var imported: Int = 0
        var alreadyHeld: Int = 0
        var alreadyHandled: Int = 0
        var removedRecorded: Int = 0
        var unconsumed: Int = 0
        var showLevelOnly: Int = 0
        var undated: Int = 0
        var seriesIdentityUnstated: Int = 0
        var skippedIdentity: Int = 0
        var malformed: Int = 0

        val unappliable: MutableSet<TrackingWatchedHistoryUnit> = LinkedHashSet()
    }

    private enum class Handle { APPLIED, REPORTED }

    companion object {

        const val NO_AUTHORIZATION: String = "wetrakr_no_authorization"

        const val NOTHING_TO_SERVE: String = "no_change_feed_unit"

        const val ADOPTION_FLOOR_UNKNOWN: String = "adoption_floor_unknown"

        const val NO_PROGRESS: String = "journal_no_progress"

        internal val ENTRY_ORDER: Comparator<TrackingChangeFeedEntry> =
            compareBy<TrackingChangeFeedEntry> { it.actionAt }.thenBy { it.entryId }
    }
}

private val WHITESPACE_RUN: Regex = Regex("\\s+")

data class TrackingJournalSyncResult(
    val providerId: String,
    val state: TrackingJournalSyncState,

    val adopted: Boolean = false,

    val fromDate: Instant? = null,

    val visibleUntil: Instant? = null,
    val pages: Int = 0,
    val entries: Int = 0,
    val imported: Int = 0,

    val alreadyHeld: Int = 0,

    val alreadyHandled: Int = 0,

    val removedRecorded: Int = 0,

    val unconsumed: Int = 0,

    val showLevelOnly: Int = 0,

    val undated: Int = 0,

    val seriesIdentityUnstated: Int = 0,

    val skippedIdentity: Int = 0,

    val malformed: Int = 0,

    val truncated: Boolean = false,

    val failure: String? = null,

    val oldestServeable: Instant? = null,

    val needsHistoryRecovery: Boolean = false,
    val unitsServedByJournal: Set<TrackingWatchedHistoryUnit> = emptySet(),

    val unitsOwedToHistory: Set<TrackingWatchedHistoryUnit> = emptySet(),
    val checkpoint: TrackingChangeFeedCheckpoint? = null,
) {

    fun describe(): String = buildString {
        append("provider=").append(providerId)
        append(" state=").append(state.name)
        append(" adopted=").append(adopted)
        append(" pages=").append(pages)
        append(" entries=").append(entries)
        append(" imported=").append(imported)
        append(" alreadyHeld=").append(alreadyHeld)
        append(" alreadyHandled=").append(alreadyHandled)
        append(" removedRecorded=").append(removedRecorded)
        append(" unconsumed=").append(unconsumed)
        append(" showLevelOnly=").append(showLevelOnly)
        append(" undated=").append(undated)
        append(" seriesIdentityUnstated=").append(seriesIdentityUnstated)
        append(" skippedIdentity=").append(skippedIdentity)
        append(" malformed=").append(malformed)
        append(" truncated=").append(truncated)

        if (failure != null) append(" failure=").append(failure.replace(WHITESPACE_RUN, "-"))
        append(" served=").append(unitsServedByJournal.joinToString(",") { it.name })
        append(" owedToHistory=").append(unitsOwedToHistory.joinToString(",") { it.name })
    }
}

enum class TrackingJournalSyncState {

    APPLIED,

    NOT_ADOPTED,

    EXPIRED,

    UNAUTHORIZED,

    UNAVAILABLE,

    REJECTED,

    NO_PROGRESS,
}
