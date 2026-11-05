package dev.infusion.tv.ui.screens

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint as AndroidPaint
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import dev.infusion.core.playback.api.PlaybackUiDolbyVisionLuminance

internal fun Modifier.playbackUiDolbyVisionLuminance(scale: Float): Modifier {

    if (!isApplicableUiLuminanceScale(scale)) return this
    return drawWithCache {
        val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(PlaybackUiDolbyVisionLuminance.rgbScaleColorMatrix(scale)),
            )
        }
        onDrawWithContent {
            drawIntoCanvas { canvas ->
                val save = canvas.nativeCanvas.saveLayer(0f, 0f, size.width, size.height, paint)
                drawContent()
                canvas.nativeCanvas.restoreToCount(save)
            }
        }
    }
}

internal fun isApplicableUiLuminanceScale(scale: Float): Boolean =
    scale.isFinite() && scale > 0f && scale < PlaybackUiDolbyVisionLuminance.IDENTITY_SCALE
