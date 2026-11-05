package dev.infusion.feature.vertical.model

import dev.infusion.core.domain.catalog.HomeItem
import dev.infusion.core.model.EditionId
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.ProviderNotice
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.core.model.playback.PlaybackFailure as DomainPlaybackFailure
import dev.infusion.core.playback.api.PlaybackFailure as EnginePlaybackFailure
import dev.infusion.core.playback.api.PlaybackSegmentTimeline
import dev.infusion.core.playback.api.PlaybackState
import dev.infusion.feature.vertical.sourcepicker.SourceDiscoveryState
import dev.infusion.feature.vertical.sourcepicker.deriveSourceDiscoveryState

data class HomeShelfUi(
    val key: String,
    val title: String,
    val sourceLabel: String?,
    val items: List<HomeItemUi>,
)

data class HomeItemUi(
    val key: String,
    val titleId: TitleId,
    val preview: MediaPreview,

    val seriesTitleId: TitleId? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,

    val episodeTitleId: TitleId? = null,
)

data class HomeUiState(
    val shelves: List<HomeShelfUi> = emptyList(),

    val initialFocusKey: String? = null,
)

enum class PrimaryAction {

    UNPLAYABLE,

    PLAY,

    RESUME,
}

sealed interface DetailsPhase {

    data object Loading : DetailsPhase

    data class Error(val message: String, val recoverable: Boolean = true) : DetailsPhase

    data object NoSource : DetailsPhase
}

data class SeriesSeasonUi(
    val key: String,
    val seasonNumber: Int,
    val title: String?,
    val episodeCount: Int?,

    val seasonTitleId: TitleId? = null,

    val nativeItemId: String? = null,
)

sealed interface SeriesHierarchyPhase {
    data object Loading : SeriesHierarchyPhase
    data object Loaded : SeriesHierarchyPhase
    data object Empty : SeriesHierarchyPhase

    data class Failed(

        val message: String,

        val retryable: Boolean,

        val providerReasons: List<String> = emptyList(),
    ) : SeriesHierarchyPhase
}

data class SeriesDetailsUiState(
    val titleId: TitleId,
    val title: String? = null,
    val kindLabel: String? = null,
    val year: Int? = null,
    val sourceLabel: String? = null,
    val synopsis: String? = null,

    val genres: List<String> = emptyList(),

    val rating: String? = null,

    val hierarchy: SeriesHierarchyPhase = SeriesHierarchyPhase.Loading,
    val seasons: List<SeriesSeasonUi> = emptyList(),

    val selectedSeasonNumber: Int? = null,

    val episodes: List<EpisodeRowUi> = emptyList(),

    val episodesPhase: SeasonEpisodesPhase = SeasonEpisodesPhase.Loading,

    val episodesByKey: Map<WatchKey, dev.infusion.core.domain.watch.EpisodeCatalogEntry> = emptyMap(),

    val nextUnwatched: EpisodeIdentityUi? = null,

    val artworkBytes: ByteArray? = null,

    val initialFocusKey: String? = null,
)

sealed interface SeasonEpisodesPhase {

    data object Loading : SeasonEpisodesPhase

    data object Loaded : SeasonEpisodesPhase

    data object Empty : SeasonEpisodesPhase

    data class Failed(val message: String) : SeasonEpisodesPhase
}

data class EpisodeRowUi(
    val key: String,
    val titleId: TitleId,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val displayTitle: String,
    val watchedStatus: WatchedDisplay,

    val overview: String? = null,

    val nativeItemId: String? = null,
)

data class SeasonEpisodesUiState(
    val seriesTitleId: TitleId,
    val seriesTitle: String?,
    val seasonNumber: Int,
    val episodeCount: Int,
    val episodes: List<EpisodeRowUi>,

    val artworkBytes: ByteArray? = null,
    val initialFocusKey: String? = null,
)

data class EpisodeDetailsUiState(
    val seriesTitleId: TitleId,
    val titleId: TitleId,
    val seriesTitle: String? = null,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val displayTitle: String? = null,
    val synopsis: String? = null,
    val watched: WatchedDisplay = WatchedDisplay.Unseen,
    val resumePositionMs: Long? = null,
    val primaryAction: PrimaryAction = PrimaryAction.UNPLAYABLE,
    val unplayableReason: String? = null,
    val hasAvailability: Boolean = false,
    val selectedEditionLabel: String? = null,
    val sourceLabel: String? = null,

    val artworkBytes: ByteArray? = null,

    val parentShowBackdropLocator: String? = null,

    val parentShowLogoLocator: String? = null,

    val episodeStillRef: MediaArtworkRef? = null,
    val initialFocusKey: String = "primary",
)

data class EpisodeIdentityUi(
    val seriesTitleId: TitleId,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitleId: TitleId? = null,
)

sealed interface WatchedDisplay {

    data object Unseen : WatchedDisplay

    data class InProgress(val positionMs: Long, val completionFraction: Double = 0.0) : WatchedDisplay

    data object Completed : WatchedDisplay
}

data class DetailsUiState(
    val titleId: TitleId,
    val title: String? = null,
    val kindLabel: String? = null,
    val year: Int? = null,

    val subtitle: String? = null,
    val editionLabel: String? = null,
    val sourceLabel: String? = null,
    val synopsis: String? = null,

    val synopsisSourceLabel: String? = null,

    val genres: List<String> = emptyList(),

    val rating: String? = null,
    val resumePositionMs: Long? = null,
    val watched: WatchedDisplay = WatchedDisplay.Unseen,
    val primaryAction: PrimaryAction = PrimaryAction.UNPLAYABLE,
    val unplayableReason: String? = null,

    val selectedEditionLabel: String? = null,
    val hasAvailability: Boolean = false,

    val phase: DetailsPhase? = null,

    val initialFocusKey: String = "primary",
)

data class DetailsArtwork(

    val ref: MediaArtworkRef? = null,

    val bytes: ByteArray? = null,

    val available: Boolean = false,
) {

    val hasArtwork: Boolean get() = (available && bytes != null) || ref != null
}

enum class SelectionPolicy {

    MANUAL,

    AUTOPLAY,
}

sealed interface PickerRowState {
    data object Loading : PickerRowState
    data class Ready(val candidate: PlayableCandidate) : PickerRowState
    data class Expired(val candidate: PlayableCandidate) : PickerRowState
    data class Unsupported(val candidate: PlayableCandidate, val reason: String) : PickerRowState
    data class Unauthorized(val candidate: PlayableCandidate) : PickerRowState
    data class TimedOut(val candidate: PlayableCandidate) : PickerRowState
    data class Failed(val candidate: PlayableCandidate, val failure: DomainPlaybackFailure) : PickerRowState

    data class ExternalLink(val candidate: PlayableCandidate) : PickerRowState
}

data class PickerRow(
    val key: String,
    val state: PickerRowState,

    val providerKey: String? = null,

    val providerLabel: String? = null,

    val providerInstanceLabel: String? = null,

    val sizeBytes: Long? = null,
)

data class PickerTab(
    val key: String,
    val label: String,

    val providerSourceId: String? = null,

    val count: Int = 0,

    val enabled: Boolean = true,
) {
    companion object {

        const val ALL_KEY = "tab:all"

        fun providerKey(providerSourceId: String): String = "tab:provider:$providerSourceId"

        val ALL: PickerTab = PickerTab(key = ALL_KEY, label = "All", providerSourceId = null)
    }
}

sealed interface SelectionResult {
    data class Started(val candidate: PlayableCandidate) : SelectionResult

    data class Blocked(val rowKey: String, val state: PickerRowState) : SelectionResult

    data class ExternalLink(val candidate: PlayableCandidate) : SelectionResult
}

sealed interface PickerResolution {
    data object Loading : PickerResolution
    data class Resolved(val rows: List<PickerRow>, val complete: Boolean) : PickerResolution
    data class Failed(val failure: DomainPlaybackFailure) : PickerResolution
}

data class SourcePickerUiState(
    val titleId: TitleId,
    val editionId: EditionId?,
    val resolution: PickerResolution = PickerResolution.Loading,

    val discoveryStarted: Boolean = false,
    val policy: SelectionPolicy = SelectionPolicy.MANUAL,

    val focusedKey: String? = null,

    val userMovedFocus: Boolean = false,

    val autoplayCountdownMs: Long? = null,

    val attemptedKeys: List<String> = emptyList(),

    val providerStates: Map<String, String> = emptyMap(),

    val notices: List<ProviderNotice> = emptyList(),

    val tabs: List<PickerTab> = emptyList(),

    val activeTabKey: String = PickerTab.ALL_KEY,

    val visibleRows: List<PickerRow> = emptyList(),
) {

    val discovery: SourceDiscoveryState
        get() = deriveSourceDiscoveryState(discoveryStarted, resolution)
}

sealed interface SubtitleAvailability {

    data object Available : SubtitleAvailability

    data class Unavailable(val reason: String) : SubtitleAvailability

    data object Unknown : SubtitleAvailability
}

sealed interface TrackSelectionOutcome {
    data object Applied : TrackSelectionOutcome

    data class Rejected(val reason: String) : TrackSelectionOutcome
}

enum class PlayerSubordinateSurface {
    AUDIO_BROWSER,
    SUBTITLE_BROWSER,
    CAST_ROW,
}

data class PlayerUiState(
    val candidate: PlayableCandidate,
    val phase: PlaybackState,
    val positionMs: Long,
    val durationMs: Long?,

    val engineReportedDurationMs: Long? = null,
    val failure: EnginePlaybackFailure? = null,

    val controlsVisible: Boolean = false,

    val informationalVisible: Boolean = false,

    val subordinateSurface: PlayerSubordinateSurface? = null,

    val bufferedPositionMs: Long? = null,

    val isLive: Boolean = false,

    val watchRecorded: Boolean = false,

    val isBuffering: Boolean = false,

    val trackInventory: dev.infusion.core.playback.api.TrackInventory? = null,

    val trackSelection: dev.infusion.core.playback.api.TrackSelection = dev.infusion.core.playback.api.TrackSelection.DEFAULT,

    val subtitleAvailability: SubtitleAvailability = SubtitleAvailability.Unknown,

    val subtitleNoEligible: Boolean = false,

    val measuredVideo: dev.infusion.core.playback.api.MeasuredVideoEvidence =
        dev.infusion.core.playback.api.MeasuredVideoEvidence.NONE,

    val lastTrackSelectionOutcome: TrackSelectionOutcome? = null,

    val firstFrameRendered: Boolean = false,

    val startupFrameReady: Boolean = false,

    val initialTrackSetupSettled: Boolean = false,

    val initialDisplayMatchSettled: Boolean = false,

    val upNext: UpNextUi? = null,

    val segments: PlaybackSegmentTimeline? = null,

    val skipIntro: dev.infusion.core.playback.api.SkipIntroUi? = null,

    val stinger: StingerUi? = null,

    val nextEpisode: NextEpisodeUi? = null,
)

enum class UpNextAvailability {

    AVAILABLE,

    NOT_YET_AIRED,
}

data class UpNextUi(

    val nextEpisode: EpisodeIdentityUi? = null,

    val identityLabel: String = "UNKNOWN",

    val isEpisode: Boolean = true,

    val availability: UpNextAvailability = UpNextAvailability.AVAILABLE,
)

data class NextEpisodeUi(
    val identityLabel: String = "UNKNOWN",
    val countdownSeconds: Int? = null,
    val isAutomatic: Boolean = false,
    val suppressed: Boolean = false,
    val fullscreenDismissed: Boolean = false,
) {
    val isCountingDown: Boolean get() = countdownSeconds != null

    companion object {

        const val LABEL: String = "Next Episode"
    }
}

data class StingerUi(
    val message: String,
    val isEarlyNotice: Boolean,
) {
    companion object {

        const val EARLY_MESSAGE: String = "Stay Tuned — this movie has a scene after the credits"

        const val CREDITS_MESSAGE: String = "Stay Tuned"

        fun early(): StingerUi = StingerUi(message = EARLY_MESSAGE, isEarlyNotice = true)

        fun credits(): StingerUi = StingerUi(message = CREDITS_MESSAGE, isEarlyNotice = false)
    }
}
