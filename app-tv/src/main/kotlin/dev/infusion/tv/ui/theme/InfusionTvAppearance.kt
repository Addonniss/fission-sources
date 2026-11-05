package dev.infusion.tv.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonBorder
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ButtonShape
import androidx.tv.material3.ButtonScale
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardColors
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardScale
import androidx.tv.material3.CardShape
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import dev.infusion.core.domain.appearance.AccentColor

object InfusionTvAppearance {

    val OledBlack = Color(0xFF000000)

    val SelectionOrange = Color(0xFFFF7A00)

    val TextPrimary = Color(0xFFFFFFFF)

    val Panel = Color(0xFF161616)

    val PanelBorder = Color(0xFF3A3A3A)

    val ErrorText = Color(0xFFFFB4AB)

    val AccentPalette: Map<AccentColor, Color> = mapOf(
        AccentColor.ORANGE to SelectionOrange,
        AccentColor.RED to Color(0xFFFF5A4D),
        AccentColor.AMBER to Color(0xFFFFC107),
        AccentColor.GREEN to Color(0xFF5BD75B),
        AccentColor.BLUE to Color(0xFF4DA3FF),
        AccentColor.PURPLE to Color(0xFFC074E8),
    )

    fun accentColor(accent: AccentColor): Color = AccentPalette.getValue(accent)

    val DefaultAccentColor: Color = SelectionOrange
}

val LocalInfusionAccent = compositionLocalOf { InfusionTvAppearance.DefaultAccentColor }

object InfusionTvContour {

    val CornerRadius: Dp = 8.dp

    val Shape: Shape = RoundedCornerShape(CornerRadius)

    val ArtworkShape: Shape = RoundedCornerShape(CornerRadius)

    val SelectedWidth: Dp = 1.dp

    val CardFocusWidth: Dp = 2.dp

    const val RestraintContourAlpha: Float = 0.35f

    const val DisabledContentAlpha: Float = 0.38f

    const val FocusScale: Float = 1f

    val ControlContainerColor: Color = InfusionTvAppearance.Panel

    val ControlContentColor: Color = InfusionTvAppearance.TextPrimary

    val FocusedContentColor: Color = InfusionTvAppearance.OledBlack

    val CardContainerColor: Color = Color.Transparent

    val CardContentColor: Color = InfusionTvAppearance.TextPrimary

    val OnArtworkScrim: Color = InfusionTvAppearance.Panel.copy(alpha = ON_ARTWORK_SCRIM_ALPHA)

    const val ON_ARTWORK_SCRIM_ALPHA: Float = 0.72f

    val CardBadgeScrim: Color = InfusionTvAppearance.Panel.copy(alpha = CARD_BADGE_SCRIM_ALPHA)

    const val CARD_BADGE_SCRIM_ALPHA: Float = 0.25f

    val CommentaryOverlayScrim: Color =
        InfusionTvAppearance.OledBlack.copy(alpha = COMMENTARY_OVERLAY_SCRIM_ALPHA)

    const val COMMENTARY_OVERLAY_SCRIM_ALPHA: Float = 0.86f

    val CategoryTileScrim: Color = InfusionTvAppearance.OledBlack.copy(alpha = CATEGORY_TILE_SCRIM_ALPHA)

    const val CATEGORY_TILE_SCRIM_ALPHA: Float = 0.55f

    val NowPlayingBackdropScrim: Color =
        InfusionTvAppearance.OledBlack.copy(alpha = NOW_PLAYING_BACKDROP_SCRIM_ALPHA)

    const val NOW_PLAYING_BACKDROP_SCRIM_ALPHA: Float = 0.72f

    fun treatment(state: InfusionControlState, accent: Color): InfusionControlTreatment = when (state) {
        InfusionControlState.NORMAL -> InfusionControlTreatment(
            containerColor = ControlContainerColor,
            contentColor = ControlContentColor,
            contourWidth = null,
        )
        InfusionControlState.SELECTED -> InfusionControlTreatment(
            containerColor = ControlContainerColor,
            contentColor = ControlContentColor,
            contourWidth = SelectedWidth,
        )
        InfusionControlState.FOCUSED -> InfusionControlTreatment(
            containerColor = accent,
            contentColor = FocusedContentColor,
            contourWidth = null,
        )
        InfusionControlState.DISABLED -> InfusionControlTreatment(
            containerColor = ControlContainerColor,
            contentColor = ControlContentColor.copy(alpha = DisabledContentAlpha),
            contourWidth = null,
        )
    }
}

enum class InfusionControlState {
    NORMAL,
    SELECTED,
    FOCUSED,
    DISABLED,
}

data class InfusionControlTreatment(
    val containerColor: Color,
    val contentColor: Color,
    val contourWidth: Dp?,
)

fun infusionTvColorScheme(accent: Color): androidx.tv.material3.ColorScheme = darkColorScheme(
    primary = accent,
    onPrimary = InfusionTvAppearance.OledBlack,
    primaryContainer = accent,
    onPrimaryContainer = InfusionTvAppearance.OledBlack,
    inversePrimary = accent,
    secondary = InfusionTvAppearance.TextPrimary,
    onSecondary = InfusionTvAppearance.OledBlack,
    secondaryContainer = InfusionTvAppearance.Panel,
    onSecondaryContainer = InfusionTvAppearance.TextPrimary,
    tertiary = InfusionTvAppearance.TextPrimary,
    onTertiary = InfusionTvAppearance.OledBlack,
    tertiaryContainer = InfusionTvAppearance.Panel,
    onTertiaryContainer = InfusionTvAppearance.TextPrimary,
    background = InfusionTvAppearance.OledBlack,
    onBackground = InfusionTvAppearance.TextPrimary,
    surface = InfusionTvAppearance.OledBlack,
    onSurface = InfusionTvAppearance.TextPrimary,
    surfaceVariant = InfusionTvAppearance.Panel,
    onSurfaceVariant = InfusionTvAppearance.TextPrimary,
    surfaceTint = InfusionTvAppearance.OledBlack,
    inverseSurface = InfusionTvAppearance.TextPrimary,
    inverseOnSurface = InfusionTvAppearance.OledBlack,
    error = InfusionTvAppearance.ErrorText,
    onError = InfusionTvAppearance.OledBlack,
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6),
    border = accent,
    borderVariant = InfusionTvAppearance.PanelBorder,
    scrim = Color(0xE6000000),
)

internal val InfusionTvColorScheme = infusionTvColorScheme(InfusionTvAppearance.DefaultAccentColor)

@Composable
fun infusionTvButtonColors(
    containerColor: Color = InfusionTvContour.ControlContainerColor,
    contentColor: Color = InfusionTvContour.ControlContentColor,
): ButtonColors {
    val focused = infusionTvButtonTreatment(InfusionControlState.FOCUSED)
    val disabled = infusionTvButtonTreatment(InfusionControlState.DISABLED)
    return ButtonDefaults.colors(
        containerColor = containerColor,
        contentColor = contentColor,
        focusedContainerColor = focused.containerColor,
        focusedContentColor = focused.contentColor,
        pressedContainerColor = focused.containerColor,
        pressedContentColor = focused.contentColor,
        disabledContainerColor = disabled.containerColor,
        disabledContentColor = disabled.contentColor,
    )
}

@Composable
fun infusionTvButtonTreatment(
    state: InfusionControlState,
    accent: Color = LocalInfusionAccent.current,
): InfusionControlTreatment = InfusionTvContour.treatment(state, accent)

@Composable
fun infusionTvNavigationItemTreatment(
    state: InfusionControlState,
    accent: Color = LocalInfusionAccent.current,
): InfusionControlTreatment = InfusionTvContour.treatment(state, accent)

fun infusionTvNavigationItemContourWidth(state: InfusionControlState): Dp? = when (state) {
    InfusionControlState.SELECTED -> InfusionTvContour.SelectedWidth
    else -> null
}

@Composable
fun infusionTvNavigationItemColors(
    containerColor: Color = InfusionTvContour.ControlContainerColor,
    contentColor: Color = InfusionTvContour.ControlContentColor,
    accent: Color = LocalInfusionAccent.current,
): ButtonColors {
    val focused = infusionTvButtonTreatment(InfusionControlState.FOCUSED, accent)
    val disabled = infusionTvButtonTreatment(InfusionControlState.DISABLED, accent)
    return ButtonDefaults.colors(
        containerColor = containerColor,
        contentColor = contentColor,
        focusedContainerColor = focused.containerColor,
        focusedContentColor = focused.contentColor,
        pressedContainerColor = focused.containerColor,
        pressedContentColor = focused.contentColor,
        disabledContainerColor = disabled.containerColor,
        disabledContentColor = disabled.contentColor,
    )
}

@Composable
fun infusionTvNavigationItemBorder(
    selected: Boolean = false,
    accent: Color = LocalInfusionAccent.current,
    shape: Shape = InfusionTvContour.Shape,
): ButtonBorder {
    val restingState =
        if (selected) InfusionControlState.SELECTED else InfusionControlState.NORMAL
    val restingWidth = infusionTvNavigationItemContourWidth(restingState)
    val resting = restingWidth
        ?.let { Border(BorderStroke(it, accent), 0.dp, shape) }
        ?: Border.None
    return ButtonDefaults.border(
        border = resting,

        focusedBorder = Border.None,
        pressedBorder = Border.None,
        disabledBorder = Border.None,
        focusedDisabledBorder = Border.None,
    )
}

@Composable
fun infusionAccentContentColor(focused: Boolean): Color = if (focused) {
    InfusionTvContour.FocusedContentColor
} else {
    LocalInfusionAccent.current
}

fun infusionTvButtonShape(shape: Shape = InfusionTvContour.Shape): ButtonShape =
    ButtonDefaults.shape(
        shape = shape,
        focusedShape = shape,
        pressedShape = shape,
        disabledShape = shape,
        focusedDisabledShape = shape,
    )

fun infusionTvButtonScale(): ButtonScale = ButtonScale.None

@Composable
fun infusionTvButtonBorder(
    selected: Boolean = false,
    accent: Color = LocalInfusionAccent.current,
    shape: Shape = InfusionTvContour.Shape,
): ButtonBorder {
    val state = if (selected) InfusionControlState.SELECTED else InfusionControlState.NORMAL
    val contourWidth = infusionTvButtonTreatment(state, accent).contourWidth
    val restingBorder = contourWidth
        ?.let { Border(BorderStroke(it, accent), 0.dp, shape) }
        ?: Border.None
    return ButtonDefaults.border(
        border = restingBorder,
        focusedBorder = Border.None,
        pressedBorder = Border.None,
        disabledBorder = Border.None,
        focusedDisabledBorder = Border.None,
    )
}

@Composable
fun infusionTvCardColors(
    containerColor: Color = InfusionTvContour.CardContainerColor,
    contentColor: Color = InfusionTvContour.CardContentColor,
): CardColors = CardDefaults.colors(
    containerColor = containerColor,
    contentColor = contentColor,
    focusedContainerColor = containerColor,
    focusedContentColor = contentColor,
    pressedContainerColor = containerColor,
    pressedContentColor = contentColor,
)

fun infusionTvCardShape(shape: Shape = InfusionTvContour.Shape): CardShape =
    CardDefaults.shape(
        shape = shape,
        focusedShape = shape,
        pressedShape = shape,
    )

fun infusionTvCardScale(scale: Float = InfusionTvContour.FocusScale): CardScale =
    CardDefaults.scale(scale = scale, focusedScale = scale, pressedScale = scale)

@Composable
fun infusionTvCardBorder(
    accent: Color = LocalInfusionAccent.current,
    shape: Shape = InfusionTvContour.Shape,
): CardBorder = CardDefaults.border(
    border = Border.None,
    focusedBorder = Border(BorderStroke(InfusionTvContour.CardFocusWidth, accent), 0.dp, shape),
    pressedBorder = Border(BorderStroke(InfusionTvContour.CardFocusWidth, accent), 0.dp, shape),
)

@Composable
fun infusionSelectionBorder(
    visible: Boolean,
    width: Dp = InfusionTvContour.SelectedWidth,
    shape: Shape = InfusionTvContour.Shape,
    accent: Color = LocalInfusionAccent.current,
): Modifier = if (!visible) {
    Modifier
} else {
    Modifier.border(width = width, color = accent, shape = shape)
}
