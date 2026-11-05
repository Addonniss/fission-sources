package dev.infusion.core.database.watchlist

import dev.infusion.core.database.dao.EditionDao
import dev.infusion.core.database.dao.ExternalIdDao
import dev.infusion.core.database.dao.TitleDao
import dev.infusion.core.database.entity.ExternalIdEntity
import dev.infusion.core.database.entity.TitleEntity
import dev.infusion.core.database.model.TitleKind
import dev.infusion.core.database.store.TitleSupersession
import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.watchlist.ResolvedRemote
import dev.infusion.core.domain.watchlist.WatchlistAliasResolution
import dev.infusion.core.domain.watchlist.WatchlistIdentityResolver
import dev.infusion.core.domain.watchlist.WatchlistKind
import dev.infusion.core.domain.watchlist.WatchlistRemoteKind
import dev.infusion.core.domain.watchlist.WatchlistRemoteRecord
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId

class RoomWatchlistIdentityResolver(
    private val titleDao: TitleDao,
    private val externalIdDao: ExternalIdDao,
    private val supersession: TitleSupersession,
    private val editionDao: EditionDao? = null,
) : WatchlistIdentityResolver {

    override fun resolve(record: WatchlistRemoteRecord): ResolvedRemote? {
        val showLike = record.remoteKind != WatchlistRemoteKind.MOVIE
        val aliasSet = if (showLike) record.seriesExternalIds ?: record.externalIds else record.externalIds
        if (aliasSet.isEmpty) return null
        val titleId = singleTitleFor(aliasSet) ?: return null
        val title = titleDao.getById(titleId.value.toString()) ?: return null
        val expected = if (showLike) TitleKind.SERIES else TitleKind.MOVIE
        if (title.kind != expected) return null
        return ResolvedRemote(
            titleId = titleId,
            kind = if (showLike) WatchlistKind.SHOW else WatchlistKind.MOVIE,
            remoteKind = record.remoteKind,
            remoteRef = record.remoteRef,
            title = record.title?.takeIf { it.isNotBlank() } ?: title.primaryTitle,
            year = record.year ?: title.year,
            artworkLocator = record.artworkLocator,
            artworkPolicy = ArtworkPolicy.POSTER,
            landscapeLocator = null,
            remoteAddedAt = record.remoteAddedAt,
        )
    }

    override fun aliasesFor(titleId: TitleId, kind: WatchlistKind): WatchlistAliasResolution? {
        val survivor = supersession.survivorOf(titleId)
        val title = titleDao.getById(survivor.value.toString()) ?: return null
        val expected = if (kind == WatchlistKind.MOVIE) TitleKind.MOVIE else TitleKind.SERIES
        if (title.kind != expected) return null
        val ids = TrackingExternalIds.fromTyped(aliasesOf(title))
        if (ids.isEmpty) return null
        return WatchlistAliasResolution(
            titleId = survivor,
            kind = kind,
            externalIds = ids,
            title = title.primaryTitle.takeIf { it.isNotBlank() },
            year = title.year,
        )
    }

    private fun singleTitleFor(ids: TrackingExternalIds): TitleId? {
        val candidates = LinkedHashSet<TitleId>()
        for (alias in ids.typed()) {
            titleIdFor(alias)?.let { candidates += supersession.survivorOf(it) }
        }
        return candidates.singleOrNull()
    }

    private fun titleIdFor(alias: TypedExternalId): TitleId? {
        val row: ExternalIdEntity = externalIdDao.get(alias.authority, alias.value) ?: return null
        row.titleId?.let { return parse(it) }
        val editionId = row.editionId ?: return null
        val edition = editionDao?.getById(editionId) ?: return null
        return parse(edition.titleId)
    }

    private fun aliasesOf(title: TitleEntity): List<TypedExternalId> {
        val typed = ArrayList<TypedExternalId>()
        externalIdDao.listByTitle(title.titleId).mapTo(typed) { it.toTyped() }
        val editions = editionDao?.listByTitle(title.titleId).orEmpty()
        for (edition in editions) {
            externalIdDao.listByEdition(edition.editionId).mapTo(typed) { it.toTyped() }
        }
        return typed
    }

    private fun ExternalIdEntity.toTyped(): TypedExternalId = TypedExternalId(authority, nativeId)

    private fun parse(raw: String): TitleId? = runCatching { TitleId.fromString(raw) }.getOrNull()
}
