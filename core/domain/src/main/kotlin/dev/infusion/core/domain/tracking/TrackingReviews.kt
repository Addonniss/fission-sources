package dev.infusion.core.domain.tracking

import dev.infusion.core.model.metadata.ReviewPage

data class TrackingReviewQuery(
    val externalIds: TrackingExternalIds,
    val kind: TrackingMediaKind,
) {
    init {
        require(!externalIds.isEmpty) {
            "a review query requires at least one proven external identifier to be addressable"
        }
        require(kind == TrackingMediaKind.MOVIE || kind == TrackingMediaKind.SHOW) {
            "reviews are read for a movie or a show only"
        }
    }
}

interface TrackingReviewReadPort {

    val providerId: String

    val displayName: String

    suspend fun readReviews(
        query: TrackingReviewQuery,
        page: Int,
        limit: Int,
    ): TrackingInboundOutcome<ReviewPage>

    companion object {

        const val DEFAULT_LIMIT: Int = 20
        const val MAX_LIMIT: Int = 50
    }
}
