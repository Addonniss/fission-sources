package dev.infusion.tv.tracking

import dev.infusion.core.domain.sync.WatchOutbox
import dev.infusion.core.domain.tracking.TrackingPreferencesStore
import dev.infusion.core.domain.tracking.TrackingSelection
import dev.infusion.core.domain.tracking.TrackingTrackerOption
import dev.infusion.core.domain.tracking.WatchTrackingSelectionControl
import dev.infusion.core.sync.WatchSyncCoordinator
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.feature.vertical.tracking.TrackingSettingsStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class TrackingGraphs(
    private val scope: CoroutineScope,
    private val preferencesStore: TrackingPreferencesStore,
    private val outbox: WatchOutbox,
    private val coordinator: WatchSyncCoordinator,
    val simkl: SimklTrackingGraph,
    val trakt: TraktTrackingGraph,
    val mdblist: MDBListTrackingGraph,
) {

    private val graphs: List<TrackingProviderGraph> = listOf(simkl.graph, trakt.graph, mdblist.graph)

    private val reviewSources: List<Pair<TrackingProviderGraph, dev.infusion.core.domain.tracking.TrackingReviewReadPort>> =
        graphs.mapNotNull { graph -> graph.reviewSource?.let { source -> graph to source } }

    fun reviewPorts(): List<dev.infusion.core.domain.tracking.TrackingReviewReadPort> =
        reviewPortsOf(
            sources = reviewSources.map { (graph, port) -> graph.provider.providerId to port },
            isConnected = { providerId ->
                reviewSources.firstOrNull { (graph, _) -> graph.provider.providerId == providerId }
                    ?.let { (graph, _) -> runCatching { graph.isConnected() }.getOrDefault(false) }
                    ?: false
            },
        )

    fun watchedHistoryPorts(): Map<String, dev.infusion.core.domain.tracking.TrackingWatchedHistoryPort> =
        buildMap {
            put(simkl.provider.providerId, simkl.watchedHistory)
            put(trakt.provider.providerId, trakt.watchedHistory)
            put(mdblist.provider.providerId, mdblist.watchedHistory)
        }

    fun bindResumeResetGuard(
        guard: (dev.infusion.core.model.watch.WatchKey) -> Boolean,
        onRead: (java.time.Instant, Boolean, Set<dev.infusion.core.model.watch.WatchKey>) -> Unit,
    ) {
        graphs.forEach { it.bindResumeResetGuard(guard, onRead) }
    }

    private val selectionControl: WatchTrackingSelectionControl =
        object : WatchTrackingSelectionControl {

            override fun current(): TrackingSelection = preferencesStore.current().selection

            override fun trackers(): List<TrackingTrackerOption> = graphs.map { graph ->
                TrackingTrackerOption(
                    trackerId = graph.provider.providerId,
                    displayName = graph.provider.displayName,
                    capabilities = graph.provider.capabilities(),
                    connected = runCatching { graph.isConnected() }.getOrDefault(false),
                )
            }

            override fun select(selection: TrackingSelection) = recordWatchTrackingSelection(selection)

            override fun retryableCount(trackerId: String): Int = outbox.retryableCount(trackerId)

            override fun notSentByUserChoiceCount(trackerId: String): Int =
                outbox.notSentByUserChoiceCount(trackerId)
        }

    private fun recordWatchTrackingSelection(selection: TrackingSelection) {
        val previous = runCatching { preferencesStore.current().selection }
            .getOrDefault(TrackingSelection.OnDeviceOnly)
        preferencesStore.record(selection)
        val departed = (previous as? TrackingSelection.Tracker)?.trackerId
        if (departed != null && departed != (selection as? TrackingSelection.Tracker)?.trackerId) {

            runCatching { coordinator.disposeUnsentWorkByUserChoice(departed) }
        }
        refreshRegistration()
    }

    fun settingsStateHolders(
        holderScope: CoroutineScope,
        jellyfinConnected: () -> Boolean = { false },
    ): List<TrackingSettingsStateHolder> = graphs.map { graph ->
        graph.settingsStateHolder(holderScope, selectionControl, jellyfinConnected)
    }

    fun refreshInbound() {
        graphs.forEach { it.refreshInbound() }
    }

    fun demandWatchedState(watchKey: WatchKey) {
        graphs.forEach { it.demandWatchedState(watchKey) }
    }

    fun demandWatchedStates(watchKeys: Collection<WatchKey>) {
        graphs.forEach { it.demandWatchedStates(watchKeys) }
    }

    suspend fun applyRegistration() {
        graphs.forEach { it.applyRegistration() }
    }

    fun upNextTrackers(): List<Pair<String, String>> = graphs
        .filter { graph -> graph.offersUpNext() }
        .map { graph -> graph.provider.providerId to graph.provider.displayName }

    suspend fun readUpNext(
        trackerId: String,
        request: dev.infusion.core.domain.upnext.UpNextReadRequest,
    ): dev.infusion.core.domain.tracking.TrackingInboundOutcome<dev.infusion.core.domain.upnext.UpNextTrackerPage>? =
        graphs.firstOrNull { graph -> graph.provider.providerId == trackerId }?.readUpNext(request)

    fun upNextAccountLabel(trackerId: String): String? =
        graphs.firstOrNull { graph -> graph.provider.providerId == trackerId }?.accountLabel()

    fun startTrackingDelivery() {
        graphs.forEach { graph ->
            graph.observeProfileSwitches()
            graph.observeConnectionChanges()
        }
        refreshRegistration()
    }

    fun refreshRegistration() {
        scope.launch { graphs.forEach { it.applyRegistration() } }
    }

    val demandScope: CoroutineScope get() = scope
}

internal fun reviewPortsOf(
    sources: List<Pair<String, dev.infusion.core.domain.tracking.TrackingReviewReadPort>>,
    isConnected: (String) -> Boolean,
): List<dev.infusion.core.domain.tracking.TrackingReviewReadPort> = sources
    .filter { (providerId, _) ->

        runCatching { isConnected(providerId) }.getOrDefault(false)
    }
    .map { (_, port) -> port }
