package dev.infusion.tv.ui.presentation

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleKind

@Immutable
data class CardAvailability(

    val provenUnitTitleIds: Set<String> = emptySet(),

    val provenSeriesTitleIds: Set<String> = emptySet(),

    val provenEpisodeKeys: Set<String> = emptySet(),
) {

    fun isUnitProven(titleId: String?): Boolean = titleId != null && titleId in provenUnitTitleIds

    fun isSeriesProven(titleId: String?): Boolean = titleId != null && titleId in provenSeriesTitleIds

    fun isEpisodeProven(seriesTitleId: String?, seasonNumber: Int?, episodeNumber: Int?): Boolean =
        seriesTitleId != null && seasonNumber != null && episodeNumber != null &&
            episodeKey(seriesTitleId, seasonNumber, episodeNumber) in provenEpisodeKeys

    companion object {

        val None: CardAvailability = CardAvailability()

        fun episodeKey(seriesTitleId: String, seasonNumber: Int, episodeNumber: Int): String =
            "$seriesTitleId|$seasonNumber|$episodeNumber"
    }
}

fun ContentCardPresentation.jellyfinAvailable(
    preview: MediaPreview,
    kind: TitleKind?,
    availability: CardAvailability,
): Boolean {
    if (!cardBadges || !jellyfinAvailabilityBadge) return false
    return when (kind) {
        TitleKind.MOVIE -> availability.isUnitProven(preview.titleId?.value?.toString())

        TitleKind.EPISODE -> jellyfinAvailableFor(
            unitTitleId = (preview.titleId ?: preview.watchKey?.titleId)?.value?.toString(),
            seriesTitleId = preview.watchKey?.seriesId?.value?.toString(),
            seasonNumber = preview.watchKey?.seasonNumber,
            episodeNumber = preview.watchKey?.episodeNumber,
            availability = availability,
        )
        TitleKind.SERIES -> availability.isSeriesProven(preview.titleId?.value?.toString())
        TitleKind.SEASON, TitleKind.CLIP, null -> false
    }
}

fun ContentCardPresentation.jellyfinAvailableFor(
    unitTitleId: String?,
    seriesTitleId: String?,
    seasonNumber: Int?,
    episodeNumber: Int?,
    availability: CardAvailability,
): Boolean {
    if (!cardBadges || !jellyfinAvailabilityBadge) return false
    return availability.isUnitProven(unitTitleId) ||
        availability.isEpisodeProven(seriesTitleId, seasonNumber, episodeNumber)
}

val LocalCardAvailability = compositionLocalOf { CardAvailability.None }
