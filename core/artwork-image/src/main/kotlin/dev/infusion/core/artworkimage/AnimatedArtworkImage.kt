package dev.infusion.core.artworkimage

import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

@Composable
fun AnimatedArtworkImage(
    request: ArtworkRequest?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val loader = LocalArtworkLoader.current
    val loadEnabled = LocalArtworkLoadEnabled.current
    if (loader == null || request == null) return
    val slotModifier = modifier.clipToBounds()

    val drawRequest = request.withSourceVariants(LocalArtworkSourceVariants.current)
    var artwork by remember(drawRequest) { mutableStateOf<AnimatedArtwork?>(null) }

    var decided by remember(drawRequest) { mutableStateOf(false) }
    LaunchedEffect(loader, drawRequest, loadEnabled) {
        if (!loadEnabled) return@LaunchedEffect
        if (decided) return@LaunchedEffect
        val loaded = loader.loadAnimated(drawRequest)
        if (!coroutineContext.isActive) {

            loaded?.release()
            return@LaunchedEffect
        }
        artwork = loaded
        decided = true
    }
    val animation = artwork
    if (animation != null) {
        AnimatedArtworkLayer(animation, contentDescription, slotModifier, alignment, contentScale)
    } else if (decided || !loadEnabled) {

        ArtworkImage(
            request = request,
            contentDescription = contentDescription,
            modifier = slotModifier,
            alignment = alignment,
            contentScale = contentScale,
        )
    }
}

@Composable
private fun AnimatedArtworkLayer(
    artwork: AnimatedArtwork,
    contentDescription: String?,
    modifier: Modifier,
    alignment: Alignment,
    contentScale: ContentScale,
) {
    val frameTicks = remember(artwork) { mutableIntStateOf(0) }
    val painter = remember(artwork) { AnimatedArtworkPainter(artwork, frameTicks) }
    DisposableEffect(artwork) {
        artwork.onFrameReady = { frameTicks.intValue += 1 }
        artwork.start()
        onDispose {

            artwork.onFrameReady = null
            artwork.release()
        }
    }
    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier,
        alignment = alignment,
        contentScale = contentScale,
    )
}

private class AnimatedArtworkPainter(
    private val artwork: AnimatedArtwork,
    private val frameTicks: MutableIntState,
) : Painter() {

    override val intrinsicSize: Size
        get() = when {
            artwork.intrinsicWidthPx > 0 && artwork.intrinsicHeightPx > 0 ->
                Size(artwork.intrinsicWidthPx.toFloat(), artwork.intrinsicHeightPx.toFloat())
            else -> Size.Unspecified
        }

    override fun DrawScope.onDraw() {

        frameTicks.intValue
        if (artwork.isReleased) return
        val width = size.width.toInt()
        val height = size.height.toInt()
        if (width <= 0 || height <= 0) return

        drawIntoCanvas { canvas -> artwork.draw(canvas.nativeCanvas, width, height) }
    }
}
