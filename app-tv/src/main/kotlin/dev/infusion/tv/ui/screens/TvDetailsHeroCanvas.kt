@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package dev.infusion.tv.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.tv.material3.MaterialTheme
import dev.infusion.core.artworkimage.ArtworkImage
import dev.infusion.core.artworkimage.ArtworkRequest
import dev.infusion.core.artworkimage.LocalArtworkLoader
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.feature.vertical.media.CanonicalHeroUi
import dev.infusion.feature.vertical.model.DetailsArtwork
import dev.infusion.tv.ui.presentation.artworkTargetPx
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvDetailsMetrics
import dev.infusion.tv.ui.theme.InfusionTvMotion

internal const val HeroBackdropTag = "canonical:hero:backdrop"

internal const val HeroBackdropArtTag = "canonical:hero:backdrop:art"

internal const val HeroPosterTag = "canonical:hero:poster"
internal const val HeroPosterArtTag = "canonical:hero:poster:art"
internal const val HeroGradientTag = "canonical:hero:gradient"
internal const val HeroUnderlayTag = "canonical:hero:underlay"
internal const val HeroReadabilityLeftTag = "canonical:hero:readability:left"
internal const val HeroReadabilityBottomTag = "canonical:hero:readability:bottom"

internal fun fullscreenBackdropArtwork(
    ref: MediaArtworkRef?,
): MediaArtworkRef? = ref?.takeIf { artwork -> artwork.policy == ArtworkPolicy.BACKDROP }

internal fun posterRoleArtwork(locator: String?): MediaArtworkRef? =
    locator?.let { poster -> MediaArtworkRef.of(poster, ArtworkPolicy.POSTER) }

internal enum class HeroPosterPresentation {

    Corner,

    DetailsEdge,
}

private const val PosterMaxViewportHeightFraction = 0.5f

private const val PosterSheetAspectRatio = 2f / 3f

private const val ReadabilityLeftExtent = 0.78f

private const val ReadabilityBottomStart = 0.38f

internal fun isScrolledPastHero(scrollOffsetPx: Int, heroHeightPx: Int): Boolean =
    heroHeightPx > 0 &&
        scrollOffsetPx >= (heroHeightPx * InfusionTvMotion.SCROLL_DIM_THRESHOLD).toInt()

@Composable
internal fun TvDetailsHeroCanvas(
    hero: CanonicalHeroUi?,
    primarySettled: Boolean,
    underlay: DetailsArtwork,
    scrolledPastHero: Boolean,
    posterPresentation: HeroPosterPresentation,
    modifier: Modifier = Modifier,

    heroImageReadiness: HeroImageReadiness? = null,
) {
    val backdropLocator = hero?.backdropLocator

    val backdropConfirmedAbsent = primarySettled && hero != null && backdropLocator == null

    val canonicalSlotAbsent = primarySettled && hero == null
    DetailsCanvasBackdrop(
        backdropLocator = backdropLocator,

        backdropConfirmedAbsent = backdropConfirmedAbsent,

        releaseCarriedBackdrop = backdropConfirmedAbsent || canonicalSlotAbsent,

        poster = if (backdropConfirmedAbsent) posterRoleArtwork(hero?.posterLocator) else null,
        posterPresentation = posterPresentation,
        underlay = underlay,
        scrolledPastHero = scrolledPastHero,

        primarySettled = primarySettled,
        heroPublished = hero != null,
        heroImageReadiness = heroImageReadiness,
        modifier = modifier,
    )
}

internal class HeroImageReadiness(
    val key: String,

    val underlayAvailable: Boolean?,
    val report: (key: String, loaded: Boolean) -> Unit,
)

internal fun coldStartHeroImageOutcome(

    backdropLocator: String?,

    backdropDecoded: Boolean,

    backdropOnScreen: Boolean,

    backdropSettled: Boolean,

    primarySettled: Boolean,

    heroPublished: Boolean,

    underlayRequested: Boolean,
    underlayDecoded: Boolean,
    underlayOnScreen: Boolean,
    underlaySettled: Boolean,

    pageUnderlayAvailable: Boolean?,
): Boolean? = when {
    backdropLocator != null -> when {
        backdropOnScreen -> true
        backdropDecoded -> null
        backdropSettled -> false
        else -> null
    }

    !primarySettled -> null

    heroPublished -> false

    underlayRequested -> when {
        underlayOnScreen -> true
        underlayDecoded -> null
        underlaySettled -> false
        else -> null
    }

    pageUnderlayAvailable == false -> false

    else -> null
}

@Composable
internal fun TvDetailsBackdropCanvas(
    backdropLocator: String?,
    modifier: Modifier = Modifier,
) {
    DetailsCanvasBackdrop(
        backdropLocator = backdropLocator,

        backdropConfirmedAbsent = backdropLocator == null,

        releaseCarriedBackdrop = backdropLocator == null,

        poster = null,
        posterPresentation = HeroPosterPresentation.Corner,
        underlay = DetailsArtwork(),
        scrolledPastHero = false,

        primarySettled = true,
        heroPublished = backdropLocator != null,
        heroImageReadiness = null,
        modifier = modifier,
    )
}

@Composable
private fun DetailsCanvasBackdrop(
    backdropLocator: String?,
    backdropConfirmedAbsent: Boolean,

    releaseCarriedBackdrop: Boolean,
    poster: MediaArtworkRef?,
    posterPresentation: HeroPosterPresentation,
    underlay: DetailsArtwork,
    scrolledPastHero: Boolean,

    primarySettled: Boolean,

    heroPublished: Boolean,

    heroImageReadiness: HeroImageReadiness?,
    modifier: Modifier = Modifier,
) {

    val underlayRef = fullscreenBackdropArtwork(underlay.ref)
    val hasUnderlayArtwork = underlayRef != null

    val confirmedAbsencePoster = poster?.takeIf { backdropConfirmedAbsent && backdropLocator == null }

    val ownBackdropRef = backdropLocator?.let { MediaArtworkRef(it, ArtworkPolicy.BACKDROP) }

    var ownBackdropReady by remember(backdropLocator) { mutableStateOf(false) }

    var ownBackdropSettled by remember(backdropLocator) { mutableStateOf(false) }

    var ownBackdropOnScreen by remember(backdropLocator) { mutableStateOf(false) }

    val ownBackdropHold = remember { HeroArtworkHold() }

    var underlayReady by remember(underlayRef?.locator) { mutableStateOf(false) }
    var underlaySettled by remember(underlayRef?.locator) { mutableStateOf(false) }

    var underlayOnScreen by remember(underlayRef?.locator) { mutableStateOf(false) }

    val underlayHold = remember { HeroArtworkHold() }

    val underlayAlpha = remember { Animatable(1f) }
    var underlayDrawn by remember { mutableStateOf(true) }

    val backdropCarriesImage = ownBackdropReady || ownBackdropHold.displayed != null
    val underlayCarriesImage = underlayDrawn &&
        (hasUnderlayArtwork || underlayHold.displayed != null)

    val underlayWanted = !backdropConfirmedAbsent && !backdropCarriesImage

    LaunchedEffect(underlayWanted) {
        if (underlayWanted) {
            if (underlayAlpha.value < 1f) {
                underlayAlpha.animateTo(1f, tween(InfusionTvMotion.ARTWORK_FADE_MILLIS))
            }
            underlayDrawn = true
        } else if (underlayAlpha.value > 0f) {
            underlayAlpha.animateTo(0f, tween(InfusionTvMotion.ARTWORK_FADE_MILLIS))
            underlayDrawn = false
        }
    }

    val showUnderlay = underlayCarriesImage

    val neutralOwnsCanvas = !underlayCarriesImage && !backdropCarriesImage

    val gradientAlpha = animateFloatAsState(
        targetValue = if (neutralOwnsCanvas) 1f else 0f,
        animationSpec = tween(InfusionTvMotion.GRADIENT_FADE_MILLIS),
        label = "details-neutral-canvas",
    )
    val canvasAlpha = animateFloatAsState(
        targetValue = if (scrolledPastHero) InfusionTvMotion.SCROLLED_BACKDROP_ALPHA else 1f,
        animationSpec = tween(InfusionTvMotion.SCROLL_DIM_MILLIS),
        label = "details-backdrop-dim",
    )

    val heroImageOutcome: Boolean? = coldStartHeroImageOutcome(
        backdropLocator = backdropLocator,
        backdropDecoded = ownBackdropReady,
        backdropOnScreen = ownBackdropOnScreen,
        backdropSettled = ownBackdropSettled,
        primarySettled = primarySettled,
        heroPublished = heroPublished,
        underlayRequested = underlayRef != null,
        underlayDecoded = underlayReady,
        underlayOnScreen = underlayOnScreen,
        underlaySettled = underlaySettled,
        pageUnderlayAvailable = heroImageReadiness?.underlayAvailable,
    )
    if (heroImageReadiness != null) {
        val readiness = heroImageReadiness
        val outcome = heroImageOutcome
        LaunchedEffect(readiness.key, outcome) {
            if (outcome != null) readiness.report(readiness.key, outcome)
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {

        val screenTarget: IntSize = with(LocalDensity.current) {
            IntSize(maxWidth.roundToPx(), maxHeight.roundToPx())
        }

        val viewportHeight = maxHeight
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = canvasAlpha.value },
        ) {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(HeroGradientTag)
                    .graphicsLayer { alpha = gradientAlpha.value }
                    .background(heroGradient()),
            )

            if (showUnderlay) {

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(HeroUnderlayTag)
                        .graphicsLayer { alpha = underlayAlpha.value },
                ) {
                    HeroArtworkLayer(
                        hold = underlayHold,
                        ref = underlayRef,
                        target = screenTarget,
                        release = !underlayWanted,
                        alignment = Alignment.Center,

                        onLoaded = {
                            underlayOnScreen = true
                            underlayReady = true
                        },
                        onSettled = { loaded ->
                            underlaySettled = true
                            if (loaded) underlayReady = true
                        },
                    )
                }
            }

            val backdropLayerPresents = backdropLocator != null || ownBackdropHold.displayed != null
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdropLayerPresents) Modifier.testTag(HeroBackdropTag) else Modifier),
            ) {

                HeroArtworkLayer(
                    hold = ownBackdropHold,
                    ref = ownBackdropRef,
                    target = screenTarget,
                    artTag = HeroBackdropArtTag,
                    release = releaseCarriedBackdrop,
                    alignment = Alignment.TopEnd,

                    onLoaded = {
                        ownBackdropOnScreen = true
                        ownBackdropReady = true
                    },

                    onSettled = { loaded ->
                        ownBackdropSettled = true
                        if (loaded) ownBackdropReady = true
                    },
                )
            }

            if (confirmedAbsencePoster != null) {
                when (posterPresentation) {
                    HeroPosterPresentation.Corner -> {
                        val posterHeight = viewportHeight * PosterMaxViewportHeightFraction
                        HeroAbsencePoster(
                            poster = confirmedAbsencePoster,
                            size = DpSize(posterHeight * PosterSheetAspectRatio, posterHeight),
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }

                    HeroPosterPresentation.DetailsEdge -> {

                        val posterHeight = viewportHeight
                        HeroAbsencePoster(
                            poster = confirmedAbsencePoster,
                            size = DpSize(posterHeight * PosterSheetAspectRatio, posterHeight),
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = InfusionTvDetailsMetrics.PageHorizontalPadding),

                            shape = InfusionTvContour.Shape,
                        )
                    }
                }
            }

            HeroReadabilityRamps()
        }
    }
}

@Composable
private fun HeroAbsencePoster(
    poster: MediaArtworkRef,
    size: DpSize,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
) {
    val target = artworkTargetPx(size)
    Box(
        modifier = modifier
            .size(size)
            .testTag(HeroPosterTag)
            .then(if (shape == null) Modifier else Modifier.clip(shape)),
    ) {
        ArtworkImage(
            request = ArtworkRequest(
                locator = poster.locator,
                policy = poster.policy,
                targetWidthPx = target.width,
                targetHeightPx = target.height,
                retainsPreviousSuccess = false,
            ),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().testTag(HeroPosterArtTag),
            arrivalFadeMillis = InfusionTvMotion.ARTWORK_FADE_MILLIS,
            contentScale = ContentScale.Fit,
        )
    }
}

internal const val HeroRetainedArtTag = "canonical:hero:retained"

internal class HeroArtworkHold {
    var displayed: MediaArtworkRef? by mutableStateOf(null)
}

internal fun heroArtworkRequest(slot: MediaArtworkRef, target: IntSize): ArtworkRequest = ArtworkRequest(
    locator = slot.locator,
    policy = slot.policy,
    targetWidthPx = target.width,
    targetHeightPx = target.height,
    retainsPreviousSuccess = false,
)

@Composable
private fun HeroArtworkLayer(
    hold: HeroArtworkHold,
    ref: MediaArtworkRef?,
    target: IntSize,
    modifier: Modifier = Modifier,

    artTag: String? = null,
    contentDescription: String? = null,
    alignment: Alignment = Alignment.Center,

    release: Boolean = false,
    onLoaded: () -> Unit = {},
    onSettled: (Boolean) -> Unit = {},
) {
    val loader = LocalArtworkLoader.current

    LaunchedEffect(release) {
        if (release) hold.displayed = null
    }

    LaunchedEffect(ref, release) {
        val requested = ref
        if (release || requested == null) return@LaunchedEffect
        val carried = hold.displayed
        if (carried != null && carried.locator == requested.locator && carried.policy == requested.policy) {
            return@LaunchedEffect
        }
        val seam = loader ?: return@LaunchedEffect
        val available = seam.load(heroArtworkRequest(requested, target))
        if (available != null) {
            hold.displayed = requested
            onLoaded()
            onSettled(true)
        } else {
            hold.displayed = null
            onSettled(false)
        }
    }
    val displayed = hold.displayed
    Box(modifier = modifier.fillMaxSize()) {

        Crossfade(
            targetState = displayed,
            animationSpec = tween(InfusionTvMotion.ARTWORK_FADE_MILLIS),
            label = "hero-artwork-crossfade",
        ) { slot ->
            if (slot != null) {
                val isRequested = slot.locator == ref?.locator && slot.policy == ref?.policy
                ArtworkImage(
                    request = heroArtworkRequest(slot, target),
                    contentDescription = contentDescription,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            when {
                                isRequested && artTag != null -> Modifier.testTag(artTag)
                                isRequested -> Modifier
                                else -> Modifier.testTag(HeroRetainedArtTag)
                            },
                        ),
                    alignment = alignment,
                )
            }
        }
    }
}

@Composable
private fun HeroReadabilityRamps() {
    val surface = MaterialTheme.colorScheme.surface
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(HeroReadabilityLeftTag)
            .drawBehind {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colorStops = arrayOf(0f to surface, 1f to Color.Transparent),
                        startX = 0f,
                        endX = size.width * ReadabilityLeftExtent,
                    ),
                )
            },
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(HeroReadabilityBottomTag)
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(0f to Color.Transparent, 1f to surface),
                        startY = size.height * ReadabilityBottomStart,
                        endY = size.height,
                    ),
                )
            },
    )
}

@Composable
private fun heroGradient(): Brush = Brush.verticalGradient(
    listOf(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.surface,
    ),
)
