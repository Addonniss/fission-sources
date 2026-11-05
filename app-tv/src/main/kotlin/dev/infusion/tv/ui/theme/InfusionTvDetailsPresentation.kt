package dev.infusion.tv.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ButtonScale
import androidx.tv.material3.Icon
import androidx.tv.material3.ProvideTextStyle
import dev.infusion.tv.ui.presentation.ContentCardPresentation

object InfusionTvDetailsMetrics {

    val HeroMinHeight = 540.dp

    val HeroContentBottomPadding = 16.dp

    val PageHorizontalPadding = 48.dp

    val HeroLogoBottomPadding = 16.dp

    val HeroLogoHeight = 100.dp

    const val HeroLogoWidthFraction = 0.4f

    val HeroHeadingMinHeight = HeroLogoHeight

    const val HeroTextWidthFraction = 0.6f

    val ActionRowGap = 12.dp

    val EpisodeStillWidth = 192.dp

    val EpisodeStillHeight = EpisodeStillWidth / ContentCardPresentation.LANDSCAPE_ASPECT_RATIO

    val EpisodeStillSize = DpSize(EpisodeStillWidth, EpisodeStillHeight)

    val ActionIconLabelGap = 6.dp

    const val NonPrimaryActionWidthBudgetFraction: Float = 0.35f

    val TvLogicalScreenWidthDp = 960.dp

    val ActionHorizontalPadding = 7.dp

    val ActionVerticalPadding = 8.dp

    val ActionControlHeight = 36.dp

    val PrimaryActionIconSize = 20.dp

    val SecondaryActionIconSize = 18.dp

    val ContextualActionIconSize = 16.dp

    const val ActionPlateAlpha: Float = 1f
}

private fun actionPlateShape() = infusionTvButtonShape()

@Composable
fun InfusionTvHeroPrimaryAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = InfusionTvDetailsMetrics.ActionHorizontalPadding,
        vertical = InfusionTvDetailsMetrics.ActionVerticalPadding,
    ),
    content: @Composable RowScope.() -> Unit,
) {
    val accent = LocalInfusionAccent.current
    val resting = InfusionTvContour.treatment(InfusionControlState.NORMAL, accent)
    val focused = InfusionTvContour.treatment(InfusionControlState.FOCUSED, accent)
    val disabled = InfusionTvContour.treatment(InfusionControlState.DISABLED, accent)
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        scale = ButtonScale.None,
        shape = actionPlateShape(),
        colors = ButtonDefaults.colors(
            containerColor = resting.containerColor,
            contentColor = resting.contentColor,
            focusedContainerColor = focused.containerColor,
            focusedContentColor = focused.contentColor,
            pressedContainerColor = focused.containerColor,
            pressedContentColor = focused.contentColor,
            disabledContainerColor = disabled.containerColor,
            disabledContentColor = disabled.contentColor,
        ),
        border = ButtonDefaults.border(
            border = Border.None,
            focusedBorder = Border.None,
            pressedBorder = Border.None,
            disabledBorder = Border.None,
            focusedDisabledBorder = Border.None,
        ),
        contentPadding = contentPadding,
    ) {
        HeroActionContent(icon, InfusionTvDetailsMetrics.PrimaryActionIconSize, content)
    }
}

@Composable
fun InfusionTvHeroIconAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val accent = LocalInfusionAccent.current
    val resting = InfusionTvContour.treatment(
        if (selected) InfusionControlState.SELECTED else InfusionControlState.NORMAL,
        accent,
    )
    val focused = InfusionTvContour.treatment(InfusionControlState.FOCUSED, accent)
    val disabled = InfusionTvContour.treatment(InfusionControlState.DISABLED, accent)
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        scale = ButtonScale.None,
        shape = actionPlateShape(),
        colors = ButtonDefaults.colors(
            containerColor = resting.containerColor,
            contentColor = resting.contentColor,
            focusedContainerColor = focused.containerColor,
            focusedContentColor = focused.contentColor,
            pressedContainerColor = focused.containerColor,
            pressedContentColor = focused.contentColor,
            disabledContainerColor = disabled.containerColor,
            disabledContentColor = disabled.contentColor,
        ),
        border = ButtonDefaults.border(
            border = resting.contourWidth
                ?.let { Border(BorderStroke(it, accent), 0.dp, InfusionTvContour.Shape) }
                ?: Border.None,
            focusedBorder = Border.None,
            pressedBorder = Border.None,
            disabledBorder = Border.None,
            focusedDisabledBorder = Border.None,
        ),
        contentPadding = PaddingValues(
            horizontal = InfusionTvDetailsMetrics.ActionHorizontalPadding,
            vertical = InfusionTvDetailsMetrics.ActionVerticalPadding,
        ),
    ) {
        HeroActionContent(icon, InfusionTvDetailsMetrics.SecondaryActionIconSize, content)
    }
}

@Composable
fun InfusionTvHeroContextualAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val accent = LocalInfusionAccent.current
    val resting = InfusionTvContour.treatment(InfusionControlState.NORMAL, accent)
    val focused = InfusionTvContour.treatment(InfusionControlState.FOCUSED, accent)
    val disabled = InfusionTvContour.treatment(InfusionControlState.DISABLED, accent)
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        scale = ButtonScale.None,
        shape = actionPlateShape(),
        colors = ButtonDefaults.colors(
            containerColor = resting.containerColor,
            contentColor = resting.contentColor,
            focusedContainerColor = focused.containerColor,
            focusedContentColor = focused.contentColor,
            pressedContainerColor = focused.containerColor,
            pressedContentColor = focused.contentColor,
            disabledContainerColor = disabled.containerColor,
            disabledContentColor = disabled.contentColor,
        ),
        border = ButtonDefaults.border(
            border = Border.None,
            focusedBorder = Border.None,
            pressedBorder = Border.None,
            disabledBorder = Border.None,
            focusedDisabledBorder = Border.None,
        ),
        contentPadding = PaddingValues(
            horizontal = InfusionTvDetailsMetrics.ActionHorizontalPadding,
            vertical = InfusionTvDetailsMetrics.ActionVerticalPadding,
        ),
    ) {
        HeroActionContent(icon, InfusionTvDetailsMetrics.ContextualActionIconSize, content)
    }
}

@Composable
private fun RowScope.HeroActionContent(
    icon: ImageVector?,
    iconSize: Dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfusionTvDetailsMetrics.ActionIconLabelGap),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(iconSize))
        }
        ProvideTextStyle(InfusionTvScaleCalibration.Type.Emphasis) {
            content()
        }
    }
}
