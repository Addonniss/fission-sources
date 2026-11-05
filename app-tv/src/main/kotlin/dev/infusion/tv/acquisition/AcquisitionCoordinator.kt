package dev.infusion.tv.acquisition

import dev.infusion.core.domain.acquisition.AcquisitionConnection
import dev.infusion.core.domain.acquisition.AcquisitionConnectionStore
import dev.infusion.core.domain.acquisition.AcquisitionKind
import dev.infusion.core.domain.acquisition.AcquisitionOperationStore
import dev.infusion.core.domain.acquisition.AcquisitionOutcome
import dev.infusion.core.domain.acquisition.AcquisitionPort
import dev.infusion.core.domain.acquisition.AcquisitionRecord
import dev.infusion.core.domain.acquisition.AcquisitionRecordStatus
import dev.infusion.core.domain.acquisition.AcquisitionRef
import dev.infusion.core.domain.acquisition.AcquisitionRequest
import dev.infusion.core.domain.acquisition.AcquisitionService
import dev.infusion.core.domain.acquisition.AcquisitionStatus
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.refresh.ProvenImportKind
import dev.infusion.core.domain.refresh.ProvenImportTarget
import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.watchlist.CardAction
import dev.infusion.core.domain.watchlist.CardActionAvailability
import dev.infusion.core.domain.watchlist.CardActionId
import dev.infusion.core.domain.watchlist.CardActionOutcome
import dev.infusion.core.domain.watchlist.CardActionRequest
import dev.infusion.core.domain.watchlist.WatchlistAliasResolution
import dev.infusion.core.domain.watchlist.WatchlistIdentityResolver
import dev.infusion.core.domain.watchlist.WatchlistKind
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.network.UrlParser
import dev.infusion.tv.ui.presentation.CardAvailability
import java.io.Closeable
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AcquisitionCoordinator(
    private val scope: CoroutineScope,
    private val profileScope: ProfileScope,
    private val store: AcquisitionOperationStore,
    private val connectionStore: AcquisitionConnectionStore,
    private val identity: WatchlistIdentityResolver,
    private val portFactory: (
        serviceId: String,
        connection: AcquisitionConnection,
        apiKey: String,
    ) -> AcquisitionPort?,

    private val availability: () -> CardAvailability,
    private val now: () -> Instant = Instant::now,
    private val onChanged: () -> Unit = {},

    private val onDiagnostic: (String) -> Unit = {},

    private val onFileProven: (serviceId: String) -> Unit = {},

    private val onProvenImportTargets: (targets: List<ProvenImportTarget>) -> Unit = {},
) {

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private sealed interface CachedResolution {
        data class Found(val resolution: WatchlistAliasResolution) : CachedResolution

        data object None : CachedResolution
    }

    private val identityCache = ConcurrentHashMap<String, CachedResolution>()

    private val identityInflight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val observationInFlight = AtomicBoolean(false)

    private val storeLock = Mutex()

    @Volatile
    private var lastObservedAtMs: Long = Long.MIN_VALUE / 2

    @Volatile
    internal var lastOfferJob: kotlinx.coroutines.Job? = null
        private set

    fun reloadForActiveProfile() {
        identityCache.clear()
        identityInflight.clear()
        bump()
    }

    fun actionFor(request: CardActionRequest): CardAction? {
        return when (val structural = structuralPlan(request)) {
            is Structural.Missing -> disabledAction(structural.reason)
            is Structural.Needs -> {

                if (isUnitProven(structural, request)) return availableAction()
                ensureResolution(structural)
                val key = identityKey(structural)
                if (!identityCache.containsKey(key)) {

                    disabledAction(PENDING_REASON)
                } else {
                    when (val plan = toPlan(structural, request, resolutionOf(key))) {
                        is Plan.Missing -> disabledAction(plan.reason)
                        is Plan.Ready -> readyAction(plan.plan)
                    }
                }
            }
        }
    }

    fun warm(request: CardActionRequest) {
        (structuralPlan(request) as? Structural.Needs)?.let(::ensureResolution)
        offerObservation()
    }

    private fun offerObservation() {
        val atMs = now().toEpochMilli()
        if (atMs - lastObservedAtMs < OBSERVE_THROTTLE_MS) return
        if (!observationInFlight.compareAndSet(false, true)) return
        lastObservedAtMs = atMs
        lastOfferJob = scope.launch {
            try {
                observeNow()
            } finally {
                observationInFlight.set(false)
            }
        }
    }

    suspend fun perform(request: CardActionRequest): CardActionOutcome {
        val needs = when (val structural = structuralPlan(request)) {
            is Structural.Missing -> return CardActionOutcome.Refused(structural.reason)
            is Structural.Needs -> structural
        }

        if (isUnitProven(needs, request)) return CardActionOutcome.Refused(AVAILABLE_REASON)
        val plan = when (val p = resolvePlan(needs, request)) {
            is Plan.Missing -> return CardActionOutcome.Refused(p.reason)
            is Plan.Ready -> p.plan
        }
        val config = connectionStore.connection(plan.serviceId)
            ?: return CardActionOutcome.Refused("Set up ${serviceLabel(plan.serviceId)} in Settings → Jellyfin")
        val apiKey = connectionStore.apiKey(plan.serviceId)
            ?: return CardActionOutcome.Refused("Re-enter the ${serviceLabel(plan.serviceId)} API key in Settings → Jellyfin")
        val port = portFactory(plan.serviceId, config.connection, apiKey)
            ?: return CardActionOutcome.Refused("Invalid ${serviceLabel(plan.serviceId)} server configuration")

        val requestedAt = now().toEpochMilli()
        upsert(plan, config.connection, status = AcquisitionRecordStatus.REQUESTED, detail = "request submitted")

        val outcome = withContext(Dispatchers.IO) {
            runCatching { port.request(plan.toRequest()) }
        }.getOrElse { error ->
            (port as? Closeable)?.let { runCatching { it.close() } }

            if (error is kotlinx.coroutines.CancellationException) throw error

            val linkage = if (error is LinkageError) " detail=${error.message?.take(160)}" else ""
            onDiagnostic(
                "acquisition-request service=${plan.serviceId} status=error" +
                    " error=${error::class.simpleName}$linkage",
            )
            upsert(plan, config.connection, status = AcquisitionRecordStatus.UNKNOWN, detail = "request outcome unknown")
            return CardActionOutcome.Done
        }
        (port as? Closeable)?.let { runCatching { it.close() } }

        onDiagnostic(
            "acquisition-request service=${plan.serviceId} status=${outcome.status.name}" +
                " searchSubmitted=${outcome.searchSubmitted}",
        )
        upsert(plan, config.connection, status = statusOf(outcome), detail = outcome.reason, outcome = outcome)
        return CardActionOutcome.Done
    }

    fun observeForeground() {
        scope.launch { observeNow() }
    }

    suspend fun observeNow() {
        val profileId = profileScope.activeProfileId
        val records = store.read(profileId)
        if (records.isEmpty()) return
        var changed = false

        val provenTargets = LinkedHashSet<ProvenImportTarget>()
        withContext(Dispatchers.IO) {
            val grouped = records
                .filter {
                    it.serverId != null &&
                        (it.status == AcquisitionRecordStatus.ACCEPTED ||
                            it.status == AcquisitionRecordStatus.DOWNLOADED)
                }
                .groupBy { it.serviceId to it.serviceInstance }
            val updated = HashMap<String, AcquisitionRecord>()
            val atMs = now().toEpochMilli()
            for ((key, group) in grouped) {
                val (serviceId, instance) = key
                val config = connectionStore.connection(serviceId) ?: continue
                if (originOf(config.connection.url) != instance) continue
                val apiKey = connectionStore.apiKey(serviceId) ?: continue
                val port = portFactory(serviceId, config.connection, apiKey) ?: continue

                var importProven = false
                try {
                    val refs = group.mapNotNull { it.toRef() }
                    if (refs.isNotEmpty()) {
                        val read = runCatching { port.observe(refs) }

                        onDiagnostic(
                            if (read.isFailure) {
                                "observe service=$serviceId refs=${refs.size} read=FAILED" +
                                    "(${read.exceptionOrNull()?.let { it::class.simpleName }})"
                            } else {
                                val rows = read.getOrNull().orEmpty()
                                "observe service=$serviceId refs=${refs.size} read=ok" +
                                    " present=${rows.count { it.filePresent }}"
                            },
                        )
                        val observations = read.getOrNull().orEmpty()
                        for (obs in observations) {
                            val matching = group.firstOrNull { it.toRef() == obs.ref } ?: continue
                            val newlyProven = !matching.filePresent && obs.filePresent
                            if (newlyProven) importProven = true
                            val next = matching.copy(
                                filePresent = obs.filePresent,

                                importProvenAtMs = when {
                                    obs.filePresent -> matching.importProvenAtMs ?: atMs
                                    else -> null
                                },

                                status = when {
                                    obs.filePresent -> AcquisitionRecordStatus.DOWNLOADED
                                    matching.status == AcquisitionRecordStatus.DOWNLOADED ->
                                        AcquisitionRecordStatus.ACCEPTED
                                    else -> matching.status
                                },
                                updatedAtMs = atMs,
                            )
                            if (next != matching) updated[matching.id] = next
                        }
                    }

                    for (record in group.filter { it.searchPending }) {
                        val base = updated[record.id] ?: record
                        if (base.status != AcquisitionRecordStatus.ACCEPTED) continue
                        val continuation = buildContinuationRequest(record) ?: continue
                        val outcome = runCatching { port.request(continuation) }.getOrNull() ?: continue
                        val next = continued(base, outcome)
                        if (next != base) updated[record.id] = next
                    }
                } finally {
                    (port as? Closeable)?.let { runCatching { it.close() } }
                }

                if (importProven) onFileProven(serviceId)

                group.map { updated[it.id] ?: it }
                    .mapNotNull { it.toProvenImportTarget() }
                    .forEach(provenTargets::add)
            }
            if (updated.isNotEmpty()) {

                storeLock.withLock {
                    val current = store.read(profileId)
                    store.write(profileId, current.map { updated[it.id] ?: it })
                }
                changed = true
            }
        }
        if (changed) bump()

        if (provenTargets.isNotEmpty()) onProvenImportTargets(provenTargets.toList())
    }

    private fun AcquisitionRecord.toProvenImportTarget(): ProvenImportTarget? {
        val provenAt = importProvenAtMs ?: return null
        val kind = when (kind) {
            AcquisitionKind.MOVIE -> ProvenImportKind.MOVIE
            AcquisitionKind.SHOW -> ProvenImportKind.SERIES
            AcquisitionKind.SEASON -> ProvenImportKind.SEASON
            AcquisitionKind.EPISODE -> ProvenImportKind.EPISODE
        }
        return ProvenImportTarget(
            titleId = titleId,
            kind = kind,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            provenAtMs = provenAt,
        )
    }

    fun hasNonTerminal(serviceId: String): Boolean =
        store.read(profileScope.activeProfileId).any { record ->
            record.serviceId == serviceId && !record.filePresent && when (record.status) {
                AcquisitionRecordStatus.REQUESTED,
                AcquisitionRecordStatus.ACCEPTED,
                AcquisitionRecordStatus.PARTIAL,
                AcquisitionRecordStatus.UNKNOWN,
                -> true
                else -> false
            }
        }

    private sealed interface Plan {
        data class Ready(val plan: Planned) : Plan
        data class Missing(val reason: String) : Plan
    }

    private sealed interface Structural {
        data class Missing(val reason: String) : Structural

        data class Needs(
            val titleId: TitleId,
            val kind: WatchlistKind,
            val acquisitionKind: AcquisitionKind,
            val seasonNumber: Int?,
            val episodeNumber: Int?,
        ) : Structural
    }

    private data class Planned(
        val kind: AcquisitionKind,
        val serviceId: String,
        val titleId: TitleId,
        val externalIds: TrackingExternalIds,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val displayTitle: String,
        val title: String?,
        val year: Int?,
    ) {
        val key: String
            get() = listOf(serviceId, kind.name, titleId.value.toString(), seasonNumber ?: "-", episodeNumber ?: "-")
                .joinToString("|")

        fun toRequest(): AcquisitionRequest = AcquisitionRequest(
            kind = kind,
            titleId = titleId,
            externalIds = externalIds,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            title = title,
            year = year,
        )
    }

    private fun structuralPlan(request: CardActionRequest): Structural = when (request.kind) {
        TitleKind.MOVIE -> {
            val movie = request.provenMovieId
            if (movie == null) {
                Structural.Missing("no proven movie identity")
            } else {
                Structural.Needs(movie, WatchlistKind.MOVIE, AcquisitionKind.MOVIE, null, null)
            }
        }
        TitleKind.SERIES -> {
            val show = request.provenShowId
            if (show == null) {
                Structural.Missing("no proven show identity")
            } else {
                Structural.Needs(show, WatchlistKind.SHOW, AcquisitionKind.SHOW, null, null)
            }
        }
        TitleKind.SEASON -> {
            val show = request.provenShowId
            val season = request.seasonNumber
            when {
                show == null -> Structural.Missing("no proven show identity")
                season == null -> Structural.Missing("no season coordinate")
                else -> Structural.Needs(show, WatchlistKind.SHOW, AcquisitionKind.SEASON, season, null)
            }
        }
        TitleKind.EPISODE -> {
            val show = request.provenShowId
            val season = request.watchKey?.seasonNumber
            val episode = request.watchKey?.episodeNumber
            when {
                show == null -> Structural.Missing("no proven show identity")
                season == null || episode == null -> Structural.Missing("no episode coordinate")
                else -> Structural.Needs(show, WatchlistKind.SHOW, AcquisitionKind.EPISODE, season, episode)
            }
        }
        TitleKind.CLIP, null -> Structural.Missing("this item cannot be requested")
    }

    private fun toPlan(
        needs: Structural.Needs,
        request: CardActionRequest,
        resolution: WatchlistAliasResolution?,
    ): Plan {
        if (resolution == null) return Plan.Missing("no proven external identifier")
        return Plan.Ready(
            Planned(
                kind = needs.acquisitionKind,
                serviceId = serviceIdFor(needs.acquisitionKind),
                titleId = resolution.titleId,
                externalIds = resolution.externalIds,
                seasonNumber = needs.seasonNumber,
                episodeNumber = needs.episodeNumber,
                displayTitle = request.displayTitle,
                title = resolution.title ?: request.displayTitle.takeIf { it.isNotBlank() },
                year = resolution.year ?: request.year,
            ),
        )
    }

    private fun resolutionOf(key: String): WatchlistAliasResolution? =
        (identityCache[key] as? CachedResolution.Found)?.resolution

    private fun identityKey(needs: Structural.Needs): String =
        "${needs.titleId.value}|${needs.kind.name}"

    private fun ensureResolution(needs: Structural.Needs) {
        val key = identityKey(needs)
        if (identityCache.containsKey(key)) return
        if (!identityInflight.add(key)) return
        scope.launch {
            val resolution = runCatching { identity.aliasesFor(needs.titleId, needs.kind) }.getOrNull()
            identityCache[key] = if (resolution == null) CachedResolution.None else CachedResolution.Found(resolution)
            identityInflight.remove(key)
            bump()
        }
    }

    private suspend fun resolvePlan(needs: Structural.Needs, request: CardActionRequest): Plan {
        val key = identityKey(needs)
        val cached = identityCache[key]
        if (cached != null) return toPlan(needs, request, (cached as? CachedResolution.Found)?.resolution)
        val resolution = withContext(Dispatchers.IO) {
            runCatching { identity.aliasesFor(needs.titleId, needs.kind) }.getOrNull()
        }
        identityCache[key] = if (resolution == null) CachedResolution.None else CachedResolution.Found(resolution)
        return toPlan(needs, request, resolution)
    }

    private suspend fun buildContinuationRequest(record: AcquisitionRecord): AcquisitionRequest? {
        val seriesTitleId = runCatching { TitleId.fromString(record.titleId) }.getOrNull() ?: return null
        val resolution = runCatching { identity.aliasesFor(seriesTitleId, WatchlistKind.SHOW) }.getOrNull()
            ?: return null
        if (resolution.externalIds.isEmpty) return null
        return AcquisitionRequest(
            kind = record.kind,
            titleId = seriesTitleId,
            externalIds = resolution.externalIds,
            seasonNumber = record.seasonNumber,
            episodeNumber = record.episodeNumber,
            title = resolution.title ?: record.displayTitle.takeIf { it.isNotBlank() },
            year = resolution.year,
        )
    }

    private fun continued(record: AcquisitionRecord, outcome: AcquisitionOutcome): AcquisitionRecord =
        record.copy(
            status = statusOf(outcome),
            titleAdded = outcome.titleAdded || record.titleAdded,
            monitored = outcome.monitored || record.monitored,
            searchSubmitted = outcome.searchSubmitted || record.searchSubmitted,
            searchPending = outcome.searchPending,
            serverId = outcome.ref?.serverId ?: record.serverId,
            detail = outcome.reason ?: record.detail,
            updatedAtMs = now().toEpochMilli(),
        )

    private fun readyAction(plan: Planned): CardAction {
        val config = connectionStore.connection(plan.serviceId)
            ?: return disabledAction("Set up ${serviceLabel(plan.serviceId)} in Settings → Jellyfin")
        return CardAction(
            id = CardActionId.ADD_TO_JELLYFIN,
            label = LABEL,
            availability = CardActionAvailability.Enabled,
            detail = statusDetail(plan, recordFor(plan, config)),
        )
    }

    private fun availableAction(): CardAction = disabledAction(AVAILABLE_REASON, AVAILABLE_REASON)

    private fun disabledAction(reason: String, detail: String? = null): CardAction = CardAction(
        id = CardActionId.ADD_TO_JELLYFIN,
        label = LABEL,
        availability = CardActionAvailability.Disabled(reason),
        detail = detail,
    )

    private fun unitTitleId(needs: Structural.Needs, request: CardActionRequest): TitleId? =
        when (needs.acquisitionKind) {
            AcquisitionKind.MOVIE -> needs.titleId
            AcquisitionKind.EPISODE -> request.titleId ?: request.watchKey?.titleId
            AcquisitionKind.SHOW, AcquisitionKind.SEASON -> null
        }

    private fun isUnitProven(needs: Structural.Needs, request: CardActionRequest): Boolean {
        val proven = availability()

        if (needs.acquisitionKind == AcquisitionKind.EPISODE) {
            val season = needs.seasonNumber
            val episode = needs.episodeNumber
            if (season != null && episode != null &&
                proven.isEpisodeProven(needs.titleId.value.toString(), season, episode)
            ) {
                return true
            }
        }
        return unitTitleId(needs, request)?.let { proven.isUnitProven(it.value.toString()) } == true
    }

    private fun recordFor(plan: Planned, config: dev.infusion.core.domain.acquisition.StoredAcquisitionConnection): AcquisitionRecord? {

        val instance = originOf(config.connection.url)
        return store.read(profileScope.activeProfileId)
            .firstOrNull { it.id == plan.key && it.serviceInstance == instance }
    }

    private fun statusDetail(plan: Planned, record: AcquisitionRecord?): String? {
        if (record == null) return null

        if (record.filePresent) return "Downloaded / imported"

        if (record.searchPending) return "Request accepted — search pending"
        return when (record.status) {
            AcquisitionRecordStatus.REQUESTED -> "Requesting…"
            AcquisitionRecordStatus.ACCEPTED -> "Request accepted"
            AcquisitionRecordStatus.DOWNLOADED -> "Downloaded / imported"
            AcquisitionRecordStatus.PARTIAL -> "Partially accepted"
            AcquisitionRecordStatus.UNSUPPORTED -> "Not supported by this server"
            AcquisitionRecordStatus.FAILED -> "Failed"
            AcquisitionRecordStatus.UNKNOWN -> "Outcome unknown"
        }
    }

    private suspend fun upsert(
        plan: Planned,
        config: AcquisitionConnection,
        status: AcquisitionRecordStatus,
        detail: String?,
        outcome: AcquisitionOutcome? = null,
    ) {
        val profileId = profileScope.activeProfileId
        val instance = outcome?.serviceInstance ?: originOf(config.url)

        storeLock.withLock {
            val existing = store.read(profileId)
            val previous = existing.firstOrNull { it.id == plan.key }
            val record = AcquisitionRecord(
                id = plan.key,
                serviceId = plan.serviceId,
                serviceInstance = instance,
                kind = plan.kind,
                titleId = plan.titleId.value.toString(),
                displayTitle = plan.displayTitle,
                seasonNumber = plan.seasonNumber,
                episodeNumber = plan.episodeNumber,
                status = status,
                titleAdded = outcome?.titleAdded ?: previous?.titleAdded ?: false,
                monitored = outcome?.monitored ?: previous?.monitored ?: false,
                searchSubmitted = outcome?.searchSubmitted ?: previous?.searchSubmitted ?: false,
                searchPending = outcome?.searchPending ?: previous?.searchPending ?: false,
                filePresent = previous?.filePresent ?: false,

                importProvenAtMs = previous?.takeIf { it.filePresent }?.importProvenAtMs,
                serverId = outcome?.ref?.serverId ?: previous?.serverId,
                detail = detail,
                updatedAtMs = now().toEpochMilli(),
            )
            store.write(profileId, existing.filterNot { it.id == plan.key } + record)
        }
        bump()
    }

    private fun statusOf(outcome: AcquisitionOutcome): AcquisitionRecordStatus = when (outcome.status) {
        AcquisitionStatus.ACCEPTED, AcquisitionStatus.ALREADY_MANAGED -> AcquisitionRecordStatus.ACCEPTED
        AcquisitionStatus.PARTIAL -> AcquisitionRecordStatus.PARTIAL
        AcquisitionStatus.FAILED, AcquisitionStatus.UNAVAILABLE -> AcquisitionRecordStatus.FAILED
        AcquisitionStatus.UNKNOWN -> AcquisitionRecordStatus.UNKNOWN
        AcquisitionStatus.UNSUPPORTED -> AcquisitionRecordStatus.UNSUPPORTED
    }

    private fun AcquisitionRecord.toRef(): AcquisitionRef? {
        val id = serverId ?: return null
        return AcquisitionRef(
            serviceId = serviceId,
            serviceInstance = serviceInstance,
            kind = kind,
            titleId = titleId,
            serverId = id,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )
    }

    private fun originOf(url: String): String = runCatching { UrlParser.parse(url).origin }.getOrDefault(url)

    private fun serviceIdFor(kind: AcquisitionKind): String =
        if (kind == AcquisitionKind.MOVIE) AcquisitionService.RADARR.id else AcquisitionService.SONARR.id

    private fun serviceLabel(serviceId: String): String =
        if (serviceId == AcquisitionService.RADARR.id) "Radarr" else "Sonarr"

    private fun bump() {
        _revision.value = _revision.value + 1
        onChanged()
    }

    private companion object {
        const val LABEL = "Add to Jellyfin"

        const val PENDING_REASON = "Checking…"

        const val AVAILABLE_REASON = "Already in Jellyfin"

        const val OBSERVE_THROTTLE_MS = 15_000L
    }
}
