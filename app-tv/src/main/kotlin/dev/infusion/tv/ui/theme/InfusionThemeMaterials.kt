package dev.infusion.tv.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

@Composable
fun InfusionAmbientBackdrop(modifier: Modifier = Modifier) {
    val tokens = LocalInfusionTheme.current
    if (!tokens.premium) return
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(tokens.backgroundTop, tokens.backgroundBottom),
                ),
            )
            .drawBehind {
                tokens.ambientOrbs.forEach { orb ->
                    val center = Offset(size.width * orb.x, size.height * orb.y)
                    val radius = maxOf(size.width, size.height) * orb.radius
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(orb.color, Color.Transparent),
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                        center = center,
                    )
                }

                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.55f),
                        ),
                        center = Offset(size.width / 2f, size.height * 0.4f),
                        radius = maxOf(size.width, size.height) * 0.85f,
                    ),
                )
            },
    )
}

enum class InfusionPremiumMaterial {

    PLATE,

    NAVIGATION_ITEM,
}

@Composable
fun Modifier.themeControlBackdrop(
    focused: Boolean,
    selected: Boolean = false,
    radius: Dp = InfusionTvContour.CornerRadius,
    material: InfusionPremiumMaterial = InfusionPremiumMaterial.PLATE,
): Modifier {
    val tokens = LocalInfusionTheme.current
    if (!tokens.premium) return this
    val cornerPx = with(LocalDensity.current) { radius.toPx() }
    return this.drawBehind {
        clipRoundRect(cornerPx) {
            if (material == InfusionPremiumMaterial.NAVIGATION_ITEM) {
                when {
                    focused -> drawRoundRect(
                        brush = tokens.fillBrush(size),
                        cornerRadius = CornerRadius(cornerPx),
                    )
                    selected -> {

                        drawRoundRect(
                            color = tokens.glassStrong,
                            cornerRadius = CornerRadius(cornerPx),
                        )
                        drawNavigationSelectionBar(tokens)
                    }
                }
            } else {
                when {
                    focused -> drawRoundRect(
                        brush = tokens.fillBrush(size),
                        cornerRadius = CornerRadius(cornerPx),
                    )
                    else -> {
                        drawRoundRect(
                            color = if (selected) tokens.glassStrong else tokens.glass,
                            cornerRadius = CornerRadius(cornerPx),
                        )

                        drawRoundRect(
                            brush = if (selected) {
                                tokens.fillBrush(size)
                            } else {
                                Brush.linearGradient(listOf(tokens.glassBorder, tokens.glassBorder))
                            },
                            cornerRadius = CornerRadius(cornerPx),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawNavigationSelectionBar(tokens: InfusionThemeTokens) {
    val barWidth = 3.dp.toPx()
    val inset = 6.dp.toPx()
    val barHeight = (size.height - inset * 2f).coerceAtLeast(0f)
    if (barHeight <= 0f) return

    drawRoundRect(
        brush = Brush.horizontalGradient(
            colors = listOf(tokens.glow, Color.Transparent),
            startX = 0f,
            endX = barWidth * 2.4f,
        ),
        topLeft = Offset(0f, inset),
        size = Size(barWidth * 2.4f, barHeight),
        cornerRadius = CornerRadius(barWidth),
    )
    drawRoundRect(
        brush = Brush.verticalGradient(tokens.gradient),
        topLeft = Offset(0f, inset),
        size = Size(barWidth, barHeight),
        cornerRadius = CornerRadius(barWidth / 2f),
    )
}

internal fun themeInfoPanelFill(tokens: InfusionThemeTokens): Color =
    if (tokens.premium) tokens.glass else InfusionTvAppearance.Panel

@Composable
fun Modifier.themeInfoPanelContour(): Modifier {
    val tokens = LocalInfusionTheme.current
    if (!tokens.premium) return this
    return this.border(1.dp, tokens.glassBorder, InfusionTvContour.Shape)
}

fun Modifier.themeGlassMaterial(
    tokens: InfusionThemeTokens,
    strong: Boolean = false,
    radius: Dp = tokens.panelRadius,
    shape: Shape = RoundedCornerShape(radius),
): Modifier = if (!tokens.premium) {
    this
} else {
    this
        .clip(shape)
        .background(if (strong) tokens.glassStrong else tokens.glass)
        .border(1.dp, tokens.glassBorder, shape)
}

@Composable
fun Modifier.themeControlMaterial(
    focused: Boolean,
    radius: Dp = InfusionTvContour.CornerRadius,
    material: InfusionPremiumMaterial = InfusionPremiumMaterial.PLATE,
): Modifier {
    val tokens = LocalInfusionTheme.current
    if (!tokens.premium) return this
    val sheen = rememberFocusSheen(focused = focused)
    val cornerPx = with(LocalDensity.current) { radius.toPx() }
    return this.drawWithContent {
        drawContent()
        clipRoundRect(cornerPx) {
            if (material == InfusionPremiumMaterial.NAVIGATION_ITEM) {
                if (focused) drawRoundedPerimeterGlow(tokens.glow, cornerPx)
                drawSheenBand(sheen)
                return@clipRoundRect
            }

            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.16f),
                    0.45f to Color.Transparent,
                ),
            )
            if (focused) {
                drawRoundedPerimeterGlow(tokens.glow, cornerPx)
                drawRoundRect(
                    brush = tokens.fillBrush(size),
                    cornerRadius = CornerRadius(cornerPx),
                    style = Stroke(width = 1.6.dp.toPx()),
                )
            }
            drawSheenBand(sheen)
        }
    }
}

@Composable
fun Modifier.themeArtworkClip(): Modifier {
    val tokens = LocalInfusionTheme.current
    val radius = if (tokens.premium) tokens.cardRadius else InfusionTvContour.CornerRadius
    return clip(RoundedCornerShape(radius))
}

@Composable
fun Modifier.themeArtworkTreatment(
    focused: Boolean,
    radius: Dp = LocalInfusionTheme.current.cardRadius,
): Modifier {
    val tokens = LocalInfusionTheme.current
    if (!tokens.premium) return this
    val sheen = rememberFocusSheen(focused = focused)
    val cornerPx = with(LocalDensity.current) { radius.toPx() }
    return this.drawWithContent {
        drawContent()
        clipRoundRect(cornerPx) {
            drawRect(brush = tokens.artworkGloss)
            drawSheenBand(sheen)
        }
    }
}

@Composable
fun Modifier.themeValueChip(focused: Boolean): Modifier {
    val tokens = LocalInfusionTheme.current
    val fill = settingsValueChipFill(tokens, focused)
    val edge = settingsValueChipEdgeColors(tokens, focused)
    val shape = RoundedCornerShape(InfusionTvContour.CornerRadius)
    val cornerPx = with(LocalDensity.current) { InfusionTvContour.CornerRadius.toPx() }
    return this
        .clip(shape)
        .drawBehind {
            drawRect(fill)

            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (focused) 0.10f else 0.07f),
                    0.62f to Color.Transparent,
                ),
            )

            val strokeWidth = 1.dp.toPx()
            val inset = strokeWidth / 2f
            drawRoundRect(
                brush = Brush.linearGradient(edge),
                topLeft = Offset(inset, inset),
                size = Size(
                    width = (size.width - strokeWidth).coerceAtLeast(0f),
                    height = (size.height - strokeWidth).coerceAtLeast(0f),
                ),
                cornerRadius = CornerRadius((cornerPx - inset).coerceAtLeast(0f)),
                style = Stroke(width = strokeWidth),
            )
        }
}

internal fun settingsValueChipFill(tokens: InfusionThemeTokens, focused: Boolean): Color = when {
    focused -> Color.Black.copy(alpha = 0.18f)
    tokens.premium -> tokens.glassStrong
    else -> InfusionTvAppearance.Panel
}

internal fun settingsValueChipEdgeColors(
    tokens: InfusionThemeTokens,
    focused: Boolean,
): List<Color> = when {
    focused -> listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.10f))
    tokens.premium -> tokens.gradient.map { it.copy(alpha = 0.45f) }
    else -> listOf(InfusionTvAppearance.PanelBorder, InfusionTvAppearance.PanelBorder)
}

@Composable
fun Modifier.themeStatusPill(): Modifier {
    val tokens = LocalInfusionTheme.current
    if (!tokens.premium) return this
    return this.drawBehind {
        val padX = 10.dp.toPx()
        val padY = 5.dp.toPx()
        val pillTopLeft = Offset(-padX, -padY)
        val pillSize = Size(size.width + padX * 2f, size.height + padY * 2f)
        val pillCorner = CornerRadius(pillSize.height / 2f)
        drawRoundRect(color = tokens.glassStrong, topLeft = pillTopLeft, size = pillSize, cornerRadius = pillCorner)
        drawRoundRect(
            brush = tokens.cardFrameBrush(),
            topLeft = pillTopLeft,
            size = pillSize,
            cornerRadius = pillCorner,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

@Composable
fun Modifier.themeAvatarHalo(): Modifier {
    val tokens = LocalInfusionTheme.current
    if (!tokens.premium) return this
    return this.drawWithContent {
        drawContent()
        val stroke = 2.dp.toPx()
        val radius = (size.minDimension - stroke) / 2f
        drawCircle(
            brush = tokens.cardFrameBrush(),
            radius = radius,
            style = Stroke(width = stroke),
        )
    }
}

internal fun premiumRailEntryContentColor(
    tokens: InfusionThemeTokens,
    selected: Boolean,
    focused: Boolean,
): Color? = when {
    !tokens.premium -> null
    focused -> tokens.onAccent
    selected -> tokens.textPrimary
    else -> tokens.textMuted
}

@Composable
fun rememberFocusSheen(focused: Boolean): () -> Float {
    val progress = remember { Animatable(SheenIdle) }
    LaunchedEffect(focused) {
        if (focused) {
            progress.snapTo(SheenStart)
            progress.animateTo(
                targetValue = SheenEnd,
                animationSpec = tween(durationMillis = 620, easing = LinearEasing),
            )
            progress.snapTo(SheenIdle)
        } else {
            progress.snapTo(SheenIdle)
        }
    }
    return { progress.value }
}

private const val SheenIdle: Float = -10f

private const val SheenStart: Float = -1.3f
private const val SheenEnd: Float = 1.3f

private inline fun DrawScope.clipRoundRect(cornerPx: Float, block: DrawScope.() -> Unit) {
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(Offset.Zero, size),
                cornerRadius = CornerRadius(cornerPx),
            ),
        )
    }
    clipPath(path) { block() }
}

private fun DrawScope.drawRoundedPerimeterGlow(glow: Color, cornerPx: Float) {
    if (glow.alpha <= 0f) return
    val layers = 3
    repeat(layers) { index ->
        val inset = (index + 1) * 1.6.dp.toPx()
        drawRoundRect(
            color = glow.copy(alpha = glow.alpha * (0.30f / (index + 1))),
            topLeft = Offset(inset, inset),
            size = Size(
                width = (size.width - inset * 2f).coerceAtLeast(0f),
                height = (size.height - inset * 2f).coerceAtLeast(0f),
            ),
            cornerRadius = CornerRadius((cornerPx - inset).coerceAtLeast(0f)),
            style = Stroke(width = 1.6.dp.toPx()),
        )
    }
}

private fun DrawScope.drawSheenBand(progress: () -> Float) {
    val value = progress()
    if (value <= SheenStart || value >= SheenEnd) return
    val width = size.width
    if (width <= 0f) return
    val centerX = width * value
    val band = width * 0.38f
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = 0.22f),
                Color.Transparent,
            ),
            start = Offset(centerX - band, 0f),
            end = Offset(centerX + band, size.height),
        ),
    )
}

@Composable
fun InfusionTvSwitch(
    checked: Boolean,
    focused: Boolean,
    modifier: Modifier = Modifier,
    stateText: String = if (checked) "On" else "Off",
) {
    val tokens = LocalInfusionTheme.current
    Box(modifier = modifier.size(SwitchWidth, SwitchHeight)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val corner = size.height / 2f
                    if (checked) {
                        if (focused) {

                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.50f),
                                cornerRadius = CornerRadius(corner),
                            )
                        } else {

                            drawRoundRect(
                                brush = tokens.fillBrush(size),
                                cornerRadius = CornerRadius(corner),
                            )
                            if (tokens.premium) {

                                drawRoundRect(
                                    color = Color.White.copy(alpha = 0.22f),
                                    topLeft = Offset(0f, 0f),
                                    size = Size(size.width, size.height * 0.5f),
                                    cornerRadius = CornerRadius(corner),
                                )
                            }
                        }
                    } else {
                        drawRoundRect(
                            color = if (focused) {

                                Color.Black.copy(alpha = 0.25f)
                            } else {
                                Color.White.copy(alpha = 0.18f)
                            },
                            cornerRadius = CornerRadius(corner),
                        )
                    }
                    val thumbRadius = (size.height - 4.dp.toPx()) / 2f
                    val travel = size.width - size.height
                    val centerX = if (checked) {
                        size.height / 2f + travel
                    } else {
                        size.height / 2f
                    }
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.28f),
                        radius = thumbRadius,
                        center = Offset(centerX, size.height / 2f + 1.dp.toPx()),
                    )
                    drawCircle(
                        color = Color.White,
                        radius = thumbRadius,
                        center = Offset(centerX, size.height / 2f),
                    )
                },
        )

        Text(
            text = stateText,
            color = Color.Transparent,
            fontSize = 1.sp,
            lineHeight = 1.sp,
            maxLines = 1,
        )
    }
}

private val SwitchWidth: Dp = 40.dp

private val SwitchHeight: Dp = 24.dp
