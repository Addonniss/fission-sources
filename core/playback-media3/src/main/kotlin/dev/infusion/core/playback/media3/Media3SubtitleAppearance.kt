package dev.infusion.core.playback.media3

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.accessibility.CaptioningManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import dev.infusion.core.playback.api.SubtitleAppearance
import dev.infusion.core.playback.api.SubtitleAppearanceStyle
import dev.infusion.core.playback.api.SubtitleEdgeChoice
import dev.infusion.core.playback.api.SubtitleFontChoice

@UnstableApi
object Media3SubtitleAppearanceApplier {

    fun apply(subtitleView: SubtitleView, appearance: SubtitleAppearance, dimForeground: Boolean = false) {
        val style = appearance.resolvedStyle()
        if (style == null) {
            applySystem(subtitleView, dimForeground)
        } else {
            applyCustom(subtitleView, style, dimForeground)
        }
    }

    fun applySystem(subtitleView: SubtitleView, dimForeground: Boolean = false) {
        if (dimForeground) {

            subtitleView.setStyle(systemCaptionStyle(subtitleView, dimForeground = true))
        } else {
            subtitleView.setUserDefaultStyle()
        }
        subtitleView.setUserDefaultTextSize()
        subtitleView.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        subtitleView.setApplyEmbeddedStyles(true)
    }

    fun systemCaptionStyle(
        subtitleView: SubtitleView,
        dimForeground: Boolean = false,
    ): CaptionStyleCompat {
        val resolved = resolveSystemCaptionStyle(subtitleView)
        if (!dimForeground) return resolved
        return withForeground(
            resolved,
            dev.infusion.core.playback.api.SubtitleHdrDimming.dimForegroundArgb(resolved.foregroundColor),
        )
    }

    private fun resolveSystemCaptionStyle(subtitleView: SubtitleView): CaptionStyleCompat {
        if (subtitleView.isInEditMode) return CaptionStyleCompat.DEFAULT
        val captioningManager =
            subtitleView.context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
        return if (captioningManager != null && captioningManager.isEnabled) {
            CaptionStyleCompat.createFromCaptionStyle(captioningManager.userStyle)
        } else {
            CaptionStyleCompat.DEFAULT
        }
    }

    private fun withForeground(style: CaptionStyleCompat, foregroundColor: Int): CaptionStyleCompat =
        CaptionStyleCompat(
            foregroundColor,
            style.backgroundColor,
            style.windowColor,
            style.edgeType,
            style.edgeColor,
            style.typeface,
        )

    fun applyCustom(
        subtitleView: SubtitleView,
        style: SubtitleAppearanceStyle,
        dimForeground: Boolean = false,
    ) {
        subtitleView.setStyle(captionStyle(style, dimForeground))
        subtitleView.setFractionalTextSize(style.textSizeFraction)
        subtitleView.setBottomPaddingFraction(style.bottomPaddingFraction)
        subtitleView.setApplyEmbeddedStyles(style.applyEmbeddedStyles)
    }

    fun captionStyle(style: SubtitleAppearanceStyle, dimForeground: Boolean = false): CaptionStyleCompat =
        CaptionStyleCompat(
            if (dimForeground) {
                dev.infusion.core.playback.api.SubtitleHdrDimming.dimForegroundArgb(style.textColorArgb)
            } else {
                style.textColorArgb
            },
            style.backgroundColorArgb,
 Color.TRANSPARENT,
            edgeTypeFor(style.edge),
 Color.BLACK,
            typefaceFor(style.font),
        )

    fun typefaceFor(font: SubtitleFontChoice): Typeface? = when (font) {
        SubtitleFontChoice.SYSTEM_DEFAULT -> null
        SubtitleFontChoice.SANS_SERIF -> Typeface.SANS_SERIF
        SubtitleFontChoice.SERIF -> Typeface.SERIF
        SubtitleFontChoice.MONOSPACE -> Typeface.MONOSPACE
    }

    fun edgeTypeFor(edge: SubtitleEdgeChoice): Int = when (edge) {
        SubtitleEdgeChoice.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
        SubtitleEdgeChoice.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        SubtitleEdgeChoice.SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
    }
}

@UnstableApi
class SubtitleAppearanceBinder(
    private val apply: (SubtitleView, SubtitleAppearance, Boolean) -> Unit = Media3SubtitleAppearanceApplier::apply,
) {

    private var boundView: SubtitleView? = null
    private var applied: SubtitleAppearance? = null
    private var appliedDim: Boolean = false

    fun bind(
        subtitleView: SubtitleView?,
        appearance: SubtitleAppearance,
        dimForeground: Boolean = false,
    ) {
        if (subtitleView == null) return
        if (subtitleView === boundView && appearance == applied && dimForeground == appliedDim) return
        apply(subtitleView, appearance, dimForeground)
        boundView = subtitleView
        applied = appearance
        appliedDim = dimForeground
    }

    fun reset() {
        boundView = null
        applied = null
        appliedDim = false
    }
}
