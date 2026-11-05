package dev.infusion.core.model.metadata

import dev.infusion.core.model.ArtworkPolicy

object ArtworkSizeBuckets {

    const val PROVIDER_TMDB = "tmdb"

    const val FALLBACK = "original"

    fun bucketFor(provider: String, policy: ArtworkPolicy): String =
        when (provider.lowercase()) {
            PROVIDER_TMDB -> when (policy) {
                ArtworkPolicy.POSTER -> "w500"
                ArtworkPolicy.BACKDROP -> "w1280"
                ArtworkPolicy.LOGO -> "w780"
                ArtworkPolicy.STILL -> "w300"
            }
            else -> FALLBACK
        }
}
