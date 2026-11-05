package dev.infusion.core.database.profile

import android.content.Context
import android.content.SharedPreferences
import dev.infusion.core.domain.appearance.AccentColor
import dev.infusion.core.domain.appearance.AppearancePreferenceStore
import dev.infusion.core.domain.appearance.ArtworkStyle
import dev.infusion.core.domain.appearance.ProfileAppearancePreferences
import dev.infusion.core.domain.appearance.SplashScreenMode
import dev.infusion.core.domain.appearance.TmdbBackdropQuality
import dev.infusion.core.domain.appearance.TmdbLandscapeCardQuality
import dev.infusion.core.domain.appearance.TopShelfAppearance

class SharedPreferencesAppearancePreferenceStore(
    context: Context,
    prefsName: String = PREFS_NAME,
) : AppearancePreferenceStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun preferencesFor(profileId: String): ProfileAppearancePreferences =
        ProfileAppearancePreferences(
            accent = AccentColor.orDefault(prefs.getString(accentKey(profileId), null)),
            moviesArtwork = ArtworkStyle.orDefault(
                prefs.getString(moviesArtworkKey(profileId), null),
                ArtworkStyle.DEFAULT_MOVIES,
            ),
            seriesArtwork = ArtworkStyle.orDefault(
                prefs.getString(seriesArtworkKey(profileId), null),
                ArtworkStyle.DEFAULT_SERIES,
            ),
            hideTitles = prefs.getBoolean(
                hideTitlesKey(profileId),
                ProfileAppearancePreferences.DEFAULT_HIDE_TITLES,
            ),

            cardBadges = prefs.getBoolean(cardBadgesKey(profileId), true),
            watchedCheckmark = prefs.getBoolean(watchedCheckmarkKey(profileId), true),

            hideWatchedTitles = prefs.getBoolean(hideWatchedTitlesKey(profileId), false),

            hideEpisodeTitles = prefs.getBoolean(hideEpisodeTitlesKey(profileId), false),

            watchlistBadge = prefs.getBoolean(watchlistBadgeKey(profileId), true),
            jellyfinAvailabilityBadge =
                prefs.getBoolean(jellyfinAvailabilityBadgeKey(profileId), true),
            progressBar = prefs.getBoolean(progressBarKey(profileId), true),

            topShelfAppearance = TopShelfAppearance.orDefault(
                prefs.getString(topShelfAppearanceKey(profileId), null),
            ),

            splashScreen = SplashScreenMode.orDefault(
                prefs.getString(splashScreenKey(profileId), null),
            ),

            tmdbBackdropQuality = TmdbBackdropQuality.orDefault(
                prefs.getString(tmdbBackdropQualityKey(profileId), null),
            ),

            tmdbLandscapeCardQuality = TmdbLandscapeCardQuality.orDefault(
                prefs.getString(tmdbLandscapeCardQualityKey(profileId), null),
            ),

            contextMenuBadge = prefs.getBoolean(contextMenuBadgeKey(profileId), true),
        )

    override fun setPreferences(profileId: String, preferences: ProfileAppearancePreferences) {
        prefs.edit()
            .putString(accentKey(profileId), preferences.accent.storageKey)
            .putString(moviesArtworkKey(profileId), preferences.moviesArtwork.storageKey)
            .putString(seriesArtworkKey(profileId), preferences.seriesArtwork.storageKey)
            .putBoolean(hideTitlesKey(profileId), preferences.hideTitles)
            .putBoolean(cardBadgesKey(profileId), preferences.cardBadges)
            .putBoolean(watchedCheckmarkKey(profileId), preferences.watchedCheckmark)
            .putBoolean(hideWatchedTitlesKey(profileId), preferences.hideWatchedTitles)
            .putBoolean(hideEpisodeTitlesKey(profileId), preferences.hideEpisodeTitles)
            .putBoolean(watchlistBadgeKey(profileId), preferences.watchlistBadge)
            .putBoolean(
                jellyfinAvailabilityBadgeKey(profileId),
                preferences.jellyfinAvailabilityBadge,
            )
            .putBoolean(progressBarKey(profileId), preferences.progressBar)
            .putString(topShelfAppearanceKey(profileId), preferences.topShelfAppearance.storageKey)
            .putString(splashScreenKey(profileId), preferences.splashScreen.storageKey)
            .putString(
                tmdbBackdropQualityKey(profileId),
                preferences.tmdbBackdropQuality.storageKey,
            )
            .putString(
                tmdbLandscapeCardQualityKey(profileId),
                preferences.tmdbLandscapeCardQuality.storageKey,
            )
            .putBoolean(contextMenuBadgeKey(profileId), preferences.contextMenuBadge)
            .apply()
    }

    override fun clearForProfile(profileId: String) {

        prefs.edit()
            .remove(accentKey(profileId))
            .remove(moviesArtworkKey(profileId))
            .remove(seriesArtworkKey(profileId))
            .remove(hideTitlesKey(profileId))
            .remove(cardBadgesKey(profileId))
            .remove(watchedCheckmarkKey(profileId))
            .remove(hideWatchedTitlesKey(profileId))
            .remove(hideEpisodeTitlesKey(profileId))
            .remove(watchlistBadgeKey(profileId))
            .remove(jellyfinAvailabilityBadgeKey(profileId))
            .remove(progressBarKey(profileId))
            .remove(topShelfAppearanceKey(profileId))
            .remove(splashScreenKey(profileId))
            .remove(tmdbBackdropQualityKey(profileId))
            .remove(tmdbLandscapeCardQualityKey(profileId))
            .remove(contextMenuBadgeKey(profileId))
            .apply()
    }

    private fun accentKey(profileId: String): String = "$ACCENT_PREFIX$profileId"

    private fun moviesArtworkKey(profileId: String): String = "$MOVIES_ARTWORK_PREFIX$profileId"

    private fun seriesArtworkKey(profileId: String): String = "$SERIES_ARTWORK_PREFIX$profileId"

    private fun hideTitlesKey(profileId: String): String = "$HIDE_TITLES_PREFIX$profileId"

    private fun cardBadgesKey(profileId: String): String = "$CARD_BADGES_PREFIX$profileId"

    private fun watchedCheckmarkKey(profileId: String): String =
        "$WATCHED_CHECKMARK_PREFIX$profileId"

    private fun hideWatchedTitlesKey(profileId: String): String =
        "$HIDE_WATCHED_TITLES_PREFIX$profileId"

    private fun hideEpisodeTitlesKey(profileId: String): String =
        "$HIDE_EPISODE_TITLES_PREFIX$profileId"

    private fun watchlistBadgeKey(profileId: String): String =
        "$WATCHLIST_BADGE_PREFIX$profileId"

    private fun jellyfinAvailabilityBadgeKey(profileId: String): String =
        "$JELLYFIN_AVAILABILITY_BADGE_PREFIX$profileId"

    private fun progressBarKey(profileId: String): String = "$PROGRESS_BAR_PREFIX$profileId"

    private fun topShelfAppearanceKey(profileId: String): String =
        "$TOP_SHELF_APPEARANCE_PREFIX$profileId"

    private fun splashScreenKey(profileId: String): String = "$SPLASH_SCREEN_PREFIX$profileId"

    private fun tmdbBackdropQualityKey(profileId: String): String =
        "$TMDB_BACKDROP_QUALITY_PREFIX$profileId"

    private fun tmdbLandscapeCardQualityKey(profileId: String): String =
        "$TMDB_LANDSCAPE_CARD_QUALITY_PREFIX$profileId"

    private fun contextMenuBadgeKey(profileId: String): String =
        "$CONTEXT_MENU_BADGE_PREFIX$profileId"

    private companion object {
        const val PREFS_NAME = "infusion_appearance_preferences"
        const val ACCENT_PREFIX = "appearance_accent:"
        const val MOVIES_ARTWORK_PREFIX = "appearance_movies_artwork:"
        const val SERIES_ARTWORK_PREFIX = "appearance_series_artwork:"
        const val HIDE_TITLES_PREFIX = "appearance_hide_titles:"
        const val CARD_BADGES_PREFIX = "appearance_card_badges:"
        const val WATCHED_CHECKMARK_PREFIX = "appearance_watched_checkmark:"
        const val HIDE_WATCHED_TITLES_PREFIX = "appearance_hide_watched_titles:"
        const val HIDE_EPISODE_TITLES_PREFIX = "appearance_hide_episode_titles:"
        const val WATCHLIST_BADGE_PREFIX = "appearance_watchlist_badge:"
        const val JELLYFIN_AVAILABILITY_BADGE_PREFIX = "appearance_jellyfin_availability:"
        const val PROGRESS_BAR_PREFIX = "appearance_progress_bar:"
        const val TOP_SHELF_APPEARANCE_PREFIX = "appearance_top_shelf_appearance:"
        const val SPLASH_SCREEN_PREFIX = "appearance_splash_screen:"
        const val TMDB_BACKDROP_QUALITY_PREFIX = "appearance_tmdb_backdrop_quality:"
        const val TMDB_LANDSCAPE_CARD_QUALITY_PREFIX = "appearance_tmdb_landscape_card_quality:"
        const val CONTEXT_MENU_BADGE_PREFIX = "appearance_context_menu_badge:"
    }
}
