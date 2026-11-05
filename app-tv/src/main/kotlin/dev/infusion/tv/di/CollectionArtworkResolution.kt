package dev.infusion.tv.di

import dev.infusion.core.domain.catalog.CatalogImage
import dev.infusion.core.domain.collections.CollectionArtworkUrls

internal suspend fun resolveCollectionArtwork(
    reference: String,
    fetch: suspend (reference: String) -> CatalogImage?,
): CatalogImage? {
    for (candidate in CollectionArtworkUrls.resolutionOrder(reference)) {
        val image = fetch(candidate)
        if (image != null) return image
    }
    return null
}
