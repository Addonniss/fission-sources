package dev.infusion.core.model.watch

import dev.infusion.core.model.EditionId
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId

data class WatchKey private constructor(
    val titleId: TitleId? = null,
    val editionId: EditionId? = null,
    val seriesId: TitleId? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val alternateOrderAlias: TypedExternalId? = null,
) {
    init {
        val movieShape = titleId != null &&
            seriesId == null && seasonNumber == null && episodeNumber == null && alternateOrderAlias == null

        val hasSeason = seasonNumber != null
        val hasEpisode = episodeNumber != null
        val numbered = hasSeason && hasEpisode
        val byAlias = alternateOrderAlias != null
        val episodeShape = seriesId != null && titleId == null &&
            hasSeason == hasEpisode && numbered != byAlias
        require(movieShape || episodeShape) {
            "WatchKey must be a movie (titleId + optional editionId) or an episode " +
                "(seriesId + season/episode, or seriesId + alternate-order alias)"
        }
        require(seasonNumber == null || seasonNumber > 0) { "seasonNumber must be positive" }
        require(episodeNumber == null || episodeNumber > 0) { "episodeNumber must be positive" }
    }

    val isEpisode: Boolean get() = seriesId != null

    companion object {

        fun movie(titleId: TitleId, editionId: EditionId? = null): WatchKey =
            WatchKey(titleId = titleId, editionId = editionId)

        fun episode(seriesId: TitleId, seasonNumber: Int, episodeNumber: Int): WatchKey =
            WatchKey(
                seriesId = seriesId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
            )

        fun episodeOrNull(seriesId: TitleId, seasonNumber: Int, episodeNumber: Int): WatchKey? =
            if (seasonNumber > 0 && episodeNumber > 0) {
                episode(seriesId, seasonNumber, episodeNumber)
            } else {
                null
            }

        fun episodeByAlternateOrder(seriesId: TitleId, alias: TypedExternalId): WatchKey =
            WatchKey(seriesId = seriesId, alternateOrderAlias = alias)
    }
}
