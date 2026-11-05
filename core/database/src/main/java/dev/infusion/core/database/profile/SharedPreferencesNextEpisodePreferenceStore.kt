package dev.infusion.core.database.profile

import android.content.Context
import android.content.SharedPreferences
import dev.infusion.core.domain.playback.NextEpisodePreferenceStore

class SharedPreferencesNextEpisodePreferenceStore(
    context: Context,
    prefsName: String = PREFS_NAME,
) : NextEpisodePreferenceStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun enableNextEpisode(profileId: String): String? =
        prefs.getString(key(KEY_ENABLED, profileId), null)

    override fun setEnableNextEpisode(profileId: String, value: String?) =
        write(key(KEY_ENABLED, profileId), value)

    override fun onlineNextMetadataPriority(profileId: String): String? =
        prefs.getString(key(KEY_ONLINE_PRIORITY, profileId), null)

    override fun setOnlineNextMetadataPriority(profileId: String, value: String?) =
        write(key(KEY_ONLINE_PRIORITY, profileId), value)

    override fun autoPlayNextEpisode(profileId: String): String? =
        prefs.getString(key(KEY_AUTO_PLAY, profileId), null)

    override fun setAutoPlayNextEpisode(profileId: String, value: String?) =
        write(key(KEY_AUTO_PLAY, profileId), value)

    override fun autoPlayDelaySeconds(profileId: String): String? =
        prefs.getString(key(KEY_AUTO_PLAY_DELAY, profileId), null)

    override fun setAutoPlayDelaySeconds(profileId: String, value: String?) =
        write(key(KEY_AUTO_PLAY_DELAY, profileId), value)

    override fun fallbackTriggerPercent(profileId: String): String? =
        prefs.getString(key(KEY_FALLBACK_PERCENT, profileId), null)

    override fun setFallbackTriggerPercent(profileId: String, value: String?) =
        write(key(KEY_FALLBACK_PERCENT, profileId), value)

    override fun clearForProfile(profileId: String) {
        prefs.edit().apply {
            remove(key(KEY_ENABLED, profileId))
            remove(key(KEY_ONLINE_PRIORITY, profileId))
            remove(key(KEY_AUTO_PLAY, profileId))
            remove(key(KEY_AUTO_PLAY_DELAY, profileId))
            remove(key(KEY_FALLBACK_PERCENT, profileId))
        }.apply()
    }

    private fun write(fullKey: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(fullKey) else putString(fullKey, value)
        }.apply()
    }

    private fun key(prefix: String, profileId: String): String = "$prefix$profileId"

    private companion object {
        const val PREFS_NAME = "infusion_playback_next_episode_preferences"
        const val KEY_ENABLED = "next_episode_enabled:"
        const val KEY_ONLINE_PRIORITY = "next_episode_online_priority:"
        const val KEY_AUTO_PLAY = "next_episode_auto_play:"
        const val KEY_AUTO_PLAY_DELAY = "next_episode_auto_play_delay:"
        const val KEY_FALLBACK_PERCENT = "next_episode_fallback_percent:"
    }
}
