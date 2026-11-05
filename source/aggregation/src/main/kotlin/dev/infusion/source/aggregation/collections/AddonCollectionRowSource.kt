package dev.infusion.source.aggregation.collections

import dev.infusion.core.domain.collections.CollectionBrowseItem
import dev.infusion.core.domain.collections.CollectionBrowseOutcome
import dev.infusion.core.domain.collections.CollectionBrowsePage
import dev.infusion.core.domain.collections.CollectionRowPageSource
import dev.infusion.core.domain.collections.CollectionSourceDefinition
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.profile.Profile
import dev.infusion.source.aggregation.AddonRegistryPort
import dev.infusion.source.aggregation.AddonRuntimeDescriptor
import dev.infusion.source.aggregation.match.ExternalIdExtractor
import dev.infusion.source.aggregation.resource.AddonArtworkRepository
import dev.infusion.source.aggregation.resource.AddonCatalogRepository
import dev.infusion.source.aggregation.resource.AddonFetchedItemArtwork
import dev.infusion.source.aggregation.resource.DeclaredCatalog
import dev.infusion.source.aggregation.discover.addonArtworkRef
import dev.infusion.source.stremio.StremioResult
import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.wire.StremioMetaPreview

class AddonCollectionRowSource(
    private val catalogRepository: AddonCatalogRepository,

    private val registry: AddonRegistryPort,

    private val viewer: () -> Profile,

    private val primary: () -> Profile,

    private val artworkRepository: AddonArtworkRepository,
) : CollectionRowPageSource {

    override suspend fun page(
        definition: CollectionSourceDefinition,
        cursor: String?,
        ownerKey: String?,
    ): CollectionBrowseOutcome {
        if (!definition.provider.trim().equals(PROVIDER, ignoreCase = true)) {
            return CollectionBrowseOutcome.Unsupported(UNSUPPORTED_PROVIDER)
        }
        val catalogId = definition.catalogId?.trim().orEmpty()
        if (catalogId.isEmpty()) return CollectionBrowseOutcome.Unsupported(MISSING_CATALOG_ID)

        val runtimes = registry.runtimesFor(viewer(), primary())
        val preferredAddonId = definition.addonId?.trim()?.takeIf { it.isNotEmpty() }
        val resolved = resolveCatalog(runtimes, preferredAddonId, catalogId, definition.catalogType)
            ?: return CollectionBrowseOutcome.Unsupported(

                if (preferredAddonId != null && runtimes.none { it.addonId == preferredAddonId }) {
                    ADDON_UNAVAILABLE
                } else {
                    CATALOG_UNAVAILABLE
                },
            )
        val (runtime, catalog) = resolved

        val extras = extrasOf(definition, catalog)
            ?: return CollectionBrowseOutcome.Unsupported(QUERY_UNSUPPORTED)
        val skip = cursor?.toIntOrNull()?.takeIf { it > 0 }

        return try {
            when (val result = catalogRepository.catalog(runtime.addonId, catalog.type, catalog.id, extras, skip)) {
                is StremioResult.Ok -> {
                    val metas = result.value.metas

                    artworkRepository.ownFetchedPage(
                        addonId = runtime.addonId,
                        ownerKey = ownerKey ?: rowOwnerKey(definition, catalog),
                        items = metas.map { preview ->
                            AddonFetchedItemArtwork(
                                nativeItemId = preview.id,
                                posterUrl = preview.poster,
                                landscapeUrl = preview.background
                                    ?: preview.poster.takeIf { preview.posterIsLandscape },
                            )
                        },
                    )
                    val items = metas.mapNotNull { preview ->
                        preview.toBrowseItem(catalog.type, runtime.addonId)
                    }

                    val supportsSkip = catalog.extras.any { it.equals(SKIP_EXTRA, ignoreCase = true) }
                    val hasMore = supportsSkip && metas.isNotEmpty()
                    CollectionBrowseOutcome.Loaded(
                        CollectionBrowsePage(
                            items = items,
                            hasMore = hasMore,
                            nextCursor = if (hasMore) ((skip ?: 0) + metas.size).toString() else null,
                        ),
                    )
                }
                is StremioResult.Err -> CollectionBrowseOutcome.Failed(result.failure.reason.name.lowercase())
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            CollectionBrowseOutcome.Failed(error.message?.take(80) ?: ROW_FAILED)
        }
    }

    private fun resolveCatalog(
        runtimes: List<AddonRuntimeDescriptor>,
        preferredAddonId: String?,
        catalogId: String,
        catalogType: String?,
    ): Pair<AddonRuntimeDescriptor, DeclaredCatalog>? {
        val type = catalogType?.trim()?.lowercase()?.let { StremioContentType.parse(it) }
        fun matches(catalog: DeclaredCatalog): Boolean =
            catalog.id == catalogId && (type == null || catalog.type == type)

        if (preferredAddonId != null) {
            val runtime = runtimes.firstOrNull { it.addonId == preferredAddonId }
            val catalog = runtime?.declaredCatalogs?.firstOrNull(::matches)
            if (runtime != null && catalog != null) return runtime to catalog
        }
        for (runtime in runtimes) {
            val catalog = runtime.declaredCatalogs.firstOrNull(::matches)
            if (catalog != null) return runtime to catalog
        }
        return null
    }

    private fun extrasOf(
        definition: CollectionSourceDefinition,
        catalog: DeclaredCatalog,
    ): List<Pair<String, String>>? {
        val sortBy = definition.sortBy?.trim()?.takeIf { it.isNotEmpty() }
        val sortHow = definition.sortHow?.trim()?.takeIf { it.isNotEmpty() }
        if (sortBy != null || sortHow != null) {

            val sortKey = catalog.extras.firstOrNull { it.equals(SORT_EXTRA, ignoreCase = true) }
                ?: return null
            if (sortBy == null) return null
            val value = if (sortHow == null) sortBy else "$sortBy.$sortHow"
            val extras = ArrayList<Pair<String, String>>(definition.filters.size + 1)
            definition.filters.forEach { (key, filterValue) ->
                if (!isAllowedExtra(key, catalog)) return null
                extras += key to filterValue
            }
            extras += sortKey to value
            return extras
        }
        if (definition.filters.isEmpty()) return emptyList()
        val extras = ArrayList<Pair<String, String>>(definition.filters.size)
        definition.filters.forEach { (key, value) ->
            if (!isAllowedExtra(key, catalog)) return null
            extras += key to value
        }
        return extras
    }

    private fun isAllowedExtra(key: String, catalog: DeclaredCatalog): Boolean =
        key.equals(SEARCH_EXTRA, ignoreCase = true) ||
            catalog.extras.any { it.equals(key, ignoreCase = true) }

    private fun StremioMetaPreview.toBrowseItem(
        catalogType: StremioContentType,
        addonId: String,
    ): CollectionBrowseItem? {
        val kind = when (type) {
            StremioContentType.MOVIE -> TitleKind.MOVIE
            StremioContentType.SERIES, StremioContentType.TV -> TitleKind.SERIES
            StremioContentType.CHANNEL -> when (catalogType) {
                StremioContentType.SERIES, StremioContentType.TV -> TitleKind.SERIES
                else -> TitleKind.MOVIE
            }

            StremioContentType.UNKNOWN -> return null
        }
        val typed = ExternalIdExtractor.extract(id)
        val tmdbId = typed.firstOrNull { it.authority == ExternalIdExtractor.AUTHORITY_TMDB }?.value?.toLongOrNull()
        val imdbId = typed.firstOrNull { it.authority == ExternalIdExtractor.AUTHORITY_IMDB }?.value
        return CollectionBrowseItem(
            providerItemId = id,
            kind = kind,
            title = name,
            year = yearOf(releaseInfo),
            tmdbId = tmdbId,
            imdbId = imdbId,

            artwork = addonArtworkRef(
                addonId = addonId,
                nativeItemId = id,
                posterAvailable = !poster.isNullOrBlank(),
                landscapeAvailable = background != null,
                posterIsLandscape = posterIsLandscape,
            ),
        )
    }

    private fun rowOwnerKey(definition: CollectionSourceDefinition, catalog: DeclaredCatalog): String =
        "collection-addon:${definition.addonId ?: "*"}:${catalog.type.wire}:${catalog.id}"

    private fun yearOf(releaseInfo: String?): Int? =
        releaseInfo?.trim()?.take(4)?.toIntOrNull()

    companion object {
        const val PROVIDER = "addon"

        internal const val UNSUPPORTED_PROVIDER = "not an add-on source"
        internal const val MISSING_CATALOG_ID = "add-on catalog id is missing"
        internal const val ADDON_UNAVAILABLE = "the source's add-on is not installed"
        internal const val CATALOG_UNAVAILABLE = "the add-on does not declare this catalog"
        internal const val QUERY_UNSUPPORTED = "an add-on filter or ordering cannot be preserved"
        internal const val ROW_FAILED = "add-on row failed"

        private const val SEARCH_EXTRA = "search"
        private const val SKIP_EXTRA = "skip"
        private const val SORT_EXTRA = "sort"
    }
}
