package dev.infusion.source.aggregation.discover

import dev.infusion.core.domain.catalog.ShelfStageMetrics
import dev.infusion.core.domain.catalog.ShelfStageRecord
import dev.infusion.core.model.Availability
import dev.infusion.core.model.AvailabilityId
import dev.infusion.core.model.AvailabilityKind
import dev.infusion.core.model.SourceId
import dev.infusion.core.model.TitleKind
import dev.infusion.source.aggregation.AddonAvailabilityStore
import dev.infusion.source.aggregation.AddonRegistryPort
import dev.infusion.source.aggregation.AddonRuntimeDescriptor
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
import dev.infusion.source.stremio.wire.StremioMetaPreview
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class DiscoverService(
    private val registry: AddonRegistryPort,
    private val catalogRepository: AddonCatalogRepository,
    private val matcher: CanonicalMatcher,
    private val availabilityStore: AddonAvailabilityStore,

    private val artworkRepository: AddonArtworkRepository,

    private val providerTimeoutMs: Long = 10_000,
    private val now: () -> Instant = Instant::now,

    private val stageMetrics: ShelfStageMetrics = ShelfStageMetrics.None,
) {

    private val fetchedPages = LinkedHashMap<String, FetchedPage>()

    private val fetchedPagesLock = Any()

    suspend fun shelves(
        viewer: dev.infusion.core.model.profile.Profile,
        primary: dev.infusion.core.model.profile.Profile,
        plan: DiscoverCatalogPlan = DiscoverCatalogPlan.Declared,
    ): List<DiscoverShelf> = shelfStream(viewer, primary, plan)
        .toList()
        .sortedBy { it.order }
        .map { it.shelf }

    fun shelfStream(
        viewer: dev.infusion.core.model.profile.Profile,
        primary: dev.infusion.core.model.profile.Profile,
        plan: DiscoverCatalogPlan = DiscoverCatalogPlan.Declared,
    ): Flow<DiscoverShelfEmission> = channelFlow {
        val streamStartNanos = System.nanoTime()

        val resolveStartNanos = System.nanoTime()
        val runtimes = registry.runtimesFor(viewer, primary)
            .map { runtime -> runtime to invocableCatalogs(runtime) }
        stageMetrics.measured(
            ShelfStageRecord(
                phase = ShelfStageRecord.Phase.RESOLVE,
                runtimes = runtimes.size,
                catalogs = runtimes.sumOf { (_, catalogs) -> catalogs.size },

                planned = runtimes.sumOf { (runtime, catalogs) ->
                    catalogs.count { catalog -> plan.executes(shelfKeyOf(runtime, catalog)) }
                },
                resolveMs = elapsedMs(resolveStartNanos),
            ),
        )

        var ordinal = 0
        val declared = runtimes.map { (runtime, catalogs) ->
            runtime to catalogs.map { catalog -> ordinal++ to catalog }
        }
        declared.forEach { (runtime, catalogs) ->

            val executed = catalogs.filter { (_, catalog) ->
                plan.executes(shelfKeyOf(runtime, catalog))
            }
            if (executed.isEmpty()) return@forEach

            launch {
                executed.forEach { (order, catalog) ->

                    buildShelf(runtime, catalog, order, streamStartNanos) { row ->
                        send(DiscoverShelfEmission(order, row))
                    }
                }
            }
        }
    }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    private class ProjectionTiming {
        var matchNanos: Long = 0L
        var availabilityNanos: Long = 0L
    }

    private suspend fun buildShelf(
        runtime: AddonRuntimeDescriptor,
        catalog: DeclaredCatalog,
        ordinal: Int = -1,
        streamStartNanos: Long = -1L,
        publish: suspend (DiscoverShelf) -> Unit = {},
    ): DiscoverShelf {
        val key = shelfKeyOf(runtime, catalog)
        val buildStartNanos = System.nanoTime()

        stageMetrics.measured(
            ShelfStageRecord(
                phase = ShelfStageRecord.Phase.REQUEST,
                ordinal = ordinal,
                streamMs = if (streamStartNanos > 0) elapsedMs(streamStartNanos) else 0L,
            ),
        )
        val result = withTimeoutOrNull(providerTimeoutMs) {
            catalogRepository.catalog(runtime.addonId, catalog.type, catalog.id)
        }
        val fetchMs = elapsedMs(buildStartNanos)
        var fetched = 0
        var admittedCount = 0
        var projectMs = 0L
        var matchMs = 0L
        var availabilityMs = 0L
        val projection = ProjectionTiming()
        var projectStartNanos = 0L

        val statements = ArrayList<Availability>()
        val shelf = when (result) {
            null -> failedShelf(key, runtime, catalog, "timeout")
            is StremioResult.Err -> failedShelf(key, runtime, catalog, redactFailure(result.failure))
            is StremioResult.Ok -> {

                val page = result.value.metas
                fetched = page.size
                projectStartNanos = System.nanoTime()
                val admitted = synchronized(fetchedPagesLock) {
                    val ownership = fetchedPages.getOrPut(key) { FetchedPage() }
                    ownership.ownFirstPage(page)
                    ownership.admitNext()
                }
                ownFetchedPage(runtime, key, page, replacesRowOwnership = true)
                val items = admitted.map { preview ->
                    projectItem(runtime, catalog, preview, projection, statements)
                }
                admittedCount = items.size
                DiscoverShelf(
                    key = key,
                    title = catalog.name,
                    sourceLabel = runtime.displayName,
                    state = ShelfState.Loaded(items, hasMore = items.isNotEmpty()),
                )
            }
        }

        publish(shelf)
        val availabilityStartNanos = System.nanoTime()
        if (statements.isNotEmpty()) availabilityStore.upsertAll(statements)
        availabilityMs = elapsedMs(availabilityStartNanos)
        matchMs = projection.matchNanos / 1_000_000
        projectMs = if (projectStartNanos > 0) elapsedMs(projectStartNanos) else 0L

        stageMetrics.measured(
            ShelfStageRecord(
                phase = ShelfStageRecord.Phase.SHELF,
                ordinal = ordinal,
                fetchMs = fetchMs,
                projectMs = projectMs,
                buildMs = elapsedMs(buildStartNanos),
                streamMs = if (streamStartNanos > 0) elapsedMs(streamStartNanos) else 0L,
                fetched = fetched,
                admitted = admittedCount,
                matchMs = matchMs,
                availabilityMs = availabilityMs,
                outcome = when (result) {
                    null -> "timeout"
                    is StremioResult.Err -> "error"
                    else -> "loaded"
                },
            ),
        )
        return shelf
    }

    suspend fun shelfPage(
        viewer: dev.infusion.core.model.profile.Profile,
        primary: dev.infusion.core.model.profile.Profile,
        shelfKey: String,
        skip: Int,
    ): DiscoverShelf? {
        val (runtime, catalog) = resolveCatalog(viewer, primary, shelfKey) ?: return null
        val key = shelfKeyOf(runtime, catalog)

        val buffered = synchronized(fetchedPagesLock) {
            fetchedPages[key]?.let { owned ->
                owned.alignToPresented(skip)
                owned.takeIf { it.hasUnadmitted }?.admitNext()
            }
        }
        if (buffered != null) {
            val items = buffered.map { preview -> projectItem(runtime, catalog, preview) }
            return DiscoverShelf(
                key = key,
                title = catalog.name,
                sourceLabel = runtime.displayName,
                state = ShelfState.Loaded(items, hasMore = items.isNotEmpty()),
            )
        }

        val cursor = synchronized(fetchedPagesLock) { fetchedPages[key]?.fetched }
            ?.coerceAtLeast(skip) ?: skip
        return when (
            val result = withTimeoutOrNull(providerTimeoutMs) {
                catalogRepository.catalog(runtime.addonId, catalog.type, catalog.id, skip = cursor)
            }
        ) {
            null -> failedShelf(key, runtime, catalog, "timeout")
            is StremioResult.Err -> failedShelf(key, runtime, catalog, redactFailure(result.failure))
            is StremioResult.Ok -> {
                val page = result.value.metas
                val admitted = synchronized(fetchedPagesLock) {
                    val ownership = fetchedPages.getOrPut(key) {

                        FetchedPage().also { it.adoptFetchedCursor(skip) }
                    }
                    ownership.ownNextPage(page)
                    ownership.admitNext()
                }

                ownFetchedPage(runtime, key, page, replacesRowOwnership = false)
                val items = admitted.map { preview -> projectItem(runtime, catalog, preview) }
                DiscoverShelf(
                    key = key,
                    title = catalog.name,
                    sourceLabel = runtime.displayName,
                    state = ShelfState.Loaded(items, hasMore = items.isNotEmpty()),
                )
            }
        }
    }

    private fun resolveCatalog(
        viewer: dev.infusion.core.model.profile.Profile,
        primary: dev.infusion.core.model.profile.Profile,
        shelfKey: String,
    ): Pair<AddonRuntimeDescriptor, DeclaredCatalog>? {
        for (runtime in registry.runtimesFor(viewer, primary)) {
            for (catalog in invocableCatalogs(runtime)) {
                if (shelfKeyOf(runtime, catalog) == shelfKey) return runtime to catalog
            }
        }
        return null
    }

    private class FetchedPage {

        private var pending: List<StremioMetaPreview> = emptyList()

        var fetched: Int = 0
            private set

        val hasUnadmitted: Boolean get() = pending.isNotEmpty()

        fun ownFirstPage(page: List<StremioMetaPreview>) {
            pending = page
            fetched = page.size
        }

        fun ownNextPage(page: List<StremioMetaPreview>) {
            pending = pending + page
            fetched += page.size
        }

        fun adoptFetchedCursor(cursor: Int) {
            fetched = cursor
        }

        fun alignToPresented(presented: Int) {
            val alreadyAdmitted = fetched - pending.size
            val alreadyPresented = presented - alreadyAdmitted
            if (alreadyPresented <= 0) return
            pending = pending.drop(alreadyPresented.coerceAtMost(pending.size))
        }

        fun admitNext(): List<StremioMetaPreview> {
            val count = DiscoverAdmissionWindow.admit(pending.size)
            val window = pending.subList(0, count)
            pending = pending.drop(count)
            return window
        }
    }

    private fun ownFetchedPage(
        runtime: AddonRuntimeDescriptor,
        key: String,
        page: List<StremioMetaPreview>,
        replacesRowOwnership: Boolean,
    ) {
        val artwork = page.map { preview ->
            AddonFetchedItemArtwork(
                nativeItemId = preview.id,
                posterUrl = preview.poster,
                landscapeUrl = preview.background ?: preview.poster.takeIf { preview.posterIsLandscape },
            )
        }
        if (replacesRowOwnership) artworkRepository.releaseFetchedPage(runtime.addonId, key)
        artworkRepository.ownFetchedPage(runtime.addonId, key, artwork)
    }

    private fun failedShelf(
        key: String,
        runtime: AddonRuntimeDescriptor,
        catalog: DeclaredCatalog,
        reason: String,
    ): DiscoverShelf = DiscoverShelf(
        key = key,
        title = catalog.name,
        sourceLabel = runtime.displayName,
        state = ShelfState.Failed(reason),
    )

    fun declaredShelfKeys(
        viewer: dev.infusion.core.model.profile.Profile,
        primary: dev.infusion.core.model.profile.Profile,
    ): List<String> =
        declaredShelfDeclarations(viewer, primary).map { declaration -> declaration.key }

    fun declaredShelfDeclarations(
        viewer: dev.infusion.core.model.profile.Profile,
        primary: dev.infusion.core.model.profile.Profile,
    ): List<DiscoverShelfDeclaration> =
        registry.runtimesFor(viewer, primary).flatMap { runtime ->
            invocableCatalogs(runtime).map { catalog ->
                DiscoverShelfDeclaration(
                    key = shelfKeyOf(runtime, catalog),
                    title = catalog.name,
                    sourceLabel = runtime.displayName,
                )
            }
        }

    suspend fun shelfFirstPage(
        viewer: dev.infusion.core.model.profile.Profile,
        primary: dev.infusion.core.model.profile.Profile,
        shelfKey: String,
    ): DiscoverShelf? {
        val (runtime, catalog) = resolveCatalog(viewer, primary, shelfKey) ?: return null
        return buildShelf(runtime, catalog)
    }

    private fun invocableCatalogs(runtime: AddonRuntimeDescriptor): List<DeclaredCatalog> =
        runtime.declaredCatalogs.filter { it.type != StremioContentType.UNKNOWN }

    private fun shelfKeyOf(runtime: AddonRuntimeDescriptor, catalog: DeclaredCatalog): String =
        shelfKey(runtime.addonId, catalog.type.wire, catalog.id)

    private fun itemKeyOf(
        runtime: AddonRuntimeDescriptor,
        catalog: DeclaredCatalog,
        nativeItemId: String,
    ): String = "${shelfKeyOf(runtime, catalog)}:item:$nativeItemId"

    private fun projectItem(
        runtime: AddonRuntimeDescriptor,
        catalog: DeclaredCatalog,
        preview: StremioMetaPreview,
        timing: ProjectionTiming? = null,

        statements: MutableList<Availability>? = null,
    ): DiscoverItem {
        val item = AddonMetaItemAdapter.fromPreview(preview, catalog.type, catalog.id, runtime.sourceId)
        val matchStartNanos = System.nanoTime()
        val match = matcher.match(item, addonAliasAuthority(runtime))
        timing?.let { it.matchNanos += System.nanoTime() - matchStartNanos }
        val statement = availabilityStatement(runtime, item, match)
        if (statement != null) {
            if (statements != null) {
                statements += statement
            } else {
                val availabilityStartNanos = System.nanoTime()
                availabilityStore.upsert(statement)
                timing?.let { it.availabilityNanos += System.nanoTime() - availabilityStartNanos }
            }
        }

        val kind = titleKindOf(item.catalogType)
        val mappedTitleId = (match as? AddonMatch.Matched)?.titleId
        return DiscoverItem(
            key = itemKeyOf(runtime, catalog, item.nativeItemId),
            titleId = mappedTitleId,
            item = item,
            kind = kind,
            match = match,
            preview = addonMediaPreview(
                item = item,
                titleId = mappedTitleId,
                kind = kind,
                addonId = runtime.addonId,

                posterAvailable = !preview.poster.isNullOrBlank(),
                landscapeAvailable = preview.background != null,

                posterIsLandscape = preview.posterIsLandscape,
            ),
        )
    }

    private fun availabilityStatement(
        runtime: AddonRuntimeDescriptor,
        item: AddonMetaItem,
        match: AddonMatch,
    ): Availability? {
        val matched = match as? AddonMatch.Matched ?: return null
        return Availability(
            availabilityId = AvailabilityId(
                UUID.nameUUIDFromBytes("addon:${runtime.addonId}:${item.nativeItemId}".toByteArray()),
            ),
            titleId = matched.titleId,
            editionId = null,
            sourceId = runtime.sourceId,
            nativeResourceId = item.nativeItemId,
            kind = AvailabilityKind.ADDON,
            observedAt = now(),
            provenance = dev.infusion.core.model.Provenance(
                sourceId = runtime.sourceId,
                protocolVersion = "stremio/1",
                retrievedAt = now(),
                normalizedBy = "DiscoverService",
            ),
        )
    }

    private fun redactFailure(failure: dev.infusion.source.stremio.StremioFailure): String =
        "${failure.reason.name.lowercase()}: ${failure.message}"

    private fun addonAliasAuthority(runtime: AddonRuntimeDescriptor): String =
        "addon:${runtime.sourceId.value}"

    private fun titleKindOf(type: StremioContentType): TitleKind? = when (type) {
        StremioContentType.MOVIE -> TitleKind.MOVIE
        StremioContentType.SERIES, StremioContentType.TV -> TitleKind.SERIES
        StremioContentType.CHANNEL -> TitleKind.CLIP

        StremioContentType.ALL, StremioContentType.UNKNOWN -> null
    }

    companion object {

        fun shelfKey(addonId: String, catalogType: String, catalogId: String): String =
            "addon:$addonId:catalog:$catalogType:$catalogId"
    }
}
