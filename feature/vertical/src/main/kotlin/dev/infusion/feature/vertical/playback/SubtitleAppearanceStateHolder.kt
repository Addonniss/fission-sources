package dev.infusion.feature.vertical.playback

import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.playback.api.SubtitleAppearance
import dev.infusion.core.playback.api.SubtitleAppearanceMode
import dev.infusion.core.playback.api.SubtitleAppearancePreferenceStore
import dev.infusion.core.playback.api.SubtitleBackgroundChoice
import dev.infusion.core.playback.api.SubtitleBackgroundOpacityChoice
import dev.infusion.core.playback.api.SubtitleEdgeChoice
import dev.infusion.core.playback.api.SubtitleFontChoice
import dev.infusion.core.playback.api.SubtitlePositionChoice
import dev.infusion.core.playback.api.SubtitleSizeChoice
import dev.infusion.core.playback.api.SubtitleTextColorChoice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SubtitleAppearanceStateHolder(
    private val store: SubtitleAppearancePreferenceStore,
    private val profileScope: ProfileScope,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private var publishedProfileId: String = profileScope.activeProfileId

    private val _appearance = MutableStateFlow(readFor(publishedProfileId))

    val appearance: StateFlow<SubtitleAppearance> = _appearance.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            profileScope.activeProfileFlow.collect { profileId ->

                if (profileId == publishedProfileId) return@collect
                publishedProfileId = profileId
                _appearance.value = readFor(profileId)
            }
        }
    }

    fun setMode(mode: SubtitleAppearanceMode) {
        update(_appearance.value.withMode(mode))
    }

    fun setSize(size: SubtitleSizeChoice) {
        update(_appearance.value.copy(size = size))
    }

    fun setFont(font: SubtitleFontChoice) {
        update(_appearance.value.copy(font = font))
    }

    fun setTextColor(color: SubtitleTextColorChoice) {
        update(_appearance.value.copy(textColor = color))
    }

    fun setBackground(background: SubtitleBackgroundChoice) {
        update(_appearance.value.copy(background = background))
    }

    fun setBackgroundOpacity(opacity: SubtitleBackgroundOpacityChoice) {
        update(_appearance.value.copy(backgroundOpacity = opacity))
    }

    fun setEdge(edge: SubtitleEdgeChoice) {
        update(_appearance.value.copy(edge = edge))
    }

    fun setPosition(position: SubtitlePositionChoice) {
        update(_appearance.value.copy(position = position))
    }

    fun reload() {
        _appearance.value = readFor(profileScope.activeProfileId)
    }

    private fun update(updated: SubtitleAppearance) {
        if (updated == _appearance.value) return
        val profileId = profileScope.activeProfileId
        _appearance.value = updated
        scope.launch(ioDispatcher) { store.setAppearance(profileId, updated) }
    }

    private fun readFor(profileId: String): SubtitleAppearance = store.appearanceFor(profileId)
}
