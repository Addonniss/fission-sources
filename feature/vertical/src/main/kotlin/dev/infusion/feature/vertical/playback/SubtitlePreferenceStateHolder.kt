package dev.infusion.feature.vertical.playback

import dev.infusion.core.domain.playback.PlaybackLanguagePreferenceStore
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.playback.api.SubtitleLanguagePreference
import dev.infusion.core.playback.api.SubtitleModePreference
import dev.infusion.core.playback.api.SubtitlePreferences
import dev.infusion.core.playback.api.SubtitleSameLanguagePolicy
import dev.infusion.core.playback.api.SubtitleTypePreference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SubtitlePreferenceStateHolder(
    private val store: PlaybackLanguagePreferenceStore,
    private val profileScope: ProfileScope,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private var publishedProfileId: String = profileScope.activeProfileId

    private val _preferences = MutableStateFlow(readFor(publishedProfileId))

    val preferences: StateFlow<SubtitlePreferences> = _preferences.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            profileScope.activeProfileFlow.collect { profileId ->

                if (profileId == publishedProfileId) return@collect
                publishedProfileId = profileId
                _preferences.value = readFor(profileId)
            }
        }
    }

    fun setMode(mode: SubtitleModePreference) {
        update(_preferences.value.copy(mode = mode))
    }

    fun setPrimary(preference: SubtitleLanguagePreference) {
        update(_preferences.value.withPrimary(preference))
    }

    fun setSecondary(preference: SubtitleLanguagePreference) {
        update(_preferences.value.withSecondary(preference))
    }

    fun setSameLanguage(policy: SubtitleSameLanguagePolicy) {
        update(_preferences.value.copy(sameLanguage = policy))
    }

    fun setType(type: SubtitleTypePreference) {
        update(_preferences.value.copy(type = type))
    }

    fun reload() {
        _preferences.value = readFor(profileScope.activeProfileId)
    }

    private fun update(updated: SubtitlePreferences) {
        if (updated == _preferences.value) return
        val profileId = profileScope.activeProfileId
        _preferences.value = updated
        scope.launch(ioDispatcher) { persist(profileId, updated) }
    }

    private fun persist(profileId: String, preferences: SubtitlePreferences) {
        store.setSubtitleModePreference(profileId, preferences.mode.name)

        store.setSubtitleLanguagePreference(
            profileId,
            SubtitlePreferences.storedTag(preferences.primary),
        )
        store.setSecondarySubtitleLanguagePreference(
            profileId,
            SubtitlePreferences.storedTag(preferences.secondary),
        )
        store.setSubtitleSameLanguagePolicy(profileId, preferences.sameLanguage.name)
        store.setSubtitleTypePreference(profileId, preferences.type.name)
    }

    private fun readFor(profileId: String): SubtitlePreferences = SubtitlePreferences.fromStored(
        mode = store.subtitleModePreference(profileId),
        primaryTag = store.subtitleLanguagePreference(profileId),
        secondaryTag = store.secondarySubtitleLanguagePreference(profileId),
        sameLanguage = store.subtitleSameLanguagePolicy(profileId),
        type = store.subtitleTypePreference(profileId),
    )
}
