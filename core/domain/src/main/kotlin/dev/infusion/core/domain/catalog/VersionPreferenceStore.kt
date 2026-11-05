package dev.infusion.core.domain.catalog

import dev.infusion.core.model.EditionId
import dev.infusion.core.model.SourceId
import dev.infusion.core.model.TitleId

data class VersionPreference(
    val editionId: EditionId,

    val sourceId: SourceId,
)

fun movieUnitKey(titleId: TitleId): String = titleId.value.toString()

fun episodeUnitKey(seriesId: TitleId, seasonNumber: Int, episodeNumber: Int): String =
    "S:${seriesId.value}:$seasonNumber:$episodeNumber"

data class EpisodeUnitCoordinate(
    val seriesTitleId: TitleId,
    val seasonNumber: Int,
    val episodeNumber: Int,
)

fun parseEpisodeUnitKey(unitKey: String): EpisodeUnitCoordinate? {
    if (!unitKey.startsWith("S:")) return null
    val parts = unitKey.removePrefix("S:").split(':')
    if (parts.size != 3) return null
    val seriesTitleId = runCatching { TitleId.fromString(parts[0]) }.getOrNull() ?: return null
    val season = parts[1].toIntOrNull() ?: return null
    val episode = parts[2].toIntOrNull() ?: return null

    if (season < 0 || episode <= 0) return null
    return EpisodeUnitCoordinate(seriesTitleId, season, episode)
}

interface VersionPreferenceStore {
    fun preferredVersion(profileId: String, unitKey: String): VersionPreference?

    fun setPreferredVersion(profileId: String, unitKey: String, preference: VersionPreference?)

    fun clearForSource(sourceId: String)
}
