package dev.infusion.tv.tracking

import dev.infusion.core.domain.metadata.MediaIdentityResolver
import dev.infusion.core.domain.metadata.MetadataLocale
import dev.infusion.core.domain.metadata.MetadataRepository
import dev.infusion.core.domain.tracking.TrackingRuntimeSourcePort
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.core.model.watch.WatchKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CanonicalTrackingRuntime(
    private val measured: TrackingRuntimeSourcePort,
    private val identityResolver: MediaIdentityResolver,
    private val metadata: MetadataRepository,
    private val locale: () -> MetadataLocale,
) : TrackingRuntimeSourcePort {

    override suspend fun runtimeMsFor(watchKey: WatchKey): Long? {
        measured.runtimeMsFor(watchKey)?.let { return it }
        return withContext(Dispatchers.IO) {
            val owner: TitleId = when {
                watchKey.isEpisode -> watchKey.seriesId ?: return@withContext null
                else -> watchKey.titleId ?: return@withContext null
            }
            val type = if (watchKey.isEpisode) MediaType.SHOW else MediaType.MOVIE
            val identity = runCatching { identityResolver.resolve(owner, type, locale()) }.getOrNull()
                ?: return@withContext null
            if (watchKey.isEpisode) {
                val season = watchKey.seasonNumber ?: return@withContext null
                val episode = watchKey.episodeNumber ?: return@withContext null
                val details = runCatching { metadata.seasonDetails(identity, season, locale()).valueOrNull() }
                    .getOrNull() ?: return@withContext null
                details.episodes
                    .firstOrNull { it.seasonNumber == season && it.episodeNumber == episode }
                    ?.runtimeMinutes
                    ?.takeIf { it > 0 }
                    ?.toLong()?.times(60_000L)
            } else {
                runCatching { metadata.details(identity, locale()) }.getOrNull()
                    ?.runtimeMinutes
                    ?.takeIf { it > 0 }
                    ?.toLong()?.times(60_000L)
            }
        }
    }
}
