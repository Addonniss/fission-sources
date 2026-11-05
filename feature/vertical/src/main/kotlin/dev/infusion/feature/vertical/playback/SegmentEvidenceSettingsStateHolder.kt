package dev.infusion.feature.vertical.playback

import dev.infusion.core.domain.playback.SegmentEvidencePreferenceStore
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.playback.api.SegmentDisplayMode
import dev.infusion.core.playback.api.SegmentEvidencePreferences
import dev.infusion.core.playback.api.SegmentEvidenceSettings
import dev.infusion.core.playback.api.SegmentFallbackTimecode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SegmentEvidenceSettingsStateHolder(
    private val store: SegmentEvidencePreferenceStore,
    private val profileScope: ProfileScope,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private var publishedProfileId: String = profileScope.activeProfileId

    private val _preferences = MutableStateFlow(readFor(publishedProfileId))

    val preferences: StateFlow<SegmentEvidencePreferences> = _preferences.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            profileScope.activeProfileFlow.collect { profileId ->
                if (profileId == publishedProfileId) return@collect
                publishedProfileId = profileId
                _preferences.value = readFor(profileId)
            }
        }
    }

    fun setOnlineIntroMetadataPriority(value: Boolean) {
        val current = _preferences.value
        if (current.onlineIntroMetadataPriority == value) return
        val updated = current.withOnlineIntroMetadataPriority(value)
        publish(updated) { profileId -> store.setOnlineIntroMetadataPriority(profileId, value.toString()) }
    }

    fun setManualFallbackEnabled(value: Boolean) {
        val current = _preferences.value
        if (current.manualFallbackEnabled == value) return
        val updated = current.withManualFallbackEnabled(value)
        publish(updated) { profileId -> store.setManualFallbackEnabled(profileId, value.toString()) }
    }

    fun setFallbackStart(value: SegmentFallbackTimecode) {
        val current = _preferences.value
        if (current.fallbackStart == value) return
        val updated = current.withFallbackStart(value)
        publish(updated) { profileId -> store.setManualFallbackStart(profileId, value.storageKey) }
    }

    fun setFallbackEnd(value: SegmentFallbackTimecode) {
        val current = _preferences.value
        if (current.fallbackEnd == value) return
        val updated = current.withFallbackEnd(value)
        publish(updated) { profileId -> store.setManualFallbackEnd(profileId, value.storageKey) }
    }

    fun setDisplayMode(value: SegmentDisplayMode) {
        val current = _preferences.value
        if (current.displayMode == value) return
        val updated = current.withDisplayMode(value)
        publish(updated) { profileId -> store.setSegmentDisplayMode(profileId, value.storageKey) }
    }

    fun setLegendEnabled(value: Boolean) {
        val current = _preferences.value
        if (current.legendEnabled == value) return
        val updated = current.withLegendEnabled(value)
        publish(updated) { profileId -> store.setSegmentLegendEnabled(profileId, value.toString()) }
    }

    fun setEnableSkipIntro(value: Boolean) {
        val current = _preferences.value
        if (current.enableSkipIntro == value) return
        publish(current.withEnableSkipIntro(value)) { profileId ->
            store.setEnableSkipIntro(profileId, value.toString())
        }
    }

    fun setAutoSkipIntro(value: Boolean) {
        val current = _preferences.value
        if (current.autoSkipIntro == value) return
        publish(current.withAutoSkipIntro(value)) { profileId ->
            store.setAutoSkipIntro(profileId, value.toString())
        }
    }

    fun setAutoSkipDelaySeconds(value: Int) {
        val current = _preferences.value
        if (current.autoSkipDelaySeconds == value) return
        val updated = current.withAutoSkipDelaySeconds(value)
        publish(updated) { profileId -> store.setAutoSkipDelaySeconds(profileId, updated.autoSkipDelaySeconds.toString()) }
    }

    fun setPersistentSkipButton(value: Boolean) {
        val current = _preferences.value
        if (current.persistentSkipButton == value) return
        publish(current.withPersistentSkipButton(value)) { profileId ->
            store.setPersistentSkipButton(profileId, value.toString())
        }
    }

    fun setEnableStingerAlerts(value: Boolean) {
        val current = _preferences.value
        if (current.enableStingerAlerts == value) return
        publish(current.withEnableStingerAlerts(value)) { profileId ->
            store.setEnableStingerAlerts(profileId, value.toString())
        }
    }

    fun setStingerTriggerPercent(value: Int) {
        val current = _preferences.value
        if (current.stingerTriggerPercent == value) return
        val updated = current.withStingerTriggerPercent(value)
        publish(updated) { profileId -> store.setStingerTriggerPercent(profileId, updated.stingerTriggerPercent.toString()) }
    }

    fun setStingerEarlyNotifySeconds(value: Int) {
        val current = _preferences.value
        if (current.stingerEarlyNotifySeconds == value) return
        val updated = current.withStingerEarlyNotifySeconds(value)
        publish(updated) { profileId -> store.setStingerEarlyNotifySeconds(profileId, updated.stingerEarlyNotifySeconds.toString()) }
    }

    fun setStingerToastDurationSeconds(value: Int) {
        val current = _preferences.value
        if (current.stingerToastDurationSeconds == value) return
        val updated = current.withStingerToastDurationSeconds(value)
        publish(updated) { profileId -> store.setStingerToastDurationSeconds(profileId, updated.stingerToastDurationSeconds.toString()) }
    }

    private fun publish(
        updated: SegmentEvidencePreferences,
        persist: (profileId: String) -> Unit,
    ) {
        val profileId = profileScope.activeProfileId
        _preferences.value = updated
        scope.launch(ioDispatcher) { persist(profileId) }
    }

    private fun readFor(profileId: String): SegmentEvidencePreferences =
        SegmentEvidencePreferences(
            onlineIntroMetadataPriority = parseBoolean(
                store.onlineIntroMetadataPriority(profileId),
                default = SegmentEvidencePreferences.DEFAULT.onlineIntroMetadataPriority,
            ),
            manualFallbackEnabled = parseBoolean(
                store.manualFallbackEnabled(profileId),
                default = SegmentEvidencePreferences.DEFAULT.manualFallbackEnabled,
            ),
            fallbackStart = SegmentFallbackTimecode.resolveStart(store.manualFallbackStart(profileId)),
            fallbackEnd = SegmentFallbackTimecode.resolveEnd(store.manualFallbackEnd(profileId)),
            displayMode = SegmentDisplayMode.resolve(store.segmentDisplayMode(profileId)),
            legendEnabled = parseBoolean(
                store.segmentLegendEnabled(profileId),
                default = SegmentEvidencePreferences.DEFAULT.legendEnabled,
            ),

            enableSkipIntro = parseBoolean(
                store.enableSkipIntro(profileId),
                default = SegmentEvidencePreferences.DEFAULT.enableSkipIntro,
            ),
            autoSkipIntro = parseBoolean(
                store.autoSkipIntro(profileId),
                default = SegmentEvidencePreferences.DEFAULT.autoSkipIntro,
            ),
            autoSkipDelaySeconds = parseBoundedInt(
                store.autoSkipDelaySeconds(profileId),
                default = SegmentEvidencePreferences.DEFAULT.autoSkipDelaySeconds,
                min = SegmentEvidenceSettings.AUTO_SKIP_DELAY_MIN,
                max = SegmentEvidenceSettings.AUTO_SKIP_DELAY_MAX,
            ),
            persistentSkipButton = parseBoolean(
                store.persistentSkipButton(profileId),
                default = SegmentEvidencePreferences.DEFAULT.persistentSkipButton,
            ),

            enableStingerAlerts = parseBoolean(
                store.enableStingerAlerts(profileId),
                default = SegmentEvidencePreferences.DEFAULT.enableStingerAlerts,
            ),
            stingerTriggerPercent = parseBoundedInt(
                store.stingerTriggerPercent(profileId),
                default = SegmentEvidencePreferences.DEFAULT.stingerTriggerPercent,
                min = SegmentEvidenceSettings.STINGER_PERCENT_MIN,
                max = SegmentEvidenceSettings.STINGER_PERCENT_MAX,
            ),
            stingerEarlyNotifySeconds = parseBoundedInt(
                store.stingerEarlyNotifySeconds(profileId),
                default = SegmentEvidencePreferences.DEFAULT.stingerEarlyNotifySeconds,
                min = SegmentEvidenceSettings.STINGER_EARLY_MIN,
                max = SegmentEvidenceSettings.STINGER_EARLY_MAX,
            ),
            stingerToastDurationSeconds = parseBoundedInt(
                store.stingerToastDurationSeconds(profileId),
                default = SegmentEvidencePreferences.DEFAULT.stingerToastDurationSeconds,
                min = SegmentEvidenceSettings.STINGER_TOAST_MIN,
                max = SegmentEvidenceSettings.STINGER_TOAST_MAX,
            ),
        )

    private fun parseBoolean(stored: String?, default: Boolean): Boolean = when (stored?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> default
    }

    private fun parseBoundedInt(stored: String?, default: Int, min: Int, max: Int): Int {
        val parsed = stored?.trim()?.toIntOrNull() ?: return default
        return parsed.coerceIn(min, max)
    }
}
