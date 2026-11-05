package dev.infusion.core.playback.api

import kotlin.math.roundToInt

object PlaybackUiDolbyVisionLuminance {

    const val IDENTITY_SCALE: Float = 1.0f

    const val DEFAULT_SCALE: Float = 0.50f

    const val KODI_ANDROID_DEFAULT_SCALE: Float = 0.58f

    const val MIN_SCALE: Float = 0.50f

    const val MAX_SCALE: Float = 1.00f

    const val SCALE_STEP: Float = 0.02f

    fun isGenuineDolbyVision(video: VideoTrack?): Boolean =
        PlayingMediaInfo.dynamicRangeLabel(video) == PlayingMediaInfo.DYNAMIC_RANGE_DOLBY_VISION

    fun clampScale(scale: Float): Float =
        if (scale.isFinite()) scale.coerceIn(MIN_SCALE, MAX_SCALE) else DEFAULT_SCALE

    fun effectiveScale(scale: Float, video: VideoTrack?): Float =
        if (isGenuineDolbyVision(video)) clampScale(scale) else IDENTITY_SCALE

    fun steppedScale(current: Float, steps: Int): Float {
        if (steps == 0) return clampScale(current)
        val next = (clampScale(current) + steps * SCALE_STEP)
        return clampScale((next * 100f).roundToInt() / 100f)
    }

    fun rgbScaleColorMatrix(scale: Float): FloatArray = floatArrayOf(
        scale, 0f, 0f, 0f, 0f,
        0f, scale, 0f, 0f, 0f,
        0f, 0f, scale, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}
