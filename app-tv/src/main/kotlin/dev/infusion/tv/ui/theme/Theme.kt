package dev.infusion.tv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import dev.infusion.core.domain.appearance.ThemePreset
import dev.infusion.core.domain.appearance.TopShelfAppearance
import dev.infusion.tv.ui.presentation.ContentCardPresentation
import dev.infusion.tv.ui.presentation.LocalContentCardPresentation

@Composable
fun InfusionTvTheme(
    accent: Color = InfusionTvAppearance.DefaultAccentColor,

    theme: ThemePreset = ThemePreset.DEFAULT,

    themeAccent: Boolean = false,

    cardPresentation: ContentCardPresentation = ContentCardPresentation.Default,

    topShelfAppearance: TopShelfAppearance = TopShelfAppearance.DEFAULT,
    content: @Composable () -> Unit,
) {

    val tokens = infusionThemeTokensFor(theme, accent, themeAccent)

    val effectiveAccent = if (themeAccent) tokens.nativeAccent else accent
    MaterialTheme(
        colorScheme = infusionTvColorScheme(tokens),
        typography = infusionTvTypography(tokens),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides tokens.textPrimary,
            LocalInfusionAccent provides effectiveAccent,
            LocalInfusionTheme provides tokens,
            LocalContentCardPresentation provides cardPresentation,
            LocalTvTopShelfAppearance provides topShelfAppearance,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()

                    .background(tokens.backgroundTop),
            ) {
                InfusionAmbientBackdrop()
                content()
            }
        }
    }
}

private fun infusionThemeTokensFor(
    theme: ThemePreset,
    accent: Color,
    themeAccent: Boolean,
): InfusionThemeTokens =
    infusionThemeTokens(
        preset = theme,
        accent = accent,

        accentOverridden = !theme.isClassic && !themeAccent,
    )

val LocalTvTopShelfAppearance = compositionLocalOf { TopShelfAppearance.DEFAULT }

fun infusionTvTypography(
    tokens: InfusionThemeTokens = classicThemeTokens(InfusionTvAppearance.DefaultAccentColor),
): Typography = Typography().let { library ->
    Typography(
        bodyLarge = InfusionTvScaleCalibration.Type.Body,
        labelLarge = InfusionTvScaleCalibration.Type.Emphasis,
        displayLarge = library.displayLarge.withProductFontFeatures().withDisplayFace(tokens),
        displayMedium = library.displayMedium.withProductFontFeatures().withDisplayFace(tokens),
        displaySmall = library.displaySmall.withProductFontFeatures().withDisplayFace(tokens),
        headlineLarge = library.headlineLarge.withProductFontFeatures().withDisplayFace(tokens),
        headlineMedium = library.headlineMedium.withProductFontFeatures().withDisplayFace(tokens),
        headlineSmall = library.headlineSmall.withProductFontFeatures().withDisplayFace(tokens),
        titleLarge = library.titleLarge.withProductFontFeatures(),
        titleMedium = library.titleMedium.withProductFontFeatures(),
        titleSmall = library.titleSmall.withProductFontFeatures(),
        bodyMedium = library.bodyMedium.withProductFontFeatures(),
        bodySmall = library.bodySmall.withProductFontFeatures(),
        labelMedium = library.labelMedium.withProductFontFeatures(),
        labelSmall = library.labelSmall.withProductFontFeatures(),
    )
}

private fun TextStyle.withDisplayFace(tokens: InfusionThemeTokens): TextStyle =
    if (!tokens.premium) {
        this
    } else {
        copy(
            fontFamily = tokens.displayFamily,
            fontWeight = tokens.displayWeight,
            letterSpacing = tokens.displayLetterSpacing,
        )
    }

private fun TextStyle.withProductFontFeatures() =
    copy(fontFeatureSettings = InfusionTvFontFeatureSettings)
