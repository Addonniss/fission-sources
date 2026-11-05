package dev.infusion.core.domain.tracking

import dev.infusion.core.model.TypedExternalId
import java.time.Instant

enum class TrackingMediaKind { MOVIE, SHOW }

data class TrackingExternalIds(
    val tmdbId: Long? = null,
    val imdbId: String? = null,
    val tvdbId: Long? = null,
) {

    val isEmpty: Boolean get() = tmdbId == null && imdbId == null && tvdbId == null

    fun typed(): List<TypedExternalId> = buildList {
        tmdbId?.let { add(TypedExternalId(AUTHORITY_TMDB, it.toString())) }
        imdbId?.let { add(TypedExternalId(AUTHORITY_IMDB, it)) }
        tvdbId?.let { add(TypedExternalId(AUTHORITY_TVDB, it.toString())) }
    }

    companion object {
        const val AUTHORITY_TMDB = "tmdb"
        const val AUTHORITY_IMDB = "imdb"
        const val AUTHORITY_TVDB = "tvdb"

        fun fromTyped(ids: Iterable<TypedExternalId>): TrackingExternalIds {
            var tmdb: Long? = null
            var imdb: String? = null
            var tvdb: Long? = null
            for (id in ids) {
                when (id.authority) {
                    AUTHORITY_TMDB -> tmdb = tmdb ?: id.value.toLongOrNull()?.takeIf { it > 0 }
                    AUTHORITY_IMDB -> imdb = imdb ?: id.value.trim().takeIf { it.isNotEmpty() }
                    AUTHORITY_TVDB -> tvdb = tvdb ?: id.value.toLongOrNull()?.takeIf { it > 0 }
                }
            }
            return TrackingExternalIds(tmdbId = tmdb, imdbId = imdb, tvdbId = tvdb)
        }
    }
}

data class TrackingMediaReference(
    val kind: TrackingMediaKind,
    val externalIds: TrackingExternalIds,
    val title: String? = null,
    val year: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
) {
    init {
        require(!externalIds.isEmpty) {
            "a tracking media reference requires at least one proven external identifier"
        }
        when (kind) {
            TrackingMediaKind.MOVIE -> require(seasonNumber == null && episodeNumber == null) {
                "a movie reference carries no season/episode coordinates"
            }
            TrackingMediaKind.SHOW -> {
                require(seasonNumber != null && episodeNumber != null) {
                    "an episode reference requires BOTH season and episode coordinates"
                }
                require(seasonNumber > 0 && episodeNumber > 0) {
                    "season/episode coordinates must be positive"
                }
            }
        }
    }
}

enum class TrackingScrobbleAction {

    START,

    PAUSE,

    RESUME,

    STOP,

    COMPLETE,

    MARK_WATCHED,
}

data class TrackingScrobbleEvent(
    val action: TrackingScrobbleAction,
    val media: TrackingMediaReference,
    val progressPercent: Double,
    val occurredAt: Instant,
) {
    init {
        require(progressPercent in 0.0..100.0) { "progressPercent must be in 0..100" }
    }
}

sealed interface TrackingDeliveryResult {
    data object Delivered : TrackingDeliveryResult

    data class Retryable(val reason: String) : TrackingDeliveryResult

    data class Rejected(val reason: String) : TrackingDeliveryResult

    data class Unauthorized(val reason: String) : TrackingDeliveryResult
}

data class TrackingDeviceAuthorization(
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val expiresAt: Instant,
    val intervalSeconds: Int,
)

sealed interface TrackingAuthorizationPoll {

    data object Pending : TrackingAuthorizationPoll

    data class SlowDown(val retryAfterSeconds: Int) : TrackingAuthorizationPoll

    data object Authorized : TrackingAuthorizationPoll

    data object Expired : TrackingAuthorizationPoll

    data class Failed(val reason: String) : TrackingAuthorizationPoll
}

sealed interface TrackingConnectionState {
    data object Disconnected : TrackingConnectionState

    data class AwaitingApproval(val authorization: TrackingDeviceAuthorization) : TrackingConnectionState

    data class Connected(
        val accountLabel: String?,
        val accessExpiresAt: Instant?,
        val refreshExpiresAt: Instant?,
        val reconnectRequired: Boolean,
    ) : TrackingConnectionState
}
