package dev.infusion.core.database.profile

import android.content.Context
import android.content.SharedPreferences
import dev.infusion.core.domain.playback.CompletionThresholdPreferenceStore

class SharedPreferencesCompletionThresholdPreferenceStore(
    context: Context,
    prefsName: String = PREFS_NAME,
) : CompletionThresholdPreferenceStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun movieCompletionThreshold(profileId: String): String? =
        prefs.getString(movieKey(profileId), null)

    override fun setMovieCompletionThreshold(profileId: String, percent: String?) {
        prefs.edit().apply {
            if (percent == null) remove(movieKey(profileId)) else putString(movieKey(profileId), percent)
        }.apply()
    }

    override fun episodeCompletionThreshold(profileId: String): String? =
        prefs.getString(episodeKey(profileId), null)

    override fun setEpisodeCompletionThreshold(profileId: String, percent: String?) {
        prefs.edit().apply {
            if (percent == null) remove(episodeKey(profileId)) else putString(episodeKey(profileId), percent)
        }.apply()
    }

    override fun clearForProfile(profileId: String) {
        prefs.edit().apply {
            remove(movieKey(profileId))
            remove(episodeKey(profileId))
        }.apply()
    }

    private fun movieKey(profileId: String): String = "$MOVIE_PREFIX$profileId"

    private fun episodeKey(profileId: String): String = "$EPISODE_PREFIX$profileId"

    private companion object {
        const val PREFS_NAME = "infusion_playback_completion_preferences"
        const val MOVIE_PREFIX = "completion_movie:"
        const val EPISODE_PREFIX = "completion_episode:"
    }
}
