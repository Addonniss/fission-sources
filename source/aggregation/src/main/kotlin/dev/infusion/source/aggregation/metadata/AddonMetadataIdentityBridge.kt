package dev.infusion.source.aggregation.metadata

import dev.infusion.core.domain.metadata.MediaIdentityResolver
import dev.infusion.core.domain.metadata.MetadataIdentityHint
import dev.infusion.core.domain.metadata.MetadataLocale
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.metadata.ExternalIds
import dev.infusion.core.model.metadata.MediaIdentity
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.source.aggregation.match.AddonMetaItem
import dev.infusion.source.aggregation.match.ExternalIdExtractor
import dev.infusion.source.stremio.policy.StremioContentType

class AddonMetadataIdentityBridge(
    private val resolver: MediaIdentityResolver,
) {

    suspend fun resolve(
        item: AddonMetaItem,
        locale: MetadataLocale,
    ): MediaIdentity? {
        val type = mediaTypeOf(item) ?: return null
        return resolver.resolveHint(hintOf(item, type), locale)
    }

    fun identityOf(item: AddonMetaItem): MediaIdentity? =
        mediaTypeOf(item)?.let { hintOf(item, it).toIdentity() }

    private fun hintOf(item: AddonMetaItem, mediaType: MediaType): MetadataIdentityHint =
        MetadataIdentityHint(
            type = mediaType,
            externalIds = ExternalIds.fromTyped(ExternalIdExtractor.extract(item.nativeItemId)),
            title = item.displayTitle.takeIf { it.isNotBlank() },
            year = item.year,
            sourceId = item.addonSourceId,
            nativeItemId = item.nativeItemId,
        )

    companion object {

        fun mediaTypeOf(item: AddonMetaItem): MediaType? = when (item.catalogType) {
            StremioContentType.MOVIE -> MediaType.MOVIE
            StremioContentType.SERIES -> MediaType.SHOW
            StremioContentType.CHANNEL,
            StremioContentType.TV,
            StremioContentType.UNKNOWN,
            -> null
        }
    }
}

fun MediaIdentity.titleKind(): TitleKind = when (type) {
    MediaType.MOVIE -> TitleKind.MOVIE
    MediaType.SHOW -> TitleKind.SERIES
}
