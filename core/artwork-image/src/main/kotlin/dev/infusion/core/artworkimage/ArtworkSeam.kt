package dev.infusion.core.artworkimage

import dev.infusion.core.model.ArtworkLocator
import dev.infusion.core.model.ArtworkRendition
import dev.infusion.core.model.ArtworkRenditionPolicy

interface ArtworkSeam {

    suspend fun catalogImage(
        nativeItemId: String,
        type: String = ArtworkLocator.DEFAULT_CATALOG_TYPE,
    ): ByteArray?

    suspend fun catalogImage(
        nativeItemId: String,
        type: String = ArtworkLocator.DEFAULT_CATALOG_TYPE,
        rendition: ArtworkRendition,
    ): ByteArray? = catalogImage(nativeItemId, type)

    suspend fun addonArtwork(addonId: String, nativeItemId: String): ByteArray?

    suspend fun addonLandscapeArtwork(addonId: String, nativeItemId: String): ByteArray? = null

    suspend fun addonEpisodeStill(nativeItemId: String): ByteArray?

    suspend fun metadataImage(provider: String, size: String, path: String): ByteArray? = null

    suspend fun userAvatar(origin: String, userId: String, tag: String): ByteArray? = null

    suspend fun collectionArtwork(
        packId: String,
        groupKey: String,
        tileKey: String,
        slot: String,
    ): ByteArray? = null
}

fun interface ArtworkBytesSource {
    suspend fun bytes(request: ArtworkRequest): ByteArray?
}

class SeamArtworkBytesSource(private val seam: ArtworkSeam) : ArtworkBytesSource {

    override suspend fun bytes(request: ArtworkRequest): ByteArray? {

        val rendition = ArtworkRenditionPolicy.forBox(request.targetWidthPx, request.targetHeightPx)
        return when (val target = ArtworkLocator.decode(request.locator)) {
            null -> null
            is ArtworkLocator.Target.Catalog ->
                seam.catalogImage(target.nativeItemId, rendition = rendition)
            is ArtworkLocator.Target.CatalogTyped ->
                seam.catalogImage(target.nativeItemId, target.type, rendition)
            is ArtworkLocator.Target.Addon ->
                seam.addonArtwork(target.addonId, target.nativeItemId)
            is ArtworkLocator.Target.AddonLandscape ->

                seam.addonLandscapeArtwork(target.addonId, target.nativeItemId)
            is ArtworkLocator.Target.Still ->

                seam.catalogImage(target.nativeItemId, rendition = rendition)
                    ?: seam.addonEpisodeStill(target.nativeItemId)
            is ArtworkLocator.Target.AddonStill ->

                seam.addonEpisodeStill(target.nativeItemId)
            is ArtworkLocator.Target.Metadata ->

                seam.metadataImage(target.provider, target.size, target.path)
            is ArtworkLocator.Target.UserAvatar ->

                seam.userAvatar(target.origin, target.userId, target.tag)
            is ArtworkLocator.Target.CollectionArtwork ->

                seam.collectionArtwork(target.packId, target.groupKey, target.tileKey, target.slot)
        }
    }
}
