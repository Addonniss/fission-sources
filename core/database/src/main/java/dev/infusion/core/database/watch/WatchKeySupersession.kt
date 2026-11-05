package dev.infusion.core.database.watch

import dev.infusion.core.database.store.TitleSupersession
import dev.infusion.core.model.watch.WatchKey

internal fun WatchKey.canonicalForConvergence(supersession: TitleSupersession?): WatchKey {
    if (supersession == null) return this
    return if (isEpisode) {
        val series = seriesId ?: return this
        val survivor = supersession.survivorOf(series)
        if (survivor == series) {
            this
        } else {
            val alias = alternateOrderAlias
            val season = seasonNumber
            val episode = episodeNumber
            if (alias != null) {
                WatchKey.episodeByAlternateOrder(survivor, alias)
            } else if (season != null && episode != null) {
                WatchKey.episode(survivor, season, episode)
            } else {
                this
            }
        }
    } else {
        val title = titleId ?: return this
        val survivor = supersession.survivorOf(title)
        if (survivor == title) this else WatchKey.movie(survivor, editionId)
    }
}
