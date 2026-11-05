package dev.infusion.feature.vertical.sourcepicker

import dev.infusion.core.domain.candidate.CandidateResolverPort
import dev.infusion.core.domain.candidate.ResolutionPhase
import dev.infusion.core.model.EditionId
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.TitleId
import dev.infusion.feature.vertical.model.PickerResolution
import dev.infusion.feature.vertical.model.PickerRow
import dev.infusion.feature.vertical.model.PickerRowState
import dev.infusion.feature.vertical.model.PickerTab
import dev.infusion.feature.vertical.model.SelectionPolicy
import dev.infusion.feature.vertical.model.SelectionResult
import dev.infusion.feature.vertical.model.SourcePickerUiState
import dev.infusion.source.aggregation.resolve.ProviderResolutionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SourcePickerStateHolder(
    private val titleId: TitleId,
    private val editionId: EditionId?,
    private val resolver: CandidateResolverPort,
    private val scope: CoroutineScope,
    private val autoplayWindowMs: Long = 5_000,

    providerStates: StateFlow<Map<String, ProviderResolutionState>>? = null,

    private val onSelectionPersisted: ((PlayableCandidate) -> Unit)? = null,
) {

    private val _state = MutableStateFlow(
        SourcePickerUiState(titleId = titleId, editionId = editionId, focusedKey = CANCEL_KEY),
    )
    val state: StateFlow<SourcePickerUiState> = _state.asStateFlow()

    private var resolveJob: Job? = null
    private var autoplayJob: Job? = null
    private var userMovedFocus = false
    private var rows: List<PickerRow> = emptyList()
    private var resolutionFailed: PickerResolution.Failed? = null

    private var autoplayCommitted = false

    private val rememberedRowByTab = LinkedHashMap<String, String>()

    private var resolutionComplete = false

    init {
        if (providerStates != null) {
            scope.launch {
                providerStates.collect { states ->
                    _state.value = _state.value.copy(
                        providerStates = states.mapValues { (_, s) ->
                            when (s) {
                                is ProviderResolutionState.Pending -> "pending"
                                is ProviderResolutionState.Resolved -> "resolved:${s.candidateCount}"
                                is ProviderResolutionState.Failed -> "failed:${s.reason}"
                            }
                        },
                    )
                }
            }
        }
        startResolution()
    }

    companion object {
        const val CANCEL_KEY = "cancel"

        private const val PROVIDER_TAB_PREFIX = "tab:provider:"
    }

    fun startResolution() {
        resolveJob?.cancel()
        autoplayJob?.cancel()
        resolutionComplete = false
        autoplayCommitted = false

        resolveJob = scope.launch {
            _state.value = _state.value.copy(
                resolution = PickerResolution.Loading,

                discoveryStarted = true,
                focusedKey = CANCEL_KEY,
                attemptedKeys = emptyList(),
                tabs = emptyList(),
                activeTabKey = PickerTab.ALL_KEY,
                visibleRows = emptyList(),
            )
            resolver.resolve(titleId, editionId).collect { phase ->
                when (phase) {
                    is ResolutionPhase.Loading -> {
                        _state.value = _state.value.copy(resolution = PickerResolution.Loading)
                    }
                    is ResolutionPhase.Candidates -> {

                        rows = relabelProviderInstances(mergeRows(rows, phase.candidates, phase.complete))
                        resolutionComplete = phase.complete

                        val tabs = deriveTabs(rows, allEnabled = resolutionComplete)
                        val activeTab = resolveActiveTab(tabs, _state.value.activeTabKey)
                        val visible = visibleRows(rows, activeTab)
                        updateFocusedKeyAfterArrival(visible)
                        _state.value = _state.value.copy(
                            resolution = PickerResolution.Resolved(rows, phase.complete),

                            notices = phase.notices,
                            tabs = tabs,
                            activeTabKey = activeTab,
                            visibleRows = visible,
                        )

                        maybeOpenAutoplayWindow()
                    }
                    is ResolutionPhase.Failed -> {
                        resolutionFailed = PickerResolution.Failed(phase.failure)
                        resolutionComplete = false
                        _state.value = _state.value.copy(
                            resolution = PickerResolution.Failed(phase.failure),
                            focusedKey = CANCEL_KEY,
                            tabs = emptyList(),
                            visibleRows = emptyList(),
                        )
                    }
                }
            }
        }
    }

    fun cancel() {
        resolveJob?.cancel()
        autoplayJob?.cancel()
        resolutionComplete = false
        autoplayCommitted = false
        _state.value = _state.value.copy(
            resolution = PickerResolution.Loading,

            discoveryStarted = false,
            autoplayCountdownMs = null,
            focusedKey = CANCEL_KEY,
            tabs = emptyList(),
            activeTabKey = PickerTab.ALL_KEY,
            visibleRows = emptyList(),
        )
    }

    fun onUserFocusMoved(key: String) {
        val current = _state.value
        val tab = current.tabs.firstOrNull { it.key == key }

        if (tab != null && !tab.enabled) return
        if (key == current.focusedKey && tab == null) return
        userMovedFocus = true
        val activeTab = tab?.key ?: current.activeTabKey
        val visible = visibleRows(rows, activeTab)

        if (visible.any { it.key == key }) rememberedRowByTab[activeTab] = key
        _state.value = current.copy(
            focusedKey = key,
            activeTabKey = activeTab,
            visibleRows = visible,
        )
    }

    fun focusTabStrip() {
        val current = _state.value
        if (current.tabs.isEmpty()) return
        val active = current.tabs.firstOrNull { it.key == current.activeTabKey && it.enabled }
            ?: current.tabs.firstOrNull { it.enabled }
            ?: return
        userMovedFocus = true
        _state.value = current.copy(focusedKey = active.key, activeTabKey = active.key)
    }

    fun activateTab(key: String) {
        val current = _state.value
        val tab = current.tabs.firstOrNull { it.key == key } ?: return
        if (!tab.enabled) return
        userMovedFocus = true
        _state.value = current.copy(
            focusedKey = key,
            activeTabKey = key,
            visibleRows = visibleRows(rows, key),
        )
    }

    fun moveTab(delta: Int) {
        val current = _state.value
        if (current.tabs.isEmpty() || delta == 0) return
        val enabledTabs = current.tabs.filter { it.enabled }
        if (enabledTabs.isEmpty()) return
        val index = enabledTabs.indexOfFirst { it.key == current.activeTabKey }
        val next = if (index < 0) 0 else (index + delta).coerceIn(0, enabledTabs.size - 1)
        activateTab(enabledTabs[next].key)
    }

    fun enterResults() {
        val current = _state.value
        if (current.tabs.firstOrNull { it.key == current.activeTabKey }?.enabled != true) return
        val visible = visibleRows(rows, current.activeTabKey)
        if (visible.isEmpty()) return
        val remembered = rememberedRowByTab[current.activeTabKey]
        val key = visible.firstOrNull { it.key == remembered && isEnabled(it) }?.key
            ?: firstFocusableKey(visible)
            ?: return
        userMovedFocus = true
        _state.value = current.copy(focusedKey = key)
    }

    fun onFocusedCandidateRemoved(removedKey: String) {
        val current = _state.value
        if (current.focusedKey != removedKey) return
        val siblings = current.visibleRows.filter { it.key != removedKey && isEnabled(it) }
        val fallback = siblings.firstOrNull()?.key
            ?: current.visibleRows.firstOrNull { it.key != removedKey }?.key
            ?: CANCEL_KEY
        _state.value = current.copy(focusedKey = fallback)
    }

    fun select(key: String): SelectionResult {
        val current = _state.value
        val row = rows.firstOrNull { it.key == key } ?: return SelectionResult.Blocked(key, PickerRowState.Loading)
        val blockedAttempt = { rowState: PickerRowState ->

            _state.value = _state.value.copy(attemptedKeys = _state.value.attemptedKeys + key)
            SelectionResult.Blocked(key, rowState)
        }
        if (current.policy == SelectionPolicy.MANUAL && current.focusedKey != key) {

            return blockedAttempt(row.state)
        }
        return when (val rowState = row.state) {
            is PickerRowState.ExternalLink -> {

                autoplayJob?.cancel()
                autoplayCommitted = true
                _state.value = current.copy(autoplayCountdownMs = null)
                SelectionResult.ExternalLink(rowState.candidate)
            }
            is PickerRowState.Ready -> {
                autoplayJob?.cancel()
                autoplayCommitted = true
                _state.value = current.copy(
                    attemptedKeys = current.attemptedKeys + key,
                    autoplayCountdownMs = null,
                )

                onSelectionPersisted?.invoke(rowState.candidate)
                SelectionResult.Started(rowState.candidate)
            }
            else -> blockedAttempt(rowState)
        }
    }

    fun setPolicy(policy: SelectionPolicy) {
        autoplayJob?.cancel()
        _state.value = _state.value.copy(policy = policy, autoplayCountdownMs = null)
        if (policy == SelectionPolicy.AUTOPLAY) maybeOpenAutoplayWindow()
    }

    fun selectTouched(key: String): SelectionResult {
        onUserFocusMoved(key)
        return select(key)
    }

    private fun maybeOpenAutoplayWindow() {
        val current = _state.value
        if (current.autoplayCountdownMs != null) return
        if (autoplayCommitted) return

        if (current.policy != SelectionPolicy.AUTOPLAY) return
        if (!resolutionComplete) return
        val eligible = current.visibleRows.firstOrNull { isEnabled(it) } ?: return
        openAutoplayWindow(eligible.key)
    }

    private fun openAutoplayWindow(key: String) {
        val row = rows.firstOrNull { it.key == key } ?: return
        if (!isEnabled(row)) return
        autoplayCommitted = true
        _state.value = _state.value.copy(
            autoplayCountdownMs = autoplayWindowMs,
            focusedKey = key,
        )
        autoplayJob?.cancel()
        autoplayJob = scope.launch {
            var remaining = autoplayWindowMs

            while (remaining > 0) {
                delay(100)
                remaining -= 100
                _state.value = _state.value.copy(autoplayCountdownMs = remaining)
            }

            val s = _state.value
            if (s.autoplayCountdownMs != null && s.policy == SelectionPolicy.AUTOPLAY) {
                _state.value = s.copy(autoplayCountdownMs = null)
                val stillReady = rows.firstOrNull { it.key == key }?.state is PickerRowState.Ready
                if (stillReady) select(key)
            }
        }
    }

    private fun updateFocusedKeyAfterArrival(visible: List<PickerRow>) {
        if (userMovedFocus) return
        val current = _state.value
        val currentKey = current.focusedKey
        if (currentKey != null && currentKey != CANCEL_KEY) {
            val isTabKey = current.tabs.any { it.key == currentKey }
            val stillVisible = visible.any { it.key == currentKey }
            if (isTabKey || stillVisible) return
        }
        val first = firstFocusableKey(visible) ?: return
        _state.value = current.copy(focusedKey = first)
    }

    private fun firstFocusableKey(visible: List<PickerRow>): String? =
        visible.firstOrNull { isEnabled(it) }?.key ?: visible.firstOrNull()?.key

    private fun mergeRows(
        existing: List<PickerRow>,
        incoming: List<PlayableCandidate>,
        complete: Boolean,
    ): List<PickerRow> = SourceCandidateProjection.mergeRows(existing, incoming, complete)

    private fun toPickerRow(candidate: PlayableCandidate): PickerRow =
        SourceCandidateProjection.toPickerRow(candidate)

    private fun deriveTabs(rows: List<PickerRow>, allEnabled: Boolean): List<PickerTab> {
        val tabs = ArrayList<PickerTab>()
        tabs.add(PickerTab.ALL.copy(count = rows.size, enabled = allEnabled))
        val seen = HashSet<String>()
        for (row in rows) {
            val providerKey = row.providerKey ?: continue
            if (seen.add(providerKey)) {
                tabs.add(
                    PickerTab(
                        key = PickerTab.providerKey(providerKey),
                        label = row.providerInstanceLabel ?: row.providerLabel ?: providerKey,
                        providerSourceId = providerKey,
                        count = rows.count { it.providerKey == providerKey },
                    ),
                )
            }
        }
        return tabs
    }

    private fun resolveActiveTab(tabs: List<PickerTab>, current: String): String {
        val currentTab = tabs.firstOrNull { it.key == current }
        if (currentTab != null && currentTab.enabled) return currentTab.key
        return tabs.firstOrNull { it.enabled && it.key != PickerTab.ALL_KEY }?.key
            ?: tabs.firstOrNull { it.enabled }?.key
            ?: tabs.firstOrNull()?.key
            ?: PickerTab.ALL_KEY
    }

    private fun relabelProviderInstances(rows: List<PickerRow>): List<PickerRow> {
        if (rows.isEmpty()) return rows
        val entries = rows.mapNotNull { row ->
            val key = row.providerKey ?: return@mapNotNull null
            key to (row.providerLabel ?: key)
        }.distinctBy { it.first }
        val labels = providerInstanceLabels(entries)
        return rows.map { row ->
            val label = row.providerKey?.let { labels[it] }
            if (label == row.providerInstanceLabel) row else row.copy(providerInstanceLabel = label)
        }
    }

    private fun visibleRows(rows: List<PickerRow>, activeTabKey: String): List<PickerRow> {
        if (activeTabKey == PickerTab.ALL_KEY) return allOrderedBySizeDescending(rows)
        val providerSourceId = activeTabKey
            .takeIf { it.startsWith(PROVIDER_TAB_PREFIX) }
            ?.removePrefix(PROVIDER_TAB_PREFIX)
            ?: return rows
        return rows.filter { it.providerKey == providerSourceId }
    }

    private fun allOrderedBySizeDescending(rows: List<PickerRow>): List<PickerRow> =
        SourceCandidateProjection.allOrderedBySizeDescending(rows)

    private fun toRowState(candidate: PlayableCandidate): PickerRowState =
        SourceCandidateProjection.toRowState(candidate)

    private fun isEnabled(row: PickerRow): Boolean = SourceCandidateProjection.isEligible(row)

    fun retryResolution() {
        startResolution()
    }
}
