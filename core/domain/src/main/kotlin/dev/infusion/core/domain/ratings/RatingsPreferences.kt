package dev.infusion.core.domain.ratings

import dev.infusion.core.model.metadata.RatingAppearance
import dev.infusion.core.model.metadata.RatingMetric
import dev.infusion.core.model.metadata.RatingSupplier

data class RatingsPreferences(
    val supplierOrder: List<RatingSupplier> = RatingSupplier.DefaultOrder,
    val enabledSuppliers: Set<RatingSupplier> = RatingSupplier.DefaultEnabled,
    val displayedMetrics: Set<RatingMetric> = RatingMetric.DefaultDisplayed,
    val appearance: RatingAppearance = RatingAppearance.Default,
    val episodeSourceOrder: List<RatingMetric> = RatingMetric.DefaultEpisodeOrder,
    val episodeEnabledMetrics: Set<RatingMetric> = RatingMetric.DefaultEpisode,
) {

    init {
        require(episodeSourceOrder.distinct().size == episodeSourceOrder.size) {
            "episode sources must be distinct"
        }
        require(episodeSourceOrder.all { it in RatingMetric.SortableEpisodeSources }) {
            "only selectable exact-episode sources may be ordered"
        }
        require(episodeEnabledMetrics.all { it in RatingMetric.SortableEpisodeSources }) {
            "only selectable exact-episode sources may be enabled"
        }
    }

    val episodeMetrics: Set<RatingMetric>
        get() = episodeEnabledMetrics

    val episodeRatingOrder: List<RatingMetric>
        get() = episodeSourceOrder.filter { it in episodeEnabledMetrics }

    val walkOrder: List<RatingSupplier>
        get() {
            val seen = LinkedHashSet<RatingSupplier>()
            supplierOrder.forEach { if (it.configurable) seen.add(it) }
            RatingSupplier.Configurable.forEach { seen.add(it) }
            return seen.filter { it in enabledSuppliers }
        }

    fun isEnabled(supplier: RatingSupplier): Boolean = supplier in enabledSuppliers

    fun displays(metric: RatingMetric): Boolean = metric in displayedMetrics

    fun displaysOnEpisodes(metric: RatingMetric): Boolean = metric in episodeMetrics

    fun isEpisodeSourceEnabled(metric: RatingMetric): Boolean = metric in episodeEnabledMetrics

    companion object {

        val Default: RatingsPreferences = RatingsPreferences()
    }
}

interface RatingsPreferencesStore {

    fun current(): RatingsPreferences

    fun record(preferences: RatingsPreferences)

    fun clearProfile(profileId: String)

    fun clearAllProfiles()
}
