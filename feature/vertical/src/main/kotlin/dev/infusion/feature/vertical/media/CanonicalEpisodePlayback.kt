package dev.infusion.feature.vertical.media

import dev.infusion.core.model.TitleId
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.feature.vertical.model.SeriesDetailsUiState

fun interface EpisodePlayability {

    fun titleIdFor(seasonNumber: Int, episodeNumber: Int): TitleId?
}

val NoEpisodePlayability: EpisodePlayability = EpisodePlayability { _, _ -> null }

data class PlayableEpisodeRow(
    val episode: EpisodeCardUi,

    val playableTitleId: TitleId?,
) {

    val focusKey: String get() = episode.focusKey

    val isPlayable: Boolean get() = playableTitleId != null
}

fun CanonicalDetailsUiState.playableEpisodeRows(playability: EpisodePlayability?): List<PlayableEpisodeRow> =
    episodes.valueOrNull().orEmpty().map { episode ->
        PlayableEpisodeRow(
            episode = episode,
            playableTitleId = playability?.titleIdFor(episode.seasonNumber, episode.episodeNumber),
        )
    }

fun SeriesDetailsUiState.episodePlayability(seriesTitleId: TitleId): EpisodePlayability =
    EpisodePlayability { seasonNumber, episodeNumber ->

        WatchKey.episodeOrNull(seriesTitleId, seasonNumber, episodeNumber)
            ?.let { episodesByKey[it] }
            ?.episodeTitleId
    }
