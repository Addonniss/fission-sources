package dev.infusion.source.aggregation.resolve

import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.source.stremio.policy.StremioContentType

object StremioRouteType {

    fun of(kind: TitleKind?, address: EpisodeAddress?): StremioContentType = when {
        kind == TitleKind.EPISODE || kind == TitleKind.SEASON -> StremioContentType.SERIES
        kind == null && address?.seriesTitleId != null -> StremioContentType.SERIES
        else -> ofCanonicalKind(kind)
    }

    fun ofCanonicalKind(kind: TitleKind?): StremioContentType = when (kind) {
        TitleKind.MOVIE -> StremioContentType.MOVIE
        TitleKind.SERIES, TitleKind.SEASON, TitleKind.EPISODE -> StremioContentType.SERIES
        null, TitleKind.CLIP -> StremioContentType.MOVIE
    }

    fun ofPlaybackAddress(watchKey: WatchKey): StremioContentType =
        of(kind = if (watchKey.isEpisode) TitleKind.EPISODE else TitleKind.MOVIE, address = null)
}
