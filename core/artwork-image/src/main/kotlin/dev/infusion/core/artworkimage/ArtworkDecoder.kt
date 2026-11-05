package dev.infusion.core.artworkimage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

data class ArtworkSize(val width: Int, val height: Int)

interface ArtworkDecoder {

    fun sourceSize(encoded: ByteArray): ArtworkSize?

    fun decode(encoded: ByteArray, sampleSize: Int): Bitmap?
}

class BitmapFactoryArtworkDecoder : ArtworkDecoder {

    override fun sourceSize(encoded: ByteArray): ArtworkSize? {
        if (encoded.isEmpty()) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return ArtworkSize(options.outWidth, options.outHeight)
    }

    override fun decode(encoded: ByteArray, sampleSize: Int): Bitmap? {
        if (encoded.isEmpty()) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options) ?: return null
        return orientToDisplay(decoded, readExifOrientation(encoded))
    }

    private fun orientToDisplay(decoded: Bitmap, exifOrientation: Int): Bitmap {
        val transform = ArtworkOrientation.transformFor(exifOrientation)
        if (transform.isIdentity) return decoded
        val width = decoded.width
        val height = decoded.height
        val source = IntArray(width * height)
        decoded.getPixels(source, 0, width, 0, 0, width, height)
        val outWidth = transform.displayWidth(width, height)
        val outHeight = transform.displayHeight(width, height)
        val oriented = IntArray(outWidth * outHeight)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val dx = transform.destinationX(x, y, width, height)
                val dy = transform.destinationY(x, y, width, height)
                oriented[dy * outWidth + dx] = source[rowOffset + x]
            }
        }
        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        output.setPixels(oriented, 0, outWidth, 0, 0, outWidth, outHeight)
        decoded.recycle()
        return output
    }

    private fun readExifOrientation(encoded: ByteArray): Int = runCatching {
        ByteArrayInputStream(encoded).use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
}

object ArtworkOrientation {

    fun transformFor(exifOrientation: Int): ArtworkOrientationTransform = when (exifOrientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ArtworkOrientationTransform(0, mirrored = true)
        ExifInterface.ORIENTATION_ROTATE_180 -> ArtworkOrientationTransform(180, mirrored = false)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> ArtworkOrientationTransform(180, mirrored = true)
        ExifInterface.ORIENTATION_TRANSPOSE -> ArtworkOrientationTransform(90, mirrored = true)
        ExifInterface.ORIENTATION_ROTATE_90 -> ArtworkOrientationTransform(90, mirrored = false)
        ExifInterface.ORIENTATION_TRANSVERSE -> ArtworkOrientationTransform(270, mirrored = true)
        ExifInterface.ORIENTATION_ROTATE_270 -> ArtworkOrientationTransform(270, mirrored = false)
        else -> ArtworkOrientationTransform(0, mirrored = false)
    }
}

data class ArtworkOrientationTransform(

    val rotationDegrees: Int,

    val mirrored: Boolean,
) {

    val isIdentity: Boolean get() = rotationDegrees == 0 && !mirrored

    private val swapsAxes: Boolean get() = rotationDegrees == 90 || rotationDegrees == 270

    fun displayWidth(width: Int, height: Int): Int = if (swapsAxes) height else width

    fun displayHeight(width: Int, height: Int): Int = if (swapsAxes) width else height

    fun destinationX(x: Int, y: Int, width: Int, height: Int): Int {
        val rotatedX = when (rotationDegrees) {
            90 -> height - 1 - y
            180 -> width - 1 - x
            270 -> y
            else -> x
        }
        return if (mirrored) displayWidth(width, height) - 1 - rotatedX else rotatedX
    }

    fun destinationY(x: Int, y: Int, width: Int, height: Int): Int = when (rotationDegrees) {
        90 -> x
        180 -> height - 1 - y
        270 -> width - 1 - x
        else -> y
    }
}

object ArtworkDownsampling {

    fun sampleSize(source: ArtworkSize, targetWidthPx: Int, targetHeightPx: Int): Int {
        if (source.width <= 0 || source.height <= 0) return 1
        if (targetWidthPx <= 0 || targetHeightPx <= 0) return 1
        var sample = 1
        while (
            source.width / (sample * 2) >= targetWidthPx &&
            source.height / (sample * 2) >= targetHeightPx
        ) {
            sample *= 2
        }
        return sample
    }
}
