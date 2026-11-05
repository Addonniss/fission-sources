package dev.infusion.core.database.watchlist

import dev.infusion.core.database.dao.ExternalIdDao
import dev.infusion.core.database.dao.TitleDao
import dev.infusion.core.domain.anchor.CanonicalRootTitleStore
import dev.infusion.core.domain.metadata.MediaIdentityStore
import dev.infusion.core.domain.watchlist.ResolvedRemote
import dev.infusion.core.domain.watchlist.WatchlistIdentityCompletion
import dev.infusion.core.domain.watchlist.WatchlistKind
import dev.infusion.core.domain.watchlist.WatchlistRemoteKind
import dev.infusion.core.domain.watchlist.WatchlistRemoteRecord
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.identity.MappingOrigin
import dev.infusion.core.model.metadata.CanonicalTitleIdentity

class RoomWatchlistIdentityCompletion(
    private val titleDao: TitleDao,
    private val externalIdDao: ExternalIdDao,
    private val identityStore: MediaIdentityStore,
    private val rootTitleStore: CanonicalRootTitleStore,
) : WatchlistIdentityCompletion {

    override suspend fun complete(record: WatchlistRemoteRecord): ResolvedRemote? {
        val showLike = record.remoteKind != WatchlistRemoteKind.MOVIE

        val aliases = if (showLike) record.seriesExternalIds ?: record.externalIds else record.externalIds
        val typed = aliases.typed()
        if (typed.isEmpty()) return null

        if (typed.any { externalIdDao.get(it.authority, it.value) != null }) return null
        val canonicalAlias = DERIVATION_ORDER.firstNotNullOfOrNull { authority ->
            typed.firstOrNull { it.authority == authority }
        } ?: return null
        val titleId = CanonicalTitleIdentity.of(canonicalAlias)
        val kind = if (showLike) WatchlistKind.SHOW else WatchlistKind.MOVIE
        val statedTitle = record.title?.trim().orEmpty()
        val materialized = rootTitleStore.materializeRoot(
            titleId = titleId,
            kind = if (showLike) TitleKind.SERIES else TitleKind.MOVIE,
            displayTitle = statedTitle,
            year = record.year,
        )
        if (!materialized) return null
        for (alias in typed) {
            identityStore.bind(alias, titleId, MappingOrigin.CONNECTOR)
        }
        val row = titleDao.getById(titleId.value.toString())
        val title = statedTitle.takeIf { it.isNotBlank() } ?: row?.primaryTitle?.takeIf { it.isNotBlank() }
            ?: return null
        return ResolvedRemote(
            titleId = titleId,
            kind = kind,
            remoteKind = record.remoteKind,
            remoteRef = record.remoteRef,
            title = title,
            year = record.year ?: row?.year,
            artworkLocator = record.artworkLocator,
            artworkPolicy = ArtworkPolicy.POSTER,
            landscapeLocator = null,
            remoteAddedAt = record.remoteAddedAt,
        )
    }

    private companion object {

        val DERIVATION_ORDER = listOf("imdb", "tmdb", "tvdb")
    }
}
