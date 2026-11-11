@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package dev.infusion.tv.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.SelectableChipBorder
import androidx.tv.material3.SelectableChipColors
import androidx.tv.material3.SelectableChipScale
import androidx.tv.material3.SelectableChipShape
import dev.infusion.tv.ui.presentation.contextMenuHintDecoration

@Composable
fun InfusionTvFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = InfusionTvContour.Shape,

    contextMenuAvailable: Boolean = onLongClick != null,
    content: @Composable () -> Unit,
) {

    val hintModifier = Modifier.contextMenuHintDecoration(
        menuAvailable = contextMenuAvailable,
        leadingInset = InfusionTvChipLeadingInset,
    )

    val tokens = LocalInfusionTheme.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val themedModifier = if (tokens.premium) {
        modifier
            .themeControlBackdrop(focused = focused, selected = selected)
            .themeControlMaterial(focused = focused)
            .then(hintModifier)
    } else {
        modifier.then(hintModifier)
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = themedModifier,
        enabled = enabled,
        onLongClick = onLongClick,
        shape = infusionTvChipShape(shape),
        colors = infusionTvChipColors(),
        scale = infusionTvChipScale(),
        border = infusionTvChipBorder(shape = shape),
        interactionSource = interaction,
        content = content,
    )
}

@Composable
fun infusionTvChipTreatment(
    state: InfusionControlState,
    accent: Color = LocalInfusionAccent.current,
): InfusionControlTreatment = InfusionTvContour.treatment(state, accent)

@Composable
fun infusionTvChipColors(
    accent: Color = LocalInfusionAccent.current,
): SelectableChipColors {
    val tokens = LocalInfusionTheme.current
    if (tokens.premium) {

        return FilterChipDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = tokens.textPrimary,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = tokens.onAccent,
            pressedContainerColor = Color.Transparent,
            pressedContentColor = tokens.onAccent,
            selectedContainerColor = Color.Transparent,
            selectedContentColor = tokens.textPrimary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = tokens.textPrimary.copy(
                alpha = InfusionTvContour.DisabledContentAlpha,
            ),
            focusedSelectedContainerColor = Color.Transparent,
            focusedSelectedContentColor = tokens.onAccent,
            pressedSelectedContainerColor = Color.Transparent,
            pressedSelectedContentColor = tokens.onAccent,
        )
    }
    val normal = infusionTvChipTreatment(InfusionControlState.NORMAL, accent)
    val selected = infusionTvChipTreatment(InfusionControlState.SELECTED, accent)
    val focused = infusionTvChipTreatment(InfusionControlState.FOCUSED, accent)
    val disabled = infusionTvChipTreatment(InfusionControlState.DISABLED, accent)
    return FilterChipDefaults.colors(
        containerColor = normal.containerColor,
        contentColor = normal.contentColor,
        focusedContainerColor = focused.containerColor,
        focusedContentColor = focused.contentColor,
        pressedContainerColor = focused.containerColor,
        pressedContentColor = focused.contentColor,
        selectedContainerColor = selected.containerColor,
        selectedContentColor = selected.contentColor,
        disabledContainerColor = disabled.containerColor,
        disabledContentColor = disabled.contentColor,
        focusedSelectedContainerColor = focused.containerColor,
        focusedSelectedContentColor = focused.contentColor,
        pressedSelectedContainerColor = focused.containerColor,
        pressedSelectedContentColor = focused.contentColor,
    )
}

internal val InfusionTvChipLeadingInset: Dp = 16.dp

fun infusionTvChipShape(shape: Shape = InfusionTvContour.Shape): SelectableChipShape {
    val resolved = infusionTvChipGeometry(shape).shape
    return FilterChipDefaults.shape(
        shape = resolved,
        focusedShape = resolved,
        pressedShape = resolved,
        selectedShape = resolved,
        disabledShape = resolved,
        focusedSelectedShape = resolved,
        focusedDisabledShape = resolved,
        pressedSelectedShape = resolved,
        selectedDisabledShape = resolved,
        focusedSelectedDisabledShape = resolved,
    )
}

data class InfusionTvChipGeometry(
    val shape: Shape,
    val cornerRadius: Dp,
    val focusScale: Float,
)

fun infusionTvChipGeometry(shape: Shape = InfusionTvContour.Shape): InfusionTvChipGeometry =
    InfusionTvChipGeometry(
        shape = shape,
        cornerRadius = InfusionTvContour.CornerRadius,
        focusScale = InfusionTvContour.FocusScale,
    )

fun infusionTvChipScale(shape: Shape = InfusionTvContour.Shape): SelectableChipScale {
    val scale = infusionTvChipGeometry(shape).focusScale
    return FilterChipDefaults.scale(
        scale = scale,
        focusedScale = scale,
        pressedScale = scale,
        selectedScale = scale,
        disabledScale = scale,
        focusedSelectedScale = scale,
        focusedDisabledScale = scale,
        pressedSelectedScale = scale,
        selectedDisabledScale = scale,
        focusedSelectedDisabledScale = scale,
    )
}

@Composable
fun infusionTvChipBorder(
    accent: Color = LocalInfusionAccent.current,
    shape: Shape = InfusionTvContour.Shape,
): SelectableChipBorder {
    val selectedTreatment = infusionTvChipTreatment(InfusionControlState.SELECTED, accent)
    val selectedContour = selectedTreatment.contourWidth
    val selectedBorder = if (selectedContour == null) {
        Border.None
    } else {
        Border(BorderStroke(selectedContour, accent), 0.dp, shape)
    }
    return FilterChipDefaults.border(
        border = Border.None,
        focusedBorder = Border.None,
        pressedBorder = Border.None,
        selectedBorder = selectedBorder,
        disabledBorder = Border.None,
        focusedSelectedBorder = Border.None,
        focusedDisabledBorder = Border.None,
        pressedSelectedBorder = Border.None,
        selectedDisabledBorder = Border.None,
        focusedSelectedDisabledBorder = Border.None,
    )
}
