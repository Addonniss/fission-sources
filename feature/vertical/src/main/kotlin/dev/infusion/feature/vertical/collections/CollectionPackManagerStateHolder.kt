package dev.infusion.feature.vertical.collections

import dev.infusion.core.domain.collections.CollectionImporter
import dev.infusion.core.domain.collections.CollectionImportOutcome
import dev.infusion.core.domain.collections.CollectionPack
import dev.infusion.core.domain.collections.CollectionPackId
import dev.infusion.core.domain.collections.CollectionPackLimits
import dev.infusion.core.domain.collections.CollectionPackName
import dev.infusion.core.domain.collections.CollectionPackStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CollectionDocumentFetch {
    data class Fetched(val text: String) : CollectionDocumentFetch
    data class Failed(val reason: String) : CollectionDocumentFetch
}

data class CollectionPackUi(
    val packId: String,
    val name: String,

    val sourceUrl: String?,
    val groupCount: Int,
    val tileCount: Int,

    val placementCount: Int,
)

data class CollectionPackManagerUiState(
    val packs: List<CollectionPackUi> = emptyList(),

    val urlText: String = "",

    val busy: Boolean = false,

    val status: String? = null,
)

class CollectionPackManagerStateHolder(
    private val store: CollectionPackStore,
    private val fetch: suspend (url: String) -> CollectionDocumentFetch,
    private val placementsFor: (profileId: String, packId: String) -> Int,
    private val activeProfileId: () -> String,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(CollectionPackManagerUiState())
    val state: StateFlow<CollectionPackManagerUiState> = _state.asStateFlow()

    init {
        refresh()

        scope.launch(ioDispatcher) {
            store.changes().collect { refresh() }
        }
    }

    fun refresh() {
        scope.launch(ioDispatcher) {
            val profileId = activeProfileId()
            val packs = store.read(profileId).packs.map { it.toUi(profileId) }
            _state.value = _state.value.copy(packs = packs)
        }
    }

    fun setUrl(text: String) {
        val current = _state.value
        if (current.urlText == text) return
        _state.value = current.copy(urlText = text, status = null)
    }

    fun submitUrl() {
        val current = _state.value
        if (current.busy) return
        val url = current.urlText.trim()
        if (url.isEmpty()) {
            _state.value = current.copy(status = "Enter a collection-pack URL.")
            return
        }
        importFromUrl(url, forcedPackId = null)
    }

    fun reimport(packId: String) {
        val current = _state.value
        if (current.busy) return
        val url = current.packs.firstOrNull { it.packId == packId }?.sourceUrl
        if (url.isNullOrBlank()) {
            _state.value = current.copy(status = "This pack has no recorded source to re-import from.")
            return
        }
        importFromUrl(url, forcedPackId = packId)
    }

    fun deletePack(packId: String) {
        val profileId = activeProfileId()
        scope.launch(ioDispatcher) {
            val existing = store.read(profileId)
            if (existing.packs.none { it.packId == packId }) {
                _state.value = _state.value.copy(status = "That pack is no longer imported.")
                return@launch
            }
            val placements = placementsFor(profileId, packId)
            store.write(profileId, existing.packs.filterNot { it.packId == packId })
            _state.value = _state.value.copy(
                status = if (placements > 0) {
                    "Pack removed. $placements placed widget(s) now show as unavailable."
                } else {
                    "Pack removed."
                },
            )
        }
    }

    private fun importFromUrl(url: String, forcedPackId: String?) {
        _state.value = _state.value.copy(busy = true, status = "Fetching ${redact(url)}…")
        scope.launch(ioDispatcher) {
            val fetched = try {
                fetch(url)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                CollectionDocumentFetch.Failed(error.message?.take(80) ?: "import failed")
            }
            when (fetched) {
                is CollectionDocumentFetch.Failed ->
                    _state.value = _state.value.copy(busy = false, status = "Import failed: ${fetched.reason}")
                is CollectionDocumentFetch.Fetched -> commit(url, forcedPackId, fetched.text)
            }
        }
    }

    private fun commit(url: String, forcedPackId: String?, text: String) {
        val profileId = activeProfileId()
        when (val outcome = CollectionImporter.parse(text)) {
            is CollectionImportOutcome.Invalid ->

                _state.value = _state.value.copy(busy = false, status = "Import failed: ${outcome.reason}")
            is CollectionImportOutcome.NoUsableSource ->
                _state.value = _state.value.copy(
                    busy = false,
                    status = "Nothing imported: no source this build can read (${outcome.unsupportedSources} unsupported).",
                )
            is CollectionImportOutcome.Parsed -> {
                val document = outcome.document
                val existing = store.read(profileId)
                val packId = forcedPackId ?: CollectionPackId.forSourceUrl(url)
                val previous = existing.packs.firstOrNull { it.packId == packId }

                val packName = document.name ?: CollectionPackName.fromSourceUrl(url)
                if (previous == null && existing.packs.size >= CollectionPackLimits.MAX_PACKS_PER_PROFILE) {
                    _state.value = _state.value.copy(
                        busy = false,
                        status = "Import failed: the profile's pack limit (${CollectionPackLimits.MAX_PACKS_PER_PROFILE}) is reached.",
                    )
                    return
                }
                val pack = CollectionPack(

                    packId = packId,
                    name = packName,
                    provenance = dev.infusion.core.domain.collections.CollectionPackProvenance(
                        sourceUrl = url.trim().take(CollectionPackLimits.MAX_PROVENANCE_URL_LENGTH),
                        importedAtMillis = System.currentTimeMillis(),
                    ),
                    groups = document.groups,
                )

                val updated = if (previous == null) existing.packs + pack else {
                    existing.packs.map { if (it.packId == packId) pack else it }
                }
                store.write(profileId, updated)
                val unsupportedNote = if (document.unsupportedSources > 0) {
                    " (${document.unsupportedSources} unsupported source(s) kept)"
                } else {
                    ""
                }
                _state.value = _state.value.copy(
                    busy = false,
                    urlText = "",
                    status = if (previous == null) {
                        "Imported “$packName”: ${document.groups.size} group(s), ${document.tileCount} tile(s)$unsupportedNote."
                    } else {
                        "Updated “$packName”: ${document.groups.size} group(s), ${document.tileCount} tile(s)$unsupportedNote."
                    },
                )
            }
        }
    }

    private fun CollectionPack.toUi(profileId: String): CollectionPackUi = CollectionPackUi(
        packId = packId,
        name = name,
        sourceUrl = provenance.sourceUrl,
        groupCount = groups.size,
        tileCount = groups.sumOf { it.tiles.size },
        placementCount = placementsFor(profileId, packId),
    )

    private fun redact(url: String): String = url.substringBefore('?').take(120)
}
