package dev.infusion.tv.ui.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.infusion.tv.R
import dev.infusion.tv.ui.theme.InfusionTvAppearance
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.LocalInfusionAccent

private class CornerBadgeGeometry(val glyph: Dp, val padding: Dp, val inset: Dp)

@Composable
private fun cornerBadgeGeometry(artworkSize: IntSize): CornerBadgeGeometry? {
    if (artworkSize.width <= 0 || artworkSize.height <= 0) return null
    val minorSide = minOf(artworkSize.width, artworkSize.height).toFloat()
    val density = LocalDensity.current
    return with(density) {
        CornerBadgeGeometry(
            glyph = (minorSide * ContentCardPresentation.WatchedMarkGlyphFraction).toDp(),
            padding = (minorSide * ContentCardPresentation.WatchedMarkPaddingFraction).toDp(),
            inset = (minorSide * ContentCardPresentation.WatchedMarkInsetFraction).toDp(),
        )
    }
}

fun watchlistProviderLogoRes(providerId: String?): Int? = when (providerId) {
    WATCHLIST_PROVIDER_TRAKT -> R.drawable.ic_provider_trakt
    WATCHLIST_PROVIDER_SIMKL -> R.drawable.ic_provider_simkl
    WATCHLIST_PROVIDER_MDBLIST -> R.drawable.ic_provider_mdblist
    else -> null
}

const val WATCHLIST_PROVIDER_TRAKT: String = "trakt"
const val WATCHLIST_PROVIDER_SIMKL: String = "simkl"
const val WATCHLIST_PROVIDER_MDBLIST: String = "mdblist"

@Composable
private fun InfusionWatchlistBadgeGlyph(
    assetRes: Int,
    glyph: Dp,
    tint: Color? = null,
) {
    if (tint != null) {
        Icon(
            painter = painterResource(assetRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(glyph),
        )
    } else {
        Image(
            painter = painterResource(assetRes),
            contentDescription = null,
            modifier = Modifier.size(glyph),
        )
    }
}

@Composable
fun BoxScope.InfusionCardWatchlistBadge(
    artworkSize: IntSize,
    variant: WatchlistBadgeVariant,
    providerId: String?,
    tag: String? = null,
) {
    val geometry = cornerBadgeGeometry(artworkSize) ?: return
    val logoRes = if (variant == WatchlistBadgeVariant.REMOTE) {
        watchlistProviderLogoRes(providerId)
    } else {
        null
    }
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(geometry.inset)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        InfusionWatchlistBadgeGlyph(
            assetRes = logoRes ?: R.drawable.ic_badge_watchlist_cube,
            glyph = geometry.glyph,
            tint = if (logoRes == null) infusionCardWatchlistCubeTint() else null,
        )
    }
}

@Composable
fun BoxScope.InfusionCardAvailabilityBadge(
    artworkSize: IntSize,
    tag: String? = null,
) {
    val geometry = cornerBadgeGeometry(artworkSize) ?: return
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(geometry.inset)
            .clip(InfusionTvContour.Shape)
            .background(InfusionTvContour.CardBadgeScrim)
            .padding(geometry.padding)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_provider_jellyfin),
            contentDescription = null,
            modifier = Modifier.size(geometry.glyph),
        )
    }
}

@Composable
fun BoxScope.InfusionCardArtworkTitle(
    title: String,
    artworkSize: IntSize,
    tag: String? = null,
) {
    if (artworkSize.width <= 0 || artworkSize.height <= 0) return
    val minorSide = minOf(artworkSize.width, artworkSize.height).toFloat()
    val density = LocalDensity.current
    val edgeInset = with(density) {
        (minorSide * ContentCardPresentation.CornerBadgeFootprintFraction).toDp()
    }
    val bottomBand = with(density) {
        (
            ContentCardPresentation.ProgressBarBottomRisePx +
                minorSide * ContentCardPresentation.ProgressBarHeightFraction
            ).toDp()
    }
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(start = edgeInset, end = edgeInset, bottom = bottomBand + 4.dp)
            .fillMaxWidth()
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            style = InfusionTvScaleCalibration.Type.Emphasis,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(InfusionTvContour.OnArtworkScrim)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun BoxScope.InfusionCardCategoryTitle(
    title: String,
    artworkSize: IntSize,
    tag: String? = null,
    scrimTag: String? = null,
) {
    if (artworkSize.width <= 0 || artworkSize.height <= 0) return
    val minorSide = minOf(artworkSize.width, artworkSize.height).toFloat()
    val density = LocalDensity.current
    val edgeInset = with(density) {
        (minorSide * ContentCardPresentation.CornerBadgeFootprintFraction).toDp()
    }

    Box(
        modifier = Modifier
            .matchParentSize()
            .background(InfusionTvContour.CategoryTileScrim)
            .then(if (scrimTag != null) Modifier.testTag(scrimTag) else Modifier),
    )
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxWidth()
            .padding(horizontal = edgeInset)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            style = InfusionTvScaleCalibration.Type.Headline,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun BoxScope.InfusionCardProgressBar(
    artworkSize: IntSize,
    fraction: Float,
    tag: String? = null,

    fillTag: String? = null,
) {
    if (artworkSize.width <= 0 || artworkSize.height <= 0) return
    val clamped = fraction.coerceIn(0f, 1f)
    if (clamped <= 0f) return

    val minorSide = minOf(artworkSize.width, artworkSize.height).toFloat()
    val density = LocalDensity.current
    val barHeight = with(density) {
        (minorSide * ContentCardPresentation.ProgressBarHeightFraction).toDp()
    }
    val edgeInset = with(density) {
        (minorSide * ContentCardPresentation.CornerBadgeFootprintFraction).toDp()
    }

    val bottomRise = with(density) { ContentCardPresentation.ProgressBarBottomRisePx.toDp() }
    val barShape = RoundedCornerShape(barHeight / 2)

    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)

            .padding(start = edgeInset, end = edgeInset, bottom = bottomRise)
            .fillMaxWidth()
            .height(barHeight)
            .clip(barShape)
            .background(infusionCardProgressTrackColor())
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .fillMaxHeight()
                .background(infusionCardProgressFillColor())
                .then(if (fillTag != null) Modifier.testTag(fillTag) else Modifier),
        )
    }
}

@Composable
fun infusionCardWatchlistCubeTint(): Color = LocalInfusionAccent.current

@Composable
fun infusionCardProgressFillColor(): Color = LocalInfusionAccent.current

@Composable
fun infusionCardProgressTrackColor(): Color =
    InfusionTvAppearance.TextPrimary.copy(alpha = ContentCardPresentation.ProgressBarTrackAlpha)

fun contentCardWatchlistBadgeKey(cardKey: String): String = "card-watchlist-badge:$cardKey"

fun contentCardAvailabilityBadgeKey(cardKey: String): String = "card-availability-badge:$cardKey"

fun contentCardProgressBarKey(cardKey: String): String = "card-progress-bar:$cardKey"

fun contentCardProgressFillKey(cardKey: String): String = "card-progress-fill:$cardKey"

fun contentCardArtworkTitleKey(cardKey: String): String = "card-artwork-title:$cardKey"

fun contentCardCategoryScrimKey(cardKey: String): String = "card-category-scrim:$cardKey"
