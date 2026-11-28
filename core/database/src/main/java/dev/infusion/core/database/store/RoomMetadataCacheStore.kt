package dev.infusion.core.database.store

import dev.infusion.core.database.dao.MetadataCacheDao
import dev.infusion.core.database.entity.MetadataCacheEntity
import dev.infusion.core.domain.metadata.CachedMetadataPayload
import dev.infusion.core.domain.metadata.MetadataCacheStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomMetadataCacheStore(
    private val dao: MetadataCacheDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MetadataCacheStore {

    override suspend fun load(namespace: String, key: String): CachedMetadataPayload? =
        withContext(ioDispatcher) {
            dao.get(namespace, key)?.let {
                CachedMetadataPayload(payload = it.payload, storedAtMs = it.storedAtMs)
            }
        }

    override suspend fun store(namespace: String, key: String, payload: String, storedAtMs: Long): Unit =
        withContext(ioDispatcher) {
            dao.upsert(
                MetadataCacheEntity(
                    namespace = namespace,
                    cacheKey = key,
                    payload = payload,
                    storedAtMs = storedAtMs,
                ),
            )
        }

    override suspend fun remove(namespace: String, key: String): Unit =
        withContext(ioDispatcher) { dao.delete(namespace, key) }

    override suspend fun clearNamespace(namespace: String): Unit =
        withContext(ioDispatcher) { dao.deleteNamespace(namespace) }

    override suspend fun purgeOlderThan(nowMs: Long, maxAgeMs: Long): Int =
        withContext(ioDispatcher) {
            if (maxAgeMs <= 0L) 0 else dao.deleteOlderThan(nowMs - maxAgeMs)
        }

    override suspend fun stamps(namespace: String, keys: List<String>): Map<String, Long> {
        val distinct = keys.distinct()
        if (distinct.isEmpty()) return emptyMap()
        return withContext(ioDispatcher) {

            distinct.chunked(STAMP_CHUNK).flatMap { chunk ->
                dao.stamps(namespace, chunk).map { stamp -> stamp.cacheKey to stamp.storedAtMs }
            }.toMap()
        }
    }

    private companion object {

        const val STAMP_CHUNK: Int = 500
    }
}
