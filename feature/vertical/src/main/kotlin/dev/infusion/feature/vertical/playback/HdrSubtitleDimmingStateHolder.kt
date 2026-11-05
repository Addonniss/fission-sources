package dev.infusion.feature.vertical.playback

import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.playback.api.HdrSubtitleDimming
import dev.infusion.core.playback.api.HdrSubtitleDimmingPreferenceStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HdrSubtitleDimmingStateHolder(
    private val store: HdrSubtitleDimmingPreferenceStore,
    private val profileScope: ProfileScope,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private var publishedProfileId: String = profileScope.activeProfileId

    private val _dimming = MutableStateFlow(readFor(publishedProfileId))

    val dimming: StateFlow<HdrSubtitleDimming> = _dimming.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            profileScope.activeProfileFlow.collect { profileId ->

                if (profileId == publishedProfileId) return@collect
                publishedProfileId = profileId
                _dimming.value = readFor(profileId)
            }
        }
    }

    fun setDimming(dimming: HdrSubtitleDimming) {
        if (dimming == _dimming.value) return
        val profileId = profileScope.activeProfileId
        _dimming.value = dimming
        scope.launch(ioDispatcher) { store.setDimming(profileId, dimming) }
    }

    private fun readFor(profileId: String): HdrSubtitleDimming = store.dimmingFor(profileId)
}
