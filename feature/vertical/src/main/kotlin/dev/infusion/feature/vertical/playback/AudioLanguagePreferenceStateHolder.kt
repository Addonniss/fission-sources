package dev.infusion.feature.vertical.playback

import dev.infusion.core.domain.playback.PlaybackLanguagePreferenceStore
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.playback.api.AudioLanguagePreference
import dev.infusion.core.playback.api.AudioLanguagePreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AudioLanguagePreferenceStateHolder(
    private val store: PlaybackLanguagePreferenceStore,
    private val profileScope: ProfileScope,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private var publishedProfileId: String = profileScope.activeProfileId

    private val _preferences = MutableStateFlow(readFor(publishedProfileId))

    val preferences: StateFlow<AudioLanguagePreferences> = _preferences.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            profileScope.activeProfileFlow.collect { profileId ->

                if (profileId == publishedProfileId) return@collect
                publishedProfileId = profileId
                _preferences.value = readFor(profileId)
            }
        }
    }

    fun setPrimary(preference: AudioLanguagePreference) {
        update(_preferences.value.withPrimary(preference))
    }

    fun setSecondary(preference: AudioLanguagePreference) {
        update(_preferences.value.withSecondary(preference))
    }

    fun reload() {
        _preferences.value = readFor(profileScope.activeProfileId)
    }

    private fun update(updated: AudioLanguagePreferences) {
        if (updated == _preferences.value) return
        val profileId = profileScope.activeProfileId
        _preferences.value = updated
        scope.launch(ioDispatcher) { persist(profileId, updated) }
    }

    private fun persist(profileId: String, preferences: AudioLanguagePreferences) {
        store.setAudioLanguagePreference(profileId, (preferences.primary as? AudioLanguagePreference.Tag)?.value)
        store.setSecondaryAudioLanguagePreference(
            profileId,
            (preferences.secondary as? AudioLanguagePreference.Tag)?.value,
        )
    }

    private fun readFor(profileId: String): AudioLanguagePreferences =
        AudioLanguagePreferences.fromStoredTags(
            primaryTag = store.audioLanguagePreference(profileId),
            secondaryTag = store.secondaryAudioLanguagePreference(profileId),
        )
}
