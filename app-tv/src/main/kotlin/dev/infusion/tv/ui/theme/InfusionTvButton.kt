package dev.infusion.tv.ui.theme

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonBorder
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ButtonGlow
import androidx.tv.material3.ButtonScale
import androidx.tv.material3.ButtonShape
import dev.infusion.tv.ui.presentation.contextMenuHintDecoration

@Composable
fun InfusionTvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    scale: ButtonScale = infusionTvButtonScale(),
    glow: ButtonGlow = ButtonDefaults.glow(),
    shape: ButtonShape = infusionTvButtonShape(),
    colors: ButtonColors = infusionTvButtonColors(),

    border: ButtonBorder? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,

    selected: Boolean = false,

    contextMenuAvailable: Boolean = onLongClick != null,

    contextMenuHintVerticalCenter: (() -> Float?)? = null,

    premiumMaterial: InfusionPremiumMaterial = InfusionPremiumMaterial.PLATE,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = LocalInfusionTheme.current

    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val resolvedBorder = border ?: infusionTvButtonBorder(selected = selected)

    val hintModifier = Modifier.contextMenuHintDecoration(
        menuAvailable = contextMenuAvailable,
        leadingInset = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
        verticalCenterPx = contextMenuHintVerticalCenter,
    )

    val themedModifier = if (tokens.premium) {
        modifier
            .themeControlBackdrop(focused = focused, selected = selected, material = premiumMaterial)
            .themeControlMaterial(focused = focused, material = premiumMaterial)
            .then(hintModifier)
    } else {
        modifier.then(hintModifier)
    }
    Button(
        onClick = onClick,
        modifier = themedModifier,
        onLongClick = onLongClick,
        enabled = enabled,
        scale = scale,
        glow = glow,
        shape = shape,
        colors = colors,
        border = resolvedBorder,
        contentPadding = contentPadding,
        interactionSource = interaction,
        content = content,
    )
}
