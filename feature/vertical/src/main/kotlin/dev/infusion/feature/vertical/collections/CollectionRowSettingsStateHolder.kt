package dev.infusion.feature.vertical.collections

import dev.infusion.core.domain.collections.CollectionPageRowPreferences
import dev.infusion.core.domain.collections.CollectionPack
import dev.infusion.core.domain.collections.CollectionPackStore
import dev.infusion.core.domain.collections.CollectionRowOrdering
import dev.infusion.core.domain.collections.CollectionRowPreferenceEntry
import dev.infusion.core.domain.collections.CollectionRowPreferenceStore
import dev.infusion.core.domain.collections.CollectionTile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CollectionRowPackUi(val packId: String, val name: String)

data class CollectionRowGroupUi(val groupKey: String, val title: String)

data class CollectionRowTileUi(val tileKey: String, val title: String)

data class CollectionRowEntryUi(
    val rowKey: String,
    val title: String,

    val sourceLabel: String,
    val enabled: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

data class CollectionRowSettingsUiState(
    val packs: List<CollectionRowPackUi> = emptyList(),
    val groups: List<CollectionRowGroupUi> = emptyList(),
    val tiles: List<CollectionRowTileUi> = emptyList(),

    val rows: List<CollectionRowEntryUi> = emptyList(),
    val selectedPackId: String? = null,
    val selectedGroupKey: String? = null,
    val selectedTileKey: String? = null,

    val status: String? = null,
)

class CollectionRowSettingsStateHolder(
    private val packStore: CollectionPackStore,
    private val preferenceStore: CollectionRowPreferenceStore,
    private val activeProfileId: () -> String,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(CollectionRowSettingsUiState())
    val state: StateFlow<CollectionRowSettingsUiState> = _state.asStateFlow()

    init {
        refresh()

        scope.launch(ioDispatcher) {
            packStore.changes().collect {
                refreshNow()
                reloadRows()
            }
        }

        scope.launch(ioDispatcher) { preferenceStore.changes().collect { reloadRows() } }
    }

    fun refresh() {
        scope.launch(ioDispatcher) { refreshNow() }
    }

    private fun refreshNow() {
        val profileId = activeProfileId()
        val packs = packStore.read(profileId).packs.map { CollectionRowPackUi(it.packId, it.name) }
        val current = _state.value
        val selected = current.selectedPackId?.takeIf { id -> packs.any { it.packId == id } }
        _state.value = current.copy(
            packs = packs,
            selectedPackId = selected,
            groups = if (selected == null) emptyList() else current.groups,
            tiles = if (selected == null) emptyList() else current.tiles,
            rows = if (selected == null) emptyList() else current.rows,
            selectedGroupKey = if (selected == null) null else current.selectedGroupKey,
            selectedTileKey = if (selected == null) null else current.selectedTileKey,
        )
    }

    fun selectPack(packId: String) {
        scope.launch(ioDispatcher) {
            val profileId = activeProfileId()
            val pack = packStore.readPack(profileId, packId)
            _state.value = _state.value.copy(
                selectedPackId = packId,
                selectedGroupKey = null,
                selectedTileKey = null,
                groups = pack?.groups?.map { CollectionRowGroupUi(it.groupKey, it.title) }.orEmpty(),
                tiles = emptyList(),
                rows = emptyList(),
                status = null,
            )
        }
    }

    fun selectGroup(groupKey: String) {
        scope.launch(ioDispatcher) {
            val pack = selectedPack() ?: return@launch
            val group = pack.groups.firstOrNull { it.groupKey == groupKey } ?: return@launch
            _state.value = _state.value.copy(
                selectedGroupKey = groupKey,
                selectedTileKey = null,
                tiles = group.tiles.map { CollectionRowTileUi(it.tileKey, it.title.ifBlank { it.tileKey }) },
                rows = emptyList(),
                status = null,
            )
        }
    }

    fun selectTile(tileKey: String) {
        scope.launch(ioDispatcher) {
            _state.value = _state.value.copy(selectedTileKey = tileKey, rows = emptyList(), status = null)
            reloadRows()
        }
    }

    fun clearTile() {
        _state.value = _state.value.copy(selectedTileKey = null, rows = emptyList(), status = null)
    }

    fun clearGroup() {

        _state.value = _state.value.copy(
            selectedGroupKey = null,
            selectedTileKey = null,
            tiles = emptyList(),
            rows = emptyList(),
            status = null,
        )
    }

    fun clearPack() {
        _state.value = _state.value.copy(
            selectedPackId = null,
            selectedGroupKey = null,
            selectedTileKey = null,
            groups = emptyList(),
            tiles = emptyList(),
            rows = emptyList(),
            status = null,
        )
    }

    fun toggleRow(rowKey: String) {
        commit { entries ->
            val index = entries.indexOfFirst { it.rowKey == rowKey }
            if (index < 0) return@commit null
            entries.toMutableList().also { list ->
                list[index] = list[index].copy(enabled = !list[index].enabled)
            }
        }
    }

    fun moveRow(rowKey: String, delta: Int) {
        if (delta == 0) return
        commit { entries ->
            val index = entries.indexOfFirst { it.rowKey == rowKey }
            val target = index + delta
            if (index < 0 || target !in entries.indices) return@commit null
            entries.toMutableList().also { list ->
                val moved = list.removeAt(index)
                list.add(target, moved)
            }
        }
    }

    private fun reloadRows() {
        val profileId = activeProfileId()
        val current = _state.value
        val packId = current.selectedPackId ?: return
        val groupKey = current.selectedGroupKey ?: return
        val tileKey = current.selectedTileKey ?: return
        val tile = tileOf(profileId, packId, groupKey, tileKey) ?: run {
            _state.value = current.copy(rows = emptyList())
            return
        }
        _state.value = current.copy(rows = rowsOf(tile, preferenceFor(profileId, packId, groupKey, tileKey)))
    }

    private fun commit(change: (List<CollectionRowPreferenceEntry>) -> List<CollectionRowPreferenceEntry>?) {
        scope.launch(ioDispatcher) {
            val profileId = activeProfileId()
            val current = _state.value
            val packId = current.selectedPackId ?: return@launch
            val groupKey = current.selectedGroupKey ?: return@launch
            val tileKey = current.selectedTileKey ?: return@launch
            val tile = tileOf(profileId, packId, groupKey, tileKey) ?: return@launch
            val preferences = preferenceFor(profileId, packId, groupKey, tileKey)
            val defined = CollectionRowOrdering.orderedEntries(tile.rows.map { it.rowKey }, preferences)
            val changed = change(defined) ?: return@launch
            val orphans = preferences.entries.filter { entry -> tile.rows.none { it.rowKey == entry.rowKey } }
            val ok = preferenceStore.writePage(profileId, packId, groupKey, tileKey, changed + orphans)
            if (!ok) {
                _state.value = _state.value.copy(
                    status = "Could not save this page's rows (the per-page limit is reached).",
                )
                return@launch
            }
            _state.value = _state.value.copy(status = null, rows = rowsOf(tile, CollectionPageRowPreferences(changed)))
        }
    }

    private fun rowsOf(tile: CollectionTile, preferences: CollectionPageRowPreferences): List<CollectionRowEntryUi> {
        val ordered = CollectionRowOrdering.orderedEntries(tile.rows.map { it.rowKey }, preferences)
        val byKey = tile.rows.distinctBy { it.rowKey }.associateBy { it.rowKey }
        return ordered.mapIndexedNotNull { index, entry ->
            val row = byKey[entry.rowKey] ?: return@mapIndexedNotNull null
            CollectionRowEntryUi(
                rowKey = entry.rowKey,
                title = row.title.ifBlank { row.definition.provider },
                sourceLabel = row.definition.provider,
                enabled = entry.enabled,
                canMoveUp = index > 0,
                canMoveDown = index < ordered.lastIndex,
            )
        }
    }

    private fun selectedPack(): CollectionPack? =
        _state.value.selectedPackId?.let { packStore.readPack(activeProfileId(), it) }

    private fun tileOf(profileId: String, packId: String, groupKey: String, tileKey: String): CollectionTile? =
        packStore.readPack(profileId, packId)
            ?.groups?.firstOrNull { it.groupKey == groupKey }
            ?.tiles?.firstOrNull { it.tileKey == tileKey }

    private fun preferenceFor(
        profileId: String,
        packId: String,
        groupKey: String,
        tileKey: String,
    ): CollectionPageRowPreferences = preferenceStore.readPage(profileId, packId, groupKey, tileKey)
}
