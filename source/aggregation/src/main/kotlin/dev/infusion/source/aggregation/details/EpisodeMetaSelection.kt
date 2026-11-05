package dev.infusion.source.aggregation.details

import dev.infusion.core.model.TitleId
import dev.infusion.source.stremio.wire.StremioVideo

data class EpisodeMetaTarget(
    val parentSeriesTitleId: TitleId? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

fun episodeVideoEntry(
    videos: List<StremioVideo>,
    nativeResourceId: String?,
    target: EpisodeMetaTarget?,
): StremioVideo? {
    val exactId = nativeResourceId?.trim()?.takeIf { it.isNotEmpty() }
    if (exactId != null) {
        val match = videos.firstOrNull { it.id == exactId }
        if (match != null) return match
    }
    val season = target?.seasonNumber ?: return null
    val episode = target.episodeNumber ?: return null
    return videos.firstOrNull { it.season == season && it.episode == episode }
}

fun isEpisodeCoordinateLabel(
    displayTitle: String?,
    seasonNumber: Int?,
    episodeNumber: Int?,
): Boolean {
    val label = displayTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val season = seasonNumber ?: return false
    val episode = episodeNumber ?: return false
    return label.equals("S${season}E${episode}", ignoreCase = true)
}
