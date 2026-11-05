package dev.infusion.feature.vertical.player

import dev.infusion.feature.vertical.media.CanonicalHeroUi
import dev.infusion.feature.vertical.media.PersonCardUi
import dev.infusion.feature.vertical.media.RatingUi

data class PlayerMetadataPresentation(

    val hero: CanonicalHeroUi? = null,

    val isEpisode: Boolean = false,

    val episodeSynopsis: String? = null,

    val episodeCoordinate: String? = null,

    val cast: List<PersonCardUi> = emptyList(),

    val episodeRatings: List<RatingUi> = emptyList(),
) {

    val synopsis: String? get() = if (isEpisode) episodeSynopsis else hero?.overview

    val playbackRatings: List<RatingUi> get() = if (isEpisode) episodeRatings else hero?.ratings.orEmpty()

    companion object {

        val NONE: PlayerMetadataPresentation = PlayerMetadataPresentation()
    }
}
