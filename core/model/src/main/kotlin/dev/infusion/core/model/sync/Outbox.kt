package dev.infusion.core.model.sync

import dev.infusion.core.model.OutboxEntryId
import dev.infusion.core.model.SessionId
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.watch.WatchEvent
import dev.infusion.core.model.watch.WatchKey
import java.time.Instant

enum class OutboxState {
    PENDING,

    FAILED,

    CANCELLED,

    ACKNOWLEDGED,
}

const val NOT_SENT_BY_USER_CHOICE: String = "NOT_SENT_BY_USER_CHOICE"

fun OutboxEntry.isNotSentByUserChoice(): Boolean =
    state == OutboxState.CANCELLED && lastError == NOT_SENT_BY_USER_CHOICE

sealed class OutboxPayload {

    data class WatchAction(
        val watchKey: WatchKey,
        val kind: WatchEvent.Kind,
        val positionMs: Long?,
        val durationMs: Long?,
        val sessionId: SessionId?,
        val occurredAt: Instant,
    ) : OutboxPayload()

    data class PositionUpdate(
        val watchKey: WatchKey,
        val positionMs: Long,
        val durationMs: Long? = null,
        val completed: Boolean = false,
        val occurredAt: Instant,
    ) : OutboxPayload() {

        init {
            require(positionMs >= 0L) { "a progress write position must be >= 0" }
        }
    }

    data class WatchlistWrite(
        val direction: WatchlistWriteDirection,
        val kind: WatchlistMembershipKind,

        val titleId: String,

        val aliases: List<TypedExternalId>,

        val remoteType: WatchlistRemoteType,
        val remoteRef: String? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val title: String? = null,
        val year: Int? = null,
        val accountRef: String? = null,
        val occurredAt: Instant,
    ) : OutboxPayload() {

        init {
            require(aliases.isNotEmpty()) {
                "a watchlist write requires at least one proven external alias"
            }
        }
    }

    data class ResumeReset(
        val watchKey: WatchKey,
        val kind: WatchlistMembershipKind,
        val aliases: List<TypedExternalId>,
        val titleId: String? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val accountRef: String? = null,
        val occurredAt: Instant,
    ) : OutboxPayload() {

        init {
            require(aliases.isNotEmpty()) {
                "a resume reset requires at least one proven external alias"
            }
        }
    }

    data class PersonalRating(
        val kind: PersonalRatingKind,

        val titleId: String,

        val aliases: List<TypedExternalId>,

        val score: Int,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val title: String? = null,
        val year: Int? = null,
        val accountRef: String? = null,
        val occurredAt: Instant,
    ) : OutboxPayload() {

        init {
            require(aliases.isNotEmpty()) {
                "a personal rating requires at least one proven external alias"
            }
            require(score >= 1) { "a personal rating score must be >= 1" }
            if (kind == PersonalRatingKind.EPISODE) {
                require(seasonNumber != null && episodeNumber != null) {
                    "an episode personal rating requires its exact season and episode numbers"
                }
            }
        }
    }
}

enum class PersonalRatingKind { MOVIE, EPISODE }

enum class WatchlistWriteDirection { ADD, REMOVE }

enum class WatchlistMembershipKind { MOVIE, SHOW }

enum class WatchlistRemoteType { MOVIE, SHOW, SEASON, EPISODE }

object OutboxWriteKind {
    const val WATCHLIST_ADD: String = "WATCHLIST_ADD"
    const val WATCHLIST_REMOVE: String = "WATCHLIST_REMOVE"
    const val WATCHLIST_RESUME_RESET: String = "WATCHLIST_RESUME_RESET"

    const val PERSONAL_RATING: String = "PERSONAL_RATING"

    const val POSITION_UPDATE: String = "POSITION_UPDATE"

    fun discriminatorOf(payload: OutboxPayload): String? = when (payload) {
        is OutboxPayload.WatchAction -> payload.kind.name
        is OutboxPayload.WatchlistWrite ->
            if (payload.direction == WatchlistWriteDirection.ADD) WATCHLIST_ADD else WATCHLIST_REMOVE
        is OutboxPayload.ResumeReset -> WATCHLIST_RESUME_RESET
        is OutboxPayload.PersonalRating -> PERSONAL_RATING
        is OutboxPayload.PositionUpdate -> POSITION_UPDATE
    }
}

data class OutboxEntry(
    val entryId: OutboxEntryId,
    val connectorId: String,
    val idempotencyKey: String,
    val payload: OutboxPayload,
    val state: OutboxState = OutboxState.PENDING,
    val attempts: Int = 0,
    val createdAt: Instant,
    val nextAttemptAt: Instant? = null,
    val lastError: String? = null,
) {
    init {
        require(connectorId.isNotBlank()) { "connectorId must not be blank" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }
        require(attempts >= 0) { "attempts must be >= 0" }
    }
}

data class ConnectorCheckpoint(
    val connectorId: String,
    val lastImportCursor: String? = null,
    val lastImportedAt: Instant? = null,
    val updatedAt: Instant = Instant.now(),
)

data class TrackingCapabilities(
    val connectorId: String,
    val historyImport: Boolean = false,
    val scrobble: Boolean = false,
    val watchlist: Boolean = false,
    val ratings: Boolean = false,
    val lists: Boolean = false,
    val importExport: Boolean = false,
)
