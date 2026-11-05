package dev.infusion.core.domain.ratings

import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.model.sync.PersonalRatingKind

data class PersonalRatingCapabilities(
    val read: Boolean,
    val writeKinds: Set<PersonalRatingKind>,
    val scaleMin: Int,
    val scaleMax: Int,
    val sideEffectNote: String? = null,
) {
    init {
        require(scaleMin >= 1) { "scaleMin must be >= 1" }
        require(scaleMax >= scaleMin) { "scaleMax must be >= scaleMin" }
    }

    val scores: IntRange get() = scaleMin..scaleMax

    fun supports(kind: PersonalRatingKind): Boolean = kind in writeKinds

    companion object {

        val None: PersonalRatingCapabilities = PersonalRatingCapabilities(
            read = false,
            writeKinds = emptySet(),
            scaleMin = 1,
            scaleMax = 10,
        )
    }
}

data class PersonalRatingRef(
    val kind: PersonalRatingKind,
    val externalIds: TrackingExternalIds,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val title: String? = null,
    val year: Int? = null,
) {
    init {
        require(!externalIds.isEmpty) {
            "a personal rating reference requires at least one proven external identifier"
        }
        if (kind == PersonalRatingKind.EPISODE) {
            require(seasonNumber != null && episodeNumber != null) {
                "an episode personal rating reference requires its exact season and episode"
            }
        }
    }
}

typealias PersonalRatingReadOutcome<T> = TrackingInboundOutcome<T>

sealed interface PersonalRatingWriteResult {
    data object Delivered : PersonalRatingWriteResult

    data class Retryable(val reason: String) : PersonalRatingWriteResult

    data class Rejected(val reason: String) : PersonalRatingWriteResult

    data class Unauthorized(val reason: String) : PersonalRatingWriteResult

    data class Unsupported(val reason: String) : PersonalRatingWriteResult
}

data class PersonalRatingLibraryRequest(
    val page: Int = 1,
    val cursor: String? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(page >= 1) { "page must be >= 1" }
        require(limit in 1..MAX_LIMIT) { "limit must be in 1..$MAX_LIMIT" }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 100
        const val MAX_LIMIT: Int = 1000
    }
}

data class PersonalRatingEntry(
    val kind: PersonalRatingKind,
    val externalIds: TrackingExternalIds,
    val score: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val preciseScore: Double? = null,
) {
    init {
        require(!externalIds.isEmpty) { "a rated entry requires at least one proven identifier" }
        require(score != null || preciseScore != null) {
            "a rated entry requires a verified score (whole or precise)"
        }
        score?.let { require(it >= 1) { "a rated entry score must be >= 1" } }
        preciseScore?.let { require(it.isFinite() && it >= 1.0) { "a precise score must be >= 1" } }
    }
}

data class PersonalRatingPage(
    val entries: List<PersonalRatingEntry>,
    val next: String? = null,
    val malformed: Int = 0,
    val truncating: Boolean = false,
)

interface PersonalRatingPort {

    val providerId: String

    fun capabilities(): PersonalRatingCapabilities

    fun accountConnected(): Boolean

    suspend fun resolveAccountIdentity(): String? = null

    suspend fun readLibrary(
        request: PersonalRatingLibraryRequest,
    ): PersonalRatingReadOutcome<PersonalRatingPage>

    suspend fun write(ref: PersonalRatingRef, score: Int): PersonalRatingWriteResult
}

sealed interface PersonalRatingState {
    data object Unknown : PersonalRatingState
    data object Loading : PersonalRatingState
    data object Unrated : PersonalRatingState
    data class Rated(val value: Int) : PersonalRatingState

    data class RatedFractional(val precise: Double) : PersonalRatingState

    data class Failed(val reason: String) : PersonalRatingState

    val knownValue: Int? get() = when (this) {
        is Rated -> value
        else -> null
    }
}
