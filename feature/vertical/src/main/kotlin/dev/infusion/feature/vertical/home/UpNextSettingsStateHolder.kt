package dev.infusion.feature.vertical.home

import dev.infusion.core.domain.upnext.UpNextPresentationStore
import dev.infusion.core.domain.upnext.UpNextSelection
import dev.infusion.core.domain.upnext.UpNextSelectionStore
import dev.infusion.core.domain.upnext.UpNextSource
import dev.infusion.core.domain.upnext.UpNextSourceResolution
import dev.infusion.core.domain.upnext.UpNextSourceRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UpNextSettingsStateHolder(
    private val selectionStore: UpNextSelectionStore,
    private val presentationStore: UpNextPresentationStore,
    private val scope: CoroutineScope,
    private val activeProfileId: () -> String,

    private val availableTrackers: () -> List<UpNextTrackerRef>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _state = MutableStateFlow(UpNextSettingsUiState())
    val state: StateFlow<UpNextSettingsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch {
            val profileId = withContext(ioDispatcher) { runCatching { activeProfileId() }.getOrNull() }
            if (profileId.isNullOrBlank()) return@launch
            val trackers = withContext(ioDispatcher) { runCatching { availableTrackers() }.getOrDefault(emptyList()) }
            val recorded = withContext(ioDispatcher) {
                runCatching { selectionStore.read(profileId) }.getOrDefault(UpNextSelection.Unset)
            }
            val show = withContext(ioDispatcher) {
                runCatching { presentationStore.showUpNext(profileId) }.getOrDefault(true)
            }
            val resolution = UpNextSourceRule.resolve(recorded, trackers.map { it.trackerId })
            _state.value = UpNextSettingsUiState(
                showUpNext = show,
                options = optionsFor(resolution, trackers),
                selection = recorded,
                effective = resolution.effective,
                unavailableTrackerId = resolution.unavailableTrackerId,
            )
        }
    }

    fun setShowUpNext(show: Boolean) {
        if (_state.value.showUpNext == show) return
        scope.launch {
            val profileId = runCatching { activeProfileId() }.getOrNull() ?: return@launch
            withContext(ioDispatcher) { runCatching { presentationStore.setShowUpNext(profileId, show) } }
            refresh()
        }
    }

    fun select(source: UpNextSource) {
        scope.launch {
            val profileId = runCatching { activeProfileId() }.getOrNull() ?: return@launch
            withContext(ioDispatcher) {
                runCatching { selectionStore.write(profileId, UpNextSelection(source)) }
            }
            refresh()
        }
    }

    private fun optionsFor(
        resolution: UpNextSourceResolution,
        trackers: List<UpNextTrackerRef>,
    ): List<UpNextSourceOption> = buildList {
        add(UpNextSourceOption(UpNextSource.LocalOnly, LOCAL_ONLY_LABEL, available = true))
        trackers.forEach { tracker ->
            add(
                UpNextSourceOption(
                    source = UpNextSource.Tracker(tracker.trackerId),
                    label = tracker.displayName,
                    available = true,
                ),
            )
        }

        resolution.unavailableTrackerId?.let { trackerId ->
            add(
                UpNextSourceOption(
                    source = UpNextSource.Tracker(trackerId),
                    label = trackerId,
                    available = false,
                ),
            )
        }
    }

    private companion object {
        const val LOCAL_ONLY_LABEL = "Local only"
    }
}

data class UpNextSourceOption(
    val source: UpNextSource,
    val label: String,

    val available: Boolean,
)

data class UpNextSettingsUiState(

    val showUpNext: Boolean = true,

    val options: List<UpNextSourceOption> = emptyList(),

    val selection: UpNextSelection = UpNextSelection.Unset,

    val effective: UpNextSource = UpNextSource.LocalOnly,

    val unavailableTrackerId: String? = null,
) {

    val selectedOption: UpNextSourceOption?
        get() = options.firstOrNull { it.source == effective } ?: options.firstOrNull()

    val selectionUnavailable: Boolean get() = unavailableTrackerId != null
}
