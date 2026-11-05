package dev.infusion.source.aggregation.discover

import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.source.aggregation.match.AddonMetaItem
import dev.infusion.source.stremio.policy.StremioContentType

fun providerRowDiscoverItem(
    key: String,
    titleId: TitleId?,
    kind: TitleKind?,
    displayTitle: String,
    year: Int?,
    providerCatalogId: String,
    providerItemId: String,
    preview: MediaPreview,
): DiscoverItem {
    val catalogType = when (kind) {
        TitleKind.SERIES -> StremioContentType.SERIES
        else -> StremioContentType.MOVIE
    }
    return DiscoverItem(
        key = key,
        titleId = titleId,
        item = AddonMetaItem(
            catalogType = catalogType,
            catalogId = providerCatalogId,
            nativeItemId = providerItemId,
            displayTitle = displayTitle,
            year = year,
            releaseInfo = year?.toString(),
            genres = emptyList(),
            description = null,
            imdbRating = null,
        ),
        kind = kind,
        match = null,
        preview = preview,
    )
}
