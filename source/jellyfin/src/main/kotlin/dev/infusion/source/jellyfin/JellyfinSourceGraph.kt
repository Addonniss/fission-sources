package dev.infusion.source.jellyfin

import dev.infusion.core.database.Clock
import dev.infusion.core.database.dao.AvailabilityDao
import dev.infusion.core.database.dao.ExternalIdDao
import dev.infusion.core.database.dao.ServerItemDao
import dev.infusion.core.database.dao.ServerMediaVersionDao
import dev.infusion.core.database.dao.TitleDao
import dev.infusion.core.database.store.RoomSourceItemStore
import dev.infusion.core.domain.candidate.CandidateResolverPort
import dev.infusion.core.domain.catalog.CatalogPort
import dev.infusion.core.domain.source.SourceItemStore
import dev.infusion.core.domain.sync.ConnectorCheckpointStore
import dev.infusion.core.domain.sync.PlaybackSessionContextStore
import dev.infusion.core.domain.sync.WatchOutbox
import dev.infusion.core.model.Source
import dev.infusion.core.model.SourceCapability
import dev.infusion.core.model.SourceHealth
import dev.infusion.core.model.SourceId
import dev.infusion.core.model.TrustClass
import dev.infusion.core.network.LanTrustRegistry
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.playback.api.ActivePlaybackReporter
import dev.infusion.core.playback.api.SessionLineage
import dev.infusion.core.playback.api.TransportRequest
import dev.infusion.core.security.SecretStore
import dev.infusion.source.jellyfin.catalog.JellyfinCatalogPort
import dev.infusion.source.jellyfin.catalog.JellyfinMapper
import dev.infusion.source.jellyfin.playback.JellyfinCandidateResolver
import dev.infusion.source.jellyfin.playback.JellyfinPlayerErrorRecovery
import dev.infusion.source.jellyfin.playback.JellyfinSegmentEvidence
import dev.infusion.source.jellyfin.playback.JellyfinTransportCodec
import dev.infusion.source.jellyfin.playback.JellyfinTransportResolver
import dev.infusion.source.jellyfin.session.JellyfinSessionReporter
import dev.infusion.source.jellyfin.session.JellyfinActiveReporterAdapter
import dev.infusion.source.jellyfin.sync.JellyfinWatchConnector
import dev.infusion.source.jellyfin.sync.JellyfinPostPlaybackSync
import dev.infusion.source.jellyfin.auth.JellyfinSession
import dev.infusion.source.jellyfin.removal.JellyfinSourceRemoval
import java.util.UUID
import kotlinx.coroutines.CoroutineScope

class JellyfinSourceGraph(
    val source: JellyfinSource,
    serverItemDao: ServerItemDao,
    serverMediaVersionDao: ServerMediaVersionDao,
    titleDao: TitleDao,
    externalIdDao: ExternalIdDao,
    availabilityDao: AvailabilityDao,
    outbox: WatchOutbox,
    secretStore: SecretStore,
    lanTrust: LanTrustRegistry,
    scope: CoroutineScope,
    limits: NetworkLimits = NetworkLimits.DEFAULT,
    clock: Clock = Clock.SYSTEM,

    transaction: ((block: () -> Unit) -> Unit)? = null,

    playbackContextStore: PlaybackSessionContextStore,

    checkpointStore: ConnectorCheckpointStore = dev.infusion.core.sync.store.InMemoryConnectorCheckpointStore(),

    private val convergence: dev.infusion.core.domain.identity.CanonicalConvergenceStore? = null,

    private val ratingsEvidence: dev.infusion.core.domain.ratings.JellyfinRatingsEvidenceStore? = null,

    private val videoPlaybackActive: () -> Boolean = { false },
) {

    val sourceId: SourceId = SourceId(UUID.nameUUIDFromBytes(source.sourceKey.toByteArray()))

    private val secretStoreRef: SecretStore = secretStore
    private val lanTrustRef: LanTrustRegistry = lanTrust
    private val outboxRef: WatchOutbox = outbox
    private val checkpointStoreRef: ConnectorCheckpointStore = checkpointStore
    private val scopeRef: CoroutineScope = scope
    private val limitsRef: NetworkLimits = limits

    val canonicalSource: Source = Source(
        sourceId = sourceId,
        type = dev.infusion.core.model.SourceType.JELLYFIN,
        trustClass = TrustClass.PERSONAL_SERVER,
        displayName = source.config.username.let { "${source.config.origin.host} · $it" },
        endpoint = source.config.origin.origin,
        enabled = true,
        credentialRef = null,
        capabilities = setOf(
            SourceCapability.BROWSE,
            SourceCapability.SEARCH,
            SourceCapability.METADATA,
            SourceCapability.STREAM,
            SourceCapability.SUBTITLES,
            SourceCapability.WATCH_STATE_SYNC,
        ),
        health = SourceHealth.UNKNOWN,
    )

    private val store: SourceItemStore = RoomSourceItemStore(
        serverItemDao,
        serverMediaVersionDao,
        titleDao,
        externalIdDao,
        availabilityDao,
        clock,
        transaction = transaction ?: { block -> block() },
    )

    private val mapper = JellyfinMapper(
        sourceId = sourceId,
        serverId = serverIdFrom(source),
        serverVersion = source.serverVersion ?: "unknown",
    )

    val catalog: JellyfinCatalogPort = JellyfinCatalogPort(
        source = canonicalSource,
        client = source.client,
        authenticator = source.authenticator,
        store = store,
        mapper = mapper,
        sessionProvider = { source.session() },
        scope = scope,
        limits = limits,
        convergence = convergence,
        ratingsEvidence = ratingsEvidence,
    )

    val resolver: CandidateResolverPort = JellyfinCandidateResolver(
        sourceId = sourceId,
        store = store,
        client = source.client,
        authenticator = source.authenticator,
        mapper = mapper,
        sessionProvider = { source.session() },
        serverVersion = source.serverVersion ?: "unknown",
        limits = limits,
    )

    private val transportResolver = JellyfinTransportResolver(
        origin = source.config.origin,
        authenticator = source.authenticator,
        sessionProvider = { source.session() },
        embyAuthHeader = source.client::embyAuthHeader,
    )

    val sessionReporter = JellyfinSessionReporter(source.client, source.authenticator, limits)

    val playerErrorRecovery = JellyfinPlayerErrorRecovery(
        client = source.client,
        authenticator = source.authenticator,
        sessionProvider = { source.session() },
        serverVersion = source.serverVersion ?: "unknown",
        limits = limits,
    )

    val segmentEvidence = JellyfinSegmentEvidence(
        client = source.client,
        authenticator = source.authenticator,
        sessionProvider = { source.session() },
        limits = limits,
    )

    val watchConnector = JellyfinWatchConnector(
        sourceId = sourceId,
        outbox = outbox,
        reporter = sessionReporter,
        sessionProvider = { source.session() },
        scope = scope,
        contextStore = playbackContextStore,
        activeLifecycleOwned = true,

        postPlaybackSync = JellyfinPostPlaybackSync(
            catalog = catalog,
            sessionProvider = { source.session() },
        ),
        videoPlaybackActive = videoPlaybackActive,
    )

    fun activeReporterFor(candidate: dev.infusion.core.model.PlayableCandidate, playbackScope: CoroutineScope): ActivePlaybackReporter? =
        source.session()?.let { session -> JellyfinActiveReporterAdapter.create(candidate, sessionReporter, session, playbackScope) }

    val musicQueue: dev.infusion.source.jellyfin.music.JellyfinMusicQueuePort by lazy {
        dev.infusion.source.jellyfin.music.JellyfinMusicQueuePort(
            sourceId = sourceId,
            catalog = catalog,
            client = source.client,
            authenticator = source.authenticator,
            transportResolver = transportResolver,
            sessionProvider = { source.session() },
            limits = limitsRef,
        )
    }

    fun musicReporterFor(
        identity: dev.infusion.core.domain.music.MusicReportingIdentity,
        playbackScope: CoroutineScope,
    ): ActivePlaybackReporter? =
        source.session()?.let { session ->
            JellyfinActiveReporterAdapter.create(identity, sessionReporter, session, playbackScope)
        }

    fun transportFor(candidate: dev.infusion.core.model.PlayableCandidate, lineage: SessionLineage): TransportRequest =
        transportResolver.requestFor(candidate, lineage)

    suspend fun currentResumePositionFor(
        candidate: dev.infusion.core.model.PlayableCandidate,
    ): dev.infusion.core.playback.api.ServerResumePosition? {
        val payload = JellyfinTransportCodec.decode(candidate.transport) ?: return null
        return catalog.currentResumePositionForNativeItem(payload.itemId)
    }

    suspend fun currentResumePosition(
        titleId: dev.infusion.core.model.TitleId,
    ): dev.infusion.core.playback.api.ServerResumePosition? =
        catalog.currentResumePosition(titleId)

    suspend fun episodeResumePositionsForSeason(
        seriesTitleId: dev.infusion.core.model.TitleId,
        seasonNumber: Int,
    ): Map<Pair<Int, Int>, dev.infusion.core.playback.api.ServerResumePosition> =
        catalog.episodeResumePositionsForSeason(seriesTitleId, seasonNumber)

    suspend fun userProgressForNativeIds(
        nativeIds: List<String>,
    ): dev.infusion.source.jellyfin.client.ApiResult<dev.infusion.source.jellyfin.catalog.ServerProgressBatch> =
        catalog.userProgressForNativeIds(nativeIds)

    fun nativeItemIdsForTitles(
        titleIds: List<dev.infusion.core.model.TitleId>,
    ): Map<dev.infusion.core.model.TitleId, String> = catalog.nativeItemIdsForTitles(titleIds)

    fun currentUserId(): String? = source.session()?.userId

    suspend fun bindPlayback(
        watchSessionId: dev.infusion.core.model.SessionId,
        candidate: dev.infusion.core.model.PlayableCandidate,
    ) {
        val payload = JellyfinTransportCodec.decode(candidate.transport) ?: return
        val playSessionId = payload.playSessionId ?: return
        watchConnector.bindPlayback(watchSessionId, payload.itemId, payload.mediaSourceId, playSessionId)
    }

    suspend fun unbindPlayback(watchSessionId: dev.infusion.core.model.SessionId) {
        watchConnector.unbindPlayback(watchSessionId)
    }

    fun removal(
        sourceRemoval: dev.infusion.core.database.sources.SourceRemoval,
        scope: CoroutineScope,
    ): JellyfinSourceRemoval = JellyfinSourceRemoval(
        sourceRemoval = sourceRemoval,
        secretStore = secretStoreRef,
        lanTrust = lanTrustRef,
        authenticator = source.authenticator,
        serverHost = source.config.host,
        scope = scope,
        outbox = outboxRef,
        checkpointStore = checkpointStoreRef,
        sessionProvider = { source.session() },
    )

    fun restoreSession(session: JellyfinSession) {
        source.restoreSession(session)
    }

    fun libraryChangeFeed(
        onHint: (dev.infusion.core.domain.refresh.LibraryChangeHint) -> Unit,
        onStatus: (dev.infusion.core.domain.refresh.LibraryFeedStatus) -> Unit,
    ): dev.infusion.core.domain.refresh.LibraryChangeFeed =
        dev.infusion.source.jellyfin.events.JellyfinLibraryChangeFeed(
            client = source.client,
            serviceInstance = source.config.origin.origin,
            sessionProvider = { source.session() },
            tokenProvider = { session -> source.authenticator.token(session) },
            webSocketFactory = { source.client.hardenedWebSockets },
            parentScope = scopeRef,
            limits = limitsRef,
            onHint = onHint,
            onStatus = onStatus,
        )

    fun close() {
        watchConnector.stopDraining()
        watchConnector.stopPinging()
        source.close()
    }

    private fun serverIdFrom(source: JellyfinSource): String {

        return source.session()?.serverId?.takeIf { it.isNotBlank() }
            ?: "origin-${source.config.origin.origin.hashCode().toUInt().toString(16)}"
    }
}
