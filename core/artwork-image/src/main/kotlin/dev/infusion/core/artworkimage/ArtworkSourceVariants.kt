package dev.infusion.core.artworkimage

import androidx.compose.runtime.compositionLocalOf

fun interface ArtworkSourceVariants {

    fun resolve(request: ArtworkRequest): ArtworkRequest

    fun variantToken(request: ArtworkRequest): String = ""

    companion object {

        val Unchanged: ArtworkSourceVariants = ArtworkSourceVariants { request -> request }
    }
}

val LocalArtworkSourceVariants = compositionLocalOf<ArtworkSourceVariants> {
    ArtworkSourceVariants.Unchanged
}

fun ArtworkRequest.withSourceVariants(variants: ArtworkSourceVariants): ArtworkRequest {
    val resolved = variants.resolve(this)
    return if (resolved == this) this else resolved
}
