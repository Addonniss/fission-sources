package dev.infusion.core.metadata

import dev.infusion.core.domain.metadata.MediaIdentityResolver
import dev.infusion.core.domain.metadata.MediaIdentityStore
import dev.infusion.core.domain.metadata.MetadataFailureException
import dev.infusion.core.domain.metadata.MetadataIdentityHint
import dev.infusion.core.domain.metadata.MetadataLocale
import dev.infusion.core.domain.metadata.MetadataProvider
import dev.infusion.core.domain.identity.CanonicalConvergenceStore
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.identity.CanonicalIdentityConvergence
import dev.infusion.core.model.identity.MappingOrigin
import dev.infusion.core.model.metadata.CanonicalTitleIdentity
import dev.infusion.core.model.metadata.ExternalIds
import dev.infusion.core.model.metadata.MediaIdentity
import dev.infusion.core.model.metadata.MediaType
import java.text.Normalizer

class DefaultMediaIdentityResolver(
    private val provider: MetadataProvider,
    private val store: MediaIdentityStore,

    private val convergence: CanonicalConvergenceStore? = null,
) : MediaIdentityResolver {

    override suspend fun resolve(
        titleId: TitleId,
        type: MediaType,
        locale: MetadataLocale,
    ): MediaIdentity? {

        if (!ownerKindAgrees(titleId, type)) return null
        val aliases = runCatching { store.externalIdsFor(titleId) }.getOrDefault(emptyList())
        val base = MediaIdentity(
            type = type,
            titleId = titleId,
            externalIds = ExternalIds.fromTyped(aliases),
        )
        return resolveIdentity(base, locale)
    }

    override suspend fun resolveIdentity(identity: MediaIdentity, locale: MetadataLocale): MediaIdentity {
        if (identity.hasCanonicalMetadataAnchor) return identity
        val resolved = resolveFromAliases(identity, locale) ?: return identity
        persist(identity, resolved)
        return resolved
    }

    override suspend fun resolveHint(hint: MetadataIdentityHint, locale: MetadataLocale): MediaIdentity {
        val identity = hint.toIdentity()
        if (identity.hasCanonicalMetadataAnchor) return identity

        resolveFromAliases(identity, locale)?.let { resolved ->
            if (resolved.hasCanonicalMetadataAnchor) return resolved
        }

        val fromTitle = resolveFromTitleAndYear(hint, locale) ?: return identity
        return identity.copy(externalIds = identity.externalIds.merge(fromTitle.externalIds) ?: fromTitle.externalIds)
    }

    override suspend fun titleIdFor(identity: MediaIdentity): TitleId? {

        identity.titleId?.let { known ->
            if (ownerKindAgrees(known, identity.type)) return canonicalId(known)
        }

        val aliases = identity.externalIds.typed()
        canonicalOwnerOf(aliases)?.let { owned ->
            if (ownerKindAgrees(owned, identity.type)) return owned

            return kindScopedTitleId(identity)
        }

        return deriveTitleId(identity)
    }

    override suspend fun titleIdsFor(identities: List<MediaIdentity>): List<TitleId?> {
        val convergenceStore = convergence ?: return identities.map { titleIdFor(it) }
        if (identities.size < 2) return identities.map { titleIdFor(it) }
        return runCatching { batchTitleIds(identities, convergenceStore) }
            .getOrElse { identities.map { titleIdFor(it) } }
    }

    private suspend fun batchTitleIds(
        identities: List<MediaIdentity>,
        convergenceStore: CanonicalConvergenceStore,
    ): List<TitleId?> {

        val knownOwners = identities.mapNotNull { it.titleId }.distinct()
        val successors: Map<TitleId, TitleId> =
            if (knownOwners.isEmpty()) emptyMap() else convergenceStore.successorsOf(knownOwners)
        val knownKinds: Map<TitleId, dev.infusion.core.model.TitleKind?> =
            if (knownOwners.isEmpty()) {
                emptyMap()
            } else {
                store.canonicalKindsOf(knownOwners.map { successors[it] ?: it }.distinct())
            }
        val answers = arrayOfNulls<TitleId>(identities.size)
        val pending = ArrayList<Int>(identities.size)
        identities.forEachIndexed { index, identity ->
            val known = identity.titleId
            if (known != null && kindAgrees(knownKinds[successors[known] ?: known], identity.type)) {
                answers[index] = successors[known] ?: known
            } else {
                pending += index
            }
        }
        if (pending.isEmpty()) return answers.toList()

        val claims = convergenceStore.claimsOf(pending.map { identities[it].externalIds.typed() })

        pending.forEachIndexed { index, _ ->
            val claimed = claims[index]
            val plan = CanonicalIdentityConvergence.plan(claimed.claims, claimed.userCorrectionConflict)
            if (plan != null) {
                for (loser in plan.losers) {
                    runCatching { convergenceStore.converge(loser, plan.survivor) }
                }
            }
        }
        val owned = pending.indices.map { index ->
            CanonicalIdentityConvergence.survivor(claims[index].claims)?.titleId
        }

        val ownedKinds: Map<TitleId, dev.infusion.core.model.TitleKind?> =
            owned.filterNotNull().distinct().let { ids ->
                if (ids.isEmpty()) emptyMap() else store.canonicalKindsOf(ids)
            }
        pending.forEachIndexed { index, at ->
            val identity = identities[at]
            val owner = owned[index]
            answers[at] = when {
                owner == null -> deriveTitleId(identity)
                kindAgrees(ownedKinds[owner], identity.type) -> owner
                else -> kindScopedTitleId(identity)
            }
        }
        return answers.toList()
    }

    private suspend fun ownerKindAgrees(titleId: TitleId, type: MediaType): Boolean {
        val kind = runCatching { store.canonicalKindOf(titleId) }.getOrNull() ?: return true

        return kindAgrees(kind, type)
    }

    private fun kindAgrees(kind: dev.infusion.core.model.TitleKind?, type: MediaType): Boolean =
        when (type) {
            MediaType.MOVIE -> kind != dev.infusion.core.model.TitleKind.SERIES
            MediaType.SHOW -> kind != dev.infusion.core.model.TitleKind.MOVIE
        }

    private fun kindScopedTitleId(identity: MediaIdentity): TitleId? =
        ID_DERIVATION_ORDER.firstNotNullOfOrNull { authority ->
            identity.externalIds.typed()
                .firstOrNull { it.authority == authority }
                ?.let { external -> CanonicalTitleIdentity.kindScoped(external, identity.type) }
        }

    private suspend fun canonicalOwnerOf(aliases: List<TypedExternalId>): TitleId? {
        if (aliases.isEmpty()) return null
        val convergenceStore = convergence ?: return firstOwnerOf(aliases)
        val claimed = runCatching { convergenceStore.claims(aliases) }.getOrNull()
            ?: return firstOwnerOf(aliases)
        val plan = CanonicalIdentityConvergence.plan(claimed.claims, claimed.userCorrectionConflict)
        if (plan != null) {
            for (loser in plan.losers) {

                runCatching { convergenceStore.converge(loser, plan.survivor) }
            }
        }
        return CanonicalIdentityConvergence.survivor(claimed.claims)?.titleId
    }

    private suspend fun firstOwnerOf(aliases: List<TypedExternalId>): TitleId? {
        for (external in aliases) {
            runCatching { store.titleIdFor(external) }.getOrNull()?.let { return it }
        }
        return null
    }

    private suspend fun canonicalId(titleId: TitleId): TitleId {
        val convergenceStore = convergence ?: return titleId
        return runCatching { convergenceStore.successorOf(titleId) }.getOrNull() ?: titleId
    }

    private suspend fun resolveFromAliases(
        identity: MediaIdentity,
        locale: MetadataLocale,
    ): MediaIdentity? {
        val attempts = buildList {
            identity.imdbId?.let { add(TypedExternalId(ExternalIds.AUTHORITY_IMDB, it)) }
            identity.tvdbId?.let { add(TypedExternalId(ExternalIds.AUTHORITY_TVDB, it.toString())) }
        }
        for (external in attempts) {
            val resolved = lookup(external, identity.type, locale) ?: continue
            val merged = identity.externalIds.merge(resolved) ?: continue
            val withTitle = identity.titleId ?: withTitleIdFor(identity, merged)
            return identity.copy(titleId = withTitle, externalIds = merged)
        }
        return null
    }

    private suspend fun lookup(
        external: TypedExternalId,
        type: MediaType,
        locale: MetadataLocale,
    ): ExternalIds? = try {
        provider.resolveExternalId(external, type, locale)
    } catch (_: MetadataFailureException) {
        null
    } catch (_: Exception) {
        null
    }

    private suspend fun withTitleIdFor(identity: MediaIdentity, merged: ExternalIds): TitleId? {

        return titleIdFor(MediaIdentity(type = identity.type, titleId = identity.titleId, externalIds = merged))
    }

    private fun deriveTitleId(identity: MediaIdentity): TitleId? =
        ID_DERIVATION_ORDER.firstNotNullOfOrNull { authority ->
            identity.externalIds.typed()
                .firstOrNull { it.authority == authority }
                ?.let(CanonicalTitleIdentity::of)
        }

    private suspend fun resolveFromTitleAndYear(
        hint: MetadataIdentityHint,
        locale: MetadataLocale,
    ): MediaIdentity? {
        val title = hint.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val candidates = try {
            provider.search(title, hint.year, hint.type, locale)
        } catch (_: MetadataFailureException) {
            return null
        } catch (_: Exception) {
            return null
        }
        val wanted = normalizeTitle(title)
        val matches = candidates.filter { candidate ->
            normalizeTitle(candidate.title) == wanted &&
                (hint.year == null || candidate.year == hint.year)
        }
        val unique = matches.singleOrNull() ?: return null
        return hint.toIdentity().copy(externalIds = unique.identity.externalIds)
    }

    private suspend fun persist(original: MediaIdentity, resolved: MediaIdentity) {
        val titleId = resolved.titleId ?: return
        val known = original.externalIds.typed().toSet()
        for (external in resolved.externalIds.typed()) {
            if (external in known) continue
            runCatching { store.bind(external, titleId, MappingOrigin.CONNECTOR) }
        }
    }

    private companion object {

        val ID_DERIVATION_ORDER = listOf("imdb", "tmdb", "tvdb")

        fun normalizeTitle(raw: String): String {
            val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKD)
                .replace(Regex("\\p{Mn}+"), "")
                .lowercase()
            val stripped = decomposed.replace(Regex("[^a-z0-9\\s]"), " ").trim()
            val collapsed = stripped.replace(Regex("\\s+"), " ")
            return collapsed.removePrefix("the ").removePrefix("a ").removePrefix("an ").trim()
        }
    }
}
