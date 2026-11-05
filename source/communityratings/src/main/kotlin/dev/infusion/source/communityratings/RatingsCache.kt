package dev.infusion.source.communityratings

import dev.infusion.core.domain.metadata.MetadataCacheStore
import dev.infusion.core.model.metadata.MediaRating
import dev.infusion.core.model.metadata.RatingSupplier
import org.json.JSONArray
import org.json.JSONObject

internal class RatingsCache(
    private val store: MetadataCacheStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {

    data class Entry(val storedAtMs: Long, val ratings: List<MediaRating>) {

        fun isFresh(nowMs: Long, ttl: Long): Boolean = nowMs - storedAtMs in 0 until ttl
    }

    suspend fun load(identityKey: String): Map<RatingSupplier, Entry> {
        val payload = runCatching { store.load(NAMESPACE, identityKey) }.getOrNull() ?: return emptyMap()
        val document = runCatching { JSONObject(payload.payload) }.getOrNull() ?: return emptyMap()
        val entries = document.optJSONObject("entries") ?: return emptyMap()
        val out = LinkedHashMap<RatingSupplier, Entry>()
        val keys = entries.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val supplier = RatingSupplier.fromId(key) ?: continue
            val entry = entries.optJSONObject(key) ?: continue
            val at = entry.optLong("at", -1L)
            if (at < 0) continue
            val ratings = decodeRatings(entry.optJSONArray("ratings"))
            if (ratings.isNotEmpty()) out[supplier] = Entry(at, ratings)
        }
        return out
    }

    suspend fun store(identityKey: String, entries: Map<RatingSupplier, Entry>) {
        if (entries.isEmpty()) return
        val root = JSONObject()
        val encoded = JSONObject()
        entries.forEach { (supplier, entry) ->
            encoded.put(
                supplier.id,
                JSONObject()
                    .put("at", entry.storedAtMs)
                    .put("ratings", JSONArray().apply { entry.ratings.forEach { put(encodeRating(it)) } }),
            )
        }
        root.put("entries", encoded)
        runCatching { store.store(NAMESPACE, identityKey, root.toString(), clock()) }
    }

    data class SeasonEntry(val storedAtMs: Long, val episodes: Map<Int, List<MediaRating>>) {
        fun isFresh(nowMs: Long, ttl: Long): Boolean = nowMs - storedAtMs in 0 until ttl
    }

    suspend fun loadSeason(seasonKey: String): Map<RatingSupplier, SeasonEntry> {
        val payload = runCatching { store.load(NAMESPACE, seasonKey) }.getOrNull() ?: return emptyMap()
        val document = runCatching { JSONObject(payload.payload) }.getOrNull() ?: return emptyMap()
        val entries = document.optJSONObject("entries") ?: return emptyMap()
        val out = LinkedHashMap<RatingSupplier, SeasonEntry>()
        val keys = entries.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val supplier = RatingSupplier.fromId(key) ?: continue
            val entry = entries.optJSONObject(key) ?: continue
            val at = entry.optLong("at", -1L)
            if (at < 0) continue
            val episodes = LinkedHashMap<Int, List<MediaRating>>()
            entry.optJSONObject("episodes")?.let { perEpisode ->
                val episodeNumbers = perEpisode.keys()
                while (episodeNumbers.hasNext()) {
                    val number = episodeNumbers.next().toIntOrNull() ?: continue
                    val ratings = decodeRatings(perEpisode.optJSONArray(number.toString()))
                    if (ratings.isNotEmpty()) episodes[number] = ratings
                }
            }
            if (episodes.isNotEmpty()) out[supplier] = SeasonEntry(at, episodes)
        }
        return out
    }

    suspend fun storeSeason(seasonKey: String, entries: Map<RatingSupplier, SeasonEntry>) {
        if (entries.isEmpty()) return
        val root = JSONObject()
        val encoded = JSONObject()
        entries.forEach { (supplier, entry) ->
            val perEpisode = JSONObject()
            entry.episodes.forEach { (number, ratings) ->
                perEpisode.put(number.toString(), JSONArray().apply { ratings.forEach { put(encodeRating(it)) } })
            }
            encoded.put(supplier.id, JSONObject().put("at", entry.storedAtMs).put("episodes", perEpisode))
        }
        root.put("entries", encoded)
        runCatching { store.store(NAMESPACE, seasonKey, root.toString(), clock()) }
    }

    private fun encodeRating(rating: MediaRating): JSONObject = JSONObject()
        .put("source", rating.source)
        .put("value", rating.value)
        .put("max", rating.max)
        .put("votes", rating.votes ?: JSONObject.NULL)
        .put("label", rating.label)
        .put("supplier", rating.supplier ?: JSONObject.NULL)

    private fun decodeRatings(array: JSONArray?): List<MediaRating> {
        if (array == null) return emptyList()
        val out = ArrayList<MediaRating>(array.length())
        for (index in 0 until array.length()) {
            val document = array.optJSONObject(index) ?: continue
            val source = document.optString("source").takeIf { it.isNotEmpty() } ?: continue
            val value = document.optDouble("value", Double.NaN).takeIf { it.isFinite() } ?: continue
            val max = document.optDouble("max", 10.0).takeIf { it > 0.0 } ?: 10.0
            val votes = if (document.isNull("votes")) null else document.optInt("votes")
            val supplier = document.optString("supplier").takeIf { it.isNotEmpty() }
            out += MediaRating(
                source = source,
                value = value,
                max = max,
                votes = votes,
                label = document.optString("label").takeIf { it.isNotEmpty() } ?: source.uppercase(),
                supplier = supplier,
            )
        }
        return out
    }

    companion object {

        const val NAMESPACE: String = "communityratings.ratings.v1"

        const val DEFAULT_TTL_MS: Long = 12L * 60L * 60L * 1000L
    }
}
