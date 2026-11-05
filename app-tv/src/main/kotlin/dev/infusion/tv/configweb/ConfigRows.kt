package dev.infusion.tv.configweb

import dev.infusion.core.domain.appearance.AccentColor
import dev.infusion.core.domain.appearance.ArtworkStyle
import dev.infusion.core.domain.appearance.SplashScreenMode
import dev.infusion.core.domain.appearance.TmdbBackdropQuality
import dev.infusion.core.domain.appearance.TmdbLandscapeCardQuality
import dev.infusion.core.domain.appearance.TopShelfAppearance
import dev.infusion.core.domain.ratings.RatingsPreferences
import dev.infusion.core.domain.upnext.UpNextSelection
import dev.infusion.core.domain.upnext.UpNextSource
import dev.infusion.core.model.metadata.RatingAppearance
import dev.infusion.core.model.metadata.RatingMetric
import dev.infusion.core.model.metadata.RatingSupplier
import dev.infusion.core.playback.api.AudioLanguagePreference
import dev.infusion.core.playback.api.CompletionThreshold
import dev.infusion.core.playback.api.DisplayMatchingPolicy
import dev.infusion.core.playback.api.DolbyVisionProfile7Fallback
import dev.infusion.core.playback.api.HdrSubtitleDimming
import dev.infusion.core.playback.api.NextEpisodePreferences
import dev.infusion.core.playback.api.NextEpisodeSettings
import dev.infusion.core.playback.api.PlaybackControlsAutoHide
import dev.infusion.core.playback.api.RelativeSeekStep
import dev.infusion.core.playback.api.SegmentDisplayMode
import dev.infusion.core.playback.api.SegmentEvidenceSettings
import dev.infusion.core.playback.api.SegmentFallbackTimecode
import dev.infusion.core.playback.api.SourceAutoplayPolicy
import dev.infusion.core.playback.api.SubtitleAppearanceMode
import dev.infusion.core.playback.api.SubtitleBackgroundChoice
import dev.infusion.core.playback.api.SubtitleBackgroundOpacityChoice
import dev.infusion.core.playback.api.SubtitleEdgeChoice
import dev.infusion.core.playback.api.SubtitleFontChoice
import dev.infusion.core.playback.api.SubtitleLanguagePreference
import dev.infusion.core.playback.api.SubtitleModePreference
import dev.infusion.core.playback.api.SubtitlePositionChoice
import dev.infusion.core.playback.api.SubtitleSameLanguagePolicy
import dev.infusion.core.playback.api.SubtitleSizeChoice
import dev.infusion.core.playback.api.SubtitleTextColorChoice
import dev.infusion.core.playback.api.SubtitleTypePreference
import dev.infusion.feature.configweb.ConfigApplyResult
import dev.infusion.feature.configweb.ConfigChoiceState
import dev.infusion.feature.configweb.ConfigRowControl
import dev.infusion.feature.configweb.ConfigRowScope
import dev.infusion.feature.configweb.ConfigValidationError
import dev.infusion.feature.vertical.home.HomeSpotlightSource
import dev.infusion.feature.vertical.home.HomeSpotlightSourceCodec
import dev.infusion.feature.vertical.home.HomeSpotlightRowId
import dev.infusion.tv.ui.screens.AudioLanguageSelector
import dev.infusion.tv.ui.screens.SubtitleLanguageSelector
import dev.infusion.tv.ui.screens.audioLanguageChoiceKey
import dev.infusion.tv.ui.screens.audioLanguageChoiceLabel
import dev.infusion.tv.ui.screens.audioLanguageSelectorChoices
import dev.infusion.tv.ui.screens.subtitleLanguageChoiceKey
import dev.infusion.tv.ui.screens.subtitleLanguageChoiceLabel
import dev.infusion.tv.ui.screens.subtitleLanguageSelectorChoices

internal object ConfigRows {

    fun general(owners: TvConfigOwners): List<RowSpec> {
        val appearance = owners.appearance
        val prefs = { appearance.preferences.value }
        return listOf(
            RowSpec(
                key = "accent",
                label = "Accent color",
                group = GROUP_GENERAL,
                control = ConfigRowControl.CHOICE,
                choices = { AccentColor.entries.map { ConfigChoiceState(it.storageKey, accentLabel(it)) } },
                read = { prefs().accent.storageKey },
                write = { token ->
                    accentOf(token)?.let { appearance.setAccent(it) }
                    verified({ prefs().accent.storageKey }, token)
                },
            ),
            RowSpec(
                key = "top_shelf",
                label = "Top Shelf Appearance",
                group = GROUP_GENERAL,
                control = ConfigRowControl.CHOICE,
                choices = {
                    TopShelfAppearance.entries.map { ConfigChoiceState(it.storageKey, it.displayName) }
                },
                read = { prefs().topShelfAppearance.storageKey },
                write = { token ->
                    topShelfOf(token)?.let { appearance.setTopShelfAppearance(it) }
                    verified({ prefs().topShelfAppearance.storageKey }, token)
                },
            ),
            RowSpec(
                key = "splash",
                label = "Splash screen",
                group = GROUP_GENERAL,
                control = ConfigRowControl.CHOICE,
                choices = {
                    SplashScreenMode.entries.map { ConfigChoiceState(it.storageKey, it.displayName) }
                },
                read = { prefs().splashScreen.storageKey },
                write = { token ->
                    splashOf(token)?.let { appearance.setSplashScreen(it) }
                    verified({ prefs().splashScreen.storageKey }, token)
                },
            ),
            RowSpec(
                key = "movies_artwork",
                label = "Movies Artwork",
                group = GROUP_GENERAL,
                control = ConfigRowControl.CHOICE,
                choices = { ArtworkStyle.entries.map { ConfigChoiceState(it.storageKey, artworkLabel(it)) } },
                read = { prefs().moviesArtwork.storageKey },
                write = { token ->
                    artworkOf(token)?.let { appearance.setMoviesArtwork(it) }
                    verified({ prefs().moviesArtwork.storageKey }, token)
                },
            ),
            RowSpec(
                key = "series_artwork",
                label = "Series Artwork",
                group = GROUP_GENERAL,
                control = ConfigRowControl.CHOICE,
                choices = { ArtworkStyle.entries.map { ConfigChoiceState(it.storageKey, artworkLabel(it)) } },
                read = { prefs().seriesArtwork.storageKey },
                write = { token ->
                    artworkOf(token)?.let { appearance.setSeriesArtwork(it) }
                    verified({ prefs().seriesArtwork.storageKey }, token)
                },
            ),
            RowSpec(
                key = "tmdb_backdrop_quality",
                label = "TMDB backdrop quality",
                group = GROUP_GENERAL,
                control = ConfigRowControl.CHOICE,
                choices = {
                    TmdbBackdropQuality.entries.map { ConfigChoiceState(it.storageKey, it.displayName) }
                },
                read = { prefs().tmdbBackdropQuality.storageKey },
                write = { token ->
                    TmdbBackdropQuality.entries.firstOrNull { it.storageKey == token }
                        ?.let { appearance.setTmdbBackdropQuality(it) }
                    verified({ prefs().tmdbBackdropQuality.storageKey }, token)
                },
            ),
            RowSpec(
                key = "tmdb_landscape_card_quality",
                label = "TMDB landscape card quality",
                group = GROUP_GENERAL,
                control = ConfigRowControl.CHOICE,
                choices = {
                    TmdbLandscapeCardQuality.entries.map { ConfigChoiceState(it.storageKey, it.displayName) }
                },
                read = { prefs().tmdbLandscapeCardQuality.storageKey },
                write = { token ->
                    TmdbLandscapeCardQuality.entries.firstOrNull { it.storageKey == token }
                        ?.let { appearance.setTmdbLandscapeCardQuality(it) }
                    verified({ prefs().tmdbLandscapeCardQuality.storageKey }, token)
                },
            ),
            flagRow(
                key = "hide_titles",
                label = "Hide Movies & Shows Titles",
                group = GROUP_GENERAL,
                read = { prefs().hideTitles },
                write = { appearance.setHideTitles(it) },
            ),
            flagRow(
                key = "hide_episode_titles",
                label = "Hide Episodes Title",
                group = GROUP_GENERAL,
                read = { prefs().hideEpisodeTitles },
                write = { appearance.setHideEpisodeTitles(it) },
            ),
            flagRow(
                key = "hide_watched_titles",
                label = "Hide watched titles",
                group = GROUP_GENERAL,
                read = { prefs().hideWatchedTitles },
                write = { appearance.setHideWatchedTitles(it) },
            ),

            flagRow(
                key = "show_provider_name",
                label = "Show Provider Name",
                group = GROUP_GENERAL,
                read = {
                    owners.homeCatalog.presentationPreferences(owners.activeProfileId()).showProviderName
                },
                write = { owners.homeCatalog.setShowProviderName(owners.activeProfileId(), it) },
            ),
            flagRow(
                key = "card_badges",
                label = "Card badges",
                group = GROUP_GENERAL,
                read = { prefs().cardBadges },
                write = { appearance.setCardBadges(it) },
            ),
            flagRow(
                key = "badge_watched",
                label = "Watched",
                group = GROUP_GENERAL,
                parentKey = "card_badges",
                active = { prefs().cardBadges },
                read = { prefs().watchedCheckmark },
                write = { appearance.setWatchedCheckmark(it) },
            ),
            flagRow(
                key = "badge_watchlist",
                label = "Watchlist",
                group = GROUP_GENERAL,
                parentKey = "card_badges",
                active = { prefs().cardBadges },
                read = { prefs().watchlistBadge },
                write = { appearance.setWatchlistBadge(it) },
            ),
            flagRow(
                key = "badge_jellyfin",
                label = "Jellyfin availability",
                group = GROUP_GENERAL,
                parentKey = "card_badges",
                active = { prefs().cardBadges },
                read = { prefs().jellyfinAvailabilityBadge },
                write = { appearance.setJellyfinAvailabilityBadge(it) },
            ),
            flagRow(
                key = "badge_progress",
                label = "Progress",
                group = GROUP_GENERAL,
                parentKey = "card_badges",
                active = { prefs().cardBadges },
                read = { prefs().progressBar },
                write = { appearance.setProgressBar(it) },
            ),

            flagRow(
                key = "badge_context_menu",
                label = "Context menu",
                group = GROUP_GENERAL,
                parentKey = "card_badges",
                active = { prefs().cardBadges },
                read = { prefs().contextMenuBadge },
                write = { appearance.setContextMenuBadge(it) },
            ),
        )
    }

    fun playback(owners: TvConfigOwners): List<RowSpec> {
        val audio = owners.audioLanguage
        val subtitles = owners.subtitlePreference
        val appearance = owners.subtitleAppearance
        val segment = owners.segmentEvidence
        val next = owners.nextEpisode
        val autoplay = owners.sourceAutoplay
        val controls = owners.playbackControlsAutoHide
        val seek = owners.relativeSeekStep
        val completion = owners.completionThreshold
        val display = owners.displayMatching
        val dv = owners.dolbyVisionProfile7Fallback
        val hdr = owners.hdrSubtitleDimming

        val subtitleAppearance = { appearance.appearance.value }
        val customActive = { subtitleAppearance().mode == SubtitleAppearanceMode.CUSTOM }
        val segmentPrefs = { segment.preferences.value }
        val nextPrefs = { next.preferences.value }
        val autoplayPrefs = { autoplay.preferences.value }
        val subtitlePrefs = { subtitles.preferences.value }

        return listOf(

            RowSpec(
                key = "display_matching",
                label = "Display matching",
                group = GROUP_DISPLAY,
                control = ConfigRowControl.CHOICE,
                scope = ConfigRowScope.DEVICE,
                help = DEVICE_SCOPE_HELP,
                choices = {
                    DisplayMatchingPolicy.entries.map { ConfigChoiceState(it.storageKey, displayMatchingLabel(it)) }
                },
                read = { display.policy.value.storageKey },
                write = { token ->
                    DisplayMatchingPolicy.entries.firstOrNull { it.storageKey == token }
                        ?.let { display.setPolicy(it) }
                    verified({ display.policy.value.storageKey }, token)
                },
            ),
            RowSpec(
                key = "dv_profile7_fallback",
                label = "DV Profile 7 fallback to HDR",
                group = GROUP_DISPLAY,
                control = ConfigRowControl.CHOICE,
                scope = ConfigRowScope.DEVICE,
                help = DEVICE_SCOPE_HELP,
                choices = {
                    DolbyVisionProfile7Fallback.entries.map {
                        ConfigChoiceState(
                            it.storageKey,
                            if (it == DolbyVisionProfile7Fallback.ON) "On" else "Off",
                        )
                    }
                },
                read = { dv.fallback.value.storageKey },
                write = { token ->
                    DolbyVisionProfile7Fallback.entries.firstOrNull { it.storageKey == token }
                        ?.let { dv.setFallback(it) }
                    verified({ dv.fallback.value.storageKey }, token)
                },
            ),

            RowSpec(
                key = "audio_language_primary",
                label = "Audio language",
                group = GROUP_AUDIO,
                control = ConfigRowControl.CHOICE,
                choices = {
                    audioLanguageSelectorChoices(
                        AudioLanguageSelector.PRIMARY,
                        audio.preferences.value.primary,
                        audio.preferences.value.primary,
                    ).map { ConfigChoiceState(audioLanguageChoiceKey(it), audioLanguageChoiceLabel(it)) }
                },
                read = { audioLanguageChoiceKey(audio.preferences.value.primary) },
                write = { token ->
                    audioPreferenceOf(token)?.let { audio.setPrimary(it) }
                    verified({ audioLanguageChoiceKey(audio.preferences.value.primary) }, token)
                },
            ),
            RowSpec(
                key = "audio_language_secondary",
                label = "Second audio language",
                group = GROUP_AUDIO,
                control = ConfigRowControl.CHOICE,
                choices = {
                    audioLanguageSelectorChoices(
                        AudioLanguageSelector.SECONDARY,
                        audio.preferences.value.secondary,
                        audio.preferences.value.primary,
                    ).map { ConfigChoiceState(audioLanguageChoiceKey(it), audioLanguageChoiceLabel(it)) }
                },
                read = { audioLanguageChoiceKey(audio.preferences.value.secondary) },
                write = { token ->
                    audioPreferenceOf(token)?.let { audio.setSecondary(it) }
                    verified({ audioLanguageChoiceKey(audio.preferences.value.secondary) }, token)
                },
                extraRule = { token ->

                    if (token != "none" && token == audioLanguageChoiceKey(audio.preferences.value.primary)) {
                        ConfigValidationError(
                            "value",
                            "the second audio language cannot be the same as the first",
                        )
                    } else {
                        null
                    }
                },
            ),

            RowSpec(
                key = "subtitles",
                label = "Subtitles",
                group = GROUP_SUBTITLES,
                control = ConfigRowControl.CHOICE,
                choices = { pairs("automatic" to "Automatic", "off" to "Off") },
                read = { subtitleModeToken(subtitlePrefs().mode) },
                write = { token ->
                    subtitleModeOf(token)?.let { subtitles.setMode(it) }
                    verified({ subtitleModeToken(subtitlePrefs().mode) }, token)
                },
            ),
            RowSpec(
                key = "subtitle_language_primary",
                label = "Subtitle language",
                group = GROUP_SUBTITLES,
                control = ConfigRowControl.CHOICE,
                choices = {
                    subtitleLanguageSelectorChoices(
                        SubtitleLanguageSelector.PRIMARY,
                        subtitlePrefs().primary,
                        subtitlePrefs().primary,
                    ).map { ConfigChoiceState(subtitleLanguageChoiceKey(it), subtitleLanguageChoiceLabel(it)) }
                },
                read = { subtitleLanguageChoiceKey(subtitlePrefs().primary) },
                write = { token ->
                    subtitleLanguageOf(token)?.let { subtitles.setPrimary(it) }
                    verified({ subtitleLanguageChoiceKey(subtitlePrefs().primary) }, token)
                },
            ),
            RowSpec(
                key = "subtitle_language_secondary",
                label = "Second subtitle language",
                group = GROUP_SUBTITLES,
                control = ConfigRowControl.CHOICE,
                choices = {
                    subtitleLanguageSelectorChoices(
                        SubtitleLanguageSelector.SECONDARY,
                        subtitlePrefs().secondary,
                        subtitlePrefs().primary,
                    ).map { ConfigChoiceState(subtitleLanguageChoiceKey(it), subtitleLanguageChoiceLabel(it)) }
                },
                read = { subtitleLanguageChoiceKey(subtitlePrefs().secondary) },
                write = { token ->
                    subtitleLanguageOf(token)?.let { subtitles.setSecondary(it) }
                    verified({ subtitleLanguageChoiceKey(subtitlePrefs().secondary) }, token)
                },
                extraRule = { token ->

                    if (token != "none" && token == subtitleLanguageChoiceKey(subtitlePrefs().primary)) {
                        ConfigValidationError(
                            "value",
                            "the second subtitle language cannot be the same as the first",
                        )
                    } else {
                        null
                    }
                },
            ),
            RowSpec(
                key = "subtitle_same_language",
                label = "Subtitles in the audio language",
                group = GROUP_SUBTITLES,
                control = ConfigRowControl.CHOICE,
                choices = {
                    pairs(
                        "off" to "Off",
                        "forced_only" to "Forced only",
                        "full" to "Full subtitles allowed",
                    )
                },
                read = { sameLanguageToken(subtitlePrefs().sameLanguage) },
                write = { token ->
                    sameLanguageOf(token)?.let { subtitles.setSameLanguage(it) }
                    verified({ sameLanguageToken(subtitlePrefs().sameLanguage) }, token)
                },
            ),
            RowSpec(
                key = "subtitle_type",
                label = "Subtitle type",
                group = GROUP_SUBTITLES,
                control = ConfigRowControl.CHOICE,
                choices = {
                    pairs(
                        "automatic" to "Automatic",
                        "standard" to "Standard",
                        "sdh" to "SDH",
                        "forced_only" to "Forced only",
                    )
                },
                read = { subtitleTypeToken(subtitlePrefs().type) },
                write = { token ->
                    subtitleTypeOf(token)?.let { subtitles.setType(it) }
                    verified({ subtitleTypeToken(subtitlePrefs().type) }, token)
                },
            ),
            RowSpec(
                key = "subtitle_hdr_dimming",
                label = "Dim subtitles in HDR",
                group = GROUP_SUBTITLES,
                control = ConfigRowControl.CHOICE,
                choices = { pairs("on" to "On", "off" to "Off") },
                read = { dimmingToken(hdr.dimming.value) },
                write = { token ->
                    dimmingOf(token)?.let { hdr.setDimming(it) }
                    verified({ dimmingToken(hdr.dimming.value) }, token)
                },
            ),
            RowSpec(
                key = "subtitle_appearance",
                label = "Subtitle appearance",
                group = GROUP_SUBTITLES,
                control = ConfigRowControl.CHOICE,
                choices = { pairs("system" to "System", "custom" to "Custom") },
                read = { subtitleAppearanceModeToken(subtitleAppearance().mode) },
                write = { token ->
                    subtitleAppearanceModeOf(token)?.let { appearance.setMode(it) }
                    verified({ subtitleAppearanceModeToken(subtitleAppearance().mode) }, token)
                },
            ),
            RowSpec(
                key = "subtitle_size",
                label = "Subtitle size",
                group = GROUP_SUBTITLES,
                control = ConfigRowControl.CHOICE,
                parentKey = "subtitle_appearance",
                active = customActive,
                choices = {
                    pairs(
                        "small" to "Small",
                        "medium" to "Medium",
                        "large" to "Large",
                        "extra_large" to "Extra large",
                    )
                },
                read = { subtitleAppearance().size.name.lowercase() },
                write = { token ->
                    subtitleSizeOf(token)?.let { appearance.setSize(it) }
                    verified({ subtitleAppearance().size.name.lowercase() }, token)
                },
            ),
            RowSpec(
                key = "subtitle_font",
                label = "Subtitle font",
                group = GROUP_SUBTITLES,
                control = ConfigRowControl.CHOICE,
                parentKey = "subtitle_appearance",
                active = customActive,
                choices = {
                    pairs(
                        "system_default" to "System default",
                        "sans_serif" to "Sans serif",
                        "serif" to "Serif",
                        "monospace" to "Monospace",
                    )
                },
                read = { subtitleAppearance().font.name.lowercase() },
                write = { token ->
                    subtitleFontOf(token)?.let { appearance.setFont(it) }
                    verified({ subtitleAppearance().font.name.lowercase() }, token)
                },
            ),
            RowSpec(
                key = "subtitle_text_color",
                label = "Subtitle text colour",
                group = GROUP_SUBTITLES,
                control = ConfigRowControl.CHOICE,
                parentKey = "subtitle_appearance",
                active = customActive,
                choices = {
                    pairs("white" to "White", "yellow" to "Yellow", "cyan" to "Cyan", "green" to "Green")
                },
                read = { subtitleAppearance().textColor.name.lowercase() },
                write = { token ->
                    subtitleTextColorOf(token)?.let { appearance.setTextColor(it) }
                    verified({ subtitleAppearance().textColor.name.lowercase() }, token)
                },
            ),
            RowSpec(
                key = "subtitle_background",
                label = "Subtitle background",
                group = GROUP_SUBTITLES,
                control = ConfigRowControl.CHOICE,
                parentKey = "subtitle_appearance",
                active = customActive,
                choices = {
                    pairs(
                        "black" to "Black",
                        "dark_grey" to "Dark grey",
                        "dark_blue" to "Dark blue",
                        "none" to "None",
                    )
                },
                read = { subtitleAppearance().background.name.lowercase() },
                write = { token ->
                    subtitleBackgroundOf(token)?.let { appearance.setBackground(it) }
                    verified({ subtitleAppearance().background.name.lowercase() }, token)
                },
            ),
            RowSpec(
                key = "subtitle_background_opacity",
                label = "Subtitle background opacity",
                group = GROUP_SUBTITLES,
                control = ConfigRowControl.CHOICE,
                parentKey = "subtitle_appearance",
                active = customActive,
                choices = {
                    pairs("full" to "Full", "high" to "High", "medium" to "Medium", "low" to "Low")
                },
                read = { subtitleAppearance().backgroundOpacity.name.lowercase() },
                write = { token ->
                    subtitleOpacityOf(token)?.let { appearance.setBackgroundOpacity(it) }
                    verified({ subtitleAppearance().backgroundOpacity.name.lowercase() }, token)
                },
            ),
            RowSpec(
                key = "subtitle_edge",
                label = "Subtitle edge",
                group = GROUP_SUBTITLES,
                control = ConfigRowControl.CHOICE,
                parentKey = "subtitle_appearance",
                active = customActive,
                choices = { pairs("none" to "None", "outline" to "Outline", "shadow" to "Shadow") },
                read = { subtitleAppearance().edge.name.lowercase() },
                write = { token ->
                    subtitleEdgeOf(token)?.let { appearance.setEdge(it) }
                    verified({ subtitleAppearance().edge.name.lowercase() }, token)
                },
            ),
            RowSpec(
                key = "subtitle_position",
                label = "Subtitle position",
                group = GROUP_SUBTITLES,
                control = ConfigRowControl.CHOICE,
                parentKey = "subtitle_appearance",
                active = customActive,
                choices = { pairs("bottom" to "Bottom", "raised" to "Raised", "middle" to "Middle") },
                read = { subtitleAppearance().position.name.lowercase() },
                write = { token ->
                    subtitlePositionOf(token)?.let { appearance.setPosition(it) }
                    verified({ subtitleAppearance().position.name.lowercase() }, token)
                },
            ),

            RowSpec(
                key = "controls_auto_hide",
                label = "Playback controls auto-hide",
                group = GROUP_CONTROLS,
                control = ConfigRowControl.CHOICE,
                choices = {
                    PlaybackControlsAutoHide.entries.map { entry ->
                        ConfigChoiceState(
                            entry.storageKey,
                            if (entry == PlaybackControlsAutoHide.OFF) "Off" else "${entry.storageKey}s",
                        )
                    }
                },
                read = { controls.autoHide.value.storageKey },
                write = { token ->
                    PlaybackControlsAutoHide.entries.firstOrNull { it.storageKey == token }
                        ?.let { controls.set(it) }
                    verified({ controls.autoHide.value.storageKey }, token)
                },
            ),
            RowSpec(
                key = "seek_step",
                label = "Rewind/Fast Forward skip time",
                group = GROUP_CONTROLS,
                control = ConfigRowControl.CHOICE,
                choices = {
                    RelativeSeekStep.entries.map {
                        ConfigChoiceState(it.storageKey, "${it.storageKey}s")
                    }
                },
                read = { seek.step.value.storageKey },
                write = { token ->
                    RelativeSeekStep.entries.firstOrNull { it.storageKey == token }?.let { seek.set(it) }
                    verified({ seek.step.value.storageKey }, token)
                },
            ),
            RowSpec(
                key = "completion_movies",
                label = "Mark movies watched at",
                group = GROUP_CONTROLS,
                control = ConfigRowControl.CHOICE,
                choices = {
                    CompletionThreshold.entries.map {
                        ConfigChoiceState(it.storageKey, "${it.storageKey}%")
                    }
                },
                read = { completion.thresholds.value.movie.storageKey },
                write = { token ->
                    CompletionThreshold.entries.firstOrNull { it.storageKey == token }
                        ?.let { completion.setMovie(it) }
                    verified({ completion.thresholds.value.movie.storageKey }, token)
                },
            ),
            RowSpec(
                key = "completion_episodes",
                label = "Mark episodes watched at",
                group = GROUP_CONTROLS,
                control = ConfigRowControl.CHOICE,
                choices = {
                    CompletionThreshold.entries.map {
                        ConfigChoiceState(it.storageKey, "${it.storageKey}%")
                    }
                },
                read = { completion.thresholds.value.episode.storageKey },
                write = { token ->
                    CompletionThreshold.entries.firstOrNull { it.storageKey == token }
                        ?.let { completion.setEpisode(it) }
                    verified({ completion.thresholds.value.episode.storageKey }, token)
                },
            ),
            RowSpec(
                key = "segment_display",
                label = "Segment display",
                group = GROUP_CONTROLS,
                control = ConfigRowControl.CHOICE,
                choices = {
                    pairs("off" to "Off", "above" to "Above the bar", "on_bar" to "On the bar")
                },
                read = { segmentPrefs().displayMode.storageKey },
                write = { token ->
                    SegmentDisplayMode.entries.firstOrNull { it.storageKey == token }
                        ?.let { segment.setDisplayMode(it) }
                    verified({ segmentPrefs().displayMode.storageKey }, token)
                },
            ),
            flagRow(
                key = "segment_legend",
                label = "Segment legend",
                group = GROUP_CONTROLS,

                help = "The legend is only drawn while Segment display is not Off.",
                read = { segmentPrefs().legendEnabled },
                write = { segment.setLegendEnabled(it) },
            ),
            RowSpec(
                key = "source_autoplay",
                label = "Source autoplay",
                group = GROUP_CONTROLS,
                control = ConfigRowControl.CHOICE,
                choices = {
                    SourceAutoplayPolicy.entries.map {
                        ConfigChoiceState(
                            it.storageKey,
                            when (it) {
                                SourceAutoplayPolicy.OFF -> "Off"
                                SourceAutoplayPolicy.FIRST_RESPONDING_ADDON -> "First responding add-on"
                                SourceAutoplayPolicy.FIRST_COMBINED_ADDON_RESULT -> "First combined add-on result"
                            },
                        )
                    }
                },
                read = { autoplayPrefs().policy.storageKey },
                write = { token ->
                    SourceAutoplayPolicy.entries.firstOrNull { it.storageKey == token }
                        ?.let { autoplay.setPolicy(it) }
                    verified({ autoplayPrefs().policy.storageKey }, token)
                },
            ),
            flagRow(
                key = "source_autoplay_prefer_jellyfin",
                label = "Prefer Jellyfin",
                group = GROUP_CONTROLS,
                parentKey = "source_autoplay",
                active = { autoplayPrefs().policy != SourceAutoplayPolicy.OFF },
                read = { autoplayPrefs().preferJellyfin },
                write = { autoplay.setPreferJellyfin(it) },
            ),

            flagRow(
                key = "intro_online_priority",
                label = "Intro metadata priority",
                group = GROUP_INTRO,
                read = { segmentPrefs().onlineIntroMetadataPriority },
                write = { segment.setOnlineIntroMetadataPriority(it) },
            ),
            flagRow(
                key = "intro_fallback_enabled",
                label = "Fallback intro window",
                group = GROUP_INTRO,
                read = { segmentPrefs().manualFallbackEnabled },
                write = { segment.setManualFallbackEnabled(it) },
            ),
            RowSpec(
                key = "intro_fallback_start",
                label = "Fallback intro start",
                group = GROUP_INTRO,
                control = ConfigRowControl.CHOICE,
                parentKey = "intro_fallback_enabled",
                active = { segmentPrefs().manualFallbackEnabled },
                choices = {
                    SegmentFallbackTimecode.entries.map {
                        ConfigChoiceState(it.storageKey, it.storageKey)
                    }
                },
                read = { segmentPrefs().fallbackStart.storageKey },
                write = { token ->
                    SegmentFallbackTimecode.entries.firstOrNull { it.storageKey == token }
                        ?.let { segment.setFallbackStart(it) }
                    verified({ segmentPrefs().fallbackStart.storageKey }, token)
                },
            ),
            RowSpec(
                key = "intro_fallback_end",
                label = "Fallback intro end",
                group = GROUP_INTRO,
                control = ConfigRowControl.CHOICE,
                parentKey = "intro_fallback_enabled",
                active = { segmentPrefs().manualFallbackEnabled },
                choices = {
                    SegmentFallbackTimecode.entries.map {
                        ConfigChoiceState(it.storageKey, it.storageKey)
                    }
                },
                read = { segmentPrefs().fallbackEnd.storageKey },
                write = { token ->
                    SegmentFallbackTimecode.entries.firstOrNull { it.storageKey == token }
                        ?.let { segment.setFallbackEnd(it) }
                    verified({ segmentPrefs().fallbackEnd.storageKey }, token)
                },
            ),
            flagRow(
                key = "skip_intro_enabled",
                label = "Enable Skip Intro",
                group = GROUP_INTRO,
                read = { segmentPrefs().enableSkipIntro },
                write = { segment.setEnableSkipIntro(it) },
            ),
            flagRow(
                key = "auto_skip_intro",
                label = "Auto-skip Intro",
                group = GROUP_INTRO,
                read = { segmentPrefs().autoSkipIntro },
                write = { segment.setAutoSkipIntro(it) },
            ),
            numberRow(
                key = "auto_skip_delay",
                label = "Auto-skip Delay",
                group = GROUP_INTRO,
                parentKey = "auto_skip_intro",
                active = { segmentPrefs().autoSkipIntro },
                min = SegmentEvidenceSettings.AUTO_SKIP_DELAY_MIN,
                max = SegmentEvidenceSettings.AUTO_SKIP_DELAY_MAX,
                read = { segmentPrefs().autoSkipDelaySeconds },
                write = { segment.setAutoSkipDelaySeconds(it) },
            ),
            flagRow(
                key = "skip_intro_persistent",
                label = "Persistent Button",
                group = GROUP_INTRO,
                read = { segmentPrefs().persistentSkipButton },
                write = { segment.setPersistentSkipButton(it) },
            ),

            flagRow(
                key = "next_episode_enabled",
                label = "Enable Next Episode",
                group = GROUP_NEXT,
                read = { nextPrefs().enableNextEpisode },
                write = { next.setEnableNextEpisode(it) },
            ),
            flagRow(
                key = "next_online_priority",
                label = "Next — Online metadata priority",
                group = GROUP_NEXT,
                parentKey = "next_episode_enabled",
                active = { nextPrefs().enableNextEpisode },
                read = { nextPrefs().onlineNextMetadataPriority },
                write = { next.setOnlineNextMetadataPriority(it) },
            ),
            numberRow(
                key = "next_fallback_percent",
                label = "Fallback Trigger Percent",
                group = GROUP_NEXT,
                parentKey = "next_episode_enabled",
                active = { nextPrefs().enableNextEpisode },
                min = NextEpisodeSettings.FALLBACK_PERCENT_MIN,
                max = NextEpisodeSettings.FALLBACK_PERCENT_MAX,
                read = { nextPrefs().fallbackTriggerPercent },
                write = { next.setFallbackTriggerPercent(it) },
            ),
            flagRow(
                key = "next_autoplay",
                label = "Automatically play next episode",
                group = GROUP_NEXT,
                parentKey = "next_episode_enabled",
                active = { nextPrefs().enableNextEpisode },
                read = { nextPrefs().autoPlayNextEpisode },
                write = { next.setAutoPlayNextEpisode(it) },
            ),
            numberRow(
                key = "next_autoplay_delay",
                label = "Auto-play Delay",
                group = GROUP_NEXT,
                parentKey = "next_autoplay",
                active = { nextPrefs().autoPlayNextEpisode },
                min = NextEpisodeSettings.AUTO_PLAY_DELAY_MIN,
                max = NextEpisodeSettings.AUTO_PLAY_DELAY_MAX,
                read = { nextPrefs().autoPlayDelaySeconds },
                write = { next.setAutoPlayDelaySeconds(it) },
            ),

            flagRow(
                key = "stinger_enabled",
                label = "Enable Stinger Alerts (movies)",
                group = GROUP_STINGER,
                read = { segmentPrefs().enableStingerAlerts },
                write = { segment.setEnableStingerAlerts(it) },
            ),
            numberRow(
                key = "stinger_trigger_percent",
                label = "Stinger Trigger Percent",
                group = GROUP_STINGER,
                parentKey = "stinger_enabled",
                active = { segmentPrefs().enableStingerAlerts },
                min = SegmentEvidenceSettings.STINGER_PERCENT_MIN,
                max = SegmentEvidenceSettings.STINGER_PERCENT_MAX,
                read = { segmentPrefs().stingerTriggerPercent },
                write = { segment.setStingerTriggerPercent(it) },
            ),
            numberRow(
                key = "stinger_early_notify",
                label = "Early Notification Delay",
                group = GROUP_STINGER,
                parentKey = "stinger_enabled",
                active = { segmentPrefs().enableStingerAlerts },
                min = SegmentEvidenceSettings.STINGER_EARLY_MIN,
                max = SegmentEvidenceSettings.STINGER_EARLY_MAX,
                read = { segmentPrefs().stingerEarlyNotifySeconds },
                write = { segment.setStingerEarlyNotifySeconds(it) },
            ),
            numberRow(
                key = "stinger_toast_duration",
                label = "Toast Duration",
                group = GROUP_STINGER,
                parentKey = "stinger_enabled",
                active = { segmentPrefs().enableStingerAlerts },
                min = SegmentEvidenceSettings.STINGER_TOAST_MIN,
                max = SegmentEvidenceSettings.STINGER_TOAST_MAX,
                read = { segmentPrefs().stingerToastDurationSeconds },
                write = { segment.setStingerToastDurationSeconds(it) },
            ),
        )
    }

    fun ratings(owners: TvConfigOwners): List<RowSpec> {
        val holder = owners.ratings

        val current = { owners.ratingsStore.current() }
        return buildList {
            add(
                RowSpec(
                    key = "appearance",
                    label = "Appearance",
                    group = GROUP_RATINGS_APPEARANCE,
                    control = ConfigRowControl.CHOICE,
                    choices = {
                        RatingAppearance.entries.map {
                            ConfigChoiceState(it.name.lowercase(), ratingAppearanceLabel(it))
                        }
                    },
                    read = { current().appearance.name.lowercase() },
                    write = { token ->
                        ratingAppearanceOf(token)?.let {
                            ensureRatingsLoaded(owners, holder)
                            holder.setAppearance(it)
                        }
                        verifiedAwait { current().appearance.name.lowercase() == token }
                    },
                ),
            )
            for (metric in RatingMetric.entries) {
                add(
                    RowSpec(
                        key = "displayed.${metric.id}",
                        label = metric.chipLabel,
                        group = GROUP_RATINGS_DISPLAYED,
                        control = ConfigRowControl.FLAG,
                        read = { flag(current().displays(metric)) },
                        write = { token ->
                            ensureRatingsLoaded(owners, holder)
                            holder.setMetricDisplayed(metric, token.toBoolean())
                            verifiedAwait { current().displays(metric) == token.toBoolean() }
                        },
                    ),
                )
            }
            for (metric in RatingMetric.SortableEpisodeSources) {
                add(
                    RowSpec(
                        key = "episode.${metric.id}",
                        label = metric.chipLabel,
                        group = GROUP_RATINGS_EPISODE,
                        control = ConfigRowControl.FLAG,
                        read = { flag(current().isEpisodeSourceEnabled(metric)) },
                        write = { token ->
                            ensureRatingsLoaded(owners, holder)
                            holder.setEpisodeSourceEnabled(metric, token.toBoolean())
                            verifiedAwait { current().isEpisodeSourceEnabled(metric) == token.toBoolean() }
                        },
                    ),
                )
            }
            for (supplier in RatingSupplier.Configurable) {
                add(
                    RowSpec(
                        key = "supplier.${supplier.id}",
                        label = supplier.displayName,
                        group = GROUP_RATINGS_SUPPLIERS,
                        control = ConfigRowControl.FLAG,
                        read = { flag(current().isEnabled(supplier)) },
                        write = { token ->
                            ensureRatingsLoaded(owners, holder)
                            holder.setSupplierEnabled(supplier, token.toBoolean())
                            verifiedAwait { current().isEnabled(supplier) == token.toBoolean() }
                        },
                    ),
                )
            }
        }
    }

    fun home(owners: TvConfigOwners): List<RowSpec> {
        val catalog = owners.homeCatalog
        val upNext = owners.upNext
        val current = { catalog.presentationPreferences(owners.activeProfileId()) }
        val upNextShown = { owners.upNextPresentationStore.showUpNext(owners.activeProfileId()) }
        val upNextSelection = { owners.upNextSelectionStore.read(owners.activeProfileId()) }
        val spotlightActive = { current().showSpotlight }
        return listOf(
            flagRow(
                key = "show_spotlight",
                label = "Show Spotlight",
                group = GROUP_HOME,
                read = { current().showSpotlight },
                write = { catalog.setShowSpotlight(owners.activeProfileId(), it) },
            ),
            RowSpec(
                key = "spotlight_source",
                label = "Spotlight source",
                group = GROUP_HOME,
                control = ConfigRowControl.CHOICE,
                parentKey = "show_spotlight",
                active = spotlightActive,
                choices = { spotlightChoices(owners, current().spotlightSource) },
                read = {
                    HomeSpotlightSourceCodec.encodeOrAutomatic(current().spotlightSource)
                },
                write = { token ->
                    catalog.setSpotlightSource(owners.activeProfileId(), HomeSpotlightSourceCodec.decode(token))
                    verified({ HomeSpotlightSourceCodec.encodeOrAutomatic(current().spotlightSource) }, token)
                },
                extraRule = { token ->

                    val source = HomeSpotlightSourceCodec.decode(token)
                    val configured = current().spotlightSource
                    val allowed = source == HomeSpotlightSource.Automatic ||
                        spotlightRowIds(owners).contains(spotlightRowIdOf(source)) ||
                        source == configured
                    if (allowed) null else ConfigValidationError(
                        "value",
                        "this is not one of the Home rows that are configured right now",
                    )
                },
            ),
            RowSpec(
                key = "spotlight_interval",
                label = "Spotlight Auto-Roll Interval",
                group = GROUP_HOME,
                control = ConfigRowControl.CHOICE,
                parentKey = "show_spotlight",
                active = spotlightActive,
                choices = {
                    pairs(
                        "0" to "Off (0s)",
                        "5" to "5s",
                        "10" to "10s",
                        "15" to "15s",
                        "30" to "30s",
                        "60" to "60s",
                    )
                },
                read = { current().spotlightAutoRollSeconds.toString() },
                write = { token ->
                    token.toIntOrNull()?.let { catalog.setSpotlightAutoRollSeconds(owners.activeProfileId(), it) }
                    verified({ current().spotlightAutoRollSeconds.toString() }, token)
                },
            ),
            flagRow(
                key = "separate_continue_watching",
                label = "Separate Continue Watching",
                group = GROUP_HOME,
                read = { current().separateContinueWatching },
                write = { catalog.setSeparateContinueWatching(owners.activeProfileId(), it) },
            ),
            flagRow(
                key = "show_up_next",
                label = "Show Up Next",
                group = GROUP_HOME,
                read = upNextShown,
                write = { desired ->
                    upNext.setShowUpNext(desired)
                    val reached = awaitUntil { upNextShown() == desired }
                    if (reached) ConfigApplyResult.Applied else ConfigApplyResult.Failed(
                        "the owner did not keep this value",
                    )
                },
            ),
            RowSpec(
                key = "up_next_source",
                label = "Up Next Source",
                group = GROUP_HOME,
                control = ConfigRowControl.CHOICE,
                parentKey = "show_up_next",
                active = upNextShown,
                choices = { upNextChoices(owners, upNextSelection()) },
                read = { upNextSourceToken(upNextSelection(), owners) },
                write = { token ->
                    upNext.select(upNextSourceOf(token))
                    val reached = awaitUntil { upNextSourceToken(upNextSelection(), owners) == token }
                    if (reached) ConfigApplyResult.Applied else ConfigApplyResult.Failed(
                        "the owner did not keep this value",
                    )
                },
                extraRule = { token ->
                    val source = upNextSourceOf(token)
                    val trackers = owners.upNext.state.value.options.mapNotNull {
                        (it.source as? UpNextSource.Tracker)?.trackerId
                    }
                    when {
                        source is UpNextSource.Tracker && trackers.isNotEmpty() && source.trackerId !in trackers ->
                            ConfigValidationError("value", "that tracker is not connected right now")
                        source is UpNextSource.Tracker && trackers.isEmpty() &&
                            (upNextSelection().source as? UpNextSource.Tracker)?.trackerId != source.trackerId ->
                            ConfigValidationError("value", "no tracker is connected right now")
                        else -> null
                    }
                },
            ),
        )
    }

    private fun flagRow(
        key: String,
        label: String,
        group: String,
        parentKey: String? = null,
        active: () -> Boolean = { true },
        help: String? = null,
        read: () -> Boolean,
        write: (Boolean) -> Unit,
    ): RowSpec = RowSpec(
        key = key,
        label = label,
        group = group,
        control = ConfigRowControl.FLAG,
        parentKey = parentKey,
        active = active,
        help = help,
        read = { flag(read()) },
        write = { token ->
            write(token.toBoolean())
            verified({ flag(read()) }, token)
        },
    )

    private fun numberRow(
        key: String,
        label: String,
        group: String,
        min: Int,
        max: Int,
        parentKey: String? = null,
        active: () -> Boolean = { true },
        read: () -> Int,
        write: (Int) -> Unit,
    ): RowSpec = RowSpec(
        key = key,
        label = label,
        group = group,
        control = ConfigRowControl.NUMBER,
        parentKey = parentKey,
        active = active,
        min = min,
        max = max,
        read = { number(read()) },
        write = { token ->
            token.toIntOrNull()?.let { write(it) }
            verified({ number(read()) }, token)
        },
    )

    private fun pairs(vararg values: Pair<String, String>): List<ConfigChoiceState> = values.map {
        ConfigChoiceState(it.first, it.second)
    }

    private fun verifiedAwait(predicate: () -> Boolean): ConfigApplyResult =
        if (awaitUntil(predicate = predicate)) ConfigApplyResult.Applied else ConfigApplyResult.Failed(
            "the owner did not keep this value",
        )

    private fun ensureRatingsLoaded(owners: TvConfigOwners, holder: dev.infusion.feature.vertical.ratings.RatingsSettingsStateHolder) {
        if (holder.state.value.preferences == owners.ratingsStore.current()) return
        holder.refresh()
        awaitUntil { holder.state.value.preferences == owners.ratingsStore.current() }
    }

    private fun ratingAppearanceLabel(appearance: RatingAppearance): String = when (appearance) {
        RatingAppearance.TEXT -> "Text"
        RatingAppearance.ABBREVIATED_TEXT -> "Abbreviated text"
        RatingAppearance.COLOUR_LOGOS -> "Colour logos"
        RatingAppearance.MONOCHROME_LOGOS -> "Monochrome logos"
    }

    private fun accentLabel(accent: AccentColor): String = when (accent) {
        AccentColor.ORANGE -> "Orange"
        AccentColor.RED -> "Red"
        AccentColor.AMBER -> "Amber"
        AccentColor.GREEN -> "Green"
        AccentColor.BLUE -> "Blue"
        AccentColor.PURPLE -> "Purple"
    }

    private fun artworkLabel(style: ArtworkStyle): String = when (style) {
        ArtworkStyle.POSTER -> "Poster"
        ArtworkStyle.LANDSCAPE -> "Landscape"
    }

    private fun displayMatchingLabel(policy: DisplayMatchingPolicy): String = when (policy) {
        DisplayMatchingPolicy.OFF -> "Off"
        DisplayMatchingPolicy.MATCH_FRAME_RATE -> "Match frame rate"
        DisplayMatchingPolicy.MATCH_FRAME_RATE_AND_RESOLUTION -> "Match frame rate and resolution"
    }

    private fun ratingAppearanceOf(token: String): RatingAppearance? =
        RatingAppearance.entries.firstOrNull { it.name.lowercase() == token }

    private fun accentOf(token: String): AccentColor? =
        AccentColor.entries.firstOrNull { it.storageKey == token }

    private fun topShelfOf(token: String): TopShelfAppearance? =
        TopShelfAppearance.entries.firstOrNull { it.storageKey == token }

    private fun splashOf(token: String): SplashScreenMode? =
        SplashScreenMode.entries.firstOrNull { it.storageKey == token }

    private fun artworkOf(token: String): ArtworkStyle? =
        ArtworkStyle.entries.firstOrNull { it.storageKey == token }

    private fun audioPreferenceOf(token: String): AudioLanguagePreference? = when (token) {
        "system-language" -> AudioLanguagePreference.SystemLanguage
        "none" -> AudioLanguagePreference.None
        else -> AudioLanguagePreference.Tag.of(token)
    }

    private fun subtitleLanguageOf(token: String): SubtitleLanguagePreference? = when (token) {
        "none" -> SubtitleLanguagePreference.None
        else -> SubtitleLanguagePreference.Tag.of(token)
    }

    private fun subtitleModeToken(mode: SubtitleModePreference): String = when (mode) {
        SubtitleModePreference.AUTOMATIC -> "automatic"
        SubtitleModePreference.OFF -> "off"
    }

    private fun subtitleModeOf(token: String): SubtitleModePreference? = when (token) {
        "automatic" -> SubtitleModePreference.AUTOMATIC
        "off" -> SubtitleModePreference.OFF
        else -> null
    }

    private fun sameLanguageToken(policy: SubtitleSameLanguagePolicy): String = policy.name.lowercase()

    private fun sameLanguageOf(token: String): SubtitleSameLanguagePolicy? =
        SubtitleSameLanguagePolicy.entries.firstOrNull { it.name.lowercase() == token }

    private fun subtitleTypeToken(type: SubtitleTypePreference): String = type.name.lowercase()

    private fun subtitleTypeOf(token: String): SubtitleTypePreference? =
        SubtitleTypePreference.entries.firstOrNull { it.name.lowercase() == token }

    private fun dimmingToken(dimming: HdrSubtitleDimming): String = dimming.name.lowercase()

    private fun dimmingOf(token: String): HdrSubtitleDimming? =
        HdrSubtitleDimming.entries.firstOrNull { it.name.lowercase() == token }

    private fun subtitleAppearanceModeToken(mode: SubtitleAppearanceMode): String = mode.name.lowercase()

    private fun subtitleAppearanceModeOf(token: String): SubtitleAppearanceMode? =
        SubtitleAppearanceMode.entries.firstOrNull { it.name.lowercase() == token }

    private fun subtitleSizeOf(token: String): SubtitleSizeChoice? =
        SubtitleSizeChoice.entries.firstOrNull { it.name.lowercase() == token }

    private fun subtitleFontOf(token: String): SubtitleFontChoice? =
        SubtitleFontChoice.entries.firstOrNull { it.name.lowercase() == token }

    private fun subtitleTextColorOf(token: String): SubtitleTextColorChoice? =
        SubtitleTextColorChoice.entries.firstOrNull { it.name.lowercase() == token }

    private fun subtitleBackgroundOf(token: String): SubtitleBackgroundChoice? =
        SubtitleBackgroundChoice.entries.firstOrNull { it.name.lowercase() == token }

    private fun subtitleOpacityOf(token: String): SubtitleBackgroundOpacityChoice? =
        SubtitleBackgroundOpacityChoice.entries.firstOrNull { it.name.lowercase() == token }

    private fun subtitleEdgeOf(token: String): SubtitleEdgeChoice? =
        SubtitleEdgeChoice.entries.firstOrNull { it.name.lowercase() == token }

    private fun subtitlePositionOf(token: String): SubtitlePositionChoice? =
        SubtitlePositionChoice.entries.firstOrNull { it.name.lowercase() == token }

    private fun upNextSourceToken(selection: UpNextSelection, owners: TvConfigOwners): String =
        when (val source = selection.source) {
            is UpNextSource.Tracker -> "tracker:${source.trackerId}"

            null, UpNextSource.LocalOnly -> UP_NEXT_LOCAL_ONLY
        }

    private fun upNextSourceOf(token: String): UpNextSource = when {
        token == UP_NEXT_LOCAL_ONLY -> UpNextSource.LocalOnly
        token.startsWith("tracker:") -> UpNextSource.Tracker(token.removePrefix("tracker:"))
        else -> UpNextSource.LocalOnly
    }

    private fun upNextChoices(owners: TvConfigOwners, selection: UpNextSelection): List<ConfigChoiceState> {
        val options = buildList {
            add(ConfigChoiceState(UP_NEXT_LOCAL_ONLY, "Local only"))
            owners.upNext.state.value.options.forEach { option ->
                val source = option.source
                if (source is UpNextSource.Tracker) {
                    add(ConfigChoiceState("tracker:${source.trackerId}", option.label))
                }
            }
        }

        val recorded = (selection.source as? UpNextSource.Tracker)?.trackerId
        if (recorded != null && options.none { it.token == "tracker:$recorded" }) {
            return options + ConfigChoiceState("tracker:$recorded", "Tracker (not connected)")
        }
        return options
    }

    private fun spotlightRowIds(owners: TvConfigOwners): List<HomeSpotlightRowId?> =
        owners.widgets.widgetConfiguration(owners.activeProfileId()).records.map { record ->
            HomeSpotlightRowId.Widget(record.widgetId)
        }

    private fun spotlightRowIdOf(source: HomeSpotlightSource): HomeSpotlightRowId? =
        (source as? HomeSpotlightSource.Row)?.rowId

    private fun spotlightChoices(
        owners: TvConfigOwners,
        configured: HomeSpotlightSource,
    ): List<ConfigChoiceState> {
        val widgetRows = owners.widgets.widgetConfiguration(owners.activeProfileId()).records
        val choices = buildList {
            add(
                ConfigChoiceState(
                    HomeSpotlightSourceCodec.encode(HomeSpotlightSource.Automatic),
                    "Automatic",
                ),
            )
            widgetRows.forEach { record ->
                val source = HomeSpotlightSource.Row(HomeSpotlightRowId.Widget(record.widgetId))
                add(
                    ConfigChoiceState(
                        HomeSpotlightSourceCodec.encode(source),
                        record.displayTitle?.takeIf { it.isNotBlank() } ?: "Home row",
                    ),
                )
            }
        }
        val encoded = HomeSpotlightSourceCodec.encodeOrAutomatic(configured)
        if (choices.none { it.token == encoded }) {
            return choices + ConfigChoiceState(encoded, "Saved row (not configured)")
        }
        return choices
    }

    const val GROUP_GENERAL: String = "General"
    const val GROUP_DISPLAY: String = "Display"
    const val GROUP_AUDIO: String = "Audio"
    const val GROUP_SUBTITLES: String = "Subtitles"
    const val GROUP_CONTROLS: String = "Playback controls"
    const val GROUP_INTRO: String = "Intro, recap and credits"
    const val GROUP_NEXT: String = "Next Episode"
    const val GROUP_STINGER: String = "Post-credit scenes"
    const val GROUP_RATINGS_APPEARANCE: String = "Appearance"
    const val GROUP_RATINGS_DISPLAYED: String = "Displayed sources"
    const val GROUP_RATINGS_EPISODE: String = "Episode card ratings"
    const val GROUP_RATINGS_SUPPLIERS: String = "Advanced"
    const val GROUP_HOME: String = "Home"

    const val DEVICE_SCOPE_HELP: String =
        "This setting applies to the whole device, not to one profile."

    const val UP_NEXT_LOCAL_ONLY: String = "local_only"
}
