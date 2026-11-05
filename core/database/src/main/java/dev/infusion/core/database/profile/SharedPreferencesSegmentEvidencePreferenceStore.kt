package dev.infusion.core.database.profile

import android.content.Context
import android.content.SharedPreferences
import dev.infusion.core.domain.playback.SegmentEvidencePreferenceStore

class SharedPreferencesSegmentEvidencePreferenceStore(
    context: Context,
    prefsName: String = PREFS_NAME,
) : SegmentEvidencePreferenceStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun onlineIntroMetadataPriority(profileId: String): String? =
        prefs.getString(key(KEY_ONLINE_PRIORITY, profileId), null)

    override fun setOnlineIntroMetadataPriority(profileId: String, value: String?) =
        write(key(KEY_ONLINE_PRIORITY, profileId), value)

    override fun manualFallbackEnabled(profileId: String): String? =
        prefs.getString(key(KEY_FALLBACK_ENABLED, profileId), null)

    override fun setManualFallbackEnabled(profileId: String, value: String?) =
        write(key(KEY_FALLBACK_ENABLED, profileId), value)

    override fun manualFallbackStart(profileId: String): String? =
        prefs.getString(key(KEY_FALLBACK_START, profileId), null)

    override fun setManualFallbackStart(profileId: String, value: String?) =
        write(key(KEY_FALLBACK_START, profileId), value)

    override fun manualFallbackEnd(profileId: String): String? =
        prefs.getString(key(KEY_FALLBACK_END, profileId), null)

    override fun setManualFallbackEnd(profileId: String, value: String?) =
        write(key(KEY_FALLBACK_END, profileId), value)

    override fun segmentDisplayMode(profileId: String): String? =
        prefs.getString(key(KEY_DISPLAY_MODE, profileId), null)

    override fun setSegmentDisplayMode(profileId: String, value: String?) =
        write(key(KEY_DISPLAY_MODE, profileId), value)

    override fun segmentLegendEnabled(profileId: String): String? =
        prefs.getString(key(KEY_LEGEND_ENABLED, profileId), null)

    override fun setSegmentLegendEnabled(profileId: String, value: String?) =
        write(key(KEY_LEGEND_ENABLED, profileId), value)

    override fun enableSkipIntro(profileId: String): String? =
        prefs.getString(key(KEY_ENABLE_SKIP_INTRO, profileId), null)

    override fun setEnableSkipIntro(profileId: String, value: String?) =
        write(key(KEY_ENABLE_SKIP_INTRO, profileId), value)

    override fun autoSkipIntro(profileId: String): String? =
        prefs.getString(key(KEY_AUTO_SKIP_INTRO, profileId), null)

    override fun setAutoSkipIntro(profileId: String, value: String?) =
        write(key(KEY_AUTO_SKIP_INTRO, profileId), value)

    override fun autoSkipDelaySeconds(profileId: String): String? =
        prefs.getString(key(KEY_AUTO_SKIP_DELAY, profileId), null)

    override fun setAutoSkipDelaySeconds(profileId: String, value: String?) =
        write(key(KEY_AUTO_SKIP_DELAY, profileId), value)

    override fun persistentSkipButton(profileId: String): String? =
        prefs.getString(key(KEY_PERSISTENT_BUTTON, profileId), null)

    override fun setPersistentSkipButton(profileId: String, value: String?) =
        write(key(KEY_PERSISTENT_BUTTON, profileId), value)

    override fun enableStingerAlerts(profileId: String): String? =
        prefs.getString(key(KEY_ENABLE_STINGER, profileId), null)

    override fun setEnableStingerAlerts(profileId: String, value: String?) =
        write(key(KEY_ENABLE_STINGER, profileId), value)

    override fun stingerTriggerPercent(profileId: String): String? =
        prefs.getString(key(KEY_STINGER_PERCENT, profileId), null)

    override fun setStingerTriggerPercent(profileId: String, value: String?) =
        write(key(KEY_STINGER_PERCENT, profileId), value)

    override fun stingerEarlyNotifySeconds(profileId: String): String? =
        prefs.getString(key(KEY_STINGER_EARLY, profileId), null)

    override fun setStingerEarlyNotifySeconds(profileId: String, value: String?) =
        write(key(KEY_STINGER_EARLY, profileId), value)

    override fun stingerToastDurationSeconds(profileId: String): String? =
        prefs.getString(key(KEY_STINGER_TOAST, profileId), null)

    override fun setStingerToastDurationSeconds(profileId: String, value: String?) =
        write(key(KEY_STINGER_TOAST, profileId), value)

    override fun clearForProfile(profileId: String) {
        prefs.edit().apply {
            remove(key(KEY_ONLINE_PRIORITY, profileId))
            remove(key(KEY_FALLBACK_ENABLED, profileId))
            remove(key(KEY_FALLBACK_START, profileId))
            remove(key(KEY_FALLBACK_END, profileId))
            remove(key(KEY_DISPLAY_MODE, profileId))
            remove(key(KEY_LEGEND_ENABLED, profileId))
            remove(key(KEY_ENABLE_SKIP_INTRO, profileId))
            remove(key(KEY_AUTO_SKIP_INTRO, profileId))
            remove(key(KEY_AUTO_SKIP_DELAY, profileId))
            remove(key(KEY_PERSISTENT_BUTTON, profileId))
            remove(key(KEY_ENABLE_STINGER, profileId))
            remove(key(KEY_STINGER_PERCENT, profileId))
            remove(key(KEY_STINGER_EARLY, profileId))
            remove(key(KEY_STINGER_TOAST, profileId))
        }.apply()
    }

    private fun write(fullKey: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(fullKey) else putString(fullKey, value)
        }.apply()
    }

    private fun key(prefix: String, profileId: String): String = "$prefix$profileId"

    private companion object {
        const val PREFS_NAME = "infusion_playback_segment_evidence_preferences"
        const val KEY_ONLINE_PRIORITY = "segment_online_intro_priority:"
        const val KEY_FALLBACK_ENABLED = "segment_manual_fallback_enabled:"
        const val KEY_FALLBACK_START = "segment_manual_fallback_start:"
        const val KEY_FALLBACK_END = "segment_manual_fallback_end:"
        const val KEY_DISPLAY_MODE = "segment_display_mode:"
        const val KEY_LEGEND_ENABLED = "segment_legend_enabled:"

        const val KEY_ENABLE_SKIP_INTRO = "segment_enable_skip_intro:"
        const val KEY_AUTO_SKIP_INTRO = "segment_auto_skip_intro:"
        const val KEY_AUTO_SKIP_DELAY = "segment_auto_skip_delay:"
        const val KEY_PERSISTENT_BUTTON = "segment_persistent_button:"
        const val KEY_ENABLE_STINGER = "segment_enable_stinger_movies:"
        const val KEY_STINGER_PERCENT = "segment_stinger_trigger_percent:"
        const val KEY_STINGER_EARLY = "segment_stinger_early_notify_seconds:"
        const val KEY_STINGER_TOAST = "segment_stinger_toast_duration:"
    }
}
