package dev.infusion.source.aggregation.resource

import dev.infusion.core.domain.catalog.CatalogImage

interface AddonArtworkRepository {

    fun retain(addonId: String, nativeItemId: String, posterUrl: String?)

    fun retainLandscape(addonId: String, nativeItemId: String, backgroundUrl: String?)

    suspend fun poster(addonId: String, nativeItemId: String): CatalogImage?

    suspend fun landscape(addonId: String, nativeItemId: String): CatalogImage?

    fun ownFetchedPage(addonId: String, ownerKey: String, items: List<AddonFetchedItemArtwork>)

    fun releaseFetchedPage(addonId: String, ownerKey: String)

    fun purgeSource(addonId: String)
}

data class AddonFetchedItemArtwork(
    val nativeItemId: String,

    val posterUrl: String?,

    val landscapeUrl: String?,
)
