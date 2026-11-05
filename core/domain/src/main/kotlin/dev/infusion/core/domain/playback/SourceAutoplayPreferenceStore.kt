package dev.infusion.core.domain.playback

interface SourceAutoplayPreferenceStore {

    fun policy(profileId: String): String?

    fun setPolicy(profileId: String, value: String?)

    fun preferJellyfin(profileId: String): String?

    fun setPreferJellyfin(profileId: String, value: String?)

    fun clearForProfile(profileId: String)
}
