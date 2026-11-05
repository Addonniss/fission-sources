package dev.infusion.feature.vertical.playback

import dev.infusion.core.domain.playback.NextEpisodePreferenceStore
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.playback.api.NextEpisodePreferences
import dev.infusion.core.playback.api.NextEpisodeSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NextEpisodeSettingsStateHolder(
    private val store: NextEpisodePreferenceStore,
    private val profileScope: ProfileScope,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private var publishedProfileId: String = profileScope.activeProfileId

    private val _preferences = MutableStateFlow(readFor(publishedProfileId))

    val preferences: StateFlow<NextEpisodePreferences> = _preferences.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            profileScope.activeProfileFlow.collect { profileId ->
                if (profileId == publishedProfileId) return@collect
                publishedProfileId = profileId
                _preferences.value = readFor(profileId)
            }
        }
    }

    fun setEnableNextEpisode(value: Boolean) {
        val current = _preferences.value
        if (current.enableNextEpisode == value) return
        publish(current.withEnableNextEpisode(value)) { profileId ->
            store.setEnableNextEpisode(profileId, value.toString())
        }
    }

    fun setOnlineNextMetadataPriority(value: Boolean) {
        val current = _preferences.value
        if (current.onlineNextMetadataPriority == value) return
        publish(current.withOnlineNextMetadataPriority(value)) { profileId ->
            store.setOnlineNextMetadataPriority(profileId, value.toString())
        }
    }

    fun setAutoPlayNextEpisode(value: Boolean) {
        val current = _preferences.value
        if (current.autoPlayNextEpisode == value) return
        publish(current.withAutoPlayNextEpisode(value)) { profileId ->
            store.setAutoPlayNextEpisode(profileId, value.toString())
        }
    }

    fun setAutoPlayDelaySeconds(value: Int) {
        val current = _preferences.value
        if (current.autoPlayDelaySeconds == value) return
        val updated = current.withAutoPlayDelaySeconds(value)
        publish(updated) { profileId ->
            store.setAutoPlayDelaySeconds(profileId, updated.autoPlayDelaySeconds.toString())
        }
    }

    fun setFallbackTriggerPercent(value: Int) {
        val current = _preferences.value
        if (current.fallbackTriggerPercent == value) return
        val updated = current.withFallbackTriggerPercent(value)
        publish(updated) { profileId ->
            store.setFallbackTriggerPercent(profileId, updated.fallbackTriggerPercent.toString())
        }
    }

    private fun publish(
        updated: NextEpisodePreferences,
        persist: (profileId: String) -> Unit,
    ) {
        val profileId = profileScope.activeProfileId
        _preferences.value = updated
        scope.launch(ioDispatcher) { persist(profileId) }
    }

    private fun readFor(profileId: String): NextEpisodePreferences = NextEpisodePreferences(
        enableNextEpisode = parseBoolean(
            store.enableNextEpisode(profileId),
            default = NextEpisodePreferences.DEFAULT.enableNextEpisode,
        ),
        onlineNextMetadataPriority = parseBoolean(
            store.onlineNextMetadataPriority(profileId),
            default = NextEpisodePreferences.DEFAULT.onlineNextMetadataPriority,
        ),
        autoPlayNextEpisode = parseBoolean(
            store.autoPlayNextEpisode(profileId),
            default = NextEpisodePreferences.DEFAULT.autoPlayNextEpisode,
        ),
        autoPlayDelaySeconds = parseBoundedInt(
            store.autoPlayDelaySeconds(profileId),
            default = NextEpisodePreferences.DEFAULT.autoPlayDelaySeconds,
            min = NextEpisodeSettings.AUTO_PLAY_DELAY_MIN,
            max = NextEpisodeSettings.AUTO_PLAY_DELAY_MAX,
        ),
        fallbackTriggerPercent = parseBoundedInt(
            store.fallbackTriggerPercent(profileId),
            default = NextEpisodePreferences.DEFAULT.fallbackTriggerPercent,
            min = NextEpisodeSettings.FALLBACK_PERCENT_MIN,
            max = NextEpisodeSettings.FALLBACK_PERCENT_MAX,
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
