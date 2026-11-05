package dev.infusion.source.aggregation.discover

import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind

fun publicListDiscoverItem(
    key: String,
    titleId: TitleId?,
    kind: TitleKind?,
    displayTitle: String,
    year: Int?,
    providerCatalogId: String,
    providerItemId: String,
    preview: MediaPreview,
): DiscoverItem = providerRowDiscoverItem(
    key = key,
    titleId = titleId,
    kind = kind,
    displayTitle = displayTitle,
    year = year,
    providerCatalogId = providerCatalogId,
    providerItemId = providerItemId,
    preview = preview,
)
