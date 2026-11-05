package dev.infusion.core.database.tracking

import dev.infusion.core.database.dao.EditionDao
import dev.infusion.core.database.dao.ExternalIdDao
import dev.infusion.core.database.dao.TitleDao
import dev.infusion.core.database.entity.ExternalIdEntity
import dev.infusion.core.database.model.TitleKind
import dev.infusion.core.database.store.TitleSupersession
import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingInboundIdentityPort
import dev.infusion.core.domain.tracking.TrackingMediaKind
import dev.infusion.core.domain.tracking.TrackingWatchKeyLookup
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.watch.WatchKey

class RoomTrackingInboundIdentity(
    private val titleDao: TitleDao,
    private val externalIdDao: ExternalIdDao,
    private val supersession: TitleSupersession,
    private val editionDao: EditionDao,
) : TrackingInboundIdentityPort {

    override fun lookUp(
        ids: TrackingExternalIds,
        kind: TrackingMediaKind,
        seasonNumber: Int?,
        episodeNumber: Int?,
    ): TrackingWatchKeyLookup {
        val candidates = LinkedHashSet<TitleId>()
        for (alias in ids.typed()) {
            titleIdFor(alias)?.let { candidates += supersession.survivorOf(it) }
        }
        if (candidates.isEmpty()) return TrackingWatchKeyLookup.Unproven
        if (candidates.size > 1) return TrackingWatchKeyLookup.Ambiguous(candidates.toList())
        val titleId = candidates.first()
        val title = titleDao.getById(titleId.value.toString()) ?: return TrackingWatchKeyLookup.Unproven
        val watchKey = when (kind) {
            TrackingMediaKind.MOVIE ->

                if (title.kind == TitleKind.MOVIE) WatchKey.movie(titleId) else null
            TrackingMediaKind.SHOW ->
                if (title.kind == TitleKind.SERIES &&
                    seasonNumber != null && seasonNumber > 0 &&
                    episodeNumber != null && episodeNumber > 0
                ) {
                    WatchKey.episode(titleId, seasonNumber, episodeNumber)
                } else {
                    null
                }
        }
        return watchKey?.let { TrackingWatchKeyLookup.Resolved(it) } ?: TrackingWatchKeyLookup.Unproven
    }

    private fun titleIdFor(alias: TypedExternalId): TitleId? {
        val row: ExternalIdEntity = externalIdDao.get(alias.authority, alias.value) ?: return null
        row.titleId?.let { return parse(it) }
        val editionId = row.editionId ?: return null
        val edition = editionDao.getById(editionId) ?: return null
        return parse(edition.titleId)
    }

    private fun parse(raw: String): TitleId? = runCatching { TitleId.fromString(raw) }.getOrNull()
}
