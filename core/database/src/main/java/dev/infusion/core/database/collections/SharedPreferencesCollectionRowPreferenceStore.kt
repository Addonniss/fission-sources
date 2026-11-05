package dev.infusion.core.database.collections

import android.content.Context
import android.content.SharedPreferences
import dev.infusion.core.domain.collections.CollectionPagePreference
import dev.infusion.core.domain.collections.CollectionPackId
import dev.infusion.core.domain.collections.CollectionRowPreferenceCodec
import dev.infusion.core.domain.collections.CollectionRowPreferenceConfiguration
import dev.infusion.core.domain.collections.CollectionRowPreferenceEntry
import dev.infusion.core.domain.collections.CollectionRowPreferenceLimits
import dev.infusion.core.domain.collections.CollectionRowPreferenceStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class SharedPreferencesCollectionRowPreferenceStore(
    context: Context,
    prefsName: String = PREFS_NAME,
) : CollectionRowPreferenceStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val decodeLock = Any()
    private val decodedCache = HashMap<String, Pair<String?, CollectionRowPreferenceConfiguration>>()

    private fun readCached(profileId: String): CollectionRowPreferenceConfiguration {
        val raw = prefs.getString(key(profileId), null)
        synchronized(decodeLock) {
            val cached = decodedCache[profileId]
            if (cached != null && cached.first == raw) return cached.second
        }
        val decoded = CollectionRowPreferenceCodec.decode(raw)
        synchronized(decodeLock) { decodedCache[profileId] = raw to decoded }
        return decoded
    }

    override fun read(profileId: String): CollectionRowPreferenceConfiguration = readCached(profileId)

    override fun writePage(
        profileId: String,
        packId: String,
        groupKey: String,
        tileKey: String,
        entries: List<CollectionRowPreferenceEntry>,
    ): Boolean {
        if (!CollectionPackId.isPackId(packId)) return false
        if (groupKey.isBlank() || tileKey.isBlank()) return false
        if (groupKey.length > CollectionRowPreferenceLimits.MAX_ROW_KEY_LENGTH) return false
        if (tileKey.length > CollectionRowPreferenceLimits.MAX_ROW_KEY_LENGTH) return false

        if (entries.any { it.rowKey.isBlank() }) return false
        if (entries.any { it.rowKey.length > CollectionRowPreferenceLimits.MAX_ROW_KEY_LENGTH }) return false
        if (entries.size > CollectionRowPreferenceLimits.MAX_ENTRIES_PER_PAGE) return false

        val existing = read(profileId)
        val identity = CollectionPagePreference(packId, groupKey, tileKey).pageId
        val current = existing.pages.firstOrNull { it.pageId == identity }
        if (current == null && existing.pages.size >= CollectionRowPreferenceLimits.MAX_PAGES_PER_PROFILE) {
            return false
        }
        val updated = current?.copy(entries = entries)
            ?: CollectionPagePreference(
                packId = packId,
                groupKey = groupKey,
                tileKey = tileKey,
                entries = entries,
            )
        val pages = if (current == null) {
            existing.pages + updated
        } else {
            existing.pages.map { if (it.pageId == identity) updated else it }
        }
        val payload = CollectionRowPreferenceCodec.encode(
            pages = pages,
            unreadableRecords = existing.unreadableRecords,
        )
        prefs.edit().putString(key(profileId), payload).apply()
        changes.tryEmit(Unit)
        return true
    }

    override fun clearProfile(profileId: String) {
        prefs.edit().remove(key(profileId)).apply()
        changes.tryEmit(Unit)
    }

    override fun clearAllProfiles() {
        val prefix = "$PREFS_KEY_PREFIX."
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
        }.apply()
        changes.tryEmit(Unit)
    }

    override fun changes(): Flow<Unit> = changes.asSharedFlow()

    private fun key(profileId: String) = "$PREFS_KEY_PREFIX.$profileId"

    companion object {

        const val PREFS_NAME: String = "infusion_collection_row_prefs"

        const val PREFS_KEY_PREFIX: String = "collection_row_prefs_v1"
    }
}
