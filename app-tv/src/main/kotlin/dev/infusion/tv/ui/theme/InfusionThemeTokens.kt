package dev.infusion.tv.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.infusion.core.domain.appearance.ThemePreset

data class InfusionThemeTokens(
    val preset: ThemePreset,

    val backgroundTop: Color,

    val backgroundBottom: Color,

    val ambientOrbs: List<ThemeOrb>,

    val gradient: List<Color>,

    val titleGradient: List<Color>,

    val onAccent: Color,

    val nativeAccent: Color,

    val textPrimary: Color,

    val textMuted: Color,

    val glass: Color,

    val glassStrong: Color,

    val glassBorder: Color,

    val glow: Color,

    val cardRadius: Dp,

    val panelRadius: Dp,

    val displayFamily: FontFamily,

    val displayWeight: FontWeight,

    val displayLetterSpacing: TextUnit,

    val premium: Boolean,
) {

    fun fillBrush(size: Size): Brush = Brush.linearGradient(
        colors = gradient,
        start = Offset(0f, 0f),
        end = Offset(size.width, size.height),
    )

    fun cardFrameBrush(): Brush = Brush.linearGradient(colors = gradient)

    fun titleBrush(size: Size): Brush = Brush.linearGradient(
        colors = titleGradient,
        start = Offset(0f, 0f),
        end = Offset(size.width, 0f),
    )

    fun glowBrush(size: Size): Brush = Brush.radialGradient(
        colors = listOf(glow, Color.Transparent),
        center = Offset(size.width / 2f, size.height / 2f),
        radius = maxOf(size.width, size.height) * 0.75f,
    )

    val artworkGloss: Brush
        get() = Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (premium) 0.14f else 0f),
            0.38f to Color.Transparent,
        )
}

data class ThemeOrb(
    val color: Color,

    val x: Float,
    val y: Float,

    val radius: Float,
)

val LocalInfusionTheme = compositionLocalOf { classicThemeTokens(InfusionTvAppearance.SelectionOrange) }

fun classicThemeTokens(accent: Color): InfusionThemeTokens = InfusionThemeTokens(
    preset = ThemePreset.CLASSIC,
    backgroundTop = InfusionTvAppearance.OledBlack,
    backgroundBottom = InfusionTvAppearance.OledBlack,
    ambientOrbs = emptyList(),
    gradient = listOf(accent, accent),
    titleGradient = listOf(InfusionTvAppearance.TextPrimary, InfusionTvAppearance.TextPrimary),
    onAccent = InfusionTvContour.FocusedContentColor,
    nativeAccent = accent,
    textPrimary = InfusionTvAppearance.TextPrimary,
    textMuted = InfusionTvAppearance.TextPrimary,
    glass = InfusionTvAppearance.Panel,
    glassStrong = InfusionTvAppearance.Panel,
    glassBorder = InfusionTvAppearance.PanelBorder,
    glow = Color.Transparent,
    cardRadius = InfusionTvContour.CornerRadius,
    panelRadius = InfusionTvContour.CornerRadius,
    displayFamily = FontFamily.Default,
    displayWeight = FontWeight.Normal,
    displayLetterSpacing = 0.sp,
    premium = false,
)

private object PremiumPalettes {
    val AuroraBackground = listOf(Color(0xFF060A1C), Color(0xFF0B1433))
    val AuroraOrbs = listOf(
        ThemeOrb(Color(0x8C7C5CFF), 0.16f, 0.10f, 0.55f),
        ThemeOrb(Color(0x61D222EE), 0.92f, 0.95f, 0.50f),
        ThemeOrb(Color(0x47EC4899), 0.46f, 0.55f, 0.42f),
    )
    val AuroraGradient = listOf(Color(0xFF7C5CFF), Color(0xFF4F8BFF), Color(0xFF22D3EE))

    val AuroraAccent = Color(0xFF7C5CFF)
    val AuroraTitle = listOf(Color(0xFFFFFFFF), Color(0xFFCFC6FF), Color(0xFF7FE9FF))

    val EmberBackground = listOf(Color(0xFF140608), Color(0xFF240A13))
    val EmberOrbs = listOf(
        ThemeOrb(Color(0x80FF7A00), 0.20f, 0.08f, 0.55f),
        ThemeOrb(Color(0x6BFF2D7A), 0.88f, 0.90f, 0.52f),
        ThemeOrb(Color(0x38FFC400), 0.62f, 0.42f, 0.38f),
    )
    val EmberGradient = listOf(Color(0xFFFFB300), Color(0xFFFF7A00), Color(0xFFFF2D7A))

    val EmberAccent = Color(0xFFFFA022)
    val EmberTitle = listOf(Color(0xFFFFF3E0), Color(0xFFFFB36B), Color(0xFFFF5C8A))

    val ObsidianBackground = listOf(Color(0xFF030303), Color(0xFF0F0D0A))
    val ObsidianOrbs = listOf(
        ThemeOrb(Color(0x42E8C27A), 0.22f, 0.12f, 0.52f),
        ThemeOrb(Color(0x3D8C6C3A), 0.85f, 0.92f, 0.48f),
        ThemeOrb(Color(0x14FFFFFF), 0.50f, 0.50f, 0.40f),
    )
    val ObsidianGradient = listOf(
        Color(0xFF9C7330),
        Color(0xFFE9CB84),
        Color(0xFFFFF6DC),
        Color(0xFFD8B062),
        Color(0xFF8E6A2A),
    )
    val ObsidianTitle = listOf(Color(0xFFFFFAF0), Color(0xFFECD09A), Color(0xFFA47A32))

    val ObsidianAccent = Color(0xFFE8C27A)
}

fun infusionThemeTokens(
    preset: ThemePreset,
    accent: Color,
    accentOverridden: Boolean = false,
): InfusionThemeTokens {
    if (preset.isClassic) return classicThemeTokens(accent)
    val base = when (preset) {
        ThemePreset.AURORA -> InfusionThemeTokens(
            preset = preset,
            backgroundTop = PremiumPalettes.AuroraBackground[0],
            backgroundBottom = PremiumPalettes.AuroraBackground[1],
            ambientOrbs = PremiumPalettes.AuroraOrbs,
            gradient = PremiumPalettes.AuroraGradient,
            titleGradient = PremiumPalettes.AuroraTitle,
            onAccent = Color(0xFFFFFFFF),
            nativeAccent = PremiumPalettes.AuroraAccent,
            textPrimary = Color(0xFFEEF0FF),
            textMuted = Color(0xFFD6DCFF).copy(alpha = 0.62f),
            glass = Color.White.copy(alpha = 0.06f),
            glassStrong = Color.White.copy(alpha = 0.11f),
            glassBorder = Color.White.copy(alpha = 0.13f),
            glow = Color(0xFF7C5CFF).copy(alpha = 0.60f),
            cardRadius = InfusionTvContour.CornerRadius,
            panelRadius = 18.dp,
            displayFamily = FontFamily.SansSerif,
            displayWeight = FontWeight.Bold,
            displayLetterSpacing = (-0.02).sp,
            premium = true,
        )
        ThemePreset.EMBER -> InfusionThemeTokens(
            preset = preset,
            backgroundTop = PremiumPalettes.EmberBackground[0],
            backgroundBottom = PremiumPalettes.EmberBackground[1],
            ambientOrbs = PremiumPalettes.EmberOrbs,
            gradient = PremiumPalettes.EmberGradient,
            titleGradient = PremiumPalettes.EmberTitle,
            onAccent = Color(0xFFFFFFFF),
            nativeAccent = PremiumPalettes.EmberAccent,
            textPrimary = Color(0xFFFFF4EE),
            textMuted = Color(0xFFFFE2D6).copy(alpha = 0.62f),
            glass = Color(0xFFFFECE2).copy(alpha = 0.06f),
            glassStrong = Color(0xFFFFECE2).copy(alpha = 0.11f),
            glassBorder = Color(0xFFFFAA78).copy(alpha = 0.18f),
            glow = Color(0xFFFF6A28).copy(alpha = 0.60f),
            cardRadius = InfusionTvContour.CornerRadius,
            panelRadius = 14.dp,
            displayFamily = FontFamily.SansSerif,
            displayWeight = FontWeight.Black,
            displayLetterSpacing = (-0.035).sp,
            premium = true,
        )
        ThemePreset.OBSIDIAN_GOLD -> InfusionThemeTokens(
            preset = preset,
            backgroundTop = PremiumPalettes.ObsidianBackground[0],
            backgroundBottom = PremiumPalettes.ObsidianBackground[1],
            ambientOrbs = PremiumPalettes.ObsidianOrbs,
            gradient = PremiumPalettes.ObsidianGradient,
            titleGradient = PremiumPalettes.ObsidianTitle,
            onAccent = Color(0xFF1A1206),
            nativeAccent = PremiumPalettes.ObsidianAccent,
            textPrimary = Color(0xFFF4EFE6),
            textMuted = Color(0xFFE8DEC8).copy(alpha = 0.55f),
            glass = Color(0xFFFFFAF0).copy(alpha = 0.035f),
            glassStrong = Color(0xFFFFFAF0).copy(alpha = 0.08f),
            glassBorder = Color(0xFFE8C27A).copy(alpha = 0.20f),
            glow = Color(0xFFE8C27A).copy(alpha = 0.40f),
            cardRadius = InfusionTvContour.CornerRadius,
            panelRadius = 6.dp,
            displayFamily = FontFamily.Serif,
            displayWeight = FontWeight.Normal,
            displayLetterSpacing = 0.005.sp,
            premium = true,
        )
        ThemePreset.CLASSIC -> classicThemeTokens(accent)
    }
    if (!accentOverridden) return base

    val overridden = listOf(
        lerp(accent, Color.White, 0.30f),
        accent,
        lerp(accent, Color.Black, 0.25f),
    )
    return base.copy(
        gradient = overridden,
        glow = accent.copy(alpha = 0.55f),

        nativeAccent = accent,

    )
}
