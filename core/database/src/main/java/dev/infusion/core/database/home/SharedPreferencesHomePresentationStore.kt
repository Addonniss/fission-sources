package dev.infusion.core.database.home

import android.content.Context
import android.content.SharedPreferences
import dev.infusion.core.domain.home.HomeCollectionRowPresentation
import dev.infusion.core.domain.home.HomeHeroPresentation
import dev.infusion.core.domain.home.HomeIdentityPresentation
import dev.infusion.core.domain.home.HomePresentationCodec
import dev.infusion.core.domain.home.HomePresentationPolicy
import dev.infusion.core.domain.home.HomePresentationStore
import dev.infusion.core.domain.home.HomeRowPresentation

class SharedPreferencesHomePresentationStore(
    context: Context,
    prefsName: String = PREFS_NAME,
) : HomePresentationStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun readRow(profileId: String, rowKey: String): HomeRowPresentation? {
        val decoded = HomePresentationCodec.decodeRow(
            prefs.getString(rowStorageKey(profileId, rowKey), null),
        ) ?: return null

        if (decoded.rowKey != rowKey) return null
        return decoded
    }

    override fun readRows(
        profileId: String,
        rowKeys: Collection<String>,
    ): Map<String, HomeRowPresentation> {
        if (rowKeys.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, HomeRowPresentation>(rowKeys.size)
        rowKeys.forEach { rowKey ->
            readRow(profileId, rowKey)?.let { out[rowKey] = it }
        }
        return out
    }

    override fun writeRow(profileId: String, row: HomeRowPresentation) {
        val payload = HomePresentationCodec.encodeRow(row) ?: return
        publish(profileId, rowStorageKey(profileId, row.rowKey), payload)
    }

    override fun readCollectionRow(profileId: String, rowKey: String): HomeCollectionRowPresentation? {
        val decoded = HomePresentationCodec.decodeCollectionRow(
            prefs.getString(groupStorageKey(profileId, rowKey), null),
        ) ?: return null
        if (decoded.rowKey != rowKey) return null
        return decoded
    }

    override fun writeCollectionRow(profileId: String, row: HomeCollectionRowPresentation) {
        val payload = HomePresentationCodec.encodeCollectionRow(row) ?: return
        publish(profileId, groupStorageKey(profileId, row.rowKey), payload)
    }

    override fun readPresentation(profileId: String, identityKey: String): HomeIdentityPresentation? {
        val decoded = HomePresentationCodec.decodePresentation(
            prefs.getString(cardKey(profileId, identityKey), null),
        ) ?: return null
        if (decoded.identityKey != identityKey) return null
        return decoded
    }

    override fun writePresentation(profileId: String, presentation: HomeIdentityPresentation) {
        val payload = HomePresentationCodec.encodePresentation(presentation) ?: return
        publish(profileId, cardKey(profileId, presentation.identityKey), payload)
    }

    override fun readHero(profileId: String, identityKey: String): HomeHeroPresentation? {
        val decoded = HomePresentationCodec.decodeHero(
            prefs.getString(heroKey(profileId, identityKey), null),
        ) ?: return null
        if (decoded.identityKey != identityKey) return null
        return decoded
    }

    override fun writeHero(profileId: String, hero: HomeHeroPresentation) {
        val payload = HomePresentationCodec.encodeHero(hero) ?: return
        publish(profileId, heroKey(profileId, hero.identityKey), payload)
    }

    override fun clearRow(profileId: String, rowKey: String) {
        prefs.edit()
            .remove(rowStorageKey(profileId, rowKey))
            .remove(groupStorageKey(profileId, rowKey))
            .apply()
    }

    override fun clearProfile(profileId: String) {
        val prefixes = listOf(
            "$ROW_KEY_PREFIX.$profileId.",
            "$GROUP_KEY_PREFIX.$profileId.",
            "$PRESENTATION_KEY_PREFIX.$profileId.",
            "$HERO_KEY_PREFIX.$profileId.",
        )
        prefs.edit().apply {
            prefs.all.keys
                .filter { key -> prefixes.any { key.startsWith(it) } }
                .forEach { remove(it) }
        }.apply()
    }

    private fun publish(profileId: String, key: String, payload: String) {
        val payloadBytes = payload.toByteArray(Charsets.UTF_8).size
        if (payloadBytes > HomePresentationPolicy.MAX_RECORD_BYTES) return
        prefs.edit().putString(key, payload).apply()
        evict(profileId, keep = key, keepBytes = payloadBytes)
    }

    private fun evict(profileId: String, keep: String, keepBytes: Int) {
        val kinds = listOf(
            EvictionKind(listOf(ROW_KEY_PREFIX, GROUP_KEY_PREFIX), HomePresentationPolicy.MAX_ROWS_PER_PROFILE),
            EvictionKind(listOf(PRESENTATION_KEY_PREFIX), HomePresentationPolicy.MAX_PRESENTATIONS_PER_PROFILE),
            EvictionKind(listOf(HERO_KEY_PREFIX), HomePresentationPolicy.MAX_HEROES_PER_PROFILE),
        ).map { kind ->
            kind to kind.prefixes.map { prefix -> "$prefix.$profileId." }
        }
        val owned = prefs.all.entries.mapNotNull { (key, value) ->
            if (key == keep) return@mapNotNull null
            val payload = value as? String ?: return@mapNotNull null
            val stamp = storedAtOf(payload) ?: return@mapNotNull null
            StoredRecord(key, stamp, payload.toByteArray(Charsets.UTF_8).size)
        }
        val removals = LinkedHashSet<String>()
        var remainingBytes = owned.filterNot { record -> record.key in removals }
            .sumOf { record -> record.bytes } + keepBytes

        kinds.forEach { (kind, prefixes) ->
            val ofKind = owned.filter { record -> prefixes.any { prefix -> record.key.startsWith(prefix) } }
                .sortedBy { record -> record.stamp }
            var count = ofKind.size + if (prefixes.any { keep.startsWith(it) }) 1 else 0
            for (record in ofKind) {
                if (count <= kind.max) break
                removals += record.key
                remainingBytes -= record.bytes
                count -= 1
            }
        }

        if (remainingBytes > HomePresentationPolicy.MAX_PROFILE_BYTES) {
            for (record in owned.filterNot { it.key in removals }.sortedBy { it.stamp }) {
                if (remainingBytes <= HomePresentationPolicy.MAX_PROFILE_BYTES) break
                removals += record.key
                remainingBytes -= record.bytes
            }
        }
        if (removals.isEmpty()) return
        val editor = prefs.edit()
        removals.forEach { key -> editor.remove(key) }
        editor.apply()
    }

    private class StoredRecord(val key: String, val stamp: Long, val bytes: Int)

    private class EvictionKind(val prefixes: List<String>, val max: Int)

    private fun storedAtOf(payload: String?): Long? {
        val text = payload ?: return null
        HomePresentationCodec.decodeRow(text)?.let { return it.storedAtEpochMs }
        HomePresentationCodec.decodeCollectionRow(text)?.let { return it.storedAtEpochMs }
        HomePresentationCodec.decodePresentation(text)?.let { return it.storedAtEpochMs }
        HomePresentationCodec.decodeHero(text)?.let { return it.storedAtEpochMs }
        return null
    }

    private fun rowStorageKey(profileId: String, rowKey: String): String =
        "$ROW_KEY_PREFIX.$profileId.${digest(rowKey)}"

    private fun groupStorageKey(profileId: String, rowKey: String): String =
        "$GROUP_KEY_PREFIX.$profileId.${digest(rowKey)}"

    private fun cardKey(profileId: String, identityKey: String): String =
        "$PRESENTATION_KEY_PREFIX.$profileId.${digest(identityKey)}"

    private fun heroKey(profileId: String, identityKey: String): String =
        "$HERO_KEY_PREFIX.$profileId.${digest(identityKey)}"

    private fun digest(value: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return buildString(32) {
            for (index in 0 until 16) {
                val unsigned = bytes[index].toInt() and 0xFF
                append(HEX[unsigned shr 4])
                append(HEX[unsigned and 0x0F])
            }
        }
    }

    companion object {

        const val PREFS_NAME: String = "infusion_home_presentation"

        const val ROW_KEY_PREFIX: String = "home_pres_row_v1"

        const val GROUP_KEY_PREFIX: String = "home_pres_group_v1"

        const val PRESENTATION_KEY_PREFIX: String = "home_pres_card_v1"

        const val HERO_KEY_PREFIX: String = "home_pres_hero_v1"

        private const val HEX: String = "0123456789abcdef"
    }
}
