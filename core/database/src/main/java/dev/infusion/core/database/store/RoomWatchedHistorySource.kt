package dev.infusion.core.database.store

import dev.infusion.core.database.InfusionDatabase
import dev.infusion.core.database.entity.WatchEventEntity
import dev.infusion.core.database.model.EventOrigin
import dev.infusion.core.database.watch.WatchKeyCodec
import dev.infusion.core.database.watch.canonicalForConvergence
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.tracking.TrackingWatchedHistoryRequest
import dev.infusion.core.domain.watch.WatchedHistoryCursor
import dev.infusion.core.domain.watch.WatchedHistoryFact
import dev.infusion.core.domain.watch.WatchedHistoryFactKind
import dev.infusion.core.domain.watch.WatchedHistoryPage
import dev.infusion.core.domain.watch.WatchedHistoryShape
import dev.infusion.core.domain.watch.WatchedHistorySource
import dev.infusion.core.model.watch.WatchEvent
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.sync.CompletionPolicy
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomWatchedHistorySource(
    private val db: InfusionDatabase,
    private val scope: ProfileScope,
    private val supersession: TitleSupersession? = null,

    private val pageRows: Int = TrackingWatchedHistoryRequest.DEFAULT_LIMIT,

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WatchedHistorySource {

    init {
        require(pageRows > 0) { "pageRows must be positive" }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeHistoryPublications(): Flow<Unit> =
        scope.activeProfileFlow.flatMapLatest {

            db.invalidationTracker.createFlow(WATCH_EVENTS_TABLE).map { }
        }

    override suspend fun readWatchedFactsPage(
        shape: WatchedHistoryShape,
        after: WatchedHistoryCursor?,
    ): WatchedHistoryPage = withContext(ioDispatcher) {
        val profileId = scope.activeProfileId

        val rows = db.watchEventDao().readWatchedHistoryOfShape(
            profileId = profileId,
            prefix = shape.keyPrefix,
            afterSet = if (after == null) 0 else 1,
            afterUndated = if (after?.undated == true) 1 else 0,
            afterMs = after?.watchedAtMs,
            afterKey = after?.watchKey,
            afterId = after?.eventId,
            limit = pageRows + 1,
        )
        val page = if (rows.size > pageRows) rows.subList(0, pageRows) else rows
        val lastRow = page.lastOrNull()
        WatchedHistoryPage(
            facts = page.mapNotNull(::toFact),

            next = lastRow?.takeIf { rows.size > pageRows }?.let { last ->
                cursorOf(last)
            },

            last = lastRow?.let(::cursorOf),
        )
    }

    private fun cursorOf(row: WatchEventEntity): WatchedHistoryCursor {
        val instant = when {
            row.kind == WatchEvent.Kind.EXPLICIT_RESET -> row.occurredAtMs
            row.origin == EventOrigin.LOCAL -> row.occurredAtMs
            else -> row.observedAtMs
        }
        return WatchedHistoryCursor(
            undated = instant == null,
            watchedAtMs = instant,
            watchKey = row.watchKey,
            eventId = row.eventId,
        )
    }

    private fun toFact(row: WatchEventEntity): WatchedHistoryFact? {
        val watchKey = decodeLedgerKey(row) ?: return null
        val ledgerAt = Instant.ofEpochMilli(row.occurredAtMs)
        return when {
            row.kind == WatchEvent.Kind.EXPLICIT_RESET -> WatchedHistoryFact(
                watchKey = watchKey,
                kind = WatchedHistoryFactKind.UNWATCHED,
                watchedAt = null,
                ledgerAt = ledgerAt,
            )

            row.kind == WatchEvent.Kind.COMPLETED || row.kind == WatchEvent.Kind.EXPLICIT_WATCHED ->
                WatchedHistoryFact(
                    watchKey = watchKey,
                    kind = WatchedHistoryFactKind.WATCHED,
                    watchedAt = watchedAtOf(row),
                    ledgerAt = ledgerAt,
                    providerId = row.connectorId,
                )

            row.kind == WatchEvent.Kind.PROGRESS && row.origin == EventOrigin.IMPORT ->
                if (!CompletionPolicy.isComplete(row.positionMs, row.durationMs)) {
                    null
                } else {
                    WatchedHistoryFact(
                        watchKey = watchKey,
                        kind = WatchedHistoryFactKind.WATCHED,
                        watchedAt = watchedAtOf(row),
                        ledgerAt = ledgerAt,
                        providerId = row.connectorId,
                    )
                }

            else -> null
        }
    }

    private fun decodeLedgerKey(row: WatchEventEntity): WatchKey? {
        val decoded = try {
            WatchKeyCodec.decode(row.watchKey)
        } catch (malformed: IllegalArgumentException) {
            return null
        }
        return decoded.canonicalForConvergence(supersession)
    }

    private fun watchedAtOf(row: WatchEventEntity): Instant? =
        when (row.origin) {
            EventOrigin.LOCAL -> Instant.ofEpochMilli(row.occurredAtMs)
            EventOrigin.IMPORT -> row.observedAtMs?.let(Instant::ofEpochMilli)
        }

    private val WatchedHistoryShape.keyPrefix: String
        get() = when (this) {
            WatchedHistoryShape.MOVIE -> WatchKeyCodec.MOVIE_PREFIX
            WatchedHistoryShape.EPISODE -> WatchKeyCodec.EPISODE_PREFIX
        }

    companion object {

        const val WATCH_EVENTS_TABLE: String = "watch_events"
    }
}
