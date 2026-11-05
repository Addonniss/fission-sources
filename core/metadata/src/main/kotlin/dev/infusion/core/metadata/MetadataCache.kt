package dev.infusion.core.metadata

import dev.infusion.core.domain.metadata.MetadataFreshness

object MetadataNamespaces {
    fun details(providerId: String): String = "$providerId.metadata.details.v1"
    fun season(providerId: String): String = "$providerId.metadata.season.v1"

    fun collection(providerId: String): String = "$providerId.metadata.collection.v2"
    fun person(providerId: String): String = "$providerId.metadata.person.v2"
    fun recommendations(providerId: String): String = "$providerId.metadata.recommendations.v2"
    fun similar(providerId: String): String = "$providerId.metadata.similar.v2"

    fun reviews(providerId: String): String = "$providerId.metadata.reviews.v1"

    fun languageArtwork(providerId: String): String = "$providerId.metadata.language-artwork.v1"
}

data class MetadataCachePolicy(
    val detailsTtlMs: Long = HOURS_12,
    val seasonTtlMs: Long = HOURS_12,
    val personTtlMs: Long = HOURS_24,
    val collectionTtlMs: Long = HOURS_24,
    val recommendationsTtlMs: Long = HOURS_6,
    val similarTtlMs: Long = HOURS_6,

    val reviewsTtlMs: Long = HOURS_6,
) {

    fun freshnessOf(namespace: String, storedAtMs: Long?, nowMs: Long): MetadataFreshness {
        if (storedAtMs == null) return MetadataFreshness.MISS
        val ttl = ttlFor(namespace)
        return if (nowMs - storedAtMs < ttl) MetadataFreshness.FRESH else MetadataFreshness.STALE
    }

    fun ttlFor(namespace: String): Long = when {
        namespace.endsWith(".season.v1") -> seasonTtlMs

        namespace.contains(".person.v") -> personTtlMs
        namespace.contains(".collection.v") -> collectionTtlMs
        namespace.contains(".recommendations.v") -> recommendationsTtlMs
        namespace.contains(".similar.v") -> similarTtlMs
        namespace.endsWith(".reviews.v1") -> reviewsTtlMs

        namespace.endsWith(".language-artwork.v1") -> detailsTtlMs
        else -> detailsTtlMs
    }

    companion object {
        const val HOURS_6: Long = 6L * 60 * 60 * 1000
        const val HOURS_12: Long = 12L * 60 * 60 * 1000
        const val HOURS_24: Long = 24L * 60 * 60 * 1000
        const val DAYS_30: Long = 30L * 24 * 60 * 60 * 1000
    }
}

class InMemoryMetadataCacheStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : dev.infusion.core.domain.metadata.MetadataCacheStore {

    private class Entry(val payload: String, val storedAtMs: Long)

    private val entries = LinkedHashMap<String, Entry>()

    override suspend fun load(
        namespace: String,
        key: String,
    ): dev.infusion.core.domain.metadata.CachedMetadataPayload? = synchronized(entries) {
        val entry = entries[composeKey(namespace, key)] ?: return null
        dev.infusion.core.domain.metadata.CachedMetadataPayload(entry.payload, entry.storedAtMs)
    }

    override suspend fun store(namespace: String, key: String, payload: String, storedAtMs: Long) {
        synchronized(entries) {
            val composed = composeKey(namespace, key)
            entries.remove(composed)
            entries[composed] = Entry(payload, storedAtMs)
            while (entries.size > maxEntries) {
                val eldest = entries.keys.firstOrNull() ?: break
                entries.remove(eldest)
            }
        }
    }

    override suspend fun remove(namespace: String, key: String) {
        synchronized(entries) { entries.remove(composeKey(namespace, key)) }
    }

    override suspend fun clearNamespace(namespace: String) {
        synchronized(entries) {
            val prefix = "$namespace\u0000"
            entries.keys.filter { it.startsWith(prefix) }.forEach { entries.remove(it) }
        }
    }

    override suspend fun purgeOlderThan(nowMs: Long, maxAgeMs: Long): Int {
        synchronized(entries) {
            val stale = entries.filterValues { nowMs - it.storedAtMs >= maxAgeMs }.keys
            stale.forEach { entries.remove(it) }
            return stale.size
        }
    }

    private fun composeKey(namespace: String, key: String): String = "$namespace\u0000$key"

    companion object {

        const val DEFAULT_MAX_ENTRIES: Int = 256
    }
}
