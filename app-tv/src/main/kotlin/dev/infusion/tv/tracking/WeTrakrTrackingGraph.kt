package dev.infusion.tv.tracking

import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.sync.WatchOutbox
import dev.infusion.core.domain.tracking.MutableTrackingServicePort
import dev.infusion.core.domain.tracking.TrackingConnectionState
import dev.infusion.core.domain.tracking.TrackingCredentialStore
import dev.infusion.core.domain.tracking.TrackingInboundCursorStore
import dev.infusion.core.domain.tracking.TrackingInboundIdentityCompletionPort
import dev.infusion.core.domain.tracking.TrackingInboundIdentityPort
import dev.infusion.core.domain.tracking.TrackingMediaResolverPort
import dev.infusion.core.domain.tracking.TrackingPreferencesStore
import dev.infusion.core.domain.tracking.TrackingRuntimeSourcePort
import dev.infusion.core.domain.tracking.TrackingWatchedCacheStore
import dev.infusion.core.domain.tracking.TrackingWatchedNegativeEvidencePort
import dev.infusion.core.domain.tracking.WatchTrackingSelectionControl
import dev.infusion.core.domain.tracking.WatchTrackingSelectionGate
import dev.infusion.core.network.HardenedHttpClient
import dev.infusion.core.network.OkHttpHardenedClient
import dev.infusion.core.sync.WatchSyncCoordinator
import dev.infusion.core.sync.tracking.TrackingWatchedBulkSync
import dev.infusion.feature.vertical.tracking.TrackingSettingsStateHolder
import dev.infusion.tracking.wetrakr.HardenedWeTrakrTransport
import dev.infusion.tracking.wetrakr.WeTrakrAuthRepository
import dev.infusion.tracking.wetrakr.WeTrakrConfiguration
import dev.infusion.tracking.wetrakr.WeTrakrHttpClient
import dev.infusion.tracking.wetrakr.WeTrakrReadAdmission
import dev.infusion.tracking.wetrakr.WeTrakrTrackingProvider
import kotlinx.coroutines.CoroutineScope

class WeTrakrTrackingGraph(
    scope: CoroutineScope,

    private val profileScope: ProfileScope,
    credentialStore: TrackingCredentialStore,
    outbox: WatchOutbox,
    resolver: TrackingMediaResolverPort,
    trackingServicePort: MutableTrackingServicePort,
    watchTrackingSelectionGate: WatchTrackingSelectionGate,
    preferencesStore: TrackingPreferencesStore,
    coordinator: WatchSyncCoordinator,
    inboundIdentity: TrackingInboundIdentityPort,
    runtimeSource: TrackingRuntimeSourcePort,
    inboundCursorStore: TrackingInboundCursorStore,
    watchedCacheStore: TrackingWatchedCacheStore,
    inboundIdentityCompletion: TrackingInboundIdentityCompletionPort? = null,

    clientId: String,
    appVersion: String,

    hub: HardenedHttpClient = OkHttpHardenedClient(),

    onActivated: (suspend () -> Unit)? = null,

    watchedNegativeEvidence: TrackingWatchedNegativeEvidencePort? = null,

    watchedBulkSync: TrackingWatchedBulkSync? = null,
) {

    val configuration: WeTrakrConfiguration = WeTrakrConfiguration(
        clientId = clientId.trim(),
        appName = APP_NAME,
        appVersion = appVersion,
    )

    private val transport = HardenedWeTrakrTransport(hub)

    private val readAdmission = WeTrakrReadAdmission()

    private val client = WeTrakrHttpClient(
        configuration = configuration,
        transport = transport,

        trace = { line -> android.util.Log.i(TRACE_TAG, line) },
        admission = readAdmission,
        admissionPartition = { admissionPartition() },
    )

    private val auth = WeTrakrAuthRepository(
        configuration = configuration,
        client = client,
        store = credentialStore,
    )

    private fun admissionPartition(): WeTrakrReadAdmission.Partition =
        WeTrakrReadAdmission.Partition(
            profileId = runCatching { profileScope.activeProfileId }.getOrDefault(""),
            accountId = auth.accountId(),
        )

    val provider: WeTrakrTrackingProvider = WeTrakrTrackingProvider(configuration, client, auth)

    val watchedHistory: dev.infusion.tracking.wetrakr.WeTrakrWatchedHistory by lazy {
        dev.infusion.tracking.wetrakr.WeTrakrWatchedHistory(client = client, auth = auth)
    }

    val watchedItemNames: dev.infusion.tracking.wetrakr.WeTrakrWatchedItemNames by lazy {
        dev.infusion.tracking.wetrakr.WeTrakrWatchedItemNames(client = client, auth = auth)
    }

    val journal: dev.infusion.tracking.wetrakr.WeTrakrJournal by lazy {
        dev.infusion.tracking.wetrakr.WeTrakrJournal(client = client, auth = auth)
    }

    val watchlistMembership: dev.infusion.tracking.wetrakr.WeTrakrWatchlistMembership by lazy {
        dev.infusion.tracking.wetrakr.WeTrakrWatchlistMembership(
            client = client,
            auth = auth,
            trace = { line -> android.util.Log.i(TRACE_TAG, line) },
        )
    }

    val personalRatings: dev.infusion.tracking.wetrakr.WeTrakrPersonalRatings by lazy {
        dev.infusion.tracking.wetrakr.WeTrakrPersonalRatings(
            client = client,
            auth = auth,
            trace = { line -> android.util.Log.i(TRACE_TAG, line) },
        )
    }

    val publicLists: dev.infusion.tracking.wetrakr.WeTrakrPublicLists by lazy {
        dev.infusion.tracking.wetrakr.WeTrakrPublicLists(
            client = client,
            auth = auth,
            trace = { line -> android.util.Log.i(TRACE_TAG, line) },
        )
    }

    val comments: dev.infusion.tracking.wetrakr.WeTrakrComments by lazy {
        dev.infusion.tracking.wetrakr.WeTrakrComments(client = client, auth = auth)
    }

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

        reviewReader = { comments },

        onTerminalDelivered = { refreshInbound() },

        deliveryTrace = { line -> android.util.Log.i(TRACE_TAG, line) },

        onActivated = onActivated,

        watchedNegativeEvidence = watchedNegativeEvidence,
        watchedBulkSync = watchedBulkSync,
    )

    fun refreshInbound() = graph.refreshInbound()

    fun refreshRegistration() = graph.refreshRegistration()

    suspend fun applyRegistration() = graph.applyRegistration()

    fun observeProfileSwitches() = graph.observeProfileSwitches()

    fun observeConnectionChanges() = graph.observeConnectionChanges()

    fun connectionState(): TrackingConnectionState = graph.connectionState()

    fun accountConnected(): Boolean = auth.isConnected()

    fun registered(): Boolean = graph.registered()

    suspend fun accountIdentity() = provider.accountIdentity()

    fun accountId(): String? = provider.accountId()

    fun settingsStateHolder(
        holderScope: CoroutineScope,
        selectionControl: WatchTrackingSelectionControl,
        jellyfinConnected: () -> Boolean = { false },
    ): TrackingSettingsStateHolder =
        graph.settingsStateHolder(holderScope, selectionControl, jellyfinConnected)

    private companion object {

        const val APP_NAME = "fission"

        const val TRACE_TAG = "INFUSION-TRACKING"
    }
}
