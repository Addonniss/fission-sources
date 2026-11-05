package dev.infusion.feature.vertical.playback

import dev.infusion.core.domain.playback.PlaybackControlsAutoHidePreferenceStore
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.playback.api.PlaybackControlsAutoHide
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlaybackControlsAutoHideStateHolder(
    private val store: PlaybackControlsAutoHidePreferenceStore,
    private val profileScope: ProfileScope,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private var publishedProfileId: String = profileScope.activeProfileId

    private val _autoHide = MutableStateFlow(readFor(publishedProfileId))

    val autoHide: StateFlow<PlaybackControlsAutoHide> = _autoHide.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            profileScope.activeProfileFlow.collect { profileId ->

                if (profileId == publishedProfileId) return@collect
                publishedProfileId = profileId
                _autoHide.value = readFor(profileId)
            }
        }
    }

    fun set(value: PlaybackControlsAutoHide) {
        if (value == _autoHide.value) return
        val profileId = profileScope.activeProfileId
        _autoHide.value = value
        scope.launch(ioDispatcher) { store.setControlsAutoHide(profileId, value.storageKey) }
    }

    private fun readFor(profileId: String): PlaybackControlsAutoHide =
        PlaybackControlsAutoHide.orDefault(store.controlsAutoHide(profileId))
}
