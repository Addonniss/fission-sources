package dev.infusion.tv.ui.screens

import dev.infusion.feature.vertical.acquisition.AcquisitionConnectionUiState
import dev.infusion.feature.vertical.acquisition.Phase

internal object SettingsRowHelp {

    internal object General {

        const val Accent =
            "Sets the highlight colour used for focus, selection and accents throughout fission."

        const val Theme =
            "Changes the overall look of fission: colours, materials, gradients, shapes and type. " +
                "Classic is the original appearance."

        const val TopShelfAppearance =
            "Shows the top bar's destinations as text only, as icons only, or as both."

        const val SplashScreen =
            "Chooses when the splash screen steps aside: as soon as Home is ready, or once Home's " +
                "first background image has loaded."

        const val MoviesArtwork = "Shows movie cards as posters or as landscape artwork."

        const val SeriesArtwork = "Shows series cards as posters or as landscape artwork."

        const val TmdbBackdropQuality =
            "Sets the image size used for the wide background behind a title. Larger sizes look " +
                "sharper."

        const val TmdbLandscapeCardQuality =
            "Sets the image size used inside a landscape card."

        const val HideTitles = "Hides the title text on movie and show cards."

        const val HideEpisodeTitles = "Hides the title text on episode cards."

        const val HideWatchedTitles =
            "Hides titles you have finished from browsing rows. Their history is kept."

        const val ShowProviderName =
            "Adds the source's name to the title of the Home rows it supplies. Rows fission " +
                "builds itself, such as Continue Watching, are not renamed."

        const val CardBadges =
            "Shows badges on the cards you browse. The rows below choose which badges."

        const val Watched = "Marks a finished title with a watched badge."

        const val Watchlist = "Marks a title that is on your watchlist."

        const val JellyfinAvailability =
            "Marks a title that is available on your Jellyfin server."

        const val Progress = "Shows a progress bar on a title you have started."

        const val ContextMenu =
            "Shows a small menu mark on the card you have focused. A long press on a card opens its " +
                "menu; this only shows or hides the mark."
    }

    internal object Playback {

        const val DisplayMatching =
            "Matches the television's refresh rate, and optionally its resolution, to the video.\n" +
                "\n" +
                SettingsPlaybackDisplayMatchingNotice

        const val DolbyVisionProfile7Fallback = SettingsPlaybackDolbyVisionProfile7FallbackNotice

        const val AudioLanguage =
            "Preferred audio track for new playback. Choosing a track in the player affects only " +
                "that playback."

        const val SecondaryAudioLanguage =
            "Used when the preferred audio language is not in a title. Choosing a track in the " +
                "player affects only that playback."

        const val Subtitles =
            "Automatic turns subtitles on when a track in your chosen language is found. Off " +
                "leaves them off until you turn them on in the player.\n\n" +
                SettingsPlaybackSubtitleNotice

        const val SubtitleLanguage =
            "Preferred subtitle language. If nothing matches, subtitles stay off rather than " +
                "switching to a different kind."

        const val SecondarySubtitleLanguage =
            "Used when the preferred subtitle language is not in a title."

        const val SubtitlesInAudioLanguage =
            "Whether a subtitle in the language of the audio track may be chosen: never, only " +
                "when it is forced, or always."

        const val SubtitleType =
            "Which kind of subtitle track is chosen. SDH is recognised only from a track's " +
                "declared caption role or an embedded track's own SDH or hearing-impaired title."

        const val DimSubtitlesInHdr = SettingsPlaybackDimSubtitlesInHdrNotice

        const val SubtitleAppearance = SettingsPlaybackSubtitleAppearanceNotice

        const val SubtitleSize = "Text size of custom subtitles."

        const val SubtitleFont = "Font of custom subtitles."

        const val SubtitleTextColor = "Text colour of custom subtitles."

        const val SubtitleBackground = "The box drawn behind custom subtitle text."

        const val SubtitleBackgroundOpacity = "How solid that box is."

        const val SubtitleEdge = "The outline that keeps custom subtitle text readable."

        const val SubtitlePosition =
            "Where custom subtitles sit. It applies to subtitles that do not bring their own " +
                "position."

        const val ControlsAutoHide =
            "Hides the player's controls after this long without a press. Off keeps them until " +
                "you hide them."

        const val RelativeSeekStep = "How far one press of rewind or fast forward moves."

        const val MovieCompletion = SettingsPlaybackCompletionNotice

        const val EpisodeCompletion = SettingsPlaybackCompletionNotice

        const val SegmentDisplay = SettingsPlaybackSegmentNotice

        const val SegmentLegend =
            "Shows a compact legend beside the bar. It appears only while the display is not Off."

        const val IntroMetadataPriority =
            "Prefers online provider metadata for opening evidence. With it off, chapters are " +
                "used first."

        const val FallbackIntroWindow =
            "Uses a fixed time window as opening evidence when no other evidence exists."

        const val FallbackIntroStart =
            "Where the fallback window starts. Used only while the window is on and no other " +
                "opening evidence exists."

        const val FallbackIntroEnd =
            "Where the fallback window ends. Used only while the window is on and no other " +
                "opening evidence exists."

        const val EnableSkipIntro = SettingsPlaybackSegmentA2Notice

        const val AutoSkipIntro =
            "Skips the intro automatically after a countdown you can cancel."

        const val AutoSkipDelay = "How long the auto-skip countdown waits. 0 skips at once."

        const val PersistentButton =
            "Keeps the Skip Intro button on screen for as long as it applies, instead of hiding " +
                "it after a few seconds."

        const val StingerAlerts =
            "Warns about a movie's extra scene after the credits. It never stops or advances " +
                "playback."

        const val StingerTriggerPercent =
            "How far into the movie the warning triggers, as a percentage of its runtime."

        const val StingerEarlyNotify = "Sets how early the warning appears. Off disables it."

        const val StingerToastDuration = "How long the warning stays on screen."

        const val SourceAutoplay = SettingsPlaybackSourceAutoplayNotice

        const val PreferJellyfin =
            "Gives a usable Jellyfin file priority over automatic add-on selection."

        const val EnableNextEpisode = SettingsPlaybackNextEpisodeNotice

        const val NextOnlinePriority =
            "Prefers online provider metadata for where an episode ends and the next is offered."

        const val NextFallbackPercent =
            "The point where the next episode is offered when no better evidence exists."

        const val AutoPlayNext =
            "Starts the next episode after a countdown you can cancel. Cancelling stops automatic " +
                "continuation for this episode only — Next stays available. Independent of Source " +
                "autoplay."

        const val AutoPlayDelay = "How long that countdown waits."
    }

    internal object Home {

        const val SeparateContinueWatching =
            "Splits Continue Watching into one row per source instead of a single mixed row."

        const val ShowSpotlight =
            "Shows the featured Spotlight at the top of Home. Off removes it from Home."

        const val SpotlightSource =
            "Chooses which Home row supplies the Spotlight. Automatic uses Home's own choice."

        const val SpotlightInterval =
            "How often the Spotlight moves on by itself. Off leaves it to manual Left/Right."

        const val ShowUpNext = "Shows an Up Next row on Home."

        const val UpNextSource =
            "Chooses where the Up Next row reads progress from. Local only uses this device's own " +
                "progress."

        const val ProviderGroup =
            "Opens this source's rows. The cell at the right end shows or hides them on Home."

        const val Catalog = "Puts this row on Home, or takes it off."
    }

    internal object Ratings {

        const val Appearance = "How community scores are shown on Details."

        const val DisplayedMetric =
            "Shows this score on Details. Rotten Tomatoes critics and audience are separate " +
                "scores."

        const val EpisodeSource =
            "Shows this source's score on episode cards. The ▲/▼ cells change its order. IMDb, " +
                "TMDB and Trakt each publish an exact-episode score; this list is independent of " +
                "the sources above."

        const val OmdbKey =
            "Optional. Your own OMDb API key supplies IMDb, Rotten Tomatoes and Metacritic " +
                "scores. It is stored securely on this device only."

        const val Advanced =
            "Opens the provider priority list, where you choose which provider wins when several " +
                "supply the same score."

        const val SupplierPriority =
            "Uses this provider's score. The ▲/▼ cells change which provider wins."
    }

    internal object Tracking {

        fun notConnected(name: String) =
            "Connect to use $name with Watch Tracking. Connecting does not change which service " +
                "is used."

        fun connecting(name: String) = "Requesting a code from $name…"

        fun awaitingApproval(name: String) =
            "Approve the $name code on another device. The connection finishes by itself."

        fun connected(accountLabel: String?, refreshAt: String?): String = buildString {
            append(if (accountLabel != null) "Connected as $accountLabel." else "Connected.")
            append(" fission renews this sign-in automatically.")
            if (refreshAt != null) {
                append(" The saved sign-in can be renewed until $refreshAt; after that, connect again.")
            }
        }

        fun reconnectRequired(expiredOn: String?): String = if (expiredOn != null) {
            "The saved sign-in expired on $expiredOn. Choose Disconnect, then Connect to renew it."
        } else {
            "The saved sign-in expired. Choose Disconnect, then Connect to renew it."
        }
    }

    internal object Jellyfin {

        const val Credentials =
            "Signs in with your Jellyfin server address, username and password. Test connection " +
                "checks them without saving anything; Save replaces the source fission uses."

        const val ApiKey =
            "Connects with an API key from your Jellyfin server, then uses the Jellyfin user you " +
                "pick from its list. Testing and loading users save nothing."

        const val QuickConnect =
            "Shows a code to approve in Jellyfin on another device, so no password is typed here. " +
                "The connection finishes on its own once the code is approved."

        const val RemoveSource =
            "Disconnects the saved Jellyfin source and revokes its stored sign-in on this device. " +
                "Your library, history and progress are kept, and nothing changes on the server."
    }

    internal object Acquisition {

        fun radarr(state: AcquisitionConnectionUiState): String = acquisitionRowHelp(
            use = "Adds a film you choose to Radarr and starts its search. Every request uses the " +
                "root folder and quality profile saved here.",
            state = state,
        )

        fun sonarr(state: AcquisitionConnectionUiState): String = acquisitionRowHelp(
            use = "Adds a series you choose to Sonarr and monitors its episodes. Every request " +
                "uses the root folder and quality profile saved here.",
            state = state,
        )

        private fun acquisitionRowHelp(use: String, state: AcquisitionConnectionUiState): String =
            buildString {
                append(use)
                when {
                    state.connectedSummary != null -> {
                        append(" Saved: ").append(state.connectedSummary).append('.')
                        append(" Choose the row to change or remove it.")
                    }
                    state.phase == Phase.LOADED ->
                        append(" This configuration was tested but not saved, so nothing uses it yet.")
                    state.phase == Phase.FAILED ->
                        append(" The last test failed, so no configuration of this one is in force.")
                    else ->
                        append(" Not configured yet, so fission cannot send requests to it.")
                }
            }
    }

    internal object Profiles {

        const val Create =
            "Adds a new profile. Its watch history and preferences are kept apart from your other " +
                "profiles."

        const val Switch =
            "Makes this the profile fission uses. A profile with a PIN asks for it first."

        const val Rename = "Changes this profile's name. Everything saved in the profile stays."

        const val Delete =
            "Deletes this profile after you choose what happens to its watch history."
    }

    internal object Widgets {

        const val ContentSource =
            "Chooses where the widget's rows come from: an installed add-on's catalog, a public " +
                "list, or an imported collection pack."

        const val Addon =
            "Chooses the installed add-on whose own catalog the widget will show."

        const val Catalog = "Chooses which of that add-on's catalogs the widget shows."

        const val Provider =
            "Chooses the public-list service the widget reads from. A provider that uses your " +
                "connected account says so in its own list."

        const val Route =
            "Chooses what that provider returns: one of its own public feeds, your account's " +
                "own lists, or a search."

        const val User =
            "Chooses whose lists the widget reads, from the accounts the lookup found."

        const val List = "Chooses the one list this widget shows."

        const val ImportedPack =
            "Chooses the imported collection pack this widget's group belongs to."

        const val CollectionGroup = "Chooses which group of that pack the widget shows."

        const val CollectionPage =
            "Chooses the collection page whose named rows you want to manage."

        const val Destination =
            "Chooses which page receives the widget when you choose Add widget."

        const val ManageRows =
            "Choose which rows each collection page shows, and the order they appear in. " +
                "Hiding a row stops it loading."

        const val ReimportPack =
            "Reads this pack again from its own recorded source and updates it in place."

        const val DeletePack =
            "Removes this pack. Widgets placed from it stay and show as unavailable."

        const val AddWidget =
            "Adds this widget as a new row at the end of the chosen page's rows; you can move or " +
                "delete it afterwards."
    }
}
