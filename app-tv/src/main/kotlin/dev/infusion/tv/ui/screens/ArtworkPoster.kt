package dev.infusion.tv.ui.screens

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.infusion.core.artworkimage.ArtworkImage
import dev.infusion.core.artworkimage.MediaArtworkImage
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.feature.vertical.model.DetailsArtwork

@Composable
internal fun TvArtworkPoster(
    artwork: DetailsArtwork,
    size: DpSize = DpSize(width = 120.dp, height = 180.dp),
) {
    val targetSize = with(LocalDensity.current) {
        IntSize(size.width.roundToPx(), size.height.roundToPx())
    }
    val bytes = artwork.bytes
    if (artwork.available && bytes != null) {
        ArtworkImage(
            encodedBytes = bytes,
            policy = ArtworkPolicy.POSTER,
            targetSize = targetSize,
            contentDescription = "Poster",
            modifier = Modifier.size(size),
        )
        return
    }

    MediaArtworkImage(
        ref = artwork.ref,
        targetSize = targetSize,
        contentDescription = "Poster",
        modifier = Modifier.size(size),
    )
}

@Composable
internal fun TvArtworkPoster(
    bytes: ByteArray?,
    size: DpSize = DpSize(width = 120.dp, height = 180.dp),
    available: Boolean = true,
) {
    TvArtworkPoster(artwork = DetailsArtwork(bytes = bytes, available = available), size = size)
}
