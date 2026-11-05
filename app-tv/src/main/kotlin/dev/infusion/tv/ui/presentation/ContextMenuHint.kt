package dev.infusion.tv.ui.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.infusion.tv.ui.theme.InfusionControlState
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.LocalInfusionAccent

fun contextMenuHintVisible(
    focused: Boolean,
    menuAvailable: Boolean,
    cardBadges: Boolean,
    contextMenuHint: Boolean,
): Boolean = focused && menuAvailable && cardBadges && contextMenuHint

@Composable
fun BoxScope.InfusionCardContextMenuHint(
    artworkSize: IntSize,
    tag: String? = null,
) {
    if (artworkSize.width <= 0 || artworkSize.height <= 0) return
    val minorSide = minOf(artworkSize.width, artworkSize.height).toFloat()
    val density = LocalDensity.current
    val inset = with(density) { (minorSide * ContentCardPresentation.WatchedMarkInsetFraction).toDp() }

    val fieldPadding = with(density) {
        (minorSide * ContentCardPresentation.WatchedMarkPaddingFraction).toDp()
    }
    val glyph = with(density) { (minorSide * ContentCardPresentation.WatchedMarkGlyphFraction).toDp() }
    val ink = InfusionTvContour.CardContentColor
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(inset)

            .padding(start = fieldPadding)
            .size(glyph)
            .drawBehind { drawContextMenuHintGlyph(ink = ink) }
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

val ContextMenuHintShownKey: SemanticsPropertyKey<Boolean> =
    SemanticsPropertyKey("InfusionContextMenuHintShown")

val ContextMenuHintInkCenterKey: SemanticsPropertyKey<Float> =
    SemanticsPropertyKey("InfusionContextMenuHintInkCenter")

@Composable
fun Modifier.contextMenuHintDecoration(
    menuAvailable: Boolean,
    leadingInset: Dp,
    verticalCenterPx: (() -> Float?)? = null,
): Modifier {
    if (!menuAvailable) return this
    var focused by remember { mutableStateOf(false) }

    val visible = contextMenuHintVisible(
        focused = focused,
        menuAvailable = true,
        cardBadges = true,
        contextMenuHint = true,
    )

    val verticalCenter = verticalCenterPx?.invoke()
    val ink = infusionContextMenuHintInk()
    return this

        .onFocusChanged { focused = it.isFocused }

        .semantics {
            set(ContextMenuHintShownKey, visible)
            if (visible && verticalCenter != null) {
                set(ContextMenuHintInkCenterKey, verticalCenter)
            }
        }
        .drawWithContent {
            drawContent()
            if (visible) {
                drawContextMenuHintInLeadingInset(
                    ink = ink,
                    leadingInset = leadingInset.toPx(),
                    verticalCenterPx = verticalCenter,
                )
            }
        }
}

@Composable
fun infusionContextMenuHintInk(): Color =
    InfusionTvContour.treatment(InfusionControlState.FOCUSED, LocalInfusionAccent.current).contentColor

internal fun DrawScope.drawContextMenuHintGlyph(
    ink: Color,
    widthFraction: Float = 0.64f,
    thicknessFraction: Float = 0.13f,
    gapFraction: Float = 0.135f,
) {
    val boxWidth = size.width
    val boxHeight = size.height
    if (boxWidth <= 0f || boxHeight <= 0f) return
    val lineWidth = boxWidth * widthFraction
    val thickness = boxHeight * thicknessFraction
    val gap = boxHeight * gapFraction
    val totalHeight = 3f * thickness + 2f * gap
    val top = (boxHeight - totalHeight) / 2f
    val corner = CornerRadius(thickness / 2f)
    repeat(3) { index ->
        drawRoundRect(
            color = ink,
            topLeft = Offset(0f, top + index * (thickness + gap)),
            size = Size(lineWidth, thickness),
            cornerRadius = corner,
        )
    }
}

internal fun DrawScope.drawContextMenuHintInLeadingInset(
    ink: Color,
    leadingInset: Float,
    lineWidthFraction: Float = 0.5f,
    thickness: Dp = 1.5.dp,
    gap: Dp = 2.dp,
    verticalCenterPx: Float? = null,
) {
    if (leadingInset <= 0f || size.width <= 0f || size.height <= 0f) return
    val lineWidth = leadingInset * lineWidthFraction

    val thicknessPx = thickness.toPx()
    val gapPx = gap.toPx()
    val totalHeight = 3f * thicknessPx + 2f * gapPx
    if (totalHeight > size.height) return
    val left = (leadingInset - lineWidth) / 2f

    val top = if (verticalCenterPx == null) {
        (size.height - totalHeight) / 2f
    } else {
        (verticalCenterPx - totalHeight / 2f).coerceIn(0f, (size.height - totalHeight).coerceAtLeast(0f))
    }
    val corner = CornerRadius(thicknessPx / 2f)
    repeat(3) { index ->
        drawRoundRect(
            color = ink,
            topLeft = Offset(left, top + index * (thicknessPx + gapPx)),
            size = Size(lineWidth, thicknessPx),
            cornerRadius = corner,
        )
    }
}

fun contentCardContextMenuHintKey(cardKey: String): String = "card-context-menu-hint:$cardKey"
