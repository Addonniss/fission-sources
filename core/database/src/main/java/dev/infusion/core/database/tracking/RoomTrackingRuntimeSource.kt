package dev.infusion.core.database.tracking

import dev.infusion.core.database.dao.WatchStateDao
import dev.infusion.core.database.store.TitleSupersession
import dev.infusion.core.database.watch.WatchKeyCodec
import dev.infusion.core.database.watch.canonicalForConvergence
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.tracking.TrackingRuntimeSourcePort
import dev.infusion.core.model.watch.WatchKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomTrackingRuntimeSource(
    private val watchStateDao: WatchStateDao,
    private val profileScope: ProfileScope,
    private val supersession: TitleSupersession? = null,
) : TrackingRuntimeSourcePort {

    override suspend fun runtimeMsFor(watchKey: WatchKey): Long? = withContext(Dispatchers.IO) {
        val key = watchKey.canonicalForConvergence(supersession)
        watchStateDao.get(profileScope.activeProfileId, WatchKeyCodec.encode(key))
            ?.durationMs
            ?.takeIf { it > 0L }
    }
}
