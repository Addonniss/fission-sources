package dev.infusion.feature.vertical.home

import dev.infusion.core.domain.home.HomeCardPresentation
import dev.infusion.core.domain.home.HomePresentationFamily
import dev.infusion.core.domain.home.HomePresentationPolicy
import dev.infusion.core.domain.home.HomeRowPresentation
import dev.infusion.core.model.MediaPreview
import dev.infusion.source.aggregation.discover.DiscoverItem
import dev.infusion.source.aggregation.discover.DiscoverShelf
import dev.infusion.source.aggregation.discover.ShelfState
import dev.infusion.source.aggregation.discover.providerRowDiscoverItem

internal fun DiscoverItem.toHomeCardPresentation(): HomeCardPresentation? {
    if (key.isBlank()) return null
    return HomeCardPresentation(
        key = key,
        titleId = preview.titleId ?: titleId,
        kind = preview.kind ?: kind,
        displayTitle = preview.displayTitle,
        year = preview.year,

        subtitle = preview.subtitle,
        providerCatalogId = item.catalogId,
        providerItemId = item.nativeItemId,

        artwork = HomePresentationPolicy.reusableArtwork(preview.artwork),
    )
}

internal fun HomeCardPresentation.toRestoredItem(): DiscoverItem? {
    if (key.isBlank()) return null
    return providerRowDiscoverItem(
        key = key,
        titleId = titleId,
        kind = kind,
        displayTitle = displayTitle,
        year = year,
        providerCatalogId = providerCatalogId,
        providerItemId = providerItemId,
        preview = MediaPreview(
            titleId = titleId,
            kind = kind,
            displayTitle = displayTitle,
            year = year,
            subtitle = subtitle,

            artwork = HomePresentationPolicy.reusableArtwork(artwork),

            progressFraction = null,
        ),
    )
}

internal fun homeRowPresentationOf(
    rowKey: String,
    family: HomePresentationFamily,
    definitionKey: String,
    shelf: DiscoverShelf,
    storedAtEpochMs: Long,
): HomeRowPresentation? {
    val loaded = shelf.state as? ShelfState.Loaded ?: return null
    val cards = loaded.items
        .take(HomePresentationPolicy.MAX_CARDS_PER_ROW)
        .mapNotNull { item -> item.toHomeCardPresentation() }
        .distinctBy { card -> card.key }
    if (cards.isEmpty()) return null
    return HomeRowPresentation(
        rowKey = rowKey,
        family = family,
        definitionKey = definitionKey,
        title = shelf.title,
        sourceLabel = shelf.sourceLabel,
        cards = cards,
        storedAtEpochMs = storedAtEpochMs,
    )
}

internal fun HomeRowPresentation.restoredShelf(): DiscoverShelf? {
    val items = cards.mapNotNull { card -> card.toRestoredItem() }
    if (items.isEmpty()) return null
    return DiscoverShelf(
        key = rowKey,
        title = title,
        sourceLabel = sourceLabel,
        state = ShelfState.Loaded(
            items = items,

            hasMore = true,
        ),
    )
}

internal fun homeRowSourceIdentity(row: HomeWidgetRow): Pair<HomePresentationFamily, String>? = when {
    row.catalog != null -> HomePresentationFamily.CATALOG to
        "addon:${row.catalog.addonId}:${row.catalog.catalogType}:${row.catalog.catalogId}"
    row.publicList != null -> HomePresentationFamily.PUBLIC_LIST to buildString {
        append("public:").append(row.publicList.provider.id)
        append('/').append(row.publicList.listId)
        row.publicList.owner?.let { append("/owner=").append(it) }
        row.publicList.slug?.let { append("/slug=").append(it) }
    }
    row.collectionGroup != null -> HomePresentationFamily.COLLECTION_GROUP to
        "group:${row.collectionGroup.packId}/${row.collectionGroup.groupKey}"
    else -> null
}

internal fun homeRowDefinitionKey(
    profileId: String,
    row: HomeWidgetRow,
    family: HomePresentationFamily,
    sourceIdentity: String,
    presentationToken: String,
    localeToken: String,
): String = HomePresentationPolicy.definitionKey(
    profileId = profileId,
    rowKey = row.rowKey,
    family = family,
    sourceIdentity = sourceIdentity,
    title = row.displayTitle,
    presentationToken = presentationToken,
    localeToken = localeToken,
)
