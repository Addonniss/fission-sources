package dev.infusion.source.aggregation.resolve

import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.Reachability
import dev.infusion.source.aggregation.AddonRegistryPort
import dev.infusion.source.aggregation.AddonRuntimeDescriptor
import dev.infusion.source.aggregation.CanonicalTitleLookup
import dev.infusion.source.aggregation.resource.AddonStreamRepository
import dev.infusion.source.stremio.StremioResult
import dev.infusion.source.stremio.wire.StremioStreamCandidate
import java.util.concurrent.ConcurrentHashMap

class CandidateRefresher(
    private val registry: AddonRegistryPort,
    private val streamRepository: AddonStreamRepository,
    private val lookup: CanonicalTitleLookup,
    private val viewer: dev.infusion.core.model.profile.Profile,
    private val primary: dev.infusion.core.model.profile.Profile,
    private val providerTimeoutMs: Long = 10_000,
) {

    private val refreshed = ConcurrentHashMap<String, PlayableCandidate>()

    suspend fun refresh(candidate: PlayableCandidate): PlayableCandidate =
        refreshed[candidate.candidateId.value.toString()] ?: run {
            val result = doRefresh(candidate)
            refreshed[candidate.candidateId.value.toString()] = result
            result
        }

    private suspend fun doRefresh(candidate: PlayableCandidate): PlayableCandidate {

        val runtime = registry.runtimesFor(viewer, primary)
            .firstOrNull { it.sourceId == candidate.sourceId }
            ?: return candidate.copy(reachability = Reachability.UNREACHABLE)

        val nativeId = candidate.nativeItemId.nativeId

        val kind = lookup.byId(candidate.titleId)?.kind
        val type = when (kind) {
            dev.infusion.core.model.TitleKind.MOVIE ->
                dev.infusion.source.stremio.policy.StremioContentType.MOVIE
            dev.infusion.core.model.TitleKind.SERIES,
            dev.infusion.core.model.TitleKind.SEASON,
            dev.infusion.core.model.TitleKind.EPISODE,
            -> dev.infusion.source.stremio.policy.StremioContentType.SERIES
            null, dev.infusion.core.model.TitleKind.CLIP ->
                dev.infusion.source.stremio.policy.StremioContentType.MOVIE
        }

        val result = kotlinx.coroutines.withTimeoutOrNull(providerTimeoutMs) {
            streamRepository.streamsFrom(runtime.addonId, type, nativeId)
        }
        return when (result) {
            null -> candidate.copy(reachability = Reachability.UNREACHABLE)
            is StremioResult.Err -> candidate.copy(reachability = Reachability.UNREACHABLE)
            is StremioResult.Ok -> {
                val factory = AddonCandidateFactory(
                    titleId = candidate.titleId,
                    editionId = candidate.editionId,
                    sourceId = runtime.sourceId,
                    nativeItemId = nativeId,
                    addonDisplayName = runtime.displayName,
                )

                result.value.streams
                    .filterNot { it is StremioStreamCandidate.Notice }
                    .map(factory::toCandidate)
                    .firstOrNull { it.familyId == candidate.familyId }
                    ?: candidate.copy(reachability = Reachability.UNREACHABLE)
            }
        }
    }
}
