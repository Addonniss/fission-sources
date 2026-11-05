package dev.infusion.core.domain.appearance

data class ProfileAppearancePreferences(
    val accent: AccentColor = AccentColor.DEFAULT,
    val moviesArtwork: ArtworkStyle = ArtworkStyle.DEFAULT_MOVIES,
    val seriesArtwork: ArtworkStyle = ArtworkStyle.DEFAULT_SERIES,

    val hideTitles: Boolean = DEFAULT_HIDE_TITLES,

    val cardBadges: Boolean = true,

    val watchedCheckmark: Boolean = true,

    val hideWatchedTitles: Boolean = false,

    val hideEpisodeTitles: Boolean = false,

    val watchlistBadge: Boolean = true,

    val jellyfinAvailabilityBadge: Boolean = true,

    val progressBar: Boolean = true,

    val topShelfAppearance: TopShelfAppearance = TopShelfAppearance.DEFAULT,

    val splashScreen: SplashScreenMode = SplashScreenMode.DEFAULT,

    val tmdbBackdropQuality: TmdbBackdropQuality = TmdbBackdropQuality.DEFAULT,

    val tmdbLandscapeCardQuality: TmdbLandscapeCardQuality = TmdbLandscapeCardQuality.DEFAULT,

    val contextMenuBadge: Boolean = true,
) {
    companion object {

        const val DEFAULT_HIDE_TITLES: Boolean = true

        val Default = ProfileAppearancePreferences()
    }
}
