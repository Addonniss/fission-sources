package dev.infusion.feature.vertical.ratings

import dev.infusion.core.domain.ratings.CommunityRatingsPort
import dev.infusion.core.domain.ratings.OmdbTestOutcome
import dev.infusion.core.domain.ratings.RatingsCredentialStore
import dev.infusion.core.domain.ratings.RatingsPreferences
import dev.infusion.core.domain.ratings.RatingsPreferencesStore
import dev.infusion.core.model.metadata.RatingAppearance
import dev.infusion.core.model.metadata.RatingMetric
import dev.infusion.core.model.metadata.RatingSupplier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RatingsSettingsUiState(
    val preferences: RatingsPreferences = RatingsPreferences.Default,

    val omdbKeyConfigured: Boolean = false,

    val availableSuppliers: Set<RatingSupplier> = emptySet(),

    val testingOmdb: Boolean = false,

    val omdbTestResult: OmdbTestOutcome? = null,
)

class RatingsSettingsStateHolder(
    private val preferencesStore: RatingsPreferencesStore,
    private val credentialStore: RatingsCredentialStore,
    private val community: CommunityRatingsPort,
    private val scope: CoroutineScope,
    private val actionDispatcher: CoroutineDispatcher = Dispatchers.Default,

    private val onPreferencesRecorded: (RatingsPreferences) -> Unit = {},
) {

    private val _state = MutableStateFlow(RatingsSettingsUiState())
    val state: StateFlow<RatingsSettingsUiState> = _state.asStateFlow()

    fun refresh() {
        scope.launch {
            val preferences = withContext(actionDispatcher) { runCatching { preferencesStore.current() }.getOrDefault(RatingsPreferences.Default) }
            val configured = withContext(actionDispatcher) { runCatching { credentialStore.hasOmdbKey() }.getOrDefault(false) }
            val available = withContext(actionDispatcher) { runCatching { community.availableSuppliers() }.getOrDefault(emptySet()) }
            _state.value = _state.value.copy(
                preferences = preferences,
                omdbKeyConfigured = configured,
                availableSuppliers = available,
                omdbTestResult = null,
            )
        }
    }

    fun setSupplierEnabled(supplier: RatingSupplier, enabled: Boolean) {
        val current = _state.value.preferences
        val updated = current.copy(
            enabledSuppliers = if (enabled) current.enabledSuppliers + supplier
            else current.enabledSuppliers - supplier,
        )
        record(updated)
    }

    fun moveSupplier(supplier: RatingSupplier, delta: Int) {
        val current = _state.value.preferences
        val order = current.supplierOrder.toMutableList()
        val index = order.indexOf(supplier)
        if (index < 0) return
        val target = (index + delta).coerceIn(0, order.lastIndex)
        if (target == index) return
        order.removeAt(index)
        order.add(target, supplier)
        record(current.copy(supplierOrder = order))
    }

    fun setMetricDisplayed(metric: RatingMetric, displayed: Boolean) {
        val current = _state.value.preferences
        val updated = current.copy(
            displayedMetrics = if (displayed) current.displayedMetrics + metric
            else current.displayedMetrics - metric,
        )
        record(updated)
    }

    fun setAppearance(appearance: RatingAppearance) {
        record(_state.value.preferences.copy(appearance = appearance))
    }

    fun setEpisodeSourceEnabled(source: RatingMetric, enabled: Boolean) {
        if (source !in RatingMetric.SortableEpisodeSources) return
        val current = _state.value.preferences
        val updated = current.copy(
            episodeEnabledMetrics = if (enabled) current.episodeEnabledMetrics + source
            else current.episodeEnabledMetrics - source,
        )
        if (updated.episodeEnabledMetrics == current.episodeEnabledMetrics) return
        record(updated)
    }

    fun moveEpisodeSource(source: RatingMetric, delta: Int) {
        if (source !in RatingMetric.SortableEpisodeSources) return
        val current = _state.value.preferences
        val order = current.episodeSourceOrder.toMutableList()
        val index = order.indexOf(source)
        if (index < 0) return
        val target = (index + delta).coerceIn(0, order.lastIndex)
        if (target == index) return
        order.removeAt(index)
        order.add(target, source)
        record(current.copy(episodeSourceOrder = order))
    }

    fun saveOmdbKey(key: String) {
        scope.launch {
            val ok = withContext(actionDispatcher) {
                runCatching { credentialStore.saveOmdbKey(key) }.isSuccess
            }
            if (ok) {
                val current = _state.value.preferences
                val enabled = current.copy(enabledSuppliers = current.enabledSuppliers + RatingSupplier.OMDB)
                withContext(actionDispatcher) { runCatching { preferencesStore.record(enabled) } }
                _state.value = _state.value.copy(preferences = enabled, omdbKeyConfigured = true)
                refreshAvailability()
            }
        }
    }

    fun removeOmdbKey() {
        scope.launch {
            withContext(actionDispatcher) { runCatching { credentialStore.removeOmdbKey() } }
            val current = _state.value.preferences
            val disabled = current.copy(enabledSuppliers = current.enabledSuppliers - RatingSupplier.OMDB)
            withContext(actionDispatcher) { runCatching { preferencesStore.record(disabled) } }
            _state.value = _state.value.copy(preferences = disabled, omdbKeyConfigured = false)
            refreshAvailability()
        }
    }

    fun testOmdb(candidateKey: String?) {
        if (_state.value.testingOmdb) return
        _state.value = _state.value.copy(testingOmdb = true, omdbTestResult = null)
        scope.launch {
            val outcome = withContext(actionDispatcher) {
                runCatching { community.testOmdb(candidateKey) }
                    .getOrDefault(OmdbTestOutcome.Unreachable("test failed"))
            }
            _state.value = _state.value.copy(testingOmdb = false, omdbTestResult = outcome)
        }
    }

    private fun refreshAvailability() {
        scope.launch {
            val available = withContext(actionDispatcher) {
                runCatching { community.availableSuppliers() }.getOrDefault(emptySet())
            }
            _state.value = _state.value.copy(availableSuppliers = available)
        }
    }

    private fun record(preferences: RatingsPreferences) {

        _state.value = _state.value.copy(preferences = preferences)

        runCatching { onPreferencesRecorded(preferences) }
        scope.launch {
            withContext(actionDispatcher) { runCatching { preferencesStore.record(preferences) } }
        }
    }
}
