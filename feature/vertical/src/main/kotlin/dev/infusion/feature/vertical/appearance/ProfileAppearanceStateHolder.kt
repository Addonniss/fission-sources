package dev.infusion.feature.vertical.appearance

import dev.infusion.core.domain.appearance.AccentColor
import dev.infusion.core.domain.appearance.AppearancePreferenceStore
import dev.infusion.core.domain.appearance.ArtworkStyle
import dev.infusion.core.domain.appearance.ProfileAppearancePreferences
import dev.infusion.core.domain.appearance.SplashScreenMode
import dev.infusion.core.domain.appearance.TmdbBackdropQuality
import dev.infusion.core.domain.appearance.TmdbLandscapeCardQuality
import dev.infusion.core.domain.appearance.TopShelfAppearance
import dev.infusion.core.domain.profile.ProfileScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProfileAppearanceStateHolder(
    private val store: AppearancePreferenceStore,
    private val profileScope: ProfileScope,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _preferences = MutableStateFlow(store.preferencesFor(profileScope.activeProfileId))

    val preferences: StateFlow<ProfileAppearancePreferences> = _preferences.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            profileScope.activeProfileFlow.collect { profileId ->
                _preferences.value = store.preferencesFor(profileId)
            }
        }
    }

    fun setAccent(accent: AccentColor) = update { it.copy(accent = accent) }

    fun setMoviesArtwork(style: ArtworkStyle) = update { it.copy(moviesArtwork = style) }

    fun setSeriesArtwork(style: ArtworkStyle) = update { it.copy(seriesArtwork = style) }

    fun setHideTitles(hide: Boolean) = update { it.copy(hideTitles = hide) }

    fun setCardBadges(enabled: Boolean) = update { it.copy(cardBadges = enabled) }

    fun setWatchedCheckmark(enabled: Boolean) = update { it.copy(watchedCheckmark = enabled) }

    fun setHideWatchedTitles(enabled: Boolean) = update { it.copy(hideWatchedTitles = enabled) }

    fun setHideEpisodeTitles(hide: Boolean) = update { it.copy(hideEpisodeTitles = hide) }

    fun setWatchlistBadge(enabled: Boolean) = update { it.copy(watchlistBadge = enabled) }

    fun setJellyfinAvailabilityBadge(enabled: Boolean) =
        update { it.copy(jellyfinAvailabilityBadge = enabled) }

    fun setProgressBar(enabled: Boolean) = update { it.copy(progressBar = enabled) }

    fun setTopShelfAppearance(appearance: TopShelfAppearance) =
        update { it.copy(topShelfAppearance = appearance) }

    fun setSplashScreen(mode: SplashScreenMode) = update { it.copy(splashScreen = mode) }

    fun setTmdbBackdropQuality(quality: TmdbBackdropQuality) =
        update { it.copy(tmdbBackdropQuality = quality) }

    fun setTmdbLandscapeCardQuality(quality: TmdbLandscapeCardQuality) =
        update { it.copy(tmdbLandscapeCardQuality = quality) }

    fun setContextMenuBadge(enabled: Boolean) = update { it.copy(contextMenuBadge = enabled) }

    private fun update(transform: (ProfileAppearancePreferences) -> ProfileAppearancePreferences) {
        val profileId = profileScope.activeProfileId
        _preferences.value = transform(_preferences.value)
        scope.launch(ioDispatcher) {
            store.setPreferences(profileId, transform(store.preferencesFor(profileId)))
        }
    }
}
