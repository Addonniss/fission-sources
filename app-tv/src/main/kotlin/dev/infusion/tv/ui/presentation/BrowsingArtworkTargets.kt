package dev.infusion.tv.ui.presentation

import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.infusion.core.model.TitleKind

fun homeCwArtworkTargetPx(
    kind: TitleKind,
    policy: ContentCardPresentation,
    screenWidthDp: Int,
    density: Float,
): IntSize {
    if (screenWidthDp <= 0 || density <= 0f) return IntSize.Zero
    val shape = policy.shapeFor(kind)
    return contentCardArtworkTargetPx(
        rowViewport = ContentRowDensity.densityReferenceWidth(screenWidthDp.dp),
        shape = shape,
        aspectRatio = policy.aspectRatioForShape(shape),
        density = density,
    )
}
