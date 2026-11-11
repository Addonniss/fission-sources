package dev.infusion.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import dev.infusion.core.domain.appearance.ThemePreset
import dev.infusion.tv.ui.theme.InfusionThemeTokens
import dev.infusion.tv.ui.theme.InfusionTvAppearance
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.LocalInfusionAccent
import dev.infusion.tv.ui.theme.LocalInfusionTheme
import dev.infusion.tv.ui.theme.classicThemeTokens
import dev.infusion.tv.ui.theme.infusionThemeTokens

@Composable
internal fun ThemePickerDialog(
    current: ThemePreset,
    onSelect: (ThemePreset) -> Unit,
    onDismiss: () -> Unit,
) {
    val choices = ThemePreset.choices
    val requesters = remember { choices.associateWith { FocusRequester() } }
    var focusedTheme by remember { mutableStateOf(current) }
    var initialFocusRequested by remember { mutableStateOf(false) }
    val initialIndex = choices.indexOf(current).coerceAtLeast(0)

    fun requestInitialFocus() {
        if (requesters[choices[initialIndex]]?.requestFocus() == true) {
            initialFocusRequested = true
        }
    }
    LaunchedEffect(Unit) {
        repeat(30) {
            if (initialFocusRequested) return@LaunchedEffect
            withFrameTick()
            requestInitialFocus()
        }
    }
    BackHandler(enabled = true, onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val appTheme = LocalInfusionTheme.current
        Surface(
            modifier = Modifier
                .width(ThemePickerWidth)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionRight -> {
                            val delta = if (event.key == Key.DirectionLeft) -1 else 1
                            val index = choices.indexOf(focusedTheme).coerceAtLeast(0)
                            val next = (index + delta + choices.size) % choices.size
                            requesters[choices[next]]?.requestFocus()
                            true
                        }
                        Key.Back -> {
                            onDismiss()
                            true
                        }
                        else -> false
                    }
                },
            shape = InfusionTvContour.Shape,
            colors = SurfaceDefaults.colors(
                containerColor = if (appTheme.premium) appTheme.backgroundTop else InfusionTvAppearance.Panel,
            ),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Choose a theme",
                    style = InfusionTvScaleCalibration.Type.Headline,
                    color = appTheme.textPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Applies to the active profile only. Classic is the accepted look.",
                    style = InfusionTvScaleCalibration.Type.Body,
                    color = appTheme.textMuted,
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    choices.forEach { preset ->
                        val requester = requesters.getValue(preset)
                        ThemePreviewCard(
                            preset = preset,
                            active = preset == current,
                            modifier = Modifier
                                .width(ThemePreviewWidth)
                                .testTag(themePickerCardKey(preset))
                                .focusRequester(requester)
                                .semantics {
                                    role = Role.RadioButton
                                    selected = preset == current
                                }
                                .onFocusChanged { if (it.isFocused) focusedTheme = preset },
                            onSelect = { onSelect(preset) },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = focusedTheme.tagline,
                    style = InfusionTvScaleCalibration.Type.Body,
                    color = appTheme.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private suspend fun withFrameTick() {
    androidx.compose.runtime.withFrameNanos { }
}

internal fun themePickerCardKey(preset: ThemePreset): String =
    "settings:general:theme:${preset.storageKey}"

@Composable
private fun ThemePreviewCard(
    preset: ThemePreset,
    active: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    val appTheme = LocalInfusionTheme.current
    val accent = LocalInfusionAccent.current
    val previewTokens = if (preset.isClassic) {
        classicThemeTokens(accent)
    } else {
        infusionThemeTokens(preset, accent)
    }
    InfusionTvButton(
        onClick = onSelect,
        modifier = modifier,
        selected = active,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ThemePreviewHeight)
                    .drawBehind { drawThemeMiniature(previewTokens) },
            ) {
                if (active) {
                    Text(
                        text = "ACTIVE",
                        style = InfusionTvScaleCalibration.Type.Body,
                        color = previewTokens.onAccent,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .drawBehind {
                                drawRoundRect(
                                    brush = previewTokens.fillBrush(size),
                                    cornerRadius = CornerRadius(size.height / 2f),
                                )
                            }
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = preset.displayName,
                    style = InfusionTvScaleCalibration.Type.SettingsContent,
                    maxLines = 1,
                )
                Text(
                    text = preset.tagline,
                    style = InfusionTvScaleCalibration.Type.Body,
                    color = appTheme.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun DrawScope.drawThemeMiniature(tokens: InfusionThemeTokens) {
    val corner = 8.dp.toPx()
    val path = Path().apply {
        addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(corner)))
    }
    clipPath(path) {
        if (!tokens.premium) {

            drawRect(color = InfusionTvAppearance.OledBlack)
            drawRect(
                color = InfusionTvAppearance.Panel,
                topLeft = Offset(size.width * 0.06f, size.height * 0.08f),
                size = Size(size.width * 0.34f, size.height * 0.09f),
            )
        } else {
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(tokens.backgroundTop, tokens.backgroundBottom),
                ),
            )
            tokens.ambientOrbs.take(2).forEach { orb ->
                val center = Offset(size.width * orb.x, size.height * orb.y)
                val radius = size.width * orb.radius * 0.9f
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(orb.color, Color.Transparent),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
        }

        val stripTop = size.height * 0.07f
        drawRoundRect(
            color = if (tokens.premium) tokens.glassStrong else InfusionTvAppearance.Panel,
            topLeft = Offset(size.width * 0.05f, stripTop),
            size = Size(size.width * 0.44f, size.height * 0.07f),
            cornerRadius = CornerRadius(size.height * 0.035f),
        )
        drawRoundRect(
            brush = tokens.fillBrush(size),
            topLeft = Offset(size.width * 0.07f, stripTop + size.height * 0.012f),
            size = Size(size.width * 0.12f, size.height * 0.046f),
            cornerRadius = CornerRadius(size.height * 0.023f),
        )

        drawRoundRect(
            brush = Brush.horizontalGradient(tokens.titleGradient),
            topLeft = Offset(size.width * 0.06f, size.height * 0.28f),
            size = Size(size.width * 0.46f, size.height * 0.085f),
            cornerRadius = CornerRadius(size.height * 0.02f),
        )
        drawRoundRect(
            color = if (tokens.premium) tokens.textMuted else InfusionTvAppearance.TextPrimary,
            topLeft = Offset(size.width * 0.06f, size.height * 0.39f),
            size = Size(size.width * 0.34f, size.height * 0.028f),
            cornerRadius = CornerRadius(size.height * 0.014f),
        )

        drawRoundRect(
            brush = tokens.fillBrush(size),
            topLeft = Offset(size.width * 0.06f, size.height * 0.47f),
            size = Size(size.width * 0.16f, size.height * 0.075f),
            cornerRadius = CornerRadius(size.height * 0.022f),
        )

        val tileTop = size.height * 0.64f
        val tileWidth = size.width * 0.155f
        val tileHeight = size.height * 0.28f
        repeat(4) { index ->
            val left = size.width * 0.06f + (tileWidth + size.width * 0.03f) * index
            val topLeft = Offset(left, tileTop)
            drawRoundRect(
                color = if (tokens.premium) {
                    tokens.glassStrong
                } else {
                    InfusionTvAppearance.Panel
                },
                topLeft = topLeft,
                size = Size(tileWidth, tileHeight),
                cornerRadius = CornerRadius(corner * 0.6f),
            )
            if (index == 0) {
                drawRoundRect(
                    brush = tokens.fillBrush(size),
                    topLeft = topLeft,
                    size = Size(tileWidth, tileHeight),
                    cornerRadius = CornerRadius(corner * 0.6f),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
            drawRect(
                color = if (tokens.premium) tokens.glass else InfusionTvAppearance.PanelBorder,
                topLeft = Offset(left, tileTop + tileHeight * 0.72f),
                size = Size(tileWidth, tileHeight * 0.28f),
            )
        }
    }
}

private val ThemePickerWidth = 852.dp

private val ThemePreviewWidth = 187.dp

private val ThemePreviewHeight = 118.dp
