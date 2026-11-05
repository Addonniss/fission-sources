package dev.infusion.feature.vertical.playback

import dev.infusion.core.domain.playback.CompletionThresholdPreferenceStore
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.playback.api.CompletionThreshold
import dev.infusion.core.playback.api.CompletionThresholds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CompletionThresholdStateHolder(
    private val store: CompletionThresholdPreferenceStore,
    private val profileScope: ProfileScope,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private var publishedProfileId: String = profileScope.activeProfileId

    private val _thresholds = MutableStateFlow(readFor(publishedProfileId))

    val thresholds: StateFlow<CompletionThresholds> = _thresholds.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            profileScope.activeProfileFlow.collect { profileId ->

                if (profileId == publishedProfileId) return@collect
                publishedProfileId = profileId
                _thresholds.value = readFor(profileId)
            }
        }
    }

    fun setMovie(threshold: CompletionThreshold) {
        update(_thresholds.value.copy(movie = threshold))
    }

    fun setEpisode(threshold: CompletionThreshold) {
        update(_thresholds.value.copy(episode = threshold))
    }

    private fun update(updated: CompletionThresholds) {
        if (updated == _thresholds.value) return
        val profileId = profileScope.activeProfileId
        _thresholds.value = updated
        scope.launch(ioDispatcher) { persist(profileId, updated) }
    }

    private fun persist(profileId: String, thresholds: CompletionThresholds) {
        store.setMovieCompletionThreshold(profileId, thresholds.movie.percent.toString())
        store.setEpisodeCompletionThreshold(profileId, thresholds.episode.percent.toString())
    }

    private fun readFor(profileId: String): CompletionThresholds = CompletionThresholds(
        movie = CompletionThreshold.orDefault(store.movieCompletionThreshold(profileId)),
        episode = CompletionThreshold.orDefault(store.episodeCompletionThreshold(profileId)),
    )
}
