package dev.infusion.source.addon.cache

import dev.infusion.core.database.Clock
import dev.infusion.source.addon.AddonManifestSnapshot
import dev.infusion.source.addon.store.AddonStore
import java.util.concurrent.ConcurrentHashMap

class AddonManifestCache(

    private val refreshAfterMs: Long = DEFAULT_REFRESH_AFTER_MS,

    private val clock: () -> Long = Clock.SYSTEM::nowMs,
) {

    data class CacheEntry(
        val snapshot: AddonManifestSnapshot,
        val fetchedAtMs: Long,
    )

    enum class Freshness { MISS, FRESH, STALE }

    private val memory = ConcurrentHashMap<String, CacheEntry>()

    fun get(addonId: String): CacheEntry? = memory[addonId]

    fun put(addonId: String, snapshot: AddonManifestSnapshot, fetchedAtMs: Long) {
        memory[addonId] = CacheEntry(snapshot, fetchedAtMs)
    }

    fun remove(addonId: String): CacheEntry? = memory.remove(addonId)

    fun clear() = memory.clear()

    fun size(): Int = memory.size

    fun ids(): Set<String> = memory.keys

    fun isStale(entry: CacheEntry): Boolean = clock() - entry.fetchedAtMs >= refreshAfterMs

    fun isStale(addonId: String): Boolean {
        val entry = get(addonId) ?: return true
        return isStale(entry)
    }

    fun hydrate(stored: List<AddonStore.StoredAddon>) {
        for (row in stored) {
            memory[row.entity.addonId] = CacheEntry(row.snapshot, row.entity.manifestFetchedAtMs)
        }
    }

    fun freshness(addonId: String): Freshness {
        val entry = get(addonId) ?: return Freshness.MISS
        return if (isStale(entry)) Freshness.STALE else Freshness.FRESH
    }

    companion object {

        const val DEFAULT_REFRESH_AFTER_MS = 6L * 60 * 60 * 1000
    }
}