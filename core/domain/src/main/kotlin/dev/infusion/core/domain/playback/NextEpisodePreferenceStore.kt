package dev.infusion.core.domain.playback

interface NextEpisodePreferenceStore {

    fun enableNextEpisode(profileId: String): String?

    fun setEnableNextEpisode(profileId: String, value: String?)

    fun onlineNextMetadataPriority(profileId: String): String?

    fun setOnlineNextMetadataPriority(profileId: String, value: String?)

    fun autoPlayNextEpisode(profileId: String): String?

    fun setAutoPlayNextEpisode(profileId: String, value: String?)

    fun autoPlayDelaySeconds(profileId: String): String?

    fun setAutoPlayDelaySeconds(profileId: String, value: String?)

    fun fallbackTriggerPercent(profileId: String): String?

    fun setFallbackTriggerPercent(profileId: String, value: String?)

    fun clearForProfile(profileId: String)
}
