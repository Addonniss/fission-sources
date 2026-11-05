package dev.infusion.core.domain.catalog

import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleKind

fun CatalogEntry.toMediaPreview(): MediaPreview = MediaPreview(
    titleId = titleId,
    kind = kind,
    displayTitle = displayTitle,
    year = year,

    subtitle = null,
    artwork = MediaArtworkRef.catalog(nativeItemId),

    progressFraction = null,

    watchKey = episodeWatchKeyOrNull(),
)

private fun CatalogEntry.episodeWatchKeyOrNull(): dev.infusion.core.model.watch.WatchKey? {
    if (kind != TitleKind.EPISODE) return null
    val series = seriesTitleId ?: return null
    val season = seasonNumber?.takeIf { it > 0 } ?: return null
    val episode = episodeNumber?.takeIf { it > 0 } ?: return null
    return dev.infusion.core.model.watch.WatchKey.episode(series, season, episode)
}
