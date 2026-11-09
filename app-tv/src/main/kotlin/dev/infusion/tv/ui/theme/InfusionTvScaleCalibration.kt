package dev.infusion.tv.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

const val InfusionTvFontFeatureSettings: String = "\"liga\" 0"

object InfusionTvScaleCalibration {

    fun ofScreenWidth(fraction: Float, heroMeasure: Dp): Dp =
        (heroMeasure + InfusionTvDetailsMetrics.PageHorizontalPadding * 2) * fraction

    object Synopsis {

        val FontSize = 9.5.sp

        val LineHeight = 13.5.sp

        val LetterSpacing = 0.15.sp

        val Style: TextStyle = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = FontSize,
            lineHeight = LineHeight,
            letterSpacing = LetterSpacing,
            fontFeatureSettings = InfusionTvFontFeatureSettings,
        )

        const val MaxLines = 4

        const val MaxScreenWidthFraction = 0.35f
    }

    object Type {

        private val EmphasisFontSize = 11.5.sp

        private val EmphasisLineHeight = 16.5.sp

        private val EmphasisLetterSpacing = 0.15.sp

        private val HeadlineFontSize = 13.5.sp

        private val HeadlineLineHeight = 19.sp

        private val HeadlineLetterSpacing = 0.1.sp

        private val DisplayFontSize = 19.sp

        private val DisplayLineHeight = 27.sp

        private val DisplayLetterSpacing = 0.1.sp

        val SettingsContent: TextStyle = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = HeadlineFontSize,
            lineHeight = HeadlineLineHeight,
            letterSpacing = HeadlineLetterSpacing,
            fontFeatureSettings = InfusionTvFontFeatureSettings,
        )

        val Body: TextStyle = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = Synopsis.FontSize,
            lineHeight = Synopsis.LineHeight,
            letterSpacing = Synopsis.LetterSpacing,
            fontFeatureSettings = InfusionTvFontFeatureSettings,
        )

        val Emphasis: TextStyle = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = EmphasisFontSize,
            lineHeight = EmphasisLineHeight,
            letterSpacing = EmphasisLetterSpacing,
            fontFeatureSettings = InfusionTvFontFeatureSettings,
        )

        val Headline: TextStyle = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = HeadlineFontSize,
            lineHeight = HeadlineLineHeight,
            letterSpacing = HeadlineLetterSpacing,
            fontFeatureSettings = InfusionTvFontFeatureSettings,
        )

        val Display: TextStyle = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = DisplayFontSize,
            lineHeight = DisplayLineHeight,
            letterSpacing = DisplayLetterSpacing,
            fontFeatureSettings = InfusionTvFontFeatureSettings,
        )

        val DisplayNormal: TextStyle = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = DisplayFontSize,
            lineHeight = DisplayLineHeight,
            letterSpacing = DisplayLetterSpacing,
            fontFeatureSettings = InfusionTvFontFeatureSettings,
        )

        val HeadlineNormal: TextStyle = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = HeadlineFontSize,
            lineHeight = HeadlineLineHeight,
            letterSpacing = HeadlineLetterSpacing,
            fontFeatureSettings = InfusionTvFontFeatureSettings,
        )

        val Title: TextStyle = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = EmphasisFontSize,
            lineHeight = EmphasisLineHeight,
            letterSpacing = EmphasisLetterSpacing,
            fontFeatureSettings = InfusionTvFontFeatureSettings,
        )

        val SourceRow: TextStyle = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = EmphasisFontSize,
            lineHeight = EmphasisLineHeight,
            letterSpacing = EmphasisLetterSpacing,
            fontFeatureSettings = InfusionTvFontFeatureSettings,
        )
    }

    object ClearLogo {

        const val MaxScreenWidthFraction = 0.25f
    }
}
