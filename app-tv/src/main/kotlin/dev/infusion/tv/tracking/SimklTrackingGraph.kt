package dev.infusion.tv.tracking

import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.sync.WatchOutbox
import dev.infusion.core.domain.tracking.MutableTrackingServicePort
import dev.infusion.core.domain.tracking.TrackingConnectionState
import dev.infusion.core.domain.tracking.TrackingCredentialStore
import dev.infusion.core.domain.tracking.TrackingInboundCursorStore
import dev.infusion.core.domain.tracking.TrackingInboundIdentityCompletionPort
import dev.infusion.core.domain.tracking.TrackingInboundIdentityPort
import dev.infusion.core.domain.tracking.TrackingInboundSyncResult
import dev.infusion.core.domain.tracking.TrackingMediaResolverPort
import dev.infusion.core.domain.tracking.TrackingPreferencesStore
import dev.infusion.core.domain.tracking.TrackingRuntimeSourcePort
import dev.infusion.core.domain.tracking.TrackingWatchedCacheStore
import dev.infusion.core.domain.tracking.TrackingWatchedLookupResult
import dev.infusion.core.domain.tracking.WatchTrackingSelectionControl
import dev.infusion.core.domain.tracking.WatchTrackingSelectionGate
import dev.infusion.core.network.HardenedHttpClient
import dev.infusion.core.network.OkHttpHardenedClient
import dev.infusion.core.sync.WatchSyncCoordinator
import dev.infusion.core.sync.tracking.TrackingInboundImporter
import dev.infusion.core.sync.tracking.TrackingScrobbleConnector
import dev.infusion.core.sync.tracking.TrackingWatchedReconciler
import dev.infusion.feature.vertical.tracking.TrackingSettingsStateHolder
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.tracking.simkl.HardenedSimklTransport
import dev.infusion.tracking.simkl.SimklAuthRepository
import dev.infusion.tracking.simkl.SimklConfiguration
import dev.infusion.tracking.simkl.SimklHttpClient
import dev.infusion.tracking.simkl.SimklTrackingProvider
import kotlinx.coroutines.CoroutineScope

class SimklTrackingGraph(
    scope: CoroutineScope,
    profileScope: ProfileScope,
    credentialStore: TrackingCredentialStore,
    outbox: WatchOutbox,
    resolver: TrackingMediaResolverPort,
    trackingServicePort: MutableTrackingServicePort,
    watchTrackingSelectionGate: WatchTrackingSelectionGate,
    preferencesStore: TrackingPreferencesStore,
    coordinator: WatchSyncCoordinator,
    inboundIdentity: TrackingInboundIdentityPort,
    inboundIdentityCompletion: TrackingInboundIdentityCompletionPort? = null,
    runtimeSource: TrackingRuntimeSourcePort,
    inboundCursorStore: TrackingInboundCursorStore,
    watchedCacheStore: TrackingWatchedCacheStore,

    clientId: String,
    appVersion: String,

    hub: HardenedHttpClient = OkHttpHardenedClient(),
) {

    val configuration: SimklConfiguration = SimklConfiguration(
        clientId = clientId.trim(),
        appName = APP_NAME,
        appVersion = appVersion,
    )

    private val client = SimklHttpClient(
        configuration = configuration,
        transport = HardenedSimklTransport(hub),

        trace = { line -> android.util.Log.i(TRACE_TAG, line) },
    )

    private val auth = SimklAuthRepository(
        configuration = configuration,
        client = client,
        store = credentialStore,
    )

    val provider: SimklTrackingProvider = SimklTrackingProvider(configuration, client, auth)

    val graph: TrackingProviderGraph = TrackingProviderGraph(
        scope = scope,
        profileScope = profileScope,
        credentialStore = credentialStore,
        outbox = outbox,
        resolver = resolver,
        trackingServicePort = trackingServicePort,
        watchTrackingSelectionGate = watchTrackingSelectionGate,
        preferencesStore = preferencesStore,
        coordinator = coordinator,
        inboundIdentity = inboundIdentity,
        inboundIdentityCompletion = inboundIdentityCompletion,
        runtimeSource = runtimeSource,
        inboundCursorStore = inboundCursorStore,
        watchedCacheStore = watchedCacheStore,
        provider = provider,
        isConnected = { auth.isConnected() },
        registered = { configuration.hasClientId },
        membership = { watchlistMembership },

        ratings = { personalRatings },
    )

    val connector: TrackingScrobbleConnector get() = graph.connector

    val inbound: TrackingInboundImporter? get() = graph.inbound

    val watched: TrackingWatchedReconciler? get() = graph.watched

    val lastInboundResult: TrackingInboundSyncResult? get() = graph.lastInboundResult

    fun demandWatchedState(watchKey: WatchKey) = graph.demandWatchedState(watchKey)

    fun demandWatchedStates(watchKeys: Collection<WatchKey>) = graph.demandWatchedStates(watchKeys)

    fun refreshInbound() = graph.refreshInbound()

    suspend fun refreshInboundNow(): TrackingInboundSyncResult = graph.refreshInboundNow()

    fun refreshRegistration() = graph.refreshRegistration()

    suspend fun applyRegistration() = graph.applyRegistration()

    fun observeProfileSwitches() = graph.observeProfileSwitches()

    fun observeConnectionChanges() = graph.observeConnectionChanges()

    fun connectionState(): TrackingConnectionState = graph.connectionState()

    fun accountConnected(): Boolean = auth.isConnected()

    suspend fun accountAccessToken(): String? = auth.accessToken()

    val publicLists: dev.infusion.tracking.simkl.SimklPublicLists by lazy {
        dev.infusion.tracking.simkl.SimklPublicLists(
            client = client,
            accountToken = { auth.accessToken() },
            isAccountConnected = { auth.isConnected() },
        )
    }

    val watchedHistory: dev.infusion.tracking.simkl.SimklWatchedHistory by lazy {
        dev.infusion.tracking.simkl.SimklWatchedHistory(client = client, auth = auth)
    }

    val watchlistMembership: dev.infusion.tracking.simkl.SimklWatchlistMembership by lazy {
        dev.infusion.tracking.simkl.SimklWatchlistMembership(
            client = client,
            auth = auth,
        )
    }

    val personalRatings: dev.infusion.tracking.simkl.SimklPersonalRatings by lazy {
        dev.infusion.tracking.simkl.SimklPersonalRatings(
            client = client,
            auth = auth,
        )
    }

    fun registered(): Boolean = graph.registered()

    fun settingsStateHolder(
        holderScope: CoroutineScope,
        selectionControl: WatchTrackingSelectionControl,
        jellyfinConnected: () -> Boolean = { false },
    ): TrackingSettingsStateHolder =
        graph.settingsStateHolder(holderScope, selectionControl, jellyfinConnected)

    private companion object {

        const val APP_NAME = "infusion"

        const val TRACE_TAG = "INFUSION-TRACKING"
    }
}
