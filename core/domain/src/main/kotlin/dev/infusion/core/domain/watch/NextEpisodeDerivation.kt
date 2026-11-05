package dev.infusion.core.domain.watch

import dev.infusion.core.model.TitleId
import dev.infusion.core.model.watch.WatchKey

object NextEpisodeDerivation {

    fun deriveNextEpisode(
        completedKey: WatchKey,
        catalogChildren: List<EpisodeCatalogEntry>,
    ): EpisodeCatalogEntry? {
        require(completedKey.isEpisode) {
            "next-episode derivation requires the completed unit to be an episode-shaped WatchKey, was $completedKey"
        }
        val seriesId = completedKey.seriesId ?: return null

        val season = completedKey.seasonNumber ?: return null
        val episode = completedKey.episodeNumber ?: return null

        if (catalogChildren.isEmpty()) return null
        val ordered = catalogChildren
            .filter { it.seriesTitleId == seriesId }
            .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))

        val next = deriveNextCoordinates(
            season = season,
            episode = episode,
            present = ordered.map { EpisodeCoordinates(it.seasonNumber, it.episodeNumber) },
        ) ?: return null
        return ordered.firstOrNull {
            it.seasonNumber == next.seasonNumber && it.episodeNumber == next.episodeNumber
        }
    }

    fun deriveNextCoordinates(
        season: Int,
        episode: Int,
        present: List<EpisodeCoordinates>,
    ): EpisodeCoordinates? {
        if (present.isEmpty()) return null
        val ordered = present.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))

        ordered.firstOrNull { it.seasonNumber == season && it.episodeNumber == episode + 1 }
            ?.let { return it }

        ordered.firstOrNull { it.seasonNumber == season + 1 && it.episodeNumber == 1 }
            ?.let { return it }

        return null
    }
}

data class EpisodeCoordinates(
    val seasonNumber: Int,
    val episodeNumber: Int,
)
