package dev.infusion.source.aggregation.anchor

import dev.infusion.core.domain.anchor.CanonicalDestinationAnchor
import dev.infusion.core.domain.anchor.CanonicalDestinationIdentity
import dev.infusion.core.domain.anchor.CanonicalRootTitleStore
import dev.infusion.core.domain.metadata.MediaIdentityStore
import dev.infusion.core.model.Availability
import dev.infusion.core.model.AvailabilityId
import dev.infusion.core.model.AvailabilityKind
import dev.infusion.core.model.Provenance
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.identity.CanonicalIdentityConvergence
import dev.infusion.core.model.identity.MappingOrigin
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.source.aggregation.AddonAvailabilityStore
import dev.infusion.source.aggregation.AddonRegistryPort
import dev.infusion.source.aggregation.CanonicalTitleLookup
import dev.infusion.source.aggregation.resource.AddonMetaRepository
import dev.infusion.source.aggregation.stremioWireId
import dev.infusion.source.stremio.policy.StremioContentType
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class CanonicalDestinationAnchorService(
    private val lookup: CanonicalTitleLookup,
    private val rootTitleStore: CanonicalRootTitleStore,
    private val aliasStore: MediaIdentityStore,
    private val availabilityStore: AddonAvailabilityStore,
    private val registry: AddonRegistryPort,
    private val metaRepository: AddonMetaRepository,
    private val viewer: dev.infusion.core.model.profile.Profile,
    private val primary: dev.infusion.core.model.profile.Profile,

    private val convergence: dev.infusion.core.domain.identity.CanonicalConvergenceStore? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Instant = Instant::now,
) : CanonicalDestinationAnchor {

    private val signals = java.util.concurrent.ConcurrentHashMap<TitleId, MutableStateFlow<Boolean>>()

    private val outcomes = java.util.concurrent.ConcurrentHashMap<TitleId, Boolean>()

    private val lock = Mutex()

    override fun materialized(titleId: TitleId): StateFlow<Boolean> =
        signals.getOrPut(titleId) { MutableStateFlow(false) }.asStateFlow()

    override suspend fun anchor(destination: CanonicalDestinationIdentity): Boolean {
        if (outcomes[destination.titleId] == true) return true
        return lock.withLock {
            if (outcomes[destination.titleId] == true) return@withLock true

            val outcome = withContext(ioDispatcher) {
                runCatching { anchorOnce(destination) }.getOrDefault(false)
            }

            if (outcome) {
                outcomes[destination.titleId] = true
                signals.getOrPut(destination.titleId) { MutableStateFlow(false) }.value = true
            }
            outcome
        }
    }

    private suspend fun anchorOnce(destination: CanonicalDestinationIdentity): Boolean {
        val kind = kindOf(destination.mediaType)
        val aliases = destination.identity.externalIds.typed().distinct()

        val titleId = canonicalDestinationId(destination.titleId, aliases)

        val existing = withContext(ioDispatcher) { lookup.byId(titleId) }
        if (existing != null &&
            aliases.all { alias -> existing.aliases.contains(alias) } &&
            hasAddOnContext(titleId)
        ) {
            return true
        }
        if (aliases.isEmpty()) return false

        if (existing == null) {
            val displayTitle = destination.displayTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val materialized = withContext(ioDispatcher) {
                rootTitleStore.materializeRoot(
                    titleId = titleId,
                    kind = kind,
                    displayTitle = displayTitle,
                    year = destination.year,
                )
            }
            if (!materialized) return false
        }

        withContext(ioDispatcher) {
            for (alias in aliases) {
                runCatching {
                    aliasStore.bind(alias, titleId, MappingOrigin.CONNECTOR)
                }
            }
        }
        if (!hasAddOnContext(titleId)) establishAddOnContext(titleId, kind, aliases)
        return true
    }

    private suspend fun canonicalDestinationId(
        routeTitleId: TitleId,
        aliases: List<TypedExternalId>,
    ): TitleId {
        val store = convergence ?: return routeTitleId
        val claimed = runCatching { store.claims(aliases) }.getOrNull() ?: return routeTitleId
        val plan = CanonicalIdentityConvergence.plan(claimed.claims, claimed.userCorrectionConflict)
        if (plan != null) {
            for (loser in plan.losers) {
                runCatching { store.converge(loser, plan.survivor) }
            }
            return plan.survivor
        }
        return runCatching { store.successorOf(routeTitleId) }.getOrNull() ?: routeTitleId
    }

    private suspend fun hasAddOnContext(titleId: TitleId): Boolean = withContext(ioDispatcher) {
        runCatching {
            val exposed = registry.runtimesFor(viewer, primary).map { it.sourceId }.toSet()
            if (exposed.isEmpty()) return@runCatching false
            availabilityStore.byTitle(titleId)
                .any { it.kind == AvailabilityKind.ADDON && it.sourceId in exposed }
        }.getOrDefault(false)
    }

    private suspend fun establishAddOnContext(
        titleId: TitleId,
        kind: TitleKind,
        aliases: List<TypedExternalId>,
    ) {
        val type = when (kind) {
            TitleKind.MOVIE -> StremioContentType.MOVIE
            else -> StremioContentType.SERIES
        }
        val runtimes = registry.runtimesFor(viewer, primary)
        for (alias in aliases.sortedWith(
            compareBy(
                { AUTHORITY_ORDER.indexOf(it.authority).let { i -> if (i < 0) AUTHORITY_ORDER.size else i } },
                { it.value },
            ),
        )) {
            val wireId = wireId(alias) ?: continue
            val result = withContext(ioDispatcher) {
                runCatching { metaRepository.primary(type, wireId, null) }.getOrNull()
            } ?: continue
            val winnerId = result.addonId ?: continue
            if (result.response == null) continue
            val runtime = runtimes.firstOrNull { it.addonId == winnerId } ?: continue
            val observedAt = now()
            availabilityStore.upsert(
                Availability(
                    availabilityId = AvailabilityId(
                        UUID.nameUUIDFromBytes(
                            "anchor:${runtime.addonId}:$wireId".toByteArray(),
                        ),
                    ),
                    titleId = titleId,
                    editionId = null,
                    sourceId = runtime.sourceId,
                    nativeResourceId = wireId,
                    kind = AvailabilityKind.ADDON,
                    observedAt = observedAt,
                    provenance = Provenance(
                        sourceId = runtime.sourceId,
                        protocolVersion = "stremio/1",
                        retrievedAt = observedAt,
                        normalizedBy = "CanonicalDestinationAnchor",
                    ),
                ),
            )
            return
        }
    }

    private fun kindOf(mediaType: MediaType): TitleKind = when (mediaType) {
        MediaType.MOVIE -> TitleKind.MOVIE
        MediaType.SHOW -> TitleKind.SERIES
    }

    private fun wireId(alias: TypedExternalId): String? = alias.stremioWireId()

    private companion object {
        const val AUTHORITY_IMDB = "imdb"
        const val AUTHORITY_TMDB = "tmdb"
        const val AUTHORITY_TVDB = "tvdb"

        val AUTHORITY_ORDER = listOf(AUTHORITY_IMDB, AUTHORITY_TMDB, AUTHORITY_TVDB)
    }
}
