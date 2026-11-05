package dev.infusion.tv.ui.presentation

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.infusion.core.artworkimage.LocalArtworkLoadEnabled
import dev.infusion.core.artworkimage.LocalArtworkLoader
import dev.infusion.core.artworkimage.MediaArtworkImage
import dev.infusion.core.domain.appearance.ArtworkStyle
import dev.infusion.core.domain.appearance.ProfileAppearancePreferences
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.landscapeSlot
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.core.model.statedLandscapeSlot
import dev.infusion.feature.vertical.artwork.SeriesLandscapeArtwork
import dev.infusion.feature.vertical.artwork.SeriesLandscapeState
import dev.infusion.tv.ui.theme.InfusionTvContour

enum class ContentCardShape {

    PORTRAIT,

    LANDSCAPE,

    SQUARE,
}

enum class ContentCardArtworkSource {

    PROJECTED,

    LANDSCAPE,
}

data class ContentCardPresentation(

    val moviesArtwork: ArtworkStyle = ArtworkStyle.DEFAULT_MOVIES,

    val seriesArtwork: ArtworkStyle = ArtworkStyle.DEFAULT_SERIES,

    val hideTitles: Boolean = false,

    val cardBadges: Boolean = true,

    val watchedCheckmark: Boolean = true,

    val hideWatchedTitles: Boolean = false,

    val hideEpisodeTitles: Boolean = false,

    val watchlistBadge: Boolean = true,

    val jellyfinAvailabilityBadge: Boolean = true,

    val progressBar: Boolean = true,

    val contextMenuBadge: Boolean = true,
) {

    fun shapeFor(kind: TitleKind?): ContentCardShape = when (kind) {
        TitleKind.MOVIE -> moviesArtwork.toShape()
        TitleKind.SERIES -> seriesArtwork.toShape()
        TitleKind.EPISODE -> EPISODE_SHAPE
        TitleKind.SEASON, TitleKind.CLIP, null -> ContentCardShape.PORTRAIT
    }

    fun aspectRatioFor(kind: TitleKind?): Float = aspectRatioForShape(shapeFor(kind))

    fun aspectRatioForShape(shape: ContentCardShape): Float = when (shape) {
        ContentCardShape.PORTRAIT -> PORTRAIT_ASPECT_RATIO
        ContentCardShape.LANDSCAPE -> LANDSCAPE_ASPECT_RATIO
        ContentCardShape.SQUARE -> SQUARE_ASPECT_RATIO
    }

    fun artworkContentScaleFor(kind: TitleKind?): ContentScale =
        artworkContentScaleForShape(shapeFor(kind))

    fun artworkContentScaleForShape(shape: ContentCardShape): ContentScale = when (shape) {
        ContentCardShape.PORTRAIT -> ContentScale.Crop
        ContentCardShape.LANDSCAPE -> ContentScale.Fit

        ContentCardShape.SQUARE -> ContentScale.Fit
    }

    fun artworkSourceFor(kind: TitleKind?): ContentCardArtworkSource = when {
        shapeFor(kind) != ContentCardShape.LANDSCAPE -> ContentCardArtworkSource.PROJECTED

        kind == TitleKind.EPISODE -> ContentCardArtworkSource.PROJECTED
        else -> ContentCardArtworkSource.LANDSCAPE
    }

    companion object {

        const val PORTRAIT_ASPECT_RATIO: Float = 2f / 3f

        const val LANDSCAPE_ASPECT_RATIO: Float = 16f / 9f

        const val SQUARE_ASPECT_RATIO: Float = 1f

        val EPISODE_SHAPE: ContentCardShape = ContentCardShape.LANDSCAPE

        val ContainerPadding: Dp = 6.dp

        val ArtworkTitleSpacing: Dp = 4.dp

        val TextLineSpacing: Dp = 2.dp

        val ArtworkStatusInset: Dp = 4.dp

        val ArtworkStatusPadding: Dp = 3.dp

        val ArtworkStatusSpacing: Dp = 6.dp

        const val WatchedMarkGlyphFraction: Float = 0.099f

        const val WatchedMarkPaddingFraction: Float = 0.028f

        const val WatchedMarkInsetFraction: Float = 0.035f

        const val CornerBadgeFootprintFraction: Float =
            WatchedMarkInsetFraction + 2f * WatchedMarkPaddingFraction + WatchedMarkGlyphFraction

        const val ProgressBarHeightFraction: Float = 0.022f

        const val ProgressBarBottomRisePx: Float = 6f

        const val ProgressBarTrackAlpha: Float = 0.38f

        val Default: ContentCardPresentation = ContentCardPresentation()
    }
}

fun ArtworkStyle.toShape(): ContentCardShape = when (this) {
    ArtworkStyle.POSTER -> ContentCardShape.PORTRAIT
    ArtworkStyle.LANDSCAPE -> ContentCardShape.LANDSCAPE
}

fun ProfileAppearancePreferences.toContentCardPresentation(): ContentCardPresentation =
    ContentCardPresentation(
        moviesArtwork = moviesArtwork,
        seriesArtwork = seriesArtwork,
        hideTitles = hideTitles,
        cardBadges = cardBadges,
        watchedCheckmark = watchedCheckmark,
        hideWatchedTitles = hideWatchedTitles,
        hideEpisodeTitles = hideEpisodeTitles,
        watchlistBadge = watchlistBadge,
        jellyfinAvailabilityBadge = jellyfinAvailabilityBadge,
        progressBar = progressBar,
        contextMenuBadge = contextMenuBadge,
    )

val LocalContentCardPresentation = compositionLocalOf { ContentCardPresentation.Default }

val LocalContentCardArtworkScale = compositionLocalOf { ContentScale.Crop }

val LocalContentCardArtworkSource =
    compositionLocalOf { ContentCardArtworkSource.PROJECTED }

enum class ContentCardArtworkState {
    PENDING,
    PRESENT,
    ABSENT,
}

class CardArtworkSettlement {

    var settledLocator: String? by mutableStateOf(null)
        private set

    var settledDrawn: Boolean by mutableStateOf(false)
        private set

    fun record(locator: String?, drawn: Boolean) {
        if (locator == null) return
        settledLocator = locator
        settledDrawn = drawn
    }

    fun stateOf(locator: String?): ContentCardArtworkState = when {
        locator == null -> ContentCardArtworkState.PENDING
        settledLocator != locator -> ContentCardArtworkState.PENDING
        settledDrawn -> ContentCardArtworkState.PRESENT
        else -> ContentCardArtworkState.ABSENT
    }
}

val LocalContentCardArtworkSettlement = compositionLocalOf<CardArtworkSettlement?> { null }

@Composable
fun MediaCardArtwork(
    preview: MediaPreview,
    targetSize: IntSize,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {

    val presentation = LocalContentCardPresentation.current
    val englishLandscape = presentation.englishLandscapeRefFor(preview)

    val choice = presentation.cardArtworkChoice(preview, englishLandscape)
    val loadEnabled = LocalArtworkLoadEnabled.current

    val settlement = LocalContentCardArtworkSettlement.current
    var preferredMissed by remember(choice.preferred) { mutableStateOf(false) }
    MediaArtworkImage(
        ref = choice.draw(preferredAnswered = !preferredMissed),
        targetSize = targetSize,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = LocalContentCardArtworkScale.current,
        retainsPreviousSuccess = false,

        presentationRole = dev.infusion.core.model.ArtworkPresentationRole.LANDSCAPE_CARD,

        onSettled = { loaded ->
            if (!loaded && loadEnabled && choice.fallback != null) preferredMissed = true

            if (loadEnabled) settlement?.record(locator = choice.preferred?.locator, drawn = loaded)
        },
    )
}

fun ContentCardArtworkSource.artworkRefFor(preview: MediaPreview): MediaArtworkRef? = when (this) {
    ContentCardArtworkSource.PROJECTED -> preview.artwork
    ContentCardArtworkSource.LANDSCAPE -> preview.artwork?.landscapeSlot()
}

fun ContentCardPresentation.drawableArtworkRefFor(preview: MediaPreview): MediaArtworkRef? =
    when (val source = artworkSourceFor(preview.kind)) {
        ContentCardArtworkSource.PROJECTED -> source.artworkRefFor(preview)
        ContentCardArtworkSource.LANDSCAPE -> preview.artwork?.statedLandscapeSlot()
    }

fun ContentCardPresentation.artworkRefFor(preview: MediaPreview): MediaArtworkRef? =
    artworkSourceFor(preview.kind).artworkRefFor(preview)

fun ContentCardPresentation.artworkRefFor(
    preview: MediaPreview,
    englishLandscape: MediaArtworkRef?,
): MediaArtworkRef? =
    if (usesEnglishLandscape(preview.kind)) {
        englishLandscape ?: artworkRefFor(preview)
    } else {
        artworkRefFor(preview)
    }

data class ContentCardArtworkChoice(
    val preferred: MediaArtworkRef?,
    val fallback: MediaArtworkRef?,
) {

    fun draw(preferredAnswered: Boolean): MediaArtworkRef? = when {
        fallback == null -> preferred
        preferred == null -> fallback
        preferredAnswered -> preferred
        else -> fallback
    }
}

fun ContentCardPresentation.cardArtworkChoice(
    preview: MediaPreview,
    englishLandscape: MediaArtworkRef?,
): ContentCardArtworkChoice {
    val preferred = artworkRefFor(preview, englishLandscape)
    val owner = artworkRefFor(preview)

    val fallback = owner?.takeIf { it != preferred } ?: preview.fallbackArtwork
    return ContentCardArtworkChoice(
        preferred = preferred,
        fallback = fallback?.takeIf { it != preferred },
    )
}

fun ContentCardPresentation.usesEnglishLandscape(kind: TitleKind?): Boolean =
    hideTitles &&
        shapeFor(kind) == ContentCardShape.LANDSCAPE &&
        (kind == TitleKind.SERIES || kind == TitleKind.MOVIE)

private fun TitleKind?.toMediaTypeOrNull(): MediaType? = when (this) {
    TitleKind.MOVIE -> MediaType.MOVIE
    TitleKind.SERIES -> MediaType.SHOW
    TitleKind.SEASON, TitleKind.EPISODE, TitleKind.CLIP, null -> null
}

fun ContentCardPresentation.hidesBelowArtworkTitle(
    kind: TitleKind?,
    identityArtwork: SeriesLandscapeState,
): Boolean = when {
    !hideTitles -> false
    !usesEnglishLandscape(kind) -> true
    else -> identityArtwork != SeriesLandscapeState.ABSENT
}

fun ContentCardPresentation.statesTitleOnArtwork(
    kind: TitleKind?,
    identityArtwork: SeriesLandscapeState,
    ownArtwork: ContentCardArtworkState,
): Boolean = hideTitles && when {
    usesEnglishLandscape(kind) -> identityArtwork == SeriesLandscapeState.ABSENT
    kind == TitleKind.SERIES || kind == TitleKind.MOVIE ->
        ownArtwork == ContentCardArtworkState.ABSENT
    else -> false
}

@Composable
fun ContentCardPresentation.cardArtworkState(
    preview: MediaPreview,
    settlement: CardArtworkSettlement,
): ContentCardArtworkState {
    val choice = cardArtworkChoice(preview, englishLandscape = null)
    val preferred = choice.preferred
    if (preferred == null && choice.fallback == null) return ContentCardArtworkState.ABSENT

    val live = settlement.stateOf(preferred?.locator)
    if (live != ContentCardArtworkState.PENDING) return live

    val loader = LocalArtworkLoader.current
    val rememberedAbsent = preferred?.let { ref ->
        loader != null && loader.settledAbsent(ref.locator, ref.policy)
    } == true
    return if (rememberedAbsent) ContentCardArtworkState.ABSENT else ContentCardArtworkState.PENDING
}

val LocalSeriesLandscapeArtwork = compositionLocalOf { SeriesLandscapeArtwork.None }

@Composable
fun ContentCardPresentation.identityArtworkState(preview: MediaPreview): SeriesLandscapeState {
    val titleId = preview.titleId
    val type = preview.kind.toMediaTypeOrNull()
    if (titleId == null || type == null || !usesEnglishLandscape(preview.kind)) {
        return SeriesLandscapeState.ABSENT
    }
    val seam = LocalSeriesLandscapeArtwork.current
    val settlement = remember(seam, titleId, type) { seam.englishLandscapeState(titleId, type) }
    return settlement.collectAsState(initial = SeriesLandscapeState.PENDING).value
}

@Composable
fun ContentCardPresentation.englishLandscapeRefFor(preview: MediaPreview): MediaArtworkRef? {
    val seam = LocalSeriesLandscapeArtwork.current
    val titleId = preview.titleId
    val type = preview.kind.toMediaTypeOrNull()
    if (titleId == null || type == null || !usesEnglishLandscape(preview.kind)) return null
    val englishArtwork = remember(seam, titleId, type) { seam.englishLandscape(titleId, type) }
    LaunchedEffect(seam, titleId, type) { seam.demand(titleId, type) }
    val resolved by englishArtwork.collectAsState(initial = null)
    return resolved
}

@Composable
fun Modifier.contentCardArtwork(
    kind: TitleKind?,
    shapeOverride: ContentCardShape? = null,
): Modifier {
    val policy = LocalContentCardPresentation.current
    val shape = shapeOverride ?: policy.shapeFor(kind)
    return aspectRatio(policy.aspectRatioForShape(shape))
}

fun Modifier.contentCardArtworkClip(): Modifier = clip(InfusionTvContour.ArtworkShape)

fun contentCardArtworkKey(cardKey: String): String = "card-artwork:$cardKey"

@Composable
fun contentCardArtworkTarget(kind: TitleKind?, shapeOverride: ContentCardShape? = null): IntSize {
    val policy = LocalContentCardPresentation.current
    val shape = shapeOverride ?: policy.shapeFor(kind)
    return contentCardArtworkTargetPx(
        rowViewport = LocalContentRowViewport.current,
        shape = shape,
        aspectRatio = policy.aspectRatioForShape(shape),
        density = LocalDensity.current.density,
    )
}

fun contentCardArtworkTargetPx(
    rowViewport: Dp,
    shape: ContentCardShape,
    aspectRatio: Float,
    density: Float,
): IntSize {
    val width = ContentRowDensity.cardWidth(rowViewport, shape)
    val height = if (aspectRatio > 0f) width / aspectRatio else 0.dp
    return IntSize(
        (width.value * density).roundToIntF(),
        (height.value * density).roundToIntF(),
    )
}

private fun Float.roundToIntF(): Int = kotlin.math.floor(this + 0.5f).toInt()

@Composable
fun artworkTargetPx(size: DpSize): IntSize = with(LocalDensity.current) {
    IntSize(size.width.roundToPx(), size.height.roundToPx())
}
