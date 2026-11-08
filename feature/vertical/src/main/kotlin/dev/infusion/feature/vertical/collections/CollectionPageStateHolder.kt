package dev.infusion.feature.vertical.collections

import dev.infusion.core.domain.collections.CollectionEntityKind
import dev.infusion.core.domain.collections.CollectionEntityPage
import dev.infusion.core.domain.collections.CollectionPageRowPreferences
import dev.infusion.core.domain.collections.CollectionPack
import dev.infusion.core.domain.collections.CollectionRow
import dev.infusion.core.domain.collections.CollectionRowOrdering
import dev.infusion.core.domain.collections.CollectionSourceDefinition
import dev.infusion.core.domain.collections.CollectionTile
import dev.infusion.core.domain.collections.CollectionTileShape
import dev.infusion.core.model.ArtworkLocator
import dev.infusion.feature.vertical.browse.appendShelfPage
import dev.infusion.source.aggregation.discover.DiscoverShelf
import dev.infusion.source.aggregation.discover.ShelfState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

data class CollectionPageRowUi(
    val rowKey: String,
    val title: String,

    val sourceLabel: String,

    val items: List<dev.infusion.source.aggregation.discover.DiscoverItem> = emptyList(),
    val hasMore: Boolean = false,
    val loading: Boolean = false,

    val failure: String? = null,

    val failureToken: Long = 0,

    val resolved: Boolean = false,
)

data class CollectionPageUiState(

    val title: String = "",

    val shape: CollectionTileShape = CollectionTileShape.LANDSCAPE,

    val backdropLocator: String? = null,
    val coverLocator: String? = null,
    val logoLocator: String? = null,

    val rows: List<CollectionPageRowUi> = emptyList(),

    val rowOrder: List<String> = emptyList(),

    val allRowsDisabled: Boolean = false,

    val missing: Boolean = false,

    val entityKind: CollectionEntityKind? = null,
)

class CollectionPageStateHolder(
    private val packFor: (profileId: String, packId: String) -> CollectionPack?,
    private val rowSource: CollectionRowSource,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,

    private val rowPreferencesFor: (
        profileId: String,
        packId: String,
        groupKey: String,
        tileKey: String,
    ) -> CollectionPageRowPreferences = { _, _, _, _ -> CollectionPageRowPreferences.None },

    private val preferencesChanges: Flow<Unit> = emptyFlow(),
) {
    private val _state = MutableStateFlow(CollectionPageUiState())
    val state: StateFlow<CollectionPageUiState> = _state.asStateFlow()

    val rowNavigationMemory: CollectionPageRowNavigationMemory = CollectionPageRowNavigationMemory()

    init {
        scope.launch(ioDispatcher) {
            preferencesChanges.collect { reapplyRowPreferences() }
        }
    }

    private val inFlight = HashMap<String, Job>()

    private var profileId: String = ""
    private var packId: String = ""
    private var groupKey: String = ""
    private var tileKey: String = ""

    private var openPageId: String? = null

    private var openTile: CollectionTile? = null

    fun open(profileId: String, pageId: String) {
        if (pageId == openPageId && this.profileId == profileId && !_state.value.missing) {
            scope.launch(ioDispatcher) { revalidate(profileId, pageId) }
            return
        }
        readPage(profileId, pageId)
    }

    private suspend fun revalidate(profileId: String, pageId: String) {
        if (pageId != openPageId || profileId != this.profileId) return
        val snapshot = openTile ?: return
        val current = packFor(profileId, packId)
            ?.groups?.firstOrNull { group -> group.groupKey == groupKey }
            ?.tiles?.firstOrNull { tile -> tile.tileKey == tileKey }
        if (current == snapshot) return
        openPageId = null
        readPage(profileId, pageId)
    }

    private fun readPage(profileId: String, pageId: String) {
        val decoded = CollectionPageId.decode(pageId)
        if (decoded == null) {
            openPageId = pageId
            openTile = null
            _state.value = CollectionPageUiState(missing = true)
            return
        }
        val (pack, group, tile) = decoded
        this.profileId = profileId
        this.packId = pack
        this.groupKey = group
        this.tileKey = tile
        openPageId = pageId
        synchronized(inFlight) { inFlight.values.forEach { it.cancel() }; inFlight.clear() }
        val packOrNull = packFor(profileId, pack)
        val groupOrNull = packOrNull?.groups?.firstOrNull { it.groupKey == group }
        val tileOrNull = groupOrNull?.tiles?.firstOrNull { it.tileKey == tile }
        openTile = tileOrNull
        if (packOrNull == null || tileOrNull == null) {
            _state.value = CollectionPageUiState(missing = true)
            return
        }
        val card = tileOrNull.toTileCard(pack, group)
        val preferences = rowPreferencesFor(profileId, pack, group, tile)
        val definedKeys = tileOrNull.rows.map { it.rowKey }
        val ordered = CollectionRowOrdering.orderedEntries(definedKeys, preferences)
        val rowsByKey = tileOrNull.rows.distinctBy { it.rowKey }.associateBy { it.rowKey }

        val effectiveRows = ordered.filter { it.enabled }.mapNotNull { rowsByKey[it.rowKey] }.map { it.toRowUi() }
        _state.value = CollectionPageUiState(
            title = card.title.ifBlank { packOrNull.name },
            shape = card.shape,
            backdropLocator = card.backdropLocator ?: card.coverLocator,
            coverLocator = card.coverLocator,
            logoLocator = card.logoLocator,

            rows = effectiveRows,
            rowOrder = ordered.map { it.rowKey },
            allRowsDisabled = definedKeys.isNotEmpty() && effectiveRows.isEmpty(),
            missing = false,

            entityKind = CollectionEntityPage.decodeToken(pack)?.ref?.kind,
        )
    }

    private fun reapplyRowPreferences() {
        if (openPageId == null || _state.value.missing) return
        val tile = openTile ?: return
        val preferences = rowPreferencesFor(profileId, packId, groupKey, tileKey)
        val definedKeys = tile.rows.map { it.rowKey }
        val ordered = CollectionRowOrdering.orderedEntries(definedKeys, preferences)
        val rowsByKey = tile.rows.distinctBy { it.rowKey }.associateBy { it.rowKey }
        val fresh = ordered.filter { it.enabled }.mapNotNull { rowsByKey[it.rowKey] }.map { it.toRowUi() }
        val effectiveKeys = fresh.map { it.rowKey }.toSet()
        synchronized(inFlight) {
            inFlight.keys.filterNot { it in effectiveKeys }.forEach { key ->
                inFlight.remove(key)?.cancel()
            }
        }
        val current = _state.value.rows.associateBy { it.rowKey }
        val merged = fresh.map { placeholder -> current[placeholder.rowKey] ?: placeholder }
        val rowOrder = ordered.map { it.rowKey }
        val allDisabled = definedKeys.isNotEmpty() && fresh.isEmpty()
        if (merged == _state.value.rows &&
            rowOrder == _state.value.rowOrder &&
            allDisabled == _state.value.allRowsDisabled
        ) {
            return
        }
        _state.value = _state.value.copy(
            rows = merged,
            rowOrder = rowOrder,
            allRowsDisabled = allDisabled,
        )
    }

    private fun CollectionRow.toRowUi(): CollectionPageRowUi = CollectionPageRowUi(
        rowKey = rowKey,
        title = title.ifBlank { definition.provider },
        sourceLabel = definition.provider,
    )

    fun ensureRow(rowKey: String) {
        val current = _state.value
        if (current.missing) return
        val row = current.rows.firstOrNull { it.rowKey == rowKey } ?: return
        if (row.loading || row.resolved) return
        synchronized(inFlight) {
            if (inFlight.containsKey(rowKey)) return
            launchRow(rowKey, first = true)
        }
    }

    fun loadMore(rowKey: String) {
        val row = _state.value.rows.firstOrNull { it.rowKey == rowKey } ?: return
        if (row.loading || !row.resolved || !row.hasMore) return
        synchronized(inFlight) {
            if (inFlight.containsKey(rowKey)) return
            launchRow(rowKey, first = false)
        }
    }

    private fun launchRow(rowKey: String, first: Boolean) {
        val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) { resolve(rowKey, first) }
        inFlight[rowKey] = job
        job.start()
    }

    private suspend fun resolve(rowKey: String, first: Boolean) {
        val pack = packFor(profileId, packId) ?: run {
            markMissing()
            return
        }
        val definition = pack.groups.firstOrNull { it.groupKey == groupKey }
            ?.tiles?.firstOrNull { it.tileKey == tileKey }
            ?.rows?.firstOrNull { it.rowKey == rowKey }
            ?: run {
                updateRow(rowKey) { it.copy(loading = false, failure = MISSING_ROW, failureToken = it.failureToken + 1) }
                return
            }
        updateRow(rowKey) { it.copy(loading = true, failure = null) }
        val title = definition.title
        try {
            val outcome = try {
                if (first) {
                    rowSource.shelf(rowKey, title.ifBlank { definition.definition.provider }, definition.definition)
                } else {
                    rowSource.page(rowKey, title.ifBlank { definition.definition.provider }, definition.definition)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                CollectionRowShelfOutcome.Failed(error.message?.take(80) ?: ROW_FAILED)
            }
            when (outcome) {
                is CollectionRowShelfOutcome.Failed ->

                    updateRow(rowKey) {
                        it.copy(
                            loading = false,
                            failure = outcome.reason,
                            resolved = true,
                            failureToken = it.failureToken + 1,
                        )
                    }
                is CollectionRowShelfOutcome.Loaded -> {
                    val shelfState = outcome.shelf.state
                    val loaded = shelfState as? ShelfState.Loaded
                    updateRow(rowKey) { existing ->

                        val appended = appendShelfPage(
                            loaded = existing.items,
                            page = loaded?.items.orEmpty(),
                            pageHasMore = loaded?.hasMore ?: false,
                        )
                        existing.copy(
                            items = appended.items,
                            hasMore = appended.hasMore,
                            loading = false,
                            failure = null,

                            failureToken = 0,
                            resolved = true,
                            sourceLabel = outcome.shelf.sourceLabel,
                        )
                    }

                    applyEnrichment(
                        rowKey = rowKey,
                        title = title.ifBlank { definition.definition.provider },
                        definition = definition.definition,
                        shelf = outcome.shelf,
                    )
                }
            }
        } finally {

            synchronized(inFlight) { inFlight.remove(rowKey) }
        }
    }

    private suspend fun applyEnrichment(
        rowKey: String,
        title: String,
        definition: CollectionSourceDefinition,
        shelf: DiscoverShelf,
    ) {
        val loaded = shelf.state as? ShelfState.Loaded ?: return
        if (loaded.items.none { it.preview.artwork == null && it.titleId != null }) return
        rowSource.enrich(rowKey, title, definition, shelf).collect { enriched ->
            val enrichedItems = (enriched.state as? ShelfState.Loaded)?.items ?: return@collect
            val byKey = enrichedItems.associateBy { it.key }
            updateRow(rowKey) { row ->
                row.copy(items = row.items.map { item -> byKey[item.key] ?: item })
            }
        }
    }

    private fun updateRow(rowKey: String, transform: (CollectionPageRowUi) -> CollectionPageRowUi) {
        _state.value = _state.value.copy(
            rows = _state.value.rows.map { row -> if (row.rowKey == rowKey) transform(row) else row },
        )
    }

    private fun markMissing() {
        _state.value = _state.value.copy(missing = true)
    }

    companion object {
        internal const val MISSING_ROW = "collection_row_missing"
        internal const val ROW_FAILED = "collection_row_failed"
    }
}
