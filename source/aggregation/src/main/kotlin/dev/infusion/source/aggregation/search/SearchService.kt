package dev.infusion.source.aggregation.search

import dev.infusion.core.model.AvailabilityKind
import dev.infusion.core.model.TitleId
import dev.infusion.source.aggregation.AddonAvailabilityStore
import dev.infusion.source.aggregation.AddonRegistryPort
import dev.infusion.source.aggregation.AddonRuntimeDescriptor
import dev.infusion.source.aggregation.TitleAvailabilityLookup
import dev.infusion.source.aggregation.discover.addonArtworkRef
import dev.infusion.source.aggregation.match.AddonMatch
import dev.infusion.source.aggregation.match.AddonMetaItem
import dev.infusion.source.aggregation.match.AddonMetaItemAdapter
import dev.infusion.source.aggregation.match.CanonicalMatcher
import dev.infusion.source.aggregation.resource.AddonArtworkRepository
import dev.infusion.source.aggregation.resource.AddonCatalogRepository
import dev.infusion.source.aggregation.resource.AddonFetchedItemArtwork
import dev.infusion.source.aggregation.resource.DeclaredCatalog
import dev.infusion.source.stremio.StremioResult
import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.wire.StremioCatalogResponse
import dev.infusion.source.stremio.wire.StremioMetaPreview
import java.util.LinkedHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

class SearchService(
    private val registry: AddonRegistryPort,
    private val catalogRepository: AddonCatalogRepository,
    private val matcher: CanonicalMatcher,
    private val availabilityStore: AddonAvailabilityStore,
    private val titleAvailability: TitleAvailabilityLookup,
    private val providerTimeoutMs: Long = 10_000,

    private val artworkRepository: AddonArtworkRepository? = null,
) : AddonCatalogSearch {

    private fun searchOwnerKey(addonId: String): String = "search:$addonId"

    override suspend fun search(
        term: String,
        viewer: dev.infusion.core.model.profile.Profile,
        primary: dev.infusion.core.model.profile.Profile,
    ): List<SearchGroup> = coroutineScope {
        if (term.isBlank()) return@coroutineScope emptyList()
        val groups = LinkedHashMap<String, SearchGroup>()
        val unmatchedOrder = LinkedHashMap<String, SearchGroup>()

        val runtimes = registry.runtimesFor(viewer, primary)
        val results = runtimes.map { runtime ->
            async { runtime to searchOne(runtime, term) }
        }.awaitAll()

        for ((runtime, hits) in results) {
            for (hit in hits) {
                val matched = hit.match as? AddonMatch.Matched
                val groupKey = matched?.titleId?.value?.toString()
                    ?: "unmatched:${runtime.addonId}:${hit.item.nativeItemId}"
                if (groupKey.startsWith("unmatched:")) {
                    unmatchedOrder.putIfAbsent(
                        groupKey,
                        SearchGroup(
                            key = groupKey,
                            titleId = null,
                            displayTitle = hit.item.displayTitle,
                            year = hit.item.year,
                            badges = setOf(AvailabilityBadge.METADATA),
                            items = mutableListOf(hit),

                            kind = titleKindOf(hit.item.catalogType),
                        ),
                    )
                } else {
                    val titleId = matched!!.titleId
                    val existing = groups[groupKey]
                    if (existing != null) {
                        groups[groupKey] = existing.copy(
                            items = existing.items + hit,
                            badges = existing.badges + badgesFor(titleId),
                        )
                    } else {
                        groups[groupKey] = SearchGroup(
                            key = groupKey,
                            titleId = titleId,
                            displayTitle = hit.item.displayTitle,
                            year = hit.item.year,
                            badges = badgesFor(titleId),
                            items = mutableListOf(hit),

                            kind = titleKindOf(hit.item.catalogType),
                        )
                    }
                }
            }
        }
        return@coroutineScope groups.values.toList() + unmatchedOrder.values.toList()
    }

    private suspend fun searchOne(runtime: AddonRuntimeDescriptor, term: String): List<SearchHit> =
        coroutineScope {
            val catalogs = searchableCatalogs(runtime)
            if (catalogs.isEmpty()) return@coroutineScope emptyList()

            val answered = catalogs
                .map { catalog -> async { catalog to searchCatalog(runtime, catalog, term) } }
                .awaitAll()
                .mapNotNull { (catalog, response) -> response?.let { catalog to it } }
            if (answered.isEmpty()) return@coroutineScope emptyList()

            ownSearchPage(runtime, answered.flatMap { (_, response) -> response.metas })
            answered.flatMap { (catalog, response) ->
                response.metas.map { preview -> hitOf(runtime, catalog, preview) }
            }
        }

    private fun searchableCatalogs(runtime: AddonRuntimeDescriptor): List<DeclaredCatalog> {
        val byType = LinkedHashMap<StremioContentType, DeclaredCatalog>()
        for (catalog in runtime.declaredCatalogs) {

            if (catalog.type == StremioContentType.UNKNOWN) continue
            if (!isSearchable(catalog)) continue
            byType.putIfAbsent(catalog.type, catalog)
        }
        return byType.values.toList()
    }

    private fun isSearchable(catalog: DeclaredCatalog): Boolean =
        catalog.id == SEARCH_CATALOG_ID ||
            catalog.extras.any { it.equals(SEARCH_EXTRA_KEY, ignoreCase = true) }

    private suspend fun searchCatalog(
        runtime: AddonRuntimeDescriptor,
        catalog: DeclaredCatalog,
        term: String,
    ): StremioCatalogResponse? {
        val result = withTimeoutOrNull(providerTimeoutMs) {
            catalogRepository.catalog(
                addonId = runtime.addonId,
                type = catalog.type,
                catalogId = catalog.id,
                extras = listOf(SEARCH_EXTRA_KEY to term),
            )
        } ?: return null
        if (result is StremioResult.Err) return null
        return (result as StremioResult.Ok).value
    }

    private fun hitOf(
        runtime: AddonRuntimeDescriptor,
        catalog: DeclaredCatalog,
        preview: StremioMetaPreview,
    ): SearchHit {
        val item = AddonMetaItemAdapter.fromPreview(preview, catalog.type, catalog.id, runtime.sourceId)
        val match = matcher.match(item, "addon:${runtime.sourceId.value}")
        if (match is AddonMatch.Matched) {
            recordAvailability(runtime, item, match)
        }
        return SearchHit(
            key = "addon:${runtime.addonId}:${item.nativeItemId}",
            addonId = runtime.addonId,
            addonDisplayName = runtime.displayName,
            item = item,
            match = match,

            artwork = addonArtworkRef(
                addonId = runtime.addonId,
                nativeItemId = item.nativeItemId,
                posterAvailable = !preview.poster.isNullOrBlank(),
                landscapeAvailable = preview.background != null,
                posterIsLandscape = preview.posterIsLandscape,
            ),
        )
    }

    private fun ownSearchPage(
        runtime: AddonRuntimeDescriptor,
        metas: List<StremioMetaPreview>,
    ) {
        val artwork = artworkRepository ?: return
        val ownerKey = searchOwnerKey(runtime.addonId)
        runCatching {
            artwork.releaseFetchedPage(runtime.addonId, ownerKey)
            artwork.ownFetchedPage(
                addonId = runtime.addonId,
                ownerKey = ownerKey,
                items = metas.map { preview ->
                    AddonFetchedItemArtwork(
                        nativeItemId = preview.id,
                        posterUrl = preview.poster,
                        landscapeUrl = preview.background
                            ?: preview.poster.takeIf { preview.posterIsLandscape },
                    )
                },
            )
        }
    }

    private fun recordAvailability(
        runtime: AddonRuntimeDescriptor,
        item: AddonMetaItem,
        match: AddonMatch.Matched,
    ) {
        availabilityStore.upsert(
            dev.infusion.core.model.Availability(
                availabilityId = dev.infusion.core.model.AvailabilityId(
                    java.util.UUID.nameUUIDFromBytes("addon:${runtime.addonId}:${item.nativeItemId}".toByteArray()),
                ),
                titleId = match.titleId,
                editionId = null,
                sourceId = runtime.sourceId,
                nativeResourceId = item.nativeItemId,
                kind = AvailabilityKind.ADDON,
                observedAt = java.time.Instant.now(),
                provenance = dev.infusion.core.model.Provenance(
                    sourceId = runtime.sourceId,
                    protocolVersion = "stremio/1",
                    retrievedAt = java.time.Instant.now(),
                    normalizedBy = "SearchService",
                ),
            ),
        )
    }

    private fun badgesFor(titleId: TitleId): Set<AvailabilityBadge> {
        val availabilities = titleAvailability.byTitle(titleId)
        if (availabilities.isEmpty()) return setOf(AvailabilityBadge.METADATA)
        return buildSet {
            for (a in availabilities) {
                when (a.kind) {
                    AvailabilityKind.LOCAL -> add(AvailabilityBadge.LOCAL)
                    AvailabilityKind.SERVER -> add(AvailabilityBadge.SERVER)
                    AvailabilityKind.ADDON -> add(AvailabilityBadge.ADDON)
                    AvailabilityKind.LEGAL_LINK -> add(AvailabilityBadge.METADATA)
                }
            }
        }
    }

    private fun titleKindOf(type: StremioContentType): dev.infusion.core.model.TitleKind? = when (type) {
        StremioContentType.MOVIE -> dev.infusion.core.model.TitleKind.MOVIE
        StremioContentType.SERIES, StremioContentType.TV -> dev.infusion.core.model.TitleKind.SERIES
        StremioContentType.CHANNEL -> dev.infusion.core.model.TitleKind.CLIP
        StremioContentType.UNKNOWN -> null
    }

    private companion object {

        const val SEARCH_CATALOG_ID: String = "search"

        const val SEARCH_EXTRA_KEY: String = "search"
    }
}
