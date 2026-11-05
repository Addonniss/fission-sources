package dev.infusion.core.domain.tracking

import java.time.Instant

enum class TrackingWatchedHistoryUnit { MOVIE, EPISODE }

data class TrackingWatchedHistoryRecord(
    val unit: TrackingWatchedHistoryUnit,
    val externalIds: TrackingExternalIds,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val watchedAt: Instant? = null,
    val title: String? = null,
    val year: Int? = null,
) {
    init {
        require(!externalIds.isEmpty) {
            "a watched-history record requires at least one proven external identifier"
        }
        when (unit) {
            TrackingWatchedHistoryUnit.MOVIE -> require(seasonNumber == null && episodeNumber == null) {
                "a movie watched-history record carries no season/episode coordinates"
            }
            TrackingWatchedHistoryUnit.EPISODE -> {
                require(seasonNumber != null && episodeNumber != null) {
                    "an episode watched-history record requires BOTH season and episode coordinates"
                }
                require(seasonNumber > 0 && episodeNumber > 0) {
                    "season/episode coordinates must be positive"
                }
            }
        }
    }
}

data class TrackingWatchedHistoryRequest(
    val unit: TrackingWatchedHistoryUnit,
    val cursor: String? = null,
    val since: Instant? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "limit must be in 1..$MAX_LIMIT" }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 200

        const val MAX_LIMIT: Int = 500

        const val MAX_PAGES_PER_SWEEP: Int = 4
    }
}

data class TrackingWatchedHistoryPage(
    val records: List<TrackingWatchedHistoryRecord>,
    val next: String? = null,
    val malformed: Int = 0,
    val undated: Int = 0,
    val showLevelOnly: Int = 0,
    val alreadyCovered: Int = 0,
    val truncating: Boolean = false,
    val completedThrough: Instant? = null,
)

interface TrackingWatchedHistoryPort {

    val providerId: String

    fun accountConnected(): Boolean

    suspend fun readHistory(
        request: TrackingWatchedHistoryRequest,
    ): TrackingInboundOutcome<TrackingWatchedHistoryPage>
}

data class TrackingWatchedHistoryCursor(
    val watermark: Instant? = null,
    val next: String? = null,
)

interface TrackingWatchedHistoryCursorStore {

    fun cursor(providerId: String, unit: TrackingWatchedHistoryUnit): TrackingWatchedHistoryCursor

    fun save(providerId: String, unit: TrackingWatchedHistoryUnit, cursor: TrackingWatchedHistoryCursor)

    fun clear(providerId: String)
}
