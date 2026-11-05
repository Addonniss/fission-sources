package dev.infusion.core.domain.playback

interface CompletionThresholdPreferenceStore {

    fun movieCompletionThreshold(profileId: String): String?

    fun setMovieCompletionThreshold(profileId: String, percent: String?)

    fun episodeCompletionThreshold(profileId: String): String?

    fun setEpisodeCompletionThreshold(profileId: String, percent: String?)

    fun clearForProfile(profileId: String)
}
