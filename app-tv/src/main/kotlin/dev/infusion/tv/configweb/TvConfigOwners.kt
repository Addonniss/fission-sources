package dev.infusion.tv.configweb

import dev.infusion.core.domain.appearance.AppearancePreferenceStore
import dev.infusion.core.domain.collections.CollectionPackStore
import dev.infusion.core.domain.collections.CollectionRowPreferenceStore
import dev.infusion.core.domain.home.HomeWidgetStore
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.ratings.RatingsCredentialStore
import dev.infusion.core.domain.ratings.RatingsPreferencesStore
import dev.infusion.core.domain.upnext.UpNextPresentationStore
import dev.infusion.core.domain.upnext.UpNextSelectionStore
import dev.infusion.feature.vertical.addon.AddonManagementPort
import dev.infusion.feature.vertical.appearance.ProfileAppearanceStateHolder
import dev.infusion.feature.vertical.collections.CollectionPackManagerStateHolder
import dev.infusion.feature.vertical.collections.CollectionRowSettingsStateHolder
import dev.infusion.feature.vertical.home.HomeCatalogPort
import dev.infusion.feature.vertical.home.HomeWidgetPort
import dev.infusion.feature.vertical.home.UpNextSettingsStateHolder
import dev.infusion.feature.vertical.playback.AudioLanguagePreferenceStateHolder
import dev.infusion.feature.vertical.playback.CompletionThresholdStateHolder
import dev.infusion.feature.vertical.playback.DisplayMatchingStateHolder
import dev.infusion.feature.vertical.playback.DolbyVisionProfile7FallbackStateHolder
import dev.infusion.feature.vertical.playback.HdrSubtitleDimmingStateHolder
import dev.infusion.feature.vertical.playback.NextEpisodeSettingsStateHolder
import dev.infusion.feature.vertical.playback.PlaybackControlsAutoHideStateHolder
import dev.infusion.feature.vertical.playback.RelativeSeekStepStateHolder
import dev.infusion.feature.vertical.playback.SegmentEvidenceSettingsStateHolder
import dev.infusion.feature.vertical.playback.SourceAutoplaySettingsStateHolder
import dev.infusion.feature.vertical.playback.SubtitleAppearanceStateHolder
import dev.infusion.feature.vertical.playback.SubtitlePreferenceStateHolder
import dev.infusion.feature.vertical.ratings.RatingsSettingsStateHolder

class TvConfigOwners(

    val profileScope: ProfileScope,
    val appearance: ProfileAppearanceStateHolder,
    val appearanceStore: AppearancePreferenceStore,

    val homeCatalog: HomeCatalogPort,
    val displayMatching: DisplayMatchingStateHolder,
    val dolbyVisionProfile7Fallback: DolbyVisionProfile7FallbackStateHolder,
    val audioLanguage: AudioLanguagePreferenceStateHolder,
    val subtitlePreference: SubtitlePreferenceStateHolder,
    val subtitleAppearance: SubtitleAppearanceStateHolder,
    val hdrSubtitleDimming: HdrSubtitleDimmingStateHolder,
    val completionThreshold: CompletionThresholdStateHolder,
    val playbackControlsAutoHide: PlaybackControlsAutoHideStateHolder,
    val relativeSeekStep: RelativeSeekStepStateHolder,
    val segmentEvidence: SegmentEvidenceSettingsStateHolder,
    val sourceAutoplay: SourceAutoplaySettingsStateHolder,
    val nextEpisode: NextEpisodeSettingsStateHolder,
    val ratings: RatingsSettingsStateHolder,
    val ratingsStore: RatingsPreferencesStore,
    val ratingsCredentials: RatingsCredentialStore,
    val addons: AddonManagementPort,
    val widgets: HomeWidgetPort,
    val widgetStore: HomeWidgetStore,
    val upNext: UpNextSettingsStateHolder,
    val upNextSelectionStore: UpNextSelectionStore,
    val upNextPresentationStore: UpNextPresentationStore,
    val collectionPacks: CollectionPackManagerStateHolder,
    val collectionPackStore: CollectionPackStore,
    val collectionRows: CollectionRowSettingsStateHolder,
    val collectionRowPreferences: CollectionRowPreferenceStore,
) {

    fun activeProfileId(): String = profileScope.activeProfileId
}
