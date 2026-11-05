package dev.infusion.tv.ui.presentation

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import dev.infusion.core.artworkimage.ArtworkLoader
import dev.infusion.core.artworkimage.ArtworkRequest
import dev.infusion.core.artworkimage.LocalArtworkLoader
import dev.infusion.tv.ui.theme.LocalInfusionAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal enum class EntityLogoRecolour {

    ORIGINAL,

    ACCENT,
}

internal const val EntityLogoVisibleAlphaMin: Float = 0.6f
internal const val EntityLogoMonochromeSpreadMax: Float = 0.10f
internal const val EntityLogoDarkLumaMax: Float = 0.35f
internal const val EntityLogoDarkFractionMin: Float = 0.98f
internal const val EntityLogoMinVisibleFraction: Float = 0.02f
internal const val EntityLogoMaxVisibleFraction: Float = 0.90f
internal const val EntityLogoMinSamples: Int = 64

internal const val EntityLogoMaxSamples: Int = 20_000

internal fun classifyEntityLogo(bitmap: Bitmap): EntityLogoRecolour {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return EntityLogoRecolour.ORIGINAL
    val step = sampleStep(width, height)
    var sampled = 0
    var visible = 0
    var darkMonochrome = 0
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val pixel = bitmap.getPixel(x, y)
            sampled++
            val alpha = ((pixel ushr 24) and 0xFF) / 255f
            if (alpha >= EntityLogoVisibleAlphaMin) {
                visible++
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                val spread = max(r, max(g, b)) - min(r, min(g, b))
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                if (spread <= EntityLogoMonochromeSpreadMax && luma <= EntityLogoDarkLumaMax) {
                    darkMonochrome++
                }
            }
            x += step
        }
        y += step
    }
    if (visible < EntityLogoMinSamples) return EntityLogoRecolour.ORIGINAL
    val visibleFraction = visible.toFloat() / sampled
    if (visibleFraction < EntityLogoMinVisibleFraction) return EntityLogoRecolour.ORIGINAL
    if (visibleFraction > EntityLogoMaxVisibleFraction) return EntityLogoRecolour.ORIGINAL
    val darkFraction = darkMonochrome.toFloat() / visible
    return if (darkFraction >= EntityLogoDarkFractionMin) {
        EntityLogoRecolour.ACCENT
    } else {
        EntityLogoRecolour.ORIGINAL
    }
}

private fun sampleStep(width: Int, height: Int): Int {
    val total = width.toLong() * height.toLong()
    if (total <= EntityLogoMaxSamples) return 1
    return max(1, ceil(sqrt(total.toDouble() / EntityLogoMaxSamples)).toInt())
}

private val EntityLogoRecolourCache = object : LruCache<String, EntityLogoRecolour>(64) {}

@Composable
internal fun entityLogoColorFilter(
    locator: String?,
    request: ArtworkRequest?,
    enabled: Boolean = true,
): ColorFilter? {
    if (!enabled || locator == null || request == null) return null
    val loader = LocalArtworkLoader.current ?: return null
    val accent = LocalInfusionAccent.current
    var recolour by remember(locator) { mutableStateOf(EntityLogoRecolourCache.get(locator)) }
    LaunchedEffect(loader, request, locator) {
        if (recolour == null) {
            recolour = classifyEntityLogoCached(loader, request, locator)
        }
    }
    return if (recolour == EntityLogoRecolour.ACCENT) {
        ColorFilter.tint(accent, BlendMode.SrcIn)
    } else {
        null
    }
}

private suspend fun classifyEntityLogoCached(
    loader: ArtworkLoader,
    request: ArtworkRequest,
    locator: String,
): EntityLogoRecolour {
    EntityLogoRecolourCache.get(locator)?.let { return it }
    val bitmap = runCatching { loader.load(request) }.getOrNull() ?: return EntityLogoRecolour.ORIGINAL
    val verdict = withContext(Dispatchers.Default) { classifyEntityLogo(bitmap) }
    EntityLogoRecolourCache.put(locator, verdict)
    return verdict
}
