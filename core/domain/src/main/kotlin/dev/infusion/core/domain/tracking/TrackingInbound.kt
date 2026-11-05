package dev.infusion.core.domain.tracking

import dev.infusion.core.model.TitleId
import dev.infusion.core.model.watch.WatchKey
import java.time.Instant

data class TrackingSavedPlayback(
    val kind: TrackingMediaKind,
    val externalIds: TrackingExternalIds,
    val progressPercent: Double,
    val pausedAt: Instant? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val title: String? = null,
    val year: Int? = null,
    val providerRecordId: String? = null,
) {
    init {
        require(!externalIds.isEmpty) {
            "a saved playback requires at least one proven external identifier to be matchable"
        }
        when (kind) {
            TrackingMediaKind.MOVIE -> require(seasonNumber == null && episodeNumber == null) {
                "a movie saved playback carries no season/episode coordinates"
            }
            TrackingMediaKind.SHOW -> {
                require(seasonNumber != null && episodeNumber != null) {
                    "an episode saved playback requires BOTH season and episode coordinates"
                }
                require(seasonNumber > 0 && episodeNumber > 0) {
                    "season/episode coordinates must be positive"
                }
            }
        }
        require(progressPercent.isFinite() && progressPercent in 0.0..100.0) {
            "progressPercent must be a finite value in 0..100"
        }
    }
}

data class TrackingActivityStamp(
    val all: Instant? = null,
    val playback: Instant? = null,
    val watched: Instant? = null,
)

sealed interface TrackingInboundOutcome<out T> {

    data class Ok<T>(val value: T) : TrackingInboundOutcome<T>

    data class Unauthorized(val reason: String) : TrackingInboundOutcome<Nothing>

    data class Unavailable(val reason: String) : TrackingInboundOutcome<Nothing>

    data class Rejected(val reason: String) : TrackingInboundOutcome<Nothing>
}

data class TrackingPlaybackRequest(

    val dateFrom: Instant? = null,

    val limit: Int = 100,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "limit must be in 1..$MAX_LIMIT" }
    }

    companion object {

        const val MAX_LIMIT: Int = 500
    }
}

data class TrackingPlaybackPage(
    val items: List<TrackingSavedPlayback>,
    val malformed: Int = 0,
    val truncated: Boolean = false,
)

interface TrackingInboundReadPort {

    val providerId: String

    suspend fun readActivityStamp(): TrackingInboundOutcome<TrackingActivityStamp>

    suspend fun readSavedPlaybacks(request: TrackingPlaybackRequest): TrackingInboundOutcome<TrackingPlaybackPage>
}

sealed interface TrackingWatchKeyLookup {
    data class Resolved(val watchKey: WatchKey) : TrackingWatchKeyLookup
    data object Unproven : TrackingWatchKeyLookup
    data class Ambiguous(val candidates: List<TitleId>) : TrackingWatchKeyLookup
}

interface TrackingInboundIdentityPort {

    fun lookUp(
        ids: TrackingExternalIds,
        kind: TrackingMediaKind,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ): TrackingWatchKeyLookup
}

interface TrackingInboundIdentityCompletionPort {

    suspend fun complete(
        ids: TrackingExternalIds,
        kind: TrackingMediaKind,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        title: String? = null,
        year: Int? = null,
    ): TrackingWatchKeyLookup
}

interface TrackingRuntimeSourcePort {

    suspend fun runtimeMsFor(watchKey: WatchKey): Long?
}

interface TrackingInboundCursorStore {

    fun playbackGate(providerId: String): Instant?

    fun savePlaybackGate(providerId: String, gate: Instant?)

    fun clear(providerId: String)
}

enum class TrackingInboundSkipReason {

    UNPROVEN_IDENTITY,

    AMBIGUOUS_IDENTITY,

    UNPROVEN_RUNTIME,

    ZERO_POSITION,

    NO_OBSERVATION_TIME,

    UNSENT_LOCAL_WORK,

    MALFORMED,

    DUPLICATE,

    ALREADY_RECORDED,

    RESUME_RESET_GUARD,
}

data class TrackingInboundSyncResult(

    val gateUnchanged: Boolean = false,

    val gate: Instant? = null,

    val imported: Int = 0,

    val skipped: Map<TrackingInboundSkipReason, Int> = emptyMap(),

    val pages: Int = 0,

    val watchedActivity: Instant? = null,

    val failure: String? = null,

    val watched: TrackingWatchedLookupResult = TrackingWatchedLookupResult(),
) {
    val anySkipped: Boolean get() = skipped.isNotEmpty()
}
