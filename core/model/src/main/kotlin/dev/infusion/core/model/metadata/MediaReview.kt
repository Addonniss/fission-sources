package dev.infusion.core.model.metadata

import java.time.Instant

data class MediaReview(
    val reviewId: String,
    val author: String,
    val authorRating: Double? = null,
    val authorAvatar: ImageRef? = null,
    val content: String,
    val createdAt: Instant? = null,
    val providerLabel: String? = null,
) {
    init {
        require(reviewId.isNotBlank()) { "a review needs the provider's own review id" }
        require(author.isNotBlank()) { "a review needs an author" }
    }
}

data class ReviewPage(
    val items: List<MediaReview> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1,
    val totalResults: Int? = null,
) {

    val hasMore: Boolean get() = page < totalPages
}
