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

    val providerPlayId: String? = null,
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

data class TrackingWatchedHistoryResidueRecord(

    val externalIds: TrackingExternalIds,

    val kind: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
) {
    init {
        require(!externalIds.isEmpty) {
            "a residue record requires at least one proven external identifier"
        }
        require(kind.isNotBlank()) { "a residue record requires its own class code" }
    }

    fun itemKey(): String = buildString {
        append(externalIds.tmdbId ?: "").append('|')
        append(externalIds.imdbId ?: "").append('|')
        append(externalIds.tvdbId ?: "").append('|')
        append(seasonNumber ?: "").append('|')
        append(episodeNumber ?: "")
    }

    companion object {

        const val KIND_UNRESOLVED_IDENTITY: String = "unresolved_identity"

        const val KIND_UNDATED: String = "undated"

        const val KIND_SEASON_ZERO: String = "season_zero"

        const val KIND_COORDINATE_MISSING: String = "coordinate_missing"

        const val KIND_COORDINATE_INVALID: String = "coordinate_invalid"

        const val KIND_COORDINATE_NEGATIVE: String = "coordinate_negative"
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

        const val MAX_LIMIT: Int = 5_000

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

    val residue: List<TrackingWatchedHistoryResidueRecord> = emptyList(),

    val seasonZero: Int = 0,
    val coordinateMissing: Int = 0,
    val coordinateInvalid: Int = 0,
    val coordinateNegative: Int = 0,
)

interface TrackingWatchedHistoryPort {

    val providerId: String

    fun accountConnected(): Boolean

    val historyPageSize: Int get() = TrackingWatchedHistoryRequest.DEFAULT_LIMIT

    suspend fun readHistory(
        request: TrackingWatchedHistoryRequest,
    ): TrackingInboundOutcome<TrackingWatchedHistoryPage>
}

data class TrackingWatchedItemName(

    val externalIds: TrackingExternalIds,

    val title: String,

    val year: Int? = null,
)

data class TrackingWatchedItemNamesPage(
    val names: List<TrackingWatchedItemName> = emptyList(),
    val rows: Int = names.size,
    val titled: Int = names.size,
    val malformed: Int = 0,

    val page: Int = 1,

    val hasMore: Boolean = false,
)

interface TrackingWatchedItemNamePort {

    val providerId: String

    suspend fun readStatedNames(
        unit: TrackingWatchedHistoryUnit,
        page: Int,
        limit: Int,
    ): TrackingInboundOutcome<TrackingWatchedItemNamesPage>
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
