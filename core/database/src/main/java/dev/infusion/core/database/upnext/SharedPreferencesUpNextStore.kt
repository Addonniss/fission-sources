package dev.infusion.core.database.upnext

import android.content.Context
import android.content.SharedPreferences
import dev.infusion.core.domain.upnext.UpNextCacheStore
import dev.infusion.core.domain.upnext.UpNextCachedAnswer
import dev.infusion.core.domain.upnext.UpNextCodec
import dev.infusion.core.domain.upnext.UpNextPresentationStore
import dev.infusion.core.domain.upnext.UpNextSelection
import dev.infusion.core.domain.upnext.UpNextSelectionStore

class SharedPreferencesUpNextStore(
    context: Context,
    prefsName: String = PREFS_NAME,
) : UpNextSelectionStore, UpNextCacheStore, UpNextPresentationStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun read(profileId: String): UpNextSelection =

        UpNextCodec.decodeSelection(prefs.getString(selectionKey(profileId), null))

    override fun write(profileId: String, selection: UpNextSelection) {
        val payload = UpNextCodec.encodeSelection(selection)
        val editor = prefs.edit()
        if (payload == null) {

            editor.remove(selectionKey(profileId))
        } else {
            editor.putString(selectionKey(profileId), payload)
        }
        editor.apply()
    }

    override fun clearProfile(profileId: String) {
        val prefix = "$CACHE_KEY_PREFIX.$profileId."
        prefs.edit().apply {
            remove(selectionKey(profileId))
            remove(presentationKey(profileId))
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
        }.apply()
    }

    override fun showUpNext(profileId: String): Boolean =

        prefs.getBoolean(presentationKey(profileId), true)

    override fun setShowUpNext(profileId: String, show: Boolean) {
        prefs.edit().putBoolean(presentationKey(profileId), show).apply()
    }

    override fun read(profileId: String, trackerId: String, accountLabel: String?): UpNextCachedAnswer? =
        UpNextCodec.decodeCache(
            stored = prefs.getString(cacheKey(profileId, trackerId, accountLabel), null),
            profileId = profileId,
            trackerId = trackerId,
            accountLabel = accountLabel,
        )

    override fun write(answer: UpNextCachedAnswer) {
        prefs.edit()
            .putString(
                cacheKey(answer.profileId, answer.trackerId, answer.accountLabel),
                UpNextCodec.encodeCache(answer),
            )
            .apply()
    }

    override fun clearAccount(profileId: String, trackerId: String, accountLabel: String?) {
        prefs.edit().remove(cacheKey(profileId, trackerId, accountLabel)).apply()
    }

    override fun clearOtherAccounts(profileId: String, trackerId: String, accountLabel: String?) {
        val prefix = "$CACHE_KEY_PREFIX.$profileId.${tokenize(trackerId)}."
        val keep = cacheKey(profileId, trackerId, accountLabel)
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(prefix) && it != keep }.forEach { remove(it) }
        }.apply()
    }

    private fun selectionKey(profileId: String) = "$SELECTION_KEY_PREFIX.$profileId"

    private fun presentationKey(profileId: String) = "$PRESENTATION_KEY_PREFIX.$profileId"

    private fun cacheKey(profileId: String, trackerId: String, accountLabel: String?): String =
        "$CACHE_KEY_PREFIX.$profileId.${tokenize(trackerId)}.${accountToken(accountLabel)}"

    private fun tokenize(value: String): String = accountToken(value)

    private fun accountToken(label: String?): String {
        val text = label?.trim()?.takeIf { it.isNotEmpty() } ?: return "none"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }.take(32)
    }

    companion object {

        const val PREFS_NAME: String = "infusion_home_up_next"

        const val SELECTION_KEY_PREFIX: String = "home_up_next_v1"

        const val CACHE_KEY_PREFIX: String = "home_up_next_cache_v1"

        const val PRESENTATION_KEY_PREFIX: String = "home_up_next_shown_v1"
    }
}
