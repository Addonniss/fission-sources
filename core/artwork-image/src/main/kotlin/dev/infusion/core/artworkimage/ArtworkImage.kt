package dev.infusion.core.artworkimage

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.ArtworkPresentationRole
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.MediaPreview

val LocalArtworkLoader = staticCompositionLocalOf<ArtworkLoader?> { null }

val LocalArtworkLoadEnabled = compositionLocalOf { true }

@Composable
fun ArtworkImage(
    request: ArtworkRequest?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    arrivalFadeMillis: Int = 0,
    onLoaded: (() -> Unit)? = null,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Crop,
    colorFilter: ColorFilter? = null,
    onSettled: ((loaded: Boolean) -> Unit)? = null,
) {
    val loader = LocalArtworkLoader.current
    val loadEnabled = LocalArtworkLoadEnabled.current
    if (loader == null || request == null) return

    val sourceVariants = LocalArtworkSourceVariants.current
    val drawRequest = request.withSourceVariants(sourceVariants)
    var bitmap by remember(drawRequest) { mutableStateOf<Bitmap?>(null) }

    var settledOutcomeReported by remember(drawRequest) { mutableStateOf(false) }
    val settledOutcome = rememberUpdatedState(onSettled)

    var started by remember(drawRequest) { mutableIntStateOf(0) }

    var settled by remember(drawRequest) { mutableIntStateOf(0) }

    var misses by remember(drawRequest) { mutableIntStateOf(0) }
    var missAtGeneration by remember(drawRequest) { mutableLongStateOf(NoArtworkAvailabilityYet) }

    var followUpTick by remember(drawRequest) { mutableIntStateOf(0) }
    val availability by loader.availability.collectAsState()

    LaunchedEffect(loader, drawRequest, loadEnabled, followUpTick) {
        if (bitmap != null) return@LaunchedEffect
        if (!loadEnabled) {

            bitmap = loader.peekDecoded(drawRequest)

            if (bitmap == null && !settledOutcomeReported) {
                settledOutcomeReported = true
                settledOutcome.value?.invoke(false)
            }
            return@LaunchedEffect
        }
        val firstLoad = started == 0

        val interrupted = started > settled && started < MaxArtworkAttempts

        val availabilityFollowUp = !drawRequest.retainsPreviousSuccess &&
            misses in 1..MaxArtworkFollowUps &&
            availability != missAtGeneration
        if (!firstLoad && !interrupted && !availabilityFollowUp) return@LaunchedEffect
        started++
        val loaded = loader.load(drawRequest)
        settled++
        if (loaded != null) {
            bitmap = loaded
        } else {
            misses++
            missAtGeneration = availability
        }

        if (!settledOutcomeReported) {
            settledOutcomeReported = true
            settledOutcome.value?.invoke(loaded != null)
        }
    }

    LaunchedEffect(availability, loadEnabled) {
        if (bitmap != null || !loadEnabled) return@LaunchedEffect
        if (drawRequest.retainsPreviousSuccess) return@LaunchedEffect
        if (started > settled) return@LaunchedEffect
        if (misses !in 1..MaxArtworkFollowUps) return@LaunchedEffect
        if (availability == missAtGeneration) return@LaunchedEffect
        followUpTick++
    }
    if (onLoaded != null) {

        val callback = rememberUpdatedState(onLoaded)
        LaunchedEffect(bitmap) { if (bitmap != null) callback.value.invoke() }
    }
    ArtworkBitmap(bitmap, contentDescription, modifier, arrivalFadeMillis, alignment, contentScale, colorFilter)
}

@Composable
fun MediaArtworkImage(
    ref: MediaArtworkRef?,
    targetSize: IntSize,
    contentDescription: String?,
    modifier: Modifier = Modifier,

    contentScale: ContentScale = ContentScale.Crop,

    retainsPreviousSuccess: Boolean = true,

    presentationRole: ArtworkPresentationRole = ArtworkPresentationRole.BACKGROUND,

    onSettled: ((loaded: Boolean) -> Unit)? = null,
) {
    ArtworkImage(
        request = ref?.let { artwork ->
            ArtworkRequest(
                locator = artwork.locator,
                policy = artwork.policy,
                targetWidthPx = targetSize.width,
                targetHeightPx = targetSize.height,
                retainsPreviousSuccess = retainsPreviousSuccess,
                presentationRole = presentationRole,
            )
        },
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        onSettled = onSettled,
    )
}

@Composable
fun MediaPreviewArtwork(
    preview: MediaPreview,
    targetSize: IntSize,
    contentDescription: String?,
    modifier: Modifier = Modifier,

    contentScale: ContentScale = ContentScale.Crop,

    retainsPreviousSuccess: Boolean = false,

    presentationRole: ArtworkPresentationRole = ArtworkPresentationRole.LANDSCAPE_CARD,
) {
    MediaArtworkImage(
        ref = preview.artwork,
        targetSize = targetSize,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        retainsPreviousSuccess = retainsPreviousSuccess,
        presentationRole = presentationRole,
    )
}

@Composable
fun ArtworkImage(
    encodedBytes: ByteArray?,
    policy: ArtworkPolicy,
    targetSize: IntSize,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    arrivalFadeMillis: Int = 0,
    alignment: Alignment = Alignment.Center,
) {
    val loader = LocalArtworkLoader.current
    if (loader == null || encodedBytes == null || encodedBytes.isEmpty()) return
    var bitmap by remember(encodedBytes, policy, targetSize) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(loader, encodedBytes, policy, targetSize) {
        bitmap = loader.loadEncoded(
            encoded = encodedBytes,
            policy = policy,
            targetWidthPx = targetSize.width,
            targetHeightPx = targetSize.height,
        )
    }
    ArtworkBitmap(bitmap, contentDescription, modifier, arrivalFadeMillis, alignment)
}

@Composable
private fun ArtworkBitmap(
    bitmap: Bitmap?,
    contentDescription: String?,
    modifier: Modifier,
    arrivalFadeMillis: Int = 0,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Crop,
    colorFilter: ColorFilter? = null,
) {
    if (bitmap == null) return
    if (arrivalFadeMillis <= 0) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            alignment = alignment,
            contentScale = contentScale,
            colorFilter = colorFilter,
        )
        return
    }
    val alpha = remember(bitmap) { Animatable(0f) }
    LaunchedEffect(bitmap) { alpha.animateTo(1f, tween(arrivalFadeMillis)) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        modifier = modifier.graphicsLayer { this.alpha = alpha.value },
        alignment = alignment,
        contentScale = contentScale,
        colorFilter = colorFilter,
    )
}

private const val MaxArtworkFollowUps = 2

private const val MaxArtworkAttempts = 8

private const val NoArtworkAvailabilityYet = Long.MIN_VALUE
