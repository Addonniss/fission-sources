package dev.infusion.core.database.tracking

import android.content.Context
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.tracking.TrackingWatchedCacheEntry
import dev.infusion.core.domain.tracking.TrackingWatchedCacheStore
import dev.infusion.core.domain.tracking.TrackingWatchedCacheVerdict
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

class SharedPreferencesTrackingWatchedCacheStore(
    context: Context,
    private val profileScope: ProfileScope,
    prefsName: String = PREFS_NAME,
    private val maxEntries: Int = MAX_ENTRIES,
) : TrackingWatchedCacheStore {

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun entries(providerId: String, keys: Collection<String>): Map<String, TrackingWatchedCacheEntry> {
        if (keys.isEmpty()) return emptyMap()
        val wanted = keys.toSet()
        return read(providerId).filterKeys { it in wanted }
    }

    override fun put(providerId: String, entries: List<TrackingWatchedCacheEntry>) {
        if (entries.isEmpty()) return

        val current = LinkedHashMap(read(providerId))
        for (entry in entries) {
            current.remove(entry.key)
            current[entry.key] = entry
        }
        write(providerId, current.values.toList().takeLast(maxEntries.coerceAtLeast(1)))
    }

    override fun clear(providerId: String) {
        prefs.edit().remove(prefKey(providerId)).apply()
    }

    private fun read(providerId: String): Map<String, TrackingWatchedCacheEntry> {
        val raw = prefs.getString(prefKey(providerId), null) ?: return emptyMap()
        return runCatching {
            val entries = JSONObject(raw).optJSONArray(FIELD_ENTRIES) ?: return@runCatching emptyMap()
            val parsed = LinkedHashMap<String, TrackingWatchedCacheEntry>(entries.length())
            for (index in 0 until entries.length()) {
                val entry = parseEntry(entries.optJSONObject(index) ?: continue) ?: continue
                parsed[entry.key] = entry
            }
            parsed
        }.getOrDefault(emptyMap())
    }

    private fun write(providerId: String, entries: List<TrackingWatchedCacheEntry>) {
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject().apply {
                    put(FIELD_KEY, entry.key)
                    put(FIELD_VERDICT, entry.verdict.name)
                    entry.watchedAt?.let { put(FIELD_WATCHED_AT, it.toEpochMilli()) }
                    entry.resolvedUnderGate?.let { put(FIELD_GATE, it.toEpochMilli()) }
                },
            )
        }
        val document = JSONObject().apply {
            put(FIELD_VERSION, DOCUMENT_VERSION)
            put(FIELD_ENTRIES, array)
        }
        prefs.edit().putString(prefKey(providerId), document.toString()).apply()
    }

    private fun parseEntry(row: JSONObject): TrackingWatchedCacheEntry? {
        val key = row.optString(FIELD_KEY, "").trim().takeIf { it.isNotEmpty() } ?: return null
        val verdict = runCatching {
            TrackingWatchedCacheVerdict.valueOf(row.optString(FIELD_VERDICT, ""))
        }.getOrNull() ?: return null
        return runCatching {
            TrackingWatchedCacheEntry(
                key = key,
                verdict = verdict,
                watchedAt = epochMillis(row, FIELD_WATCHED_AT),
                resolvedUnderGate = epochMillis(row, FIELD_GATE),
            )
        }.getOrNull()
    }

    private fun epochMillis(row: JSONObject, field: String): Instant? {
        if (!row.has(field) || row.isNull(field)) return null
        return row.optLong(field, 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli)
    }

    private fun prefKey(providerId: String) = "$PREFS_KEY_PREFIX.$providerId.${profileScope.activeProfileId}"

    companion object {

        const val PREFS_NAME: String = "infusion_tracking_watched_cache"

        const val MAX_ENTRIES: Int = 256

        private const val PREFS_KEY_PREFIX = "tracking-watched-cache"
        private const val DOCUMENT_VERSION = 1
        private const val FIELD_VERSION = "version"
        private const val FIELD_ENTRIES = "entries"
        private const val FIELD_KEY = "key"
        private const val FIELD_VERDICT = "verdict"
        private const val FIELD_WATCHED_AT = "watched_at_ms"
        private const val FIELD_GATE = "resolved_under_gate_ms"
    }
}
