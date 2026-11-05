package dev.infusion.core.model.metadata

import dev.infusion.core.model.ArtworkLocator
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.ArtworkPresentationRole

object TmdbBackdropVariant {

    fun resolve(
        locator: String,
        policy: ArtworkPolicy,
        role: ArtworkPresentationRole,
        backgroundBucket: String,
        landscapeCardBucket: String,
    ): String {
        if (policy != ArtworkPolicy.BACKDROP) return locator
        val bucket = when (role) {
            ArtworkPresentationRole.BACKGROUND -> backgroundBucket
            ArtworkPresentationRole.LANDSCAPE_CARD -> landscapeCardBucket
        }.trim().trim('/')
        if (bucket.isEmpty()) return locator
        val target = ArtworkLocator.decode(locator) as? ArtworkLocator.Target.Metadata
            ?: return locator
        if (!target.provider.equals(ArtworkSizeBuckets.PROVIDER_TMDB, ignoreCase = true)) {
            return locator
        }
        if (target.size == bucket) return locator
        return ArtworkLocator.metadata(target.provider, bucket, target.path)
    }
}
