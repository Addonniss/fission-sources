package dev.infusion.feature.vertical.player

import dev.infusion.core.domain.catalog.CatalogPort
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.metadata.MediaRating
import dev.infusion.core.model.metadata.ratingScoreText
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.feature.vertical.media.CanonicalDetailsStateHolder
import dev.infusion.feature.vertical.media.RatingUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlayerPresentationStateHolder(
    private val watchKey: WatchKey,
    private val titleId: TitleId,
    private val catalog: CatalogPort,
    private val canonical: CanonicalDetailsStateHolder?,
    private val scope: CoroutineScope,

    private val episodeRatingsEvidence: (suspend (seasonNumber: Int, episodeNumber: Int) -> List<MediaRating>)? = null,

    private val episodeRatingsProjection: (List<MediaRating>) -> List<MediaRating> = { it },
) {

    private val _state = MutableStateFlow(PlayerMetadataPresentation.NONE)
    val state: StateFlow<PlayerMetadataPresentation> = _state.asStateFlow()

    private var episodeSynopsis: String? = null

    private var episodeRatings: List<RatingUi> = emptyList()

    private val episodeCoordinate: String? = if (watchKey.isEpisode) {
        val season = watchKey.seasonNumber
        val episode = watchKey.episodeNumber
        if (season != null && episode != null) "S${season}E$episode" else null
    } else {
        null
    }

    init {
        val holder = canonical
        if (holder != null) {
            scope.launch {
                holder.state.collect { publish(it.hero, it.cast) }
            }
        } else {
            publish(null, emptyList())
        }
        if (watchKey.isEpisode && episodeCoordinate != null) {
            scope.launch {

                catalog.observeTitleDetails(titleId).collect { details ->
                    if (details != null) {
                        episodeSynopsis = details.title.synopsis
                        publish(canonical?.state?.value?.hero, canonical?.state?.value?.cast.orEmpty())
                    }
                }
            }
        }

        val evidence = episodeRatingsEvidence
        val season = watchKey.seasonNumber
        val episode = watchKey.episodeNumber
        if (watchKey.isEpisode && evidence != null && season != null && episode != null) {
            scope.launch {
                val ratings = runCatching { evidence(season, episode) }.getOrDefault(emptyList())
                episodeRatings = episodeRatingsProjection(ratings).map { rating ->
                    RatingUi(
                        label = rating.label,

                        text = ratingScoreText(rating.ratedMetric, rating.value),
                        fraction = rating.fraction,
                        metricId = rating.metric,
                    )
                }
                publish(canonical?.state?.value?.hero, canonical?.state?.value?.cast.orEmpty())
            }
        }
    }

    private fun publish(
        hero: dev.infusion.feature.vertical.media.CanonicalHeroUi?,
        cast: List<dev.infusion.feature.vertical.media.PersonCardUi>,
    ) {
        _state.value = PlayerMetadataPresentation(
            hero = hero,
            isEpisode = watchKey.isEpisode,
            episodeSynopsis = episodeSynopsis,
            episodeCoordinate = episodeCoordinate,
            cast = cast,
            episodeRatings = episodeRatings,
        )
    }
}
