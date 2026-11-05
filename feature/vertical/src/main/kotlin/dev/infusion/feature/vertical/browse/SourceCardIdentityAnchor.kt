package dev.infusion.feature.vertical.browse

import dev.infusion.core.domain.anchor.CanonicalRootTitleStore
import dev.infusion.core.domain.metadata.MediaIdentityStore
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.identity.MappingOrigin
import dev.infusion.core.model.metadata.MediaIdentity

class SourceCardIdentityAnchor(

    private val rootTitleStore: CanonicalRootTitleStore? = null,

    private val identityStore: MediaIdentityStore? = null,
) {

    suspend fun anchor(
        identity: MediaIdentity,
        titleId: TitleId,
        kind: TitleKind?,
        displayTitle: String,
        year: Int?,
    ) {
        val store = identityStore ?: return
        val roots = rootTitleStore ?: return
        val aliases = identity.externalIds.typed()
        if (aliases.isEmpty()) return
        runCatching {

            if (store.externalIdsFor(titleId).isNotEmpty()) return@runCatching
            write(identity, titleId, kind, displayTitle, year)
        }
    }

    internal suspend fun anchorAll(entries: List<SourceCardIdentityToAnchor>) {
        val store = identityStore ?: return
        val roots = rootTitleStore ?: return
        if (entries.isEmpty()) return
        val resolvable = entries.filter { it.identity.externalIds.typed().isNotEmpty() }
        if (resolvable.isEmpty()) return
        val anchored = runCatching {
            store.externalIdsForAll(resolvable.map { it.titleId }.distinct())
        }.getOrElse { return }

        val written = HashSet<TitleId>()
        for (entry in resolvable) {
            if (entry.titleId in written) continue
            if (anchored[entry.titleId].orEmpty().isNotEmpty()) continue
            val done = runCatching { write(entry.identity, entry.titleId, entry.kind, entry.displayTitle, entry.year) }
                .getOrDefault(false)
            if (done) written += entry.titleId
        }
    }

    private suspend fun write(
        identity: MediaIdentity,
        titleId: TitleId,
        kind: TitleKind?,
        displayTitle: String,
        year: Int?,
    ): Boolean {
        val store = identityStore ?: return false
        val roots = rootTitleStore ?: return false
        val aliases = identity.externalIds.typed()
        if (aliases.isEmpty()) return false
        val rootKind = if (kind == TitleKind.SERIES) TitleKind.SERIES else TitleKind.MOVIE
        val present = roots.materializeRoot(titleId, rootKind, displayTitle, year)
        if (!present) return false
        for (alias in aliases) {
            store.bind(alias, titleId, MappingOrigin.CONNECTOR)
        }
        return true
    }
}

internal data class SourceCardIdentityToAnchor(
    val identity: MediaIdentity,
    val titleId: TitleId,
    val kind: TitleKind?,
    val displayTitle: String,
    val year: Int?,
)
