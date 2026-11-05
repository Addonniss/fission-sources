package dev.infusion.tv.tracking

import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.sync.WatchOutbox
import dev.infusion.core.domain.tracking.MutableTrackingServicePort
import dev.infusion.core.domain.tracking.TrackingConnectionState
import dev.infusion.core.domain.tracking.TrackingCredentialStore
import dev.infusion.core.domain.tracking.TrackingInboundCursorStore
import dev.infusion.core.domain.tracking.TrackingInboundIdentityCompletionPort
import dev.infusion.core.domain.tracking.TrackingInboundIdentityPort
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.domain.tracking.TrackingInboundReadPort
import dev.infusion.core.domain.tracking.TrackingInboundSkipReason
import dev.infusion.core.domain.tracking.TrackingInboundSyncResult
import dev.infusion.core.domain.tracking.TrackingMediaResolverPort
import dev.infusion.core.domain.tracking.TrackingPreferencesStore
import dev.infusion.core.domain.tracking.TrackingProviderPort
import dev.infusion.core.domain.tracking.TrackingRuntimeSourcePort
import dev.infusion.core.domain.tracking.TrackingWatchedCacheStore
import dev.infusion.core.domain.tracking.TrackingWatchedLookupPort
import dev.infusion.core.domain.tracking.TrackingWatchedLookupResult
import dev.infusion.core.domain.tracking.WatchTrackingSelectionControl
import dev.infusion.core.domain.tracking.WatchTrackingSelectionGate
import dev.infusion.core.domain.upnext.UpNextReadPort
import dev.infusion.core.domain.watchlist.WatchlistMembershipPort
import java.time.Instant
import dev.infusion.core.domain.upnext.UpNextReadRequest
import dev.infusion.core.domain.upnext.UpNextTrackerPage
import dev.infusion.core.sync.WatchSyncCoordinator
import dev.infusion.core.sync.tracking.TrackingInboundImporter
import dev.infusion.core.sync.tracking.TrackingScrobbleConnector
import dev.infusion.core.sync.tracking.TrackingWatchedReconciler
import dev.infusion.feature.vertical.tracking.TrackingSettingsStateHolder
import dev.infusion.core.model.watch.WatchKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TrackingProviderGraph(
    private val scope: CoroutineScope,
    private val profileScope: ProfileScope,
    private val credentialStore: TrackingCredentialStore,
    private val outbox: WatchOutbox,
    private val resolver: TrackingMediaResolverPort,
    private val trackingServicePort: MutableTrackingServicePort,

    private val watchTrackingSelectionGate: WatchTrackingSelectionGate,

    private val preferencesStore: TrackingPreferencesStore,
    private val coordinator: WatchSyncCoordinator,
    private val inboundIdentity: TrackingInboundIdentityPort,

    private val inboundIdentityCompletion: TrackingInboundIdentityCompletionPort? = null,
    private val runtimeSource: TrackingRuntimeSourcePort,
    private val inboundCursorStore: TrackingInboundCursorStore,
    private val watchedCacheStore: TrackingWatchedCacheStore,

    val provider: TrackingProviderPort,

    val isConnected: () -> Boolean,

    val registered: () -> Boolean,

    reader: TrackingInboundReadPort? = provider as? TrackingInboundReadPort,

    lookup: TrackingWatchedLookupPort? = provider as? TrackingWatchedLookupPort,

    private val upNext: UpNextReadPort? = provider as? UpNextReadPort,

    onTerminalDelivered: () -> Unit = {},

    membership: () -> WatchlistMembershipPort? = { null },

    ratings: () -> dev.infusion.core.domain.ratings.PersonalRatingPort? = { null },

    private val reviewReader: () -> dev.infusion.core.domain.tracking.TrackingReviewReadPort? = { null },
) {

    val connector: TrackingScrobbleConnector = TrackingScrobbleConnector(
        outbox = outbox,
        resolver = resolver,
        provider = provider,
        selectionGate = watchTrackingSelectionGate,
        onTerminalDelivered = onTerminalDelivered,
        membership = membership,

        activeAccountRef = ::accountIdentity,

        ratings = ratings,
    )

    val inbound: TrackingInboundImporter? = reader?.let { readable ->
        TrackingInboundImporter(
            providerId = provider.providerId,
            reader = readable,
            identity = inboundIdentity,
            identityCompletion = inboundIdentityCompletion,
            runtime = runtimeSource,
            cursorStore = inboundCursorStore,
            outbox = outbox,
            coordinator = coordinator,

            inboundGuard = { key -> resumeGuard(key) },
            onPlaybackRead = { startedAt, completed, seen ->
                playbackReadEvidence(startedAt, completed, seen)
            },
        )
    }

    @Volatile
    private var resumeGuard: (WatchKey) -> Boolean = { false }

    val reviewSource: dev.infusion.core.domain.tracking.TrackingReviewReadPort? by lazy {
        if (!provider.capabilities().reviews) return@lazy null
        reviewReader()
    }

    @Volatile
    private var playbackReadEvidence: (Instant, Boolean, Set<WatchKey>) -> Unit = { _, _, _ -> }

    fun bindResumeResetGuard(
        guard: (WatchKey) -> Boolean,
        onRead: (java.time.Instant, Boolean, Set<WatchKey>) -> Unit,
    ) {
        resumeGuard = guard
        playbackReadEvidence = onRead
    }

    private val inboundMutex = Mutex()

    @Volatile
    private var connected = false

    @Volatile
    private var watchTrackingActive = false

    @Volatile
    private var wasWatchTrackingActive = false

    val watched: TrackingWatchedReconciler? = lookup?.let { requestScoped ->
        TrackingWatchedReconciler(
            providerId = provider.providerId,
            lookup = requestScoped,
            resolver = resolver,
            cache = watchedCacheStore,
            outbox = outbox,
            coordinator = coordinator,
            scope = scope,
            enabled = { watchTrackingActive },
            onPass = ::traceWatchedPass,
        )
    }

    fun demandWatchedState(watchKey: WatchKey) {
        watched?.demand(watchKey)
    }

    fun offersUpNext(): Boolean = upNext != null && runCatching { isConnected() }.getOrDefault(false)

    suspend fun readUpNext(
        request: UpNextReadRequest,
    ): TrackingInboundOutcome<UpNextTrackerPage>? {
        val port = upNext ?: return null
        return runCatching { port.readUpNext(request) }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            TrackingInboundOutcome.Unavailable(UP_NEXT_READ_FAILED)
        }
    }

    fun accountLabel(): String? = runCatching {
        credentialStore.credentials(provider.providerId)?.accountLabel
    }.getOrNull()

    fun accountIdentity(): String? = runCatching {
        credentialStore.credentials(provider.providerId)?.accountId
    }.getOrNull()

    fun demandWatchedStates(watchKeys: Collection<WatchKey>) {
        watched?.demandAll(watchKeys)
    }

    @Volatile
    var lastInboundResult: TrackingInboundSyncResult? = null
        private set

    fun refreshInbound() {
        scope.launch { refreshInboundNow() }
    }

    suspend fun refreshInboundNow(): TrackingInboundSyncResult {
        connected = runCatching { isConnected() }.getOrDefault(false)
        watchTrackingActive = connected && runCatching { selectedForActiveProfile() }.getOrDefault(false)
        val readable = inbound
        if (!watchTrackingActive || readable == null) {
            return TrackingInboundSyncResult(
                gateUnchanged = true,
                gate = inboundCursorStore.playbackGate(provider.providerId),
                watched = TrackingWatchedLookupResult(),
            )
        }
        val playback = inboundMutex.withLock {
            runCatching { readable.refresh() }.getOrElse {
                TrackingInboundSyncResult(failure = INBOUND_REFRESH_FAILED)
            }
        }

        val watchedEngine = watched
        val watchedResult = if (watchedEngine == null) {
            TrackingWatchedLookupResult()
        } else {
            watchedEngine.noteWatchedActivity(playback.watchedActivity)
            watchedEngine.reconcile()
        }
        val result = playback.copy(watched = watchedResult)
        lastInboundResult = result
        traceInbound(result)
        return result
    }

    private fun traceInbound(result: TrackingInboundSyncResult) {
        val line = buildString {
            append(provider.providerId)
            append(" inbound")
            append(" playback_imported=").append(result.imported)
            append(" playback_skipped=").append(reasonCounts(result.skipped))
            append(" pages=").append(result.pages)
            result.watchedActivity?.let { append(" watched_activity_observed=true") }
            result.failure?.let { append(" failure=present") }
        }
        android.util.Log.i(TRACE_TAG, line)
    }

    private fun traceWatchedPass(result: TrackingWatchedLookupResult) {
        val line = buildString {
            append(provider.providerId)
            append(" watched_lookup")
            append(" demanded=").append(result.demanded)
            append(" cached=").append(result.cached)
            append(" unresolved=").append(result.unresolved)
            append(" requests=").append(result.requests)
            append(" queried=").append(result.queried)
            append(" imported=").append(result.imported)
            append(" not_watched=").append(result.notWatched)
            append(" skipped=").append(reasonCounts(result.skipped))
            result.failure?.let { append(" failure=present") }
        }
        android.util.Log.i(TRACE_TAG, line)
    }

    private fun reasonCounts(skipped: Map<TrackingInboundSkipReason, Int>): String {
        val parts = TrackingInboundSkipReason.entries
            .mapNotNull { reason -> skipped[reason]?.takeIf { it > 0 }?.let { "$reason:$it" } }
        return if (parts.isEmpty()) "NONE" else parts.joinToString(",")
    }

    private var connectionObservation: Job? = null

    private val registrationMutex = Mutex()

    fun refreshRegistration() {
        scope.launch { applyRegistration() }
    }

    suspend fun applyRegistration() {
        registrationMutex.withLock {
            val nowConnected = runCatching { isConnected() }.getOrDefault(false)
            connected = nowConnected
            val isSelected = runCatching { selectedForActiveProfile() }.getOrDefault(false)
            watchTrackingActive = nowConnected && isSelected

            val activation = watchTrackingActive && !wasWatchTrackingActive
            if (activation && provider.capabilities().playbackImport) {
                runCatching { inboundCursorStore.clear(provider.providerId) }
            }
            if (watchTrackingActive) {
                trackingServicePort.publishConnector(
                    provider.providerId,
                    provider.capabilities().asConnectorRegistration(provider.displayName),
                )
                connector.startDraining(scope)
                if (provider.capabilities().playbackImport || provider.capabilities().watchedImport) {
                    refreshInbound()
                }
            } else {
                trackingServicePort.publishConnector(provider.providerId, null)
                connector.stopDraining()
                if (!nowConnected) {

                    runCatching { inboundCursorStore.clear(provider.providerId) }

                    watched?.clear()
                }

            }
            wasWatchTrackingActive = watchTrackingActive
            traceRegistration(nowConnected, isSelected)
        }
    }

    private fun traceRegistration(nowConnected: Boolean, isSelected: Boolean) {
        val published = runCatching {
            trackingServicePort.connectedConnectors().joinToString(",") { it.connectorId }
        }.getOrDefault("?")
        android.util.Log.i(
            TRACE_TAG,
            "${provider.providerId} registration connected=$nowConnected selected=$isSelected" +
                " active=$watchTrackingActive published=[$published]",
        )
    }

    private fun selectedForActiveProfile(): Boolean =
        preferencesStore.current().selectedTrackerId == provider.providerId

    fun observeProfileSwitches() {
        profileScope.activeProfileFlow
            .onEach {

                watched?.reset()
                refreshRegistration()
            }
            .launchIn(scope)
    }

    fun observeConnectionChanges() {
        if (connectionObservation != null) return
        connectionObservation = credentialStore.connectionChanges()
            .onEach { applyRegistration() }
            .launchIn(scope)
    }

    fun settingsStateHolder(
        holderScope: CoroutineScope,
        selectionControl: WatchTrackingSelectionControl,

        jellyfinConnected: () -> Boolean = { false },
    ): TrackingSettingsStateHolder = TrackingSettingsStateHolder(
        providerId = provider.providerId,
        providerName = provider.displayName,
        scope = holderScope,
        clientRegistered = { runCatching { registered() }.getOrDefault(false) },
        connectionStateAction = { provider.connectionState() },
        beginAction = { provider.beginAuthorization() },
        pollAction = { provider.pollAuthorization() },
        cancelAction = { provider.cancelAuthorization() },
        disconnectAction = { provider.disconnect() },
        selectionControl = selectionControl,

        connectionChanges = credentialStore.connectionChanges(),
        jellyfinConnected = jellyfinConnected,
    )

    fun connectionState(): TrackingConnectionState = runCatching { provider.connectionState() }
        .getOrDefault(TrackingConnectionState.Disconnected)

    private companion object {

        const val TRACE_TAG = "INFUSION-TRACKING"

        const val INBOUND_REFRESH_FAILED = "inbound refresh failed"

        const val UP_NEXT_READ_FAILED = "up next read failed"
    }
}
