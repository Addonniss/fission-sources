package dev.infusion.core.database.store

import dev.infusion.core.database.dao.TitleDao
import dev.infusion.core.database.entity.TitleEntity
import dev.infusion.core.domain.anchor.CanonicalRootTitleStore
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomCanonicalRootTitleStore(
    private val titleDao: TitleDao,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val transaction: (block: () -> Unit) -> Unit = { block -> block() },
) : CanonicalRootTitleStore {

    override suspend fun materializeRoot(
        titleId: TitleId,
        kind: TitleKind,
        displayTitle: String,
        year: Int?,
    ): Boolean = withContext(ioDispatcher) {
        val name = displayTitle.trim()
        if (name.isEmpty()) return@withContext false
        val dbKind = when (kind) {
            TitleKind.MOVIE -> dev.infusion.core.database.model.TitleKind.MOVIE
            TitleKind.SERIES -> dev.infusion.core.database.model.TitleKind.SERIES

            TitleKind.SEASON, TitleKind.EPISODE, TitleKind.CLIP -> return@withContext false
        }
        var present = false
        transaction {
            val existing = titleDao.getById(titleId.value.toString())
            if (existing != null) {
                present = true
                return@transaction
            }
            titleDao.upsert(
                TitleEntity(
                    titleId = titleId.value.toString(),
                    kind = dbKind,
                    primaryTitle = name,
                    originalTitle = null,
                    year = year,
                    createdAtMs = nowMs(),
                ),
            )
            present = true
        }
        present
    }
}
