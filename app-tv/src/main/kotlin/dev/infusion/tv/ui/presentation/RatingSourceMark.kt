package dev.infusion.tv.ui.presentation

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.infusion.core.model.metadata.RatingAppearance
import dev.infusion.core.model.metadata.RatingMetric
import dev.infusion.tv.R
import dev.infusion.tv.ui.theme.InfusionTvContour

val LocalRatingAppearance = compositionLocalOf { RatingAppearance.Default }

const val RottenTomatoesFreshFraction: Double = 0.60

val RatingMarkSize: Dp = 18.dp

private const val JellyfinMarkScale = 0.9f

private val JellyfinCriticsMonochrome = Color(0xFF9E9E9E)

data class RatingMark(val res: Int, val tint: Color?, val size: Dp)

fun ratingMark(
    metric: RatingMetric?,
    fraction: Double,
    appearance: RatingAppearance,
): RatingMark? {
    if (!appearance.usesMark) return null
    val monochrome = appearance.isMonochrome
    val res = ratingMarkRes(metric, fraction, monochrome) ?: return null
    val size = if (metric == RatingMetric.JELLYFIN_COMMUNITY || metric == RatingMetric.JELLYFIN_CRITICS) {
        RatingMarkSize * JellyfinMarkScale
    } else {
        RatingMarkSize
    }
    val tint = when {
        !monochrome -> null
        metric == RatingMetric.JELLYFIN_CRITICS -> JellyfinCriticsMonochrome

        else -> InfusionTvContour.ControlContentColor
    }
    return RatingMark(res = res, tint = tint, size = size)
}

fun ratingMarkRes(metric: RatingMetric?, fraction: Double, monochrome: Boolean): Int? = when (metric) {
    RatingMetric.IMDB -> monoOr(R.drawable.ic_rating_imdb, R.drawable.ic_rating_imdb_mono, monochrome)
    RatingMetric.TMDB -> monoOr(R.drawable.ic_rating_tmdb, R.drawable.ic_rating_tmdb_mono, monochrome)
    RatingMetric.TRAKT -> monoOr(R.drawable.ic_rating_trakt, R.drawable.ic_rating_trakt_mono, monochrome)
    RatingMetric.METACRITIC -> monoOr(R.drawable.ic_rating_metacritic, R.drawable.ic_rating_metacritic_mono, monochrome)
    RatingMetric.LETTERBOXD -> monoOr(R.drawable.ic_rating_letterboxd, R.drawable.ic_rating_letterboxd_mono, monochrome)
    RatingMetric.MAL -> monoOr(R.drawable.ic_rating_mal, R.drawable.ic_rating_mal_mono, monochrome)
    RatingMetric.SIMKL -> monoOr(R.drawable.ic_rating_simkl, R.drawable.ic_rating_simkl_mono, monochrome)
    RatingMetric.RT_CRITICS -> rtStatusMark(
        fresh = R.drawable.ic_rating_rt_fresh,
        freshMono = R.drawable.ic_rating_rt_fresh_mono,
        rotten = R.drawable.ic_rating_rt_rotten,
        rottenMono = R.drawable.ic_rating_rt_rotten_mono,
        freshStatus = fraction >= RottenTomatoesFreshFraction,
        monochrome = monochrome,
    )
    RatingMetric.RT_AUDIENCE -> rtStatusMark(
        fresh = R.drawable.ic_rating_rt_audience_fresh,
        freshMono = R.drawable.ic_rating_rt_audience_fresh_mono,
        rotten = R.drawable.ic_rating_rt_audience_rotten,
        rottenMono = R.drawable.ic_rating_rt_audience_rotten_mono,
        freshStatus = fraction >= RottenTomatoesFreshFraction,
        monochrome = monochrome,
    )

    RatingMetric.JELLYFIN_COMMUNITY -> R.drawable.ic_rating_jellyfin
    RatingMetric.JELLYFIN_CRITICS -> R.drawable.ic_rating_jellyfin_critics
    null -> null
}

private fun monoOr(colour: Int, monochrome: Int, wantMonochrome: Boolean): Int =
    if (wantMonochrome) monochrome else colour

private fun rtStatusMark(
    fresh: Int,
    freshMono: Int,
    rotten: Int,
    rottenMono: Int,
    freshStatus: Boolean,
    monochrome: Boolean,
): Int = if (freshStatus) monoOr(fresh, freshMono, monochrome) else monoOr(rotten, rottenMono, monochrome)
