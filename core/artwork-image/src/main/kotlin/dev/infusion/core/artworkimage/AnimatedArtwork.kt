package dev.infusion.core.artworkimage

import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import java.nio.ByteBuffer

data class AnimatedFrames(

    val frameCount: Int,

    val widthPx: Int,

    val heightPx: Int,
)

data class AnimatedFrameSize(val widthPx: Int, val heightPx: Int) {

    val pixelCount: Long get() = widthPx.toLong() * heightPx.toLong()
}

object AnimatedPresentation {

    fun frameSizeFor(
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        boxWidthPx: Int,
        boxHeightPx: Int,
    ): AnimatedFrameSize? {
        if (sourceWidthPx <= 0 || sourceHeightPx <= 0) return null
        if (boxWidthPx <= 0 || boxHeightPx <= 0) {
            return clampToBudget(AnimatedFrameSize(sourceWidthPx, sourceHeightPx))
        }

        val scale = minOf(
            1.0,
            maxOf(
                boxWidthPx.toDouble() / sourceWidthPx.toDouble(),
                boxHeightPx.toDouble() / sourceHeightPx.toDouble(),
            ),
        )
        val width = kotlin.math.round(sourceWidthPx * scale).toInt().coerceAtLeast(1)
        val height = kotlin.math.round(sourceHeightPx * scale).toInt().coerceAtLeast(1)
        return clampToBudget(AnimatedFrameSize(width, height))
    }

    private fun clampToBudget(size: AnimatedFrameSize): AnimatedFrameSize {
        if (size.pixelCount <= MaxFramePixels) return size
        val factor = kotlin.math.sqrt(MaxFramePixels.toDouble() / size.pixelCount.toDouble())
        return AnimatedFrameSize(
            widthPx = kotlin.math.round(size.widthPx * factor).toInt().coerceAtLeast(1),
            heightPx = kotlin.math.round(size.heightPx * factor).toInt().coerceAtLeast(1),
        )
    }

    const val MaxFramePixels: Long = 2_097_152L
}

object AnimatedFrameProbe {

    private const val GIF_TRAILER = 0x3B
    private const val GIF_EXTENSION = 0x21
    private const val GIF_IMAGE_DESCRIPTOR = 0x2C

    fun framesIn(encoded: ByteArray): AnimatedFrames? = when {
        isGif(encoded) -> gifFrames(encoded)
        isWebp(encoded) -> webpFrames(encoded)
        else -> null
    }

    private fun isGif(b: ByteArray): Boolean =
        b.size >= 14 &&
            b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() &&
            b[3] == '8'.code.toByte() && (b[4] == '7'.code.toByte() || b[4] == '9'.code.toByte()) &&
            b[5] == 'a'.code.toByte()

    private fun isWebp(b: ByteArray): Boolean =
        b.size >= 30 &&
            b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
            b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()

    private fun gifFrames(b: ByteArray): AnimatedFrames? {
        val width = u16(b, 6)
        val height = u16(b, 8)
        var index = 13
        val packed = b[10].toInt() and 0xFF
        if (packed and 0x80 != 0) index += 3 * (1 shl ((packed and 0x07) + 1))
        var frames = 0
        while (index < b.size) {
            when (b[index].toInt() and 0xFF) {
                GIF_TRAILER -> break
                GIF_EXTENSION -> {
                    if (index + 2 > b.size) return null
                    index = skipSubBlocks(b, index + 2) ?: return null
                }
                GIF_IMAGE_DESCRIPTOR -> {
                    if (index + 10 > b.size) return null
                    val flags = b[index + 9].toInt() and 0xFF
                    index += 10
                    if (flags and 0x80 != 0) index += 3 * (1 shl ((flags and 0x07) + 1))
                    if (index >= b.size) return null

                    index = skipSubBlocks(b, index + 1) ?: return null
                    frames++
                }
                else -> return null
            }
        }
        if (frames < 2) return null
        return AnimatedFrames(frameCount = frames, widthPx = width, heightPx = height)
    }

    private fun skipSubBlocks(b: ByteArray, start: Int): Int? {
        var index = start
        while (index < b.size) {
            val length = b[index].toInt() and 0xFF
            index += 1
            if (length == 0) return index
            index += length
        }
        return null
    }

    private fun webpFrames(b: ByteArray): AnimatedFrames? {
        var index = 12
        var frames = 0
        var width = 0
        var height = 0
        var animated = false
        while (index + 8 <= b.size) {
            val fourCc = String(b, index, 4, Charsets.US_ASCII)
            val size = i32(b, index + 4)
            if (size < 0) return null
            val body = index + 8
            when (fourCc) {
                "VP8X" -> {
                    if (body + 10 > b.size) return null
                    animated = animated || (b[body].toInt() and ANIMATION_FLAG != 0)
                    width = 1 + u24(b, body + 4)
                    height = 1 + u24(b, body + 7)
                }
                "ANIM" -> animated = true
                "ANMF" -> frames++
            }

            val next = body.toLong() + size.toLong() + (size.toLong() and 1L)
            if (next > b.size) break
            index = next.toInt()
        }
        if (!animated || frames < 2) return null
        return AnimatedFrames(frameCount = frames, widthPx = width, heightPx = height)
    }

    private fun u16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u24(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16)

    private fun i32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    private const val ANIMATION_FLAG = 0x02
}

interface AnimatedArtwork {

    val frameCount: Int

    val intrinsicWidthPx: Int

    val intrinsicHeightPx: Int

    var onFrameReady: (() -> Unit)?

    val isReleased: Boolean

    fun start()

    fun draw(canvas: Canvas, widthPx: Int, heightPx: Int)

    fun release()
}

internal class DrawableAnimatedArtwork(
    private val drawable: Drawable,
    override val frameCount: Int,
    override val intrinsicWidthPx: Int,
    override val intrinsicHeightPx: Int,
) : AnimatedArtwork {

    @Volatile
    override var isReleased: Boolean = false
        private set

    @Volatile
    override var onFrameReady: (() -> Unit)? = null

    private val frameCallback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            if (!isReleased) onFrameReady?.invoke()
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, whenMillis: Long) = Unit

        override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
    }

    init {
        drawable.callback = frameCallback
    }

    override fun start() {
        if (isReleased) return
        drawable.setVisible(true, false)
        (drawable as? Animatable)?.start()
    }

    override fun draw(canvas: Canvas, widthPx: Int, heightPx: Int) {
        if (isReleased || widthPx <= 0 || heightPx <= 0) return
        drawable.setBounds(0, 0, widthPx, heightPx)
        drawable.draw(canvas)
    }

    override fun release() {
        if (isReleased) return
        isReleased = true
        onFrameReady = null
        (drawable as? Animatable)?.stop()
        drawable.setVisible(false, false)
        drawable.callback = null
    }
}

sealed interface AnimatedArtworkResult {

    class Animation(val artwork: AnimatedArtwork) : AnimatedArtworkResult

    data object NotAnimated : AnimatedArtworkResult

    data object Undecodable : AnimatedArtworkResult
}

fun interface AnimatedArtworkDecoder {

    fun decode(encoded: ByteArray, targetWidthPx: Int, targetHeightPx: Int): AnimatedArtworkResult
}

class ImageDecoderAnimatedArtworkDecoder : AnimatedArtworkDecoder {

    override fun decode(
        encoded: ByteArray,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ): AnimatedArtworkResult {
        if (encoded.isEmpty()) return AnimatedArtworkResult.NotAnimated
        if (Build.VERSION.SDK_INT < ANIMATED_DECODER_API) return AnimatedArtworkResult.NotAnimated
        val frames = AnimatedFrameProbe.framesIn(encoded) ?: return AnimatedArtworkResult.NotAnimated

        val presented = AnimatedPresentation.frameSizeFor(
            sourceWidthPx = frames.widthPx,
            sourceHeightPx = frames.heightPx,
            boxWidthPx = targetWidthPx,
            boxHeightPx = targetHeightPx,
        )
        val source = ImageDecoder.createSource(ByteBuffer.wrap(encoded))
        return try {
            val drawable = ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                if (presented != null) {
                    decoder.setTargetSize(presented.widthPx, presented.heightPx)
                }
            }

            if (drawable !is Animatable) {
                AnimatedArtworkResult.NotAnimated
            } else {
                val width = presented?.widthPx ?: drawable.intrinsicWidth
                val height = presented?.heightPx ?: drawable.intrinsicHeight

                if (frameBudgetExceeded(width, height)) {
                    drawable.setVisible(false, false)
                    AnimatedArtworkResult.NotAnimated
                } else {
                    AnimatedArtworkResult.Animation(
                        DrawableAnimatedArtwork(
                            drawable = drawable,
                            frameCount = frames.frameCount,
                            intrinsicWidthPx = width,
                            intrinsicHeightPx = height,
                        ),
                    )
                }
            }
        } catch (t: Throwable) {

            AnimatedArtworkResult.Undecodable
        }
    }

    private fun frameBudgetExceeded(widthPx: Int, heightPx: Int): Boolean {
        if (widthPx <= 0 || heightPx <= 0) return true
        return widthPx.toLong() * heightPx.toLong() * ARGB_8888_BYTES > MaxFrameBytes
    }

    companion object {

        const val ANIMATED_DECODER_API: Int = 28

        const val ARGB_8888_BYTES: Long = 4L

        const val MaxFrameBytes: Long = AnimatedPresentation.MaxFramePixels * 4L
    }
}
