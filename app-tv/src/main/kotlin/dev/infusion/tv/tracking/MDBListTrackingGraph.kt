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
import dev.infusion.tracking.mdblist.HardenedMDBListTransport
import dev.infusion.tracking.mdblist.MDBListAuthRepository
import dev.infusion.tracking.mdblist.MDBListConfiguration
import dev.infusion.tracking.mdblist.MDBListHttpClient
import dev.infusion.tracking.mdblist.MDBListTrackingProvider
import kotlinx.coroutines.CoroutineScope

class MDBListTrackingGraph(
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

    val configuration: MDBListConfiguration = MDBListConfiguration(
        clientId = clientId.trim(),
        appName = APP_NAME,
        appVersion = appVersion,
    )

    private val transport = HardenedMDBListTransport(hub)

    private val client = MDBListHttpClient(
        configuration = configuration,
        transport = transport,

        trace = { line -> android.util.Log.i(TRACE_TAG, line) },
    )

    private val auth = MDBListAuthRepository(
        configuration = configuration,
        client = client,
        store = credentialStore,
    )

    val provider: MDBListTrackingProvider = MDBListTrackingProvider(configuration, client, auth)

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
        registered = { configuration.hasRegistration },
        membership = { watchlistMembership },

        ratings = { personalRatings },

        reviewReader = { reviews },

        onTerminalDelivered = { refreshInboundAfterTerminalWrite() },
    )

    private fun refreshInboundAfterTerminalWrite() = graph.refreshInbound()

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

    val publicLists: dev.infusion.tracking.mdblist.MDBListPublicLists by lazy {
        dev.infusion.tracking.mdblist.MDBListPublicLists(
            transport = transport,
            accountToken = { auth.accessToken() },
            isAccountConnected = { auth.isConnected() },
        )
    }

    val watchlistMembership: dev.infusion.tracking.mdblist.MDBListWatchlistMembership by lazy {
        dev.infusion.tracking.mdblist.MDBListWatchlistMembership(
            client = client,
            auth = auth,
        )
    }

    val watchedHistory: dev.infusion.tracking.mdblist.MDBListWatchedHistory by lazy {
        dev.infusion.tracking.mdblist.MDBListWatchedHistory(client = client, auth = auth)
    }

    val personalRatings: dev.infusion.tracking.mdblist.MDBListPersonalRatings by lazy {
        dev.infusion.tracking.mdblist.MDBListPersonalRatings(
            client = client,
            auth = auth,
        )
    }

    val reviews: dev.infusion.tracking.mdblist.MDBListDiscussions by lazy {
        dev.infusion.tracking.mdblist.MDBListDiscussions(
            client = client,
            auth = auth,
        )
    }

    val communityMedia: dev.infusion.tracking.mdblist.MDBListCommunityMedia by lazy {
        dev.infusion.tracking.mdblist.MDBListCommunityMedia(client = client, auth = auth)
    }

    suspend fun communityMediaInfo(
        mediaProvider: String,
        mediaType: String,
        mediaId: String,
    ): String? = communityMedia.mediaInfo(mediaProvider, mediaType, mediaId)

    fun registered(): Boolean = graph.registered()

    fun settingsStateHolder(
        holderScope: CoroutineScope,
        selectionControl: WatchTrackingSelectionControl,
        jellyfinConnected: () -> Boolean = { false },
    ): TrackingSettingsStateHolder =
        graph.settingsStateHolder(holderScope, selectionControl, jellyfinConnected)

    private companion object {

        const val APP_NAME = "INFUSION"

        const val TRACE_TAG = "INFUSION-TRACKING"
    }
}
