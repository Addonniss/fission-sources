@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package dev.infusion.tv.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.infusion.core.artworkimage.ArtworkImage
import dev.infusion.core.artworkimage.ArtworkRequest
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.feature.vertical.media.CanonicalHeroUi
import dev.infusion.tv.ui.presentation.IdentityHeading
import dev.infusion.tv.ui.presentation.IdentityMetadataState
import dev.infusion.tv.ui.presentation.artworkTargetPx
import dev.infusion.tv.ui.presentation.entityLogoColorFilter
import dev.infusion.tv.ui.presentation.identityHeading
import dev.infusion.tv.ui.theme.InfusionTvDetailsMetrics
import dev.infusion.tv.ui.theme.InfusionTvMotion
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.LocalInfusionTheme

internal const val HeroLogoTag = "canonical:hero:logo"
internal const val HeroTitleTag = "canonical:hero:title"

@Composable
fun TvCanonicalHero(
    hero: CanonicalHeroUi?,
    titleFallback: String?,
    modifier: Modifier = Modifier,
    minHeight: Dp = InfusionTvDetailsMetrics.HeroMinHeight,
    headingModifier: Modifier = Modifier,
    logoWidthFractionOfScreen: Float? = null,

    contentBottomPadding: Dp = InfusionTvDetailsMetrics.HeroContentBottomPadding,

    headingAlignment: Alignment.Horizontal = Alignment.Start,

    logoTag: String = HeroLogoTag,
    titleTag: String = HeroTitleTag,

    logoOverrideLocator: String? = null,

    entityLogoRecolour: Boolean = false,

    headingMetadata: IdentityMetadataState = IdentityMetadataState.SETTLED,
    content: @Composable ColumnScope.() -> Unit,
) {
    val logoLocator = logoOverrideLocator ?: hero?.logoLocator

    val heading = identityHeading(
        declaredLogoLocator = logoLocator,
        canonicalTitle = hero?.title,
        evidenceTitle = titleFallback,
        metadata = headingMetadata,
    )
    val headingLocator = (heading as? IdentityHeading.Logo)?.locator
    val headingTitle = (heading as? IdentityHeading.Title)?.text

    val headingAlpha = animateFloatAsState(
        targetValue = if (heading is IdentityHeading.Empty) 0f else 1f,
        animationSpec = tween(InfusionTvMotion.HEADING_FADE_MILLIS),
        label = "details-hero-heading",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = contentBottomPadding),
            verticalArrangement = Arrangement.spacedBy(InfusionTvDetailsMetrics.ActionRowGap),
        ) {
            HeroHeading(
                headingLocator,
                headingTitle,
                headingAlpha,
                headingModifier,
                logoWidthFractionOfScreen,
                headingAlignment,
                logoTag,
                titleTag,
                entityLogoRecolour,
            )
            content()
        }
    }
}

@Composable
fun TvCanonicalLogoHeader(
    logoLocator: String?,
    modifier: Modifier = Modifier,
    logoWidthFractionOfScreen: Float? = InfusionTvScaleCalibration.ClearLogo.MaxScreenWidthFraction,
) {
    if (logoLocator == null) return

    val headingAlpha = animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(InfusionTvMotion.HEADING_FADE_MILLIS),
        label = "canonical-logo-header",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 0.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = InfusionTvDetailsMetrics.HeroContentBottomPadding),
        ) {
            HeroHeading(
                logoLocator = logoLocator,
                title = null,
                alpha = headingAlpha,
                headingModifier = Modifier,
                logoWidthFractionOfScreen = logoWidthFractionOfScreen,
                headingAlignment = Alignment.CenterHorizontally,
                logoTag = HeroLogoTag,
                titleTag = HeroTitleTag,
                entityLogoRecolour = false,
            )
        }
    }
}

@Composable
private fun HeroHeading(
    logoLocator: String?,
    title: String?,
    alpha: State<Float>,
    headingModifier: Modifier,
    logoWidthFractionOfScreen: Float?,
    headingAlignment: Alignment.Horizontal,
    logoTag: String,
    titleTag: String,
    entityLogoRecolour: Boolean,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {

        val logoMaxWidth = logoWidthFractionOfScreen
            ?.let { InfusionTvScaleCalibration.ofScreenWidth(it, maxWidth) }
            ?: (maxWidth * InfusionTvDetailsMetrics.HeroLogoWidthFraction)
        val logoBox = DpSize(
            width = logoMaxWidth,
            height = InfusionTvDetailsMetrics.HeroLogoHeight,
        )
        val centered = headingAlignment == Alignment.CenterHorizontally
        Box(
            modifier = Modifier
                .heightIn(min = InfusionTvDetailsMetrics.HeroHeadingMinHeight)
                .graphicsLayer { this.alpha = alpha.value }

                .then(if (centered) Modifier.fillMaxWidth() else Modifier)
                .then(headingModifier),
            contentAlignment = if (centered) Alignment.TopCenter else Alignment.TopStart,
        ) {
            if (logoLocator != null) {

                val logoTarget = artworkTargetPx(logoBox)
                val logoRequest = ArtworkRequest.of(logoLocator, ArtworkPolicy.LOGO, logoTarget)

                val logoColorFilter = entityLogoColorFilter(logoLocator, logoRequest, entityLogoRecolour)
                Box(
                    modifier = Modifier

                        .widthIn(max = logoMaxWidth)
                        .fillMaxWidth()
                        .height(InfusionTvDetailsMetrics.HeroLogoHeight)
                        .padding(bottom = InfusionTvDetailsMetrics.HeroLogoBottomPadding)
                        .testTag(HeroLogoTag),
                ) {
                    ArtworkImage(
                        request = logoRequest,
                        contentDescription = "Logo",
                        modifier = Modifier.fillMaxSize(),

                        alignment = if (centered) Alignment.BottomCenter else Alignment.BottomStart,
                        contentScale = ContentScale.Fit,

                        arrivalFadeMillis = InfusionTvMotion.ARTWORK_FADE_MILLIS,

                        colorFilter = logoColorFilter,
                    )
                }
            } else if (title != null) {

                val themeTokens = LocalInfusionTheme.current
                val titleStyle = if (themeTokens.premium) {
                    InfusionTvScaleCalibration.Type.Headline.copy(
                        brush = Brush.linearGradient(themeTokens.titleGradient),
                    )
                } else {
                    InfusionTvScaleCalibration.Type.Headline
                }
                Text(
                    title,
                    style = titleStyle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier
                        .then(if (centered) Modifier.fillMaxWidth() else Modifier)
                        .testTag(HeroTitleTag),
                )
            }
        }
    }
}
