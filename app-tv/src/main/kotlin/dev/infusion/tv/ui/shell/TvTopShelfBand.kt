package dev.infusion.tv.ui.shell

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.infusion.core.domain.appearance.TopShelfAppearance
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration

object TvTopShelfBand {

    val HorizontalPadding = 24.dp

    val VerticalPadding = 8.dp

    val TabHorizontalPadding = 12.dp

    val TabVerticalPadding = 8.dp

    val TabContentPadding = PaddingValues(
        horizontal = TabHorizontalPadding,
        vertical = TabVerticalPadding,
    )

    val TabGap = 4.dp

    val IconLabelGap = 6.dp

    val MinTabWidth = 48.dp

    const val MaxScreenWidthFraction: Float = 0.5f

    const val HiddenWhileSpotlightAlpha: Float = 0f

    const val VisibleAlpha: Float = 1f

    const val TabBackgroundAlpha: Float = 0.5f

    val TabBackgroundColor: Color =
        InfusionTvContour.ControlContainerColor.copy(alpha = TabBackgroundAlpha)

    fun alphaFor(spotlightOwnsFocus: Boolean): Float =
        if (spotlightOwnsFocus) HiddenWhileSpotlightAlpha else VisibleAlpha

    const val QualificationScreenDensity: Float = 2f

    const val QualificationScreenWidthPx: Int = 1920

    const val QualificationScreenHeightPx: Int = 1080

    val QualificationScreenWidthDp: Dp =
        (QualificationScreenWidthPx / QualificationScreenDensity).dp

    val QualificationScreenHeightDp: Dp =
        (QualificationScreenHeightPx / QualificationScreenDensity).dp

    fun qualificationScreenPx(logical: Dp): Float = logical.value * QualificationScreenDensity

    fun heightFor(labelLineHeight: Dp): Dp = labelLineHeight +
        TabContentPadding.calculateTopPadding() +
        TabContentPadding.calculateBottomPadding() +
        VerticalPadding * 2

    @Composable
    fun height(): Dp = heightFor(
        with(LocalDensity.current) {
            InfusionTvScaleCalibration.Type.Emphasis.lineHeight.toDp()
        },
    )

    fun tabWidthFor(labelWidth: Dp): Dp = labelWidth + TabHorizontalPadding * 2

    fun iconSizeFor(labelLineHeight: Dp): Dp = labelLineHeight

    @Composable
    fun iconSize(): Dp = iconSizeFor(
        with(LocalDensity.current) {
            InfusionTvScaleCalibration.Type.Emphasis.lineHeight.toDp()
        },
    )

    fun tabInkWidthFor(
        labelWidth: Dp,
        appearance: TopShelfAppearance,
        iconSize: Dp,
    ): Dp = when (appearance) {
        TopShelfAppearance.TEXT_ONLY -> labelWidth
        TopShelfAppearance.ICONS_ONLY -> iconSize
        TopShelfAppearance.ICONS_AND_TEXT -> iconSize + IconLabelGap + labelWidth
    }

    fun tabWidthFor(
        labelWidth: Dp,
        appearance: TopShelfAppearance,
        iconSize: Dp,
    ): Dp = maxOf(
        tabInkWidthFor(labelWidth, appearance, iconSize) + TabHorizontalPadding * 2,
        MinTabWidth,
    )

    fun tabRowWidth(labelWidths: List<Dp>): Dp =
        rowWidthFor(labelWidths.map { tabWidthFor(it) })

    fun tabRowWidth(
        labelWidths: List<Dp>,
        appearance: TopShelfAppearance,
        iconSize: Dp,
    ): Dp = rowWidthFor(labelWidths.map { tabWidthFor(it, appearance, iconSize) })

    fun rowWidthFor(itemWidths: List<Dp>): Dp =
        if (itemWidths.isEmpty()) {
            0.dp
        } else {
            itemWidths.fold(0.dp) { total, itemWidth -> total + itemWidth } +
                TabGap * (itemWidths.size - 1)
        }

    fun tabRowScreenFraction(
        rowWidth: Dp,
        screenWidth: Dp = QualificationScreenWidthDp,
    ): Float = if (screenWidth <= 0.dp) 0f else rowWidth / screenWidth

    fun fitsScreenWidthBudget(
        rowWidth: Dp,
        screenWidth: Dp = QualificationScreenWidthDp,
    ): Boolean = tabRowScreenFraction(rowWidth, screenWidth) <= MaxScreenWidthFraction
}

class TvTopShelfScrollSeam {

    var contentScrollPx: Float by mutableFloatStateOf(0f)
        private set

    var topResetToken: Int by mutableIntStateOf(0)
        private set

    fun requestTopReset() {
        topResetToken += 1
    }

    fun publish(scrollPx: Float) {
        contentScrollPx = scrollPx
    }
}

val LocalTvTopShelfScrollSeam = compositionLocalOf<TvTopShelfScrollSeam?> { null }

val LocalTvTopShelfInset = compositionLocalOf { 0.dp }
