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
import dev.infusion.core.domain.appearance.TopShelfAppearance
import dev.infusion.tv.ui.presentation.ContentCardPresentation
import dev.infusion.tv.ui.presentation.LocalContentCardPresentation

@Composable
fun InfusionTvTheme(
    accent: Color = InfusionTvAppearance.DefaultAccentColor,

    cardPresentation: ContentCardPresentation = ContentCardPresentation.Default,

    topShelfAppearance: TopShelfAppearance = TopShelfAppearance.DEFAULT,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = infusionTvColorScheme(accent),
        typography = infusionTvTypography(),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides InfusionTvAppearance.TextPrimary,
            LocalInfusionAccent provides accent,
            LocalContentCardPresentation provides cardPresentation,
            LocalTvTopShelfAppearance provides topShelfAppearance,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(InfusionTvAppearance.OledBlack),
            ) {
                content()
            }
        }
    }
}

val LocalTvTopShelfAppearance = compositionLocalOf { TopShelfAppearance.DEFAULT }

fun infusionTvTypography(): Typography = Typography().let { library ->
    Typography(
        bodyLarge = InfusionTvScaleCalibration.Type.Body,
        labelLarge = InfusionTvScaleCalibration.Type.Emphasis,
        displayLarge = library.displayLarge.withProductFontFeatures(),
        displayMedium = library.displayMedium.withProductFontFeatures(),
        displaySmall = library.displaySmall.withProductFontFeatures(),
        headlineLarge = library.headlineLarge.withProductFontFeatures(),
        headlineMedium = library.headlineMedium.withProductFontFeatures(),
        headlineSmall = library.headlineSmall.withProductFontFeatures(),
        titleLarge = library.titleLarge.withProductFontFeatures(),
        titleMedium = library.titleMedium.withProductFontFeatures(),
        titleSmall = library.titleSmall.withProductFontFeatures(),
        bodyMedium = library.bodyMedium.withProductFontFeatures(),
        bodySmall = library.bodySmall.withProductFontFeatures(),
        labelMedium = library.labelMedium.withProductFontFeatures(),
        labelSmall = library.labelSmall.withProductFontFeatures(),
    )
}

private fun TextStyle.withProductFontFeatures() =
    copy(fontFeatureSettings = InfusionTvFontFeatureSettings)
