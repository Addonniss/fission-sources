package dev.infusion.core.domain.playback

interface SegmentEvidencePreferenceStore {

    fun onlineIntroMetadataPriority(profileId: String): String?

    fun setOnlineIntroMetadataPriority(profileId: String, value: String?)

    fun manualFallbackEnabled(profileId: String): String?

    fun setManualFallbackEnabled(profileId: String, value: String?)

    fun manualFallbackStart(profileId: String): String?

    fun setManualFallbackStart(profileId: String, value: String?)

    fun manualFallbackEnd(profileId: String): String?

    fun setManualFallbackEnd(profileId: String, value: String?)

    fun segmentDisplayMode(profileId: String): String?

    fun setSegmentDisplayMode(profileId: String, value: String?)

    fun segmentLegendEnabled(profileId: String): String?

    fun setSegmentLegendEnabled(profileId: String, value: String?)

    fun enableSkipIntro(profileId: String): String?

    fun setEnableSkipIntro(profileId: String, value: String?)

    fun autoSkipIntro(profileId: String): String?

    fun setAutoSkipIntro(profileId: String, value: String?)

    fun autoSkipDelaySeconds(profileId: String): String?

    fun setAutoSkipDelaySeconds(profileId: String, value: String?)

    fun persistentSkipButton(profileId: String): String?

    fun setPersistentSkipButton(profileId: String, value: String?)

    fun enableStingerAlerts(profileId: String): String?

    fun setEnableStingerAlerts(profileId: String, value: String?)

    fun stingerTriggerPercent(profileId: String): String?

    fun setStingerTriggerPercent(profileId: String, value: String?)

    fun stingerEarlyNotifySeconds(profileId: String): String?

    fun setStingerEarlyNotifySeconds(profileId: String, value: String?)

    fun stingerToastDurationSeconds(profileId: String): String?

    fun setStingerToastDurationSeconds(profileId: String, value: String?)

    fun clearForProfile(profileId: String)
}
