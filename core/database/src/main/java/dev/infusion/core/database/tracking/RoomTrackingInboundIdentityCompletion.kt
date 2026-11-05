package dev.infusion.core.database.tracking

import dev.infusion.core.database.dao.ExternalIdDao
import dev.infusion.core.database.dao.TitleDao
import dev.infusion.core.domain.anchor.CanonicalRootTitleStore
import dev.infusion.core.domain.metadata.MediaIdentityStore
import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingInboundIdentityCompletionPort
import dev.infusion.core.domain.tracking.TrackingMediaKind
import dev.infusion.core.domain.tracking.TrackingWatchKeyLookup
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.identity.MappingOrigin
import dev.infusion.core.model.metadata.CanonicalTitleIdentity
import dev.infusion.core.model.watch.WatchKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomTrackingInboundIdentityCompletion(
    private val titleDao: TitleDao,
    private val externalIdDao: ExternalIdDao,
    private val identityStore: MediaIdentityStore,
    private val rootTitleStore: CanonicalRootTitleStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TrackingInboundIdentityCompletionPort {

    override suspend fun complete(
        ids: TrackingExternalIds,
        kind: TrackingMediaKind,
        seasonNumber: Int?,
        episodeNumber: Int?,
        title: String?,
        year: Int?,
    ): TrackingWatchKeyLookup = withContext(ioDispatcher) {
        val typed = ids.typed()
        if (typed.isEmpty()) return@withContext TrackingWatchKeyLookup.Unproven
        val watchShape = when (kind) {
            TrackingMediaKind.MOVIE -> true
            TrackingMediaKind.SHOW ->
                seasonNumber != null && seasonNumber > 0 && episodeNumber != null && episodeNumber > 0
        }
        if (!watchShape) return@withContext TrackingWatchKeyLookup.Unproven
        val canonicalAlias = DERIVATION_ORDER.firstNotNullOfOrNull { authority ->
            typed.firstOrNull { it.authority == authority }
        } ?: return@withContext TrackingWatchKeyLookup.Unproven
        val titleId = CanonicalTitleIdentity.of(canonicalAlias)

        val ownedBy = typed.mapNotNull { externalIdDao.get(it.authority, it.value)?.titleId }
        if (ownedBy.any { it != titleId.value.toString() }) return@withContext TrackingWatchKeyLookup.Unproven

        val expectedKind = if (kind == TrackingMediaKind.SHOW) TitleKind.SERIES else TitleKind.MOVIE
        val existing = titleDao.getById(titleId.value.toString())
        if (existing != null) {

            if (existing.kind.name != expectedKind.name) return@withContext TrackingWatchKeyLookup.Unproven
        } else {

            val displayTitle = title?.trim().orEmpty()
            if (displayTitle.isEmpty()) return@withContext TrackingWatchKeyLookup.Unproven
            if (!rootTitleStore.materializeRoot(titleId, expectedKind, displayTitle, year)) {
                return@withContext TrackingWatchKeyLookup.Unproven
            }
        }

        for (alias in typed) {
            identityStore.bind(alias, titleId, MappingOrigin.CONNECTOR)
        }
        val watchKey = when (kind) {
            TrackingMediaKind.MOVIE -> WatchKey.movie(titleId)
            TrackingMediaKind.SHOW -> WatchKey.episode(titleId, seasonNumber!!, episodeNumber!!)
        }
        TrackingWatchKeyLookup.Resolved(watchKey)
    }

    private companion object {

        val DERIVATION_ORDER = listOf("imdb", "tmdb", "tvdb")
    }
}
