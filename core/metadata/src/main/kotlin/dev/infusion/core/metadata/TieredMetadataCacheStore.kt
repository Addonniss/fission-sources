package dev.infusion.core.metadata

import dev.infusion.core.domain.metadata.CachedMetadataPayload
import dev.infusion.core.domain.metadata.MetadataCacheStore

class TieredMetadataCacheStore(
    private val persistent: MetadataCacheStore,
    private val memory: MetadataCacheStore = InMemoryMetadataCacheStore(),
) : MetadataCacheStore {

    override suspend fun load(namespace: String, key: String): CachedMetadataPayload? {
        memory.load(namespace, key)?.let { return it }
        val hydrated = persistent.load(namespace, key) ?: return null
        memory.store(namespace, key, hydrated.payload, hydrated.storedAtMs)
        return hydrated
    }

    override suspend fun store(namespace: String, key: String, payload: String, storedAtMs: Long) {
        persistent.store(namespace, key, payload, storedAtMs)
        memory.store(namespace, key, payload, storedAtMs)
    }

    override suspend fun remove(namespace: String, key: String) {
        memory.remove(namespace, key)
        persistent.remove(namespace, key)
    }

    override suspend fun clearNamespace(namespace: String) {
        memory.clearNamespace(namespace)
        persistent.clearNamespace(namespace)
    }

    override suspend fun purgeOlderThan(nowMs: Long, maxAgeMs: Long): Int {
        memory.purgeOlderThan(nowMs, maxAgeMs)
        return persistent.purgeOlderThan(nowMs, maxAgeMs)
    }
}
