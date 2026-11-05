package dev.infusion.core.domain.ratings

import dev.infusion.core.model.metadata.MediaRating
import dev.infusion.core.model.metadata.RatingMetric
import dev.infusion.core.model.metadata.RatingSupplier

data class JellyfinRatingsEvidence(

    val community: Double? = null,

    val critics: Double? = null,
) {

    val isEmpty: Boolean get() = community == null && critics == null

    fun ratings(): List<MediaRating> = buildList {
        community?.let { value ->
            if (value.isFinite() && value > 0.0 && value <= RatingMetric.JELLYFIN_COMMUNITY.defaultMax) {
                add(generic(RatingMetric.JELLYFIN_COMMUNITY, value))
            }
        }
        critics?.let { value ->
            if (value.isFinite() && value > 0.0 && value <= RatingMetric.JELLYFIN_CRITICS.defaultMax) {
                add(generic(RatingMetric.JELLYFIN_CRITICS, value))
            }
        }
    }

    private fun generic(metric: RatingMetric, value: Double): MediaRating = MediaRating(
        source = metric.id,
        value = value,
        max = metric.defaultMax,
        votes = null,
        label = metric.chipLabel,
        supplier = RatingSupplier.JELLYFIN.id,
    )
}

interface JellyfinRatingsEvidenceStore {

    suspend fun record(identityKey: String, evidence: JellyfinRatingsEvidence)

    suspend fun load(identityKey: String): JellyfinRatingsEvidence?
}
