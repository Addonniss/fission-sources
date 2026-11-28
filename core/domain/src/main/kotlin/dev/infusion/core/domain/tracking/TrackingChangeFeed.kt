package dev.infusion.core.domain.tracking

import java.time.Instant

enum class TrackingChangeFeedCategory {

    WATCHED,

    WATCHING,

    WAITING,

    PLANNING,

    DROPPED,

    PAUSED,

    IGNORED,

    RATINGS,
    FAVORITES,
    NOTES,
    LIKES,
    COMMENTS,
    LISTS,
}

enum class TrackingChangeFeedStatus {
    ADDED,
    UPDATED,

    REMOVED,
}

data class TrackingChangeFeedEntry(
    val entryId: String,
    val category: TrackingChangeFeedCategory,
    val status: TrackingChangeFeedStatus,
    val actionAt: Instant,
    val unit: TrackingWatchedHistoryUnit? = null,
    val externalIds: TrackingExternalIds = TrackingExternalIds(),
    val seriesExternalIds: TrackingExternalIds = TrackingExternalIds(),
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val watchedAt: Instant? = null,
    val watchedAtUnknown: Boolean = false,
    val providerPlayId: String? = null,
    val title: String? = null,
    val year: Int? = null,
) {
    init {
        require(entryId.isNotBlank()) { "a change feed entry requires the provider's own entry id" }
        when (unit) {
            null -> require(seasonNumber == null && episodeNumber == null) {
                "an entry with no ledger unit carries no season/episode coordinates"
            }
            TrackingWatchedHistoryUnit.MOVIE -> require(seasonNumber == null && episodeNumber == null) {
                "a movie entry carries no season/episode coordinates"
            }
            TrackingWatchedHistoryUnit.EPISODE -> {
                require(seasonNumber != null && episodeNumber != null) {
                    "an episode entry requires BOTH season and episode coordinates"
                }
                require(seasonNumber > 0 && episodeNumber > 0) {
                    "season/episode coordinates must be positive"
                }
            }
        }
    }

    val identityIds: TrackingExternalIds
        get() = when (unit) {
            TrackingWatchedHistoryUnit.MOVIE -> externalIds
            TrackingWatchedHistoryUnit.EPISODE -> seriesExternalIds
            null -> TrackingExternalIds()
        }
}

data class TrackingChangeFeedPage(
    val entries: List<TrackingChangeFeedEntry>,
    val visibleUntil: Instant,
    val retentionDays: Int? = null,
    val malformed: Int = 0,
    val unconsumed: Int = 0,
    val truncating: Boolean = false,
)

data class TrackingChangeFeedRequest(
    val fromDate: Instant,
    val page: Int = FIRST_PAGE,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "limit must be in 1..$MAX_LIMIT" }
        require(page >= FIRST_PAGE) { "page starts at $FIRST_PAGE" }
    }

    companion object {
        const val FIRST_PAGE: Int = 1

        const val DEFAULT_LIMIT: Int = 100

        const val MAX_LIMIT: Int = 1_000

        const val MAX_PAGES_PER_SWEEP: Int = 4

        const val REPLAY_OVERLAP_MS: Long = 5_000L
    }
}

sealed interface TrackingChangeFeedOutcome {

    data class Ok(val page: TrackingChangeFeedPage) : TrackingChangeFeedOutcome

    data class Expired(val oldest: Instant?, val reason: String) : TrackingChangeFeedOutcome

    data class Unauthorized(val reason: String) : TrackingChangeFeedOutcome

    data class Unavailable(val reason: String) : TrackingChangeFeedOutcome

    data class Rejected(val reason: String) : TrackingChangeFeedOutcome
}

interface TrackingChangeFeedPort {

    val providerId: String

    fun accountConnected(): Boolean

    val servesUnits: Set<TrackingWatchedHistoryUnit>
        get() = setOf(TrackingWatchedHistoryUnit.MOVIE, TrackingWatchedHistoryUnit.EPISODE)

    val pageSize: Int get() = TrackingChangeFeedRequest.DEFAULT_LIMIT

    suspend fun readPage(request: TrackingChangeFeedRequest): TrackingChangeFeedOutcome
}

data class TrackingChangeFeedRemoval(
    val entryId: String,
    val category: TrackingChangeFeedCategory,
    val actionAt: Instant,
)

data class TrackingChangeFeedCheckpoint(
    val fromDate: Instant,
    val lastAppliedActionAt: Instant? = null,
    val lastAppliedEntryId: String? = null,
    val handledEntryIds: List<String> = emptyList(),
    val droppedEntryIds: Int = 0,
    val removals: List<TrackingChangeFeedRemoval> = emptyList(),
    val removedObserved: Int = 0,
    val accountFingerprint: String? = null,
    val retentionDays: Int? = null,
    val adoptedAt: Instant? = null,
    val updatedAt: Instant = Instant.EPOCH,
    val lastReason: String? = null,
) {
    init {
        require(handledEntryIds.size <= MAX_APPLIED_ENTRY_IDS) {
            "the replay-dedup record is bounded to $MAX_APPLIED_ENTRY_IDS entries"
        }
        require(removals.size <= MAX_REMOVALS) { "the removal evidence is bounded to $MAX_REMOVALS entries" }
    }

    companion object {

        const val MAX_APPLIED_ENTRY_IDS: Int = 512

        const val MAX_REMOVALS: Int = 32

        const val REASON_ADOPTED: String = "adopted_from_import"

        const val REASON_ADVANCED: String = "advanced"

        const val REASON_RECOVERED_EXPIRED: String = "recovered_expired"

        const val REASON_ACCOUNT_CHANGED: String = "account_changed"
    }
}

interface TrackingChangeFeedCheckpointStore {

    fun checkpoint(providerId: String): TrackingChangeFeedCheckpoint?

    fun save(providerId: String, checkpoint: TrackingChangeFeedCheckpoint)

    fun clearCheckpoint(providerId: String)
}
