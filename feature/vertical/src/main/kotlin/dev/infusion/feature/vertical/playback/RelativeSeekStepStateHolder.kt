package dev.infusion.feature.vertical.playback

import dev.infusion.core.domain.playback.RelativeSeekStepPreferenceStore
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.playback.api.RelativeSeekStep
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RelativeSeekStepStateHolder(
    private val store: RelativeSeekStepPreferenceStore,
    private val profileScope: ProfileScope,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private var publishedProfileId: String = profileScope.activeProfileId

    private val _step = MutableStateFlow(readFor(publishedProfileId))

    val step: StateFlow<RelativeSeekStep> = _step.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            profileScope.activeProfileFlow.collect { profileId ->

                if (profileId == publishedProfileId) return@collect
                publishedProfileId = profileId
                _step.value = readFor(profileId)
            }
        }
    }

    fun set(value: RelativeSeekStep) {
        if (value == _step.value) return
        val profileId = profileScope.activeProfileId
        _step.value = value
        scope.launch(ioDispatcher) { store.setRelativeSeekStep(profileId, value.storageKey) }
    }

    private fun readFor(profileId: String): RelativeSeekStep =
        RelativeSeekStep.orDefault(store.relativeSeekStep(profileId))
}
