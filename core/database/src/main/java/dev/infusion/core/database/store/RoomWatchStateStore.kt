package dev.infusion.core.database.store

import dev.infusion.core.database.Clock
import dev.infusion.core.database.dao.WatchStateDao
import dev.infusion.core.database.mapper.WatchStateMapper
import dev.infusion.core.database.watch.WatchKeyCodec
import dev.infusion.core.database.watch.canonicalForConvergence
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.watch.WatchStateStore
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.model.watch.WatchState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

class RoomWatchStateStore(
    private val watchStateDao: WatchStateDao,
    private val scope: ProfileScope,
    private val supersession: TitleSupersession? = null,
    private val clock: Clock = Clock.SYSTEM,
) : WatchStateStore {

    override fun save(state: WatchState) {
        val canonical = state.watchKey.canonicalForConvergence(supersession)
        val stored = if (canonical == state.watchKey) state else state.copy(watchKey = canonical)
        watchStateDao.upsert(WatchStateMapper.toEntity(scope.activeProfileId, stored, updatedAtMs = clock.nowMs()))
    }

    override fun observe(key: WatchKey): Flow<WatchState?> =
        scope.activeProfileFlow.flatMapLatest { profileId ->
            watchStateDao.getFlow(profileId, encode(key)).map { it?.let(WatchStateMapper::toCanonical) }
        }

    override fun current(key: WatchKey): WatchState? =
        watchStateDao.get(scope.activeProfileId, encode(key))?.let(WatchStateMapper::toCanonical)

    override fun observeAll(): Flow<List<WatchState>> =
        scope.activeProfileFlow.flatMapLatest { profileId ->
            watchStateDao.observeAll(profileId).map { rows -> rows.map(WatchStateMapper::toCanonical) }
        }

    private fun encode(key: WatchKey): String =
        WatchKeyCodec.encode(key.canonicalForConvergence(supersession))
}
