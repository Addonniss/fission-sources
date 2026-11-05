package dev.infusion.source.jellyfin.catalog

import dev.infusion.core.domain.identity.CanonicalConvergenceStore
import dev.infusion.core.model.Title
import dev.infusion.core.model.TypedExternalId

suspend fun convergeMirroredIdentities(
    titles: Collection<Title>,
    convergence: CanonicalConvergenceStore,
) {
    for (title in titles) {
        val asserted = title.assertedMetadataAliases()
        if (asserted.isEmpty()) continue

        val successor = runCatching { convergence.successorOf(title.titleId) }.getOrNull()
        if (successor != null) continue
        val claims = runCatching { convergence.claims(asserted) }.getOrNull() ?: continue
        if (claims.userCorrectionConflict) continue

        val survivor = claims.claims.minByOrNull { it.declaredAtMs }?.titleId ?: continue
        if (survivor == title.titleId) continue
        runCatching { convergence.converge(title.titleId, survivor) }
    }
}

internal val METADATA_AUTHORITIES: Set<String> = setOf("tmdb", "imdb", "tvdb")

internal fun Title.assertedMetadataAliases(): List<TypedExternalId> =
    aliases.filter { it.authority in METADATA_AUTHORITIES }
