package dev.infusion.core.domain.watch

import dev.infusion.core.model.TitleId
import dev.infusion.core.model.watch.WatchKey

data class EpisodeCatalogEntry(
    val seriesTitleId: TitleId,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitleId: TitleId,
    val displayTitle: String? = null,
) {
    val watchKey: WatchKey
        get() = WatchKey.episode(seriesTitleId, seasonNumber, episodeNumber)
}

object NextUnwatchedDerivation {

    fun deriveNextUnwatched(
        episodes: List<EpisodeCatalogEntry>,
        watchStates: Map<WatchKey, dev.infusion.core.model.watch.WatchState>,
    ): EpisodeCatalogEntry? {
        if (episodes.isEmpty()) return null
        val ordered = episodes.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))

        ordered.firstOrNull { ep ->
            watchStates[ep.watchKey]?.status == dev.infusion.core.model.watch.WatchStatus.IN_PROGRESS
        }?.let { return it }

        val completed = ordered.filter { ep ->
            watchStates[ep.watchKey]?.status == dev.infusion.core.model.watch.WatchStatus.COMPLETED
        }
        if (completed.isNotEmpty()) {
            val latestCompleted = completed.last()
            val afterLatest = ordered.dropWhile { it != latestCompleted }.drop(1)
            afterLatest.firstOrNull { ep ->
                val status = watchStates[ep.watchKey]?.status
                status == null || status == dev.infusion.core.model.watch.WatchStatus.EXPLICITLY_RESET
            }?.let { return it }
        }

        return ordered.firstOrNull()
    }
}
