package dev.infusion.core.database.collections

import android.content.Context
import android.content.SharedPreferences
import dev.infusion.core.domain.collections.CollectionPack
import dev.infusion.core.domain.collections.CollectionPackCodec
import dev.infusion.core.domain.collections.CollectionPackConfiguration
import dev.infusion.core.domain.collections.CollectionPackStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class SharedPreferencesCollectionPackStore(
    context: Context,
    prefsName: String = PREFS_NAME,

    private val decode: (String?) -> CollectionPackConfiguration = CollectionPackCodec::decode,
) : CollectionPackStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val decodeLock = Any()
    private val decodedCache = HashMap<String, Pair<String?, CollectionPackConfiguration>>()

    private val decodeLocks = HashMap<String, Any>()

    private fun readCached(profileId: String): CollectionPackConfiguration {
        val raw = prefs.getString(key(profileId), null)
        synchronized(decodeLock) {
            val cached = decodedCache[profileId]
            if (cached != null && cached.first == raw) return cached.second
        }

        val profileLock = synchronized(decodeLock) { decodeLocks.getOrPut(profileId) { Any() } }
        synchronized(profileLock) {
            synchronized(decodeLock) {
                val cached = decodedCache[profileId]
                if (cached != null && cached.first == raw) return cached.second
            }
            val decoded = decode(raw)
            synchronized(decodeLock) { decodedCache[profileId] = raw to decoded }
            return decoded
        }
    }

    override fun read(profileId: String): CollectionPackConfiguration = readCached(profileId)

    override fun write(profileId: String, packs: List<CollectionPack>) {

        val existing = read(profileId)
        val payload = CollectionPackCodec.encode(
            packs = packs,
            unreadableRecords = existing.unreadableRecords,
        )
        prefs.edit().putString(key(profileId), payload).apply()
        changes.tryEmit(Unit)
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

        const val PREFS_NAME: String = "infusion_collection_packs"

        const val PREFS_KEY_PREFIX: String = "collections_packs_v1"
    }
}
