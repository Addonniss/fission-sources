package dev.infusion.tv.ui.screens

import androidx.compose.ui.unit.Dp

internal object SeriesStageOneComposition {

    const val StageOneTag: String = "series-details:stage1"

    const val EpisodeRowPeekFraction: Float = 0.30f

    fun peekBandPx(episodeRowGapPx: Int, episodeRowPx: Int): Int =
        episodeRowGapPx.coerceAtLeast(0) +
            (episodeRowPx.coerceAtLeast(0) * EpisodeRowPeekFraction).toInt()

    fun stageHeightPx(
        viewportPx: Int,
        episodeRowGapPx: Int,
        episodeRowPx: Int,
    ): Int = (viewportPx - peekBandPx(episodeRowGapPx, episodeRowPx)).coerceAtLeast(0)

    fun episodeRowVisibleShare(
        viewportPx: Int,
        stageHeightPx: Int,
        episodeRowGapPx: Int,
        episodeRowPx: Int,
    ): Float {
        if (episodeRowPx <= 0) return 0f
        val visible = viewportPx - stageHeightPx - episodeRowGapPx.coerceAtLeast(0)
        return (visible.toFloat() / episodeRowPx).coerceIn(0f, 1f)
    }

    fun actionRowLeadIn(lineHeight: Dp, freeSpaceBelowRow: Dp): Dp =
        minOf(lineHeight, freeSpaceBelowRow)
}

internal fun seriesSeasonsComposeEpisodeRow(
    canonicalSeasonsPrimary: Boolean,
    canonicalEpisodesReady: Boolean,
    sourceHierarchyLoaded: Boolean,
    sourceEpisodesLoaded: Boolean,
    sourceEpisodeCount: Int,
): Boolean = if (canonicalSeasonsPrimary) {
    canonicalEpisodesReady
} else {
    sourceHierarchyLoaded && sourceEpisodesLoaded && sourceEpisodeCount > 0
}

internal enum class SeriesEntryFraming {

    AT_REST,

    STAGE_TWO,
}

internal fun seriesEntryFraming(
    entryKey: String?,
    episodeRowComposed: Boolean,
): SeriesEntryFraming? = when {
    entryKey == null -> null
    entryKey in SeriesActionLineKeys -> SeriesEntryFraming.AT_REST
    episodeRowComposed -> SeriesEntryFraming.AT_REST
    else -> SeriesEntryFraming.STAGE_TWO
}

internal fun seriesSeasonsEpisodeRowPending(
    canonicalSeasonsPrimary: Boolean,
    canonicalEpisodesLoading: Boolean,
    canonicalSeasonOpening: Boolean,
    sourceHierarchyLoading: Boolean,
    sourceEpisodesLoading: Boolean,
): Boolean = if (canonicalSeasonsPrimary) {
    canonicalEpisodesLoading || canonicalSeasonOpening
} else {
    sourceHierarchyLoading || sourceEpisodesLoading
}

internal fun seriesAtRestFramingEstablished(
    stageOneBlockPx: Int,
    viewportPx: Int,
    seasonsSectionMeasured: Boolean,
    episodeRowPending: Boolean,
    episodeRowComposed: Boolean,
    episodeRowMeasured: Boolean,
): Boolean = stageOneBlockPx > 0 && seasonsSectionMeasured && when {
    episodeRowPending -> false
    episodeRowComposed -> episodeRowMeasured && stageOneBlockPx < viewportPx
    else -> true
}
