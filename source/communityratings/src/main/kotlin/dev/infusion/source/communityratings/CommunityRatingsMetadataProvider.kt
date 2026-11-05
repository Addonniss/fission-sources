package dev.infusion.source.communityratings

import dev.infusion.core.domain.metadata.MetadataLocale
import dev.infusion.core.domain.metadata.MetadataProvider
import dev.infusion.core.domain.metadata.SeasonEvidenceDecorator
import dev.infusion.core.domain.ratings.CommunityRatingsPort
import dev.infusion.core.domain.ratings.RatingsPreferencesStore
import dev.infusion.core.model.metadata.MediaDetails
import dev.infusion.core.model.metadata.MediaIdentity
import dev.infusion.core.model.metadata.MediaRating
import dev.infusion.core.model.metadata.MovieDetails
import dev.infusion.core.model.metadata.RatingMetric
import dev.infusion.core.model.metadata.RatingSupplier
import dev.infusion.core.model.metadata.SeasonDetails
import dev.infusion.core.model.metadata.ShowDetails
import kotlinx.coroutines.withTimeoutOrNull

class CommunityRatingsMetadataProvider(
    private val delegate: MetadataProvider,
    private val ratings: CommunityRatingsPort,
    private val preferences: RatingsPreferencesStore,

    private val alreadyHeld: suspend (MediaIdentity) -> List<MediaRating> = { emptyList() },
) : MetadataProvider by delegate, SeasonEvidenceDecorator {

    override suspend fun details(identity: MediaIdentity, locale: MetadataLocale): MediaDetails? {
        val base = delegate.details(identity, locale) ?: return null
        val prefs = preferences.current()

        val canonicalRatings = base.ratings.filterNot { it.ratedSupplier in RatingSupplier.Configurable }
        val baseMetrics = canonicalRatings.mapNotNull { it.ratedMetric }.toSet()

        val held = runCatching { alreadyHeld(identity) }.getOrDefault(emptyList())
            .filter { rating -> rating.ratedMetric?.let { it !in baseMetrics } == true }

        val covered = baseMetrics + held.mapNotNull { it.ratedMetric }

        val community = runCatching {
            withTimeoutOrNull(COMMUNITY_DECORATION_BUDGET_MS) {
                ratings.ratingsFor(identity, base.mediaType, prefs, covered)
            }
        }.getOrNull().orEmpty()

        val byMetric = LinkedHashMap<RatingMetric, MediaRating>()
        (held + community).forEach { rating ->
            val metric = rating.ratedMetric ?: return@forEach
            if (metric in baseMetrics) return@forEach
            byMetric.putIfAbsent(metric, rating)
        }
        if (byMetric.isEmpty()) {
            return if (canonicalRatings.size == base.ratings.size) base else base.withRatings(canonicalRatings)
        }
        return base.withRatings(canonicalRatings + byMetric.values)
    }

    override suspend fun season(
        identity: MediaIdentity,
        seasonNumber: Int,
        locale: MetadataLocale,
    ): SeasonDetails? {
        val base = delegate.season(identity, seasonNumber, locale) ?: return null
        return decorateSeason(base, identity, seasonNumber, locale)
    }

    override suspend fun decorateSeason(
        season: SeasonDetails,
        identity: MediaIdentity,
        seasonNumber: Int,
        locale: MetadataLocale,
    ): SeasonDetails {
        if (season.episodes.isEmpty()) return season
        val stripped = season.episodes.map { episode ->
            val kept = episode.ratings.filterNot { it.ratedSupplier in RatingSupplier.Configurable }
            if (kept.size == episode.ratings.size) episode else episode.copy(ratings = kept)
        }
        val prefs = preferences.current()
        val available = runCatching { ratings.availableEpisodeMetrics() }.getOrDefault(emptySet())
        val wanted = prefs.episodeMetrics.intersect(available)
        if (wanted.isEmpty()) return season.copy(episodes = stripped)
        val community = runCatching {
            ratings.episodeRatingsFor(identity, seasonNumber, prefs, wanted)
        }.getOrDefault(emptyMap())
        if (community.isEmpty()) return season.copy(episodes = stripped)
        return season.copy(
            episodes = stripped.map { episode ->
                val present = (episode.ratings + listOfNotNull(episode.rating))
                    .mapNotNull { it.ratedMetric }
                    .toSet()
                val extra = community[episode.episodeNumber].orEmpty()
                    .filterNot { it.ratedMetric in present }
                if (extra.isEmpty()) episode else episode.copy(ratings = episode.ratings + extra)
            },
        )
    }

    private companion object {

        const val COMMUNITY_DECORATION_BUDGET_MS: Long = 2_500L
    }
}

private fun MediaDetails.withRatings(value: List<MediaRating>): MediaDetails = when (this) {
    is MovieDetails -> copy(ratings = value)
    is ShowDetails -> copy(ratings = value)
}
