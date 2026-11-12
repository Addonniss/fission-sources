package dev.infusion.source.aggregation.match

import dev.infusion.core.domain.identity.IdentityMappingStore
import dev.infusion.core.model.Title
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.identity.IdentityMapping
import dev.infusion.core.model.identity.MappingOrigin
import dev.infusion.core.model.identity.MappingStatus
import dev.infusion.core.sync.IdentityMappingRules
import dev.infusion.core.sync.IdentityMappingService
import dev.infusion.source.aggregation.CanonicalTitleLookup

class CanonicalMatcher(
    private val lookup: CanonicalTitleLookup,
    private val identityStore: IdentityMappingStore,
    private val service: IdentityMappingService = IdentityMappingService(identityStore),

    private val materializer: CanonicalTitleMaterializer = CanonicalTitleMaterializer.NOOP,
) {

    private val exactNameYearConfidence = 0.9
    private val nameOnlyConfidence = 0.6

    fun match(item: AddonMetaItem, addonAliasAuthority: String): AddonMatch {
        val extracted = ExternalIdExtractor.extract(item.nativeItemId)

        for (external in extracted) {
            val active = identityStore.activeByExternal(external)
            if (active != null) {
                val title = lookup.byId(active.canonicalTitleId)
                if (title != null) {
                    return AddonMatch.Matched(
                        titleId = active.canonicalTitleId,
                        confidence = 1.0,
                        evidence = listOf(MatchEvidence.TypedExternalId(external, 1.0)),
                        mapping = active,
                        isDurable = true,
                    )
                }
            }

            val proposed = identityStore.byExternal(external)
                .firstOrNull { it.status == MappingStatus.PROPOSED }
            if (proposed != null) {
                val title = lookup.byId(proposed.canonicalTitleId)
                if (title != null) {
                    return AddonMatch.Matched(
                        titleId = proposed.canonicalTitleId,
                        confidence = proposed.confidence,
                        evidence = listOf(MatchEvidence.TypedExternalId(external, proposed.confidence)),
                        mapping = proposed,
                        isDurable = false,
                    )
                }
            }

            val byAlias = lookup.byExternalId(external)
            val strong = byAlias.maxByOrNull { it.titleId.value.mostSignificantBits }
            if (strong != null) {
                val mapping = service.propose(
                    external = external,
                    titleId = strong.titleId,
                    origin = MappingOrigin.CONNECTOR,
                    confidence = IdentityMappingRules.STRONG_LINK_CONFIDENCE,
                )
                val durable = mapping.status == MappingStatus.ACTIVE
                return AddonMatch.Matched(
                    titleId = strong.titleId,
                    confidence = 1.0,
                    evidence = listOf(
                        MatchEvidence.TypedExternalId(external, 1.0),
                        MatchEvidence.AddonAlias(external, strong.titleId),
                    ),
                    mapping = mapping,
                    isDurable = durable,
                )
            }

            val rootKind = rootKindOf(item.catalogType)
            if (rootKind != null) {
                val materialized = materializer.materializeExact(item, external, rootKind)
                if (materialized != null) {
                    val mapping = service.propose(
                        external = external,
                        titleId = materialized.titleId,
                        origin = MappingOrigin.CONNECTOR,
                        confidence = IdentityMappingRules.STRONG_LINK_CONFIDENCE,
                    )
                    val durable = mapping.status == MappingStatus.ACTIVE
                    return AddonMatch.Matched(
                        titleId = materialized.titleId,
                        confidence = 1.0,
                        evidence = listOf(
                            MatchEvidence.TypedExternalId(external, 1.0),
                            MatchEvidence.AddonAlias(external, materialized.titleId),
                        ),
                        mapping = mapping,
                        isDurable = durable,
                    )
                }
            }
        }

        val heuristic = nameYearMatch(item, addonAliasAuthority)
        if (heuristic != null) {
            return heuristic
        }

        return AddonMatch.Unmatched
    }

    private fun rootKindOf(type: dev.infusion.source.stremio.policy.StremioContentType): dev.infusion.core.model.TitleKind? =
        when (type) {
            dev.infusion.source.stremio.policy.StremioContentType.MOVIE -> dev.infusion.core.model.TitleKind.MOVIE
            dev.infusion.source.stremio.policy.StremioContentType.SERIES,
            dev.infusion.source.stremio.policy.StremioContentType.TV,
            -> dev.infusion.core.model.TitleKind.SERIES

            dev.infusion.source.stremio.policy.StremioContentType.CHANNEL,
            dev.infusion.source.stremio.policy.StremioContentType.ALL,
            dev.infusion.source.stremio.policy.StremioContentType.UNKNOWN,
            -> null
        }

    private fun nameYearMatch(item: AddonMetaItem, addonAliasAuthority: String): AddonMatch? {
        val normalized = normalizeName(item.displayTitle)
        if (normalized.isBlank()) return null
        val candidates = lookup.byNameAndYear(normalized, item.year)
            .filter { normalizeName(it.displayTitle) == normalized }
        if (candidates.isEmpty()) return null

        val candidate = candidates.minByOrNull { it.titleId.value.mostSignificantBits } ?: return null
        val yearMatch = item.year != null && candidate.year == item.year
        val confidence = if (yearMatch) exactNameYearConfidence else nameOnlyConfidence

        val external = TypedExternalId(
            addonAliasAuthority,
            item.nativeItemId.take(256),
        )
        val mapping = service.propose(
            external = external,
            titleId = candidate.titleId,
            origin = MappingOrigin.CONNECTOR,
            confidence = confidence,
        )
        return AddonMatch.Matched(
            titleId = candidate.titleId,
            confidence = confidence,
            evidence = listOf(
                MatchEvidence.NameYear(
                    normalizedName = normalized,
                    year = item.year,
                    candidateTitleId = candidate.titleId,
                    confidence = confidence,
                ),
            ),
            mapping = mapping,
            isDurable = mapping.status == MappingStatus.ACTIVE,
        )
    }

    companion object {

        fun normalizeName(name: String): String = name
            .lowercase()
            .trim()
            .replace(Regex("\\s+"), " ")
            .removePrefix("the ")
            .removePrefix("a ")
            .removePrefix("an ")
            .trimEnd('.', '!', '?', ',', ':', ';')
            .trim()
    }
}
