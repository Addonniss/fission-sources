package dev.infusion.source.addon.repository

import dev.infusion.core.database.Clock
import dev.infusion.core.database.dao.SourceDao
import dev.infusion.core.database.entity.AddonEntity
import dev.infusion.core.database.entity.AddonHealthObservationEntity
import dev.infusion.core.database.entity.SourceEntity
import dev.infusion.core.database.model.HealthState
import dev.infusion.core.database.model.RemovalScope
import dev.infusion.core.database.model.SourceType
import dev.infusion.core.database.sources.SourceRemoval
import dev.infusion.core.model.SourceHealth
import dev.infusion.core.network.DiagnosticReason
import dev.infusion.core.network.LanTrustGrant
import dev.infusion.core.network.LanTrustRegistry
import dev.infusion.core.network.isProviderObservation
import dev.infusion.source.addon.AddonDisclosure
import dev.infusion.source.addon.AddonManifestSnapshot
import dev.infusion.source.addon.AddonManifestSnapshotCodec
import dev.infusion.source.addon.AddonMapper
import dev.infusion.source.addon.InstalledAddon
import dev.infusion.source.addon.cache.AddonManifestCache
import dev.infusion.source.addon.manifest.AddonManifestMapper
import dev.infusion.source.addon.model.AddonBehaviorHints
import dev.infusion.source.addon.model.AddonCatalog
import dev.infusion.source.addon.model.AddonCatalogExtra
import dev.infusion.source.addon.model.AddonManifest
import dev.infusion.source.addon.model.AddonResource
import dev.infusion.source.addon.model.AddonStremioAddonsConfig
import dev.infusion.source.addon.store.AddonCredentialStore
import dev.infusion.source.addon.store.AddonStore
import dev.infusion.source.stremio.AddonClientFactory
import dev.infusion.source.stremio.StremioClient
import dev.infusion.source.stremio.StremioClientFactory
import dev.infusion.source.stremio.StremioFailure
import dev.infusion.source.stremio.StremioProviderState
import dev.infusion.source.stremio.StremioResult
import dev.infusion.source.stremio.url.ConfiguredAddonUrl
import dev.infusion.source.stremio.wire.Resource
import dev.infusion.source.addon.repository.AddonRepository.AddonAddResult
import dev.infusion.source.addon.repository.AddonRepository.AddonFetchResult
import dev.infusion.source.addon.repository.AddonRepository.AddonOrderResult
import dev.infusion.source.addon.repository.AddonRepository.AddonRemoveResult
import dev.infusion.source.addon.repository.AddonRepository.RefreshResult
import dev.infusion.source.addon.repository.AddonRepository.SetEnabledResult
import dev.infusion.source.addon.repository.AddonRepository.SetDisplayNameResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class RoomAddonRepository(
    private val addonStore: AddonStore,
    private val credentialStore: AddonCredentialStore,
    private val sourceDao: SourceDao,
    private val sourceRemoval: SourceRemoval,
    private val lanTrust: LanTrustRegistry,
    private val transaction: (block: () -> Unit) -> Unit,
    private val cache: AddonManifestCache = AddonManifestCache(),
    private val clock: Clock = Clock.SYSTEM,

    private val clientFactory: AddonClientFactory = { configured, trust ->
        StremioClientFactory.create(configured, trust, lanTrust)
    },

    private val backgroundScope: CoroutineScope? = null,

    private val onSourceRemoved: (addonId: String) -> Unit = {},

    private val healthHistoryLimit: Int = AddonHealthObservationEntity.MAX_HISTORY,
) : AddonRepository {

    private val _installed = MutableStateFlow<AddonRepositoryState>(AddonRepositoryState.Restoring)
    override val installed: StateFlow<AddonRepositoryState> = _installed.asStateFlow()

    private val _restoreIssues = MutableStateFlow<List<AddonStore.RestoreIssue>>(emptyList())
    override val restoreIssues: StateFlow<List<AddonStore.RestoreIssue>> = _restoreIssues.asStateFlow()

    private val mutex = Mutex()

    private val runtimes = ConcurrentHashMap<String, InstalledAddonRuntime>()

    private val ownedScope = backgroundScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ownsScope = backgroundScope == null

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override suspend fun restore(): AddonRepositoryState = mutex.withLock {

        _installed.value = AddonRepositoryState.Restoring
        _restoreIssues.value = emptyList()

        val issues = LinkedHashMap<String, AddonStore.RestoreIssue>()
        val candidates = LinkedHashMap<String, InstalledAddonRuntime>()
        val attemptGrants = LinkedHashSet<String>()
        try {

            val decoded = ArrayList<Pair<String, InstalledAddon>>()
            for (stored in addonStore.listActive()) {
                val addonId = stored.entity.addonId
                val installed = try {
                    AddonMapper.toDomain(stored.entity, credentialStore)
                } catch (e: Exception) {
                    issues[addonId] = AddonStore.RestoreIssue(addonId, classifyMappingFailure(stored.entity, e))
                    continue
                }
                if (installed.lanConsented && installed.lanHost != null) {

                    val host = installed.lanHost
                    if (!lanTrust.isApproved(host)) {
                        lanTrust.approve(LanTrustGrant(host))
                        attemptGrants += host
                    }
                }
                decoded += addonId to installed
            }

            for ((addonId, installed) in decoded) {
                val runtime = try {
                    buildRuntime(installed)
                } catch (e: IllegalArgumentException) {

                    installed.lanHost?.let { host ->
                        if (attemptGrants.remove(host)) lanTrust.revoke(host)
                    }
                    issues[addonId] = AddonStore.RestoreIssue(addonId, RESTORE_ISSUE_RUNTIME_CONSTRUCTION)
                    continue
                }

                if (!installed.enabled) runtime.providerState.disable()
                candidates[addonId] = runtime
            }
        } catch (e: Exception) {

            for (candidate in candidates.values) runCatching { candidate.close() }
            for (host in attemptGrants) lanTrust.revoke(host)
            for (prior in runtimes.values) runCatching { prior.close() }
            runtimes.clear()
            cache.clear()
            val fatal = mergeStoreIssues(issues).values.toList()
            _restoreIssues.value = fatal
            val failed = AddonRepositoryState.Failed(fatal)
            _installed.value = failed
            return failed
        }

        val issuesList = mergeStoreIssues(issues).values.toList()
        _restoreIssues.value = issuesList

        val state: AddonRepositoryState = when {

            candidates.isEmpty() && issuesList.isNotEmpty() -> AddonRepositoryState.Failed(issuesList)
            else -> AddonRepositoryState.Ready(orderedInstalled(candidates.values))
        }

        for (prior in runtimes.values) runCatching { prior.close() }
        runtimes.clear()
        cache.clear()
        for ((addonId, runtime) in candidates) {
            runtimes[addonId] = runtime
            val installed = runtime.installed
            cache.put(addonId, installed.snapshot, installed.manifestFetchedAtMs)
        }
        _installed.value = state

        if (state is AddonRepositoryState.Ready) startBackgroundRefresh()
        return state
    }

    override suspend fun fetchManifest(
        configured: ConfiguredAddonUrl,
        forceRefresh: Boolean,
    ): AddonRepository.AddonFetchResult {
        val addonId = configured.identityUuid().toString()
        val cached = cache.get(addonId)
        if (!forceRefresh && cached != null && !cache.isStale(cached)) {
            return AddonRepository.AddonFetchResult.Ok(
                manifest = snapshotToDomain(cached.snapshot),
                snapshot = cached.snapshot,
                fromCache = true,
            )
        }
        val client = try {

            clientFactory(
                configured,
                if (lanTrust.isApproved(configured.host)) {
                    dev.infusion.core.network.TrustClass.LAN_TRUSTED
                } else {
                    dev.infusion.core.network.TrustClass.PUBLIC
                },
            )
        } catch (e: IllegalArgumentException) {
            return AddonRepository.AddonFetchResult.Failed(
                StremioFailure(
                    reason = DiagnosticReason.INTERNAL,
                    status = null,
                    message = "unable to build add-on client",
                    retryable = false,
                    diagnostic = dev.infusion.core.network.RedactedDiagnostic(
                        correlationId = dev.infusion.core.network.CorrelationIds.newId(),
                        reason = DiagnosticReason.INTERNAL,
                        origin = configured.origin,
                    ),
                ),
            )
        }
        return try {
            when (val result = client.fetchManifest()) {
                is StremioResult.Ok -> {
                    val dto = result.value
                    val snapshot = AddonManifestSnapshotCodec.fromManifest(dto)
                    AddonRepository.AddonFetchResult.Ok(
                        manifest = AddonManifestMapper.toDomain(dto),
                        snapshot = snapshot,
                        fromCache = false,
                    )
                }
                is StremioResult.Err -> AddonRepository.AddonFetchResult.Failed(result.failure)
            }
        } finally {
            client.close()
        }
    }

    override suspend fun add(request: InstallRequest): AddonRepository.AddonAddResult = mutex.withLock {
        val configured = request.configured
        val addonId = configured.identityUuid().toString()

        addonStore.getEntity(addonId)?.let { existing ->
            if (existing.removedAtMs == null) return AddonRepository.AddonAddResult.AlreadyInstalled(addonId)
        }

        val lanGrantPreExisted = request.lanConsented && lanTrust.isApproved(configured.host)

        if (request.lanConsented) lanTrust.approve(LanTrustGrant(configured.host))
        fun rollbackLan() {
            if (request.lanConsented && !lanGrantPreExisted) lanTrust.revoke(configured.host)
        }

        val snapshot = request.validatedSnapshot ?: run {
            val fetch = fetchManifest(configured, forceRefresh = true)
            when (fetch) {
                is AddonRepository.AddonFetchResult.Ok -> fetch.snapshot
                is AddonRepository.AddonFetchResult.Failed -> {
                    rollbackLan()
                    return AddonRepository.AddonAddResult.FetchFailed(fetch.failure)
                }
            }
        }

        val now = clock.nowMs()
        val ownerProfileId = request.ownerProfileId
        val querySecretRefId = configured.querySecret?.raw?.let { credentialStore.stage(it) }
        val lanRequested = request.trust == dev.infusion.core.network.TrustClass.LAN_TRUSTED
        val installed = InstalledAddon(
            addonId = addonId,
            configured = configured,
            snapshot = snapshot,
            disclosure = buildDisclosure(configured, snapshot, lanRequested, now),
            lanConsented = request.lanConsented,
            lanHost = if (lanRequested) configured.host else null,
            enabled = true,
            sortOrder = Int.MAX_VALUE,
            ownerProfileId = ownerProfileId,
            installedAtMs = now,
            updatedAtMs = now,
            manifestFetchedAtMs = now,
        )

        val runtime = try {
            buildRuntime(installed)
        } catch (e: Exception) {
            querySecretRefId?.let { credentialStore.delete(it) }
            rollbackLan()
            return AddonRepository.AddonAddResult.Invalid(ADD_INVALID_RUNTIME_CONSTRUCTION)
        }

        try {
            transaction {
                sourceDao.upsert(
                    SourceEntity(
                        sourceId = addonId,
                        type = SourceType.STREMIO_REMOTE,
                        ownerProfileId = ownerProfileId,
                        displayName = installed.displayName,
                        origin = configured.origin,
                        trustClass = dev.infusion.core.database.model.TrustClass.REMOTE_ADDON,
                        enabled = true,
                        healthState = HealthState.UNKNOWN,
                        lastHealthCheckMs = null,
                        createdAtMs = now,
                        removedAtMs = null,
                    ),
                )
                addonStore.put(AddonMapper.toEntity(installed, querySecretRefId = querySecretRefId), snapshot)

                val orderedIds = (addonStore.listActive().map { it.entity.addonId } + addonId).distinct()
                addonStore.reorder(orderedIds)
            }
        } catch (e: Exception) {

            runtime.close()
            querySecretRefId?.let { credentialStore.delete(it) }
            rollbackLan()
            return AddonRepository.AddonAddResult.Invalid("failed to persist add-on configuration")
        }

        runtimes[addonId] = runtime
        cache.put(addonId, snapshot, now)

        if (request.lanConsented) rebindLanTrustFor(configured.host)

        syncRuntimeOrder()
        publishReady()
        return AddonRepository.AddonAddResult.Ok(addonId)
    }

    override suspend fun remove(
        addonId: String,
        removalScope: RemovalScope,
    ): AddonRepository.AddonRemoveResult = mutex.withLock {
        val runtime = runtimes[addonId]
        runtime?.providerState?.disable()
        val installed = runtime?.installed
        if (installed?.lanConsented == true && installed.lanHost != null) {
            lanTrust.revoke(installed.lanHost)
        }

        val stored = addonStore.get(addonId)
        val entity = stored?.entity
        entity?.querySecretRefId?.let { credentialStore.delete(it) }
        val report = sourceRemoval.remove(addonId, removalScope)

        addonStore.delete(addonId)
        cache.remove(addonId)
        runtimes.remove(addonId)
        runtime?.close()
        onSourceRemoved(addonId)
        publishReady()
        AddonRepository.AddonRemoveResult.Ok(report)
    }

    override suspend fun setEnabled(addonId: String, enabled: Boolean): SetEnabledResult = mutex.withLock {
        val now = clock.nowMs()
        if (addonStore.getEntity(addonId) == null) return SetEnabledResult.NotFound
        addonStore.setEnabled(addonId, enabled, now)
        sourceDao.setEnabled(addonId, enabled)
        val runtime = runtimes[addonId]
        if (runtime != null) {

            if (enabled) runtime.providerState.enable() else runtime.providerState.disable()
            runtime.replaceInstalled(runtime.installed.copy(enabled = enabled, updatedAtMs = now))
        }
        publishReady()
        SetEnabledResult.Applied
    }

    override suspend fun setDisplayName(addonId: String, displayName: String?): SetDisplayNameResult = mutex.withLock {
        val normalized = displayName?.trim()

        if (normalized != null && normalized.isEmpty()) return SetDisplayNameResult.Invalid
        if (addonStore.getEntity(addonId) == null) return SetDisplayNameResult.NotFound
        val now = clock.nowMs()

        val manifestFallback = runtimes[addonId]?.installed?.snapshot?.name
            ?: addonStore.get(addonId)?.snapshot?.name
        addonStore.setDisplayName(addonId, normalized, now)

        manifestFallback?.let { fallback -> sourceDao.setDisplayName(addonId, normalized ?: fallback) }
        val runtime = runtimes[addonId]
        if (runtime != null) {
            runtime.replaceInstalled(
                runtime.installed.copy(displayNameOverride = normalized, updatedAtMs = now),
            )
        }
        publishReady()
        SetDisplayNameResult.Applied
    }

    override suspend fun setOrder(orderedIds: List<String>): AddonOrderResult = mutex.withLock {
        val activeIds = runtimes.keys
        val expected = activeIds
        if (orderedIds.toSet() != expected || orderedIds.size != expected.size) {
            return AddonOrderResult.Invalid(
                "order must be a contiguous permutation of the installed add-on ids",
            )
        }

        addonStore.reorder(orderedIds)
        orderedIds.forEachIndexed { index, id ->
            runtimes[id]?.let { it.replaceInstalled(it.installed.copy(sortOrder = index, updatedAtMs = clock.nowMs())) }
        }
        publishReady()
        AddonOrderResult.Applied(orderedIds)
    }

    override suspend fun refresh(addonId: String): RefreshResult = mutex.withLock {
        if (closed.get()) return RefreshResult.NotFound
        val runtime = runtimes[addonId] ?: return RefreshResult.NotFound
        if (!runtime.installed.enabled) return RefreshResult.Disabled

        val entry = cache.get(addonId)
        if (entry != null && !cache.isStale(entry)) return RefreshResult.Fresh

        val now = clock.nowMs()
        val result = runtime.useClient {
            it.fetchManifest(cancellation = runtime.providerState.currentScope())
        }
        return when (result) {
            is StremioResult.Ok -> {
                val dto = result.value
                val snapshot = AddonManifestSnapshotCodec.fromManifest(dto)
                val domain = AddonManifestMapper.toDomain(dto)
                swapManifest(addonId, runtime, snapshot, now)
                recordHealth(addonId, healthy = true, reason = null, status = result.status, now = now)
                RefreshResult.Ok(domain)
            }
            is StremioResult.Err -> {

                recordHealth(addonId, healthy = false, reason = result.failure.reason, status = result.failure.status, now = now)
                RefreshResult.StaleNotRefreshed(result.failure)
            }
        }
    }

    override fun runtimeFor(addonId: String): InstalledAddonRuntime? = runtimes[addonId]

    override fun close() {
        closed.set(true)
        for (runtime in runtimes.values) {
            runCatching { runtime.close() }
        }
        runtimes.clear()
        cache.clear()
        if (ownsScope) {

            ownedScope.coroutineContext[kotlinx.coroutines.Job]?.let { job ->
                kotlinx.coroutines.runBlocking {
                    job.cancelAndJoin()
                }
            }
            ownedScope.cancel()
        }
    }

    private fun publishReady() {
        val list = runtimes.values.sortedBy { it.installed.sortOrder }.map { it.installed }
        _installed.value = AddonRepositoryState.Ready(list)
    }

    private fun orderedInstalled(runtimes: Collection<InstalledAddonRuntime>): List<InstalledAddon> =
        runtimes.sortedBy { it.installed.sortOrder }.map { it.installed }

    private fun buildRuntime(installed: InstalledAddon): InstalledAddonRuntime {

        val bound = installed.withLiveLanTrust(lanTrust)
        val client = clientFactory(bound.configured, bound.trust)
        val state = StremioProviderState()
        return InstalledAddonRuntime(client = client, providerState = state, installed = bound)
    }

    private fun rebindLanTrustFor(host: String) {
        val affected = runtimes.filterValues { runtime ->
            runtime.installed.liveLanHost == null &&
                !runtime.installed.lanConsented &&
                runtime.installed.configured.host.equals(host, ignoreCase = true)
        }
        if (affected.isEmpty()) return
        for ((id, prior) in affected) {
            val rebound = runCatching { buildRuntime(prior.installed) }.getOrNull() ?: continue
            if (!prior.installed.enabled) rebound.providerState.disable()
            runtimes[id] = rebound
            runCatching { prior.close() }
        }
    }

    private fun swapManifest(addonId: String, runtime: InstalledAddonRuntime, snapshot: AddonManifestSnapshot, now: Long) {
        val refId = addonStore.get(addonId)?.entity?.querySecretRefId
        val updated = runtime.installed.copy(
            snapshot = snapshot,
            updatedAtMs = now,
            manifestFetchedAtMs = now,
        )

        addonStore.put(AddonMapper.toEntity(updated, querySecretRefId = refId, manifestFetchedAtMs = now), snapshot)
        runtime.replaceInstalled(updated)
        cache.put(addonId, snapshot, now)
        publishReady()
    }

    private fun startBackgroundRefresh() {
        ownedScope.launch {
            if (closed.get()) return@launch
            val staleOrMissing = runtimes.keys.filter { cache.freshness(it) == AddonManifestCache.Freshness.STALE }
            for (addonId in staleOrMissing) {
                if (closed.get()) return@launch
                refresh(addonId)
            }
        }
    }

    private fun mergeStoreIssues(
        participant: LinkedHashMap<String, AddonStore.RestoreIssue>,
    ): LinkedHashMap<String, AddonStore.RestoreIssue> {
        for (storeIssue in addonStore.restoreIssues()) {
            if (storeIssue.addonId !in participant) {
                participant[storeIssue.addonId] = AddonStore.RestoreIssue(
                    addonId = storeIssue.addonId,
                    reason = normalizeStoreIssueReason(storeIssue.reason),
                )
            }
        }
        return participant
    }

    private fun normalizeStoreIssueReason(reason: String): String =
        if (reason.startsWith("corrupt manifest snapshot")) {
            RESTORE_ISSUE_SNAPSHOT_CORRUPT
        } else {
            RESTORE_ISSUE_UNKNOWN
        }

    private fun syncRuntimeOrder() {
        val order = addonStore.listActive().map { it.entity.addonId }
        order.forEachIndexed { index, id ->
            runtimes[id]?.replaceInstalled(runtimes[id]!!.installed.copy(sortOrder = index))
        }
    }

    private fun buildDisclosure(
        configured: ConfiguredAddonUrl,
        snapshot: AddonManifestSnapshot,
        lanRequested: Boolean,
        nowMs: Long,
    ): AddonDisclosure = AddonDisclosure(
        endpoint = configured.origin,
        requestedResourceTypes = snapshot.requestedResourceTypes,
        catalogVisibility = snapshot.catalogVisibility,
        networkDisclosure = if (lanRequested) {
            "HTTPS/HTTP to ${configured.origin} (approved LAN host); no credentials; no cookies; no headers; bounded responses"
        } else {
            "HTTPS to ${configured.origin}; no credentials; no cookies; no headers; bounded responses"
        },
        credentialUse = AddonDisclosure.CREDENTIAL_USE_NONE,
        lanGranted = lanRequested,
        disclosedAtMs = nowMs,
    )

    private fun classifyMappingFailure(entity: AddonEntity, failure: Exception): String {
        val refId = entity.querySecretRefId
        if (refId != null) {
            if (credentialStore.resolve(refId) == null) {
                return if (credentialStore.exists(refId)) {
                    RESTORE_ISSUE_QUERY_SECRET_UNDECODABLE
                } else {
                    RESTORE_ISSUE_QUERY_SECRET_MISSING
                }
            }
        }
        return when {
            !isValidSnapshotJson(entity.manifestSnapshotJson) -> RESTORE_ISSUE_SNAPSHOT_CORRUPT
            failure.message?.contains("public base") == true -> RESTORE_ISSUE_URL_CORRUPT
            else -> RESTORE_ISSUE_UNKNOWN
        }
    }

    private fun isValidSnapshotJson(raw: String): Boolean = try {
        org.json.JSONObject(raw)
        true
    } catch (e: Exception) {
        false
    }

    private fun healthStateFor(reason: DiagnosticReason?): HealthState = when (reason) {
        DiagnosticReason.UNAUTHORIZED -> HealthState.DEGRADED
        DiagnosticReason.TIMEOUT,
        DiagnosticReason.CONNECTION_FAILED,
        DiagnosticReason.DNS_FAILED,
        DiagnosticReason.REDIRECT_POLICY,
        DiagnosticReason.PRIVATE_DESTINATION,
        -> HealthState.UNREACHABLE
        null -> HealthState.HEALTHY
        else -> HealthState.DEGRADED
    }

    private fun recordHealth(addonId: String, healthy: Boolean, reason: DiagnosticReason?, status: Int?, now: Long) {
        if (reason != null && !reason.isProviderObservation) return
        addonStore.recordHealthObservation(
            AddonHealthObservationEntity(
                observationId = java.util.UUID.randomUUID().toString(),
                addonId = addonId,
                observedAtMs = now,
                healthy = healthy,
                reason = reason?.name,
                status = status,
                sourceHealth = when {
                    healthy -> SourceHealth.HEALTHY
                    reason == DiagnosticReason.UNAUTHORIZED -> SourceHealth.UNAUTHORIZED
                    reason == DiagnosticReason.TIMEOUT ||
                        reason == DiagnosticReason.CONNECTION_FAILED ||
                        reason == DiagnosticReason.DNS_FAILED ||
                        reason == DiagnosticReason.REDIRECT_POLICY ||
                        reason == DiagnosticReason.PRIVATE_DESTINATION -> SourceHealth.UNREACHABLE
                    else -> SourceHealth.DEGRADED
                },
            ),
        )
        addonStore.pruneHealthObservations(addonId, healthHistoryLimit)
        sourceDao.updateHealth(addonId, healthStateFor(reason).name, now)
    }

    private fun snapshotToDomain(s: AddonManifestSnapshot): AddonManifest = AddonManifest(
        id = s.id,
        name = s.name,
        description = s.description,
        version = s.version,
        logo = s.logo,
        background = s.background,
        catalogs = s.catalogs.map { c ->
            AddonCatalog(
                type = c.type,
                id = c.id,
                name = c.name,
                extras = c.extras.map { e ->
                    AddonCatalogExtra(e.name, e.isRequired, e.options, e.optionsLimit)
                },
            )
        },
        types = s.types,
        rawTypes = s.types.map { it.wire },
        resources = s.resources.map { r ->
            AddonResource(
                resource = Resource.parse(r.name) ?: Resource.CATALOG,
                types = r.types,
                idPrefixes = r.idPrefixes,
                extraSupported = emptyList(),
            )
        },
        idPrefixes = s.idPrefixes,
        behaviorHints = s.behaviorHints?.let { b ->
            AddonBehaviorHints(adult = b.adult, configurable = b.configurable, configurationRequired = b.configurationRequired)
        },
        stremioAddonsConfig = s.stremioAddonsConfig?.let { c ->
            AddonStremioAddonsConfig(catalogId = c.catalogId, addons = c.addons)
        },
        language = s.language,
        configVersion = s.configVersion,
        timestamp = s.timestamp,
    )

    private companion object {
        const val RESTORE_ISSUE_QUERY_SECRET_MISSING = "secret_ref_missing"
        const val RESTORE_ISSUE_QUERY_SECRET_UNDECODABLE = "secret_bytes_undecodable"
        const val RESTORE_ISSUE_SNAPSHOT_CORRUPT = "snapshot_corrupt"
        const val RESTORE_ISSUE_URL_CORRUPT = "url_corrupt"
        const val RESTORE_ISSUE_RUNTIME_CONSTRUCTION = "runtime_construction_failed"
        const val RESTORE_ISSUE_UNKNOWN = "restore_failed"

        const val ADD_INVALID_RUNTIME_CONSTRUCTION = "runtime construction failed"
    }
}