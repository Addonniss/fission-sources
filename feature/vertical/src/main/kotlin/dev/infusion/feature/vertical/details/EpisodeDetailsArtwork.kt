package dev.infusion.feature.vertical.details

import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.metadata.ImageRef
import dev.infusion.feature.vertical.media.metadataArtworkLocator

object EpisodeDetailsArtwork {

    fun backgroundLocator(backdrop: ImageRef?, provider: String = "tmdb"): String? =
        metadataArtworkLocator(backdrop, ArtworkPolicy.BACKDROP, provider)

    fun logoLocator(logo: ImageRef?, provider: String = "tmdb"): String? =
        metadataArtworkLocator(logo, ArtworkPolicy.LOGO, provider)

    fun foregroundRef(episodeNativeItemId: String?): MediaArtworkRef? =
        episodeNativeItemId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { MediaArtworkRef.still(it) }
}
