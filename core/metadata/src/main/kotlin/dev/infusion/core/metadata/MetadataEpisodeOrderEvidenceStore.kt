package dev.infusion.core.metadata

import dev.infusion.core.domain.metadata.CanonicalEpisodeOrder
import dev.infusion.core.domain.metadata.CanonicalEpisodeOrderEvidenceStore
import dev.infusion.core.domain.metadata.CanonicalEpisodeOrderRecording
import dev.infusion.core.domain.metadata.CanonicalEpisodeRef
import dev.infusion.core.domain.metadata.MetadataCacheStore
import dev.infusion.core.domain.metadata.MetadataFreshness
import dev.infusion.core.domain.metadata.MetadataLocale
import dev.infusion.core.model.metadata.MediaIdentity
import org.json.JSONArray
import org.json.JSONObject

class MetadataEpisodeOrderEvidenceStore(
    private val cache: MetadataCacheStore,
    private val providerId: String,
    private val policy: MetadataCachePolicy = MetadataCachePolicy(),
    private val codec: EpisodeOrderEvidenceCodec = EpisodeOrderEvidenceCodec(),
    private val clock: () -> Long = System::currentTimeMillis,

    private val trace: (String) -> Unit = {},
) : CanonicalEpisodeOrderEvidenceStore {

    private val namespace: String = MetadataNamespaces.episodeOrder(providerId)
    private val detailsNamespace: String = MetadataNamespaces.details(providerId)
    private val seasonNamespace: String = MetadataNamespaces.season(providerId)

    private val freshnessGatedNamespaces: Set<String> = setOf(seasonNamespace)

    override suspend fun load(identity: MediaIdentity, locale: MetadataLocale): CanonicalEpisodeOrder? {
        val startedAt = System.nanoTime()
        val key = MetadataCacheKeys.details(identity, locale) ?: return null

        val rowAt = System.nanoTime()
        val row = runCatching { cache.load(namespace, key) }.getOrNull()
        val rowMs = (System.nanoTime() - rowAt) / 1_000_000
        if (row == null) {
            trace("evidence order load hit=false reason=absent row_ms=$rowMs total_ms=${totalMs(startedAt)}")
            return null
        }
        val decodeAt = System.nanoTime()
        val record = codec.decode(row.payload)
        val decodeMs = (System.nanoTime() - decodeAt) / 1_000_000
        if (record == null) {
            trace("evidence order load hit=false reason=corrupt row_ms=$rowMs decode_ms=$decodeMs " +
                "total_ms=${totalMs(startedAt)}")
            return dropObsolete(key)
        }

        if (record.episodes.isEmpty() || record.seasons.none { it > 0 }) {
            trace("evidence order load hit=false reason=empty row_ms=$rowMs decode_ms=$decodeMs " +
                "total_ms=${totalMs(startedAt)}")
            return dropObsolete(key)
        }
        val stamps = StampReads()
        val stampAt = System.nanoTime()
        val stillBound = stillBound(record, stamps)
        val stampMs = (System.nanoTime() - stampAt) / 1_000_000
        if (!stillBound) {
            trace(
                "evidence order load hit=false reason=unbound row_ms=$rowMs decode_ms=$decodeMs " +
                    "stamp_ms=$stampMs stamp_queries=${stamps.queries} stamp_keys=${stamps.keys} " +
                    "total_ms=${totalMs(startedAt)}",
            )
            return dropObsolete(key)
        }
        trace(
            "evidence order load hit=true row_ms=$rowMs decode_ms=$decodeMs stamp_ms=$stampMs " +
                "stamp_queries=${stamps.queries} stamp_keys=${stamps.keys} " +
                "episodes=${record.episodes.size} seasons=${record.seasons.size} " +
                "total_ms=${totalMs(startedAt)}",
        )
        return CanonicalEpisodeOrder(seasons = record.seasons, episodes = record.episodes)
    }

    private class StampReads {
        var queries: Int = 0
        var keys: Int = 0
    }

    private fun totalMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    private suspend fun stillBound(
        record: EpisodeOrderRecord,
        reads: StampReads = StampReads(),
    ): Boolean {
        val now = clock()
        for ((sourceNamespace, stamps) in record.stamps.groupBy { it.namespace }) {
            reads.queries++
            reads.keys += stamps.size
            val current = runCatching { cache.stamps(sourceNamespace, stamps.map { it.key }) }.getOrNull()
                ?: return false
            for (stamp in stamps) {
                val storedAtMs = current[stamp.key] ?: return false
                if (storedAtMs != stamp.storedAtMs) return false
                if (sourceNamespace in freshnessGatedNamespaces &&
                    policy.freshnessOf(sourceNamespace, storedAtMs, now) != MetadataFreshness.FRESH
                ) {
                    return false
                }
            }
        }
        return record.stamps.isNotEmpty()
    }

    override suspend fun beginRecording(
        identity: MediaIdentity,
        locale: MetadataLocale,
    ): CanonicalEpisodeOrderRecording = Recording(identity, locale)

    private suspend fun dropObsolete(key: String): CanonicalEpisodeOrder? {
        runCatching { cache.remove(namespace, key) }
        return null
    }

    private inner class Recording(
        private val identity: MediaIdentity,
        private val locale: MetadataLocale,
    ) : CanonicalEpisodeOrderRecording {

        private val detailsKey: String? = MetadataCacheKeys.details(identity, locale)

        private val captured = LinkedHashMap<Pair<String, String>, Long?>()

        override suspend fun detailsStamp(): Long? {
            val key = detailsKey ?: return null
            return capture(detailsNamespace, key)
        }

        override suspend fun seasonStamp(seasonNumber: Int): Long? {
            val key = MetadataCacheKeys.season(identity, locale, seasonNumber) ?: return null
            return capture(seasonNamespace, key)
        }

        override suspend fun commit(order: CanonicalEpisodeOrder, seasonsRead: Set<Int>): Boolean {
            val key = detailsKey ?: return refuse("no_key", order)
            if (order.episodes.isEmpty()) return refuse("empty_episodes", order)
            val statedAboveZero = order.seasons.filter { it > 0 }.distinct()
            if (statedAboveZero.isEmpty()) return refuse("no_positive_seasons", order)

            if (!seasonsRead.containsAll(statedAboveZero)) return refuse("incomplete_walk", order)

            if (captured.keys.none { it.first == detailsNamespace && it.second == key }) {
                return refuse("details_unbound", order)
            }
            val stamps = ArrayList<SourceStamp>(captured.size)
            for ((source, stampBefore) in captured) {
                val (sourceNamespace, sourceKey) = source

                val storedAtMs = stampBefore
                    ?: runCatching { cache.stamps(sourceNamespace, listOf(sourceKey))[sourceKey] }.getOrNull()
                    ?: return refuse("stamp_unresolved", order)

                val current = runCatching { cache.stamps(sourceNamespace, listOf(sourceKey))[sourceKey] }.getOrNull()
                if (current != storedAtMs) return refuse("stamp_changed", order)
                stamps += SourceStamp(sourceNamespace, sourceKey, storedAtMs)
            }
            if (stamps.isEmpty()) return refuse("no_stamps", order)

            val episodes = order.episodes.distinctBy { it.seasonNumber to it.episodeNumber }
            val seasons = order.seasons.distinct().sorted()
            val payload = codec.encode(
                EpisodeOrderRecord(
                    seasons = seasons,
                    episodes = episodes,
                    stamps = stamps.sortedWith(compareBy({ it.namespace }, { it.key })),
                ),
            ) ?: return refuse("encode_failed", order)
            val written = runCatching {
                cache.store(namespace, key, payload, clock())
                true
            }.getOrDefault(false)

            trace(
                "evidence order record written=$written " +
                    "reason=${if (written) "stored" else "store_failed"} " +
                    "stamps=${stamps.size} episodes=${episodes.size} seasons=${seasons.size}",
            )
            return written
        }

        private fun refuse(reason: String, order: CanonicalEpisodeOrder): Boolean {
            trace(
                "evidence order record written=false reason=$reason " +
                    "stamps=${captured.size} episodes=${order.episodes.size} seasons=${order.seasons.size}",
            )
            return false
        }

        private suspend fun capture(sourceNamespace: String, key: String): Long? {
            val stamp = runCatching { cache.stamps(sourceNamespace, listOf(key))[key] }.getOrNull()
            captured[sourceNamespace to key] = stamp
            return stamp
        }
    }
}

data class SourceStamp(val namespace: String, val key: String, val storedAtMs: Long)

data class EpisodeOrderRecord(
    val seasons: List<Int>,
    val episodes: List<CanonicalEpisodeRef>,
    val stamps: List<SourceStamp>,
)

class EpisodeOrderEvidenceCodec {

    fun encode(record: EpisodeOrderRecord): String? = runCatching {
        val episodes = JSONArray()
        record.episodes.forEach { episode ->
            episodes.put(
                JSONArray().apply {
                    put(episode.seasonNumber)
                    put(episode.episodeNumber)
                    put(episode.title ?: JSONObject.NULL)
                    put(episode.released ?: JSONObject.NULL)
                    put(episode.runtimeMinutes ?: JSONObject.NULL)
                },
            )
        }
        val stamps = JSONArray()
        record.stamps.forEach { stamp ->
            stamps.put(
                JSONArray().apply {
                    put(stamp.namespace)
                    put(stamp.key)
                    put(stamp.storedAtMs)
                },
            )
        }
        JSONObject()
            .put("v", FORMAT_VERSION)
            .put("seasons", JSONArray(record.seasons))
            .put("episodes", episodes)
            .put("stamps", stamps)
            .toString()
    }.getOrNull()

    fun decode(payload: String): EpisodeOrderRecord? = runCatching {
        val document = JSONObject(payload)
        if (document.optInt("v", -1) != FORMAT_VERSION) return null
        val seasonsJson = document.optJSONArray("seasons") ?: return null
        val seasons = ArrayList<Int>(seasonsJson.length())
        for (index in 0 until seasonsJson.length()) {
            val season = seasonsJson.opt(index)
            if (season !is Number) return null
            seasons += season.toInt()
        }
        val episodesJson = document.optJSONArray("episodes") ?: return null
        val episodes = ArrayList<CanonicalEpisodeRef>(episodesJson.length())
        for (index in 0 until episodesJson.length()) {
            val entry = episodesJson.optJSONArray(index) ?: return null
            if (entry.length() != 5) return null
            val seasonNumber = entry.opt(0) as? Number ?: return null
            val episodeNumber = entry.opt(1) as? Number ?: return null
            val title = entry.opt(2).let { if (it == null || it == JSONObject.NULL) null else it as? String ?: return null }
            val released = entry.opt(3).let { if (it == null || it == JSONObject.NULL) null else it as? String ?: return null }
            val runtime = entry.opt(4).let { if (it == null || it == JSONObject.NULL) null else (it as? Number)?.toInt() ?: return null }
            episodes += CanonicalEpisodeRef(
                seasonNumber = seasonNumber.toInt(),
                episodeNumber = episodeNumber.toInt(),
                title = title,
                released = released,
                runtimeMinutes = runtime,
            )
        }
        val stampsJson = document.optJSONArray("stamps") ?: return null
        val stamps = ArrayList<SourceStamp>(stampsJson.length())
        for (index in 0 until stampsJson.length()) {
            val entry = stampsJson.optJSONArray(index) ?: return null
            if (entry.length() != 3) return null
            val sourceNamespace = entry.opt(0) as? String ?: return null
            val key = entry.opt(1) as? String ?: return null
            val storedAtMs = entry.opt(2) as? Number ?: return null
            stamps += SourceStamp(sourceNamespace, key, storedAtMs.toLong())
        }
        EpisodeOrderRecord(seasons = seasons, episodes = episodes, stamps = stamps)
    }.getOrNull()

    private companion object {

        const val FORMAT_VERSION = 1
    }
}
