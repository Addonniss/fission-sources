package dev.infusion.core.database.tracking

import dev.infusion.core.database.dao.EditionDao
import dev.infusion.core.database.dao.ExternalIdDao
import dev.infusion.core.database.dao.TitleDao
import dev.infusion.core.database.entity.ExternalIdEntity
import dev.infusion.core.database.entity.TitleEntity
import dev.infusion.core.database.store.TitleSupersession
import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingMediaKind
import dev.infusion.core.domain.tracking.TrackingMediaReference
import dev.infusion.core.domain.tracking.TrackingMediaResolverPort
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.watch.WatchKey

class RoomTrackingMediaResolver(
    private val titleDao: TitleDao,
    private val externalIdDao: ExternalIdDao,
    private val supersession: TitleSupersession,
    private val editionDao: EditionDao? = null,
) : TrackingMediaResolverPort {

    override fun resolve(key: WatchKey): TrackingMediaReference? {
        val seriesOrTitleId = key.seriesId ?: key.titleId ?: return null

        if (key.isEpisode && (key.seasonNumber == null || key.episodeNumber == null)) return null
        val survivor = supersession.survivorOf(seriesOrTitleId)
        val title = titleDao.getById(survivor.value.toString()) ?: return null
        val ids = TrackingExternalIds.fromTyped(aliasesOf(title))

        if (ids.isEmpty) return null
        val kind = if (key.isEpisode) TrackingMediaKind.SHOW else TrackingMediaKind.MOVIE
        return TrackingMediaReference(
            kind = kind,
            externalIds = ids,
            title = title.primaryTitle.takeIf { it.isNotBlank() },
            year = title.year,
            seasonNumber = key.seasonNumber,
            episodeNumber = key.episodeNumber,
        )
    }

    override fun hasProvenIdentity(titleId: TitleId): Boolean {
        val survivor = supersession.survivorOf(titleId)
        val title = titleDao.getById(survivor.value.toString()) ?: return false
        return !TrackingExternalIds.fromTyped(aliasesOf(title)).isEmpty
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
}
