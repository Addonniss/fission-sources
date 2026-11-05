package dev.infusion.core.sync.tracking

import dev.infusion.core.domain.tracking.TrackingInboundIdentityCompletionPort
import dev.infusion.core.domain.tracking.TrackingInboundIdentityPort
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.domain.tracking.TrackingMediaKind
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryCursor
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryCursorStore
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryPort
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryRecord
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryRequest
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryUnit
import dev.infusion.core.domain.tracking.TrackingWatchKeyLookup
import dev.infusion.core.model.watch.WatchEvent
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.sync.WatchSyncCoordinator
import java.time.Instant

class TrackingWatchedHistoryImporter(

    private val providerId: String,
    private val reader: TrackingWatchedHistoryPort,
    private val identity: TrackingInboundIdentityPort,

    private val identityCompletion: TrackingInboundIdentityCompletionPort? = null,
    private val cursorStore: TrackingWatchedHistoryCursorStore,
    private val coordinator: WatchSyncCoordinator,

    private val isAlreadyImported: (WatchKey, Instant) -> Boolean = { _, _ -> false },

    private val maxPages: Int = TrackingWatchedHistoryRequest.MAX_PAGES_PER_SWEEP,

    private val pageLimit: Int = TrackingWatchedHistoryRequest.DEFAULT_LIMIT,
) {

    suspend fun sweep(
        units: List<TrackingWatchedHistoryUnit> = listOf(
            TrackingWatchedHistoryUnit.MOVIE,
            TrackingWatchedHistoryUnit.EPISODE,
        ),
    ): TrackingWatchedHistorySweepResult {
        if (!reader.accountConnected()) {
            return TrackingWatchedHistorySweepResult(
                providerId = providerId,
                failures = units.associateWith { NO_AUTHORIZATION },
            )
        }
        var imported = 0
        var pages = 0
        var skippedIdentity = 0
        var undated = 0
        var showLevelOnly = 0
        var malformed = 0
        var alreadyCovered = 0
        var truncated = false
        val failures = LinkedHashMap<TrackingWatchedHistoryUnit, String>()
        val completed = LinkedHashSet<TrackingWatchedHistoryUnit>()

        val seen = HashSet<String>()

        for (unit in units) {
            var position = cursorStore.cursor(providerId, unit)
            var unitPages = 0
            while (unitPages < maxPages) {
                val outcome = reader.readHistory(
                    TrackingWatchedHistoryRequest(
                        unit = unit,
                        cursor = position.next,
                        since = position.watermark,
                        limit = pageLimit,
                    ),
                )
                val page = when (outcome) {
                    is TrackingInboundOutcome.Ok -> outcome.value
                    is TrackingInboundOutcome.Unauthorized -> {
                        failures[unit] = outcome.reason
                        break
                    }
                    is TrackingInboundOutcome.Unavailable -> {
                        failures[unit] = outcome.reason
                        break
                    }
                    is TrackingInboundOutcome.Rejected -> {
                        failures[unit] = outcome.reason
                        break
                    }
                }
                unitPages++
                pages++
                malformed += page.malformed
                undated += page.undated
                showLevelOnly += page.showLevelOnly
                alreadyCovered += page.alreadyCovered
                if (page.truncating) truncated = true

                for (record in page.records) {
                    val key = dedupeKey(record)
                    if (!seen.add(key)) {
                        alreadyCovered++
                        continue
                    }
                    val watchKey = resolve(record)
                    if (watchKey == null) {
                        skippedIdentity++
                        continue
                    }
                    val observedAt = record.watchedAt ?: continue
                    if (isAlreadyImported(watchKey, observedAt)) {
                        alreadyCovered++
                        continue
                    }
                    coordinator.importObservation(
                        connectorId = providerId,
                        watchKey = watchKey,
                        kind = WatchEvent.Kind.COMPLETED,

                        observedAt = observedAt,

                        positionMs = null,
                    )
                    imported++
                }

                if (page.next == null) {

                    position = TrackingWatchedHistoryCursor(
                        watermark = page.completedThrough ?: position.watermark,
                        next = null,
                    )
                    cursorStore.save(providerId, unit, position)
                    completed += unit
                    break
                }
                position = position.copy(next = page.next)
                cursorStore.save(providerId, unit, position)
            }
            if (unitPages >= maxPages && unit !in completed && unit !in failures) {

                truncated = true
            }
        }

        return TrackingWatchedHistorySweepResult(
            providerId = providerId,
            imported = imported,
            pages = pages,
            skippedIdentity = skippedIdentity,
            undated = undated,
            showLevelOnly = showLevelOnly,
            malformed = malformed,
            alreadyCovered = alreadyCovered,
            truncated = truncated,
            failures = failures,
            completed = completed,
        )
    }

    private suspend fun resolve(record: TrackingWatchedHistoryRecord): WatchKey? {
        val kind = when (record.unit) {
            TrackingWatchedHistoryUnit.MOVIE -> TrackingMediaKind.MOVIE
            TrackingWatchedHistoryUnit.EPISODE -> TrackingMediaKind.SHOW
        }
        when (
            val lookup = identity.lookUp(
                ids = record.externalIds,
                kind = kind,
                seasonNumber = record.seasonNumber,
                episodeNumber = record.episodeNumber,
            )
        ) {
            is TrackingWatchKeyLookup.Resolved -> return lookup.watchKey
            is TrackingWatchKeyLookup.Ambiguous -> return null
            TrackingWatchKeyLookup.Unproven -> Unit
        }
        val completion = identityCompletion ?: return null
        return when (
            val completed = completion.complete(
                ids = record.externalIds,
                kind = kind,
                seasonNumber = record.seasonNumber,
                episodeNumber = record.episodeNumber,

                title = record.title,
                year = record.year,
            )
        ) {
            is TrackingWatchKeyLookup.Resolved -> completed.watchKey
            else -> null
        }
    }

    private fun dedupeKey(record: TrackingWatchedHistoryRecord): String = buildString {
        append(record.unit.name)
        append('|').append(record.externalIds.imdbId ?: "")
        append('|').append(record.externalIds.tmdbId ?: "")
        append('|').append(record.externalIds.tvdbId ?: "")
        append('|').append(record.seasonNumber ?: "")
        append('|').append(record.episodeNumber ?: "")
        append('|').append(record.watchedAt ?: "")
    }

    companion object {

        const val NO_AUTHORIZATION: String = "tracker_not_connected"
    }
}

data class TrackingWatchedHistorySweepResult(
    val providerId: String,

    val imported: Int = 0,

    val pages: Int = 0,

    val truncated: Boolean = false,

    val skippedIdentity: Int = 0,

    val undated: Int = 0,

    val showLevelOnly: Int = 0,

    val malformed: Int = 0,

    val alreadyCovered: Int = 0,

    val failures: Map<TrackingWatchedHistoryUnit, String> = emptyMap(),

    val completed: Set<TrackingWatchedHistoryUnit> = emptySet(),
) {
    val isEmpty: Boolean get() = imported == 0 && pages == 0

    fun describe(): String = buildString {
        append("provider=").append(providerId)
        append(" imported=").append(imported)
        append(" pages=").append(pages)
        append(" truncated=").append(truncated)
        append(" alreadyCovered=").append(alreadyCovered)
        append(" undated=").append(undated)
        append(" showLevelOnly=").append(showLevelOnly)
        append(" skippedIdentity=").append(skippedIdentity)
        append(" malformed=").append(malformed)
        if (failures.isNotEmpty()) {
            append(" failures=").append(failures.entries.joinToString(",") { "${it.key.name}:${it.value}" })
        }
        append(" completed=").append(completed.joinToString(",") { it.name })
    }
}
