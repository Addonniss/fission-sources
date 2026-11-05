package dev.infusion.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.infusion.tv.ui.theme.InfusionTvButton
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import dev.infusion.core.domain.appearance.AccentColor
import dev.infusion.core.domain.appearance.ArtworkStyle
import dev.infusion.core.domain.appearance.SplashScreenMode
import dev.infusion.core.domain.appearance.TmdbBackdropQuality
import dev.infusion.core.domain.appearance.TmdbLandscapeCardQuality
import dev.infusion.core.domain.appearance.TopShelfAppearance
import dev.infusion.core.domain.home.HomePresentationDefaults
import dev.infusion.core.playback.api.AudioLanguagePreference
import dev.infusion.core.playback.api.AudioLanguageTag
import dev.infusion.core.playback.api.CompletionThreshold
import dev.infusion.core.playback.api.NextEpisodePreferences
import dev.infusion.core.playback.api.PlaybackControlsAutoHide
import dev.infusion.core.playback.api.SegmentDisplayMode
import dev.infusion.core.playback.api.SegmentEvidencePreferences
import dev.infusion.core.playback.api.SegmentFallbackTimecode
import dev.infusion.core.playback.api.SourceAutoplayPolicy
import dev.infusion.core.playback.api.SourceAutoplayPreferences
import dev.infusion.core.playback.api.SubtitleAppearance
import dev.infusion.core.playback.api.SubtitleAppearanceMode
import dev.infusion.core.playback.api.SubtitleBackgroundChoice
import dev.infusion.core.playback.api.SubtitleBackgroundOpacityChoice
import dev.infusion.core.playback.api.SubtitleEdgeChoice
import dev.infusion.core.playback.api.SubtitleFontChoice
import dev.infusion.core.playback.api.SubtitleLanguagePreference
import dev.infusion.core.playback.api.SubtitleModePreference
import dev.infusion.core.playback.api.SubtitlePositionChoice
import dev.infusion.core.playback.api.SubtitlePreferences
import dev.infusion.core.playback.api.SubtitleSameLanguagePolicy
import dev.infusion.core.playback.api.SubtitleSizeChoice
import dev.infusion.core.playback.api.SubtitleTextColorChoice
import dev.infusion.core.playback.api.SubtitleTypePreference
import dev.infusion.core.playback.api.DisplayMatchingPolicy
import dev.infusion.core.playback.api.DolbyVisionProfile7Fallback
import dev.infusion.core.playback.api.HdrSubtitleDimming
import dev.infusion.feature.vertical.home.HomeCatalogGroupUi
import dev.infusion.feature.vertical.home.HomeCatalogKey
import dev.infusion.feature.vertical.home.HomeCatalogOptionUi
import dev.infusion.feature.vertical.home.HomeCustomizationStateHolder
import dev.infusion.feature.vertical.home.HomeCustomizationUiState
import dev.infusion.feature.vertical.home.HomeSpotlightSource
import dev.infusion.feature.vertical.home.HomeSpotlightSourceCodec
import dev.infusion.feature.vertical.home.homeSpotlightRowKey
import dev.infusion.feature.vertical.home.homeSpotlightSourceLabel
import dev.infusion.feature.vertical.home.WidgetBuilderPageUi
import dev.infusion.feature.vertical.home.WidgetBuilderStateHolder
import dev.infusion.feature.vertical.home.WidgetBuilderUiState
import dev.infusion.feature.vertical.profile.PinEntryStateHolder
import dev.infusion.feature.vertical.profile.ProfileManagementStateHolder
import dev.infusion.feature.vertical.acquisition.AcquisitionConnectionStateHolder
import dev.infusion.feature.vertical.setup.JellyfinConnectedUser
import dev.infusion.feature.vertical.setup.JellyfinConnectionStateHolder
import dev.infusion.feature.vertical.setup.JellyfinServerDiscoveryStateHolder
import dev.infusion.feature.vertical.setup.JellyfinSetupStateHolder
import dev.infusion.feature.vertical.setup.JellyfinSetupStatus
import dev.infusion.feature.vertical.tracking.TrackingSettingsStateHolder
import dev.infusion.feature.vertical.tracking.TrackingSettingsUiState
import dev.infusion.feature.vertical.ratings.RatingsSettingsStateHolder
import dev.infusion.feature.vertical.addon.AddonManagementStateHolder
import dev.infusion.feature.vertical.addon.AddonManagementUiState
import dev.infusion.feature.vertical.ratings.RatingsSettingsUiState
import dev.infusion.core.domain.ratings.OmdbTestOutcome
import dev.infusion.core.domain.ratings.RatingsPreferences
import dev.infusion.core.model.metadata.RatingAppearance
import dev.infusion.core.model.metadata.RatingMetric
import dev.infusion.core.model.metadata.RatingSupplier
import dev.infusion.core.domain.tracking.TrackingConnectionState
import dev.infusion.core.domain.tracking.TrackingSelection
import dev.infusion.feature.vertical.tracking.TRACKING_LOCAL_LABEL
import dev.infusion.feature.vertical.tracking.TRACKING_SELECTION_TITLE
import dev.infusion.feature.vertical.tracking.TRACKING_THIS_DEVICE_SUBTITLE
import dev.infusion.feature.vertical.tracking.trackingSelectionNotice
import dev.infusion.feature.vertical.tracking.trackingSelectionRowValue
import dev.infusion.feature.vertical.tracking.trackingSelectionSubtitle
import dev.infusion.feature.vertical.tracking.trackingSelectedSendCountLine
import dev.infusion.feature.vertical.tracking.trackingTrackerLabel
import dev.infusion.feature.vertical.tracking.trackingUserChoiceCountLine
import dev.infusion.tv.ui.focus.DirectionalPressOwnership
import dev.infusion.tv.ui.focus.FocusKeyRegistry
import dev.infusion.tv.ui.focus.LocalDirectionalPressOwnership
import dev.infusion.tv.ui.focus.TvEntryEvent
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.shell.InfusionTvShell
import dev.infusion.tv.ui.shell.RailContentPage
import dev.infusion.tv.ui.shell.RailSection
import dev.infusion.tv.ui.theme.InfusionTvAppearance
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvFieldLabel
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.InfusionTvStateTextField
import dev.infusion.tv.ui.theme.InfusionTvTheme
import dev.infusion.tv.ui.theme.LocalInfusionAccent
import dev.infusion.tv.ui.theme.infusionAccentContentColor
import dev.infusion.tv.ui.theme.infusionSelectionBorder
import dev.infusion.tv.ui.theme.infusionTvButtonBorder
import dev.infusion.tv.ui.theme.infusionTvButtonColors
import kotlin.math.abs

internal const val JellyfinFocusUrl = "jellyfin:url"
internal const val JellyfinFocusUsername = "jellyfin:username"
internal const val JellyfinFocusPassword = "jellyfin:password"
internal const val JellyfinFocusConnect = "jellyfin:connect"
internal const val JellyfinFocusRemove = "jellyfin:remove"

const val SettingsPairingOpen = "settings:pairing:open"
const val SettingsDiagnosticsOpen = "settings:diagnostics:open"

internal const val TrackingFocusAction = "tracking:action"
internal const val TrackingFocusCode = "tracking:code"
const val SettingsTrackingPane = "settings:tracking:pane"

const val SettingsTrackingAction = "settings:tracking:action"
fun trackingActionTag(providerId: String): String = "$SettingsTrackingAction:$providerId"
const val SettingsTrackingStatus = "settings:tracking:status"
fun trackingStatusTag(providerId: String): String = "$SettingsTrackingStatus:$providerId"

const val SettingsTrackingSelection = "settings:tracking:selection"

const val SettingsTrackingSends = "settings:tracking:count:sends"
const val SettingsTrackingNotSent = "settings:tracking:count:not-sent"

const val TrackingSelectionOnDeviceOnlyKey = "on_device_only"

const val SettingsTrackingOverlay = "settings:tracking:overlay"
const val SettingsTrackingOverlayAction = "settings:tracking:overlay:action"
const val SettingsTrackingOverlayStatus = "settings:tracking:overlay:status"
const val SettingsTrackingCode = "settings:tracking:code"
const val SettingsTrackingAuthorizationUrl = "settings:tracking:url"
const val SettingsTrackingQr = "settings:tracking:qr"

const val SettingsGeneralPane = "settings:general:pane"

const val SettingsGeneralAccentRow = "settings:general:accent"

const val SettingsGeneralTopShelfAppearanceRow = "settings:general:top-shelf-appearance"

const val SettingsGeneralSplashScreenRow = "settings:general:splash-screen"

const val SettingsGeneralMoviesArtworkRow = "settings:general:movies-artwork"

const val SettingsGeneralSeriesArtworkRow = "settings:general:series-artwork"

const val SettingsGeneralTmdbBackdropQualityRow = "settings:general:tmdb-backdrop-quality"

const val SettingsGeneralTmdbLandscapeCardQualityRow =
    "settings:general:tmdb-landscape-card-quality"

const val SettingsGeneralHideTitlesRow = "settings:general:hide-titles"

const val SettingsGeneralHideEpisodeTitlesRow = "settings:general:hide-episode-titles"

const val SettingsGeneralHideWatchedTitlesRow = "settings:general:hide-watched-titles"

const val SettingsGeneralShowProviderNameRow = "settings:general:provider-name"

const val SettingsGeneralCardBadgesRow = "settings:general:card-badges"

const val SettingsGeneralWatchedRow = "settings:general:watched"

const val SettingsGeneralWatchlistBadgeRow = "settings:general:watchlist-badge"

const val SettingsGeneralJellyfinAvailabilityRow = "settings:general:jellyfin-availability"

const val SettingsGeneralProgressBarRow = "settings:general:progress-bar"

const val SettingsGeneralContextMenuBadgeRow = "settings:general:context-menu-badge"

const val SettingsGeneralAccentChoicePrefix = "$SettingsGeneralAccentRow:choice:"

fun generalSelectorChoiceKey(rowTag: String, storageKey: String): String =
    "$rowTag:choice:$storageKey"

private val GeneralRowKeys = listOf(
    SettingsGeneralAccentRow,
    SettingsGeneralTopShelfAppearanceRow,
    SettingsGeneralSplashScreenRow,
    SettingsGeneralMoviesArtworkRow,
    SettingsGeneralSeriesArtworkRow,
    SettingsGeneralTmdbBackdropQualityRow,
    SettingsGeneralTmdbLandscapeCardQualityRow,
    SettingsGeneralHideTitlesRow,
    SettingsGeneralHideEpisodeTitlesRow,
    SettingsGeneralHideWatchedTitlesRow,
    SettingsGeneralShowProviderNameRow,
    SettingsGeneralCardBadgesRow,
    SettingsGeneralWatchedRow,
    SettingsGeneralWatchlistBadgeRow,
    SettingsGeneralJellyfinAvailabilityRow,
    SettingsGeneralProgressBarRow,
    SettingsGeneralContextMenuBadgeRow,
)

internal fun generalRowKeysFor(cardBadges: Boolean): List<String> =
    if (cardBadges) {
        GeneralRowKeys
    } else {
        GeneralRowKeys - setOf(
            SettingsGeneralWatchedRow,
            SettingsGeneralWatchlistBadgeRow,
            SettingsGeneralJellyfinAvailabilityRow,
            SettingsGeneralProgressBarRow,
            SettingsGeneralContextMenuBadgeRow,
        )
    }

const val SettingsPlaybackPane = "settings:playback:pane"

const val SettingsPlaybackDisplayMatchingRow = "settings:playback:display-matching"

const val SettingsPlaybackAudioLanguageRow = "settings:playback:audio-language"

const val SettingsPlaybackAudioLanguageSecondaryRow = "settings:playback:audio-language-secondary"

const val SettingsPlaybackSubtitlesRow = "settings:playback:subtitles"
const val SettingsPlaybackSubtitleLanguageRow = "settings:playback:subtitle-language"
const val SettingsPlaybackSubtitleLanguageSecondaryRow = "settings:playback:subtitle-language-secondary"
const val SettingsPlaybackSubtitleSameLanguageRow = "settings:playback:subtitle-same-language"
const val SettingsPlaybackSubtitleTypeRow = "settings:playback:subtitle-type"

const val SettingsPlaybackDimSubtitlesInHdrRow = "settings:playback:dim-subtitles-in-hdr"

const val SettingsPlaybackSubtitleAppearanceRow = "settings:playback:subtitle-appearance"
const val SettingsPlaybackSubtitleSizeRow = "settings:playback:subtitle-size"
const val SettingsPlaybackSubtitleFontRow = "settings:playback:subtitle-font"
const val SettingsPlaybackSubtitleTextColorRow = "settings:playback:subtitle-text-color"
const val SettingsPlaybackSubtitleBackgroundRow = "settings:playback:subtitle-background"
const val SettingsPlaybackSubtitleBackgroundOpacityRow = "settings:playback:subtitle-background-opacity"
const val SettingsPlaybackSubtitleEdgeRow = "settings:playback:subtitle-edge"
const val SettingsPlaybackSubtitlePositionRow = "settings:playback:subtitle-position"

const val SettingsPlaybackMovieCompletionRow = "settings:playback:movie-completion"
const val SettingsPlaybackEpisodeCompletionRow = "settings:playback:episode-completion"

const val SettingsPlaybackControlsAutoHideRow = "settings:playback:controls-auto-hide"

const val SettingsPlaybackRelativeSeekStepRow = "settings:playback:relative-seek-step"

internal fun relativeSeekStepChoiceLabel(step: dev.infusion.core.playback.api.RelativeSeekStep): String =
    "${step.seconds}s"

internal fun playbackControlsAutoHideChoiceLabel(autoHide: PlaybackControlsAutoHide): String =
    when (autoHide) {
        PlaybackControlsAutoHide.OFF -> "Off"
        PlaybackControlsAutoHide.THREE_SECONDS -> "3s"
        PlaybackControlsAutoHide.FIVE_SECONDS -> "5s"
        PlaybackControlsAutoHide.TEN_SECONDS -> "10s"
    }

const val SettingsPlaybackSegmentOnlinePriorityRow = "settings:playback:segment-online-priority"
const val SettingsPlaybackSegmentFallbackRow = "settings:playback:segment-fallback"
const val SettingsPlaybackSegmentFallbackStartRow = "settings:playback:segment-fallback-start"
const val SettingsPlaybackSegmentFallbackEndRow = "settings:playback:segment-fallback-end"

const val SettingsPlaybackSegmentDisplayRow = "settings:playback:segment-display"
const val SettingsPlaybackSegmentLegendRow = "settings:playback:segment-legend"

internal fun segmentDisplayChoiceLabel(mode: SegmentDisplayMode): String = when (mode) {
    SegmentDisplayMode.OFF -> "Off"
    SegmentDisplayMode.ABOVE_PROGRESS_BAR -> "Above progress bar"
    SegmentDisplayMode.ON_PROGRESS_BAR -> "On progress bar"
}

internal fun segmentLegendChoiceLabel(enabled: Boolean): String = if (enabled) "On" else "Off"

internal fun segmentOnlinePriorityChoiceLabel(enabled: Boolean): String = if (enabled) "On" else "Off"

internal fun segmentManualFallbackChoiceLabel(enabled: Boolean): String = if (enabled) "On" else "Off"

const val SettingsPlaybackEnableSkipIntroRow = "settings:playback:enable-skip-intro"
const val SettingsPlaybackAutoSkipIntroRow = "settings:playback:auto-skip-intro"
const val SettingsPlaybackAutoSkipDelayRow = "settings:playback:auto-skip-delay"
const val SettingsPlaybackPersistentButtonRow = "settings:playback:persistent-button"
const val SettingsPlaybackStingerAlertsRow = "settings:playback:stinger-alerts"
const val SettingsPlaybackStingerPercentRow = "settings:playback:stinger-trigger-percent"
const val SettingsPlaybackStingerEarlyRow = "settings:playback:stinger-early-notify"
const val SettingsPlaybackStingerToastRow = "settings:playback:stinger-toast-duration"

const val SettingsPlaybackSourceAutoplayRow = "settings:playback:source-autoplay"
const val SettingsPlaybackPreferJellyfinRow = "settings:playback:prefer-jellyfin"

internal fun sourceAutoplayChoiceLabel(policy: SourceAutoplayPolicy): String = when (policy) {
    SourceAutoplayPolicy.OFF -> "Off"
    SourceAutoplayPolicy.FIRST_RESPONDING_ADDON -> "First responding add-on"
    SourceAutoplayPolicy.FIRST_COMBINED_ADDON_RESULT -> "First combined add-on result"
}

const val SettingsPlaybackSourceAutoplayNotice =
    "When enabled, a source is chosen automatically for a new start; the choice is shown as a " +
        "cancellable countdown you can dismiss. Prefer Jellyfin wins a usable server file before " +
        "automatic add-on selection. Choosing a source yourself always cancels the countdown."

const val SettingsPlaybackEnableNextEpisodeRow = "settings:playback:enable-next-episode"
const val SettingsPlaybackNextOnlinePriorityRow = "settings:playback:next-online-priority"
const val SettingsPlaybackAutoPlayNextRow = "settings:playback:auto-play-next"
const val SettingsPlaybackAutoPlayDelayRow = "settings:playback:auto-play-delay"
const val SettingsPlaybackNextFallbackPercentRow = "settings:playback:next-fallback-percent"

internal fun autoPlayDelayChoiceLabel(seconds: Int): String =
    if (seconds == 0) "Immediately (0s)" else "${seconds}s"

internal fun nextFallbackPercentChoiceLabel(percent: Int): String = "$percent%"

internal val autoPlayDelayChoices: List<Int> = (0..20).toList()

internal val nextFallbackPercentChoices: List<Int> = (50..99).toList()

const val SettingsPlaybackNextEpisodeNotice =
    "Next Episode is offered near the end of an episode using the same credits/chapter evidence the " +
        "player already gathers. Automatic continuation shows a cancellable countdown you can " +
        "dismiss; cancelling stops automatic continuation for the current episode (Next stays " +
        "available manually). Source autoplay and automatic continuation are independent settings."

internal fun segmentToggleChoiceLabel(enabled: Boolean): String = if (enabled) "On" else "Off"

internal fun autoSkipDelayChoiceLabel(seconds: Int): String = "${seconds}s"

internal fun stingerPercentChoiceLabel(percent: Int): String = "$percent%"

internal fun stingerEarlyChoiceLabel(seconds: Int): String = if (seconds == 0) "Off" else "${seconds}s"

internal fun stingerToastChoiceLabel(seconds: Int): String = "${seconds}s"

internal val autoSkipDelayChoices: List<Int> = (0..10).toList()

internal val stingerPercentChoices: List<Int> = (50..99).toList()

internal val stingerEarlyChoices: List<Int> = (0..60).toList()

internal val stingerToastChoices: List<Int> = (1..30).toList()

const val SettingsPlaybackSegmentA2Notice =
    "Skip Intro is offered for episodes when opening evidence exists; auto-skip shows a cancellable " +
        "countdown (0 skips at once). Stinger alerts are movie-only and never stop or advance playback."

const val SettingsPlaybackSegmentNotice =
    "Intro, recap, credits and chapter evidence is gathered per playback and drawn on the progress " +
        "bar. Online metadata is preferred when Available; chapters are used first when the " +
        "priority is Off. The fallback window is only used when no other opening evidence exists. " +
        "Display and legend only change how it is drawn — evidence is still gathered while hidden."

internal fun segmentFallbackTimecodeChoiceLabel(timecode: SegmentFallbackTimecode): String = timecode.storageKey

const val SettingsPlaybackCompletionNotice =
    "Reaching the selected percentage marks the title watched without stopping playback. " +
        "Applies to playback started after this change."

internal data class PlaybackGroup(

    val heading: String,

    val rowKeys: List<String>,
)

internal val PlaybackDisplayGroup = PlaybackGroup(
    heading = "Display",
    rowKeys = listOf(
        SettingsPlaybackDisplayMatchingRow,
        SettingsPlaybackDolbyVisionProfile7FallbackRow,
    ),
)

internal val PlaybackAudioGroup = PlaybackGroup(
    heading = "Audio",
    rowKeys = listOf(
        SettingsPlaybackAudioLanguageRow,
        SettingsPlaybackAudioLanguageSecondaryRow,
    ),
)

internal val PlaybackSubtitlesGroup = PlaybackGroup(
    heading = "Subtitles",
    rowKeys = listOf(
        SettingsPlaybackSubtitlesRow,
        SettingsPlaybackSubtitleLanguageRow,
        SettingsPlaybackSubtitleLanguageSecondaryRow,
        SettingsPlaybackSubtitleSameLanguageRow,
        SettingsPlaybackSubtitleTypeRow,

        SettingsPlaybackDimSubtitlesInHdrRow,
        SettingsPlaybackSubtitleAppearanceRow,
    ),
)

internal val PlaybackControlsGroup = PlaybackGroup(
    heading = "Playback controls",
    rowKeys = listOf(
        SettingsPlaybackControlsAutoHideRow,

        SettingsPlaybackRelativeSeekStepRow,
        SettingsPlaybackMovieCompletionRow,
        SettingsPlaybackEpisodeCompletionRow,

        SettingsPlaybackSegmentDisplayRow,
        SettingsPlaybackSegmentLegendRow,

        SettingsPlaybackSourceAutoplayRow,
        SettingsPlaybackPreferJellyfinRow,
    ),
)

internal val PlaybackIntroGroup = PlaybackGroup(
    heading = "Intro, recap and credits",
    rowKeys = listOf(

        SettingsPlaybackSegmentOnlinePriorityRow,
        SettingsPlaybackSegmentFallbackRow,
        SettingsPlaybackSegmentFallbackStartRow,
        SettingsPlaybackSegmentFallbackEndRow,

        SettingsPlaybackEnableSkipIntroRow,
        SettingsPlaybackAutoSkipIntroRow,
        SettingsPlaybackAutoSkipDelayRow,
        SettingsPlaybackPersistentButtonRow,
    ),
)

internal val PlaybackNextEpisodeGroup = PlaybackGroup(
    heading = "Next episode",
    rowKeys = listOf(
        SettingsPlaybackEnableNextEpisodeRow,
        SettingsPlaybackNextOnlinePriorityRow,
        SettingsPlaybackNextFallbackPercentRow,
        SettingsPlaybackAutoPlayNextRow,
        SettingsPlaybackAutoPlayDelayRow,
    ),
)

internal val PlaybackStingerGroup = PlaybackGroup(
    heading = "Post-credit scenes",
    rowKeys = listOf(
        SettingsPlaybackStingerAlertsRow,
        SettingsPlaybackStingerPercentRow,
        SettingsPlaybackStingerEarlyRow,
        SettingsPlaybackStingerToastRow,
    ),
)

internal val PlaybackGroups: List<PlaybackGroup> = listOf(
    PlaybackDisplayGroup,
    PlaybackAudioGroup,
    PlaybackSubtitlesGroup,
    PlaybackControlsGroup,
    PlaybackIntroGroup,
    PlaybackNextEpisodeGroup,
    PlaybackStingerGroup,
)

private val PlaybackRowKeys: List<String> = PlaybackGroups.flatMap { it.rowKeys }

internal fun completionThresholdChoiceLabel(threshold: CompletionThreshold): String =
    "${threshold.percent}%"

private val PlaybackCustomAppearanceRowKeys = listOf(
    SettingsPlaybackSubtitleSizeRow,
    SettingsPlaybackSubtitleFontRow,
    SettingsPlaybackSubtitleTextColorRow,
    SettingsPlaybackSubtitleBackgroundRow,
    SettingsPlaybackSubtitleBackgroundOpacityRow,
    SettingsPlaybackSubtitleEdgeRow,
    SettingsPlaybackSubtitlePositionRow,
)

internal fun playbackRowKeysFor(
    mode: SubtitleAppearanceMode,
    manualFallbackEnabled: Boolean = false,
    autoSkipIntro: Boolean = false,
    stingerAlerts: Boolean = true,
    sourceAutoplayEnabled: Boolean = false,
    enableNextEpisode: Boolean = true,
    autoPlayNextEpisode: Boolean = false,
): List<String> {

    val hidden = buildSet {
        if (!manualFallbackEnabled) {
            add(SettingsPlaybackSegmentFallbackStartRow)
            add(SettingsPlaybackSegmentFallbackEndRow)
        }
        if (!autoSkipIntro) add(SettingsPlaybackAutoSkipDelayRow)
        if (!stingerAlerts) {
            add(SettingsPlaybackStingerPercentRow)
            add(SettingsPlaybackStingerEarlyRow)
            add(SettingsPlaybackStingerToastRow)
        }

        if (!sourceAutoplayEnabled) add(SettingsPlaybackPreferJellyfinRow)

        if (!enableNextEpisode) {
            add(SettingsPlaybackNextOnlinePriorityRow)
            add(SettingsPlaybackNextFallbackPercentRow)
            add(SettingsPlaybackAutoPlayNextRow)
            add(SettingsPlaybackAutoPlayDelayRow)
        } else if (!autoPlayNextEpisode) {
            add(SettingsPlaybackAutoPlayDelayRow)
        }
    }
    val tail = PlaybackRowKeys.filterNot { it in hidden }
    return buildList {
        for (key in tail) {
            add(key)

            if (key == SettingsPlaybackSubtitleAppearanceRow && mode == SubtitleAppearanceMode.CUSTOM) {
                addAll(PlaybackCustomAppearanceRowKeys)
            }
        }
    }
}

const val SettingsPlaybackDisplayMatchingNotice =
    "The screen may briefly go black while the television changes display mode.\n" +
        "\n" +

        "This setting applies to the whole device, not to one profile."

fun displayMatchingChoiceLabel(policy: DisplayMatchingPolicy): String = when (policy) {
    DisplayMatchingPolicy.OFF -> "Off"
    DisplayMatchingPolicy.MATCH_FRAME_RATE -> "Match frame rate"
    DisplayMatchingPolicy.MATCH_FRAME_RATE_AND_RESOLUTION -> "Match frame rate and resolution"
}

fun displayMatchingChoiceSupportingText(policy: DisplayMatchingPolicy): String = when (policy) {
    DisplayMatchingPolicy.OFF -> "Keep the current resolution and refresh rate."
    DisplayMatchingPolicy.MATCH_FRAME_RATE ->
        "Match video cadence while retaining the current output resolution where possible."
    DisplayMatchingPolicy.MATCH_FRAME_RATE_AND_RESOLUTION ->
        "Match both the video cadence and native resolution."
}

const val SettingsPlaybackDolbyVisionProfile7FallbackRow = "settings:playback:dv-profile7-fallback"

const val SettingsPlaybackDolbyVisionProfile7FallbackNotice =
    "Uses the HDR10 base layer of a Dolby Vision Profile 7 title when this device cannot play " +
        "that profile as Dolby Vision. Applies to playback started after this change.\n" +
        "\n" +

        "This setting applies to the whole device, not to one profile."

fun dolbyVisionProfile7FallbackChoiceLabel(fallback: DolbyVisionProfile7Fallback): String =
    when (fallback) {
        DolbyVisionProfile7Fallback.ON -> "On"
        DolbyVisionProfile7Fallback.OFF -> "Off"
    }

fun dolbyVisionProfile7FallbackChoiceSupportingText(fallback: DolbyVisionProfile7Fallback): String =
    when (fallback) {
        DolbyVisionProfile7Fallback.ON ->
            "Play the HDR10 base layer when this device does not support the title's Dolby Vision profile."
        DolbyVisionProfile7Fallback.OFF ->
            "Always keep the normal Dolby Vision path, even when this device cannot play it."
    }

const val SettingsPlaybackAudioLanguageNotice =
    "Applies to playback started after this change. Choosing an audio track in the player affects " +
        "only that playback."

const val SettingsPlaybackSubtitleNotice =
    "Applies to playback started after this change. Choosing a subtitle in the player affects only " +
        "that playback. If your subtitle settings match nothing, subtitles stay off rather than " +
        "switching to a different kind. SDH is recognised only from a track's declared caption role " +
        "or an embedded track's own SDH or hearing-impaired title."

const val SettingsPlaybackDimSubtitlesInHdrNotice =
    "While HDR or Dolby Vision video is playing, caps the subtitle text colour so it is less bright. " +
        "It applies to both System and Custom subtitle appearance and changes only the rendered text " +
        "colour — font, size, background and edge are kept. Image subtitles, such as Blu-ray PGS or " +
        "DVD VobSub, are drawn by the source and are not changed."

internal fun hdrSubtitleDimmingChoiceLabel(dimming: HdrSubtitleDimming): String =
    if (dimming == HdrSubtitleDimming.ON) "On" else "Off"

enum class AudioLanguageSelector {

    PRIMARY,

    SECONDARY,
}

enum class SubtitleLanguageSelector {

    PRIMARY,

    SECONDARY,
}

fun audioLanguageChoiceKey(preference: AudioLanguagePreference): String = when (preference) {
    AudioLanguagePreference.SystemLanguage -> "system-language"
    AudioLanguagePreference.None -> "none"
    is AudioLanguagePreference.Tag -> preference.value
}

private val representableAudioLanguages: List<Pair<String, String>> = listOf(
    "af" to "Afrikaans",
    "sq" to "Albanian",
    "am" to "Amharic",
    "ar" to "Arabic",
    "hy" to "Armenian",
    "az" to "Azerbaijani",
    "eu" to "Basque",
    "be" to "Belarusian",
    "bn" to "Bengali",
    "bs" to "Bosnian",
    "bg" to "Bulgarian",
    "my" to "Burmese",
    "ca" to "Catalan",
    "zh" to "Chinese",
    "zh-CN" to "Chinese (Simplified)",
    "zh-TW" to "Chinese (Traditional)",
    "hr" to "Croatian",
    "cs" to "Czech",
    "da" to "Danish",
    "nl" to "Dutch",
    "en" to "English",
    "et" to "Estonian",
    "tl" to "Filipino",
    "fi" to "Finnish",
    "fr" to "French",
    "gl" to "Galician",
    "ka" to "Georgian",
    "de" to "German",
    "el" to "Greek",
    "gu" to "Gujarati",
    "he" to "Hebrew",
    "hi" to "Hindi",
    "hu" to "Hungarian",
    "is" to "Icelandic",
    "id" to "Indonesian",
    "ga" to "Irish",
    "it" to "Italian",
    "ja" to "Japanese",
    "kn" to "Kannada",
    "kk" to "Kazakh",
    "km" to "Khmer",
    "ko" to "Korean",
    "lo" to "Lao",
    "lv" to "Latvian",
    "lt" to "Lithuanian",
    "mk" to "Macedonian",
    "ms" to "Malay",
    "ml" to "Malayalam",
    "mt" to "Maltese",
    "mr" to "Marathi",
    "mn" to "Mongolian",
    "ne" to "Nepali",
    "no" to "Norwegian",
    "pa" to "Punjabi",
    "fa" to "Persian",
    "pl" to "Polish",
    "pt" to "Portuguese (Portugal)",
    "pt-br" to "Portuguese (Brazil)",
    "ro" to "Romanian",
    "ru" to "Russian",
    "sr" to "Serbian",
    "si" to "Sinhala",
    "sk" to "Slovak",
    "sl" to "Slovenian",
    "es" to "Spanish",
    "es-419" to "Spanish (Latin America)",
    "sw" to "Swahili",
    "sv" to "Swedish",
    "ta" to "Tamil",
    "te" to "Telugu",
    "th" to "Thai",
    "tr" to "Turkish",
    "uk" to "Ukrainian",
    "ur" to "Urdu",
    "uz" to "Uzbek",
    "vi" to "Vietnamese",
    "cy" to "Welsh",
    "zu" to "Zulu",
)

private val representableAudioLanguageNames: Map<String, String> = buildMap {
    for ((code, name) in representableAudioLanguages) {
        val canonical = AudioLanguageTag.canonicalize(code) ?: continue
        putIfAbsent(canonical, name)
    }
}

private fun representableAudioLanguageChoices(): List<AudioLanguagePreference> =
    representableAudioLanguages
        .mapNotNull { (code, _) -> AudioLanguagePreference.Tag.of(code) }
        .distinct()
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { audioLanguageChoiceLabel(it) })

fun audioLanguageChoiceLabel(preference: AudioLanguagePreference): String = when (preference) {
    AudioLanguagePreference.SystemLanguage -> "System language"
    AudioLanguagePreference.None -> "None"
    is AudioLanguagePreference.Tag ->
        representableAudioLanguageNames[preference.value]
            ?: platformLanguageDisplayName(preference.value)
            ?: preference.value
}

private fun platformLanguageDisplayName(tag: String): String? {
    val locale = runCatching { java.util.Locale.forLanguageTag(tag) }.getOrNull() ?: return null
    val name = runCatching { locale.getDisplayLanguage() }.getOrNull()
    if (name.isNullOrBlank() || name.equals(locale.language, ignoreCase = true)) return null
    return name
}

fun audioLanguageSelectorChoices(
    selector: AudioLanguageSelector,
    current: AudioLanguagePreference,
    primaryPreference: AudioLanguagePreference,
): List<AudioLanguagePreference> {
    val languages = representableAudioLanguageChoices()
    return when (selector) {
        AudioLanguageSelector.PRIMARY -> buildList {
            add(AudioLanguagePreference.SystemLanguage)
            addAll(languages)
            if (current is AudioLanguagePreference.Tag && current !in languages) add(current)
        }
        AudioLanguageSelector.SECONDARY -> buildList {
            add(AudioLanguagePreference.None)
            val excluded = primaryPreference as? AudioLanguagePreference.Tag
            addAll(languages.filter { language -> language != excluded })
            if (current is AudioLanguagePreference.Tag && current != excluded && current !in this) add(current)
        }
    }
}

fun subtitleLanguageChoiceLabel(preference: SubtitleLanguagePreference): String = when (preference) {
    SubtitleLanguagePreference.None -> "None"
    is SubtitleLanguagePreference.Tag ->

        representableAudioLanguageNames[preference.value]
            ?: platformLanguageDisplayName(preference.value)
            ?: preference.value
}

fun subtitleLanguageChoiceKey(preference: SubtitleLanguagePreference): String = when (preference) {
    SubtitleLanguagePreference.None -> "none"
    is SubtitleLanguagePreference.Tag -> preference.value
}

fun subtitleModeChoiceLabel(mode: SubtitleModePreference): String = when (mode) {
    SubtitleModePreference.AUTOMATIC -> "Automatic"
    SubtitleModePreference.OFF -> "Off"
}

fun subtitleSameLanguageChoiceLabel(policy: SubtitleSameLanguagePolicy): String = when (policy) {
    SubtitleSameLanguagePolicy.OFF -> "Off"
    SubtitleSameLanguagePolicy.FORCED_ONLY -> "Forced only"
    SubtitleSameLanguagePolicy.FULL -> "Full subtitles allowed"
}

fun subtitleTypeChoiceLabel(type: SubtitleTypePreference): String = when (type) {
    SubtitleTypePreference.AUTOMATIC -> "Automatic"
    SubtitleTypePreference.STANDARD -> "Standard"
    SubtitleTypePreference.SDH -> "SDH"
    SubtitleTypePreference.FORCED_ONLY -> "Forced only"
}

const val SettingsPlaybackSubtitleAppearanceNotice =
    "System uses your television's own caption style and text size. Custom applies the settings below " +
        "to text subtitles. Image subtitles, such as Blu-ray PGS or DVD VobSub, are drawn by the " +
        "source and are not changed. Position applies to subtitles that do not bring their own position."

fun subtitleAppearanceModeChoiceLabel(mode: SubtitleAppearanceMode): String = when (mode) {
    SubtitleAppearanceMode.SYSTEM -> "System"
    SubtitleAppearanceMode.CUSTOM -> "Custom"
}

fun subtitleAppearanceModeChoiceSupportingText(mode: SubtitleAppearanceMode): String = when (mode) {
    SubtitleAppearanceMode.SYSTEM ->
        "Use the television's accessibility caption style, including its text size."
    SubtitleAppearanceMode.CUSTOM ->
        "Style text subtitles with the settings below. Your saved choices are kept if you switch back."
}

fun subtitleSizeChoiceLabel(choice: SubtitleSizeChoice): String = when (choice) {
    SubtitleSizeChoice.SMALL -> "Small"
    SubtitleSizeChoice.MEDIUM -> "Medium"
    SubtitleSizeChoice.LARGE -> "Large"
    SubtitleSizeChoice.EXTRA_LARGE -> "Extra large"
}

fun subtitleFontChoiceLabel(choice: SubtitleFontChoice): String = when (choice) {
    SubtitleFontChoice.SYSTEM_DEFAULT -> "Default"
    SubtitleFontChoice.SANS_SERIF -> "Sans serif"
    SubtitleFontChoice.SERIF -> "Serif"
    SubtitleFontChoice.MONOSPACE -> "Monospace"
}

fun subtitleTextColorChoiceLabel(choice: SubtitleTextColorChoice): String = when (choice) {
    SubtitleTextColorChoice.WHITE -> "White"
    SubtitleTextColorChoice.YELLOW -> "Yellow"
    SubtitleTextColorChoice.CYAN -> "Cyan"
    SubtitleTextColorChoice.GREEN -> "Green"
}

fun subtitleBackgroundChoiceLabel(choice: SubtitleBackgroundChoice): String = when (choice) {
    SubtitleBackgroundChoice.BLACK -> "Black"
    SubtitleBackgroundChoice.DARK_GREY -> "Dark grey"
    SubtitleBackgroundChoice.DARK_BLUE -> "Dark blue"
    SubtitleBackgroundChoice.NONE -> "None"
}

fun subtitleBackgroundOpacityChoiceLabel(choice: SubtitleBackgroundOpacityChoice): String = when (choice) {
    SubtitleBackgroundOpacityChoice.FULL -> "100%"
    SubtitleBackgroundOpacityChoice.HIGH -> "80%"
    SubtitleBackgroundOpacityChoice.MEDIUM -> "60%"
    SubtitleBackgroundOpacityChoice.LOW -> "40%"
}

fun subtitleEdgeChoiceLabel(choice: SubtitleEdgeChoice): String = when (choice) {
    SubtitleEdgeChoice.NONE -> "None"
    SubtitleEdgeChoice.OUTLINE -> "Outline"
    SubtitleEdgeChoice.SHADOW -> "Shadow"
}

fun subtitlePositionChoiceLabel(choice: SubtitlePositionChoice): String = when (choice) {
    SubtitlePositionChoice.BOTTOM -> "Bottom"
    SubtitlePositionChoice.RAISED -> "Higher"
    SubtitlePositionChoice.MIDDLE -> "Middle"
}

fun subtitleLanguageSelectorChoices(
    selector: SubtitleLanguageSelector,
    current: SubtitleLanguagePreference,
    primaryPreference: SubtitleLanguagePreference,
): List<SubtitleLanguagePreference> {
    val languages = representableAudioLanguageChoices().mapNotNull { preference ->
        (preference as? AudioLanguagePreference.Tag)?.let { tag -> SubtitleLanguagePreference.Tag.of(tag.value) }
    }
    return when (selector) {
        SubtitleLanguageSelector.PRIMARY -> buildList {
            add(SubtitleLanguagePreference.None)
            addAll(languages)
            if (current is SubtitleLanguagePreference.Tag && current !in languages) add(current)
        }
        SubtitleLanguageSelector.SECONDARY -> buildList {
            add(SubtitleLanguagePreference.None)
            val excluded = primaryPreference as? SubtitleLanguagePreference.Tag
            addAll(languages.filter { language -> language != excluded })
            if (current is SubtitleLanguagePreference.Tag && current != excluded && current !in this) add(current)
        }
    }
}

const val SettingsHomeSeparateCwSwitch = "settings:home:switch:separate-cw"

const val SettingsHomeSpotlightSwitch = "settings:home:switch:spotlight"

const val SettingsHomeSpotlightSourceRow = "settings:home:spotlight-source"

const val SettingsHomeSpotlightIntervalRow = "settings:home:spotlight-interval"

const val SettingsHomeUpNextSwitch = "settings:home:switch:up-next"

const val SettingsHomeUpNextSourceRow = "settings:home:up-next-source"

fun upNextSourceChoiceKey(source: dev.infusion.core.domain.upnext.UpNextSource): String =
    when (source) {
        dev.infusion.core.domain.upnext.UpNextSource.LocalOnly -> "local"
        is dev.infusion.core.domain.upnext.UpNextSource.Tracker -> "tracker:${source.trackerId}"
    }

val SpotlightAutoRollIntervals: List<Int> = listOf(0, 5, 10, 15, 30, 60)

fun spotlightIntervalChoiceLabel(seconds: Int): String =
    if (seconds <= 0) "Off (0s)" else "${seconds}s"

fun spotlightIntervalValueLabel(seconds: Int): String =
    if (seconds <= 0) "Off" else "${seconds}s"

fun spotlightSourceChoiceKey(source: HomeSpotlightSource): String = when (source) {
    HomeSpotlightSource.Automatic -> HomeSpotlightSourceCodec.TOKEN_AUTOMATIC
    is HomeSpotlightSource.Row -> homeSpotlightRowKey(source.rowId)
}

fun spotlightSourceChoices(
    options: List<dev.infusion.feature.vertical.home.HomeSpotlightSourceOption>,
): List<HomeSpotlightSource> =
    listOf(HomeSpotlightSource.Automatic) + options.map { option -> HomeSpotlightSource.Row(option.rowId) }

internal val HomeRowSpacing = 8.dp
internal val HomeRowActionWidth = 56.dp
internal val HomeRowToggleWidth = 96.dp
internal val HomeRowHeight = 48.dp
internal val HomeRowCellPadding = 12.dp

internal val GeneralRowHeight = 48.dp
private val GeneralRowMaxWidth = 520.dp
private val GeneralSelectorWidth = 360.dp

private val GeneralChildRowLabelIndent = 24.dp

private val GeneralSelectorMaxHeight = 480.dp

private val SpotlightSourceSelectorWidth = 470.dp
private val SpotlightSourceSelectorMaxHeight = 420.dp

private val GeneralSelectorRowSpacing = 4.dp

private val TrackingOverlayWidth = 520.dp
private val TrackingOverlayMaxHeight = 640.dp

private fun homePreferenceStateKey(preferenceKey: String) = "$preferenceKey:state"

private const val ProfilesFirstKey = KeyCreate
private const val PairingFirstKey = "pairing:open"
private const val DiagnosticsFirstKey = "diagnostics:open"

private fun homeGroupKey(addonId: String) = "settings:home:group:$addonId"
private fun homeGroupShownKey(addonId: String) = "settings:home:group:$addonId:shown"
private fun homeGroupMoveUpKey(addonId: String) = "settings:home:group:$addonId:up"
private fun homeGroupMoveDownKey(addonId: String) = "settings:home:group:$addonId:down"
private fun homeCatalogRowKey(key: HomeCatalogKey) = "settings:home:catalog:${key.shelfKey}"
private fun homeCatalogShownKey(key: HomeCatalogKey) = "settings:home:catalog:${key.shelfKey}:shown"
private fun homeCatalogMoveUpKey(key: HomeCatalogKey) = "settings:home:catalog:${key.shelfKey}:up"
private fun homeCatalogMoveDownKey(key: HomeCatalogKey) = "settings:home:catalog:${key.shelfKey}:down"

private data class HomeFocusCell(val column: Int, val key: String)

private fun homeRowFocusOrder(
    state: HomeCustomizationUiState,
    expandedGroupIds: Set<String>,

    showUpNext: Boolean = false,

    showSpotlight: Boolean = true,
): List<List<HomeFocusCell>> {
    val rows = mutableListOf<List<HomeFocusCell>>()

    rows += listOf(HomeFocusCell(column = 0, key = SettingsHomeSeparateCwSwitch))

    rows += listOf(HomeFocusCell(column = 0, key = SettingsHomeSpotlightSwitch))

    if (showSpotlight) {

        rows += listOf(HomeFocusCell(column = 0, key = SettingsHomeSpotlightSourceRow))

        rows += listOf(HomeFocusCell(column = 0, key = SettingsHomeSpotlightIntervalRow))
    }

    rows += listOf(HomeFocusCell(column = 0, key = SettingsHomeUpNextSwitch))
    if (showUpNext) {
        rows += listOf(HomeFocusCell(column = 0, key = SettingsHomeUpNextSourceRow))
    }
    state.groups.forEach { group ->
        val providerRow = mutableListOf(HomeFocusCell(column = 0, key = homeGroupKey(group.addonId)))
        if (providerCanMoveUp(group)) {
            providerRow += HomeFocusCell(column = 1, key = homeGroupMoveUpKey(group.addonId))
        }
        if (providerCanMoveDown(group)) {
            providerRow += HomeFocusCell(column = 2, key = homeGroupMoveDownKey(group.addonId))
        }

        providerRow += HomeFocusCell(column = 3, key = homeGroupShownKey(group.addonId))
        rows += providerRow
        if (group.addonId in expandedGroupIds) {
            group.catalogs.forEach { option ->

                val catalogRow = mutableListOf(
                    HomeFocusCell(column = 0, key = homeCatalogRowKey(option.key)),
                )
                if (option.canMoveUp) {
                    catalogRow += HomeFocusCell(column = 1, key = homeCatalogMoveUpKey(option.key))
                }
                if (option.canMoveDown) {
                    catalogRow += HomeFocusCell(column = 2, key = homeCatalogMoveDownKey(option.key))
                }

                rows += catalogRow
            }
        }
    }
    return rows
}

private fun homeFocusableKeys(
    state: HomeCustomizationUiState,
    expandedGroupIds: Set<String>,
    showUpNext: Boolean = false,
    showSpotlight: Boolean = true,
): Set<String> = homeRowFocusOrder(state, expandedGroupIds, showUpNext, showSpotlight)
    .flatten()
    .mapTo(mutableSetOf()) { cell -> cell.key }

private fun homeInRowFocusKeys(
    state: HomeCustomizationUiState,
    expandedGroupIds: Set<String>,
    showUpNext: Boolean = false,
    showSpotlight: Boolean = true,
): Set<String> = homeRowFocusOrder(state, expandedGroupIds, showUpNext, showSpotlight)
    .flatMap { row -> row.drop(1) }
    .mapTo(mutableSetOf()) { cell -> cell.key }

private class HomeGridNav(private val rows: List<List<HomeFocusCell>>) {

    fun verticalTargetKey(currentKey: String, direction: Key): String? {
        val delta = when (direction) {
            Key.DirectionUp -> -1
            Key.DirectionDown -> 1
            else -> return null
        }
        val rowIndex = rows.indexOfFirst { row -> row.any { cell -> cell.key == currentKey } }
        if (rowIndex < 0) return null
        val column = rows[rowIndex].first { cell -> cell.key == currentKey }.column
        val targetRow = rows.getOrNull(rowIndex + delta) ?: return null
        return targetRow.minByOrNull { cell -> abs(cell.column - column) }?.key
    }

    fun horizontalTargetKey(currentKey: String, direction: Key): String? {
        val delta = when (direction) {
            Key.DirectionLeft -> -1
            Key.DirectionRight -> 1
            else -> return null
        }
        val row = rows.firstOrNull { row -> row.any { cell -> cell.key == currentKey } }
            ?: return null
        val index = row.indexOfFirst { cell -> cell.key == currentKey }
        return row.getOrNull(index + delta)?.key
    }
}

private fun providerCanMoveUp(group: HomeCatalogGroupUi): Boolean =
    !group.hiddenFromHome && group.canMoveUp

private fun providerCanMoveDown(group: HomeCatalogGroupUi): Boolean =
    !group.hiddenFromHome && group.canMoveDown

private class PendingReorderFocus {
    var key: String? = null
    var fallbackKey: String? = null
}

@Composable
fun TvSettingsScreen(
    model: TvTopNavModel? = null,
    jellyfinConnected: Boolean = false,
    onNavigate: (TopLevelDestination) -> Unit = {},
    onExit: () -> Unit = {},
    setup: JellyfinSetupStateHolder? = null,
    sourceLabel: String? = null,

    jellyfinConnection: JellyfinConnectionStateHolder? = null,

    jellyfinConnectedUser: JellyfinConnectedUser? = null,

    acquisition: List<AcquisitionConnectionStateHolder> = emptyList(),

    jellyfinDiscovery: JellyfinServerDiscoveryStateHolder? = null,

    tracking: List<TrackingSettingsStateHolder> = emptyList(),

    ratingsSettings: RatingsSettingsStateHolder? = null,
    profileManagement: ProfileManagementStateHolder? = null,
    pinEntryFactory: ((String) -> PinEntryStateHolder)? = null,
    onOpenPairing: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    homeCustomization: HomeCustomizationStateHolder? = null,

    widgetBuilder: WidgetBuilderStateHolder? = null,

    collectionPackManager: dev.infusion.feature.vertical.collections.CollectionPackManagerStateHolder? = null,

    collectionRowSettings: dev.infusion.feature.vertical.collections.CollectionRowSettingsStateHolder? = null,

    upNextSettings: dev.infusion.feature.vertical.home.UpNextSettingsStateHolder? = null,

    addonsManagement: AddonManagementStateHolder? = null,

    selectedSection: String = SettingsRailGeneral,
    onSelectedSectionChange: (String) -> Unit = {},

    accent: AccentColor = AccentColor.DEFAULT,

    onAccentChange: (AccentColor) -> Unit = {},

    topShelfAppearance: TopShelfAppearance = TopShelfAppearance.DEFAULT,

    onTopShelfAppearanceChange: (TopShelfAppearance) -> Unit = {},

    splashScreen: SplashScreenMode = SplashScreenMode.DEFAULT,

    onSplashScreenChange: (SplashScreenMode) -> Unit = {},

    tmdbBackdropQuality: TmdbBackdropQuality = TmdbBackdropQuality.DEFAULT,

    onTmdbBackdropQualityChange: (TmdbBackdropQuality) -> Unit = {},

    tmdbLandscapeCardQuality: TmdbLandscapeCardQuality = TmdbLandscapeCardQuality.DEFAULT,

    onTmdbLandscapeCardQualityChange: (TmdbLandscapeCardQuality) -> Unit = {},

    moviesArtwork: ArtworkStyle = ArtworkStyle.DEFAULT_MOVIES,

    onMoviesArtworkChange: (ArtworkStyle) -> Unit = {},

    seriesArtwork: ArtworkStyle = ArtworkStyle.DEFAULT_SERIES,

    onSeriesArtworkChange: (ArtworkStyle) -> Unit = {},

    hideTitles: Boolean = false,

    onHideTitlesChange: (Boolean) -> Unit = {},

    hideEpisodeTitles: Boolean = false,

    onHideEpisodeTitlesChange: (Boolean) -> Unit = {},

    cardBadges: Boolean = true,

    onCardBadgesChange: (Boolean) -> Unit = {},

    watchedCheckmark: Boolean = true,

    onWatchedCheckmarkChange: (Boolean) -> Unit = {},

    watchlistBadge: Boolean = true,

    onWatchlistBadgeChange: (Boolean) -> Unit = {},

    jellyfinAvailabilityBadge: Boolean = true,

    onJellyfinAvailabilityBadgeChange: (Boolean) -> Unit = {},

    progressBar: Boolean = true,

    onProgressBarChange: (Boolean) -> Unit = {},

    contextMenuBadge: Boolean = true,

    onContextMenuBadgeChange: (Boolean) -> Unit = {},

    hideWatchedTitles: Boolean = false,

    onHideWatchedTitlesChange: (Boolean) -> Unit = {},

    displayMatching: DisplayMatchingPolicy = DisplayMatchingPolicy.DEFAULT,

    onDisplayMatchingChange: (DisplayMatchingPolicy) -> Unit = {},

    dolbyVisionProfile7Fallback: DolbyVisionProfile7Fallback = DolbyVisionProfile7Fallback.DEFAULT,

    onDolbyVisionProfile7FallbackChange: (DolbyVisionProfile7Fallback) -> Unit = {},

    audioLanguage: AudioLanguagePreference = AudioLanguagePreference.SystemLanguage,

    onAudioLanguageChange: (AudioLanguagePreference) -> Unit = {},

    secondaryAudioLanguage: AudioLanguagePreference = AudioLanguagePreference.None,

    onSecondaryAudioLanguageChange: (AudioLanguagePreference) -> Unit = {},

    subtitlePreferences: SubtitlePreferences = SubtitlePreferences.DEFAULT,

    onSubtitleModeChange: (SubtitleModePreference) -> Unit = {},

    onSubtitleLanguageChange: (SubtitleLanguagePreference) -> Unit = {},

    onSecondarySubtitleLanguageChange: (SubtitleLanguagePreference) -> Unit = {},

    onSubtitleSameLanguageChange: (SubtitleSameLanguagePolicy) -> Unit = {},

    onSubtitleTypeChange: (SubtitleTypePreference) -> Unit = {},

    subtitleAppearance: SubtitleAppearance = SubtitleAppearance.DEFAULT,

    onSubtitleAppearanceModeChange: (SubtitleAppearanceMode) -> Unit = {},

    onSubtitleSizeChange: (SubtitleSizeChoice) -> Unit = {},

    onSubtitleFontChange: (SubtitleFontChoice) -> Unit = {},

    onSubtitleTextColorChange: (SubtitleTextColorChoice) -> Unit = {},

    onSubtitleBackgroundChange: (SubtitleBackgroundChoice) -> Unit = {},

    onSubtitleBackgroundOpacityChange: (SubtitleBackgroundOpacityChoice) -> Unit = {},

    onSubtitleEdgeChange: (SubtitleEdgeChoice) -> Unit = {},

    onSubtitlePositionChange: (SubtitlePositionChoice) -> Unit = {},

    dimSubtitlesInHdr: HdrSubtitleDimming = HdrSubtitleDimming.DEFAULT,

    onDimSubtitlesInHdrChange: (HdrSubtitleDimming) -> Unit = {},

    movieCompletion: CompletionThreshold = CompletionThreshold.DEFAULT,

    onMovieCompletionChange: (CompletionThreshold) -> Unit = {},

    episodeCompletion: CompletionThreshold = CompletionThreshold.DEFAULT,

    onEpisodeCompletionChange: (CompletionThreshold) -> Unit = {},

    controlsAutoHide: PlaybackControlsAutoHide = PlaybackControlsAutoHide.DEFAULT,

    onControlsAutoHideChange: (PlaybackControlsAutoHide) -> Unit = {},

    relativeSeekStep: dev.infusion.core.playback.api.RelativeSeekStep =
        dev.infusion.core.playback.api.RelativeSeekStep.DEFAULT,

    onRelativeSeekStepChange: (dev.infusion.core.playback.api.RelativeSeekStep) -> Unit = {},

    segmentPreferences: SegmentEvidencePreferences = SegmentEvidencePreferences.DEFAULT,
    onSegmentOnlinePriorityChange: (Boolean) -> Unit = {},
    onSegmentManualFallbackChange: (Boolean) -> Unit = {},
    onSegmentFallbackStartChange: (SegmentFallbackTimecode) -> Unit = {},
    onSegmentFallbackEndChange: (SegmentFallbackTimecode) -> Unit = {},

    onSegmentDisplayChange: (SegmentDisplayMode) -> Unit = {},

    onSegmentLegendChange: (Boolean) -> Unit = {},

    onEnableSkipIntroChange: (Boolean) -> Unit = {},
    onAutoSkipIntroChange: (Boolean) -> Unit = {},
    onAutoSkipDelayChange: (Int) -> Unit = {},
    onPersistentSkipButtonChange: (Boolean) -> Unit = {},
    onEnableStingerAlertsChange: (Boolean) -> Unit = {},
    onStingerTriggerPercentChange: (Int) -> Unit = {},
    onStingerEarlyNotifyChange: (Int) -> Unit = {},
    onStingerToastDurationChange: (Int) -> Unit = {},

    sourceAutoplayPreferences: SourceAutoplayPreferences = SourceAutoplayPreferences.DEFAULT,

    onSourceAutoplayPolicyChange: (SourceAutoplayPolicy) -> Unit = {},

    onPreferJellyfinChange: (Boolean) -> Unit = {},

    nextEpisodePreferences: NextEpisodePreferences = NextEpisodePreferences.DEFAULT,

    onEnableNextEpisodeChange: (Boolean) -> Unit = {},

    onNextOnlinePriorityChange: (Boolean) -> Unit = {},

    onAutoPlayNextChange: (Boolean) -> Unit = {},

    onAutoPlayDelayChange: (Int) -> Unit = {},

    onNextFallbackPercentChange: (Int) -> Unit = {},
) {

    val resolvedModel = model ?: remember { TvTopNavModel() }

    val profilesRequesters = rememberFocusRequesters()
    val jellyfinRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val trackingRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val ratingsRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val pairingRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val addonsRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val diagnosticsRequesters = remember { mutableMapOf<String, FocusRequester>() }

    val aboutRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val homeRequesters = remember { mutableMapOf<String, FocusRequester>() }

    val widgetsRequesters = rememberFocusRequesters()
    val generalRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val playbackRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val requesters = rememberFocusRequesters()
    val chrome = rememberTopLevelPageChrome(TopLevelDestination.SETTINGS, resolvedModel)

    LaunchedEffect(chrome.entryToken, chrome.lastEntryEvent) {
        if (chrome.entryToken > 0 && chrome.lastEntryEvent == TvEntryEvent.ACTIVE_TAB_RESELECT) {
            onSelectedSectionChange(SettingsRailGeneral)
        }
    }

    var expandedHomeGroups by remember { mutableStateOf<Set<String>>(emptySet()) }

    val homeState: HomeCustomizationUiState? by if (homeCustomization != null) {
        homeCustomization.state.collectAsState()
    } else {
        remember { mutableStateOf<HomeCustomizationUiState?>(null) }
    }
    val homeInRowKeys: Set<String> = homeState?.let { home ->
        homeInRowFocusKeys(
            home,
            expandedHomeGroups,

            upNextSettings?.state?.value?.showUpNext == true,

            home.showSpotlight,
        )
    } ?: emptySet()

    val widgetsState: WidgetBuilderUiState? by if (widgetBuilder != null) {
        widgetBuilder.state.collectAsState()
    } else {
        remember { mutableStateOf<WidgetBuilderUiState?>(null) }
    }
    val widgetPages: List<WidgetBuilderPageUi> = widgetsState?.pages.orEmpty()

    val collectionPackState: dev.infusion.feature.vertical.collections.CollectionPackManagerUiState by
        if (collectionPackManager != null) {
            collectionPackManager.state.collectAsState()
        } else {
            remember { mutableStateOf(dev.infusion.feature.vertical.collections.CollectionPackManagerUiState()) }
        }

    val collectionRowState: dev.infusion.feature.vertical.collections.CollectionRowSettingsUiState by
        if (collectionRowSettings != null) {
            collectionRowSettings.state.collectAsState()
        } else {
            remember {
                mutableStateOf(dev.infusion.feature.vertical.collections.CollectionRowSettingsUiState())
            }
        }

    val rowManagerBackKeys = collectionRowBackKeys(collectionRowState)

    val addonsState: AddonManagementUiState? by if (addonsManagement != null) {
        addonsManagement.state.collectAsState()
    } else {
        remember { mutableStateOf<AddonManagementUiState?>(null) }
    }
    val addonInRowKeys: Set<String> = addonsState?.let(::addonSectionInRowKeys) ?: emptySet()
    val addonBackKeys: Set<String> = addonsState?.let(::addonSectionBackKeys) ?: emptySet()

    InfusionTvShell(
        page = TopLevelDestination.SETTINGS,
        jellyfinConnected = jellyfinConnected,
        model = resolvedModel,
        requesters = requesters,
        chrome = chrome,

        entryDefaultKey = null,
        onNavigateTo = onNavigate,
        onExit = onExit,
        contentBackHandledInternally = true,

        entryFocusHandledInternally = true,
        onTopShelfTabDown = { tab, key ->

            if (tab == TopLevelDestination.SETTINGS && key == Key.DirectionDown) {
                chrome.requestContentEntry()
                true
            } else {
                false
            }
        },
    ) {

        CompositionLocalProvider(
            LocalDirectionalPressOwnership provides remember { DirectionalPressOwnership() },
        ) {
        ProvideTextStyle(InfusionTvScaleCalibration.Type.SettingsContent) {
            RailContentPage(
                sections = listOf(

                        RailSection(
                            railKey = SettingsRailGeneral,
                            label = "General",
                            content = { entryToken, rememberedKey, onFocusRequested, _ ->
                                GeneralSectionContent(
                                    accent = accent,
                                    onAccentChange = onAccentChange,
                                    topShelfAppearance = topShelfAppearance,
                                    onTopShelfAppearanceChange = onTopShelfAppearanceChange,
                                    splashScreen = splashScreen,
                                    onSplashScreenChange = onSplashScreenChange,
                                    moviesArtwork = moviesArtwork,
                                    onMoviesArtworkChange = onMoviesArtworkChange,
                                    seriesArtwork = seriesArtwork,
                                    onSeriesArtworkChange = onSeriesArtworkChange,
                                    tmdbBackdropQuality = tmdbBackdropQuality,
                                    onTmdbBackdropQualityChange = onTmdbBackdropQualityChange,
                                    tmdbLandscapeCardQuality = tmdbLandscapeCardQuality,
                                    onTmdbLandscapeCardQualityChange =
                                        onTmdbLandscapeCardQualityChange,
                                    hideTitles = hideTitles,
                                    onHideTitlesChange = onHideTitlesChange,
                                    hideEpisodeTitles = hideEpisodeTitles,
                                    onHideEpisodeTitlesChange = onHideEpisodeTitlesChange,
                                    cardBadges = cardBadges,
                                    onCardBadgesChange = onCardBadgesChange,
                                    watchedCheckmark = watchedCheckmark,
                                    onWatchedCheckmarkChange = onWatchedCheckmarkChange,
                                    watchlistBadge = watchlistBadge,
                                    onWatchlistBadgeChange = onWatchlistBadgeChange,
                                    jellyfinAvailabilityBadge = jellyfinAvailabilityBadge,
                                    onJellyfinAvailabilityBadgeChange = onJellyfinAvailabilityBadgeChange,
                                    progressBar = progressBar,
                                    onProgressBarChange = onProgressBarChange,
                                    contextMenuBadge = contextMenuBadge,
                                    onContextMenuBadgeChange = onContextMenuBadgeChange,
                                    hideWatchedTitles = hideWatchedTitles,
                                    onHideWatchedTitlesChange = onHideWatchedTitlesChange,

                                    showProviderName = homeState?.showProviderName
                                        ?: HomePresentationDefaults.SHOW_PROVIDER_NAME,
                                    onShowProviderNameChange = { show ->
                                        homeCustomization?.setShowProviderName(show)
                                    },
                                    requesters = generalRequesters,
                                    entryToken = entryToken,
                                    rememberedKey = rememberedKey,
                                    onFocusRequested = onFocusRequested,
                                )
                            },
                        ),

                        RailSection(
                            railKey = SettingsRailPlayback,
                            label = "Playback",
                            content = { entryToken, rememberedKey, onFocusRequested, _ ->
                                PlaybackSectionContent(
                                    displayMatching = displayMatching,
                                    onDisplayMatchingChange = onDisplayMatchingChange,

                                    dolbyVisionProfile7Fallback = dolbyVisionProfile7Fallback,
                                    onDolbyVisionProfile7FallbackChange = onDolbyVisionProfile7FallbackChange,
                                    audioLanguage = audioLanguage,
                                    onAudioLanguageChange = onAudioLanguageChange,
                                    secondaryAudioLanguage = secondaryAudioLanguage,
                                    onSecondaryAudioLanguageChange = onSecondaryAudioLanguageChange,
                                    subtitlePreferences = subtitlePreferences,
                                    onSubtitleModeChange = onSubtitleModeChange,
                                    onSubtitleLanguageChange = onSubtitleLanguageChange,
                                    onSecondarySubtitleLanguageChange = onSecondarySubtitleLanguageChange,
                                    onSubtitleSameLanguageChange = onSubtitleSameLanguageChange,
                                    onSubtitleTypeChange = onSubtitleTypeChange,

                                    subtitleAppearance = subtitleAppearance,
                                    onSubtitleAppearanceModeChange = onSubtitleAppearanceModeChange,
                                    onSubtitleSizeChange = onSubtitleSizeChange,
                                    onSubtitleFontChange = onSubtitleFontChange,
                                    onSubtitleTextColorChange = onSubtitleTextColorChange,
                                    onSubtitleBackgroundChange = onSubtitleBackgroundChange,
                                    onSubtitleBackgroundOpacityChange = onSubtitleBackgroundOpacityChange,
                                    onSubtitleEdgeChange = onSubtitleEdgeChange,
                                    onSubtitlePositionChange = onSubtitlePositionChange,

                                    dimSubtitlesInHdr = dimSubtitlesInHdr,
                                    onDimSubtitlesInHdrChange = onDimSubtitlesInHdrChange,
                                    movieCompletion = movieCompletion,
                                    onMovieCompletionChange = onMovieCompletionChange,
                                    episodeCompletion = episodeCompletion,
                                    onEpisodeCompletionChange = onEpisodeCompletionChange,
                                    controlsAutoHide = controlsAutoHide,
                                    onControlsAutoHideChange = onControlsAutoHideChange,
                                    relativeSeekStep = relativeSeekStep,
                                    onRelativeSeekStepChange = onRelativeSeekStepChange,
                                    segmentPreferences = segmentPreferences,
                                    onSegmentOnlinePriorityChange = onSegmentOnlinePriorityChange,
                                    onSegmentManualFallbackChange = onSegmentManualFallbackChange,
                                    onSegmentFallbackStartChange = onSegmentFallbackStartChange,
                                    onSegmentFallbackEndChange = onSegmentFallbackEndChange,
                                    onSegmentDisplayChange = onSegmentDisplayChange,
                                    onSegmentLegendChange = onSegmentLegendChange,
                                    onEnableSkipIntroChange = onEnableSkipIntroChange,
                                    onAutoSkipIntroChange = onAutoSkipIntroChange,
                                    onAutoSkipDelayChange = onAutoSkipDelayChange,
                                    onPersistentSkipButtonChange = onPersistentSkipButtonChange,
                                    onEnableStingerAlertsChange = onEnableStingerAlertsChange,
                                    onStingerTriggerPercentChange = onStingerTriggerPercentChange,
                                    onStingerEarlyNotifyChange = onStingerEarlyNotifyChange,
                                    onStingerToastDurationChange = onStingerToastDurationChange,

                                    sourceAutoplayPreferences = sourceAutoplayPreferences,
                                    onSourceAutoplayPolicyChange = onSourceAutoplayPolicyChange,
                                    onPreferJellyfinChange = onPreferJellyfinChange,

                                    nextEpisodePreferences = nextEpisodePreferences,
                                    onEnableNextEpisodeChange = onEnableNextEpisodeChange,
                                    onNextOnlinePriorityChange = onNextOnlinePriorityChange,
                                    onAutoPlayNextChange = onAutoPlayNextChange,
                                    onAutoPlayDelayChange = onAutoPlayDelayChange,
                                    onNextFallbackPercentChange = onNextFallbackPercentChange,
                                    requesters = playbackRequesters,
                                    entryToken = entryToken,
                                    rememberedKey = rememberedKey,
                                    onFocusRequested = onFocusRequested,
                                )
                            },
                        ),

                        RailSection(
                            railKey = SettingsRailHome,
                            label = "Home",
                            content = { entryToken, rememberedKey, onFocusRequested, onReturnToRail ->
                                HomeSectionContent(
                                    holder = homeCustomization,
                                    upNext = upNextSettings,
                                    expandedGroupIds = expandedHomeGroups,
                                    onExpandedGroupChange = { addonId, expanded ->
                                        expandedHomeGroups = if (expanded) {
                                            expandedHomeGroups + addonId
                                        } else {
                                            expandedHomeGroups - addonId
                                        }
                                    },
                                    requesters = homeRequesters,
                                    entryToken = entryToken,
                                    rememberedKey = rememberedKey,
                                    onFocusRequested = onFocusRequested,
                                    onReturnToRail = onReturnToRail,
                                )
                            },
                        ),

                        RailSection(
                            railKey = SettingsRailWidgets,
                            label = "Widgets",
                            content = { entryToken, rememberedKey, onFocusRequested, onReturnToRail ->
                                WidgetBuilderSectionContent(
                                    holder = widgetBuilder,
                                    requesters = widgetsRequesters,
                                    entryToken = entryToken,
                                    rememberedKey = rememberedKey,
                                    onFocusRequested = onFocusRequested,
                                    onReturnToRail = onReturnToRail,

                                    packManagerState = collectionPackState,
                                    onPackUrlChange = { collectionPackManager?.setUrl(it) },
                                    onSubmitPackUrl = { collectionPackManager?.submitUrl() },
                                    onReimportPack = { collectionPackManager?.reimport(it) },
                                    onDeletePack = { collectionPackManager?.deletePack(it) },

                                    rowSettingsState = collectionRowState,
                                    onSelectRowPack = { collectionRowSettings?.selectPack(it) },
                                    onSelectRowGroup = { collectionRowSettings?.selectGroup(it) },
                                    onSelectRowPage = { collectionRowSettings?.selectTile(it) },
                                    onToggleRow = { collectionRowSettings?.toggleRow(it) },
                                    onMoveRow = { rowKey, delta -> collectionRowSettings?.moveRow(rowKey, delta) },

                                    onClearRowPage = { collectionRowSettings?.clearTile() },
                                    onClearRowGroup = { collectionRowSettings?.clearGroup() },
                                    onClearRowPack = { collectionRowSettings?.clearPack() },
                                )
                            },
                        ),
                        RailSection(
                            railKey = SettingsRailProfiles,
                            label = "Profiles",
                            content = { entryToken, rememberedKey, onFocusRequested, onReturnToRail ->
                                ProfilesSectionContent(
                                    profileManagement = profileManagement,
                                    pinEntryFactory = pinEntryFactory,
                                    requesters = profilesRequesters,
                                    entryToken = entryToken,
                                    rememberedKey = rememberedKey,
                                    firstKey = ProfilesFirstKey,
                                    onFocusRequested = onFocusRequested,
                                    onReturnToRail = onReturnToRail,
                                )
                            },
                        ),
                        RailSection(
                            railKey = SettingsRailJellyfin,
                            label = "Jellyfin",
                            content = { entryToken, rememberedKey, onFocusRequested, onReturnToRail ->
                                TvJellyfinSection(
                                    setup = setup,
                                    connection = jellyfinConnection,
                                    connectedUser = jellyfinConnectedUser,
                                    acquisition = acquisition,
                                    discovery = jellyfinDiscovery,
                                    sourceLabel = sourceLabel,
                                    requesters = jellyfinRequesters,
                                    entryToken = entryToken,
                                    rememberedKey = rememberedKey,
                                    onFocusRequested = onFocusRequested,
                                    onReturnToRail = onReturnToRail,
                                )
                            },
                        ),
                        RailSection(
                            railKey = SettingsRailTracking,
                            label = "Tracking",
                            content = { entryToken, rememberedKey, onFocusRequested, onReturnToRail ->
                                TrackingSectionContent(
                                    holders = tracking,
                                    requesters = trackingRequesters,
                                    entryToken = entryToken,
                                    rememberedKey = rememberedKey,
                                    onFocusRequested = onFocusRequested,
                                    onReturnToRail = onReturnToRail,
                                )
                            },
                        ),

                        RailSection(
                            railKey = SettingsRailRatings,
                            label = "Ratings",
                            content = { entryToken, rememberedKey, onFocusRequested, onReturnToRail ->
                                RatingsSectionContent(
                                    holder = ratingsSettings,
                                    requesters = ratingsRequesters,
                                    entryToken = entryToken,
                                    rememberedKey = rememberedKey,
                                    onFocusRequested = onFocusRequested,
                                    onReturnToRail = onReturnToRail,
                                )
                            },
                        ),
                        RailSection(
                            railKey = SettingsRailPairing,
                            label = "Phone setup",
                            content = { entryToken, rememberedKey, onFocusRequested, onReturnToRail ->

                                PhoneSetupSectionContent(
                                    tag = SettingsPairingOpen,
                                    firstKey = PairingFirstKey,
                                    requesters = pairingRequesters,
                                    entryToken = entryToken,
                                    rememberedKey = rememberedKey,
                                    onFocusRequested = onFocusRequested,
                                    onClick = onOpenPairing,
                                )
                            },
                        ),
                        RailSection(
                            railKey = SettingsRailAddons,
                            label = "Add-ons",
                            content = { entryToken, rememberedKey, onFocusRequested, onReturnToRail ->

                                if (addonsManagement == null) {
                                    Text(
                                        "Add-on management unavailable.",
                                        style = InfusionTvScaleCalibration.Type.SettingsContent,
                                        modifier = Modifier.padding(24.dp),
                                    )
                                } else {
                                    AddonsSectionContent(
                                        stateHolder = addonsManagement,
                                        requesters = addonsRequesters,
                                        entryToken = entryToken,
                                        rememberedKey = rememberedKey,
                                        onFocusRequested = onFocusRequested,

                                        onReturnToRail = onReturnToRail,
                                    )
                                }
                            },
                        ),
                        RailSection(
                            railKey = SettingsRailDiagnostics,
                            label = "Diagnostics",
                            content = { entryToken, rememberedKey, onFocusRequested, onReturnToRail ->
                                SectionActionButton(
                                    label = "Open diagnostics",
                                    tag = SettingsDiagnosticsOpen,
                                    firstKey = DiagnosticsFirstKey,
                                    requesters = diagnosticsRequesters,
                                    entryToken = entryToken,
                                    rememberedKey = rememberedKey,
                                    onFocusRequested = onFocusRequested,
                                    onReturnToRail = onReturnToRail,
                                    onClick = onOpenDiagnostics,
                                )
                            },
                        ),

                        RailSection(
                            railKey = SettingsRailAbout,
                            label = "About",
                            content = { entryToken, rememberedKey, onFocusRequested, _ ->
                                AboutSectionContent(
                                    requesters = aboutRequesters,
                                    entryToken = entryToken,
                                    rememberedKey = rememberedKey,
                                    onFocusRequested = onFocusRequested,
                                )
                            },
                        ),
                    ),
                    selectedKey = selectedSection,
                    onSelectedKeyChange = onSelectedSectionChange,
                    onBackToTopShelf = {

                        chrome.onShelfRevealedByBack()
                        requesters[TopLevelDestination.SETTINGS.tabKey()]?.requestFocus()
                    },
                    topShelfFocused = chrome.barFocused,
                    focusRailOnEntry = true,

                    railEntryToken = chrome.entryToken,

                    onZoneFocusChanged = { chrome.onContentKeyFocused(it) },

                    editorKeys = setOf(
                        JellyfinFocusUrl,
                        JellyfinFocusUsername,
                        JellyfinFocusPassword,

                        SettingsWidgetsQueryRow,

                        SettingsRatingsOmdbField,

                        AddonInstallUrlKey,
                    ),

                    inRowKeys = homeInRowKeys + widgetRowInRowKeys(widgetPages) +

                        collectionRowInRowKeys(collectionRowState.rows) +

                        ratingsSupplierInRowKeys() +

                        ratingsEpisodeInRowKeys() +

                        addonInRowKeys,

                    contentBackKeys = rowManagerBackKeys + addonBackKeys,
                )
        }

        }
    }
}

@Composable
internal fun RestoreSectionFocus(
    entryToken: Int,
    rememberedKey: String?,
    firstKey: String,
    requesters: MutableMap<String, FocusRequester>,
) {
    val registry = requesters as? FocusKeyRegistry
    LaunchedEffect(entryToken) {
        if (entryToken <= 0) return@LaunchedEffect
        if (registry != null) {

            registry.requestFirst(rememberedKey, firstKey)
            return@LaunchedEffect
        }
        val target = rememberedKey?.let { requesters[it] } ?: requesters[firstKey]
        target?.requestFocus()
    }
}

@Composable
private fun GeneralSectionContent(
    accent: AccentColor,
    onAccentChange: (AccentColor) -> Unit,
    topShelfAppearance: TopShelfAppearance,
    onTopShelfAppearanceChange: (TopShelfAppearance) -> Unit,
    splashScreen: SplashScreenMode,
    onSplashScreenChange: (SplashScreenMode) -> Unit,
    moviesArtwork: ArtworkStyle,
    onMoviesArtworkChange: (ArtworkStyle) -> Unit,
    seriesArtwork: ArtworkStyle,
    onSeriesArtworkChange: (ArtworkStyle) -> Unit,
    tmdbBackdropQuality: TmdbBackdropQuality,
    onTmdbBackdropQualityChange: (TmdbBackdropQuality) -> Unit,
    tmdbLandscapeCardQuality: TmdbLandscapeCardQuality,
    onTmdbLandscapeCardQualityChange: (TmdbLandscapeCardQuality) -> Unit,
    hideTitles: Boolean,
    onHideTitlesChange: (Boolean) -> Unit,
    hideEpisodeTitles: Boolean,
    onHideEpisodeTitlesChange: (Boolean) -> Unit,
    cardBadges: Boolean,
    onCardBadgesChange: (Boolean) -> Unit,
    watchedCheckmark: Boolean,
    onWatchedCheckmarkChange: (Boolean) -> Unit,
    watchlistBadge: Boolean,
    onWatchlistBadgeChange: (Boolean) -> Unit,
    jellyfinAvailabilityBadge: Boolean,
    onJellyfinAvailabilityBadgeChange: (Boolean) -> Unit,
    progressBar: Boolean,
    onProgressBarChange: (Boolean) -> Unit,

    contextMenuBadge: Boolean,
    onContextMenuBadgeChange: (Boolean) -> Unit,
    hideWatchedTitles: Boolean,
    onHideWatchedTitlesChange: (Boolean) -> Unit,

    showProviderName: Boolean,
    onShowProviderNameChange: (Boolean) -> Unit,
    requesters: MutableMap<String, FocusRequester>,
    entryToken: Int,
    rememberedKey: String?,
    onFocusRequested: (String) -> Unit,
) {

    val rowKeys = generalRowKeysFor(cardBadges)

    RestoreSectionFocus(
        entryToken = entryToken,
        rememberedKey = rememberedKey?.takeIf { it in rowKeys },
        firstKey = SettingsGeneralAccentRow,
        requesters = requesters,
    )

    var accentSelectorOpen by remember { mutableStateOf(false) }

    var focusedRowKey by remember { mutableStateOf(SettingsGeneralAccentRow) }

    var selectorWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(accentSelectorOpen) {
        val wasOpen = selectorWasOpen
        selectorWasOpen = accentSelectorOpen
        if (wasOpen && !accentSelectorOpen) {
            repeat(30) {
                withFrameNanos { }
                if (requesters[SettingsGeneralAccentRow]?.requestFocus() == true) {
                    return@LaunchedEffect
                }
            }
        }
    }

    var splashSelectorOpen by remember { mutableStateOf(false) }
    var splashSelectorWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(splashSelectorOpen) {
        val wasOpen = splashSelectorWasOpen
        splashSelectorWasOpen = splashSelectorOpen
        if (wasOpen && !splashSelectorOpen) {
            repeat(30) {
                withFrameNanos { }
                if (requesters[SettingsGeneralSplashScreenRow]?.requestFocus() == true) {
                    return@LaunchedEffect
                }
            }
        }
    }

    var backdropQualitySelectorOpen by remember { mutableStateOf(false) }
    var backdropQualitySelectorWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(backdropQualitySelectorOpen) {
        val wasOpen = backdropQualitySelectorWasOpen
        backdropQualitySelectorWasOpen = backdropQualitySelectorOpen
        if (wasOpen && !backdropQualitySelectorOpen) {
            repeat(30) {
                withFrameNanos { }
                if (requesters[SettingsGeneralTmdbBackdropQualityRow]?.requestFocus() == true) {
                    return@LaunchedEffect
                }
            }
        }
    }

    var cardQualitySelectorOpen by remember { mutableStateOf(false) }
    var cardQualitySelectorWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(cardQualitySelectorOpen) {
        val wasOpen = cardQualitySelectorWasOpen
        cardQualitySelectorWasOpen = cardQualitySelectorOpen
        if (wasOpen && !cardQualitySelectorOpen) {
            repeat(30) {
                withFrameNanos { }
                if (requesters[SettingsGeneralTmdbLandscapeCardQualityRow]?.requestFocus() == true) {
                    return@LaunchedEffect
                }
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag(SettingsGeneralPane)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val delta = when (event.key) {
                    Key.DirectionUp -> -1
                    Key.DirectionDown -> 1
                    else -> return@onPreviewKeyEvent false
                }

                val index = rowKeys.indexOf(focusedRowKey).coerceAtLeast(0)
                val target = rowKeys[(index + delta).coerceIn(0, rowKeys.lastIndex)]
                requesters[target]?.requestFocus()
                true
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GeneralSettingRow(
            label = "Accent color",
            value = accentDisplayName(accent),
            tag = SettingsGeneralAccentRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.General.Accent,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onOpen = { accentSelectorOpen = true },
        )

        GeneralToggleRow(
            label = "Top Shelf Appearance",
            value = topShelfAppearance.displayName,
            tag = SettingsGeneralTopShelfAppearanceRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.General.TopShelfAppearance,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onToggle = { onTopShelfAppearanceChange(topShelfAppearance.next()) },
        )

        GeneralSettingRow(
            label = "Splash screen",
            value = splashScreen.displayName,
            tag = SettingsGeneralSplashScreenRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.General.SplashScreen,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onOpen = { splashSelectorOpen = true },
        )

        GeneralToggleRow(
            label = "Movies Artwork",
            value = artworkStyleDisplayName(moviesArtwork),
            tag = SettingsGeneralMoviesArtworkRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.General.MoviesArtwork,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onToggle = { onMoviesArtworkChange(moviesArtwork.toggled()) },
        )

        GeneralToggleRow(
            label = "Series Artwork",
            value = artworkStyleDisplayName(seriesArtwork),
            tag = SettingsGeneralSeriesArtworkRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.General.SeriesArtwork,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onToggle = { onSeriesArtworkChange(seriesArtwork.toggled()) },
        )

        GeneralSettingRow(
            label = "TMDB backdrop quality",
            value = tmdbBackdropQuality.displayName,
            tag = SettingsGeneralTmdbBackdropQualityRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.General.TmdbBackdropQuality,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onOpen = { backdropQualitySelectorOpen = true },
        )

        GeneralSettingRow(
            label = "TMDB landscape card quality",
            value = tmdbLandscapeCardQuality.displayName,
            tag = SettingsGeneralTmdbLandscapeCardQualityRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.General.TmdbLandscapeCardQuality,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onOpen = { cardQualitySelectorOpen = true },
        )

        GeneralToggleRow(
            label = "Hide Movies & Shows Titles",
            value = if (hideTitles) "On" else "Off",
            tag = SettingsGeneralHideTitlesRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.General.HideTitles,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onToggle = { onHideTitlesChange(!hideTitles) },
        )

        GeneralToggleRow(
            label = "Hide Episodes Title",
            value = if (hideEpisodeTitles) "On" else "Off",
            tag = SettingsGeneralHideEpisodeTitlesRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.General.HideEpisodeTitles,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onToggle = { onHideEpisodeTitlesChange(!hideEpisodeTitles) },
        )

        GeneralToggleRow(
            label = "Hide watched titles",
            value = if (hideWatchedTitles) "On" else "Off",
            tag = SettingsGeneralHideWatchedTitlesRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.General.HideWatchedTitles,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onToggle = { onHideWatchedTitlesChange(!hideWatchedTitles) },
        )

        GeneralToggleRow(
            label = "Show Provider Name",
            value = if (showProviderName) "On" else "Off",
            tag = SettingsGeneralShowProviderNameRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.General.ShowProviderName,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onToggle = { onShowProviderNameChange(!showProviderName) },
        )

        GeneralToggleRow(
            label = "Card badges",
            value = if (cardBadges) "On" else "Off",
            tag = SettingsGeneralCardBadgesRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.General.CardBadges,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onToggle = { onCardBadgesChange(!cardBadges) },
        )

        if (cardBadges) {
            GeneralToggleRow(
                label = "Watched",
                value = if (watchedCheckmark) "On" else "Off",
                tag = SettingsGeneralWatchedRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.General.Watched,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onToggle = { onWatchedCheckmarkChange(!watchedCheckmark) },
                labelIndent = GeneralChildRowLabelIndent,
            )

            GeneralToggleRow(
                label = "Watchlist",
                value = if (watchlistBadge) "On" else "Off",
                tag = SettingsGeneralWatchlistBadgeRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.General.Watchlist,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onToggle = { onWatchlistBadgeChange(!watchlistBadge) },
                labelIndent = GeneralChildRowLabelIndent,
            )
            GeneralToggleRow(
                label = "Jellyfin availability",
                value = if (jellyfinAvailabilityBadge) "On" else "Off",
                tag = SettingsGeneralJellyfinAvailabilityRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.General.JellyfinAvailability,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onToggle = { onJellyfinAvailabilityBadgeChange(!jellyfinAvailabilityBadge) },
                labelIndent = GeneralChildRowLabelIndent,
            )
            GeneralToggleRow(
                label = "Progress",
                value = if (progressBar) "On" else "Off",
                tag = SettingsGeneralProgressBarRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.General.Progress,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onToggle = { onProgressBarChange(!progressBar) },
                labelIndent = GeneralChildRowLabelIndent,
            )

            GeneralToggleRow(
                label = "Context menu",
                value = if (contextMenuBadge) "On" else "Off",
                tag = SettingsGeneralContextMenuBadgeRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.General.ContextMenu,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onToggle = { onContextMenuBadgeChange(!contextMenuBadge) },
                labelIndent = GeneralChildRowLabelIndent,
            )
        }
    }
    if (accentSelectorOpen) {
        GeneralSelectorDialog(
            rowTag = SettingsGeneralAccentRow,
            choices = AccentColor.entries,
            current = accent,
            choiceKey = { it.storageKey },
            choiceLabel = { accentDisplayName(it) },
            leading = { choice ->
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(
                            color = InfusionTvAppearance.accentColor(choice),
                            shape = RoundedCornerShape(3.dp),
                        ),
                )
                Spacer(Modifier.width(12.dp))
            },
            onSelect = { chosen ->
                onAccentChange(chosen)
                accentSelectorOpen = false
            },
            onDismiss = { accentSelectorOpen = false },
        )
    }

    if (splashSelectorOpen) {
        GeneralSelectorDialog(
            rowTag = SettingsGeneralSplashScreenRow,
            choices = SplashScreenMode.entries,
            current = splashScreen,
            choiceKey = { it.storageKey },
            choiceLabel = { it.displayName },
            choiceSupportingText = { it.supportingText },
            onSelect = { chosen ->
                onSplashScreenChange(chosen)
                splashSelectorOpen = false
            },
            onDismiss = { splashSelectorOpen = false },
        )
    }

    if (backdropQualitySelectorOpen) {
        GeneralSelectorDialog(
            rowTag = SettingsGeneralTmdbBackdropQualityRow,
            choices = TmdbBackdropQuality.entries,
            current = tmdbBackdropQuality,
            choiceKey = { it.storageKey },
            choiceLabel = { it.displayName },
            choiceSupportingText = { it.supportingText },
            onSelect = { chosen ->
                onTmdbBackdropQualityChange(chosen)
                backdropQualitySelectorOpen = false
            },
            onDismiss = { backdropQualitySelectorOpen = false },
        )
    }

    if (cardQualitySelectorOpen) {
        GeneralSelectorDialog(
            rowTag = SettingsGeneralTmdbLandscapeCardQualityRow,
            choices = TmdbLandscapeCardQuality.entries,
            current = tmdbLandscapeCardQuality,
            choiceKey = { it.storageKey },
            choiceLabel = { it.displayName },
            choiceSupportingText = { it.supportingText },
            onSelect = { chosen ->
                onTmdbLandscapeCardQualityChange(chosen)
                cardQualitySelectorOpen = false
            },
            onDismiss = { cardQualitySelectorOpen = false },
        )
    }
}

internal fun ArtworkStyle.toggled(): ArtworkStyle = when (this) {
    ArtworkStyle.POSTER -> ArtworkStyle.LANDSCAPE
    ArtworkStyle.LANDSCAPE -> ArtworkStyle.POSTER
}

@Composable
private fun PlaybackSectionContent(
    displayMatching: DisplayMatchingPolicy,
    onDisplayMatchingChange: (DisplayMatchingPolicy) -> Unit,
    dolbyVisionProfile7Fallback: DolbyVisionProfile7Fallback,
    onDolbyVisionProfile7FallbackChange: (DolbyVisionProfile7Fallback) -> Unit,
    audioLanguage: AudioLanguagePreference,
    onAudioLanguageChange: (AudioLanguagePreference) -> Unit,
    secondaryAudioLanguage: AudioLanguagePreference,
    onSecondaryAudioLanguageChange: (AudioLanguagePreference) -> Unit,
    subtitlePreferences: SubtitlePreferences,
    onSubtitleModeChange: (SubtitleModePreference) -> Unit,
    onSubtitleLanguageChange: (SubtitleLanguagePreference) -> Unit,
    onSecondarySubtitleLanguageChange: (SubtitleLanguagePreference) -> Unit,
    onSubtitleSameLanguageChange: (SubtitleSameLanguagePolicy) -> Unit,
    onSubtitleTypeChange: (SubtitleTypePreference) -> Unit,
    subtitleAppearance: SubtitleAppearance,
    onSubtitleAppearanceModeChange: (SubtitleAppearanceMode) -> Unit,
    onSubtitleSizeChange: (SubtitleSizeChoice) -> Unit,
    onSubtitleFontChange: (SubtitleFontChoice) -> Unit,
    onSubtitleTextColorChange: (SubtitleTextColorChoice) -> Unit,
    onSubtitleBackgroundChange: (SubtitleBackgroundChoice) -> Unit,
    onSubtitleBackgroundOpacityChange: (SubtitleBackgroundOpacityChoice) -> Unit,
    onSubtitleEdgeChange: (SubtitleEdgeChoice) -> Unit,
    onSubtitlePositionChange: (SubtitlePositionChoice) -> Unit,
    dimSubtitlesInHdr: HdrSubtitleDimming,
    onDimSubtitlesInHdrChange: (HdrSubtitleDimming) -> Unit,
    movieCompletion: CompletionThreshold,
    onMovieCompletionChange: (CompletionThreshold) -> Unit,
    episodeCompletion: CompletionThreshold,
    onEpisodeCompletionChange: (CompletionThreshold) -> Unit,
    controlsAutoHide: PlaybackControlsAutoHide,
    onControlsAutoHideChange: (PlaybackControlsAutoHide) -> Unit,
    relativeSeekStep: dev.infusion.core.playback.api.RelativeSeekStep,
    onRelativeSeekStepChange: (dev.infusion.core.playback.api.RelativeSeekStep) -> Unit,
    segmentPreferences: SegmentEvidencePreferences,
    onSegmentOnlinePriorityChange: (Boolean) -> Unit,
    onSegmentManualFallbackChange: (Boolean) -> Unit,
    onSegmentFallbackStartChange: (SegmentFallbackTimecode) -> Unit,
    onSegmentFallbackEndChange: (SegmentFallbackTimecode) -> Unit,
    onSegmentDisplayChange: (SegmentDisplayMode) -> Unit,
    onSegmentLegendChange: (Boolean) -> Unit,

    onEnableSkipIntroChange: (Boolean) -> Unit,
    onAutoSkipIntroChange: (Boolean) -> Unit,
    onAutoSkipDelayChange: (Int) -> Unit,
    onPersistentSkipButtonChange: (Boolean) -> Unit,
    onEnableStingerAlertsChange: (Boolean) -> Unit,
    onStingerTriggerPercentChange: (Int) -> Unit,
    onStingerEarlyNotifyChange: (Int) -> Unit,
    onStingerToastDurationChange: (Int) -> Unit,

    sourceAutoplayPreferences: SourceAutoplayPreferences,
    onSourceAutoplayPolicyChange: (SourceAutoplayPolicy) -> Unit,
    onPreferJellyfinChange: (Boolean) -> Unit,

    nextEpisodePreferences: NextEpisodePreferences,
    onEnableNextEpisodeChange: (Boolean) -> Unit,
    onNextOnlinePriorityChange: (Boolean) -> Unit,
    onAutoPlayNextChange: (Boolean) -> Unit,
    onAutoPlayDelayChange: (Int) -> Unit,
    onNextFallbackPercentChange: (Int) -> Unit,
    requesters: MutableMap<String, FocusRequester>,
    entryToken: Int,
    rememberedKey: String?,
    onFocusRequested: (String) -> Unit,
) {
    RestoreSectionFocus(entryToken, rememberedKey, SettingsPlaybackDisplayMatchingRow, requesters)
    var openSelectorFor by remember { mutableStateOf<String?>(null) }
    var selectorReturnKey by remember { mutableStateOf<String?>(null) }
    var focusedRowKey by remember { mutableStateOf(SettingsPlaybackDisplayMatchingRow) }
    var selectorWasOpen by remember { mutableStateOf(false) }

    val rowKeys = playbackRowKeysFor(
        mode = subtitleAppearance.mode,
        manualFallbackEnabled = segmentPreferences.manualFallbackEnabled,
        autoSkipIntro = segmentPreferences.autoSkipIntro,
        stingerAlerts = segmentPreferences.enableStingerAlerts,

        sourceAutoplayEnabled = sourceAutoplayPreferences.policy.enabled,

        enableNextEpisode = nextEpisodePreferences.enableNextEpisode,
        autoPlayNextEpisode = nextEpisodePreferences.autoPlayNextEpisode,
    )

    LaunchedEffect(openSelectorFor) {
        val wasOpen = selectorWasOpen
        selectorWasOpen = openSelectorFor != null
        if (wasOpen && openSelectorFor == null) {
            val target = selectorReturnKey ?: rowKeys.first()
            repeat(30) {
                withFrameNanos { }
                if (requesters[target]?.requestFocus() == true) {
                    return@LaunchedEffect
                }
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag(SettingsPlaybackPane)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val delta = when (event.key) {
                    Key.DirectionUp -> -1
                    Key.DirectionDown -> 1
                    else -> return@onPreviewKeyEvent false
                }

                val index = rowKeys.indexOf(focusedRowKey).coerceAtLeast(0)
                val target = rowKeys[(index + delta).coerceIn(0, rowKeys.lastIndex)]
                requesters[target]?.requestFocus()
                true
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        if (PlaybackDisplayGroup.rowKeys.any { it in rowKeys }) {
            Text(PlaybackDisplayGroup.heading, style = InfusionTvScaleCalibration.Type.Headline)
        }
        GeneralSettingRow(
            label = "Display matching",
            value = displayMatchingChoiceLabel(displayMatching),
            tag = SettingsPlaybackDisplayMatchingRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.DisplayMatching,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onOpen = { openSelectorFor = SettingsPlaybackDisplayMatchingRow },
        )

        GeneralSettingRow(
            label = "DV Profile 7 fallback to HDR",
            value = dolbyVisionProfile7FallbackChoiceLabel(dolbyVisionProfile7Fallback),
            tag = SettingsPlaybackDolbyVisionProfile7FallbackRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.DolbyVisionProfile7Fallback,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onOpen = { openSelectorFor = SettingsPlaybackDolbyVisionProfile7FallbackRow },
        )

        if (PlaybackAudioGroup.rowKeys.any { it in rowKeys }) {
            Text(PlaybackAudioGroup.heading, style = InfusionTvScaleCalibration.Type.Headline)
        }
        GeneralSettingRow(
            label = "Audio language",
            value = audioLanguageChoiceLabel(audioLanguage),
            tag = SettingsPlaybackAudioLanguageRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.AudioLanguage,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onOpen = { openSelectorFor = SettingsPlaybackAudioLanguageRow },
        )
        GeneralSettingRow(
            label = "Second audio language",
            value = audioLanguageChoiceLabel(secondaryAudioLanguage),
            tag = SettingsPlaybackAudioLanguageSecondaryRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.SecondaryAudioLanguage,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onOpen = { openSelectorFor = SettingsPlaybackAudioLanguageSecondaryRow },
        )

        if (PlaybackSubtitlesGroup.rowKeys.any { it in rowKeys }) {
            Text(PlaybackSubtitlesGroup.heading, style = InfusionTvScaleCalibration.Type.Headline)
        }
        GeneralSettingRow(
            label = "Subtitles",
            value = subtitleModeChoiceLabel(subtitlePreferences.mode),
            tag = SettingsPlaybackSubtitlesRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.Subtitles,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onOpen = { openSelectorFor = SettingsPlaybackSubtitlesRow },
        )
        GeneralSettingRow(
            label = "Subtitle language",
            value = subtitleLanguageChoiceLabel(subtitlePreferences.primary),
            tag = SettingsPlaybackSubtitleLanguageRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.SubtitleLanguage,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onOpen = { openSelectorFor = SettingsPlaybackSubtitleLanguageRow },
        )
        GeneralSettingRow(
            label = "Second subtitle language",
            value = subtitleLanguageChoiceLabel(subtitlePreferences.secondary),
            tag = SettingsPlaybackSubtitleLanguageSecondaryRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.SecondarySubtitleLanguage,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onOpen = { openSelectorFor = SettingsPlaybackSubtitleLanguageSecondaryRow },
        )
        GeneralSettingRow(
            label = "Subtitles in the audio language",
            value = subtitleSameLanguageChoiceLabel(subtitlePreferences.sameLanguage),
            tag = SettingsPlaybackSubtitleSameLanguageRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.SubtitlesInAudioLanguage,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onOpen = { openSelectorFor = SettingsPlaybackSubtitleSameLanguageRow },
        )
        GeneralSettingRow(
            label = "Subtitle type",
            value = subtitleTypeChoiceLabel(subtitlePreferences.type),
            tag = SettingsPlaybackSubtitleTypeRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.SubtitleType,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onOpen = { openSelectorFor = SettingsPlaybackSubtitleTypeRow },
        )

        GeneralToggleRow(
            label = "Dim subtitles in HDR",
            value = hdrSubtitleDimmingChoiceLabel(dimSubtitlesInHdr),
            tag = SettingsPlaybackDimSubtitlesInHdrRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.DimSubtitlesInHdr,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onToggle = {
                onDimSubtitlesInHdrChange(
                    if (dimSubtitlesInHdr == HdrSubtitleDimming.ON) {
                        HdrSubtitleDimming.OFF
                    } else {
                        HdrSubtitleDimming.ON
                    },
                )
            },
        )

        GeneralSettingRow(
            label = "Subtitle appearance",
            value = subtitleAppearanceModeChoiceLabel(subtitleAppearance.mode),
            tag = SettingsPlaybackSubtitleAppearanceRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.SubtitleAppearance,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onOpen = { openSelectorFor = SettingsPlaybackSubtitleAppearanceRow },
        )

        if (subtitleAppearance.isCustom) {
            GeneralSettingRow(
                label = "Subtitle size",
                value = subtitleSizeChoiceLabel(subtitleAppearance.size),
                tag = SettingsPlaybackSubtitleSizeRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.Playback.SubtitleSize,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onOpen = { openSelectorFor = SettingsPlaybackSubtitleSizeRow },
            )
            GeneralSettingRow(
                label = "Subtitle font",
                value = subtitleFontChoiceLabel(subtitleAppearance.font),
                tag = SettingsPlaybackSubtitleFontRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.Playback.SubtitleFont,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onOpen = { openSelectorFor = SettingsPlaybackSubtitleFontRow },
            )
            GeneralSettingRow(
                label = "Subtitle text color",
                value = subtitleTextColorChoiceLabel(subtitleAppearance.textColor),
                tag = SettingsPlaybackSubtitleTextColorRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.Playback.SubtitleTextColor,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onOpen = { openSelectorFor = SettingsPlaybackSubtitleTextColorRow },
            )
            GeneralSettingRow(
                label = "Subtitle background",
                value = subtitleBackgroundChoiceLabel(subtitleAppearance.background),
                tag = SettingsPlaybackSubtitleBackgroundRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.Playback.SubtitleBackground,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onOpen = { openSelectorFor = SettingsPlaybackSubtitleBackgroundRow },
            )
            GeneralSettingRow(
                label = "Subtitle background opacity",
                value = subtitleBackgroundOpacityChoiceLabel(subtitleAppearance.backgroundOpacity),
                tag = SettingsPlaybackSubtitleBackgroundOpacityRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.Playback.SubtitleBackgroundOpacity,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onOpen = { openSelectorFor = SettingsPlaybackSubtitleBackgroundOpacityRow },
            )
            GeneralSettingRow(
                label = "Subtitle edge",
                value = subtitleEdgeChoiceLabel(subtitleAppearance.edge),
                tag = SettingsPlaybackSubtitleEdgeRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.Playback.SubtitleEdge,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onOpen = { openSelectorFor = SettingsPlaybackSubtitleEdgeRow },
            )
            GeneralSettingRow(
                label = "Subtitle position",
                value = subtitlePositionChoiceLabel(subtitleAppearance.position),
                tag = SettingsPlaybackSubtitlePositionRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.Playback.SubtitlePosition,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onOpen = { openSelectorFor = SettingsPlaybackSubtitlePositionRow },
            )
        }

        if (PlaybackControlsGroup.rowKeys.any { it in rowKeys }) {
            Text(PlaybackControlsGroup.heading, style = InfusionTvScaleCalibration.Type.Headline)
        }
        GeneralSettingRow(
            label = "Playback controls auto-hide",
            value = playbackControlsAutoHideChoiceLabel(controlsAutoHide),
            tag = SettingsPlaybackControlsAutoHideRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.ControlsAutoHide,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onOpen = { openSelectorFor = SettingsPlaybackControlsAutoHideRow },
        )

        GeneralSettingRow(
            label = "Rewind/Fast Forward skip time",
            value = relativeSeekStepChoiceLabel(relativeSeekStep),
            tag = SettingsPlaybackRelativeSeekStepRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.RelativeSeekStep,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onOpen = { openSelectorFor = SettingsPlaybackRelativeSeekStepRow },
        )

        GeneralSettingRow(
            label = "Mark movies watched at",
            value = completionThresholdChoiceLabel(movieCompletion),
            tag = SettingsPlaybackMovieCompletionRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.MovieCompletion,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onOpen = { openSelectorFor = SettingsPlaybackMovieCompletionRow },
        )
        GeneralSettingRow(
            label = "Mark episodes watched at",
            value = completionThresholdChoiceLabel(episodeCompletion),
            tag = SettingsPlaybackEpisodeCompletionRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.EpisodeCompletion,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onOpen = { openSelectorFor = SettingsPlaybackEpisodeCompletionRow },
        )

        GeneralSettingRow(
            label = "Segment display",
            value = segmentDisplayChoiceLabel(segmentPreferences.displayMode),
            tag = SettingsPlaybackSegmentDisplayRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.SegmentDisplay,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onOpen = { openSelectorFor = SettingsPlaybackSegmentDisplayRow },
        )
        GeneralToggleRow(
            label = "Segment legend",
            value = segmentLegendChoiceLabel(segmentPreferences.legendEnabled),
            tag = SettingsPlaybackSegmentLegendRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.SegmentLegend,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onToggle = { onSegmentLegendChange(!segmentPreferences.legendEnabled) },
        )

        GeneralSettingRow(
            label = "Source autoplay",
            value = sourceAutoplayChoiceLabel(sourceAutoplayPreferences.policy),
            tag = SettingsPlaybackSourceAutoplayRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.SourceAutoplay,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onOpen = { openSelectorFor = SettingsPlaybackSourceAutoplayRow },
        )
        if (sourceAutoplayPreferences.policy.enabled) {
            GeneralToggleRow(
                label = "Prefer Jellyfin",
                value = segmentToggleChoiceLabel(sourceAutoplayPreferences.preferJellyfin),
                tag = SettingsPlaybackPreferJellyfinRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.Playback.PreferJellyfin,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onToggle = { onPreferJellyfinChange(!sourceAutoplayPreferences.preferJellyfin) },
                labelIndent = GeneralChildRowLabelIndent,
            )
        }

        if (PlaybackIntroGroup.rowKeys.any { it in rowKeys }) {
            Text(PlaybackIntroGroup.heading, style = InfusionTvScaleCalibration.Type.Headline)
        }
        GeneralToggleRow(
            label = "Intro metadata priority",
            value = segmentOnlinePriorityChoiceLabel(segmentPreferences.onlineIntroMetadataPriority),
            tag = SettingsPlaybackSegmentOnlinePriorityRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.IntroMetadataPriority,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onToggle = {
                onSegmentOnlinePriorityChange(!segmentPreferences.onlineIntroMetadataPriority)
            },
        )
        GeneralToggleRow(
            label = "Fallback intro window",
            value = segmentManualFallbackChoiceLabel(segmentPreferences.manualFallbackEnabled),
            tag = SettingsPlaybackSegmentFallbackRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.FallbackIntroWindow,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onToggle = { onSegmentManualFallbackChange(!segmentPreferences.manualFallbackEnabled) },
        )

        if (segmentPreferences.manualFallbackEnabled) {
            GeneralSettingRow(
                label = "Fallback intro start",
                value = segmentFallbackTimecodeChoiceLabel(segmentPreferences.fallbackStart),
                tag = SettingsPlaybackSegmentFallbackStartRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.Playback.FallbackIntroStart,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onOpen = { openSelectorFor = SettingsPlaybackSegmentFallbackStartRow },
                labelIndent = GeneralChildRowLabelIndent,
            )
            GeneralSettingRow(
                label = "Fallback intro end",
                value = segmentFallbackTimecodeChoiceLabel(segmentPreferences.fallbackEnd),
                tag = SettingsPlaybackSegmentFallbackEndRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.Playback.FallbackIntroEnd,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onOpen = { openSelectorFor = SettingsPlaybackSegmentFallbackEndRow },
                labelIndent = GeneralChildRowLabelIndent,
            )
        }

        GeneralToggleRow(
            label = "Enable Skip Intro",
            value = segmentToggleChoiceLabel(segmentPreferences.enableSkipIntro),
            tag = SettingsPlaybackEnableSkipIntroRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.EnableSkipIntro,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onToggle = { onEnableSkipIntroChange(!segmentPreferences.enableSkipIntro) },
        )
        GeneralToggleRow(
            label = "Auto-skip Intro",
            value = segmentToggleChoiceLabel(segmentPreferences.autoSkipIntro),
            tag = SettingsPlaybackAutoSkipIntroRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.AutoSkipIntro,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onToggle = { onAutoSkipIntroChange(!segmentPreferences.autoSkipIntro) },
        )
        if (segmentPreferences.autoSkipIntro) {
            GeneralSettingRow(
                label = "Auto-skip Delay",
                value = autoSkipDelayChoiceLabel(segmentPreferences.autoSkipDelaySeconds),
                tag = SettingsPlaybackAutoSkipDelayRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.Playback.AutoSkipDelay,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onOpen = { openSelectorFor = SettingsPlaybackAutoSkipDelayRow },
                labelIndent = GeneralChildRowLabelIndent,
            )
        }
        GeneralToggleRow(
            label = "Persistent Button",
            value = segmentToggleChoiceLabel(segmentPreferences.persistentSkipButton),
            tag = SettingsPlaybackPersistentButtonRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.PersistentButton,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onToggle = { onPersistentSkipButtonChange(!segmentPreferences.persistentSkipButton) },
        )
        if (PlaybackNextEpisodeGroup.rowKeys.any { it in rowKeys }) {
            Text(PlaybackNextEpisodeGroup.heading, style = InfusionTvScaleCalibration.Type.Headline)
        }

        GeneralToggleRow(
            label = "Enable Next Episode",
            value = segmentToggleChoiceLabel(nextEpisodePreferences.enableNextEpisode),
            tag = SettingsPlaybackEnableNextEpisodeRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.EnableNextEpisode,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onToggle = { onEnableNextEpisodeChange(!nextEpisodePreferences.enableNextEpisode) },
        )
        if (nextEpisodePreferences.enableNextEpisode) {
            GeneralToggleRow(
                label = "Next — Online metadata priority",
                value = segmentToggleChoiceLabel(nextEpisodePreferences.onlineNextMetadataPriority),
                tag = SettingsPlaybackNextOnlinePriorityRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.Playback.NextOnlinePriority,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onToggle = {
                    onNextOnlinePriorityChange(!nextEpisodePreferences.onlineNextMetadataPriority)
                },
                labelIndent = GeneralChildRowLabelIndent,
            )
            GeneralSettingRow(
                label = "Fallback Trigger Percent",
                value = nextFallbackPercentChoiceLabel(nextEpisodePreferences.fallbackTriggerPercent),
                tag = SettingsPlaybackNextFallbackPercentRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.Playback.NextFallbackPercent,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onOpen = { openSelectorFor = SettingsPlaybackNextFallbackPercentRow },
                labelIndent = GeneralChildRowLabelIndent,
            )
            GeneralToggleRow(
                label = "Automatically play next episode",
                value = segmentToggleChoiceLabel(nextEpisodePreferences.autoPlayNextEpisode),
                tag = SettingsPlaybackAutoPlayNextRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.Playback.AutoPlayNext,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onToggle = { onAutoPlayNextChange(!nextEpisodePreferences.autoPlayNextEpisode) },
                labelIndent = GeneralChildRowLabelIndent,
            )
            if (nextEpisodePreferences.autoPlayNextEpisode) {
                GeneralSettingRow(
                    label = "Auto-play Delay",
                    value = autoPlayDelayChoiceLabel(nextEpisodePreferences.autoPlayDelaySeconds),
                    tag = SettingsPlaybackAutoPlayDelayRow,
                    presentation = SettingsRowPresentation.EXPANDED,
                    help = SettingsRowHelp.Playback.AutoPlayDelay,
                    requesters = requesters,
                    onFocused = { key ->
                        focusedRowKey = key
                        onFocusRequested(key)
                    },
                    onOpen = { openSelectorFor = SettingsPlaybackAutoPlayDelayRow },
                    labelIndent = GeneralChildRowLabelIndent,
                )
            }
        }

        if (PlaybackStingerGroup.rowKeys.any { it in rowKeys }) {
            Text(PlaybackStingerGroup.heading, style = InfusionTvScaleCalibration.Type.Headline)
        }
        GeneralToggleRow(
            label = "Enable Stinger Alerts (movies)",
            value = segmentToggleChoiceLabel(segmentPreferences.enableStingerAlerts),
            tag = SettingsPlaybackStingerAlertsRow,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Playback.StingerAlerts,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onToggle = { onEnableStingerAlertsChange(!segmentPreferences.enableStingerAlerts) },
        )
        if (segmentPreferences.enableStingerAlerts) {
            GeneralSettingRow(
                label = "Stinger Trigger Percent",
                value = stingerPercentChoiceLabel(segmentPreferences.stingerTriggerPercent),
                tag = SettingsPlaybackStingerPercentRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.Playback.StingerTriggerPercent,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onOpen = { openSelectorFor = SettingsPlaybackStingerPercentRow },
                labelIndent = GeneralChildRowLabelIndent,
            )
            GeneralSettingRow(
                label = "Early Notification Delay",
                value = stingerEarlyChoiceLabel(segmentPreferences.stingerEarlyNotifySeconds),
                tag = SettingsPlaybackStingerEarlyRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.Playback.StingerEarlyNotify,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onOpen = { openSelectorFor = SettingsPlaybackStingerEarlyRow },
                labelIndent = GeneralChildRowLabelIndent,
            )
            GeneralSettingRow(
                label = "Toast Duration",
                value = stingerToastChoiceLabel(segmentPreferences.stingerToastDurationSeconds),
                tag = SettingsPlaybackStingerToastRow,
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.Playback.StingerToastDuration,
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onOpen = { openSelectorFor = SettingsPlaybackStingerToastRow },
                labelIndent = GeneralChildRowLabelIndent,
            )
        }
    }
    when (openSelectorFor) {
        SettingsPlaybackSegmentDisplayRow -> GeneralSelectorDialog(
            rowTag = SettingsPlaybackSegmentDisplayRow,
            choices = SegmentDisplayMode.entries,
            current = segmentPreferences.displayMode,
            choiceKey = { it.storageKey },
            choiceLabel = { segmentDisplayChoiceLabel(it) },
            onSelect = { chosen ->
                onSegmentDisplayChange(chosen)
                selectorReturnKey = SettingsPlaybackSegmentDisplayRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackSegmentDisplayRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackSegmentFallbackStartRow -> GeneralSelectorDialog(
            rowTag = SettingsPlaybackSegmentFallbackStartRow,
            choices = SegmentFallbackTimecode.entries,
            current = segmentPreferences.fallbackStart,
            choiceKey = { it.storageKey },
            choiceLabel = { segmentFallbackTimecodeChoiceLabel(it) },
            onSelect = { chosen ->
                onSegmentFallbackStartChange(chosen)
                selectorReturnKey = SettingsPlaybackSegmentFallbackStartRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackSegmentFallbackStartRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackSegmentFallbackEndRow -> GeneralSelectorDialog(
            rowTag = SettingsPlaybackSegmentFallbackEndRow,
            choices = SegmentFallbackTimecode.entries,
            current = segmentPreferences.fallbackEnd,
            choiceKey = { it.storageKey },
            choiceLabel = { segmentFallbackTimecodeChoiceLabel(it) },
            onSelect = { chosen ->
                onSegmentFallbackEndChange(chosen)
                selectorReturnKey = SettingsPlaybackSegmentFallbackEndRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackSegmentFallbackEndRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackControlsAutoHideRow -> GeneralSelectorDialog(
            rowTag = SettingsPlaybackControlsAutoHideRow,
            choices = PlaybackControlsAutoHide.entries,
            current = controlsAutoHide,
            choiceKey = { it.storageKey },
            choiceLabel = { playbackControlsAutoHideChoiceLabel(it) },
            onSelect = { chosen ->
                onControlsAutoHideChange(chosen)
                selectorReturnKey = SettingsPlaybackControlsAutoHideRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackControlsAutoHideRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackRelativeSeekStepRow -> GeneralSelectorDialog(
            rowTag = SettingsPlaybackRelativeSeekStepRow,
            choices = dev.infusion.core.playback.api.RelativeSeekStep.entries,
            current = relativeSeekStep,
            choiceKey = { it.storageKey },
            choiceLabel = { relativeSeekStepChoiceLabel(it) },
            onSelect = { chosen ->
                onRelativeSeekStepChange(chosen)
                selectorReturnKey = SettingsPlaybackRelativeSeekStepRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackRelativeSeekStepRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackAutoSkipDelayRow -> GeneralSelectorDialog(
            rowTag = SettingsPlaybackAutoSkipDelayRow,
            choices = autoSkipDelayChoices,
            current = segmentPreferences.autoSkipDelaySeconds,
            choiceKey = { it.toString() },
            choiceLabel = { autoSkipDelayChoiceLabel(it) },
            onSelect = { chosen ->
                onAutoSkipDelayChange(chosen)
                selectorReturnKey = SettingsPlaybackAutoSkipDelayRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackAutoSkipDelayRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackStingerPercentRow -> GeneralSelectorDialog(
            rowTag = SettingsPlaybackStingerPercentRow,
            choices = stingerPercentChoices,
            current = segmentPreferences.stingerTriggerPercent,
            choiceKey = { it.toString() },
            choiceLabel = { stingerPercentChoiceLabel(it) },
            onSelect = { chosen ->
                onStingerTriggerPercentChange(chosen)
                selectorReturnKey = SettingsPlaybackStingerPercentRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackStingerPercentRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackStingerEarlyRow -> GeneralSelectorDialog(
            rowTag = SettingsPlaybackStingerEarlyRow,
            choices = stingerEarlyChoices,
            current = segmentPreferences.stingerEarlyNotifySeconds,
            choiceKey = { it.toString() },
            choiceLabel = { stingerEarlyChoiceLabel(it) },
            onSelect = { chosen ->
                onStingerEarlyNotifyChange(chosen)
                selectorReturnKey = SettingsPlaybackStingerEarlyRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackStingerEarlyRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackStingerToastRow -> GeneralSelectorDialog(
            rowTag = SettingsPlaybackStingerToastRow,
            choices = stingerToastChoices,
            current = segmentPreferences.stingerToastDurationSeconds,
            choiceKey = { it.toString() },
            choiceLabel = { stingerToastChoiceLabel(it) },
            onSelect = { chosen ->
                onStingerToastDurationChange(chosen)
                selectorReturnKey = SettingsPlaybackStingerToastRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackStingerToastRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackSourceAutoplayRow -> GeneralSelectorDialog(
            rowTag = SettingsPlaybackSourceAutoplayRow,
            choices = SourceAutoplayPolicy.entries,
            current = sourceAutoplayPreferences.policy,
            choiceKey = { it.storageKey },
            choiceLabel = { sourceAutoplayChoiceLabel(it) },
            onSelect = { chosen ->
                onSourceAutoplayPolicyChange(chosen)
                selectorReturnKey = SettingsPlaybackSourceAutoplayRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackSourceAutoplayRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackAutoPlayDelayRow -> GeneralSelectorDialog(
            rowTag = SettingsPlaybackAutoPlayDelayRow,
            choices = autoPlayDelayChoices,
            current = nextEpisodePreferences.autoPlayDelaySeconds,
            choiceKey = { it.toString() },
            choiceLabel = { autoPlayDelayChoiceLabel(it) },
            onSelect = { chosen ->
                onAutoPlayDelayChange(chosen)
                selectorReturnKey = SettingsPlaybackAutoPlayDelayRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackAutoPlayDelayRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackNextFallbackPercentRow -> GeneralSelectorDialog(
            rowTag = SettingsPlaybackNextFallbackPercentRow,
            choices = nextFallbackPercentChoices,
            current = nextEpisodePreferences.fallbackTriggerPercent,
            choiceKey = { it.toString() },
            choiceLabel = { nextFallbackPercentChoiceLabel(it) },
            onSelect = { chosen ->
                onNextFallbackPercentChange(chosen)
                selectorReturnKey = SettingsPlaybackNextFallbackPercentRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackNextFallbackPercentRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackDisplayMatchingRow -> GeneralSelectorDialog(
            rowTag = SettingsPlaybackDisplayMatchingRow,
            choices = DisplayMatchingPolicy.entries,
            current = displayMatching,
            choiceKey = { it.storageKey },
            choiceLabel = { displayMatchingChoiceLabel(it) },
            choiceSupportingText = { displayMatchingChoiceSupportingText(it) },
            onSelect = { chosen ->
                onDisplayMatchingChange(chosen)
                selectorReturnKey = SettingsPlaybackDisplayMatchingRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackDisplayMatchingRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackDolbyVisionProfile7FallbackRow -> GeneralSelectorDialog(
            rowTag = SettingsPlaybackDolbyVisionProfile7FallbackRow,
            choices = DolbyVisionProfile7Fallback.entries,
            current = dolbyVisionProfile7Fallback,
            choiceKey = { it.storageKey },
            choiceLabel = { dolbyVisionProfile7FallbackChoiceLabel(it) },
            choiceSupportingText = { dolbyVisionProfile7FallbackChoiceSupportingText(it) },
            onSelect = { chosen ->
                onDolbyVisionProfile7FallbackChange(chosen)
                selectorReturnKey = SettingsPlaybackDolbyVisionProfile7FallbackRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackDolbyVisionProfile7FallbackRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackMovieCompletionRow -> GeneralSelectorDialog(
            rowTag = SettingsPlaybackMovieCompletionRow,
            choices = CompletionThreshold.entries,
            current = movieCompletion,
            choiceKey = { it.storageKey },
            choiceLabel = { completionThresholdChoiceLabel(it) },
            onSelect = { chosen ->
                onMovieCompletionChange(chosen)
                selectorReturnKey = SettingsPlaybackMovieCompletionRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackMovieCompletionRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackEpisodeCompletionRow -> GeneralSelectorDialog(
            rowTag = SettingsPlaybackEpisodeCompletionRow,
            choices = CompletionThreshold.entries,
            current = episodeCompletion,
            choiceKey = { it.storageKey },
            choiceLabel = { completionThresholdChoiceLabel(it) },
            onSelect = { chosen ->
                onEpisodeCompletionChange(chosen)
                selectorReturnKey = SettingsPlaybackEpisodeCompletionRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackEpisodeCompletionRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackAudioLanguageRow -> SettingsLanguageSelectorDialog(
            rowTag = SettingsPlaybackAudioLanguageRow,
            choices = audioLanguageSelectorChoices(AudioLanguageSelector.PRIMARY, audioLanguage, audioLanguage),
            current = audioLanguage,
            choiceKey = { preference -> audioLanguageChoiceKey(preference) },
            choiceLabel = { preference -> audioLanguageChoiceLabel(preference) },
            onSelect = { chosen ->
                onAudioLanguageChange(chosen)
                selectorReturnKey = SettingsPlaybackAudioLanguageRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackAudioLanguageRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackAudioLanguageSecondaryRow -> SettingsLanguageSelectorDialog(
            rowTag = SettingsPlaybackAudioLanguageSecondaryRow,
            choices = audioLanguageSelectorChoices(AudioLanguageSelector.SECONDARY, secondaryAudioLanguage, audioLanguage),
            current = secondaryAudioLanguage,
            choiceKey = { preference -> audioLanguageChoiceKey(preference) },
            choiceLabel = { preference -> audioLanguageChoiceLabel(preference) },
            onSelect = { chosen ->
                onSecondaryAudioLanguageChange(chosen)
                selectorReturnKey = SettingsPlaybackAudioLanguageSecondaryRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackAudioLanguageSecondaryRow
                openSelectorFor = null
            },
        )

        SettingsPlaybackSubtitleLanguageRow -> SettingsLanguageSelectorDialog(
            rowTag = SettingsPlaybackSubtitleLanguageRow,
            choices = subtitleLanguageSelectorChoices(
                SubtitleLanguageSelector.PRIMARY,
                subtitlePreferences.primary,
                subtitlePreferences.primary,
            ),
            current = subtitlePreferences.primary,
            choiceKey = { preference -> subtitleLanguageChoiceKey(preference) },
            choiceLabel = { preference -> subtitleLanguageChoiceLabel(preference) },
            onSelect = { chosen ->
                onSubtitleLanguageChange(chosen)
                selectorReturnKey = SettingsPlaybackSubtitleLanguageRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackSubtitleLanguageRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackSubtitleLanguageSecondaryRow -> SettingsLanguageSelectorDialog(
            rowTag = SettingsPlaybackSubtitleLanguageSecondaryRow,
            choices = subtitleLanguageSelectorChoices(
                SubtitleLanguageSelector.SECONDARY,
                subtitlePreferences.secondary,
                subtitlePreferences.primary,
            ),
            current = subtitlePreferences.secondary,
            choiceKey = { preference -> subtitleLanguageChoiceKey(preference) },
            choiceLabel = { preference -> subtitleLanguageChoiceLabel(preference) },
            onSelect = { chosen ->
                onSecondarySubtitleLanguageChange(chosen)
                selectorReturnKey = SettingsPlaybackSubtitleLanguageSecondaryRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackSubtitleLanguageSecondaryRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackSubtitlesRow -> GeneralSelectorDialog(
            rowTag = SettingsPlaybackSubtitlesRow,
            choices = SubtitleModePreference.entries,
            current = subtitlePreferences.mode,
            choiceKey = { mode -> mode.name },
            choiceLabel = { mode -> subtitleModeChoiceLabel(mode) },
            onSelect = { chosen ->
                onSubtitleModeChange(chosen)
                selectorReturnKey = SettingsPlaybackSubtitlesRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackSubtitlesRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackSubtitleSameLanguageRow -> GeneralSelectorDialog(
            rowTag = SettingsPlaybackSubtitleSameLanguageRow,
            choices = SubtitleSameLanguagePolicy.entries,
            current = subtitlePreferences.sameLanguage,
            choiceKey = { policy -> policy.name },
            choiceLabel = { policy -> subtitleSameLanguageChoiceLabel(policy) },
            onSelect = { chosen ->
                onSubtitleSameLanguageChange(chosen)
                selectorReturnKey = SettingsPlaybackSubtitleSameLanguageRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackSubtitleSameLanguageRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackSubtitleTypeRow -> GeneralSelectorDialog(
            rowTag = SettingsPlaybackSubtitleTypeRow,
            choices = SubtitleTypePreference.entries,
            current = subtitlePreferences.type,
            choiceKey = { type -> type.name },
            choiceLabel = { type -> subtitleTypeChoiceLabel(type) },
            onSelect = { chosen ->
                onSubtitleTypeChange(chosen)
                selectorReturnKey = SettingsPlaybackSubtitleTypeRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackSubtitleTypeRow
                openSelectorFor = null
            },
        )

        SettingsPlaybackSubtitleAppearanceRow -> SettingsAppearanceSelector(
            rowTag = SettingsPlaybackSubtitleAppearanceRow,
            choices = SubtitleAppearanceMode.entries,
            current = subtitleAppearance.mode,
            choiceLabel = { mode -> subtitleAppearanceModeChoiceLabel(mode) },
            choiceSupportingText = { mode -> subtitleAppearanceModeChoiceSupportingText(mode) },
            onSelect = { chosen ->
                onSubtitleAppearanceModeChange(chosen)
                selectorReturnKey = SettingsPlaybackSubtitleAppearanceRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackSubtitleAppearanceRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackSubtitleSizeRow -> SettingsAppearanceSelector(
            rowTag = SettingsPlaybackSubtitleSizeRow,
            choices = SubtitleSizeChoice.entries,
            current = subtitleAppearance.size,
            choiceLabel = { size -> subtitleSizeChoiceLabel(size) },
            onSelect = { chosen ->
                onSubtitleSizeChange(chosen)
                selectorReturnKey = SettingsPlaybackSubtitleSizeRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackSubtitleSizeRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackSubtitleFontRow -> SettingsAppearanceSelector(
            rowTag = SettingsPlaybackSubtitleFontRow,
            choices = SubtitleFontChoice.entries,
            current = subtitleAppearance.font,
            choiceLabel = { font -> subtitleFontChoiceLabel(font) },
            onSelect = { chosen ->
                onSubtitleFontChange(chosen)
                selectorReturnKey = SettingsPlaybackSubtitleFontRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackSubtitleFontRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackSubtitleTextColorRow -> SettingsAppearanceSelector(
            rowTag = SettingsPlaybackSubtitleTextColorRow,
            choices = SubtitleTextColorChoice.entries,
            current = subtitleAppearance.textColor,
            choiceLabel = { color -> subtitleTextColorChoiceLabel(color) },
            onSelect = { chosen ->
                onSubtitleTextColorChange(chosen)
                selectorReturnKey = SettingsPlaybackSubtitleTextColorRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackSubtitleTextColorRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackSubtitleBackgroundRow -> SettingsAppearanceSelector(
            rowTag = SettingsPlaybackSubtitleBackgroundRow,
            choices = SubtitleBackgroundChoice.entries,
            current = subtitleAppearance.background,
            choiceLabel = { background -> subtitleBackgroundChoiceLabel(background) },
            onSelect = { chosen ->
                onSubtitleBackgroundChange(chosen)
                selectorReturnKey = SettingsPlaybackSubtitleBackgroundRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackSubtitleBackgroundRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackSubtitleBackgroundOpacityRow -> SettingsAppearanceSelector(
            rowTag = SettingsPlaybackSubtitleBackgroundOpacityRow,
            choices = SubtitleBackgroundOpacityChoice.entries,
            current = subtitleAppearance.backgroundOpacity,
            choiceLabel = { opacity -> subtitleBackgroundOpacityChoiceLabel(opacity) },
            onSelect = { chosen ->
                onSubtitleBackgroundOpacityChange(chosen)
                selectorReturnKey = SettingsPlaybackSubtitleBackgroundOpacityRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackSubtitleBackgroundOpacityRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackSubtitleEdgeRow -> SettingsAppearanceSelector(
            rowTag = SettingsPlaybackSubtitleEdgeRow,
            choices = SubtitleEdgeChoice.entries,
            current = subtitleAppearance.edge,
            choiceLabel = { edge -> subtitleEdgeChoiceLabel(edge) },
            onSelect = { chosen ->
                onSubtitleEdgeChange(chosen)
                selectorReturnKey = SettingsPlaybackSubtitleEdgeRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackSubtitleEdgeRow
                openSelectorFor = null
            },
        )
        SettingsPlaybackSubtitlePositionRow -> SettingsAppearanceSelector(
            rowTag = SettingsPlaybackSubtitlePositionRow,
            choices = SubtitlePositionChoice.entries,
            current = subtitleAppearance.position,
            choiceLabel = { position -> subtitlePositionChoiceLabel(position) },
            onSelect = { chosen ->
                onSubtitlePositionChange(chosen)
                selectorReturnKey = SettingsPlaybackSubtitlePositionRow
                openSelectorFor = null
            },
            onDismiss = {
                selectorReturnKey = SettingsPlaybackSubtitlePositionRow
                openSelectorFor = null
            },
        )
    }
}

@Composable
private fun <T : Enum<T>> SettingsAppearanceSelector(
    rowTag: String,
    choices: List<T>,
    current: T,
    choiceLabel: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    choiceSupportingText: ((T) -> String?)? = null,
) {
    GeneralSelectorDialog(
        rowTag = rowTag,
        choices = choices,
        current = current,
        choiceKey = { choice -> choice.name },
        choiceLabel = choiceLabel,
        choiceSupportingText = choiceSupportingText,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

@Composable
private fun <T : Any> SettingsLanguageSelectorDialog(
    rowTag: String,
    choices: List<T>,
    current: T,
    choiceKey: (T) -> String,
    choiceLabel: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val requesters = remember { mutableMapOf<T, FocusRequester>() }
    val bringIntoView = remember { mutableMapOf<T, BringIntoViewRequester>() }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var focusedChoice by remember(choices) { mutableStateOf(current) }

    val currentIndex = choices.indexOf(current).coerceAtLeast(0)
    val density = LocalDensity.current
    val rowPitchPx = with(density) { (GeneralRowHeight + GeneralSelectorRowSpacing).toPx() }
    var initialFocusRequested by remember { mutableStateOf(false) }
    LaunchedEffect(choices) {
        scrollState.scrollTo((currentIndex * rowPitchPx).roundToInt())
        repeat(30) {
            if (initialFocusRequested) return@LaunchedEffect
            withFrameNanos { }
            if (requesters[current]?.requestFocus() == true) initialFocusRequested = true
        }
    }
    BackHandler(enabled = true, onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .width(GeneralSelectorWidth)
                .border(
                    width = InfusionTvContour.SelectedWidth,
                    color = LocalInfusionAccent.current,
                    shape = InfusionTvContour.Shape,
                ),
            shape = InfusionTvContour.Shape,
            colors = SurfaceDefaults.colors(containerColor = InfusionTvAppearance.Panel),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = GeneralSelectorMaxHeight)
                    .verticalScroll(scrollState)
                    .padding(16.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionUp, Key.DirectionDown -> {
                                val delta = if (event.key == Key.DirectionUp) -1 else 1
                                val index = choices.indexOf(focusedChoice).coerceAtLeast(0)
                                val target = choices[(index + delta).coerceIn(0, choices.lastIndex)]
                                requesters[target]?.requestFocus()
                                true
                            }
                            Key.Back -> {
                                onDismiss()
                                true
                            }
                            else -> false
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(GeneralSelectorRowSpacing),
            ) {
                choices.forEach { choice ->
                    val key = choiceKey(choice)
                    val requester = remember(choice) { FocusRequester() }
                    val bringRequester = remember(choice) { BringIntoViewRequester() }
                    requesters[choice] = requester
                    bringIntoView[choice] = bringRequester
                    InfusionTvButton(
                        onClick = { onSelect(choice) },
                        modifier = Modifier
                            .fillMaxWidth()

                            .height(GeneralRowHeight)
                            .testTag(generalSelectorChoiceKey(rowTag, key))
                            .focusRequester(requester)
                            .bringIntoViewRequester(bringRequester)
                            .onFocusChanged { state ->
                                if (state.isFocused) {
                                    focusedChoice = choice
                                    scope.launch { bringRequester.bringIntoView() }
                                }
                            }
                            .semantics {
                                role = Role.RadioButton
                                this.selected = choice == current
                            },
                        selected = choice == current,
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                choiceLabel(choice),
                                style = InfusionTvScaleCalibration.Type.SettingsContent,
                                modifier = Modifier.weight(1f),
                            )
                            if (choice == current) {
                                Text("✓", style = InfusionTvScaleCalibration.Type.SettingsContent)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun GeneralSettingRow(
    label: String,
    value: String,
    tag: String,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onOpen: () -> Unit,

    labelIndent: Dp = 0.dp,

    presentation: SettingsRowPresentation = SettingsRowPresentation.COMPACT,

    help: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val expanded = presentation == SettingsRowPresentation.EXPANDED
    InfusionTvButton(
        onClick = onOpen,
        interactionSource = interactionSource,
        modifier = Modifier
            .then(
                if (expanded) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.widthIn(max = GeneralRowMaxWidth)
                },
            )
            .then(
                if (expanded) {
                    Modifier.heightIn(min = GeneralRowHeight)
                } else {
                    Modifier.height(GeneralRowHeight)
                },
            )
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {

                    Key.DirectionRight -> {
                        onOpen()
                        true
                    }
                    else -> false
                }
            }
            .then(
                stableFocusModifier(
                    key = tag,
                    requesters = requesters,
                    onFocusedKeyChanged = onFocused,
                ),
            )
            .then(if (expanded) settingsRowBringIntoView(focused) else Modifier),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        SettingsRowBody(
            presentation = presentation,
            label = label,
            help = help,
            value = value,
            focused = focused,
            labelIndent = labelIndent,
            valueColor = infusionAccentContentColor(focused),
            trailing = {
                Spacer(Modifier.width(SettingsRowPresentationMetrics.TrailingGap))
                Text("›", style = InfusionTvScaleCalibration.Type.SettingsContent)
            },
        )
    }
}

@Composable
private fun GeneralToggleRow(
    label: String,
    value: String,
    tag: String,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onToggle: () -> Unit,
    labelIndent: Dp = 0.dp,
    modifier: Modifier = Modifier,
    rightEdge: Boolean = true,

    presentation: SettingsRowPresentation = SettingsRowPresentation.COMPACT,

    help: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val expanded = presentation == SettingsRowPresentation.EXPANDED
    InfusionTvButton(
        onClick = onToggle,
        interactionSource = interactionSource,
        modifier = Modifier
            .then(modifier)
            .then(
                when {
                    expanded -> Modifier.fillMaxWidth()
                    rightEdge -> Modifier.widthIn(max = GeneralRowMaxWidth)
                    else -> Modifier
                },
            )
            .then(
                if (expanded) {
                    Modifier.heightIn(min = GeneralRowHeight)
                } else {
                    Modifier.height(GeneralRowHeight)
                },
            )

            .then(if (rightEdge) Modifier.focusProperties { right = FocusRequester.Cancel } else Modifier)
            .then(
                stableFocusModifier(
                    key = tag,
                    requesters = requesters,
                    onFocusedKeyChanged = onFocused,
                ),
            )
            .then(if (expanded) settingsRowBringIntoView(focused) else Modifier),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        SettingsRowBody(
            presentation = presentation,
            label = label,
            help = help,
            value = value,
            focused = focused,
            labelIndent = labelIndent,
            valueColor = infusionAccentContentColor(focused),
        )
    }
}

@Composable
internal fun <T> GeneralSelectorDialog(
    rowTag: String,
    choices: List<T>,
    current: T,
    choiceKey: (T) -> String,
    choiceLabel: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    choiceSupportingText: ((T) -> String?)? = null,
    leading: (@Composable (T) -> Unit)? = null,

    choiceEnabled: (T) -> Boolean = { true },
    panelWidth: Dp = GeneralSelectorWidth,
    panelMaxHeight: Dp = GeneralSelectorMaxHeight,
    scrollIndicators: Boolean = false,
    onEndReached: (() -> Unit)? = null,
) {
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val initialChoice = if (choices.contains(current) && choiceEnabled(current)) current
    else choices.firstOrNull { choiceEnabled(it) } ?: choices.firstOrNull() ?: current
    var focusedChoice by remember { mutableStateOf(initialChoice) }
    var initialFocusRequested by remember { mutableStateOf(false) }
    fun requestInitialFocus() {
        if (requesters[choiceKey(initialChoice)]?.requestFocus() == true) {
            initialFocusRequested = true
        }
    }
    LaunchedEffect(Unit) {
        repeat(30) {
            if (initialFocusRequested) return@LaunchedEffect
            withFrameNanos { }
            requestInitialFocus()
        }
    }

    BackHandler(enabled = true, onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .width(panelWidth)
                .onGloballyPositioned {

                    if (!initialFocusRequested) requestInitialFocus()
                }
                .border(
                    width = InfusionTvContour.SelectedWidth,
                    color = LocalInfusionAccent.current,
                    shape = InfusionTvContour.Shape,
                ),
            shape = InfusionTvContour.Shape,
            colors = SurfaceDefaults.colors(containerColor = InfusionTvAppearance.Panel),
        ) {
            Column(

                modifier = Modifier
                    .padding(16.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionUp, Key.DirectionDown -> {
                                val delta = if (event.key == Key.DirectionUp) -1 else 1
                                val index = choices.indexOf(focusedChoice).coerceAtLeast(0)

                                val target = nextEnabledChoice(choices, index, delta, choiceEnabled)
                                if (target != null) requesters[choiceKey(target)]?.requestFocus()
                                true
                            }
                            Key.Back -> {
                                onDismiss()
                                true
                            }
                            else -> false
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (scrollIndicators && scrollState.canScrollBackward) {
                    SelectorScrollHint("▲")
                }
                Column(
                    modifier = Modifier
                        .heightIn(max = panelMaxHeight)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(GeneralSelectorRowSpacing),
                ) {
                    choices.forEach { choice ->
                        val key = choiceKey(choice)
                        val requester = remember(choice) { FocusRequester() }
                        val bringRequester = remember(choice) { BringIntoViewRequester() }
                        requesters[key] = requester
                        val supportingText = choiceSupportingText?.invoke(choice)
                        val choiceIsEnabled = choiceEnabled(choice)
                        InfusionTvButton(
                            onClick = { onSelect(choice) },

                            enabled = choiceIsEnabled,
                            modifier = Modifier
                                .fillMaxWidth()

                                .heightIn(min = GeneralRowHeight)
                                .testTag(generalSelectorChoiceKey(rowTag, key))
                                .focusRequester(requester)
                                .bringIntoViewRequester(bringRequester)
                                .onFocusChanged { state ->
                                    if (state.isFocused) {
                                        focusedChoice = choice

                                        scope.launch { bringRequester.bringIntoView() }

                                        if (onEndReached != null && choice == choices.lastOrNull()) {
                                            onEndReached()
                                        }
                                    }
                                }
                                .semantics {
                                    role = Role.RadioButton
                                    this.selected = choice == current
                                    if (!choiceIsEnabled) this.disabled()
                                },
                            selected = choice == current,
                            contentPadding = PaddingValues(horizontal = 16.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                leading?.invoke(choice)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        choiceLabel(choice),
                                        style = InfusionTvScaleCalibration.Type.SettingsContent,
                                    )
                                    if (supportingText != null) {
                                        Text(
                                            supportingText,
                                            style = InfusionTvScaleCalibration.Type.SettingsContent,
                                            color = LocalContentColor.current.copy(alpha = 0.75f),
                                        )
                                    }
                                }
                                if (choice == current) {
                                    Text("✓", style = InfusionTvScaleCalibration.Type.SettingsContent)
                                }
                            }
                        }
                    }
                }
                if (scrollIndicators && scrollState.canScrollForward) {
                    SelectorScrollHint("▼")
                }
            }
        }
    }
}

private fun <T> nextEnabledChoice(
    choices: List<T>,
    from: Int,
    delta: Int,
    enabled: (T) -> Boolean,
): T? {
    var index = from + delta
    while (index in choices.indices) {
        if (enabled(choices[index])) return choices[index]
        index += delta
    }
    return null
}

@Composable
private fun SelectorScrollHint(glyph: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { disabled() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = InfusionTvScaleCalibration.Type.SettingsContent,
            color = LocalContentColor.current.copy(alpha = 0.75f),
            modifier = Modifier.wrapContentHeight(Alignment.CenterVertically),
        )
    }
}

private fun accentDisplayName(accent: AccentColor): String = when (accent) {
    AccentColor.ORANGE -> "Orange"
    AccentColor.RED -> "Red"
    AccentColor.AMBER -> "Amber"
    AccentColor.GREEN -> "Green"
    AccentColor.BLUE -> "Blue"
    AccentColor.PURPLE -> "Purple"
}

private fun artworkStyleDisplayName(style: ArtworkStyle): String = when (style) {
    ArtworkStyle.POSTER -> "Poster"
    ArtworkStyle.LANDSCAPE -> "Landscape"
}

@Composable
private fun SectionActionButton(
    label: String,
    tag: String,
    firstKey: String,
    requesters: MutableMap<String, FocusRequester>,
    entryToken: Int,
    rememberedKey: String?,
    onFocusRequested: (String) -> Unit,
    onReturnToRail: () -> Unit,
    onClick: () -> Unit,
) {
    val requester = remember(tag) { FocusRequester() }
    requesters[tag] = requester
    requesters[firstKey] = requester
    RestoreSectionFocus(entryToken, rememberedKey, firstKey, requesters)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InfusionTvButton(
            onClick = onClick,

            colors = infusionTvButtonColors(),
            modifier = Modifier
                .testTag(tag)
                .focusRequester(requester)
                .onFocusChanged { state ->
                    if (state.isFocused) onFocusRequested(tag)
                }
                .onPreviewKeyEvent { event ->

                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp
                },
        ) {
            Text(label)
        }
    }
}

@Composable
private fun PhoneSetupSectionContent(
    tag: String,
    firstKey: String,
    requesters: MutableMap<String, FocusRequester>,
    entryToken: Int,
    rememberedKey: String?,
    onFocusRequested: (String) -> Unit,
    onClick: () -> Unit,
) {
    val requester = remember(tag) { FocusRequester() }
    requesters[tag] = requester
    requesters[firstKey] = requester
    RestoreSectionFocus(entryToken, rememberedKey, firstKey, requesters)
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        InfusionTvButton(
            onClick = onClick,
            interactionSource = interactionSource,
            colors = infusionTvButtonColors(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = GeneralRowHeight)
                .testTag(tag)
                .focusRequester(requester)
                .onFocusChanged { state ->
                    if (state.isFocused) onFocusRequested(tag)
                }
                .then(settingsRowBringIntoView(focused))
                .onPreviewKeyEvent { event ->

                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp
                },
        ) {
            SettingsRowBody(
                presentation = SettingsRowPresentation.EXPANDED,
                label = PhoneSetupRowLabel,
                help = PhoneSetupRowHelp,
                value = "",
                focused = focused,
                valueColor = infusionAccentContentColor(focused),
            )
        }
    }
}

internal const val PhoneSetupRowLabel: String = "Manage from phone"

internal const val PhoneSetupRowHelp: String =
    "Scan a QR code to manage settings, add-ons and collections from your phone."

@Composable
private fun ProfilesSectionContent(
    profileManagement: ProfileManagementStateHolder?,
    pinEntryFactory: ((String) -> PinEntryStateHolder)?,
    requesters: MutableMap<String, FocusRequester>,
    entryToken: Int,
    rememberedKey: String?,
    firstKey: String,
    onFocusRequested: (String) -> Unit,
    onReturnToRail: () -> Unit,
) {
    if (profileManagement == null || pinEntryFactory == null) {
        Text(
            "Profiles unavailable.",
            style = InfusionTvScaleCalibration.Type.SettingsContent,
            modifier = Modifier.padding(24.dp),
        )
        return
    }
    RestoreSectionFocus(entryToken, rememberedKey, firstKey, requesters)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        TvProfileSection(
            management = profileManagement,
            pinEntryFactory = pinEntryFactory,

            requesters = requesters,
            onControlFocused = onFocusRequested,
        )
    }
}

@Composable
internal fun JellyfinActionCell(
    label: String,
    tag: String,
    available: Boolean,
    focusRequester: FocusRequester,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onClick: () -> Unit,
) {
    if (!available) {
        requesters.remove(tag)
        Box(
            modifier = Modifier
                .height(48.dp)
                .testTag(tag)
                .semantics {
                    role = Role.Button
                    disabled()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            )
        }
        return
    }
    requesters[tag] = focusRequester
    InfusionTvButton(
        onClick = onClick,
        modifier = Modifier
            .testTag(tag)
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (state.isFocused) onFocused(tag)
            },
    ) {
        Text(label)
    }
}

@Composable
private fun TrackingSectionContent(
    holders: List<TrackingSettingsStateHolder>,
    requesters: MutableMap<String, FocusRequester>,
    entryToken: Int,
    rememberedKey: String?,
    onFocusRequested: (String) -> Unit,
    onReturnToRail: () -> Unit,
) {

    val states: List<TrackingSettingsUiState?> = holders.map { holder ->
        val state by holder.state.collectAsState()
        state
    }
    val providerIds: List<String> = holders.map { it.state.value.providerId }

    RestoreSectionFocus(entryToken, rememberedKey, SettingsTrackingSelection, requesters)

    val paneRows = remember(providerIds) {
        buildList {
            add(SettingsTrackingSelection)
            providerIds.forEach { add(trackingActionTag(it)) }
        }
    }
    var focusedRowKey by remember { mutableStateOf(SettingsTrackingSelection) }
    val actionFocus = remember(providerIds) { providerIds.associateWith { FocusRequester() } }

    LaunchedEffect(holders) { holders.forEach { it.refresh() } }
    DisposableEffect(holders) { onDispose { holders.forEach { it.stop() } } }

    var overlayProviderId by remember { mutableStateOf<String?>(null) }

    var selectionSelectorOpen by remember { mutableStateOf(false) }
    var selectionSelectorWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(selectionSelectorOpen) {
        val wasOpen = selectionSelectorWasOpen
        selectionSelectorWasOpen = selectionSelectorOpen
        if (wasOpen && !selectionSelectorOpen) {
            repeat(30) {
                withFrameNanos { }
                if (requesters[SettingsTrackingSelection]?.requestFocus() == true) {
                    return@LaunchedEffect
                }
            }
        }
    }
    val awaitingProviderId = providerIds.firstOrNull { id ->
        states.getOrNull(providerIds.indexOf(id))?.awaitingApproval != null
    }
    LaunchedEffect(awaitingProviderId) {
        if (awaitingProviderId != null) overlayProviderId = awaitingProviderId
    }
    LaunchedEffect(states.map { it?.isConnected }) {

        val open = overlayProviderId ?: return@LaunchedEffect
        val index = providerIds.indexOf(open)
        if (index >= 0 && states.getOrNull(index)?.isConnected == true) overlayProviderId = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag(SettingsTrackingPane)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val delta = when (event.key) {
                    Key.DirectionUp -> -1
                    Key.DirectionDown -> 1
                    else -> return@onPreviewKeyEvent false
                }

                val available = paneRows.filter { requesters.containsKey(it) }
                if (available.isEmpty()) return@onPreviewKeyEvent true
                val index = available.indexOf(focusedRowKey).coerceAtLeast(0)
                val target = available[(index + delta).coerceIn(0, available.lastIndex)]
                requesters[target]?.requestFocus()
                true
            },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Tracking", style = InfusionTvScaleCalibration.Type.Headline)
        val current = states.firstOrNull { it != null }
        if (current == null || holders.isEmpty()) {
            Text(
                "Tracking setup unavailable.",
                style = InfusionTvScaleCalibration.Type.SettingsContent,
            )
            return@Column
        }

        GeneralSettingRow(
            label = TRACKING_SELECTION_TITLE,
            value = trackingSelectionRowValue(current),
            tag = SettingsTrackingSelection,
            requesters = requesters,
            onFocused = { key ->
                focusedRowKey = key
                onFocusRequested(key)
            },
            onOpen = { selectionSelectorOpen = true },
            presentation = SettingsRowPresentation.EXPANDED,
            help = trackingSelectionRowHelp(current),
        )

        for ((index, providerId) in providerIds.withIndex()) {
            val providerState = states.getOrNull(index) ?: continue
            val providerHolder = holders[index]
            TrackingProviderRow(
                label = trackingTrackerLabel(providerId, providerState.providerName),
                value = trackingProviderRowValue(providerState),
                help = trackingProviderRowHelp(providerState),
                tag = trackingActionTag(providerId),
                statusTag = trackingStatusTag(providerId),

                entryKey = if (index == 0) TrackingFocusAction else null,
                available = trackingActionAvailable(providerState),
                focusRequester = actionFocus.getValue(providerId),
                requesters = requesters,
                onFocused = { key ->
                    focusedRowKey = key
                    onFocusRequested(key)
                },
                onClick = {
                    when (providerState.connection) {
                        is TrackingConnectionState.Connected -> providerHolder.disconnect()
                        is TrackingConnectionState.AwaitingApproval -> providerHolder.cancel()
                        else -> {

                            overlayProviderId = providerId
                            providerHolder.connect()
                        }
                    }
                },
            )

            if (!providerState.clientRegistered) {
                Text(
                    TrackingSettingsStateHolder.missingRegistrationMessage(providerState.providerName),
                    style = InfusionTvScaleCalibration.Type.SettingsContent,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        trackingSelectedSendCountLine(current)?.let { line ->
            Text(
                line,
                style = InfusionTvScaleCalibration.Type.SettingsContent,
                modifier = Modifier.testTag(SettingsTrackingSends),
            )
        }
        trackingUserChoiceCountLine(current)?.let { line ->
            Text(
                line,
                style = InfusionTvScaleCalibration.Type.SettingsContent,
                modifier = Modifier.testTag(SettingsTrackingNotSent),
            )
        }
        trackingSelectionNotice(current)?.let { notice ->
            Text(notice, style = InfusionTvScaleCalibration.Type.SettingsContent)
        }

        current.confirmation?.let { confirmation ->
            Text(confirmation, style = InfusionTvScaleCalibration.Type.SettingsContent)
        }
    }

    val selectorState = states.firstOrNull { it != null }
    if (selectionSelectorOpen && selectorState != null && holders.isNotEmpty()) {

        val choices = listOf(TrackingSelection.OnDeviceOnly) +
            selectorState.selectableTrackers.map { TrackingSelection.Tracker(it.trackerId) }
        GeneralSelectorDialog(
            rowTag = SettingsTrackingSelection,
            choices = choices,
            current = selectorState.selection,
            choiceKey = { selection -> trackingSelectionChoiceStorageKey(selection) },
            choiceLabel = { selection ->
                trackingSelectionChoiceLabel(selection, selectorState)
            },
            onSelect = { chosen ->

                holders.first().select(chosen)
                selectionSelectorOpen = false
            },
            onDismiss = { selectionSelectorOpen = false },
        )
    }

    val overlayIndex = overlayProviderId?.let { providerIds.indexOf(it) } ?: -1
    val overlayState = states.getOrNull(overlayIndex)
    if (overlayIndex >= 0 && overlayState != null) {
        TrackingAuthorizationOverlay(
            state = overlayState,
            onDismiss = {

                overlayProviderId = null
                holders[overlayIndex].cancel()
            },
        )
    }
}

@Composable
private fun TrackingAuthorizationOverlay(
    state: TrackingSettingsUiState,
    onDismiss: () -> Unit,
) {
    val actionFocus = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }
    fun requestInitialFocus() {
        if (actionFocus.requestFocus()) initialFocusRequested = true
    }

    LaunchedEffect(Unit) {
        repeat(30) {
            if (initialFocusRequested) return@LaunchedEffect
            withFrameNanos { }
            requestInitialFocus()
        }
    }

    val dismiss = { if (!state.busy) onDismiss() }

    BackHandler(enabled = true) { dismiss() }
    val authorization = state.awaitingApproval
    val awaiting = authorization != null
    Dialog(
        onDismissRequest = { dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .width(TrackingOverlayWidth)
                .onGloballyPositioned {

                    if (!initialFocusRequested) requestInitialFocus()
                }
                .border(
                    width = InfusionTvContour.SelectedWidth,
                    color = LocalInfusionAccent.current,
                    shape = InfusionTvContour.Shape,
                )
                .testTag(SettingsTrackingOverlay),
            shape = InfusionTvContour.Shape,
            colors = SurfaceDefaults.colors(containerColor = InfusionTvAppearance.Panel),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = TrackingOverlayMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Back -> {
                                dismiss()
                                true
                            }

                            Key.DirectionUp, Key.DirectionDown -> true
                            else -> false
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Connect ${state.providerName}",
                    style = InfusionTvScaleCalibration.Type.Headline,
                )
                if (authorization != null) {
                    Text(
                        "Open this address and enter the code:",
                        style = InfusionTvScaleCalibration.Type.SettingsContent,
                    )

                    Text(
                        authorization.verificationUri,
                        style = InfusionTvScaleCalibration.Type.SettingsContent,
                        modifier = Modifier.testTag(SettingsTrackingAuthorizationUrl),
                    )
                    Text(
                        "Code: ${authorization.userCode}",
                        style = InfusionTvScaleCalibration.Type.Headline,
                        modifier = Modifier.testTag(SettingsTrackingCode),
                    )
                    val qrContent = authorization.verificationUriComplete ?: authorization.verificationUri
                    val qr = remember(qrContent) {
                        runCatching { dev.infusion.feature.pairing.PairingQrEncoder().encode(qrContent) }.getOrNull()
                    }
                    if (qr != null) {
                        val bitmap = remember(qr) { qr.toDisplayBitmap(scale = 5) }
                        androidx.compose.foundation.Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "${state.providerName} authorization QR code",
                            modifier = Modifier
                                .size(200.dp)
                                .testTag(SettingsTrackingQr),
                        )
                    }
                }
                val statusLine = trackingOverlayStatusText(state)
                if (statusLine != null) {
                    Text(
                        statusLine,
                        style = InfusionTvScaleCalibration.Type.SettingsContent,
                        modifier = Modifier.testTag(SettingsTrackingOverlayStatus),
                    )
                }
                state.error?.let { message ->
                    Text(
                        message,
                        style = InfusionTvScaleCalibration.Type.SettingsContent,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                InfusionTvButton(
                    onClick = dismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SettingsTrackingOverlayAction)
                        .focusRequester(actionFocus),
                ) {
                    Text(if (awaiting || state.busy) "Cancel" else "Close")
                }
            }
        }
    }
}

internal fun trackingOverlayStatusText(state: TrackingSettingsUiState): String? {
    val authorization = state.awaitingApproval
    return when {
        state.busy -> "Requesting a code from ${state.providerName}…"
        authorization != null ->
            "Waiting for your approval. The code expires at " +
                trackingOverlayExpiryText(authorization.expiresAt) + "."
        state.isConnected -> "The ${state.providerName} account is connected."
        else -> null
    }
}

private fun trackingOverlayExpiryText(expiresAt: java.time.Instant): String =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        .withZone(java.time.ZoneOffset.UTC)
        .format(expiresAt) + " UTC"

@Composable
private fun TrackingProviderRow(

    label: String,

    value: String,

    help: String,

    tag: String,

    statusTag: String,

    entryKey: String?,
    available: Boolean,
    focusRequester: FocusRequester,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onClick: () -> Unit,
) {
    if (!available) {
        requesters.remove(tag)
        entryKey?.let { requesters.remove(it) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = GeneralRowHeight)
                .testTag(tag)
                .semantics {
                    role = Role.Button
                    disabled()
                },
            contentAlignment = Alignment.Center,
        ) {
            SettingsRowBody(
                presentation = SettingsRowPresentation.EXPANDED,
                label = label,

                help = null,
                value = value,
                focused = false,
                valueModifier = Modifier.testTag(statusTag),
                valueColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            )
        }
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    requesters[tag] = focusRequester
    entryKey?.let { requesters[it] = focusRequester }
    InfusionTvButton(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = GeneralRowHeight)
            .testTag(tag)
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (state.isFocused) onFocused(tag)
            }
            .then(settingsRowBringIntoView(focused)),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        SettingsRowBody(
            presentation = SettingsRowPresentation.EXPANDED,
            label = label,
            help = help,
            value = value,
            focused = focused,
            valueModifier = Modifier.testTag(statusTag),
            valueColor = infusionAccentContentColor(focused),
        )
    }
}

internal fun trackingActionLabel(state: TrackingSettingsUiState): String = when (state.connection) {
    is TrackingConnectionState.Connected -> "Disconnect"
    is TrackingConnectionState.AwaitingApproval -> "Cancel"
    else -> "Connect"
}

internal fun trackingActionAvailable(state: TrackingSettingsUiState): Boolean = when (state.connection) {
    is TrackingConnectionState.Connected -> true
    is TrackingConnectionState.AwaitingApproval -> true
    else -> state.clientRegistered
}

internal fun trackingSelectionChoiceStorageKey(selection: TrackingSelection): String = when (selection) {
    TrackingSelection.OnDeviceOnly -> TrackingSelectionOnDeviceOnlyKey
    is TrackingSelection.Tracker -> selection.trackerId
}

internal fun trackingSelectionChoiceLabel(
    selection: TrackingSelection,
    state: TrackingSettingsUiState,
): String = when (selection) {
    TrackingSelection.OnDeviceOnly -> TRACKING_LOCAL_LABEL
    is TrackingSelection.Tracker -> trackingTrackerLabel(
        selection.trackerId,
        state.trackers.firstOrNull { it.trackerId == selection.trackerId }?.displayName,
    )
}

internal fun trackingSelectionRowHelp(state: TrackingSettingsUiState): String {
    val recorded = state.selection
    if (recorded !is TrackingSelection.Tracker) {
        return trackingSelectionSubtitle(recorded, state.trackers, state.providerName)
    }
    val alwaysOn = TRACKING_THIS_DEVICE_SUBTITLE
    if (state.selectedTrackerUnavailable) {
        val name = state.selectedTracker?.displayName ?: state.selectedTrackerId.orEmpty()
        return "$name is not connected, so nothing is sent to it and nothing is read from it. " +
            "It stays your chosen service.\n\n$alwaysOn"
    }
    return trackingSelectionSubtitle(recorded, state.trackers, state.providerName) + "\n\n" + alwaysOn
}

internal fun trackingProviderRowStatusText(state: TrackingSettingsUiState): String =
    when (val connection = state.connection) {
        is TrackingConnectionState.Connected ->
            if (connection.reconnectRequired) "Reconnect required" else "Connected"
        is TrackingConnectionState.AwaitingApproval -> "Awaiting approval"
        TrackingConnectionState.Disconnected -> when {
            state.busy -> "Connecting…"

            !state.clientRegistered -> "Not connected"
            state.error != null -> "Connection failed"
            else -> "Not connected"
        }
    }

internal fun trackingProviderRowValue(state: TrackingSettingsUiState): String {
    val status = trackingProviderRowStatusText(state)
    if (state.busy && state.connection == TrackingConnectionState.Disconnected) return status
    return "$status · ${trackingActionLabel(state)}"
}

internal fun trackingProviderRowHelp(state: TrackingSettingsUiState): String {
    val name = state.providerName
    val help = when (val connection = state.connection) {
        is TrackingConnectionState.Connected ->
            if (connection.reconnectRequired) {
                SettingsRowHelp.Tracking.reconnectRequired(trackingDateText(connection.refreshExpiresAt))
            } else {
                SettingsRowHelp.Tracking.connected(
                    connection.accountLabel,
                    trackingDateText(connection.refreshExpiresAt),
                )
            }
        is TrackingConnectionState.AwaitingApproval -> SettingsRowHelp.Tracking.awaitingApproval(name)
        TrackingConnectionState.Disconnected ->
            if (state.busy) {
                SettingsRowHelp.Tracking.connecting(name)
            } else {
                SettingsRowHelp.Tracking.notConnected(name)
            }
    }

    val error = state.error
    return if (error == null) help else "$help\n$error"
}

private fun trackingDateText(instant: java.time.Instant?): String? =
    instant?.toString()?.substringBefore('T')

private fun dev.infusion.feature.pairing.QrMatrix.toDisplayBitmap(scale: Int): android.graphics.Bitmap {
    val scaled = size * scale
    val pixels = IntArray(scaled * scaled)
    for (y in 0 until scaled) {
        for (x in 0 until scaled) {
            val dark = get(x / scale, y / scale)
            pixels[y * scaled + x] = if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    return android.graphics.Bitmap.createBitmap(pixels, scaled, scaled, android.graphics.Bitmap.Config.ARGB_8888)
}

@Composable
private fun HomeSectionContent(
    holder: HomeCustomizationStateHolder?,

    upNext: dev.infusion.feature.vertical.home.UpNextSettingsStateHolder? = null,
    expandedGroupIds: Set<String>,
    onExpandedGroupChange: (String, Boolean) -> Unit,
    requesters: MutableMap<String, FocusRequester>,
    entryToken: Int,
    rememberedKey: String?,
    onFocusRequested: (String) -> Unit,
    onReturnToRail: () -> Unit,
) {
    if (holder == null) {
        Text(
            "Home customization unavailable.",
            style = InfusionTvScaleCalibration.Type.SettingsContent,
            modifier = Modifier.padding(24.dp),
        )
        return
    }
    val state by holder.state.collectAsState()

    var focusedControlKey by remember { mutableStateOf<String?>(null) }
    fun reportFocus(key: String) {
        focusedControlKey = key
        onFocusRequested(key)
    }

    val upNextState = upNext?.state?.collectAsState()?.value
    val upNextShown = upNextState?.showUpNext == true
    val focusableKeys = homeFocusableKeys(state, expandedGroupIds, upNextShown, state.showSpotlight)
    val gridNav = HomeGridNav(
        homeRowFocusOrder(state, expandedGroupIds, upNextShown, state.showSpotlight),
    )

    val entryTargetKey = when {
        SettingsHomeSeparateCwSwitch in focusableKeys -> SettingsHomeSeparateCwSwitch
        SettingsHomeSpotlightIntervalRow in focusableKeys -> SettingsHomeSpotlightIntervalRow
        else -> state.groups.firstOrNull()?.let { homeGroupKey(it.addonId) }
    }

    val pendingReorderFocus = remember { PendingReorderFocus() }

    var spotlightSelectorOpen by remember { mutableStateOf(false) }
    var spotlightSelectorWasOpen by remember { mutableStateOf(false) }

    var spotlightSourceSelectorOpen by remember { mutableStateOf(false) }
    var spotlightSourceSelectorWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(spotlightSourceSelectorOpen) {
        val wasOpen = spotlightSourceSelectorWasOpen
        spotlightSourceSelectorWasOpen = spotlightSourceSelectorOpen
        if (wasOpen && !spotlightSourceSelectorOpen) {
            repeat(30) {
                withFrameNanos { }
                if (requesters[SettingsHomeSpotlightSourceRow]?.requestFocus() == true) {
                    return@LaunchedEffect
                }
            }
        }
    }

    var upNextSourceSelectorOpen by remember { mutableStateOf(false) }
    var upNextSourceSelectorWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(upNextSourceSelectorOpen) {
        val wasOpen = upNextSourceSelectorWasOpen
        upNextSourceSelectorWasOpen = upNextSourceSelectorOpen
        if (wasOpen && !upNextSourceSelectorOpen) {
            repeat(30) {
                withFrameNanos { }
                if (requesters[SettingsHomeUpNextSourceRow]?.requestFocus() == true) {
                    return@LaunchedEffect
                }
            }
        }
    }
    LaunchedEffect(spotlightSelectorOpen) {
        val wasOpen = spotlightSelectorWasOpen
        spotlightSelectorWasOpen = spotlightSelectorOpen
        if (wasOpen && !spotlightSelectorOpen) {
            repeat(30) {
                withFrameNanos { }
                if (requesters[SettingsHomeSpotlightIntervalRow]?.requestFocus() == true) {
                    return@LaunchedEffect
                }
            }
        }
    }

    LaunchedEffect(entryToken) {
        if (entryToken <= 0) return@LaunchedEffect
        val target = rememberedKey?.takeIf { it in focusableKeys } ?: entryTargetKey
        if (target != null) requesters[target]?.requestFocus()
    }

    LaunchedEffect(state.selectedOrder, state.groups) {
        val pending = pendingReorderFocus.key ?: return@LaunchedEffect
        val fallback = pendingReorderFocus.fallbackKey
        pendingReorderFocus.key = null
        pendingReorderFocus.fallbackKey = null
        val target = pending.takeIf { it in focusableKeys } ?: fallback
        if (target != null) requesters[target]?.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Column(
            modifier = Modifier

                .fillMaxWidth()

                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val current = focusedControlKey ?: return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp, Key.DirectionDown -> {
                            gridNav.verticalTargetKey(current, event.key)
                                ?.let { target -> requesters[target]?.requestFocus() }
                            true
                        }
                        Key.DirectionRight -> {
                            gridNav.horizontalTargetKey(current, Key.DirectionRight)
                                ?.let { target -> requesters[target]?.requestFocus() }
                            true
                        }
                        Key.DirectionLeft -> {
                            val target = gridNav.horizontalTargetKey(current, Key.DirectionLeft)
                                ?: return@onPreviewKeyEvent false
                            requesters[target]?.requestFocus()
                            true
                        }
                        else -> false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(HomeRowSpacing),
        ) {

            HomePreferenceRow(
                label = "Separate Continue Watching",
                tag = SettingsHomeSeparateCwSwitch,
                help = SettingsRowHelp.Home.SeparateContinueWatching,
                checked = state.separateContinueWatching,
                requesters = requesters,
                onFocused = ::reportFocus,
                onToggle = { holder.setSeparateContinueWatching(!state.separateContinueWatching) },
            )

            HomePreferenceRow(
                label = "Show Spotlight",
                tag = SettingsHomeSpotlightSwitch,
                help = SettingsRowHelp.Home.ShowSpotlight,
                checked = state.showSpotlight,
                requesters = requesters,
                onFocused = ::reportFocus,
                onToggle = { holder.setShowSpotlight(!state.showSpotlight) },
            )

            if (state.showSpotlight) {

                HomeSelectorRow(
                    label = "Spotlight source",
                    value = homeSpotlightSourceLabel(state.spotlightSource, state.spotlightSourceOptions),
                    tag = SettingsHomeSpotlightSourceRow,
                    help = SettingsRowHelp.Home.SpotlightSource,
                    requesters = requesters,
                    onFocused = ::reportFocus,
                    onOpen = { spotlightSourceSelectorOpen = true },
                )

                state.spotlightSourceNote?.let { note ->
                    Text(note, style = InfusionTvScaleCalibration.Type.Body)
                }

                HomeSelectorRow(
                    label = "Spotlight Auto-Roll Interval",
                    value = spotlightIntervalValueLabel(state.spotlightAutoRollSeconds),
                    tag = SettingsHomeSpotlightIntervalRow,
                    help = SettingsRowHelp.Home.SpotlightInterval,
                    requesters = requesters,
                    onFocused = ::reportFocus,
                    onOpen = { spotlightSelectorOpen = true },
                )
            }

            if (upNext != null && upNextState != null) {
                HomePreferenceRow(
                    label = "Show Up Next",
                    tag = SettingsHomeUpNextSwitch,
                    help = SettingsRowHelp.Home.ShowUpNext,
                    checked = upNextState.showUpNext,
                    requesters = requesters,
                    onFocused = ::reportFocus,
                    onToggle = { upNext.setShowUpNext(!upNextState.showUpNext) },
                )
                if (upNextShown) {
                    HomeSelectorRow(
                        label = "Up Next Source",
                        value = upNextState.selectedOption?.label ?: "Local only",
                        tag = SettingsHomeUpNextSourceRow,
                        help = SettingsRowHelp.Home.UpNextSource,
                        requesters = requesters,
                        onFocused = ::reportFocus,
                        onOpen = { upNextSourceSelectorOpen = true },
                    )

                    upNextState.unavailableTrackerId?.let { trackerId ->
                        Text(
                            "The $trackerId account is not connected. Up Next is using local " +
                                "progress until it is connected again; your selection is kept.",
                            style = InfusionTvScaleCalibration.Type.Body,
                        )
                    }
                }
            }

            Text(
                "Home rows are created in Settings → Widget Builder. Each widget is an " +
                    "independent Home row, and you can reorder or delete it there.",
                style = InfusionTvScaleCalibration.Type.SettingsContent,
            )
        }
    }

    if (upNextSourceSelectorOpen && upNext != null && upNextState != null) {
        val choices = upNextState.options.filter { it.available }
        if (choices.isNotEmpty()) {
            GeneralSelectorDialog(
                rowTag = SettingsHomeUpNextSourceRow,
                choices = choices,

                current = choices.firstOrNull { it.source == upNextState.effective } ?: choices.first(),
                choiceKey = { option -> upNextSourceChoiceKey(option.source) },
                choiceLabel = { option -> option.label },
                onSelect = { option ->
                    upNext.select(option.source)
                    upNextSourceSelectorOpen = false
                },
                onDismiss = { upNextSourceSelectorOpen = false },
            )
        } else {

            LaunchedEffect(Unit) { upNextSourceSelectorOpen = false }
        }
    }

    if (spotlightSourceSelectorOpen) {
        val choices = spotlightSourceChoices(state.spotlightSourceOptions)
        GeneralSelectorDialog(
            rowTag = SettingsHomeSpotlightSourceRow,
            choices = choices,

            current = choices.firstOrNull { it == state.spotlightSource } ?: choices.first(),
            choiceKey = ::spotlightSourceChoiceKey,
            choiceLabel = { source -> homeSpotlightSourceLabel(source, state.spotlightSourceOptions) },
            choiceSupportingText = { source ->
                (source as? HomeSpotlightSource.Row)
                    ?.let { row -> state.spotlightSourceOptions.firstOrNull { it.rowId == row.rowId } }
                    ?.context
            },
            onSelect = { chosen ->
                holder.setSpotlightSource(chosen)
                spotlightSourceSelectorOpen = false
            },
            onDismiss = { spotlightSourceSelectorOpen = false },
            panelWidth = SpotlightSourceSelectorWidth,
            panelMaxHeight = SpotlightSourceSelectorMaxHeight,
            scrollIndicators = true,
        )
    }

    if (spotlightSelectorOpen) {
        GeneralSelectorDialog(
            rowTag = SettingsHomeSpotlightIntervalRow,
            choices = SpotlightAutoRollIntervals,

            current = state.spotlightAutoRollSeconds,
            choiceKey = { seconds -> seconds.toString() },
            choiceLabel = { seconds -> spotlightIntervalChoiceLabel(seconds) },
            onSelect = { chosen ->
                holder.setSpotlightAutoRollSeconds(chosen)
                spotlightSelectorOpen = false
            },
            onDismiss = { spotlightSelectorOpen = false },
        )
    }
}

@Composable
private fun HomePreferenceRow(
    label: String,
    tag: String,
    checked: Boolean,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onToggle: () -> Unit,

    help: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HomeRowHeight),
        horizontalArrangement = Arrangement.spacedBy(HomeRowSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomePrimaryCell(
            label = label,
            tag = tag,
            requesters = requesters,
            onFocused = onFocused,
            onClick = onToggle,
            modifier = Modifier.weight(1f),
            rightEdge = true,
            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off,
            help = help,
            trailingFocused = { focused ->
                Spacer(Modifier.width(SettingsRowPresentationMetrics.ValueGap))
                HomePreferenceStateValue(
                    tag = homePreferenceStateKey(tag),
                    checked = checked,
                    focused = focused,
                )
            },
        )
    }
}

@Composable
private fun HomePreferenceStateValue(tag: String, checked: Boolean, focused: Boolean) {
    Box(
        modifier = Modifier
            .testTag(tag)
            .semantics(mergeDescendants = true) {
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            },
    ) {
        Text(
            text = homePreferenceStateText(checked),
            style = InfusionTvScaleCalibration.Type.SettingsContent,
            color = homePresentationValueColor(focused, LocalInfusionAccent.current),
        )
    }
}

internal fun homePresentationValueColor(focused: Boolean, accent: Color): Color =
    if (focused) InfusionTvContour.FocusedContentColor else accent

internal fun homePreferenceStateText(checked: Boolean): String = if (checked) "On" else "Off"

@Composable
private fun HomeSelectorRow(
    label: String,
    value: String,
    tag: String,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onOpen: () -> Unit,

    help: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HomeRowHeight),
        horizontalArrangement = Arrangement.spacedBy(HomeRowSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomePrimaryCell(
            label = label,
            tag = tag,
            requesters = requesters,
            onFocused = onFocused,
            onClick = onOpen,
            modifier = Modifier.weight(1f),
            rightEdge = true,
            help = help,
            trailingFocused = { focused ->
                Text(
                    value,
                    style = InfusionTvScaleCalibration.Type.SettingsContent,
                    color = homePresentationValueColor(focused, LocalInfusionAccent.current),
                )
                Spacer(Modifier.width(12.dp))
                Text("›", style = InfusionTvScaleCalibration.Type.SettingsContent)
            },
        )
    }
}

@Composable
internal fun HomePrimaryCell(
    label: String,
    tag: String,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    rightEdge: Boolean = false,
    toggleableState: ToggleableState? = null,

    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,

    trailingFocused: (@Composable (focused: Boolean) -> Unit)? = null,

    help: String? = null,
) {
    val requester = remember(tag) { FocusRequester() }
    requesters[tag] = requester
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    InfusionTvButton(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .then(
                if (help != null) {
                    Modifier.heightIn(min = HomeRowHeight)
                } else {
                    Modifier.height(HomeRowHeight)
                },
            )
            .then(if (rightEdge) Modifier.homeRowRightEdge() else Modifier)
            .testTag(tag)
            .focusRequester(requester)
            .onFocusChanged { focusState ->
                if (focusState.isFocused) onFocused(tag)
            }
            .semantics {
                if (toggleableState != null) {
                    role = Role.Switch
                    this.toggleableState = toggleableState
                }
            }
            .then(if (help != null) settingsRowBringIntoView(focused) else Modifier),
        contentPadding = PaddingValues(horizontal = HomeRowCellPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) leading()
            SettingsRowLabelBlock(
                label = label,
                help = help,
                focused = focused,

                maxLines = if (help != null) 1 else Int.MAX_VALUE,
                modifier = Modifier.weight(1f),

                labelColor = if (dimmed) LocalContentColor.current.copy(alpha = 0.6f) else Color.Unspecified,
            )
            if (trailingFocused != null) trailingFocused.invoke(focused) else trailing?.invoke()
        }
    }
}

@Composable
internal fun HomeStateToggle(
    tag: String,
    checked: Boolean,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onToggle: () -> Unit,

    rightEdge: Boolean = true,
) {
    val requester = remember(tag) { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val treatment = homeStateCellTreatment(HomeStateCell.VISIBILITY, checked)
    requesters[tag] = requester
    InfusionTvButton(
        onClick = onToggle,
        interactionSource = interactionSource,

        border = infusionTvButtonBorder(selected = treatment.accentFrame),
        modifier = Modifier
            .width(HomeRowToggleWidth)
            .height(HomeRowHeight)
            .then(if (rightEdge) Modifier.homeRowRightEdge() else Modifier)
            .testTag(tag)
            .focusRequester(requester)
            .onFocusChanged { focusState ->
                if (focusState.isFocused) onFocused(tag)
            }
            .semantics {
                role = Role.Switch
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            },
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        HomeStateLabel(checked, accent = treatment.accentLabel, focused = focused)
    }
}

internal fun homeStateCellTreatment(cell: HomeStateCell, on: Boolean): HomeStateCellTreatment =
    when (cell) {
        HomeStateCell.PREFERENCE ->
            HomeStateCellTreatment(accentFrame = false, accentLabel = false)
        HomeStateCell.CATALOG ->
            HomeStateCellTreatment(accentFrame = on, accentLabel = on)
        HomeStateCell.VISIBILITY ->
            HomeStateCellTreatment(accentFrame = true, accentLabel = on)
    }

internal enum class HomeStateCell {

    PREFERENCE,

    CATALOG,

    VISIBILITY,
}

internal data class HomeStateCellTreatment(
    val accentFrame: Boolean,
    val accentLabel: Boolean,
)

@Composable
internal fun HomeStateBadge(tag: String, checked: Boolean, cell: HomeStateCell) {
    val treatment = homeStateCellTreatment(cell, checked)
    Box(
        modifier = Modifier
            .width(HomeRowToggleWidth)
            .height(HomeRowHeight)
            .background(
                color = InfusionTvContour.ControlContainerColor,
                shape = InfusionTvContour.Shape,
            )
            .then(infusionSelectionBorder(visible = treatment.accentFrame))
            .testTag(tag)
            .semantics {
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            },
        contentAlignment = Alignment.Center,
    ) {
        HomeStateLabel(checked, accent = treatment.accentLabel)
    }
}

@Composable
private fun HomeStateLabel(checked: Boolean, accent: Boolean, focused: Boolean = false) {
    Text(
        text = if (checked) "ON" else "OFF",
        style = InfusionTvScaleCalibration.Type.SettingsContent,
        color = if (accent) infusionAccentContentColor(focused) else Color.Unspecified,
    )
}

@Composable
private fun HomeArrowCell(
    glyph: String,
    tag: String,
    enabled: Boolean,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onMove: () -> Unit,
    rightEdge: Boolean = false,
) {
    if (!enabled) {
        HomeDisabledCell(glyph = glyph, tag = tag, width = HomeRowActionWidth)
        return
    }
    val requester = remember(tag) { FocusRequester() }
    requesters[tag] = requester
    InfusionTvButton(
        onClick = onMove,
        modifier = Modifier
            .width(HomeRowActionWidth)
            .height(HomeRowHeight)
            .then(if (rightEdge) Modifier.homeRowRightEdge() else Modifier)
            .testTag(tag)
            .focusRequester(requester)
            .onFocusChanged { focusState ->
                if (focusState.isFocused) onFocused(tag)
            },
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        HomeDirectionGlyph(glyph)
    }
}

@Composable
private fun HomeDisabledCell(glyph: String, tag: String, width: Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(HomeRowHeight)
            .testTag(tag)
            .semantics { disabled() },
        contentAlignment = Alignment.Center,
    ) {
        HomeDirectionGlyph(glyph, dimmed = true)
    }
}

@Composable
private fun HomeDirectionGlyph(glyph: String, dimmed: Boolean = false) {
    Text(
        glyph,
        style = InfusionTvScaleCalibration.Type.SettingsContent,
        color = if (dimmed) {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        } else {
            Color.Unspecified
        },
    )
}

private fun Modifier.homeRowRightEdge(): Modifier =
    focusProperties { right = FocusRequester.Cancel }

@Composable
private fun HomeProviderGroup(
    group: HomeCatalogGroupUi,
    expanded: Boolean,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onToggleCollapsed: () -> Unit,
    onToggleVisibility: () -> Unit,
    onMove: (Int) -> Unit,
    onToggleCatalog: (HomeCatalogOptionUi) -> Unit,
    onMoveCatalog: (HomeCatalogOptionUi, Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(HomeRowSpacing)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HomeRowHeight),
            horizontalArrangement = Arrangement.spacedBy(HomeRowSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeProviderCell(
                modifier = Modifier.weight(1f),
                group = group,
                expanded = expanded,
                requesters = requesters,
                onFocused = onFocused,
                onToggleCollapsed = onToggleCollapsed,
            )
            HomeArrowCell(
                glyph = "▲",
                tag = homeGroupMoveUpKey(group.addonId),
                enabled = providerCanMoveUp(group),
                requesters = requesters,
                onFocused = onFocused,
                onMove = { onMove(-1) },
                rightEdge = !providerCanMoveDown(group),
            )
            HomeArrowCell(
                glyph = "▼",
                tag = homeGroupMoveDownKey(group.addonId),
                enabled = providerCanMoveDown(group),
                requesters = requesters,
                onFocused = onFocused,
                onMove = { onMove(1) },
                rightEdge = true,
            )
            HomeStateToggle(
                tag = homeGroupShownKey(group.addonId),
                checked = !group.hiddenFromHome,
                requesters = requesters,
                onFocused = onFocused,
                onToggle = onToggleVisibility,
            )
        }
        if (expanded) {
            group.catalogs.forEach { option ->

                key(option.key.shelfKey) {
                    HomeCatalogRow(
                        option = option,
                        requesters = requesters,
                        onFocused = onFocused,
                        onToggle = { onToggleCatalog(option) },
                        onMove = { delta -> onMoveCatalog(option, delta) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeProviderCell(
    modifier: Modifier = Modifier,
    group: HomeCatalogGroupUi,
    expanded: Boolean,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onToggleCollapsed: () -> Unit,
) {
    HomePrimaryCell(
        label = group.addonDisplayName,
        tag = homeGroupKey(group.addonId),
        requesters = requesters,
        onFocused = onFocused,
        onClick = onToggleCollapsed,
        modifier = modifier,
        dimmed = group.hiddenFromHome,
        help = SettingsRowHelp.Home.ProviderGroup,
        trailing = {
            Text(
                if (expanded) "▼" else "▶",
                style = InfusionTvScaleCalibration.Type.SettingsContent,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        },
    )
}

@Composable
private fun HomeCatalogRow(
    option: HomeCatalogOptionUi,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onToggle: () -> Unit,
    onMove: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HomeRowHeight),
        horizontalArrangement = Arrangement.spacedBy(HomeRowSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomePrimaryCell(
            label = option.displayName,
            tag = homeCatalogRowKey(option.key),
            requesters = requesters,
            onFocused = onFocused,
            onClick = onToggle,
            modifier = Modifier.weight(1f),
            dimmed = !option.selected,
            rightEdge = !option.canMoveUp && !option.canMoveDown,
            toggleableState = if (option.selected) ToggleableState.On else ToggleableState.Off,
            help = SettingsRowHelp.Home.Catalog,
        )
        HomeArrowCell(
            glyph = "▲",
            tag = homeCatalogMoveUpKey(option.key),
            enabled = option.canMoveUp,
            requesters = requesters,
            onFocused = onFocused,
            onMove = { onMove(-1) },
            rightEdge = !option.canMoveDown,
        )
        HomeArrowCell(
            glyph = "▼",
            tag = homeCatalogMoveDownKey(option.key),
            enabled = option.canMoveDown,
            requesters = requesters,
            onFocused = onFocused,
            onMove = { onMove(1) },
            rightEdge = true,
        )
        HomeStateBadge(
            tag = homeCatalogShownKey(option.key),
            checked = option.selected,
            cell = HomeStateCell.CATALOG,
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun LabeledField(
    label: String,
    value: String,
    secret: Boolean = false,
    onChange: (String) -> Unit,
    testTag: String = "",
    focusRequester: FocusRequester? = null,
    onDpadUp: (() -> Unit)? = null,
    onDpadDown: (() -> Unit)? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    imeAction: ImeAction = ImeAction.Default,
    onImeNext: (() -> Unit)? = null,
    onImeSubmit: (() -> Unit)? = null,
) {
    Column {

        InfusionTvFieldLabel(label, style = InfusionTvScaleCalibration.Type.SettingsContent)

        val textState = rememberTextFieldState()

        LaunchedEffect(value) {
            if (textState.text.toString() != value) {
                textState.setTextAndPlaceCursorAtEnd(value)
            }
        }

        LaunchedEffect(textState) {
            snapshotFlow { textState.text.toString() }.collect(onChange)
        }
        val keyboardController = LocalSoftwareKeyboardController.current
        val imeVisible = WindowInsets.isImeVisible
        val hasImeAction = imeAction != ImeAction.Default && (onImeNext != null || onImeSubmit != null)
        InfusionTvStateTextField(
            state = textState,
            secret = secret,

            textStyle = InfusionTvScaleCalibration.Type.SettingsContent,

            keyboardOptions = if (hasImeAction) KeyboardOptions(imeAction = imeAction) else KeyboardOptions.Default,
            onKeyboardAction = if (hasImeAction) {
                { _ ->
                    if (imeAction == ImeAction.Next) {

                        onImeNext?.invoke()
                    } else {

                        onImeSubmit?.invoke()
                        keyboardController?.hide()
                    }
                }
            } else {
                null
            },
            modifier = Modifier.fieldModifier(
                testTag = testTag,
                focusRequester = focusRequester,
                imeVisible = imeVisible,
                onDpadUp = onDpadUp,
                onDpadDown = onDpadDown,
                onFocusChanged = onFocusChanged,
            ),
        )
    }
}

@Composable
private fun Modifier.fieldModifier(
    testTag: String,
    focusRequester: FocusRequester?,
    imeVisible: Boolean,
    onDpadUp: (() -> Unit)?,
    onDpadDown: (() -> Unit)?,
    onFocusChanged: ((Boolean) -> Unit)? = null,
): Modifier {

    val ambientPressOwner = LocalDirectionalPressOwnership.current
    val pressOwner = ambientPressOwner ?: remember { DirectionalPressOwnership() }
    return this
        .fillMaxWidth()
        .then(if (testTag.isNotBlank()) Modifier.testTag(testTag) else Modifier)
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .then(
            if (onFocusChanged != null) {
                Modifier.onFocusChanged { state ->
                    onFocusChanged(state.isFocused)
                }
            } else {
                Modifier
            },
        )
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val down = event.key == Key.DirectionDown && onDpadDown != null
            val up = event.key == Key.DirectionUp && onDpadUp != null
            if (!down && !up) return@onPreviewKeyEvent false

            if (!pressOwner.claim(event.nativeKeyEvent.downTime, event.nativeKeyEvent.repeatCount)) {
                return@onPreviewKeyEvent true
            }

            if (imeVisible) return@onPreviewKeyEvent false
            if (down) onDpadDown?.invoke() else onDpadUp?.invoke()
            true
        }
}

@Preview
@Composable
private fun TvSettingsScreenPreview() {
    val model = remember { TvTopNavModel() }
    InfusionTvTheme {
        TvSettingsScreen(
            model = model,
            jellyfinConnected = false,
            onNavigate = {},
            onExit = {},
        )
    }
}

const val SettingsRatingsPane = "settings:ratings:pane"

const val SettingsRatingsOmdbKey = "settings:ratings:omdb:key"

const val SettingsRatingsOmdbField = "settings:ratings:omdb:field"

const val SettingsRatingsAppearance = "settings:ratings:appearance"

const val SettingsRatingsAdvanced = "settings:ratings:advanced"

fun ratingsSupplierToggleKey(id: String): String = "settings:ratings:supplier:$id:toggle"

fun ratingsSupplierUpKey(id: String): String = "settings:ratings:supplier:$id:up"

fun ratingsSupplierDownKey(id: String): String = "settings:ratings:supplier:$id:down"

fun ratingsMetricToggleKey(id: String): String = "settings:ratings:metric:$id"

fun ratingsEpisodeSourceToggleKey(id: String): String = "settings:ratings:episode-source:$id:toggle"

fun ratingsEpisodeSourceUpKey(id: String): String = "settings:ratings:episode-source:$id:up"

fun ratingsEpisodeSourceDownKey(id: String): String = "settings:ratings:episode-source:$id:down"

internal fun ratingsOrderedSuppliers(order: List<RatingSupplier>): List<RatingSupplier> {
    val seen = LinkedHashSet<RatingSupplier>()
    order.forEach { if (it.configurable) seen.add(it) }
    RatingSupplier.Configurable.forEach { seen.add(it) }
    return seen.toList()
}

internal fun ratingsSupplierInRowKeys(): Set<String> =
    RatingSupplier.Configurable.flatMap { listOf(ratingsSupplierUpKey(it.id), ratingsSupplierDownKey(it.id)) }
        .toSet()

internal fun ratingsEpisodeInRowKeys(): Set<String> =
    RatingMetric.SortableEpisodeSources.flatMap {
        listOf(ratingsEpisodeSourceUpKey(it.id), ratingsEpisodeSourceDownKey(it.id))
    }.toSet()

internal fun ratingsEpisodeOrderedSources(order: List<RatingMetric>): List<RatingMetric> {
    val seen = LinkedHashSet<RatingMetric>()
    order.forEach { if (it in RatingMetric.SortableEpisodeSources) seen.add(it) }
    RatingMetric.SortableEpisodeSources.forEach { seen.add(it) }
    return seen.toList()
}

internal fun ratingsAppearanceLabel(appearance: RatingAppearance): String = when (appearance) {
    RatingAppearance.TEXT -> "Text"
    RatingAppearance.ABBREVIATED_TEXT -> "Abbreviated text"
    RatingAppearance.COLOUR_LOGOS -> "Colour icons"
    RatingAppearance.MONOCHROME_LOGOS -> "Monochrome icons"
}

internal fun omdbTestMessage(outcome: OmdbTestOutcome): String = when (outcome) {
    OmdbTestOutcome.Valid -> "Key accepted — OMDb responded with a usable title."
    OmdbTestOutcome.MissingKey -> "No key to test — enter your OMDb API key."
    OmdbTestOutcome.InvalidKey -> "Invalid API key — OMDb rejected the key."
    OmdbTestOutcome.QuotaExhausted -> "OMDb quota reached — try again later."
    is OmdbTestOutcome.Unreachable -> "Could not reach OMDb (${outcome.reason})."
}

private fun ratingsFocusRows(
    preferences: RatingsPreferences,
    advancedOpen: Boolean,
): List<List<HomeFocusCell>> {
    val suppliers = ratingsOrderedSuppliers(preferences.supplierOrder)
    val episodeSources = ratingsEpisodeOrderedSources(preferences.episodeSourceOrder)
    return buildList {
        add(listOf(HomeFocusCell(column = 0, key = SettingsRatingsAppearance)))
        RatingMetric.entries.forEach { metric ->
            add(listOf(HomeFocusCell(column = 0, key = ratingsMetricToggleKey(metric.id))))
        }

        episodeSources.forEachIndexed { index, source ->
            val row = mutableListOf(HomeFocusCell(column = 0, key = ratingsEpisodeSourceToggleKey(source.id)))
            if (index > 0) row += HomeFocusCell(column = 1, key = ratingsEpisodeSourceUpKey(source.id))
            if (index < episodeSources.lastIndex) {
                row += HomeFocusCell(column = 2, key = ratingsEpisodeSourceDownKey(source.id))
            }
            add(row)
        }
        add(listOf(HomeFocusCell(column = 0, key = SettingsRatingsOmdbKey)))
        add(listOf(HomeFocusCell(column = 0, key = SettingsRatingsAdvanced)))

        if (advancedOpen) {
            suppliers.forEachIndexed { index, supplier ->
                val row = mutableListOf(HomeFocusCell(column = 0, key = ratingsSupplierToggleKey(supplier.id)))
                if (index > 0) row += HomeFocusCell(column = 1, key = ratingsSupplierUpKey(supplier.id))
                if (index < suppliers.lastIndex) {
                    row += HomeFocusCell(column = 2, key = ratingsSupplierDownKey(supplier.id))
                }
                add(row)
            }
        }
    }
}

@Composable
private fun RatingsSupplierRow(
    supplier: RatingSupplier,
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    available: Boolean,

    showArrows: Boolean,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(

        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HomeRowSpacing),
    ) {
        GeneralToggleRow(
            label = if (available) supplier.displayName else "${supplier.displayName} (not configured)",
            value = if (enabled) "On" else "Off",
            tag = ratingsSupplierToggleKey(supplier.id),
            requesters = requesters,
            onFocused = onFocused,
            onToggle = onToggle,
            modifier = Modifier.weight(1f),

            rightEdge = !showArrows,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Ratings.SupplierPriority,
        )
        if (showArrows) {
            HomeArrowCell(
                glyph = "▲",
                tag = ratingsSupplierUpKey(supplier.id),
                enabled = canMoveUp,
                requesters = requesters,
                onFocused = onFocused,
                onMove = onMoveUp,
                rightEdge = !canMoveDown,
            )
            HomeArrowCell(
                glyph = "▼",
                tag = ratingsSupplierDownKey(supplier.id),
                enabled = canMoveDown,
                requesters = requesters,
                onFocused = onFocused,
                onMove = onMoveDown,
                rightEdge = true,
            )
        }
    }
}

@Composable
private fun RatingsEpisodeSourceRow(
    source: RatingMetric,
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HomeRowSpacing),
    ) {
        GeneralToggleRow(
            label = source.chipLabel,
            value = if (enabled) "On" else "Off",
            tag = ratingsEpisodeSourceToggleKey(source.id),
            requesters = requesters,
            onFocused = onFocused,
            onToggle = onToggle,
            modifier = Modifier.weight(1f),
            rightEdge = false,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Ratings.EpisodeSource,
        )
        HomeArrowCell(
            glyph = "▲",
            tag = ratingsEpisodeSourceUpKey(source.id),
            enabled = canMoveUp,
            requesters = requesters,
            onFocused = onFocused,
            onMove = onMoveUp,
            rightEdge = !canMoveDown,
        )
        HomeArrowCell(
            glyph = "▼",
            tag = ratingsEpisodeSourceDownKey(source.id),
            enabled = canMoveDown,
            requesters = requesters,
            onFocused = onFocused,
            onMove = onMoveDown,
            rightEdge = true,
        )
    }
}

@Composable
private fun RatingsSectionContent(
    holder: RatingsSettingsStateHolder?,
    requesters: MutableMap<String, FocusRequester>,
    entryToken: Int,
    rememberedKey: String?,
    onFocusRequested: (String) -> Unit,
    onReturnToRail: () -> Unit,
) {
    val uiState: RatingsSettingsUiState? = holder?.state?.collectAsState()?.value

    RestoreSectionFocus(entryToken, rememberedKey, SettingsRatingsAppearance, requesters)
    LaunchedEffect(holder) { holder?.refresh() }

    var omdbOverlayOpen by remember { mutableStateOf(false) }
    var omdbOverlayWasOpen by remember { mutableStateOf(false) }

    LaunchedEffect(omdbOverlayOpen) {
        val wasOpen = omdbOverlayWasOpen
        omdbOverlayWasOpen = omdbOverlayOpen
        if (wasOpen && !omdbOverlayOpen) {
            repeat(30) {
                withFrameNanos { }
                if (requesters[SettingsRatingsOmdbKey]?.requestFocus() == true) return@LaunchedEffect
            }
        }
    }
    var advancedOpen by remember { mutableStateOf(false) }
    var appearanceSelectorOpen by remember { mutableStateOf(false) }
    var appearanceSelectorWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(appearanceSelectorOpen) {
        val wasOpen = appearanceSelectorWasOpen
        appearanceSelectorWasOpen = appearanceSelectorOpen
        if (wasOpen && !appearanceSelectorOpen) {
            repeat(30) {
                withFrameNanos { }
                if (requesters[SettingsRatingsAppearance]?.requestFocus() == true) return@LaunchedEffect
            }
        }
    }

    var advancedWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(advancedOpen) {
        val wasOpen = advancedWasOpen
        advancedWasOpen = advancedOpen
        if (wasOpen && !advancedOpen) {
            repeat(30) {
                withFrameNanos { }
                if (requesters[SettingsRatingsAdvanced]?.requestFocus() == true) return@LaunchedEffect
            }
        }
    }

    if (uiState == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .testTag(SettingsRatingsPane),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Ratings", style = InfusionTvScaleCalibration.Type.Headline)
            Text("Ratings setup unavailable.", style = InfusionTvScaleCalibration.Type.SettingsContent)
        }
        return
    }

    val activeHolder = holder ?: return
    val state = uiState ?: return
    val preferences = state.preferences
    val orderedSuppliers = remember(preferences.supplierOrder) {
        ratingsOrderedSuppliers(preferences.supplierOrder)
    }
    val orderedMetrics = remember { RatingMetric.entries.toList() }

    val focusRows = remember(preferences.supplierOrder, preferences.episodeSourceOrder, advancedOpen) {
        ratingsFocusRows(preferences, advancedOpen)
    }
    val gridNav = remember(focusRows) { HomeGridNav(focusRows) }
    val focusableKeys = remember(focusRows) {
        focusRows.flatten().mapTo(mutableSetOf()) { cell -> cell.key }
    }
    var focusedControlKey by remember { mutableStateOf(SettingsRatingsAppearance) }

    var pendingSupplierFocus by remember { mutableStateOf<String?>(null) }
    var pendingSupplierFallback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(preferences.supplierOrder) {
        val pending = pendingSupplierFocus ?: return@LaunchedEffect
        val fallback = pendingSupplierFallback
        pendingSupplierFocus = null
        pendingSupplierFallback = null
        val target = pending.takeIf { it in focusableKeys } ?: fallback
        if (target != null) requesters[target]?.requestFocus()
    }

    var pendingEpisodeFocus by remember { mutableStateOf<String?>(null) }
    var pendingEpisodeFallback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(preferences.episodeSourceOrder) {
        val pending = pendingEpisodeFocus ?: return@LaunchedEffect
        val fallback = pendingEpisodeFallback
        pendingEpisodeFocus = null
        pendingEpisodeFallback = null
        val target = pending.takeIf { it in focusableKeys } ?: fallback
        if (target != null) requesters[target]?.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag(SettingsRatingsPane)

            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val current = focusedControlKey ?: return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp, Key.DirectionDown -> {
                        gridNav.verticalTargetKey(current, event.key)
                            ?.let { target -> requesters[target]?.requestFocus() }
                        true
                    }
                    Key.DirectionRight -> {
                        gridNav.horizontalTargetKey(current, Key.DirectionRight)
                            ?.let { target -> requesters[target]?.requestFocus() }
                        true
                    }
                    Key.DirectionLeft -> {
                        val target = gridNav.horizontalTargetKey(current, Key.DirectionLeft)
                            ?: return@onPreviewKeyEvent false
                        requesters[target]?.requestFocus()
                        true
                    }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ratings", style = InfusionTvScaleCalibration.Type.Headline)
        Text(
            "Community ratings from the sources you choose. These are not your own ratings.",
            style = InfusionTvScaleCalibration.Type.SettingsContent,
        )

        Text("Appearance", style = InfusionTvScaleCalibration.Type.Headline)
        GeneralSettingRow(
            label = "Appearance",
            value = ratingsAppearanceLabel(preferences.appearance),
            tag = SettingsRatingsAppearance,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Ratings.Appearance,
            requesters = requesters,
            onFocused = { key -> focusedControlKey = key; onFocusRequested(key) },
            onOpen = { appearanceSelectorOpen = true },
        )

        Text("Displayed sources", style = InfusionTvScaleCalibration.Type.Headline)
        orderedMetrics.forEach { metric ->
            GeneralToggleRow(
                label = metric.chipLabel,
                value = if (metric in preferences.displayedMetrics) "On" else "Off",
                tag = ratingsMetricToggleKey(metric.id),
                presentation = SettingsRowPresentation.EXPANDED,
                help = SettingsRowHelp.Ratings.DisplayedMetric,
                requesters = requesters,
                onFocused = { key -> focusedControlKey = key; onFocusRequested(key) },
                onToggle = { activeHolder.setMetricDisplayed(metric, metric !in preferences.displayedMetrics) },
            )
        }

        Text("Episode card ratings", style = InfusionTvScaleCalibration.Type.Headline)
        val episodeSources = remember(preferences.episodeSourceOrder) {
            ratingsEpisodeOrderedSources(preferences.episodeSourceOrder)
        }
        episodeSources.forEachIndexed { index, source ->
            RatingsEpisodeSourceRow(
                source = source,
                enabled = preferences.isEpisodeSourceEnabled(source),
                canMoveUp = index > 0,
                canMoveDown = index < episodeSources.lastIndex,
                requesters = requesters,
                onFocused = { key -> focusedControlKey = key; onFocusRequested(key) },
                onToggle = { activeHolder.setEpisodeSourceEnabled(source, !preferences.isEpisodeSourceEnabled(source)) },
                onMoveUp = {
                    pendingEpisodeFocus = ratingsEpisodeSourceUpKey(source.id)
                    pendingEpisodeFallback = ratingsEpisodeSourceToggleKey(source.id)
                    activeHolder.moveEpisodeSource(source, -1)
                },
                onMoveDown = {
                    pendingEpisodeFocus = ratingsEpisodeSourceDownKey(source.id)
                    pendingEpisodeFallback = ratingsEpisodeSourceToggleKey(source.id)
                    activeHolder.moveEpisodeSource(source, 1)
                },
            )
        }

        Text("OMDb", style = InfusionTvScaleCalibration.Type.Headline)
        GeneralSettingRow(
            label = "OMDb API key",
            value = if (state.omdbKeyConfigured) "Configured" else "Not set",
            tag = SettingsRatingsOmdbKey,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Ratings.OmdbKey,
            requesters = requesters,
            onFocused = { key -> focusedControlKey = key; onFocusRequested(key) },
            onOpen = { omdbOverlayOpen = true },
        )

        GeneralSettingRow(
            label = "Advanced",
            value = "Provider priority",
            tag = SettingsRatingsAdvanced,
            presentation = SettingsRowPresentation.EXPANDED,
            help = SettingsRowHelp.Ratings.Advanced,
            requesters = requesters,
            onFocused = { key -> focusedControlKey = key; onFocusRequested(key) },
            onOpen = { advancedOpen = !advancedOpen },
        )

        if (advancedOpen) {
            orderedSuppliers.forEachIndexed { index, supplier ->
                RatingsSupplierRow(
                    supplier = supplier,
                    enabled = supplier in preferences.enabledSuppliers,
                    canMoveUp = index > 0,
                    canMoveDown = index < orderedSuppliers.lastIndex,
                    available = supplier in state.availableSuppliers,
                    showArrows = true,
                    requesters = requesters,
                    onFocused = { key -> focusedControlKey = key; onFocusRequested(key) },
                    onToggle = { activeHolder.setSupplierEnabled(supplier, supplier !in preferences.enabledSuppliers) },
                    onMoveUp = {
                        pendingSupplierFocus = ratingsSupplierUpKey(supplier.id)
                        pendingSupplierFallback = ratingsSupplierToggleKey(supplier.id)
                        activeHolder.moveSupplier(supplier, -1)
                    },
                    onMoveDown = {
                        pendingSupplierFocus = ratingsSupplierDownKey(supplier.id)
                        pendingSupplierFallback = ratingsSupplierToggleKey(supplier.id)
                        activeHolder.moveSupplier(supplier, 1)
                    },
                )
            }
        }
    }

    if (appearanceSelectorOpen) {
        GeneralSelectorDialog(
            rowTag = SettingsRatingsAppearance,
            choices = RatingAppearance.entries.toList(),
            current = preferences.appearance,
            choiceKey = { it.name },
            choiceLabel = { ratingsAppearanceLabel(it) },
            onSelect = { activeHolder.setAppearance(it); appearanceSelectorOpen = false },
            onDismiss = { appearanceSelectorOpen = false },
        )
    }

    if (omdbOverlayOpen) {
        RatingsOmdbOverlay(
            configured = state.omdbKeyConfigured,
            testing = state.testingOmdb,
            lastOutcome = state.omdbTestResult,
            onTest = { draft -> activeHolder.testOmdb(draft) },
            onSave = { draft -> activeHolder.saveOmdbKey(draft); omdbOverlayOpen = false },
            onRemove = { activeHolder.removeOmdbKey() },
            onDismiss = { omdbOverlayOpen = false },
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RatingsOmdbOverlay(
    configured: Boolean,
    testing: Boolean,
    lastOutcome: OmdbTestOutcome?,
    onTest: (String?) -> Unit,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {

    var draft by remember { mutableStateOf("") }
    val fieldRequester = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }

    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible

    BackHandler(enabled = true) {
        if (imeVisible) keyboardController?.hide() else onDismiss()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .width(640.dp)
                .border(
                    width = InfusionTvContour.SelectedWidth,
                    color = LocalInfusionAccent.current,
                    shape = InfusionTvContour.Shape,
                ),
            shape = InfusionTvContour.Shape,
            colors = SurfaceDefaults.colors(containerColor = InfusionTvAppearance.Panel),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("OMDb API key", style = InfusionTvScaleCalibration.Type.Headline)
                Text(
                    "Enter your own OMDb API key. Test it without saving, then Save to use it. " +
                        "The key is never shown again, logged, or shipped with the app.",
                    style = InfusionTvScaleCalibration.Type.SettingsContent,
                )
                LabeledField(
                    label = "API key",
                    value = draft,
                    secret = true,
                    onChange = { draft = it },
                    testTag = SettingsRatingsOmdbField,
                    focusRequester = fieldRequester,
                )
                if (configured) {
                    Text("A key is currently saved.", style = InfusionTvScaleCalibration.Type.SettingsContent)
                }
                lastOutcome?.let { outcome ->
                    Text(
                        omdbTestMessage(outcome),
                        style = InfusionTvScaleCalibration.Type.SettingsContent,
                        color = when (outcome) {
                            OmdbTestOutcome.Valid -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfusionTvButton(onClick = { onTest(draft.takeIf { it.isNotBlank() }) }) {
                        Text(if (testing) "Testing…" else "Test")
                    }
                    InfusionTvButton(onClick = { if (draft.isNotBlank()) onSave(draft) }) {
                        Text("Save")
                    }
                    if (configured) {
                        InfusionTvButton(onClick = onRemove) { Text("Remove") }
                    }

                }
            }
        }
    }
    LaunchedEffect(Unit) {
        repeat(30) {
            if (initialFocusRequested) return@LaunchedEffect
            withFrameNanos { }
            if (fieldRequester.requestFocus()) initialFocusRequested = true
        }
    }
}
