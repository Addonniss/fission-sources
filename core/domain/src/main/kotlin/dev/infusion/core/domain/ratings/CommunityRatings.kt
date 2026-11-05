package dev.infusion.core.domain.ratings

import dev.infusion.core.model.metadata.MediaIdentity
import dev.infusion.core.model.metadata.MediaRating
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.core.model.metadata.RatingMetric
import dev.infusion.core.model.metadata.RatingSupplier

sealed interface OmdbTestOutcome {

    data object Valid : OmdbTestOutcome

    data object MissingKey : OmdbTestOutcome

    data object InvalidKey : OmdbTestOutcome

    data object QuotaExhausted : OmdbTestOutcome

    data class Unreachable(val reason: String) : OmdbTestOutcome
}

interface CommunityRatingsPort {

    fun availableSuppliers(): Set<RatingSupplier>

    suspend fun ratingsFor(
        identity: MediaIdentity,
        type: MediaType,
        preferences: RatingsPreferences,
        coveredMetrics: Set<RatingMetric>,
    ): List<MediaRating>

    suspend fun testOmdb(candidateKey: String?): OmdbTestOutcome

    fun availableEpisodeMetrics(): Set<RatingMetric>

    suspend fun episodeRatingsFor(
        identity: MediaIdentity,
        seasonNumber: Int,
        preferences: RatingsPreferences,
        wantedMetrics: Set<RatingMetric>,
    ): Map<Int, List<MediaRating>>
}

interface RatingsCredentialStore {

    fun hasOmdbKey(): Boolean

    fun saveOmdbKey(key: String)

    fun removeOmdbKey()

    fun omdbKey(): String?

    fun clearProfile(profileId: String)

    fun clearAllProfiles()
}

fun projectRatings(
    ratings: List<MediaRating>,
    preferences: RatingsPreferences,
): List<MediaRating> {
    if (ratings.isEmpty()) return emptyList()
    val rank = supplierRanking(preferences)
    return ratings

        .filter { rating ->
            rating.value.isFinite() && rating.value > 0.0 && rating.value <= rating.max + 1e-9
        }

        .filter { rating ->
            val supplier = rating.ratedSupplier
            supplier == null || !supplier.configurable || supplier in preferences.enabledSuppliers
        }
        .mapNotNull { rating -> rating.ratedMetric?.let { it to rating } }
        .filter { (metric, _) -> preferences.displays(metric) }
        .groupBy({ it.first }, { it.second })
        .mapNotNull { (metric, candidates) ->
            candidates.minWithOrNull(
                compareBy(
                    { rank[it.ratedSupplier] ?: Int.MAX_VALUE },
                    { -it.value },
                ),
            )?.let { metric to it }
        }
        .sortedBy { (metric, _) -> metric.ordinal }
        .map { (_, rating) -> rating }
}

fun projectEpisodeRatings(
    ratings: List<MediaRating>,
    preferences: RatingsPreferences,
): List<MediaRating> {
    if (ratings.isEmpty()) return emptyList()
    val projected = projectRatings(ratings, preferences.copy(displayedMetrics = preferences.episodeMetrics))
    if (projected.isEmpty()) return emptyList()
    val order = preferences.episodeRatingOrder
    if (order.isEmpty()) return emptyList()
    val rank = order.withIndex().associate { (index, metric) -> metric to index }
    return projected.sortedBy { rating -> rank[rating.ratedMetric] ?: Int.MAX_VALUE }
}

internal fun supplierRanking(preferences: RatingsPreferences): Map<RatingSupplier?, Int> {
    val order = buildList {
        addAll(preferences.walkOrder)

        listOf(RatingSupplier.TMDB, RatingSupplier.JELLYFIN, RatingSupplier.ADDON)
            .filterNot { it in preferences.walkOrder }
            .forEach { add(it) }
    }
    return order.withIndex().associate { (index, supplier) -> supplier to index }
}
