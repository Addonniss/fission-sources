package dev.infusion.tv.di

import android.content.Context
import android.os.Build
import dev.infusion.core.database.InfusionDatabase
import dev.infusion.core.database.profile.MutableProfileScope
import dev.infusion.core.database.profile.RoomProfileRepository
import dev.infusion.core.database.profile.SharedPreferencesActiveProfileStore
import dev.infusion.core.database.sources.SourceRemoval
import dev.infusion.core.database.store.RoomConnectorCheckpointStore
import dev.infusion.core.database.store.RoomPlaybackSessionContextStore
import dev.infusion.core.database.store.RoomWatchedHistorySource
import dev.infusion.core.database.store.RoomWatchEventLedger
import dev.infusion.core.database.store.RoomWatchOutbox
import dev.infusion.core.database.store.RoomWatchStateStore
import dev.infusion.core.domain.home.addressesSameSource
import dev.infusion.core.domain.profile.ActiveProfileStore
import dev.infusion.core.domain.profile.DeleteProfileResult
import dev.infusion.core.domain.profile.Pbkdf2ProfilePinHasher
import dev.infusion.core.domain.profile.ProfileLedgerDisposition
import dev.infusion.core.domain.profile.ProfilePolicy
import dev.infusion.core.domain.profile.ProfileRepository
import dev.infusion.core.domain.profile.ProfileTrackingCredentialRevoker
import dev.infusion.core.domain.tracking.ConnectorRegistration
import dev.infusion.core.domain.tracking.TrackingServicePort
import dev.infusion.core.domain.tracking.watchTrackingSelectionGate
import dev.infusion.core.domain.watch.GetContinueWatchingUseCase
import dev.infusion.core.domain.watch.GetRecentlyWatchedUseCase
import dev.infusion.core.model.landscapeSlot
import dev.infusion.core.model.profile.Profile
import dev.infusion.core.network.InMemoryLanTrustRegistry
import dev.infusion.core.network.LanTrustRegistry
import dev.infusion.core.playback.api.PlaybackEngine
import dev.infusion.core.security.SecretStore
import dev.infusion.core.security.SecretStoreFactory
import dev.infusion.core.sync.WatchSyncCoordinator
import dev.infusion.feature.vertical.details.DetailsStateHolder
import dev.infusion.feature.vertical.home.HomeContinueWatchingStateHolder
import dev.infusion.feature.vertical.home.HomeCatalogRowsStateHolder
import dev.infusion.feature.vertical.home.HomeCustomizationStateHolder
import dev.infusion.feature.vertical.home.HomePresentationStateHolder
import dev.infusion.feature.vertical.home.HomeStateHolder
import dev.infusion.feature.vertical.home.resolveCwTitleFromCatalog
import dev.infusion.feature.vertical.jellyfin.JellyfinRowFetch
import dev.infusion.feature.vertical.jellyfin.JellyfinTabPort
import dev.infusion.feature.vertical.jellyfin.JellyfinTabRowItem
import dev.infusion.feature.vertical.jellyfin.JellyfinTabStateHolder
import dev.infusion.feature.vertical.player.PlayerStateHolder
import dev.infusion.feature.vertical.profile.PinEntryStateHolder
import dev.infusion.feature.vertical.profile.ProfileManagementStateHolder
import dev.infusion.feature.vertical.profile.ProfileSourceVisibility
import dev.infusion.feature.vertical.watchlist.RecentlyWatchedStateHolder
import dev.infusion.feature.vertical.profile.ProfileSwitchingSeam
import dev.infusion.feature.vertical.profile.StartupProfileRestore
import dev.infusion.feature.vertical.sourcepicker.SourcePickerStateHolder
import dev.infusion.tv.ui.presentation.toContentCardPresentation
import dev.infusion.tv.watchlist.WatchlistMembershipArtworkMemory
import dev.infusion.source.jellyfin.JellyfinSourceCoordinator
import dev.infusion.source.jellyfin.JellyfinSourceGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private const val LIBRARY_GENRE_ARTWORK_PARALLELISM: Int = 4

class AppContainer(
    private val context: Context,
    database: InfusionDatabase? = null,

    jellyfin: JellyfinSourceGraph? = null,

    private val secretStore: SecretStore? = null,

    lanTrust: LanTrustRegistry? = null,

    private val playerEngineFactory: (() -> PlaybackEngine)? = null,

    phoneSetupLanAddress: String? = null,

    private val jellyfinDiscovery: dev.infusion.core.domain.jellyfin.JellyfinDiscoveryPort? = null,

    collectionDateSeam: (() -> java.time.LocalDate)? = null,

    addonClientFactorySeam: dev.infusion.source.stremio.AddonClientFactory? = null,

    metadataProviderSeam: dev.infusion.core.domain.metadata.MetadataProvider? = null,

    private val simklHubSeam: dev.infusion.core.network.HardenedHttpClient? = null,

    private val simklClientIdSeam: String? = null,

    private val traktHubSeam: dev.infusion.core.network.HardenedHttpClient? = null,

    private val traktClientIdSeam: String? = null,
    private val traktClientSecretSeam: String? = null,

    private val mdblistHubSeam: dev.infusion.core.network.HardenedHttpClient? = null,

    private val mdblistClientIdSeam: String? = null,
) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var latestPlayerSnapshot: dev.infusion.core.playback.api.DiagnosticsSnapshot? = null

    private val db: InfusionDatabase = database ?: InfusionDatabase.create(context)

    private val secretStoreRef: SecretStore by lazy { secretStore ?: SecretStoreFactory.create(context) }

    private val lanTrustRef: LanTrustRegistry = lanTrust ?: InMemoryLanTrustRegistry()

    private val activeProfileStore: ActiveProfileStore = SharedPreferencesActiveProfileStore(context)

    private val languagePreferences: dev.infusion.core.domain.playback.PlaybackLanguagePreferenceStore by lazy {
        dev.infusion.core.database.profile.SharedPreferencesPlaybackLanguagePreferenceStore(context)
    }

    private val subtitleAppearancePreferences:
        dev.infusion.core.playback.api.SubtitleAppearancePreferenceStore by lazy {
        dev.infusion.core.database.profile.SharedPreferencesSubtitleAppearancePreferenceStore(context)
    }

    private val hdrSubtitleDimmingPreferences:
        dev.infusion.core.playback.api.HdrSubtitleDimmingPreferenceStore by lazy {
        dev.infusion.core.database.profile.SharedPreferencesHdrSubtitleDimmingPreferenceStore(context)
    }

    private val appearancePreferences: dev.infusion.core.domain.appearance.AppearancePreferenceStore by lazy {
        dev.infusion.core.database.profile.SharedPreferencesAppearancePreferenceStore(context)
    }

    private val completionThresholdPreferences:
        dev.infusion.core.domain.playback.CompletionThresholdPreferenceStore by lazy {
        dev.infusion.core.database.profile.SharedPreferencesCompletionThresholdPreferenceStore(context)
    }

    private val playbackControlsAutoHidePreferences:
        dev.infusion.core.domain.playback.PlaybackControlsAutoHidePreferenceStore by lazy {
        dev.infusion.core.database.profile.SharedPreferencesPlaybackControlsAutoHidePreferenceStore(context)
    }

    private val relativeSeekStepPreferences:
        dev.infusion.core.domain.playback.RelativeSeekStepPreferenceStore by lazy {
        dev.infusion.core.database.profile.SharedPreferencesRelativeSeekStepPreferenceStore(context)
    }

    private val homeWidgetStore: dev.infusion.core.domain.home.HomeWidgetStore by lazy {
        dev.infusion.core.database.home.SharedPreferencesHomeWidgetStore(context)
    }

    private val defaultHomeWidgetsSeeder: dev.infusion.feature.vertical.home.DefaultHomeWidgetSeeder by lazy {
        dev.infusion.feature.vertical.home.DefaultHomeWidgetSeeder(
            store = homeWidgetStore,
            newWidgetId = ::newHomeWidgetId,
        )
    }

    private val collectionPackStore: dev.infusion.core.domain.collections.CollectionPackStore by lazy {
        dev.infusion.core.database.collections.SharedPreferencesCollectionPackStore(context)
    }

    private val collectionRowPreferenceStore: dev.infusion.core.domain.collections.CollectionRowPreferenceStore by lazy {
        dev.infusion.core.database.collections.SharedPreferencesCollectionRowPreferenceStore(context)
    }

    private val collectionToday: () -> java.time.LocalDate =
        collectionDateSeam ?: { java.time.LocalDate.now() }

    val activeProfileId: String get() = profileScope.activeProfileId

    val activeProfileFlow: kotlinx.coroutines.flow.Flow<String> get() = profileScope.activeProfileFlow

    val appearanceStateHolder: dev.infusion.feature.vertical.appearance.ProfileAppearanceStateHolder by lazy {
        dev.infusion.feature.vertical.appearance.ProfileAppearanceStateHolder(
            store = appearancePreferences,
            profileScope = profileScope,
            scope = appScope,
        )
    }

    private val displayMatchingPreferences: dev.infusion.core.playback.api.DisplayMatchingPreferenceStore by lazy {
        dev.infusion.core.database.profile.SharedPreferencesDisplayMatchingPreferenceStore(context)
    }

    val displayMatchingStateHolder: dev.infusion.feature.vertical.playback.DisplayMatchingStateHolder by lazy {
        dev.infusion.feature.vertical.playback.DisplayMatchingStateHolder(
            store = displayMatchingPreferences,
            scope = appScope,
        )
    }

    private val dolbyVisionProfile7FallbackPreferences:
        dev.infusion.core.playback.api.DolbyVisionProfile7FallbackPreferenceStore by lazy {
        dev.infusion.core.database.profile.SharedPreferencesDolbyVisionProfile7FallbackPreferenceStore(context)
    }

    val dolbyVisionProfile7FallbackStateHolder:
        dev.infusion.feature.vertical.playback.DolbyVisionProfile7FallbackStateHolder by lazy {
        dev.infusion.feature.vertical.playback.DolbyVisionProfile7FallbackStateHolder(
            store = dolbyVisionProfile7FallbackPreferences,
            scope = appScope,
        )
    }

    private val dolbyVisionProfile7FallbackProvider: () -> dev.infusion.core.playback.api.DolbyVisionProfile7Fallback =
        { dolbyVisionProfile7FallbackStateHolder.fallback.value }

    val audioLanguagePreferenceStateHolder: dev.infusion.feature.vertical.playback.AudioLanguagePreferenceStateHolder by lazy {
        dev.infusion.feature.vertical.playback.AudioLanguagePreferenceStateHolder(
            store = languagePreferences,
            profileScope = profileScope,
            scope = appScope,
        )
    }

    val subtitlePreferenceStateHolder: dev.infusion.feature.vertical.playback.SubtitlePreferenceStateHolder by lazy {
        dev.infusion.feature.vertical.playback.SubtitlePreferenceStateHolder(
            store = languagePreferences,
            profileScope = profileScope,
            scope = appScope,
        )
    }

    val subtitleAppearanceStateHolder:
        dev.infusion.feature.vertical.playback.SubtitleAppearanceStateHolder by lazy {
        dev.infusion.feature.vertical.playback.SubtitleAppearanceStateHolder(
            store = subtitleAppearancePreferences,
            profileScope = profileScope,
            scope = appScope,
        )
    }

    val hdrSubtitleDimmingStateHolder:
        dev.infusion.feature.vertical.playback.HdrSubtitleDimmingStateHolder by lazy {
        dev.infusion.feature.vertical.playback.HdrSubtitleDimmingStateHolder(
            store = hdrSubtitleDimmingPreferences,
            profileScope = profileScope,
            scope = appScope,
        )
    }

    val completionThresholdStateHolder:
        dev.infusion.feature.vertical.playback.CompletionThresholdStateHolder by lazy {
        dev.infusion.feature.vertical.playback.CompletionThresholdStateHolder(
            store = completionThresholdPreferences,
            profileScope = profileScope,
            scope = appScope,
        )
    }

    val playbackControlsAutoHideStateHolder:
        dev.infusion.feature.vertical.playback.PlaybackControlsAutoHideStateHolder by lazy {
        dev.infusion.feature.vertical.playback.PlaybackControlsAutoHideStateHolder(
            store = playbackControlsAutoHidePreferences,
            profileScope = profileScope,
            scope = appScope,
        )
    }

    val relativeSeekStepStateHolder:
        dev.infusion.feature.vertical.playback.RelativeSeekStepStateHolder by lazy {
        dev.infusion.feature.vertical.playback.RelativeSeekStepStateHolder(
            store = relativeSeekStepPreferences,
            profileScope = profileScope,
            scope = appScope,
        )
    }

    private val segmentEvidencePreferences:
        dev.infusion.core.domain.playback.SegmentEvidencePreferenceStore by lazy {
        dev.infusion.core.database.profile.SharedPreferencesSegmentEvidencePreferenceStore(context)
    }

    val segmentEvidenceSettingsStateHolder:
        dev.infusion.feature.vertical.playback.SegmentEvidenceSettingsStateHolder by lazy {
        dev.infusion.feature.vertical.playback.SegmentEvidenceSettingsStateHolder(
            store = segmentEvidencePreferences,
            profileScope = profileScope,
            scope = appScope,
        )
    }

    private val sourceAutoplayPreferences:
        dev.infusion.core.domain.playback.SourceAutoplayPreferenceStore by lazy {
        dev.infusion.core.database.profile.SharedPreferencesSourceAutoplayPreferenceStore(context)
    }

    val sourceAutoplaySettingsStateHolder:
        dev.infusion.feature.vertical.playback.SourceAutoplaySettingsStateHolder by lazy {
        dev.infusion.feature.vertical.playback.SourceAutoplaySettingsStateHolder(
            store = sourceAutoplayPreferences,
            profileScope = profileScope,
            scope = appScope,
        )
    }

    private val nextEpisodePreferences:
        dev.infusion.core.domain.playback.NextEpisodePreferenceStore by lazy {
        dev.infusion.core.database.profile.SharedPreferencesNextEpisodePreferenceStore(context)
    }

    val nextEpisodeSettingsStateHolder:
        dev.infusion.feature.vertical.playback.NextEpisodeSettingsStateHolder by lazy {
        dev.infusion.feature.vertical.playback.NextEpisodeSettingsStateHolder(
            store = nextEpisodePreferences,
            profileScope = profileScope,
            scope = appScope,
        )
    }

    private val segmentEvidenceSources: List<dev.infusion.core.playback.api.SegmentEvidenceSource> by lazy {
        val client = dev.infusion.core.network.OkHttpHardenedClient()
        listOf(
            dev.infusion.source.segments.TheIntroDbSegmentSource(client),
            dev.infusion.source.segments.IntroDbAppSegmentSource(client),
        )
    }

    val playbackSegmentCoordinator:
        dev.infusion.feature.vertical.player.PlaybackSegmentCoordinator by lazy {
        dev.infusion.feature.vertical.player.PlaybackSegmentCoordinator(
            sources = segmentEvidenceSources,
            scope = appScope,
        )
    }

    private val stingerEvidenceSources: List<dev.infusion.core.playback.api.StingerEvidenceSource> by lazy {
        val client = dev.infusion.core.network.OkHttpHardenedClient()
        listOf(
            dev.infusion.source.tmdb.TmdbStingerSource(client, GeneratedTmdbCredential.apiKey()),
            dev.infusion.source.segments.AfterCreditsStingerSource(client),
        )
    }

    val playbackStingerCoordinator:
        dev.infusion.feature.vertical.player.PlaybackStingerCoordinator by lazy {
        dev.infusion.feature.vertical.player.PlaybackStingerCoordinator(
            sources = stingerEvidenceSources,
            scope = appScope,
        )
    }

    private suspend fun segmentAttemptEvidenceFor(
        watchKey: dev.infusion.core.model.watch.WatchKey,
        candidate: dev.infusion.core.model.PlayableCandidate,
    ): dev.infusion.core.playback.api.SegmentAttemptEvidence {
        val local = jellyfinSource
            ?.takeIf { graph -> candidate.sourceId == graph.sourceId }
            ?.segmentEvidence
            ?.evidence(candidate.nativeItemId.nativeId)
            ?: dev.infusion.source.jellyfin.playback.JellyfinSegmentEvidence.Result.EMPTY

        val titleId = watchKey.seriesId ?: watchKey.titleId
            ?: return dev.infusion.core.playback.api.SegmentAttemptEvidence(
                chapters = local.chapters,
                serverSegments = local.segments,
            )
        val type = if (watchKey.isEpisode) {
            dev.infusion.core.model.metadata.MediaType.SHOW
        } else {
            dev.infusion.core.model.metadata.MediaType.MOVIE
        }
        val resolved = try {
            mediaIdentityResolver.resolve(titleId, type, metadataLocale())
        } catch (_: Exception) {
            null
        }
        val external = resolved?.externalIds

        val stingerIdentity = if (!watchKey.isEpisode && resolved != null) {
            val details = try {
                metadataRepository.observeDetails(resolved, metadataLocale()).first().details
            } catch (_: Exception) {
                null
            }
            dev.infusion.core.playback.api.StingerLookupIdentity(
                tmdbId = external?.tmdbId,
                imdbId = external?.imdbId,
                title = details?.title,
                year = details?.year,
            )
        } else {
            dev.infusion.core.playback.api.StingerLookupIdentity()
        }
        return dev.infusion.core.playback.api.SegmentAttemptEvidence(
            identity = dev.infusion.core.playback.api.SegmentLookupIdentity(
                tmdbId = external?.tmdbId,
                imdbId = external?.imdbId,
                showImdbId = external?.imdbId,
                seasonNumber = watchKey.seasonNumber,
                episodeNumber = watchKey.episodeNumber,
                isMovie = !watchKey.isEpisode,
            ),
            chapters = local.chapters,
            serverSegments = local.segments,
            stingerIdentity = stingerIdentity,
        )
    }

    val captioningLanguageTag: String? by lazy { resolveCaptioningLanguageTag(context) }

    private fun resolveCaptioningLanguageTag(context: android.content.Context): String? {
        val manager = context.getSystemService(android.content.Context.CAPTIONING_SERVICE)
            as? android.view.accessibility.CaptioningManager ?: return null
        val locale = manager.locale ?: return null
        return locale.toLanguageTag().takeIf { tag -> tag.isNotBlank() && tag != "und" }
    }

    val systemLanguageTags: List<String> by lazy { resolveSystemLanguageTags(context) }

    private fun resolveSystemLanguageTags(context: android.content.Context): List<String> {
        val locales = context.resources.configuration.locales
        return (0 until locales.size()).mapNotNull { index ->
            locales[index].toLanguageTag().takeIf { tag -> tag.isNotBlank() && tag != "und" }
        }
    }

    val displayMatchingCoordinator: dev.infusion.tv.playback.DisplayMatchingCoordinator by lazy {
        dev.infusion.tv.playback.DisplayMatchingCoordinator(displayMatchingDiagnostics)
    }

    val displayMatchingDiagnostics: dev.infusion.tv.playback.DisplayMatchingDiagnostics by lazy {
        dev.infusion.tv.playback.DisplayMatchingDiagnostics.androidLog()
    }

    private val profileScope: MutableProfileScope = MutableProfileScope(ProfilePolicy.DEFAULT_PROFILE_ID)

    private val profileRepository: RoomProfileRepository by lazy {
        RoomProfileRepository(
            profileDao = db.profileDao(),
            watchEventDao = db.watchEventDao(),
            watchStateDao = db.watchStateDao(),
            sourceDao = db.sourceDao(),
            checkpointDao = db.checkpointDao(),
            outboxDao = db.outboxDao(),
            activeProfileStore = activeProfileStore,
            scope = profileScope,
            pinHasher = Pbkdf2ProfilePinHasher(),
            transaction = { block -> db.runInTransaction(block) },

            credentialRevoker = jellyfinProfileSourceRevoker,

            trackingCredentialRevoker = ProfileTrackingCredentialRevoker { profileId ->
                trackingProfileCredentialRevoker.revoke(profileId)

                runCatching { trackingPreferencesStore.clearProfile(profileId) }

                runCatching { homePresentationStore.clearProfile(profileId) }
            },

            onProfileCreated = { profileId -> defaultHomeWidgetsSeeder.seedNewProfile(profileId) },
        )
    }

    private val jellyfinProfileSourceRevoker: dev.infusion.core.domain.profile.ProfileSourceCredentialRevoker by lazy {
        dev.infusion.source.jellyfin.removal.JellyfinProfileSourceRevoker(
            stateDao = db.jellyfinSourceStateDao(),

            secretStore = { secretStoreRef },
            lanTrust = lanTrustRef,
        )
    }

    val activeProfile: Profile get() = profileRepository.primary().let { primary ->
        profileRepository.list().firstOrNull { it.profileId == profileScope.activeProfileId } ?: primary
    }

    val startupProfileRestore: StartupProfileRestore by lazy {
        StartupProfileRestore(
            repository = profileRepository,
            activeProfileStore = activeProfileStore,
            retargetScope = { recovered ->
                applySourceVisibilityFor(recovered)
                profileScope.switchTo(recovered)
            },
        )
    }

    private val visibilityAwareProfileRepository: ProfileRepository =
        object : ProfileRepository by profileRepository {
            override fun switchActive(profileId: String): Profile {

                if (profileScope.activeProfileId != profileId) {
                    runCatching { musicQueueStateHolder.stop() }
                }

                applySourceVisibilityFor(profileId)
                val switched = profileRepository.switchActive(profileId)

                refreshRatingsAppearance()
                return switched
            }

            override suspend fun delete(
                profileId: String,
                disposition: ProfileLedgerDisposition,
            ): DeleteProfileResult {
                val deletedActiveProfile = profileScope.activeProfileId == profileId
                val result = profileRepository.delete(profileId, disposition)

                if (deletedActiveProfile && result is DeleteProfileResult.Ok) {
                    runCatching { musicQueueStateHolder.stop() }
                }

                applySourceVisibilityFor(profileScope.activeProfileId)

                languagePreferences.clearForProfile(profileId)

                subtitleAppearancePreferences.clearForProfile(profileId)

                hdrSubtitleDimmingPreferences.clearForProfile(profileId)

                appearancePreferences.clearForProfile(profileId)

                completionThresholdPreferences.clearForProfile(profileId)

                playbackControlsAutoHidePreferences.clearForProfile(profileId)

                nextEpisodePreferences.clearForProfile(profileId)
                return result
            }
        }

    suspend fun switchProfile(profileId: String): Profile = withContext(Dispatchers.IO) {

        runCatching { musicQueueStateHolder.stopAndAwait() }
        visibilityAwareProfileRepository.switchActive(profileId)
    }

    fun profileManagementStateHolder(scope: CoroutineScope): ProfileManagementStateHolder =
        ProfileManagementStateHolder(
            repository = visibilityAwareProfileRepository,
            profileScope = profileScope,
            scope = scope,
            ioDispatcher = Dispatchers.IO,
        )

    fun pinEntryStateHolder(profileId: String, scope: CoroutineScope): PinEntryStateHolder =
        PinEntryStateHolder(
            profileId = profileId,
            repository = visibilityAwareProfileRepository,
            scope = scope,
            ioDispatcher = Dispatchers.IO,
            nowMs = { android.os.SystemClock.elapsedRealtime() },
        )

    fun diagnosticExportStateHolder(): dev.infusion.feature.vertical.diagnostics.DiagnosticExportStateHolder {
        val installedAddons = try {
            addonManager.installedAddonIds().size
        } catch (_: Exception) {
            0
        }
        return dev.infusion.feature.vertical.diagnostics.DiagnosticExportStateHolder(
            app = dev.infusion.core.network.DiagnosticAppDescriptor("Infusion TV", "0.1.0", 36),
            counts = {
                dev.infusion.core.network.DiagnosticCounts(
                    profiles = 1,
                    sources = if (jellyfinSource != null) 1 else 0,
                    addons = installedAddons,
                )
            },
            sourceDiagnostics = { liveSourceDiagnostics() },
            playerDiagnostics = { latestPlayerSnapshot?.let { listOf(it) } ?: emptyList() },
            nowMs = { android.os.SystemClock.elapsedRealtime() },
        )
    }

    private fun liveSourceDiagnostics(): List<dev.infusion.core.network.RedactedDiagnostic> = buildList {
        jellyfinSource?.source?.recentDiagnostics()?.let { addAll(it) }
        for (addonId in addonManager.installedAddonIds()) {
            val diag = addonManager.runtimeFor(addonId)?.providerState?.lastFailureObservation?.diagnostic
            if (diag != null) add(diag)
        }
    }

    val titlePreviewHandoff: dev.infusion.core.domain.preview.TitlePreviewHandoff by lazy {
        dev.infusion.core.domain.preview.InMemoryTitlePreviewHandoff()
    }

    val detailsBootstrapMetrics: dev.infusion.core.domain.preview.DetailsBootstrapMetrics by lazy {
        if (isDebuggable) {
            dev.infusion.tv.perf.LogcatDetailsBootstrapMetrics()
        } else {
            dev.infusion.core.domain.preview.DetailsBootstrapMetrics.None
        }
    }

    val shelfDeliveryMetrics: dev.infusion.core.domain.catalog.ShelfDeliveryMetrics by lazy {
        if (isDebuggable) {
            dev.infusion.tv.perf.LogcatShelfDeliveryMetrics()
        } else {
            dev.infusion.core.domain.catalog.ShelfDeliveryMetrics.None
        }
    }

    val profileSwitchingSeam: ProfileSwitchingSeam by lazy {
        ProfileSwitchingSeam(
            profileScope = profileScope,
            scope = appScope,
            homeFactory = { _profileId, holderScope ->
                HomeStateHolder(
                    catalog = catalog,
                    continueWatching = continueWatching,
                    scope = holderScope,

                    episodeHierarchy = canonicalShowHierarchy,

                )
            },
            continueWatchingFactory = { _profileId, _holderScope ->
                GetContinueWatchingUseCase(RoomWatchStateStore(db.watchStateDao(), profileScope, titleSupersession))
            },
            detailsFactory = { _profileId, titleId, holderScope ->
                DetailsStateHolder(
                    titleId = titleId,
                    catalog = detailsCatalog(),
                    watchStateRepository = RoomWatchStateStore(db.watchStateDao(), profileScope, titleSupersession),
                    scope = holderScope,
                    refresh = jellyfinSource?.let { graph -> { graph.catalog.refreshDetails(titleId) } },

                    serverResume = jellyfinSource?.let { graph -> { graph.currentResumePosition(titleId) } },
                    selectedEditionLabel = { selectedVersionLabel(titleId) },

                    handoff = titlePreviewHandoff,

                    metrics = detailsBootstrapMetrics,

                    watchedStateDemand = watchedStateDemand,
                )
            },
        )
    }

    fun homeStateHolder(): HomeStateHolder =
        profileSwitchingSeam.home.current.value
            ?: error("the switching seam seeds the home holder synchronously")

    fun detailsStateHolder(titleId: dev.infusion.core.model.TitleId): DetailsStateHolder =
        profileSwitchingSeam.details(titleId).current.value
            ?: error("the switching seam seeds the details holder synchronously")

    val versionPreferences: dev.infusion.core.domain.catalog.VersionPreferenceStore by lazy {
        dev.infusion.core.database.profile.SharedPreferencesVersionPreferenceStore(context)
    }

    private fun movieUnitKey(titleId: dev.infusion.core.model.TitleId): String =
        dev.infusion.core.domain.catalog.movieUnitKey(titleId)

    private fun selectedVersionLabel(titleId: dev.infusion.core.model.TitleId): String? {

        val pref = versionPreferences.preferredVersion(profileScope.activeProfileId, movieUnitKey(titleId))
            ?: return null
        return "Selected version"
    }

    private fun selectedEpisodeVersionLabel(
        seriesId: dev.infusion.core.model.TitleId,
        seasonNumber: Int,
        episodeNumber: Int,
    ): String? {
        val unitKey = dev.infusion.core.domain.catalog.episodeUnitKey(seriesId, seasonNumber, episodeNumber)
        val pref = versionPreferences.preferredVersion(profileScope.activeProfileId, unitKey) ?: return null
        return "Selected version"
    }

    fun seriesDetailsStateHolder(
        titleId: dev.infusion.core.model.TitleId,
        holderScope: CoroutineScope,
    ): dev.infusion.feature.vertical.details.SeriesDetailsStateHolder {

        val nativePort = addonNativePort()
        return dev.infusion.feature.vertical.details.SeriesDetailsStateHolder(
            titleId = titleId,
            catalog = dev.infusion.source.addon.shells.AddonMetaEnrichingCatalogPort(
                base = nativePort,
                detailsService = addonDetailsService,
            ),
            watchStateRepository = RoomWatchStateStore(db.watchStateDao(), profileScope, titleSupersession),
            scope = holderScope,
            refresh = jellyfinSource?.let { graph -> { graph.catalog.refreshDetails(titleId) } },

            hierarchyFailureProvider = {
                nativePort.lastHierarchyFailure()?.let { failure ->
                    dev.infusion.feature.vertical.model.SeriesHierarchyPhase.Failed(
                        message = dev.infusion.source.addon.shells.HierarchyFailure.MESSAGE,
                        retryable = failure.retryable,
                        providerReasons = failure.providerReasons,
                    )
                }
            },

            watchedStateDemand = watchedStateDemand,

            additionalHierarchies = listOf(
                addonInfrastructure.nativeCatalogPort(base = { NoSourceCatalogPort() }),
            ),
        )
    }

    fun seasonEpisodesStateHolder(
        seriesTitleId: dev.infusion.core.model.TitleId,
        seasonNumber: Int,
        seasonTitleId: dev.infusion.core.model.TitleId,
        holderScope: CoroutineScope,
    ): dev.infusion.feature.vertical.details.SeasonEpisodesStateHolder =
        dev.infusion.feature.vertical.details.SeasonEpisodesStateHolder(
            seriesTitleId = seriesTitleId,
            seasonNumber = seasonNumber,
            seasonTitleId = seasonTitleId,
            catalog = detailsCatalog(),
            watchStateRepository = RoomWatchStateStore(db.watchStateDao(), profileScope, titleSupersession),
            scope = holderScope,

            watchedStateDemand = watchedStateDemand,

            serverSeasonResume = jellyfinSource?.let { graph ->
                { number -> graph.episodeResumePositionsForSeason(seriesTitleId, number) }
            },
        )

    fun episodeDetailsStateHolder(
        seriesTitleId: dev.infusion.core.model.TitleId,
        seasonNumber: Int,
        episodeNumber: Int,
        episodeTitleId: dev.infusion.core.model.TitleId,
        holderScope: CoroutineScope,

        resumeHintMs: Long? = null,
    ): dev.infusion.feature.vertical.details.EpisodeDetailsStateHolder =
        dev.infusion.feature.vertical.details.EpisodeDetailsStateHolder(
            seriesTitleId = seriesTitleId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitleId = episodeTitleId,

            catalog = detailsCatalogForEpisode(seriesTitleId),
            watchStateRepository = RoomWatchStateStore(db.watchStateDao(), profileScope, titleSupersession),
            scope = holderScope,
            selectedEditionLabel = {
                selectedEpisodeVersionLabel(seriesTitleId, seasonNumber, episodeNumber)
            },

            resolveParentShowBackdrop = parentShowBackdropSeamFor(seriesTitleId),

            resolveParentShowLogo = parentShowLogoSeamFor(seriesTitleId),
            resumeHintMs = resumeHintMs,

            serverResume = jellyfinSource?.let { graph -> { graph.currentResumePosition(episodeTitleId) } },

            watchedStateDemand = watchedStateDemand,
        )

    private val canonicalConvergence: dev.infusion.core.domain.identity.CanonicalConvergenceStore by lazy {
        dev.infusion.core.database.store.RoomCanonicalConvergenceStore(
            titleDao = db.titleDao(),
            titleLinkDao = db.titleLinkDao(),
            externalIdDao = db.externalIdDao(),
            identityMappingDao = db.identityMappingDao(),
            availabilityDao = db.availabilityDao(),
            editionDao = db.editionDao(),
            serverItemDao = db.serverItemDao(),
            localAssetDao = db.localAssetDao(),
            playableCandidateDao = db.playableCandidateDao(),
            metadataCacheDao = db.metadataCacheDao(),
            watchEventDao = db.watchEventDao(),
            watchStateDao = db.watchStateDao(),
            transaction = { block -> db.runInTransaction(block) },
            removedSourceIds = { db.sourceDao().listRemovedSourceIds().toSet() },
        )
    }

    private val metadataCacheStore: dev.infusion.core.domain.metadata.MetadataCacheStore by lazy {
        dev.infusion.core.metadata.TieredMetadataCacheStore(
            persistent = dev.infusion.core.database.store.RoomMetadataCacheStore(db.metadataCacheDao()),
        )
    }

    private val jellyfinRatingsEvidence: dev.infusion.core.domain.ratings.JellyfinRatingsEvidenceStore by lazy {
        dev.infusion.source.communityratings.JellyfinRatingsEvidenceCache(metadataCacheStore)
    }

    private fun buildCoordinator(): JellyfinSourceCoordinator = JellyfinSourceCoordinator(
        lanTrust = lanTrustRef,
        secretStore = secretStoreRef,
        stateDao = db.jellyfinSourceStateDao(),
        sourceDao = db.sourceDao(),
        secretDao = db.secretReferenceDao(),
        serverItemDao = db.serverItemDao(),
        serverMediaVersionDao = db.serverMediaVersionDao(),
        titleDao = db.titleDao(),
        externalIdDao = db.externalIdDao(),
        availabilityDao = db.availabilityDao(),
        outbox = RoomWatchOutbox(db.outboxDao(), profileScope),
        playbackContextStore = RoomPlaybackSessionContextStore(db.playSessionContextDao()),
        sourceRemoval = SourceRemoval(
            database = db,
            sourceDao = db.sourceDao(),
            secretDao = db.secretReferenceDao(),
            serverItemDao = db.serverItemDao(),
            assetDao = db.localAssetDao(),
            availabilityDao = db.availabilityDao(),
            candidateDao = db.playableCandidateDao(),
            watchEventDao = db.watchEventDao(),
            watchStateDao = db.watchStateDao(),
            playSessionContextDao = db.playSessionContextDao(),
            candidateHandoffStore = dev.infusion.core.database.store.RoomCandidateHandoffStore(
                dao = db.candidateHandoffDao(),
                secretStore = secretStoreRef,
            ),
        ),
        scope = appScope,
        transaction = { block -> db.runInTransaction(block) },
        profileScope = profileScope,

        checkpointStore = dev.infusion.core.database.store.RoomConnectorCheckpointStore(
            checkpointDao = db.checkpointDao(),
            scope = profileScope,
        ),

        retirementIntentStore = dev.infusion.core.database.store.RoomRetirementIntentStore(
            dao = db.retirementIntentDao(),
        ),

        convergence = canonicalConvergence,

        ratingsEvidence = jellyfinRatingsEvidence,

        videoPlaybackActive = { videoPlaybackMediaSession.destinationActive.value },
    )

    val jellyfinCoordinator: JellyfinSourceCoordinator by lazy { buildCoordinator() }

    val jellyfinDiscoveryPort: dev.infusion.core.domain.jellyfin.JellyfinDiscoveryPort
        get() = jellyfinDiscovery
            ?: dev.infusion.source.jellyfin.discovery.JellyfinUdpDiscovery()

    private var jellyfinSourceRecord: Pair<JellyfinSourceGraph, String>? = jellyfin?.let {
        it to ProfilePolicy.DEFAULT_PROFILE_ID
    } ?: kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Default) {

        val stateRow = db.jellyfinSourceStateDao().getActive()
        val pendingIntents = db.retirementIntentDao().count()
        if (stateRow != null || pendingIntents > 0) {
            val startupCoordinator = buildCoordinator()
            startupCoordinator.drainPendingExternalRetirements()

            startupCoordinator.repairPersistedCredentialBinding()
        }
        val state = stateRow ?: return@runBlocking null
        val graph = buildCoordinator().restore() ?: return@runBlocking null

        graph.watchConnector.startDraining()
        graph to (db.sourceDao().getById(state.sourceId)?.ownerProfileId
            ?: ProfilePolicy.DEFAULT_PROFILE_ID)
    }

    var jellyfinSource: JellyfinSourceGraph? = null
        private set(value) {
            field = value

            _jellyfinSourceId.value = value?.sourceId?.value?.toString()
            _jellyfinConnected.value = value != null

            if (jellyfinServerProgressOwnerDelegate.isInitialized()) {
                jellyfinServerProgressOwner.invalidate()
            }
        }

    private val _jellyfinSourceId = MutableStateFlow<String?>(null)

    private val _jellyfinConnected = MutableStateFlow(false)
    val jellyfinConnected: StateFlow<Boolean> = _jellyfinConnected.asStateFlow()

    private val jellyfinServerProgressOwnerDelegate:
        Lazy<dev.infusion.tv.ui.presentation.JellyfinServerProgressOwner> = lazy {
        dev.infusion.tv.ui.presentation.JellyfinServerProgressOwner(
            scope = appScope,
            identity = {
                jellyfinSource?.let { graph ->
                    graph.currentUserId()?.let { userId ->
                        dev.infusion.tv.ui.presentation.ServerProgressIdentity(
                            sourceId = graph.sourceId.value.toString(),
                            userId = userId,
                        )
                    }
                }
            },
            resolveNativeIds = { titleIds ->
                val graph = jellyfinSource ?: return@JellyfinServerProgressOwner emptyMap()

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    graph.nativeItemIdsForTitles(titleIds)
                }
            },
            read = { nativeIds ->
                val graph = jellyfinSource ?: return@JellyfinServerProgressOwner null
                when (val answer = graph.userProgressForNativeIds(nativeIds)) {
                    is dev.infusion.source.jellyfin.client.ApiResult.Ok -> answer.value
                    is dev.infusion.source.jellyfin.client.ApiResult.Err -> null
                }
            },
        )
    }

    private val jellyfinServerProgressOwner: dev.infusion.tv.ui.presentation.JellyfinServerProgressOwner
        get() = jellyfinServerProgressOwnerDelegate.value

    var catalog: dev.infusion.core.domain.catalog.CatalogPort = NoSourceCatalogPort()
        private set

    private fun detailsCatalog(): dev.infusion.core.domain.catalog.CatalogPort =
        dev.infusion.source.addon.shells.AddonMetaEnrichingCatalogPort(
            base = addonNativePort(),
            detailsService = addonDetailsService,
        )

    private fun detailsCatalogForEpisode(
        seriesTitleId: dev.infusion.core.model.TitleId,
    ): dev.infusion.core.domain.catalog.CatalogPort =
        dev.infusion.source.addon.shells.AddonMetaEnrichingCatalogPort(
            base = addonNativePort(),
            detailsService = addonDetailsService,
            episodeParentSeriesTitleId = seriesTitleId,
        )

    private fun addonNativePort(): dev.infusion.source.addon.shells.AddonNativeCatalogPort =
        addonInfrastructure.nativeCatalogPort(base = { catalog })

    var resolver: dev.infusion.core.domain.candidate.CandidateResolverPort = NoSourceCandidateResolver()
        private set

    init {

        jellyfinSourceRecord?.let { (graph, owner) ->
            if (owner == ProfilePolicy.DEFAULT_PROFILE_ID) {
                jellyfinSource = graph
                catalog = graph.catalog
                resolver = graph.resolver
            }
        }

    }

    private fun applySourceVisibilityFor(targetProfileId: String) {
        val record = jellyfinSourceRecord ?: run {
            exposeNoSource()
            return
        }
        val (graph, owner) = record
        val primary = profileRepository.primary()
        val viewer = profileRepository.list().firstOrNull { it.profileId == targetProfileId } ?: primary
        if (ProfileSourceVisibility.isVisible(owner, viewer, primary)) {
            jellyfinSource = graph
            catalog = graph.catalog
            resolver = graph.resolver
        } else {
            exposeNoSource()
        }

        appScope.launch { runCatching { libraryRefreshCoordinator.onConfigurationChanged() } }
    }

    private fun exposeNoSource() {
        jellyfinSource = null
        catalog = NoSourceCatalogPort()
        resolver = NoSourceCandidateResolver()

        invalidateLibraryBrowse()
    }

    suspend fun connectJellyfin(
        serverUrl: String,
        username: String,
        password: String,
        consentLan: Boolean,
    ): dev.infusion.source.jellyfin.SetupResult {

        val androidDeviceId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        )
        return applyJellyfinSetup(
            jellyfinCoordinator.setup(
                serverUrl,
                username,
                password,
                deviceId = androidDeviceId,
                consentLan = consentLan,
            ),
        )
    }

    suspend fun connectJellyfinWithApiKey(
        serverUrl: String,
        apiKey: String,
        targetUserId: String?,
        consentLan: Boolean,
    ): dev.infusion.source.jellyfin.SetupResult =
        applyJellyfinSetup(
            jellyfinCoordinator.setupWithApiKey(serverUrl, apiKey, targetUserId, consentLan),
        )

    suspend fun connectJellyfinQuickConnect(
        serverUrl: String,
        consentLan: Boolean,
        onCode: (String) -> Unit,
        shouldCancel: () -> Boolean,
    ): dev.infusion.source.jellyfin.SetupResult =
        applyJellyfinSetup(
            jellyfinCoordinator.connectQuickConnect(
                serverUrl = serverUrl,
                consentLan = consentLan,
                onChallenge = onCode,
                shouldCancel = shouldCancel,
            ),
        )

    private suspend fun applyJellyfinSetup(
        outcome: dev.infusion.source.jellyfin.SetupResult,
    ): dev.infusion.source.jellyfin.SetupResult {
        if (outcome is dev.infusion.source.jellyfin.SetupResult.Ok) {

            jellyfinSource?.watchConnector?.stopPinging()
            jellyfinSource?.watchConnector?.stopDraining()
            jellyfinSource?.close()

            jellyfinSourceRecord = outcome.graph to profileScope.activeProfileId
            jellyfinSource = outcome.graph
            catalog = outcome.graph.catalog
            resolver = outcome.graph.resolver
            outcome.graph.watchConnector.startDraining()
            outcome.graph.catalog.refreshHome()
            refreshJellyfinConnectedUser()

            appScope.launch { runCatching { libraryRefreshCoordinator.onConfigurationChanged() } }
        }
        return outcome
    }

    private val _jellyfinConnectedUser =
        MutableStateFlow<dev.infusion.feature.vertical.setup.JellyfinConnectedUser?>(null)

    val jellyfinConnectedUser: StateFlow<dev.infusion.feature.vertical.setup.JellyfinConnectedUser?> =
        _jellyfinConnectedUser.asStateFlow()

    suspend fun refreshJellyfinConnectedUser() {
        _jellyfinConnectedUser.value = kotlinx.coroutines.withContext(Dispatchers.IO) {

            fun diag(step: String, detail: String = "") {
                dev.infusion.source.jellyfin.session.JellyfinForensicsLog.event(
                    stage = "avatar-present-$step",
                    fields = detail,
                )
            }
            val row = db.jellyfinSourceStateDao().getActive() ?: run {
                diag("no-row")
                return@withContext null
            }
            if (row.userAvatarTag.isNullOrBlank()) {
                diag("tag-absent")

                jellyfinCoordinator.refreshConnectedUserAvatarEvidence()
            }
            val refreshed = db.jellyfinSourceStateDao().getActive() ?: row
            val tag = refreshed.userAvatarTag?.takeIf { it.isNotBlank() }
            val canonicalOrigin = runCatching {
                dev.infusion.core.network.UrlParser.parse(refreshed.origin).origin
            }.getOrNull()
            diag("state", "tag=${tag != null} origin=${canonicalOrigin != null}")
            buildConnectedUser(refreshed, tag)
        }
    }

    private fun buildConnectedUser(
        row: dev.infusion.core.database.entity.JellyfinSourceStateEntity,
        tag: String?,
    ): dev.infusion.feature.vertical.setup.JellyfinConnectedUser {

        val canonicalOrigin = runCatching {
            dev.infusion.core.network.UrlParser.parse(row.origin).origin
        }.getOrNull()
        return dev.infusion.feature.vertical.setup.JellyfinConnectedUser(
            userName = row.username,
            avatarLocator = if (tag != null && canonicalOrigin != null) {
                dev.infusion.core.model.ArtworkLocator.userAvatar(canonicalOrigin, row.userId, tag)
            } else {
                null
            },
        )
    }

    suspend fun removeJellyfin(
        removalScope: dev.infusion.core.database.model.RemovalScope =
            dev.infusion.core.database.model.RemovalScope.REVOKE_ONLY,
    ): Boolean {
        val graph = jellyfinSource ?: return false
        val removedSourceId = graph.sourceId.value.toString()

        runCatching { musicQueueStateHolder.stopAndAwait() }
        jellyfinCoordinator.remove(graph, removalScope)
        graph.close()
        jellyfinSourceRecord = null
        exposeNoSource()

        _jellyfinConnectedUser.value = null

        artworkCache.purgePartition(removedSourceId)

        artworkLoader.purgeAll()

        versionPreferences.clearForSource(removedSourceId)

        appScope.launch { runCatching { libraryRefreshCoordinator.onConfigurationChanged() } }
        return true
    }

    private val titleSupersession: dev.infusion.core.database.store.TitleSupersession by lazy {
        dev.infusion.core.database.store.TitleSupersession(db.titleDao())
    }

    private fun watchEventLedger(): RoomWatchEventLedger =
        RoomWatchEventLedger(db.watchEventDao(), profileScope, titleSupersession)

    private fun watchStateStore(): RoomWatchStateStore =
        RoomWatchStateStore(db.watchStateDao(), profileScope, titleSupersession)

    fun watchlistRecentlyWatchedStateHolder(
        scope: CoroutineScope,
        playbackActive: kotlinx.coroutines.flow.StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
    ): RecentlyWatchedStateHolder =
        RecentlyWatchedStateHolder(
            recentlyWatched = GetRecentlyWatchedUseCase(
                history = RoomWatchedHistorySource(db, profileScope, titleSupersession),
                authority = {
                    val trackerId = trackingPreferencesStore.current().selectedTrackerId
                    dev.infusion.core.domain.watch.RecentlyWatchedAuthority(activeTrackerId = trackerId)
                },

                ioDispatcher = Dispatchers.IO,
            ),
            scope = scope,
            resolve = { entry -> recentlyWatchedFacts(entry) },

            playbackActive = playbackActive,
        )

    suspend fun sweepActiveTrackerWatchedHistory():
        dev.infusion.core.sync.tracking.TrackingWatchedHistorySweepResult? =
        withContext(Dispatchers.IO) {

            if (videoPlaybackMediaSession.destinationActive.value) {
                android.util.Log.i(WATCHED_HISTORY_TAG, "skip reason=playback_active")
                return@withContext null
            }

            if (!watchedHistorySweepInFlight.compareAndSet(false, true)) return@withContext null
            try {
                val trackerId = trackingPreferencesStore.current().selectedTrackerId
                if (trackerId == null) {

                    android.util.Log.i(WATCHED_HISTORY_TAG, "skip reason=on_device_only")
                    return@withContext null
                }
                val port = trackingGraphs.watchedHistoryPorts()[trackerId]
                if (port == null) {

                    android.util.Log.i(WATCHED_HISTORY_TAG, "skip reason=no_history_port tracker=$trackerId")
                    return@withContext null
                }
                dev.infusion.core.sync.tracking.TrackingWatchedHistoryImporter(
                    providerId = trackerId,
                    reader = port,
                    identity = dev.infusion.core.database.tracking.RoomTrackingInboundIdentity(
                        titleDao = db.titleDao(),
                        externalIdDao = db.externalIdDao(),
                        supersession = titleSupersession,
                        editionDao = db.editionDao(),
                    ),
                    identityCompletion = trackingInboundIdentityCompletion,
                    cursorStore = watchedHistoryCursorStore,
                    coordinator = coordinator,

                    isAlreadyImported = { watchKey, observedAt ->
                        coordinator.alreadyImportedCompletion(trackerId, watchKey, observedAt)
                    },
                ).sweep()
                    .also { result ->

                        android.util.Log.i(WATCHED_HISTORY_TAG, result.describe())
                    }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {

                android.util.Log.w(
                    WATCHED_HISTORY_TAG,
                    "sweep_failed type=${failure.javaClass.simpleName} message=${failure.message}",
                )
                null
            } finally {
                watchedHistorySweepInFlight.set(false)
            }
        }

    private val watchedHistorySweepInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    private val watchedHistoryCursorStore:
        dev.infusion.core.database.tracking.SharedPreferencesTrackingWatchedHistoryCursorStore by lazy {
        dev.infusion.core.database.tracking.SharedPreferencesTrackingWatchedHistoryCursorStore(
            context = context,
            profileScope = profileScope,
        )
    }

    private suspend fun recentlyWatchedFacts(
        entry: dev.infusion.core.domain.watch.RecentlyWatchedEntry,
    ): dev.infusion.feature.vertical.watchlist.RecentlyWatchedFacts? {
        val isMovie = entry.kind == dev.infusion.core.domain.watch.RecentlyWatchedKind.MOVIE
        val mediaType = if (isMovie) dev.infusion.core.model.metadata.MediaType.MOVIE else dev.infusion.core.model.metadata.MediaType.SHOW
        val watchlistKind = if (isMovie) {
            dev.infusion.core.domain.watchlist.WatchlistKind.MOVIE
        } else {
            dev.infusion.core.domain.watchlist.WatchlistKind.SHOW
        }
        val proven = runCatching { watchlistIdentityResolver.aliasesFor(entry.titleId, watchlistKind) }.getOrNull()
        val identity = if (proven != null) {
            dev.infusion.core.model.metadata.MediaIdentity(
                type = mediaType,
                titleId = proven.titleId,
                externalIds = dev.infusion.core.model.metadata.ExternalIds(
                    tmdbId = proven.externalIds.tmdbId,
                    imdbId = proven.externalIds.imdbId,
                    tvdbId = proven.externalIds.tvdbId,
                ),
            )
        } else {
            runCatching {
                mediaIdentityResolver.resolve(
                    entry.titleId,
                    mediaType,
                    dev.infusion.core.domain.metadata.MetadataLocale.Default,
                )
            }.getOrNull()
                ?: dev.infusion.core.model.metadata.MediaIdentity(type = mediaType, titleId = entry.titleId)
        }
        val details = withContext(Dispatchers.IO) {
            runCatching {
                metadataRepository.details(identity, dev.infusion.core.domain.metadata.MetadataLocale.Default)
            }.getOrNull()
        } ?: return null
        val artwork = details.artwork
        val poster = artwork.poster
        val backdrop = artwork.backdrop
        val ref = when {
            poster != null -> dev.infusion.core.model.MediaArtworkRef.metadata(
                provider = poster.provider,
                path = poster.path,
                policy = dev.infusion.core.model.ArtworkPolicy.POSTER,
                landscapePath = backdrop?.path,
            )
            backdrop != null -> dev.infusion.core.model.MediaArtworkRef.metadata(
                provider = backdrop.provider,
                path = backdrop.path,
                policy = dev.infusion.core.model.ArtworkPolicy.BACKDROP,
            )
            else -> null
        }
        return dev.infusion.feature.vertical.watchlist.RecentlyWatchedFacts(
            displayTitle = details.title,
            year = details.year,
            artwork = ref,
        )
    }

    private val trackingPort = dev.infusion.core.domain.tracking.MutableTrackingServicePort()

    private val watchOutbox: RoomWatchOutbox by lazy {
        RoomWatchOutbox(db.outboxDao(), profileScope)
    }

    val coordinator: WatchSyncCoordinator by lazy {
        WatchSyncCoordinator(
            ledger = RoomWatchEventLedger(db.watchEventDao(), profileScope, titleSupersession),
            stateStore = RoomWatchStateStore(db.watchStateDao(), profileScope, titleSupersession),
            outbox = watchOutbox,
            checkpointStore = RoomConnectorCheckpointStore(db.checkpointDao(), profileScope),
            trackingPort = trackingPort,

            selectionGate = watchTrackingSelectionGate,

            localEventObserver = { event -> jellyfinSource?.watchConnector?.onLocalEvent(event) },

            removedSourceIds = { db.sourceDao().listRemovedSourceIds().toSet() },
        )
    }

    private suspend fun ensureLocalProfile() = withContext(Dispatchers.IO) {
        profileRepository.ensurePrimaryProfile()
    }

    suspend fun resumePositionMs(watchKey: dev.infusion.core.model.watch.WatchKey): Long? =
        localResumeObservation(watchKey)?.positionMs

    suspend fun localResumeObservation(
        watchKey: dev.infusion.core.model.watch.WatchKey,
    ): dev.infusion.feature.vertical.media.LocalResumeObservation? =
        withContext(Dispatchers.IO) {
            ensureLocalProfile()
            val state = coordinator.materialize(watchKey)
            dev.infusion.feature.vertical.media.LocalResumeObservation(
                positionMs = state.positionMs,
                observedAt = state.lastMeaningfulAt,
            )
        }

    private val trackingCredentialStore: dev.infusion.core.database.tracking.SharedPreferencesTrackingCredentialStore by lazy {
        dev.infusion.core.database.tracking.SharedPreferencesTrackingCredentialStore(
            context = context,
            profileScope = profileScope,
            secretStore = { secretStoreRef },
        )
    }

    private val acquisitionConnectionStore: dev.infusion.core.database.acquisition.SharedPreferencesAcquisitionConnectionStore by lazy {
        dev.infusion.core.database.acquisition.SharedPreferencesAcquisitionConnectionStore(
            context = context,
            profileScope = profileScope,
            secretStore = { secretStoreRef },
        )
    }

    fun acquisitionConnectionStateHolder(
        scope: CoroutineScope,
        service: dev.infusion.core.domain.acquisition.AcquisitionService,
    ): dev.infusion.feature.vertical.acquisition.AcquisitionConnectionStateHolder =
        dev.infusion.feature.vertical.acquisition.AcquisitionConnectionStateHolder(
            scope = scope,
            serviceId = service.id,
            serviceLabel = when (service) {
                dev.infusion.core.domain.acquisition.AcquisitionService.RADARR -> "Radarr"
                dev.infusion.core.domain.acquisition.AcquisitionService.SONARR -> "Sonarr"
            },
            loadAction = { url, apiKey ->
                dev.infusion.source.acquisition.acquisitionClient(service, url, apiKey).fold(
                    onSuccess = { client ->
                        try {
                            client.load()
                        } finally {
                            client.close()
                        }
                    },
                    onFailure = { error ->
                        dev.infusion.core.domain.acquisition.AcquisitionTestOutcome.Failed(
                            kind = dev.infusion.core.domain.acquisition.AcquisitionFailureKind.POLICY,
                            status = null,
                            message = error.message ?: "invalid server url",
                        )
                    },
                )
            },
            store = acquisitionConnectionStore,
            initial = acquisitionConnectionStore.connection(service.id),
        )

    private val acquisitionOperationStore:
        dev.infusion.core.database.acquisition.SharedPreferencesAcquisitionOperationStore by lazy {
        dev.infusion.core.database.acquisition.SharedPreferencesAcquisitionOperationStore(
            context = context,
            profileScope = profileScope,
        )
    }

    private val cardAvailabilityState:
        kotlinx.coroutines.flow.StateFlow<dev.infusion.tv.ui.presentation.CardAvailability> by lazy {
        cardAvailability.stateIn(
            appScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            dev.infusion.tv.ui.presentation.CardAvailability.None,
        )
    }

    val acquisitionCoordinator: dev.infusion.tv.acquisition.AcquisitionCoordinator by lazy {
        dev.infusion.tv.acquisition.AcquisitionCoordinator(
            scope = appScope,
            profileScope = profileScope,
            store = acquisitionOperationStore,
            connectionStore = acquisitionConnectionStore,
            identity = watchlistIdentityResolver,
            portFactory = { serviceId, connection, apiKey ->
                when (serviceId) {
                    dev.infusion.core.domain.acquisition.AcquisitionService.RADARR.id ->
                        dev.infusion.source.acquisition.radarrAcquisitionAdapter(connection, apiKey).getOrNull()
                    dev.infusion.core.domain.acquisition.AcquisitionService.SONARR.id ->
                        dev.infusion.source.acquisition.sonarrAcquisitionAdapter(connection, apiKey).getOrNull()
                    else -> null
                }
            },
            availability = { cardAvailabilityState.value },
            onChanged = {},
            onDiagnostic = { reason -> android.util.Log.i("live-refresh", reason) },

            onFileProven = { _ ->
                libraryRefreshCoordinator.requestServiceRefresh(
                    dev.infusion.source.jellyfin.events.JellyfinLibraryChangeFeed.SERVICE_ID,
                )
            },

            onProvenImportTargets = { targets ->
                targets.forEach { target ->
                    libraryRefreshCoordinator.markImportProven(
                        target,
                        dev.infusion.source.jellyfin.events.JellyfinLibraryChangeFeed.SERVICE_ID,
                    )
                }
            },
        )
    }

    private val trackingPreferencesStore: dev.infusion.core.domain.tracking.TrackingPreferencesStore by lazy {
        dev.infusion.core.database.tracking.SharedPreferencesTrackingPreferencesStore(
            context = context,
            profileScope = profileScope,
        )
    }

    private val watchTrackingSelectionGate: dev.infusion.core.domain.tracking.WatchTrackingSelectionGate by lazy {
        trackingPreferencesStore.watchTrackingSelectionGate()
    }

    private val trackingMediaResolver: dev.infusion.core.database.tracking.RoomTrackingMediaResolver by lazy {
        dev.infusion.core.database.tracking.RoomTrackingMediaResolver(
            titleDao = db.titleDao(),
            externalIdDao = db.externalIdDao(),
            supersession = titleSupersession,
            editionDao = db.editionDao(),
        )
    }

    private val appVersionLabel: String by lazy {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "0.0.0"
    }

    private val appVersionCode: Long by lazy {
        runCatching {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                packageInfo.versionCode.toLong()
            }
        }.getOrNull() ?: 0L
    }

    private val updateControllerDelegate = lazy {
        val client = dev.infusion.core.network.OkHttpHardenedClient()
        val storage = dev.infusion.core.update.DirectoryUpdateStorage(
            java.io.File(context.cacheDir, "updates"),
        )
        val downloader = dev.infusion.core.update.UpdateDownloader(client)
        dev.infusion.tv.updater.UpdateController(
            scope = appScope,
            checkSource = dev.infusion.core.update.GitHubReleaseDiscovery(client),
            installer = dev.infusion.tv.updater.AndroidUpdateInstaller(
                context = context,
                downloader = downloader,
                verifier = dev.infusion.tv.updater.AndroidApkVerifier(context),
                storage = storage,
            ),
            storage = storage,

            installedVersionCode = { appVersionCode },
        )
    }

    val updateController: dev.infusion.tv.updater.UpdateController get() = updateControllerDelegate.value

    private val trackingRuntimeSource: dev.infusion.core.domain.tracking.TrackingRuntimeSourcePort by lazy {
        dev.infusion.tv.tracking.CanonicalTrackingRuntime(
            measured = dev.infusion.core.database.tracking.RoomTrackingRuntimeSource(
                watchStateDao = db.watchStateDao(),
                profileScope = profileScope,
                supersession = titleSupersession,
            ),
            identityResolver = mediaIdentityResolver,
            metadata = metadataRepository,
            locale = metadataLocale,
        )
    }

    private val trackingInboundCursorStore:
        dev.infusion.core.database.tracking.SharedPreferencesTrackingInboundCursorStore by lazy {
        dev.infusion.core.database.tracking.SharedPreferencesTrackingInboundCursorStore(
            context = context,
            profileScope = profileScope,
        )
    }

    private val trackingInboundIdentityCompletion:
        dev.infusion.core.domain.tracking.TrackingInboundIdentityCompletionPort by lazy {
        dev.infusion.core.database.tracking.RoomTrackingInboundIdentityCompletion(
            titleDao = db.titleDao(),
            externalIdDao = db.externalIdDao(),
            identityStore = dev.infusion.core.database.store.RoomMediaIdentityStore(
                externalIdDao = db.externalIdDao(),
                titleDao = db.titleDao(),
                transaction = { block -> db.runInTransaction(block) },
            ),
            rootTitleStore = dev.infusion.core.database.store.RoomCanonicalRootTitleStore(
                titleDao = db.titleDao(),
                transaction = { block -> db.runInTransaction(block) },
            ),
        )
    }

    private val trackingWatchedCacheStore:
        dev.infusion.core.database.tracking.SharedPreferencesTrackingWatchedCacheStore by lazy {
        dev.infusion.core.database.tracking.SharedPreferencesTrackingWatchedCacheStore(
            context = context,
            profileScope = profileScope,
        )
    }

    val watchedStateDemand: dev.infusion.feature.vertical.details.WatchedStateDemand by lazy {
        dev.infusion.feature.vertical.details.WatchedStateDemand { watchKey ->
            trackingGraphs.demandWatchedState(watchKey)
        }
    }

    val cardWatchedState:
        kotlinx.coroutines.flow.StateFlow<dev.infusion.tv.ui.presentation.CardWatchedState> by lazy {
        watchStateStore().observeAll()
            .map { states -> dev.infusion.tv.ui.presentation.CardWatchedState.of(states) }
            .stateIn(
                appScope,
                kotlinx.coroutines.flow.SharingStarted.Eagerly,
                dev.infusion.tv.ui.presentation.CardWatchedState.None,
            )
    }

    val cardWatchedDemand: dev.infusion.tv.ui.presentation.CardWatchedDemand by lazy {
        dev.infusion.tv.ui.presentation.CoalescingCardWatchedDemand(
            demand = watchedStateDemand,
            scope = appScope,
        )
    }

    val cardProgress:
        kotlinx.coroutines.flow.Flow<dev.infusion.tv.ui.presentation.CardProgress> by lazy {
        combine(
            watchStateStore().observeAll(),
            cardServerProgress,
        ) { states, server ->
            dev.infusion.tv.ui.presentation.CardProgress.of(
                states = states,
                server = server,
            )
        }.distinctUntilChanged()
    }

    val cardServerProgress:
        kotlinx.coroutines.flow.StateFlow<dev.infusion.tv.ui.presentation.CardServerProgress> by lazy {
        jellyfinServerProgressOwner.progress
    }

    val cardResumeDemand: dev.infusion.tv.ui.presentation.CardResumeDemand by lazy {
        jellyfinServerProgressOwner
    }

    val simklTracking: dev.infusion.tv.tracking.SimklTrackingGraph by lazy {
        dev.infusion.tv.tracking.SimklTrackingGraph(
            scope = appScope,
            profileScope = profileScope,
            credentialStore = trackingCredentialStore,
            outbox = watchOutbox,
            resolver = trackingMediaResolver,
            trackingServicePort = trackingPort,
            watchTrackingSelectionGate = watchTrackingSelectionGate,

            preferencesStore = trackingPreferencesStore,
            coordinator = coordinator,
            inboundIdentity = dev.infusion.core.database.tracking.RoomTrackingInboundIdentity(
                titleDao = db.titleDao(),
                externalIdDao = db.externalIdDao(),
                supersession = titleSupersession,
                editionDao = db.editionDao(),
            ),
            inboundIdentityCompletion = trackingInboundIdentityCompletion,
            runtimeSource = trackingRuntimeSource,
            inboundCursorStore = trackingInboundCursorStore,
            watchedCacheStore = trackingWatchedCacheStore,
            clientId = simklClientIdSeam ?: dev.infusion.tv.tracking.GeneratedSimklCredential.clientId(),
            appVersion = appVersionLabel,
            hub = simklHubSeam ?: dev.infusion.core.network.OkHttpHardenedClient(),
        )
    }

    val traktTracking: dev.infusion.tv.tracking.TraktTrackingGraph by lazy {
        dev.infusion.tv.tracking.TraktTrackingGraph(
            scope = appScope,
            profileScope = profileScope,
            credentialStore = trackingCredentialStore,
            outbox = watchOutbox,
            resolver = trackingMediaResolver,
            trackingServicePort = trackingPort,
            watchTrackingSelectionGate = watchTrackingSelectionGate,
            preferencesStore = trackingPreferencesStore,
            coordinator = coordinator,
            inboundIdentity = dev.infusion.core.database.tracking.RoomTrackingInboundIdentity(
                titleDao = db.titleDao(),
                externalIdDao = db.externalIdDao(),
                supersession = titleSupersession,
                editionDao = db.editionDao(),
            ),
            inboundIdentityCompletion = trackingInboundIdentityCompletion,
            runtimeSource = trackingRuntimeSource,
            inboundCursorStore = trackingInboundCursorStore,
            watchedCacheStore = trackingWatchedCacheStore,
            clientId = traktClientIdSeam ?: dev.infusion.tv.tracking.GeneratedTraktCredential.clientId(),
            clientSecret = traktClientSecretSeam ?: dev.infusion.tv.tracking.GeneratedTraktCredential.clientSecret(),
            appVersion = appVersionLabel,
            hub = traktHubSeam ?: dev.infusion.core.network.OkHttpHardenedClient(),
        )
    }

    val mdblistTracking: dev.infusion.tv.tracking.MDBListTrackingGraph by lazy {
        dev.infusion.tv.tracking.MDBListTrackingGraph(
            scope = appScope,
            profileScope = profileScope,
            credentialStore = trackingCredentialStore,
            outbox = watchOutbox,
            resolver = trackingMediaResolver,
            trackingServicePort = trackingPort,
            watchTrackingSelectionGate = watchTrackingSelectionGate,
            preferencesStore = trackingPreferencesStore,
            coordinator = coordinator,
            inboundIdentity = dev.infusion.core.database.tracking.RoomTrackingInboundIdentity(
                titleDao = db.titleDao(),
                externalIdDao = db.externalIdDao(),
                supersession = titleSupersession,
                editionDao = db.editionDao(),
            ),
            inboundIdentityCompletion = trackingInboundIdentityCompletion,
            runtimeSource = trackingRuntimeSource,
            inboundCursorStore = trackingInboundCursorStore,
            watchedCacheStore = trackingWatchedCacheStore,
            clientId = mdblistClientIdSeam ?: dev.infusion.tv.tracking.GeneratedMDBListCredential.clientId(),
            appVersion = appVersionLabel,
            hub = mdblistHubSeam ?: dev.infusion.core.network.OkHttpHardenedClient(),
        )
    }

    val trackingGraphs: dev.infusion.tv.tracking.TrackingGraphs by lazy {
        dev.infusion.tv.tracking.TrackingGraphs(
            scope = appScope,
            preferencesStore = trackingPreferencesStore,
            outbox = watchOutbox,
            coordinator = coordinator,
            simkl = simklTracking,
            trakt = traktTracking,
            mdblist = mdblistTracking,
        )
    }

    val watchlistStore: dev.infusion.core.database.watchlist.SharedPreferencesWatchlistStore by lazy {
        dev.infusion.core.database.watchlist.SharedPreferencesWatchlistStore(
            context = context,
            profileScope = profileScope,
        )
    }

    val cardContextMenuHost: dev.infusion.tv.ui.presentation.CardContextMenuHostState by lazy {
        dev.infusion.tv.ui.presentation.CardContextMenuHostState()
    }

    val watchlistIdentityResolver: dev.infusion.core.domain.watchlist.WatchlistIdentityResolver by lazy {
        dev.infusion.core.database.watchlist.RoomWatchlistIdentityResolver(
            titleDao = db.titleDao(),
            externalIdDao = db.externalIdDao(),
            supersession = titleSupersession,
            editionDao = db.editionDao(),
        )
    }

    private val watchlistIdentityCompletion: dev.infusion.core.domain.watchlist.WatchlistIdentityCompletion by lazy {
        dev.infusion.core.database.watchlist.RoomWatchlistIdentityCompletion(
            titleDao = db.titleDao(),
            externalIdDao = db.externalIdDao(),
            identityStore = dev.infusion.core.database.store.RoomMediaIdentityStore(
                externalIdDao = db.externalIdDao(),
                titleDao = db.titleDao(),
                transaction = { block -> db.runInTransaction(block) },
            ),
            rootTitleStore = dev.infusion.core.database.store.RoomCanonicalRootTitleStore(
                titleDao = db.titleDao(),
                transaction = { block -> db.runInTransaction(block) },
            ),
        )
    }

    val watchlistCoordinator: dev.infusion.tv.watchlist.WatchlistCoordinator by lazy {
        dev.infusion.tv.watchlist.WatchlistCoordinator(
            store = watchlistStore,
            profileScope = profileScope,
            scope = appScope,
            watchCoordinator = coordinator,
            outbox = watchOutbox,
            identity = watchlistIdentityResolver,
            identityCompletion = watchlistIdentityCompletion,
            memberships = { membershipsByProvider() },
            activeTracker = { trackingPreferencesStore.current().selectedTrackerId },
            accountRef = { providerId ->
                runCatching { trackingCredentialStore.credentials(providerId)?.accountLabel }.getOrNull()
            },

            isResumeEligible = { unit -> cardWatchedState.value.isInProgress(unit) },
            isWatchedUnit = { unit -> cardWatchedState.value.isWatched(unit) },

            acquisition = acquisitionCoordinator,

            ratings = personalRatingsCoordinator,
        )
    }

    val personalRatingsCoordinator: dev.infusion.tv.ratings.PersonalRatingsCoordinator by lazy {
        dev.infusion.tv.ratings.PersonalRatingsCoordinator(
            profileScope = profileScope,
            scope = appScope,
            outbox = watchOutbox,
            ratings = { ratingsByProvider() },
            activeTracker = { trackingPreferencesStore.current().selectedTrackerId },
            accountRef = { providerId ->
                runCatching { trackingCredentialStore.credentials(providerId)?.accountId }.getOrNull()
            },

            aliasesFor = { titleId, kind -> watchlistIdentityResolver.aliasesFor(titleId, kind) },
        )
    }

    private fun ratingsByProvider(): Map<String, dev.infusion.core.domain.ratings.PersonalRatingPort> =
        mapOf(
            traktTracking.provider.providerId to traktTracking.personalRatings,
            simklTracking.provider.providerId to simklTracking.personalRatings,
            mdblistTracking.provider.providerId to mdblistTracking.personalRatings,
        )

    val personalRatingsRevision: kotlinx.coroutines.flow.Flow<Long> =
        personalRatingsCoordinator.revision

    private fun membershipsByProvider(): Map<String, dev.infusion.core.domain.watchlist.WatchlistMembershipPort> =
        mapOf(
            traktTracking.provider.providerId to traktTracking.watchlistMembership,
            simklTracking.provider.providerId to simklTracking.watchlistMembership,
            mdblistTracking.provider.providerId to mdblistTracking.watchlistMembership,
        )

    val cardWatchlistMembership: Flow<dev.infusion.tv.ui.presentation.CardWatchlistMembership> =
        watchlistCoordinator.state
            .map { snapshot ->
                val tracker = watchlistCoordinator.activeTrackerIdOrNull()
                dev.infusion.tv.ui.presentation.CardWatchlistMembership.of(
                    snapshot = snapshot,
                    activeTracker = tracker,
                    activeAccountRef = tracker?.let(::activeTrackingAccountRef),
                )
            }
            .distinctUntilChanged()

    private fun activeTrackingAccountRef(providerId: String): String? =
        runCatching { trackingCredentialStore.credentials(providerId)?.accountLabel }.getOrNull()

    val cardAvailability: Flow<dev.infusion.tv.ui.presentation.CardAvailability> =
        combine(
            _jellyfinSourceId,
            db.availabilityDao().observeServerAvailability(),
            db.availabilityDao().observeSeriesWithEpisode(),
            db.availabilityDao().observeServerEpisodeCoordinates(),
        ) { sourceId, availability, series, episodeCoordinates ->
            if (sourceId == null) {
                dev.infusion.tv.ui.presentation.CardAvailability.None
            } else {
                reduceCardAvailability(sourceId, availability, series, episodeCoordinates)
            }
        }.distinctUntilChanged()

    private suspend fun reduceCardAvailability(
        sourceId: String,
        availability: List<dev.infusion.core.database.dao.ServerAvailabilityRow>,
        series: List<dev.infusion.core.database.dao.ServerEpisodeSeriesRow>,
        episodeCoordinates: List<dev.infusion.core.database.dao.ServerEpisodeCoordinateRow>,
    ): dev.infusion.tv.ui.presentation.CardAvailability {
        val unitIds = mirroredUnitTitleIds(availability, sourceId)
        val seriesIds = LinkedHashSet<String>()
        series.filter { it.sourceId == sourceId }.forEach { seriesIds += it.seriesTitleId }
        val episodeKeys = LinkedHashSet<String>()
        episodeCoordinates.filter { it.sourceId == sourceId }.forEach {
            episodeKeys += dev.infusion.tv.ui.presentation.CardAvailability.episodeKey(
                it.seriesTitleId,
                it.seasonNumber,
                it.episodeNumber,
            )
        }
        if (unitIds.isEmpty() && seriesIds.isEmpty() && episodeKeys.isEmpty()) {
            return dev.infusion.tv.ui.presentation.CardAvailability.None
        }

        val linkedUnits = if (unitIds.isEmpty()) emptySet() else convergenceLinked(unitIds.toList())
        val linkedSeries = if (seriesIds.isEmpty()) emptySet() else convergenceLinked(seriesIds.toList())
        val linkedEpisodeKeys = if (seriesIds.isEmpty()) {
            emptySet()
        } else {
            val linkedSeriesIds = convergenceLinked(seriesIds.toList())
            episodeCoordinates.asSequence()
                .filter { it.sourceId == sourceId && it.seriesTitleId in linkedSeriesIds }
                .mapTo(LinkedHashSet()) {
                    dev.infusion.tv.ui.presentation.CardAvailability.episodeKey(
                        it.seriesTitleId,
                        it.seasonNumber,
                        it.episodeNumber,
                    )
                }
        }
        return dev.infusion.tv.ui.presentation.CardAvailability(
            provenUnitTitleIds = unitIds + linkedUnits,
            provenSeriesTitleIds = seriesIds + linkedSeries,
            provenEpisodeKeys = episodeKeys + linkedEpisodeKeys,
        )
    }

    private suspend fun convergenceLinked(titleIds: List<String>): Set<String> =
        db.availabilityDao().supersededTitleIds(titleIds).mapTo(LinkedHashSet()) { it.titleId }

    private fun mirroredUnitTitleIds(
        availability: List<dev.infusion.core.database.dao.ServerAvailabilityRow>,
        sourceId: String,
    ): Set<String> = availability.asSequence()
        .filter {
            it.sourceId == sourceId &&
                (it.kind == dev.infusion.core.model.TitleKind.MOVIE.name ||
                    it.kind == dev.infusion.core.model.TitleKind.EPISODE.name)
        }
        .mapTo(LinkedHashSet()) { it.titleId }

    fun onAppForeground() {

        runCatching {
            trackingGraphs.bindResumeResetGuard(
                guard = { key -> watchlistCoordinator.blocksInboundResume(key) },
                onRead = { startedAt, completed, seen ->
                    watchlistCoordinator.notePlaybackRead(startedAt, completed, seen)
                },
            )
        }
        trackingGraphs.refreshInbound()

        watchlistCoordinator.refresh()

        acquisitionCoordinator.observeForeground()

        if (jellyfinServerProgressOwnerDelegate.isInitialized()) {
            jellyfinServerProgressOwner.refreshVisible()
        }

        appScope.launch {
            runCatching {
                wireRefreshConfigurationOnce()
                libraryRefreshCoordinator.start()
            }
        }
    }

    fun onAppBackground() {
        libraryRefreshCoordinator.stop()
    }

    fun trackingSettingsStateHolders(scope: CoroutineScope) =
        trackingGraphs.settingsStateHolders(scope, jellyfinConnected = { jellyfinConnected.value })

    fun trackingSettingsStateHolder(scope: CoroutineScope) =
        trackingSettingsStateHolders(scope).first { it.providerId == simklTracking.provider.providerId }

    suspend fun applyTrackingRegistration() = trackingGraphs.applyRegistration()

    @Volatile
    private var trackingStarted = false

    fun startTrackingDelivery() {
        if (trackingStarted) return
        trackingStarted = true
        trackingGraphs.startTrackingDelivery()
    }

    private val trackingProfileCredentialRevoker:
        dev.infusion.core.domain.profile.ProfileTrackingCredentialRevoker by lazy {
        dev.infusion.core.domain.profile.ProfileTrackingCredentialRevoker { profileId ->
            withContext(Dispatchers.IO) {
                runCatching {
                    trackingCredentialStore.clearProfile(simklTracking.provider.providerId, profileId)
                }
                runCatching {
                    trackingCredentialStore.clearProfile(traktTracking.provider.providerId, profileId)
                }

                runCatching {
                    acquisitionConnectionStore.clearProfile(profileId)
                }

                runCatching {
                    acquisitionOperationStore.clearProfile(profileId)
                }
            }
        }
    }

    val continueWatching: GetContinueWatchingUseCase by lazy {
        GetContinueWatchingUseCase(RoomWatchStateStore(db.watchStateDao(), profileScope, titleSupersession))
    }

    suspend fun browseCatalog(
        parentNativeId: String?,
        startIndex: Int,
        limit: Int,
    ): dev.infusion.core.domain.catalog.CatalogPage? =
        withContext(Dispatchers.IO) {
            catalog.browse(parentNativeId, startIndex, limit)
        }

    suspend fun searchCatalog(
        searchTerm: String,
        startIndex: Int = 0,
        limit: Int = 100,
    ): dev.infusion.core.domain.catalog.CatalogPage? =
        withContext(Dispatchers.IO) {
            catalog.search(searchTerm, startIndex, limit)
        }

    suspend fun catalogChildren(
        parentNativeId: String,
        startIndex: Int = 0,
        limit: Int = 100,
    ): dev.infusion.core.domain.catalog.CatalogPage? =
        withContext(Dispatchers.IO) {
            catalog.children(parentNativeId, startIndex, limit)
        }

    suspend fun catalogImage(
        nativeItemId: String,
        type: String = "Primary",
        rendition: dev.infusion.core.model.ArtworkRendition = dev.infusion.core.model.ArtworkRendition.Unbounded,
    ): dev.infusion.core.domain.catalog.CatalogImage? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            artworkCache.getOrLoad(
                dev.infusion.core.artworkcache.ArtworkCacheKey(
                    artworkPartition(),
                    nativeItemId,
                    type,
                    rendition.token,
                ),
            ) { catalog.image(nativeItemId, type, rendition) }
        }

    suspend fun addonArtwork(
        addonId: String,
        nativeItemId: String,
    ): dev.infusion.core.domain.catalog.CatalogImage? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            artworkCache.getOrLoad(
                dev.infusion.core.artworkcache.ArtworkCacheKey("addon:$addonId", nativeItemId, "poster"),
            ) { addonResources.artwork.poster(addonId, nativeItemId) }
        }

    suspend fun addonLandscapeArtwork(
        addonId: String,
        nativeItemId: String,
    ): dev.infusion.core.domain.catalog.CatalogImage? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            artworkCache.getOrLoad(
                dev.infusion.core.artworkcache.ArtworkCacheKey("addon:$addonId", nativeItemId, "landscape"),
            ) { addonResources.artwork.landscape(addonId, nativeItemId) }
        }

    suspend fun addonEpisodeStill(
        nativeItemId: String,
    ): dev.infusion.core.domain.catalog.CatalogImage? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val enabled = addonManager.installedAddonIds().filter { addonId ->
                when (val st = addonRepository.installed.value) {
                    is dev.infusion.source.addon.repository.AddonRepositoryState.Ready ->
                        st.installed.any { it.addonId == addonId && it.enabled }
                    else -> false
                }
            }
            for (addonId in enabled) {
                val image = artworkCache.getOrLoad(
                    dev.infusion.core.artworkcache.ArtworkCacheKey("addon:$addonId", nativeItemId, "still"),
                ) { addonResources.artwork.poster(addonId, nativeItemId) }
                if (image != null) return@withContext image
            }
            null
        }

    private fun parentShowBackdropSeamFor(
        seriesTitleId: dev.infusion.core.model.TitleId,
    ): suspend () -> String? =
        {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val locale = metadataLocale()
                val identity = runCatching {
                    mediaIdentityResolver.resolve(
                        seriesTitleId,
                        dev.infusion.core.model.metadata.MediaType.SHOW,
                        locale,
                    )
                }.getOrNull()
                val backdrop = identity?.let { resolved ->
                    runCatching {
                        metadataRepository.observeDetails(resolved, locale).first().details?.artwork?.backdrop
                    }.getOrNull()
                }
                dev.infusion.feature.vertical.details.EpisodeDetailsArtwork
                    .backgroundLocator(backdrop)
            }
        }

    private fun parentShowLogoSeamFor(
        seriesTitleId: dev.infusion.core.model.TitleId,
    ): suspend () -> String? =
        {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val locale = metadataLocale()
                val identity = runCatching {
                    mediaIdentityResolver.resolve(
                        seriesTitleId,
                        dev.infusion.core.model.metadata.MediaType.SHOW,
                        locale,
                    )
                }.getOrNull()
                val logo = identity?.let { resolved ->
                    runCatching {
                        metadataRepository.observeDetails(resolved, locale).first().details?.artwork?.logo
                    }.getOrNull()
                }
                dev.infusion.feature.vertical.details.EpisodeDetailsArtwork
                    .logoLocator(logo)
            }
        }

    fun sourcesLogoSeamFor(
        seriesTitleId: dev.infusion.core.model.TitleId,
    ): suspend () -> String? = parentShowLogoSeamFor(seriesTitleId)

    fun sourcesPosterSeamFor(
        titleId: dev.infusion.core.model.TitleId,
        mediaType: dev.infusion.core.model.metadata.MediaType,
    ): suspend () -> String? =
        {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val locale = metadataLocale()
                val identity = runCatching {
                    mediaIdentityResolver.resolve(titleId, mediaType, locale)
                }.getOrNull()
                val poster = identity?.let { resolved ->
                    runCatching {
                        metadataRepository.observeDetails(resolved, locale).first().details?.artwork?.poster
                    }.getOrNull()
                }
                poster?.let { ref ->
                    dev.infusion.core.model.MediaArtworkRef.metadata(
                        provider = ref.provider.ifBlank {
                            dev.infusion.core.model.metadata.ArtworkSizeBuckets.PROVIDER_TMDB
                        },
                        path = ref.path,
                        policy = dev.infusion.core.model.ArtworkPolicy.POSTER,
                    ).locator
                }
            }
        }

    private val artworkCache: dev.infusion.core.artworkcache.BoundedArtworkCache by lazy {
        dev.infusion.core.artworkcache.BoundedArtworkCache(
            memoryQuotaBytes = 24L * 1024 * 1024,
            diskQuotaBytes = 96L * 1024 * 1024,
            diskRoot = java.io.File(context.cacheDir, "artwork"),
            partitionDiskQuotaBytes = 64L * 1024 * 1024,
        )
    }

    private val artworkBytesSource: dev.infusion.core.artworkimage.ArtworkBytesSource by lazy {
        dev.infusion.core.artworkimage.SeamArtworkBytesSource(artworkImageSeam)
    }

    private val artworkImageSeam: dev.infusion.core.artworkimage.ArtworkSeam by lazy {
        object : dev.infusion.core.artworkimage.ArtworkSeam {
                    override suspend fun catalogImage(
                        nativeItemId: String,
                        type: String,
                    ): ByteArray? = this@AppContainer.catalogImage(nativeItemId, type)?.bytes

                    override suspend fun catalogImage(
                        nativeItemId: String,
                        type: String,
                        rendition: dev.infusion.core.model.ArtworkRendition,
                    ): ByteArray? = this@AppContainer.catalogImage(nativeItemId, type, rendition)?.bytes

                    override suspend fun addonArtwork(
                        addonId: String,
                        nativeItemId: String,
                    ): ByteArray? = this@AppContainer.addonArtwork(addonId, nativeItemId)?.bytes

                    override suspend fun addonLandscapeArtwork(
                        addonId: String,
                        nativeItemId: String,
                    ): ByteArray? =
                        this@AppContainer.addonLandscapeArtwork(addonId, nativeItemId)?.bytes

                    override suspend fun addonEpisodeStill(
                        nativeItemId: String,
                    ): ByteArray? = this@AppContainer.addonEpisodeStill(nativeItemId)?.bytes

                    override suspend fun metadataImage(
                        provider: String,
                        size: String,
                        path: String,
                    ): ByteArray? = this@AppContainer.metadataImage(provider, size, path)?.bytes

                    override suspend fun collectionArtwork(
                        packId: String,
                        groupKey: String,
                        tileKey: String,
                        slot: String,
                    ): ByteArray? =
                        this@AppContainer.collectionArtwork(packId, groupKey, tileKey, slot)?.bytes

                    override suspend fun userAvatar(
                        origin: String,
                        userId: String,
                        tag: String,
                    ): ByteArray? {

                        fun stage(step: String, detail: String) {
                            dev.infusion.source.jellyfin.session.JellyfinForensicsLog.event(
                                stage = "avatar-$step",
                                fields = "user=${dev.infusion.source.jellyfin.session.JellyfinForensicsLog.opaque(userId)} $detail",
                            )
                        }
                        val graph = jellyfinSource ?: run { stage("seam", "no-graph"); return null }
                        if (graph.canonicalSource.endpoint != origin) {
                            stage("seam", "origin-mismatch")
                            return null
                        }
                        val session = graph.source.session() ?: run { stage("seam", "no-session"); return null }
                        if (session.userId != userId) {
                            stage("seam", "user-mismatch")
                            return null
                        }

                        return when (val fetch = graph.catalog.userImage(session, userId, tag)) {
                            is dev.infusion.source.jellyfin.client.ApiResult.Ok -> {
                                stage("fetch", "ok status=${fetch.status} bytes=${fetch.value.bytes.size}")
                                fetch.value.bytes
                            }
                            is dev.infusion.source.jellyfin.client.ApiResult.Err -> {
                                stage(
                                    "fetch",
                                    "err kind=${fetch.failure.kind} status=${fetch.failure.status ?: "none"}",
                                )
                                null
                            }
                        }
                    }
                }
    }

    val artworkLoader: dev.infusion.core.artworkimage.ArtworkLoader by lazy {
        dev.infusion.core.artworkimage.ArtworkLoader(
            bytesSource = artworkBytesSource,

            sourceVariants = liveArtworkSourceVariants,

            metrics = if (isDebuggable) {
                dev.infusion.core.artworkimage.LogcatArtworkDecodeMetrics()
            } else {
                dev.infusion.core.artworkimage.ArtworkDecodeMetrics.None
            },

            loadMetrics = if (isDebuggable) {
                dev.infusion.core.artworkimage.LogcatArtworkLoadMetrics()
            } else {
                dev.infusion.core.artworkimage.ArtworkLoadMetrics.None
            },
        )
    }

    private val isDebuggable: Boolean
        get() = (context.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun artworkSourceVariantsFor(
        backdropQuality: dev.infusion.core.domain.appearance.TmdbBackdropQuality,
        landscapeCardQuality: dev.infusion.core.domain.appearance.TmdbLandscapeCardQuality,
    ): dev.infusion.core.artworkimage.ArtworkSourceVariants =
        object : dev.infusion.core.artworkimage.ArtworkSourceVariants {
            override fun resolve(
                request: dev.infusion.core.artworkimage.ArtworkRequest,
            ): dev.infusion.core.artworkimage.ArtworkRequest =
                resolveArtworkSourceVariant(request, backdropQuality, landscapeCardQuality)

            override fun variantToken(
                request: dev.infusion.core.artworkimage.ArtworkRequest,
            ): String = variantTokenFor(
                request = request,
                backdropQuality = backdropQuality,
                landscapeCardQuality = landscapeCardQuality,
            )
        }

    private val liveArtworkSourceVariants: dev.infusion.core.artworkimage.ArtworkSourceVariants =
        object : dev.infusion.core.artworkimage.ArtworkSourceVariants {
            override fun resolve(
                request: dev.infusion.core.artworkimage.ArtworkRequest,
            ): dev.infusion.core.artworkimage.ArtworkRequest {
                val appearance = appearanceStateHolder.preferences.value
                return resolveArtworkSourceVariant(
                    request = request,
                    backdropQuality = appearance.tmdbBackdropQuality,
                    landscapeCardQuality = appearance.tmdbLandscapeCardQuality,
                )
            }

            override fun variantToken(
                request: dev.infusion.core.artworkimage.ArtworkRequest,
            ): String {
                val appearance = appearanceStateHolder.preferences.value
                return variantTokenFor(
                    request = request,
                    backdropQuality = appearance.tmdbBackdropQuality,
                    landscapeCardQuality = appearance.tmdbLandscapeCardQuality,
                )
            }
        }

    private fun resolveArtworkSourceVariant(
        request: dev.infusion.core.artworkimage.ArtworkRequest,
        backdropQuality: dev.infusion.core.domain.appearance.TmdbBackdropQuality,
        landscapeCardQuality: dev.infusion.core.domain.appearance.TmdbLandscapeCardQuality,
    ): dev.infusion.core.artworkimage.ArtworkRequest {
        val locator = dev.infusion.core.model.metadata.TmdbBackdropVariant.resolve(
            locator = request.locator,
            policy = request.policy,
            role = request.presentationRole,
            backgroundBucket = backdropQuality.bucket,
            landscapeCardBucket = landscapeCardQuality.bucket,
        )
        return if (locator == request.locator) request else request.copy(locator = locator)
    }

    private fun variantTokenFor(
        request: dev.infusion.core.artworkimage.ArtworkRequest,
        backdropQuality: dev.infusion.core.domain.appearance.TmdbBackdropQuality,
        landscapeCardQuality: dev.infusion.core.domain.appearance.TmdbLandscapeCardQuality,
    ): String = when (request.presentationRole) {
        dev.infusion.core.model.ArtworkPresentationRole.BACKGROUND -> backdropQuality.storageKey
        dev.infusion.core.model.ArtworkPresentationRole.LANDSCAPE_CARD ->
            landscapeCardQuality.storageKey
    }

    private fun artworkPartition(): String =
        jellyfinSource?.sourceId?.value?.toString() ?: "no-source"

    private val metadataLocale: () -> dev.infusion.core.domain.metadata.MetadataLocale = {
        dev.infusion.core.domain.metadata.MetadataLocale.of(java.util.Locale.getDefault().toLanguageTag())
    }

    private val metadataHttpClient: dev.infusion.core.network.HardenedHttpClient by lazy {
        dev.infusion.core.network.OkHttpHardenedClient()
    }

    private val metadataImageLimits: dev.infusion.core.network.NetworkLimits =
        dev.infusion.core.network.NetworkLimits(
            maxResponseBytes = 4L * 1024 * 1024,
            maxDecompressedBytes = 4L * 1024 * 1024,
            maxDecompressionRatio = 4,
            connectTimeoutMs = 10_000,
            readTimeoutMs = 20_000,
            totalTimeoutMs = 30_000,
            maxRedirects = 2,
        )

    private val metadataProvider: dev.infusion.core.domain.metadata.MetadataProvider by lazy {
        metadataProviderSeam ?: dev.infusion.source.tmdb.TmdbMetadataProvider(
            transport = metadataHttpClient,
            apiKey = GeneratedTmdbCredential.apiKey(),
        )
    }

    private val ratingsPreferencesStore: dev.infusion.core.domain.ratings.RatingsPreferencesStore by lazy {
        dev.infusion.core.database.profile.SharedPreferencesRatingsPreferencesStore(
            context = context,
            profileScope = profileScope,
        )
    }

    private val ratingsCredentialStore: dev.infusion.core.domain.ratings.RatingsCredentialStore by lazy {
        dev.infusion.core.database.profile.SharedPreferencesRatingsCredentialStore(
            context = context,
            profileScope = profileScope,
            secretStore = { secretStoreRef },
        )
    }

    val communityRatings: dev.infusion.core.domain.ratings.CommunityRatingsPort by lazy {
        dev.infusion.source.communityratings.CommunityRatingsRepository(
            http = metadataHttpClient,
            cache = metadataCacheStore,
            omdbKeyProvider = {
                runCatching { ratingsCredentialStore.omdbKey() }.getOrNull()
            },
            traktClientIdProvider = {
                traktClientIdSeam ?: dev.infusion.tv.tracking.GeneratedTraktCredential.clientId()
            },
            simklClientIdProvider = {
                simklClientIdSeam ?: dev.infusion.tv.tracking.GeneratedSimklCredential.clientId()
            },
            mdbListMediaInfo = dev.infusion.source.communityratings.MdbListMediaInfoReader { provider, type, id ->
                mdblistTracking.communityMediaInfo(provider, type, id)
            },
            mdbListConnected = { mdblistTracking.accountConnected() },
        )
    }

    private val ratingsAppearanceFlow = kotlinx.coroutines.flow.MutableStateFlow(
        runCatching { ratingsPreferencesStore.current().appearance }
            .getOrDefault(dev.infusion.core.model.metadata.RatingAppearance.Default),
    )

    val ratingsAppearance: kotlinx.coroutines.flow.StateFlow<dev.infusion.core.model.metadata.RatingAppearance> =
        ratingsAppearanceFlow.asStateFlow()

    private fun refreshRatingsAppearance() {
        runCatching { ratingsPreferencesStore.current().appearance }
            .getOrNull()?.let { ratingsAppearanceFlow.value = it }
    }

    private suspend fun alreadyHeldRatings(identity: dev.infusion.core.model.metadata.MediaIdentity): List<dev.infusion.core.model.metadata.MediaRating> {
        val held = mutableListOf<dev.infusion.core.model.metadata.MediaRating>()

        identity.titleId?.let { titleId ->
            val row = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    db.titleDao().getById(titleId.value.toString())
                }
            }.getOrNull()
            val raw = row?.rating?.trim()?.takeIf { it.isNotEmpty() }
            val value = raw?.toDoubleOrNull()
            if (value != null && value > 0.0 && row?.ratingProvenanceJson != null) {
                held += dev.infusion.core.model.metadata.MediaRating(
                    source = "imdb",
                    value = value,
                    max = 10.0,
                    label = "IMDb",
                    supplier = "addon",
                )
            }
        }

        val identityKey = identity.cacheKey()
        if (identityKey != null) {
            val evidence = runCatching { jellyfinRatingsEvidence.load(identityKey) }.getOrNull()
            evidence?.let { held += it.ratings() }
        }
        return held
    }

    val ratingsSettings: dev.infusion.feature.vertical.ratings.RatingsSettingsStateHolder by lazy {
        dev.infusion.feature.vertical.ratings.RatingsSettingsStateHolder(
            preferencesStore = ratingsPreferencesStore,
            credentialStore = ratingsCredentialStore,
            community = communityRatings,
            scope = appScope,
            onPreferencesRecorded = { prefs -> ratingsAppearanceFlow.value = prefs.appearance },
        )
    }

    val metadataRepository: dev.infusion.core.domain.metadata.MetadataRepository by lazy {
        dev.infusion.core.metadata.SwrMetadataRepository(
            provider = dev.infusion.source.communityratings.CommunityRatingsMetadataProvider(
                delegate = metadataProvider,
                ratings = communityRatings,
                preferences = ratingsPreferencesStore,
                alreadyHeld = { identity -> alreadyHeldRatings(identity) },
            ),
            cache = metadataCacheStore,
            codec = dev.infusion.core.metadata.MetadataJsonCodec(),
        )
    }

    val mediaIdentityResolver: dev.infusion.core.domain.metadata.MediaIdentityResolver by lazy {
        dev.infusion.core.metadata.DefaultMediaIdentityResolver(
            provider = metadataProvider,
            store = dev.infusion.core.database.store.RoomMediaIdentityStore(
                externalIdDao = db.externalIdDao(),
                titleDao = db.titleDao(),
                transaction = { block -> db.runInTransaction(block) },
            ),

            convergence = canonicalConvergence,
        )
    }

    val seriesLandscapeArtwork: dev.infusion.feature.vertical.artwork.SeriesLandscapeArtwork by lazy {
        dev.infusion.feature.vertical.artwork.SeriesLandscapeArtworkStateHolder(
            resolver = mediaIdentityResolver,
            repository = metadataRepository,
            scope = appScope,
            locale = metadataLocale,
        )
    }

    fun canonicalDetailsStateHolder(
        titleId: dev.infusion.core.model.TitleId,
        mediaType: dev.infusion.core.model.metadata.MediaType,
        holderScope: CoroutineScope,
        identityHint: dev.infusion.core.domain.metadata.MetadataIdentityHint? = null,

        loadSecondarySections: Boolean = true,

        eagerSecondarySections: Boolean = false,

        retainAcrossVisits: Boolean = false,
    ): dev.infusion.feature.vertical.media.CanonicalDetailsStateHolder {
        if (!retainAcrossVisits) {
            return buildCanonicalDetailsStateHolder(
                titleId, mediaType, holderScope, identityHint, loadSecondarySections, eagerSecondarySections,
            )
        }
        val key = CanonicalDetailsHolderKey(activeProfileId, titleId, mediaType)
        return synchronized(retainedCanonicalDetailsHolders) {
            retainedCanonicalDetailsHolders.getOrPut(key) {
                buildCanonicalDetailsStateHolder(
                    titleId,
                    mediaType,
                    retainedCanonicalDetailsScope,
                    identityHint,
                    loadSecondarySections,
                    eagerSecondarySections,
                )
            }
        }
    }

    private data class CanonicalDetailsHolderKey(
        val profileId: String,
        val titleId: dev.infusion.core.model.TitleId,
        val mediaType: dev.infusion.core.model.metadata.MediaType,
    )

    private val retainedCanonicalDetailsHolders =
        object : LinkedHashMap<CanonicalDetailsHolderKey, dev.infusion.feature.vertical.media.CanonicalDetailsStateHolder>(
 8,
 0.75f,
 true,
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<CanonicalDetailsHolderKey, dev.infusion.feature.vertical.media.CanonicalDetailsStateHolder>,
            ): Boolean = size > 8
        }

    private val retainedCanonicalDetailsScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    private fun buildCanonicalDetailsStateHolder(
        titleId: dev.infusion.core.model.TitleId,
        mediaType: dev.infusion.core.model.metadata.MediaType,
        holderScope: CoroutineScope,
        identityHint: dev.infusion.core.domain.metadata.MetadataIdentityHint?,
        loadSecondarySections: Boolean,
        eagerSecondarySections: Boolean,
    ): dev.infusion.feature.vertical.media.CanonicalDetailsStateHolder =
        dev.infusion.feature.vertical.media.CanonicalDetailsStateHolder(
            titleId = titleId,
            mediaType = mediaType,
            repository = metadataRepository,
            resolver = mediaIdentityResolver,
            scope = holderScope,
            identityHint = identityHint,
            locale = metadataLocale,
            loadSecondarySections = loadSecondarySections,
            eagerSecondarySections = eagerSecondarySections,

            destinationAnchor = addonDestinationAnchor,

            watchStateRepository = RoomWatchStateStore(db.watchStateDao(), profileScope, titleSupersession),

            serverSeasonResume = jellyfinSource?.let { graph ->
                { seasonNumber -> graph.episodeResumePositionsForSeason(titleId, seasonNumber) }
            },

            watchedStateDemand = watchedStateDemand,

            ratingsProjection = { ratings ->
                dev.infusion.core.domain.ratings.projectRatings(ratings, ratingsPreferencesStore.current())
            },

            episodeRatingsProjection = { ratings ->
                dev.infusion.core.domain.ratings.projectEpisodeRatings(ratings, ratingsPreferencesStore.current())
            },

            rootTitleStore = dev.infusion.core.database.store.RoomCanonicalRootTitleStore(
                titleDao = db.titleDao(),
                transaction = { block -> db.runInTransaction(block) },
            ),
            identityStore = dev.infusion.core.database.store.RoomMediaIdentityStore(
                externalIdDao = db.externalIdDao(),
                titleDao = db.titleDao(),
                transaction = { block -> db.runInTransaction(block) },
            ),

            trackerReviewPorts = { trackingGraphs.reviewPorts() },
            trackerReviewChanges = trackingCredentialStore.connectionChanges(),
        )

    fun personDetailsStateHolder(
        personId: dev.infusion.core.model.metadata.PersonId,
        holderScope: CoroutineScope,
    ): dev.infusion.feature.vertical.media.PersonDetailsStateHolder =
        dev.infusion.feature.vertical.media.PersonDetailsStateHolder(
            personId = personId,
            repository = metadataRepository,
            scope = holderScope,
            locale = metadataLocale,

            resolver = mediaIdentityResolver,
        )

    fun collectionDetailsStateHolder(
        collectionId: Long,
        currentTitleKey: String?,
        holderScope: CoroutineScope,
    ): dev.infusion.feature.vertical.media.CollectionDetailsStateHolder =
        dev.infusion.feature.vertical.media.CollectionDetailsStateHolder(
            collectionId = collectionId,
            repository = metadataRepository,
            scope = holderScope,
            currentTitleKey = currentTitleKey,
            locale = metadataLocale,

            resolver = mediaIdentityResolver,
        )

    suspend fun canonicalTitleIdFor(identity: dev.infusion.core.model.metadata.MediaIdentity): dev.infusion.core.model.TitleId? =
        runCatching { mediaIdentityResolver.titleIdFor(identity) }.getOrNull()

    suspend fun canonicalCardTarget(
        card: dev.infusion.feature.vertical.media.MediaCardUi,
    ): dev.infusion.core.model.TitleId? {
        val identity = card.identity
        return runCatching {
            val target = mediaIdentityResolver.titleIdFor(identity) ?: return null
            cardIdentityAnchor.anchor(
                identity = identity,
                titleId = target,
                kind = if (identity.type == dev.infusion.core.model.metadata.MediaType.SHOW) {
                    dev.infusion.core.model.TitleKind.SERIES
                } else {
                    dev.infusion.core.model.TitleKind.MOVIE
                },
                displayTitle = card.title,
                year = card.year,
            )
            target
        }.getOrNull()
    }

    private val cardIdentityAnchor: dev.infusion.feature.vertical.browse.SourceCardIdentityAnchor by lazy {
        dev.infusion.feature.vertical.browse.SourceCardIdentityAnchor(
            rootTitleStore = dev.infusion.core.database.store.RoomCanonicalRootTitleStore(
                titleDao = db.titleDao(),
                transaction = { block -> db.runInTransaction(block) },
            ),
            identityStore = dev.infusion.core.database.store.RoomMediaIdentityStore(
                externalIdDao = db.externalIdDao(),
                titleDao = db.titleDao(),
                transaction = { block -> db.runInTransaction(block) },
            ),
        )
    }

    val addonMetadataIdentityBridge: dev.infusion.source.aggregation.metadata.AddonMetadataIdentityBridge by lazy {
        dev.infusion.source.aggregation.metadata.AddonMetadataIdentityBridge(mediaIdentityResolver)
    }

    private val canonicalShowHierarchy: dev.infusion.core.domain.metadata.CanonicalShowHierarchy by lazy {
        object : dev.infusion.core.domain.metadata.CanonicalShowHierarchy {
            override suspend fun seasonNumbers(
                seriesTitleId: dev.infusion.core.model.TitleId,
            ): List<Int> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val locale = metadataLocale()
                val identity = runCatching {
                    mediaIdentityResolver.resolve(
                        seriesTitleId,
                        dev.infusion.core.model.metadata.MediaType.SHOW,
                        locale,
                    )
                }.getOrNull() ?: return@withContext emptyList()
                val show = runCatching {
                    metadataRepository.observeDetails(identity, locale).first().details
                }.getOrNull() as? dev.infusion.core.model.metadata.ShowDetails
                show?.seasons?.map { it.seasonNumber } ?: emptyList()
            }

            override suspend fun episodes(
                seriesTitleId: dev.infusion.core.model.TitleId,
                seasonNumber: Int,
            ): List<dev.infusion.core.domain.metadata.CanonicalEpisodeRef> =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val locale = metadataLocale()
                    val identity = runCatching {
                        mediaIdentityResolver.resolve(
                            seriesTitleId,
                            dev.infusion.core.model.metadata.MediaType.SHOW,
                            locale,
                        )
                    }.getOrNull() ?: return@withContext emptyList()
                    val season = runCatching {
                        metadataRepository.seasonDetails(identity, seasonNumber, locale).valueOrNull()
                    }.getOrNull() ?: return@withContext emptyList()
                    season.episodes.map { episode ->
                        dev.infusion.core.domain.metadata.CanonicalEpisodeRef(
                            seasonNumber = episode.seasonNumber,
                            episodeNumber = episode.episodeNumber,
                            title = episode.name?.takeIf { it.isNotBlank() },
                            released = episode.airDate?.toString(),

                            runtimeMinutes = episode.runtimeMinutes,
                        )
                    }
                }
        }
    }

    suspend fun metadataImage(
        provider: String,
        size: String,
        path: String,
    ): dev.infusion.core.domain.catalog.CatalogImage? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (!provider.equals(dev.infusion.source.tmdb.TmdbEndpoints.IMAGE_PROVIDER, ignoreCase = true)) {
                return@withContext null
            }
            artworkCache.getOrLoad(
                dev.infusion.core.artworkcache.ArtworkCacheKey("meta:$provider", path, size),
            ) {
                val url = try {
                    dev.infusion.core.network.UrlParser.parse(
                        dev.infusion.source.tmdb.TmdbEndpoints.imageUrl(size, path),
                    )
                } catch (_: dev.infusion.core.network.NetworkPolicyException) {
                    return@getOrLoad null
                }
                val response = try {
                    metadataHttpClient.execute(
                        dev.infusion.core.network.HardenedRequest(
                            url = url,
                            method = "GET",
                            limits = metadataImageLimits,
                        ),
                    )
                } catch (_: dev.infusion.core.network.NetworkPolicyException) {
                    return@getOrLoad null
                }
                try {
                    if (response.statusCode !in 200..299) return@getOrLoad null
                    val contentType = response.headers["content-type"].orEmpty()
                        .substringBefore(';').trim().lowercase()
                    if (!contentType.startsWith("image/")) return@getOrLoad null
                    val bytes = response.body.readBytes()
                    if (bytes.isEmpty()) return@getOrLoad null
                    dev.infusion.core.domain.catalog.CatalogImage(
                        contentType = contentType.ifEmpty { null },
                        bytes = bytes,
                    )
                } finally {
                    response.close()
                }
            }
        }

    private val collectionArtworkLimits: dev.infusion.core.network.NetworkLimits =
        dev.infusion.core.network.NetworkLimits(
            maxResponseBytes = 6L * 1024 * 1024,
            maxDecompressedBytes = 6L * 1024 * 1024,
            maxDecompressionRatio = 8,
            connectTimeoutMs = 10_000,
            readTimeoutMs = 20_000,
            totalTimeoutMs = 30_000,
            maxRedirects = 2,
        )

    suspend fun collectionArtwork(
        packId: String,
        groupKey: String,
        tileKey: String,
        slot: String,
    ): dev.infusion.core.domain.catalog.CatalogImage? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val cacheKey = dev.infusion.core.artworkcache.ArtworkCacheKey(
                partition = "collection:$packId",
                nativeItemId = "$groupKey/$tileKey",
                kind = slot,
            )

            artworkCache.get(cacheKey)?.let { return@withContext it }
            val reference = collectionArtworkReference(packId, groupKey, tileKey, slot)
                ?: return@withContext null

            artworkCache.getOrLoad(cacheKey) {
                resolveCollectionArtwork(reference) { candidate -> fetchCollectionArtwork(candidate) }
            }
        }

    private suspend fun fetchCollectionArtwork(
        reference: String,
    ): dev.infusion.core.domain.catalog.CatalogImage? {
        val url = try {
            dev.infusion.core.network.UrlParser.parse(reference)
        } catch (_: dev.infusion.core.network.NetworkPolicyException) {
            return null
        }

        val response = try {
            metadataHttpClient.execute(
                dev.infusion.core.network.HardenedRequest(
                    url = url,
                    method = "GET",
                    limits = collectionArtworkLimits,
                ),
            )
        } catch (_: dev.infusion.core.network.NetworkPolicyException) {
            return null
        }
        try {
            if (response.statusCode !in 200..299) return null
            val contentType = response.headers["content-type"].orEmpty()
                .substringBefore(';').trim().lowercase()
            if (!contentType.startsWith("image/") && !contentType.startsWith("application/octet")) {
                return null
            }
            val bytes = response.body.readBytes()
            if (bytes.isEmpty()) return null
            return dev.infusion.core.domain.catalog.CatalogImage(
                contentType = contentType.ifEmpty { null },
                bytes = bytes,
            )
        } finally {
            response.close()
        }
    }

    private fun collectionArtworkReference(
        packId: String,
        groupKey: String,
        tileKey: String,
        slot: String,
    ): String? {
        val pack = collectionPackStore.readPack(profileScope.activeProfileId, packId) ?: return null
        val tile = pack.groups.firstOrNull { it.groupKey == groupKey }
            ?.tiles?.firstOrNull { it.tileKey == tileKey }
            ?: return null
        return when (slot) {
            dev.infusion.core.model.ArtworkLocator.COLLECTION_SLOT_COVER -> tile.artwork.cover
            dev.infusion.core.model.ArtworkLocator.COLLECTION_SLOT_LOGO -> tile.artwork.logo
            dev.infusion.core.model.ArtworkLocator.COLLECTION_SLOT_BACKDROP -> tile.artwork.backdrop

            dev.infusion.core.model.ArtworkLocator.COLLECTION_SLOT_FOCUS ->
                tile.artwork.focus?.takeIf { tile.artwork.focusEnabled }
            else -> null
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun playerStateHolder(
        watchKey: dev.infusion.core.model.watch.WatchKey,
        candidate: dev.infusion.core.model.PlayableCandidate? = null,
        playbackScope: kotlinx.coroutines.CoroutineScope = appScope,
    ): PlayerStateHolder {

        runCatching { musicQueueStateHolder.stopForVideoOwnership() }

        val upNextHolder = upNextStateHolder(watchKey)
        val jellyfinGraph = jellyfinSource?.takeIf { graph -> candidate?.sourceId == graph.sourceId }
        return PlayerStateHolder(
            watchKey = watchKey,

            engine = playerEngineFactory?.invoke()
                ?: if (jellyfinGraph != null) {
                    dev.infusion.core.playback.media3.Media3PlaybackEngine(
                        context = context,

                        dolbyVisionProfile7Fallback = dolbyVisionProfile7FallbackProvider,
                    )
                } else {
                    createPlayerEngine()
                },
            coordinator = coordinator,
            scope = playbackScope,

            transportFor = { candidate, lineage ->
                val jellyfinGraph = jellyfinSource
                when {
                    jellyfinGraph != null && candidate.sourceId == jellyfinGraph.sourceId ->
                        jellyfinGraph.transportFor(candidate, lineage)
                    addonManager.installedAddonIds().contains(candidate.sourceId.value.toString()) -> {

                        val mergedCandidate = try {
                            addonSubtitleMerger.mergeStandaloneSubtitles(candidate)
                        } catch (_: Exception) {
                            candidate
                        }

                        activeAttemptRoute?.close()
                        activeAttemptRoute = registerAddonMediaHosts(
                            mergedCandidate,
                            attempt = lineage.requestId,
                        )
                        dev.infusion.source.addon.AddonTransportResolver.requestFor(mergedCandidate, lineage)
                    }

                    else -> throw IllegalArgumentException(
                        "no wired source for candidate source ${candidate.sourceId.value}",
                    )
                }
            },

            diagnosticsSink = { latestPlayerSnapshot = it },

            sourceSessionBinder = jellyfinGraph?.let { graph ->
                { watchSessionId, candidate -> graph.bindPlayback(watchSessionId, candidate) }
            } ?: { _, _ -> },
            sourceSessionUnbinder = jellyfinGraph?.let { graph ->
                { watchSessionId -> graph.unbindPlayback(watchSessionId) }
            } ?: {},

            activeReporterFor = { playable ->
                jellyfinGraph?.activeReporterFor(playable, playbackScope)
            },

            errorRecovery = jellyfinGraph?.let { graph ->
                { candidate, positionMs, _ -> graph.playerErrorRecovery.recover(candidate, positionMs) }
            },

            startWithControlsVisible = false,

            subtitlePreferences = { subtitlePreferenceStateHolder.preferences.value },
            captioningLanguage = { captioningLanguageTag },

            audioPreferences = { audioLanguagePreferenceStateHolder.preferences.value },
            systemLanguages = { systemLanguageTags },

            completionThresholds = { completionThresholdStateHolder.thresholds.value },

            controlsAutoHide = { playbackControlsAutoHideStateHolder.autoHide.value },

            relativeSeekStepMs = { relativeSeekStepStateHolder.step.value.stepMs },

            serverResumePosition = jellyfinSource?.let { graph ->
                { playable ->
                    if (playable.sourceId == graph.sourceId) {
                        graph.currentResumePositionFor(playable)
                    } else {
                        null
                    }
                }
            },

            segmentEvidence = playbackSegmentCoordinator,
            segmentAttemptEvidence = { key, playable -> segmentAttemptEvidenceFor(key, playable) },
            segmentSettings = { segmentEvidenceSettingsStateHolder.preferences.value.toSettings() },

            stingerEvidence = playbackStingerCoordinator,

            nextEpisodeSettings = { nextEpisodeSettingsStateHolder.preferences.value.toSettings() },

            upNextState = upNextHolder.state,

            onAttemptReleased = {
                activeAttemptRoute?.close()
                activeAttemptRoute = null
            },

            onAttemptTerminal = {
                activeAttemptRoute?.close()
                activeAttemptRoute = null
            },
        )
    }

    fun upNextStateHolder(
        completedKey: dev.infusion.core.model.watch.WatchKey,
    ): dev.infusion.feature.vertical.player.UpNextStateHolder =
        dev.infusion.feature.vertical.player.UpNextStateHolder(
            completedKey = completedKey,

            catalog = detailsCatalog(),
            scope = appScope,

            canonicalHierarchy = canonicalShowHierarchy,
        )

    fun playerPresentationStateHolder(
        watchKey: dev.infusion.core.model.watch.WatchKey,
        playingTitleId: dev.infusion.core.model.TitleId,
        canonical: dev.infusion.feature.vertical.media.CanonicalDetailsStateHolder?,
        holderScope: kotlinx.coroutines.CoroutineScope,
    ): dev.infusion.feature.vertical.player.PlayerPresentationStateHolder =
        dev.infusion.feature.vertical.player.PlayerPresentationStateHolder(
            watchKey = watchKey,
            titleId = playingTitleId,
            catalog = if (watchKey.isEpisode && watchKey.seriesId != null) {
                detailsCatalogForEpisode(watchKey.seriesId!!)
            } else {
                detailsCatalog()
            },
            canonical = canonical,
            scope = holderScope,

            episodeRatingsEvidence = if (watchKey.isEpisode && watchKey.seriesId != null) {
                { seasonNumber, episodeNumber ->
                    val identity = runCatching {
                        mediaIdentityResolver.resolve(
                            watchKey.seriesId!!,
                            dev.infusion.core.model.metadata.MediaType.SHOW,
                            metadataLocale(),
                        )
                    }.getOrNull()
                    if (identity == null) {
                        emptyList()
                    } else {
                        val result = metadataRepository.seasonDetails(identity, seasonNumber, metadataLocale())
                        val episodes = (result as? dev.infusion.core.domain.metadata.MetadataResult.Success)?.value?.episodes
                        val episode = episodes?.firstOrNull {
                            it.seasonNumber == seasonNumber && it.episodeNumber == episodeNumber
                        }
                        episode?.let { listOfNotNull(it.rating) + it.ratings }.orEmpty()
                    }
                }
            } else {
                null
            },
            episodeRatingsProjection = { ratings ->
                dev.infusion.core.domain.ratings.projectEpisodeRatings(
                    ratings,
                    ratingsPreferencesStore.current(),
                )
            },
        )

    private val candidateHandoff: dev.infusion.core.domain.candidate.CandidateHandoffStore by lazy {
        dev.infusion.core.database.store.RoomCandidateHandoffStore(
            dao = db.candidateHandoffDao(),
            secretStore = secretStoreRef,
        )
    }

    suspend fun saveCandidate(candidate: dev.infusion.core.model.PlayableCandidate) {
        require(candidate.expectedMode != dev.infusion.core.model.ExpectedMode.EXTERNAL_LINK) {
            "external-link candidates must never reach the player handoff store"
        }
        candidateHandoff.put(candidate)
    }

    suspend fun candidateById(candidateId: dev.infusion.core.model.CandidateId): dev.infusion.core.model.PlayableCandidate? =
        candidateHandoff.byId(candidateId)

    private val phoneSetupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val phoneSetupLanAddressRef: () -> String? =
        { phoneSetupLanAddress ?: dev.infusion.core.pairing.android.LanAddressResolver.resolve() }

    val phoneSetupStateHolder: dev.infusion.tv.configweb.ConfigWebSessionStateHolder by lazy {
        dev.infusion.tv.configweb.ConfigWebSessionStateHolder(
            coordinator = configWebCoordinator(),
            assets = dev.infusion.tv.configweb.ConfigWebAssets.fromContext(context),
            lanAddress = phoneSetupLanAddressRef,
            scope = phoneSetupScope,

            playbackActive = videoPlaybackMediaSession.destinationActive,

            activeProfileFlow = activeProfileFlow,
        )
    }

    private val addonClientFactory: dev.infusion.source.stremio.AddonClientFactory =
        addonClientFactorySeam ?: { configured, trust ->
            dev.infusion.source.stremio.StremioClientFactory.create(
                configured,
                trust,
                lanTrustRef,
                limits = dev.infusion.source.stremio.StremioLimits.DEFAULT,
            )
        }

    val addonInfrastructure: dev.infusion.source.addon.shells.AddonInfrastructure by lazy {
        dev.infusion.source.addon.shells.AddonInfrastructure.create(
            database = db,
            secretStore = secretStoreRef,
            lanTrust = lanTrustRef,
            profileScope = profileScope,
            clientFactory = addonClientFactory,

            onSourceRemoved = { addonId ->
                versionPreferences.clearForSource(addonId)
                originRoutingRegistry.retireSource(addonId)

                artworkCache.purgePartition("addon:$addonId")
                artworkLoader.purgeAll()
                addonResources.artwork.purgeSource(addonId)
            },

            canonicalShowHierarchy = { canonicalShowHierarchy },

            shelfStageMetrics = if (isDebuggable) {
                dev.infusion.tv.perf.LogcatShelfStageMetrics()
            } else {
                dev.infusion.core.domain.catalog.ShelfStageMetrics.None
            },
        )
    }

    val addonRepository: dev.infusion.source.addon.repository.RoomAddonRepository
        get() = addonInfrastructure.repository

    val addonManager: dev.infusion.source.addon.lifecycle.AddonLifecycleManager
        get() = addonInfrastructure.manager

    val addonResources: dev.infusion.source.addon.resource.AddonResourceBundle
        get() = addonInfrastructure.resources

    val addonPorts: dev.infusion.source.addon.shells.AddonShellPorts
        get() = addonInfrastructure.ports

    val addonStartupRestorer: dev.infusion.source.addon.lifecycle.AddonStartupRestorer<dev.infusion.source.addon.repository.AddonRepositoryState>
        get() = addonInfrastructure.startupRestorer

    private val addonRegistry: dev.infusion.source.aggregation.AddonRegistryPort
        get() = addonInfrastructure.registry

    private val addonLookup: dev.infusion.source.aggregation.CanonicalTitleLookup
        get() = addonInfrastructure.lookup

    private val addonAvailabilityStore: dev.infusion.source.aggregation.AddonAvailabilityStore
        get() = addonInfrastructure.availabilityStore

    val streamLimitObservationJournal:
        dev.infusion.feature.vertical.diagnostics.StreamLimitObservationJournal by lazy {
            dev.infusion.feature.vertical.diagnostics.StreamLimitObservationJournal()
        }

    private val titleAvailabilityLookup: dev.infusion.source.aggregation.TitleAvailabilityLookup
        get() = addonInfrastructure.titleAvailability

    private val addonMatcher: dev.infusion.source.aggregation.match.CanonicalMatcher
        get() = addonInfrastructure.matcher

    val discoverService: dev.infusion.source.aggregation.discover.DiscoverService
        get() = addonInfrastructure.discoverService

    val searchService: dev.infusion.source.aggregation.search.SearchService
        get() = addonInfrastructure.searchService

    private val addonDetailsService: dev.infusion.source.aggregation.details.AddonDetailsService
        get() = addonInfrastructure.detailsService

    private val addonDestinationAnchor: dev.infusion.core.domain.anchor.CanonicalDestinationAnchor
        get() = addonInfrastructure.destinationAnchor

    private val addonSubtitleMerger: dev.infusion.source.aggregation.subtitles.AddonSubtitleMerger
        get() = addonInfrastructure.subtitleMerger

    suspend fun ensureAddonsRestored(): dev.infusion.source.addon.repository.AddonRepositoryState =
        addonInfrastructure.ensureRestored()

    private val coldStartPerfTrace: (stage: String, detail: String) -> Unit =
        if (isDebuggable) dev.infusion.tv.perf.LogcatColdStartTrace()::trace else { _, _ -> }

    val coldStartSplash: dev.infusion.tv.ui.shell.ColdStartSplashController =
        dev.infusion.tv.ui.shell.ColdStartSplashController(trace = coldStartPerfTrace)

    private val _addonDeepLinks = kotlinx.coroutines.channels.Channel<String>(
        capacity = DEEP_LINK_BUFFER_CAPACITY,
    )

    val addonDeepLinks: kotlinx.coroutines.flow.Flow<String> =
        _addonDeepLinks.receiveAsFlow()

    fun offerAddonDeepLink(addonUrl: String) {

        _addonDeepLinks.trySend(addonUrl)
    }

    private val _episodePlayFromBeginning =
        kotlinx.coroutines.channels.Channel<EpisodePlayFromBeginningRequest>(
            capacity = DEEP_LINK_BUFFER_CAPACITY,
        )

    val episodePlayFromBeginning: kotlinx.coroutines.flow.Flow<EpisodePlayFromBeginningRequest> =
        _episodePlayFromBeginning.receiveAsFlow()

    fun offerEpisodePlayFromBeginning(titleId: dev.infusion.core.model.TitleId, unitKey: String) {
        _episodePlayFromBeginning.trySend(EpisodePlayFromBeginningRequest(titleId, unitKey))
    }

    private val _episodeBrowseShow =
        kotlinx.coroutines.channels.Channel<EpisodeBrowseShowRequest>(
            capacity = DEEP_LINK_BUFFER_CAPACITY,
        )

    val episodeBrowseShow: kotlinx.coroutines.flow.Flow<EpisodeBrowseShowRequest> =
        _episodeBrowseShow.receiveAsFlow()

    fun offerEpisodeBrowseShow(seriesTitleId: dev.infusion.core.model.TitleId) {
        _episodeBrowseShow.trySend(EpisodeBrowseShowRequest(seriesTitleId))
    }

    private val _openNowPlaying =
        kotlinx.coroutines.channels.Channel<String?>(capacity = DEEP_LINK_BUFFER_CAPACITY)

    val openNowPlaying: kotlinx.coroutines.flow.Flow<String?> = _openNowPlaying.receiveAsFlow()

    fun offerOpenNowPlaying(originTabKey: String? = null) {
        _openNowPlaying.trySend(originTabKey)
    }

    fun discoverStateHolder(scope: CoroutineScope): dev.infusion.feature.vertical.discover.DiscoverStateHolder {
        return dev.infusion.feature.vertical.discover.DiscoverStateHolder(
            port = addonPorts.discoverPort(discoverService),
            scope = scope,
            metrics = shelfDeliveryMetrics,
        )
    }

    fun homeCustomizationStateHolder(scope: CoroutineScope): HomeCustomizationStateHolder =
        HomeCustomizationStateHolder(
            port = addonPorts.homeCatalogPort(discoverService),
            scope = scope,

            widgets = homeWidgetPort,
        )

    private val publicListDirectories: Map<String, dev.infusion.core.domain.publist.PublicListDirectory> by lazy {
        mapOf(
            dev.infusion.core.domain.publist.PublicListProvider.TRAKT.id to traktTracking.publicLists,
            dev.infusion.core.domain.publist.PublicListProvider.MDBLIST.id to mdblistTracking.publicLists,
            dev.infusion.core.domain.publist.PublicListProvider.SIMKL.id to simklTracking.publicLists,
        )
    }

    private val publicListContentSources: Map<String, dev.infusion.core.domain.publist.PublicListContentSource> by lazy {
        mapOf(
            dev.infusion.core.domain.publist.PublicListProvider.TRAKT.id to traktTracking.publicLists,
            dev.infusion.core.domain.publist.PublicListProvider.MDBLIST.id to mdblistTracking.publicLists,
            dev.infusion.core.domain.publist.PublicListProvider.SIMKL.id to simklTracking.publicLists,
        )
    }

    private val sourceRowCards: dev.infusion.feature.vertical.browse.SourceRowCards by lazy {
        dev.infusion.feature.vertical.browse.SourceRowCards(metadata = metadataRepository)
    }

    val publicListRowSource: dev.infusion.feature.vertical.home.PublicListWidgetRowSource by lazy {
        dev.infusion.feature.vertical.home.PublicListWidgetResolver(
            sources = publicListContentSources,
            cards = sourceRowCards,
            identityResolver = mediaIdentityResolver,

            rootTitleStore = dev.infusion.core.database.store.RoomCanonicalRootTitleStore(
                titleDao = db.titleDao(),
                transaction = { block -> db.runInTransaction(block) },
            ),
            identityStore = dev.infusion.core.database.store.RoomMediaIdentityStore(
                externalIdDao = db.externalIdDao(),
                titleDao = db.titleDao(),
                transaction = { block -> db.runInTransaction(block) },
            ),
        )
    }

    val collectionRowSource: dev.infusion.feature.vertical.collections.CollectionRowSource by lazy {
        val publicListArm = dev.infusion.feature.vertical.collections.PublicListCollectionRowSource(
            publicListContentSources,
        )
        val addonArm = dev.infusion.source.aggregation.collections.AddonCollectionRowSource(
            catalogRepository = addonResources.catalog,
            registry = addonRegistry,
            viewer = { addonPorts.viewerProfileSnapshot() },
            primary = { addonPorts.primaryProfileSnapshot() },
            artworkRepository = addonResources.artwork,
        )
        dev.infusion.feature.vertical.collections.CollectionRowResolver(
            sources = mapOf(
                dev.infusion.source.tmdb.TmdbBrowseProvider.PROVIDER to tmdbBrowseProvider,
                dev.infusion.core.domain.publist.PublicListProvider.TRAKT.id to publicListArm,
                dev.infusion.core.domain.publist.PublicListProvider.MDBLIST.id to publicListArm,
                dev.infusion.source.aggregation.collections.AddonCollectionRowSource.PROVIDER to addonArm,
            ),
            cards = sourceRowCards,
            identityResolver = mediaIdentityResolver,

            rootTitleStore = dev.infusion.core.database.store.RoomCanonicalRootTitleStore(
                titleDao = db.titleDao(),
                transaction = { block -> db.runInTransaction(block) },
            ),
            identityStore = dev.infusion.core.database.store.RoomMediaIdentityStore(
                externalIdDao = db.externalIdDao(),
                titleDao = db.titleDao(),
                transaction = { block -> db.runInTransaction(block) },
            ),
        )
    }

    private val tmdbBrowseProvider: dev.infusion.source.tmdb.TmdbBrowseProvider by lazy {
        dev.infusion.source.tmdb.TmdbBrowseProvider(
            transport = metadataHttpClient,
            apiKey = GeneratedTmdbCredential.apiKey(),
        )
    }

    fun collectionPageStateHolder(pageId: String): dev.infusion.feature.vertical.collections.CollectionPageStateHolder =
        synchronized(collectionPageHolders) {
            collectionPageHolders.remove(pageId)?.let { existing ->

                collectionPageHolders[pageId] = existing
                return@synchronized existing
            }
            val holder = dev.infusion.feature.vertical.collections.CollectionPageStateHolder(

                packFor = { profileId, packId ->
                    dev.infusion.core.domain.collections.CollectionEntityPage
                        .packFor(packId, collectionToday())
                        ?: collectionPackStore.readPack(profileId, packId)
                },
                rowSource = collectionRowSource,
                scope = appScope,

                rowPreferencesFor = { profileId, packId, groupKey, tileKey ->
                    collectionRowPreferenceStore.readPage(profileId, packId, groupKey, tileKey)
                },
                preferencesChanges = collectionRowPreferenceStore.changes(),
            )
            collectionPageHolders[pageId] = holder
            while (collectionPageHolders.size > COLLECTION_PAGE_HOLDER_LIMIT) {
                val eldest = collectionPageHolders.keys.first()
                collectionPageHolders.remove(eldest)
            }
            holder
        }

    private val collectionPageHolders =
        LinkedHashMap<String, dev.infusion.feature.vertical.collections.CollectionPageStateHolder>()

    fun collectionRowSettingsStateHolder(scope: CoroutineScope): dev.infusion.feature.vertical.collections.CollectionRowSettingsStateHolder =
        dev.infusion.feature.vertical.collections.CollectionRowSettingsStateHolder(
            packStore = collectionPackStore,
            preferenceStore = collectionRowPreferenceStore,
            activeProfileId = { profileScope.activeProfileId },
            scope = scope,
        )

    fun collectionPackManagerStateHolder(scope: CoroutineScope): dev.infusion.feature.vertical.collections.CollectionPackManagerStateHolder =
        dev.infusion.feature.vertical.collections.CollectionPackManagerStateHolder(
            store = collectionPackStore,
            fetch = { url -> fetchCollectionDocument(url) },
            placementsFor = { profileId, packId ->
                homeWidgetStore.read(profileId).records.count { record ->
                    (record.source as? dev.infusion.core.domain.home.HomeWidgetSourceRef.CollectionGroup)?.packId == packId
                }
            },
            activeProfileId = { profileScope.activeProfileId },
            scope = scope,
        )

    private val collectionDocumentLimits: dev.infusion.core.network.NetworkLimits =
        dev.infusion.core.network.NetworkLimits(
            maxResponseBytes = dev.infusion.core.domain.collections.CollectionPackLimits.MAX_DOCUMENT_BYTES.toLong(),
            maxDecompressedBytes = dev.infusion.core.domain.collections.CollectionPackLimits.MAX_DOCUMENT_BYTES.toLong() * 4,
            maxDecompressionRatio = 8,
            connectTimeoutMs = 10_000,
            readTimeoutMs = 20_000,
            totalTimeoutMs = 30_000,
            maxRedirects = 2,
        )

    private suspend fun fetchCollectionDocument(
        url: String,
    ): dev.infusion.feature.vertical.collections.CollectionDocumentFetch =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val parsed = try {
                dev.infusion.core.network.UrlParser.parse(url.trim())
            } catch (_: dev.infusion.core.network.NetworkPolicyException) {
                return@withContext dev.infusion.feature.vertical.collections.CollectionDocumentFetch.Failed(
                    "that URL is not allowed",
                )
            }
            val response = try {
                metadataHttpClient.execute(
                    dev.infusion.core.network.HardenedRequest(
                        url = parsed,
                        method = "GET",
                        limits = collectionDocumentLimits,
                    ),
                )
            } catch (_: dev.infusion.core.network.NetworkPolicyException) {
                return@withContext dev.infusion.feature.vertical.collections.CollectionDocumentFetch.Failed(
                    "the document could not be fetched",
                )
            }
            response.use { resp ->
                if (resp.statusCode !in 200..299) {
                    return@withContext dev.infusion.feature.vertical.collections.CollectionDocumentFetch.Failed(
                        "the server answered ${resp.statusCode}",
                    )
                }
                val bytes = try {
                    resp.body.readBytes()
                } catch (_: java.io.IOException) {
                    return@withContext dev.infusion.feature.vertical.collections.CollectionDocumentFetch.Failed(
                        "the document could not be read",
                    )
                }
                if (bytes.size > dev.infusion.core.domain.collections.CollectionPackLimits.MAX_DOCUMENT_BYTES) {
                    return@withContext dev.infusion.feature.vertical.collections.CollectionDocumentFetch.Failed(
                        "the document is larger than this build accepts",
                    )
                }
                dev.infusion.feature.vertical.collections.CollectionDocumentFetch.Fetched(
                    bytes.toString(Charsets.UTF_8),
                )
            }
        }

    fun homeCatalogRowsStateHolder(scope: CoroutineScope): HomeCatalogRowsStateHolder =
        HomeCatalogRowsStateHolder(
            port = addonPorts.homeCatalogPort(discoverService),
            scope = scope,
            metrics = shelfDeliveryMetrics,

            widgetPort = homeWidgetPort,

            publicListRowSource = publicListRowSource,

            collectionPacks = { profileId -> collectionPackStore.read(profileId).packs },

            presentationCache = homePresentationStore,
            localeToken = homePresentationLocale,

            playbackActive = videoPlaybackMediaSession.destinationActive,
        )

    val homeWidgetPort: dev.infusion.feature.vertical.home.HomeWidgetPort by lazy {
        object : dev.infusion.feature.vertical.home.HomeWidgetPort {
            private val catalogPort = addonPorts.homeCatalogPort(discoverService)

            override fun changes() = homeWidgetStore.changes()

            override fun collectionChanges() = collectionPackStore.changes()

            override fun destinations(): List<dev.infusion.feature.vertical.home.WidgetDestination> =
                listOf(dev.infusion.feature.vertical.home.WidgetDestinations.HOME)

            override fun viewerProfile() = addonPorts.viewerProfileSnapshot()

            override fun primaryProfile() = addonPorts.primaryProfileSnapshot()

            override fun availableExternalCatalogs(
                viewer: dev.infusion.core.model.profile.Profile,
                primary: dev.infusion.core.model.profile.Profile,
            ) = catalogPort.availableCatalogs(viewer, primary)

            override fun publicListProviders(): List<dev.infusion.core.domain.publist.PublicListProvider> =
                publicListDirectories.values
                    .map { it.provider }
                    .filter { provider ->
                        val directory = publicListDirectories[provider.id] ?: return@filter false

                        directory.capabilities().hasUsableRoutes(directory.accountConnected())
                    }

            override fun publicListAccountConnected(providerId: String): Boolean =
                publicListDirectories[providerId]?.accountConnected() == true

            override fun publicListCapabilities(providerId: String) =
                publicListDirectories[providerId]?.capabilities()

            override suspend fun publicLists(
                providerId: String,
                route: dev.infusion.core.domain.publist.PublicListRoute,
                query: String?,
            ): dev.infusion.core.domain.publist.PublicListResult<List<dev.infusion.core.domain.publist.PublicListSummary>> {
                val directory = publicListDirectories[providerId]
                    ?: return dev.infusion.core.domain.publist.PublicListResult.Unsupported(providerId)
                return directory.lists(route, query)
            }

            override suspend fun publicListPage(
                providerId: String,
                route: dev.infusion.core.domain.publist.PublicListRoute,
                query: String?,
                cursor: String?,
            ): dev.infusion.core.domain.publist.PublicListResult<dev.infusion.core.domain.publist.PublicListDirectoryPage> {
                val directory = publicListDirectories[providerId]
                    ?: return dev.infusion.core.domain.publist.PublicListResult.Unsupported(providerId)
                return directory.listPage(route, query, cursor)
            }

            override suspend fun publicListUsers(
                providerId: String,
                query: String,
            ): dev.infusion.core.domain.publist.PublicListResult<List<dev.infusion.core.domain.publist.PublicListUser>> {
                val directory = publicListDirectories[providerId]
                    ?: return dev.infusion.core.domain.publist.PublicListResult.Unsupported(providerId)
                return directory.users(query)
            }

            override suspend fun publicListUserLists(
                providerId: String,
                username: String,
            ): dev.infusion.core.domain.publist.PublicListResult<List<dev.infusion.core.domain.publist.PublicListSummary>> {
                val directory = publicListDirectories[providerId]
                    ?: return dev.infusion.core.domain.publist.PublicListResult.Unsupported(providerId)
                return directory.userLists(username)
            }

            override fun widgetConfiguration(profileId: String) = homeWidgetStore.read(profileId)

            override fun addExternalCatalogWidget(
                profileId: String,
                destinationId: String,
                addonId: String,
                catalogType: String,
                catalogId: String,
            ): dev.infusion.feature.vertical.home.WidgetAddOutcome {

                if (destinations().none { it.id == destinationId }) {
                    return dev.infusion.feature.vertical.home.WidgetAddOutcome.UNKNOWN_DESTINATION
                }
                val available = availableExternalCatalogs(viewerProfile(), primaryProfile()).any { option ->
                    option.key.addonId == addonId &&
                        option.key.catalogType == catalogType &&
                        option.key.catalogId == catalogId
                }
                if (!available) return dev.infusion.feature.vertical.home.WidgetAddOutcome.UNKNOWN_CATALOG
                val existing = homeWidgetStore.read(profileId)
                if (existing.records.size >= dev.infusion.core.domain.home.HomeWidgetLimits.MAX_WIDGETS_PER_PROFILE) {
                    return dev.infusion.feature.vertical.home.WidgetAddOutcome.LIMIT_REACHED
                }

                val source = dev.infusion.core.domain.home.HomeWidgetSourceRef.ExternalCatalog(
                    addonId = addonId,
                    catalogType = catalogType,
                    catalogId = catalogId,
                )
                val duplicate = existing.records.any { record ->
                    record.destinationId == destinationId &&
                        record.source.addressesSameSource(source)
                }
                if (duplicate) return dev.infusion.feature.vertical.home.WidgetAddOutcome.DUPLICATE
                val record = dev.infusion.core.domain.home.HomeWidgetRecord(
                    widgetId = newHomeWidgetId(),
                    destinationId = destinationId,
                    source = source,
                )

                homeWidgetStore.write(profileId, existing.records + record)
                return dev.infusion.feature.vertical.home.WidgetAddOutcome.ADDED
            }

            override fun addPublicListWidget(
                profileId: String,
                destinationId: String,
                providerId: String,
                listId: String,
                owner: String?,
                displayTitle: String?,
            ): dev.infusion.feature.vertical.home.WidgetAddOutcome =

                dev.infusion.feature.vertical.home.PublicListWidgetCreation.add(
                    store = homeWidgetStore,
                    profileId = profileId,
                    destinationId = destinationId,
                    providerId = providerId,
                    listId = listId,
                    owner = owner,
                    displayTitle = displayTitle,
                    newWidgetId = { newHomeWidgetId() },
                    destinationAvailable = { id -> destinations().any { it.id == id } },
                    providerAvailable = { provider -> publicListProviders().any { it == provider } },
                )

            override fun recordPublicListTitle(
                profileId: String,
                providerId: String,
                listId: String,
                owner: String?,
                title: String,
            ): Boolean {
                val storable = dev.infusion.core.domain.home.HomeWidgetRecord.storableTitle(title)
                    ?: return false
                val existing = homeWidgetStore.read(profileId)
                val target = dev.infusion.core.domain.home.HomeWidgetSourceRef.PublicList(
                    providerId = providerId,
                    listId = listId,
                    owner = owner?.takeIf { it.isNotBlank() },
                )
                var changed = false
                val updated = existing.records.map { record ->
                    val matches = record.source.addressesSameSource(target)
                    if (matches && record.displayTitle == null) {
                        changed = true
                        record.copy(displayTitle = storable)
                    } else {
                        record
                    }
                }
                if (!changed) return false
                homeWidgetStore.write(profileId, updated)
                return true
            }

            override fun collectionGroupOptions(
                profileId: String,
            ): List<dev.infusion.feature.vertical.home.WidgetBuilderCollectionGroupUi> =
                collectionPackStore.read(profileId).packs.flatMap { pack ->
                    pack.groups.map { group ->
                        dev.infusion.feature.vertical.home.WidgetBuilderCollectionGroupUi(
                            packId = pack.packId,
                            groupKey = group.groupKey,
                            packName = pack.name,
                            groupTitle = group.title,
                            tileCount = group.tiles.size,
                        )
                    }
                }

            override fun addCollectionGroupWidget(
                profileId: String,
                destinationId: String,
                packId: String,
                groupKey: String,
                displayTitle: String?,
            ): dev.infusion.feature.vertical.home.WidgetAddOutcome {
                if (destinations().none { it.id == destinationId }) {
                    return dev.infusion.feature.vertical.home.WidgetAddOutcome.UNKNOWN_DESTINATION
                }
                val pack = collectionPackStore.readPack(profileId, packId)
                    ?: return dev.infusion.feature.vertical.home.WidgetAddOutcome.UNKNOWN_COLLECTION_GROUP
                val group = pack.groups.firstOrNull { it.groupKey == groupKey }
                    ?: return dev.infusion.feature.vertical.home.WidgetAddOutcome.UNKNOWN_COLLECTION_GROUP
                val existing = homeWidgetStore.read(profileId)
                if (existing.records.size >= dev.infusion.core.domain.home.HomeWidgetLimits.MAX_WIDGETS_PER_PROFILE) {
                    return dev.infusion.feature.vertical.home.WidgetAddOutcome.LIMIT_REACHED
                }
                val source = dev.infusion.core.domain.home.HomeWidgetSourceRef.CollectionGroup(
                    packId = packId,
                    groupKey = groupKey,
                )
                val duplicate = existing.records.any { record ->
                    record.destinationId == destinationId && record.source.addressesSameSource(source)
                }
                if (duplicate) return dev.infusion.feature.vertical.home.WidgetAddOutcome.DUPLICATE
                val label = dev.infusion.core.domain.home.HomeWidgetRecord.storableTitle(
                    displayTitle ?: group.title.takeIf { it.isNotBlank() } ?: pack.name,
                )
                val record = dev.infusion.core.domain.home.HomeWidgetRecord(
                    widgetId = newHomeWidgetId(),
                    destinationId = destinationId,
                    source = source,
                    displayTitle = label,
                )
                homeWidgetStore.write(profileId, existing.records + record)
                return dev.infusion.feature.vertical.home.WidgetAddOutcome.ADDED
            }

            override fun moveWidget(
                profileId: String,
                widgetId: String,
                delta: Int,
            ): dev.infusion.feature.vertical.home.WidgetAddOutcome {
                val records = homeWidgetStore.read(profileId).records.toMutableList()
                val index = records.indexOfFirst { it.widgetId == widgetId }
                if (index < 0) return dev.infusion.feature.vertical.home.WidgetAddOutcome.WIDGET_UNKNOWN
                val moving = records[index]

                val siblings = records.filter { it.destinationId == moving.destinationId }
                val position = siblings.indexOfFirst { it.widgetId == widgetId }
                val neighbour = siblings.getOrNull(position + delta)
                    ?: return dev.infusion.feature.vertical.home.WidgetAddOutcome.ADDED
                val neighbourIndex = records.indexOfFirst { it.widgetId == neighbour.widgetId }
                records[index] = neighbour
                records[neighbourIndex] = moving
                homeWidgetStore.write(profileId, records)
                return dev.infusion.feature.vertical.home.WidgetAddOutcome.ADDED
            }

            override fun deleteWidget(profileId: String, widgetId: String) {
                val records = homeWidgetStore.read(profileId).records
                if (records.none { it.widgetId == widgetId }) return
                homeWidgetStore.write(profileId, records.filterNot { it.widgetId == widgetId })
            }
        }
    }

    private fun newHomeWidgetId(): String = dev.infusion.feature.vertical.home.newHomeWidgetId()

    fun widgetBuilderStateHolder(scope: CoroutineScope): dev.infusion.feature.vertical.home.WidgetBuilderStateHolder {

        val homeCatalogPort = addonPorts.homeCatalogPort(discoverService)
        return dev.infusion.feature.vertical.home.WidgetBuilderStateHolder(
            port = homeWidgetPort,
            scope = scope,
            spotlightSource = { profileId ->
                homeCatalogPort.presentationPreferences(profileId).spotlightSource
            },
        )
    }

    fun homeUpNextStateHolder(scope: CoroutineScope): dev.infusion.feature.vertical.home.HomeUpNextStateHolder =
        dev.infusion.feature.vertical.home.HomeUpNextStateHolder(
            watchStates = watchStateStore(),
            hierarchy = canonicalShowHierarchy,
            identity = dev.infusion.core.database.tracking.RoomTrackingInboundIdentity(
                titleDao = db.titleDao(),
                externalIdDao = db.externalIdDao(),
                supersession = titleSupersession,
                editionDao = db.editionDao(),
            ),

            identityCompletion = trackingInboundIdentityCompletion,
            selectionStore = upNextStore,
            presentationStore = upNextStore,
            scope = scope,
            activeProfileId = { profileScope.activeProfileId },
            availableTrackers = {
                trackingGraphs.upNextTrackers().map { (trackerId, displayName) ->
                    dev.infusion.feature.vertical.home.UpNextTrackerRef(trackerId, displayName)
                }
            },
            accountLabelFor = { trackerId -> trackingGraphs.upNextAccountLabel(trackerId) },
            readUpNext = { trackerId, request ->
                trackingGraphs.readUpNext(trackerId, request)
                    ?: dev.infusion.core.domain.tracking.TrackingInboundOutcome.Unavailable(
                        "no connected tracker declares Up Next",
                    )
            },
            enrich = { request -> upNextCardEnrichment(request) },
            cacheStore = upNextStore,

            presentationRetention = upNextPresentationRetention,

            trace = { line -> android.util.Log.i("INFUSION-UPNEXT", line) },

            presentationWindow = dev.infusion.feature.vertical.home.HomePresentationWindow(),

            playbackActive = videoPlaybackMediaSession.destinationActive,
        )

    fun upNextSettingsStateHolder(scope: CoroutineScope): dev.infusion.feature.vertical.home.UpNextSettingsStateHolder =
        dev.infusion.feature.vertical.home.UpNextSettingsStateHolder(
            selectionStore = upNextStore,
            presentationStore = upNextStore,
            scope = scope,
            activeProfileId = { profileScope.activeProfileId },
            availableTrackers = {
                trackingGraphs.upNextTrackers().map { (trackerId, displayName) ->
                    dev.infusion.feature.vertical.home.UpNextTrackerRef(trackerId, displayName)
                }
            },
        )

    private val upNextStore: dev.infusion.core.database.upnext.SharedPreferencesUpNextStore by lazy {
        dev.infusion.core.database.upnext.SharedPreferencesUpNextStore(context)
    }

    val configWebOwners: dev.infusion.tv.configweb.TvConfigOwners by lazy {
        dev.infusion.tv.configweb.TvConfigOwners(
            profileScope = profileScope,
            appearance = appearanceStateHolder,
            appearanceStore = appearancePreferences,
            homeCatalog = addonPorts.homeCatalogPort(discoverService),
            displayMatching = displayMatchingStateHolder,
            dolbyVisionProfile7Fallback = dolbyVisionProfile7FallbackStateHolder,
            audioLanguage = audioLanguagePreferenceStateHolder,
            subtitlePreference = subtitlePreferenceStateHolder,
            subtitleAppearance = subtitleAppearanceStateHolder,
            hdrSubtitleDimming = hdrSubtitleDimmingStateHolder,
            completionThreshold = completionThresholdStateHolder,
            playbackControlsAutoHide = playbackControlsAutoHideStateHolder,
            relativeSeekStep = relativeSeekStepStateHolder,
            segmentEvidence = segmentEvidenceSettingsStateHolder,
            sourceAutoplay = sourceAutoplaySettingsStateHolder,
            nextEpisode = nextEpisodeSettingsStateHolder,
            ratings = ratingsSettings,
            ratingsStore = ratingsPreferencesStore,
            ratingsCredentials = ratingsCredentialStore,
            addons = addonPorts.managementPort(),
            widgets = homeWidgetPort,
            widgetStore = homeWidgetStore,
            upNext = upNextSettingsStateHolder(appScope),
            upNextSelectionStore = upNextStore,
            upNextPresentationStore = upNextStore,
            collectionPacks = collectionPackManagerStateHolder(appScope),
            collectionPackStore = collectionPackStore,
            collectionRows = collectionRowSettingsStateHolder(appScope),
            collectionRowPreferences = collectionRowPreferenceStore,
        )
    }

    val configWebBridge: dev.infusion.tv.configweb.TvConfigOwnerBridge by lazy {
        dev.infusion.tv.configweb.TvConfigOwnerBridge(configWebOwners)
    }

    fun configWebCoordinator(): dev.infusion.feature.configweb.ConfigWebCoordinator =
        dev.infusion.feature.configweb.ConfigWebCoordinator(
            port = configWebBridge,
            state = configWebBridge,
        )

    private val homePresentationStore: dev.infusion.core.domain.home.HomePresentationStore by lazy {
        dev.infusion.core.database.home.SharedPreferencesHomePresentationStore(context)
    }

    private val homePresentationLocale: () -> String = { metadataLocale().tag }

    private val homeHeroPresentationCache: dev.infusion.feature.vertical.home.HomeHeroPresentationCache by lazy {
        dev.infusion.feature.vertical.home.PersistedHomeHeroPresentationCache(
            store = homePresentationStore,
            profileId = { activeProfileId },
            localeToken = homePresentationLocale,
        )
    }

    val heroPresentationCache: dev.infusion.feature.vertical.home.HomeHeroPresentationCache
        get() = homeHeroPresentationCache

    private val upNextPresentationRetention: dev.infusion.feature.vertical.home.UpNextPresentationRetention =
        dev.infusion.feature.vertical.home.PersistedUpNextPresentationRetention(
            store = homePresentationStore,
            localeToken = homePresentationLocale,
        )

    private suspend fun upNextCardEnrichment(
        request: dev.infusion.feature.vertical.home.UpNextCardRequest,
    ): dev.infusion.feature.vertical.home.UpNextCardEnrichment {
        val catalog = homeCwBaseCatalog()
        val seriesTitle = runCatching {
            catalog.observeTitleDetails(request.seriesTitleId).firstOrNull()?.title?.displayTitle
        }.getOrNull()

        val episodeTitleId = runCatching { upNextEpisodeTitleId(catalog, request) }.getOrNull()

        val canonical = runCatching {
            upNextEpisodePresentation(request.seriesTitleId, request.seasonNumber, request.episodeNumber)
        }.getOrDefault(UpNextEpisodePresentation())
        return dev.infusion.feature.vertical.home.UpNextCardEnrichment(
            seriesTitle = seriesTitle,
            episodeTitleId = episodeTitleId,
            artwork = canonical.artwork,
            episodeTitle = canonical.episodeTitle,
            runtimeMinutes = canonical.runtimeMinutes,
            airDate = canonical.airDate,

            parentBackdrop = parentShowBackdropArtworkRef(request.seriesTitleId, seriesTitle),
        )
    }

    private suspend fun parentShowBackdropArtworkRef(
        seriesTitleId: dev.infusion.core.model.TitleId,
        cardTitle: String?,
    ): dev.infusion.core.model.MediaArtworkRef? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val locale = metadataLocale()
            val identity = runCatching {
                mediaIdentityResolver.resolve(
                    seriesTitleId,
                    dev.infusion.core.model.metadata.MediaType.SHOW,
                    locale,
                )
            }.getOrNull() ?: run {
                fallbackGap("identity-unavailable")
                return@withContext null
            }

            val details = runCatching {
                metadataRepository.details(identity, locale)
            }.getOrNull() ?: run {
                fallbackGap("details-unavailable")
                return@withContext null
            }
            val backdrop = details.artwork.backdrop ?: run {
                fallbackGap("show-has-no-backdrop")
                return@withContext null
            }
            val ref = dev.infusion.feature.vertical.home.episodeCardParentBackdropRef(
                parentBackdrop = backdrop,
                canonicalTitle = cardTitle,
                parentShowTitle = details.title,
            )
            if (ref == null) {

                fallbackGap("identity-disagreement")
            }
            ref
        }

    private fun fallbackGap(reason: String) {
        android.util.Log.i("INFUSION-ARTWORK", "episode-card artwork fallback reason=$reason")
    }

    private data class UpNextEpisodePresentation(
        val artwork: dev.infusion.core.model.MediaArtworkRef? = null,
        val episodeTitle: String? = null,
        val runtimeMinutes: Int? = null,
        val airDate: java.time.LocalDate? = null,
    )

    private suspend fun upNextEpisodePresentation(
        seriesId: dev.infusion.core.model.TitleId,
        seasonNumber: Int,
        episodeNumber: Int,
    ): UpNextEpisodePresentation {
        val locale = metadataLocale()
        val identity = runCatching {
            mediaIdentityResolver.resolve(
                seriesId,
                dev.infusion.core.model.metadata.MediaType.SHOW,
                locale,
            )
        }.getOrNull() ?: return UpNextEpisodePresentation()
        val season = runCatching {
            metadataRepository.seasonDetails(identity, seasonNumber, locale).valueOrNull()
        }.getOrNull() ?: return UpNextEpisodePresentation()
        val episode = season.episodes.firstOrNull { it.episodeNumber == episodeNumber }
            ?: return UpNextEpisodePresentation()
        return UpNextEpisodePresentation(
            artwork = dev.infusion.feature.vertical.home.canonicalCwStillRef(episode.still),
            episodeTitle = episode.name?.takeIf { it.isNotBlank() },
            runtimeMinutes = episode.runtimeMinutes,
            airDate = episode.airDate,
        )
    }

    private suspend fun upNextEpisodeTitleId(
        catalog: dev.infusion.core.domain.catalog.CatalogPort,
        request: dev.infusion.feature.vertical.home.UpNextCardRequest,
    ): dev.infusion.core.model.TitleId? {
        val seriesNative = catalog.nativeItemIdFor(request.seriesTitleId) ?: return null
        val seasons = catalog.children(seriesNative, 0, 200)?.entries.orEmpty()
        val seasonNative = seasons.firstOrNull { it.seasonNumber == request.seasonNumber }
            ?.nativeItemId?.takeIf { it.isNotBlank() } ?: return null
        return catalog.children(seasonNative, 0, 200)?.entries.orEmpty()
            .firstOrNull { it.episodeNumber == request.episodeNumber }
            ?.titleId
    }

    fun homePresentationStateHolder(scope: CoroutineScope): HomePresentationStateHolder =
        HomePresentationStateHolder(
            port = addonPorts.homeCatalogPort(discoverService),
            scope = scope,
        )

    fun homeContinueWatchingStateHolder(scope: CoroutineScope): HomeContinueWatchingStateHolder {

        val cwCatalog = homeCwBaseCatalog()
        return HomeContinueWatchingStateHolder(
            localContinueWatching = continueWatching,
            scope = scope,
            jellyfin = { jellyfinTabPort().continueWatching() },
            resolveCwTitle = { state ->

                resolveCwTitleFromCatalog(cwCatalog, state, canonicalShowHierarchy)
            },
            provenanceFor = { state ->
                homeCwProvenance(state.watchKey.titleId ?: state.watchKey.seriesId)
            },
            artworkFor = { state ->
                homeCwArtwork(state)
            },

            jellyfinSourceId = jellyfinSource?.sourceId,

            trace = { line -> android.util.Log.i("INFUSION-CW", line) },

            presentationWindows = dev.infusion.feature.vertical.home.HomeCwPresentationWindows(),

            presentationCache = homePresentationStore,
            presentationProfile = { activeProfileId },
            localeToken = homePresentationLocale,

            playbackActive = videoPlaybackMediaSession.destinationActive,
        )
    }

    private fun homeCwBaseCatalog(): dev.infusion.core.domain.catalog.CatalogPort = addonNativePort()

    private suspend fun cwOwnerArtworkRefs(
        titleId: dev.infusion.core.model.TitleId,
    ): List<dev.infusion.core.model.MediaArtworkRef> {
        val jellyfinId = jellyfinSource?.sourceId
        val descriptors = addonRegistry.runtimesFor(
            addonPorts.viewerProfileSnapshot(),
            addonPorts.primaryProfileSnapshot(),
        )
        return titleAvailabilityLookup.byTitle(titleId).mapNotNull { row ->
            val native = row.nativeResourceId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            when {
                row.sourceId == jellyfinId ->
                    dev.infusion.core.model.MediaArtworkRef.catalog(native)
                else -> descriptors.firstOrNull { it.sourceId == row.sourceId }?.addonId
                    ?.let { addonId ->
                        dev.infusion.core.model.MediaArtworkRef.addon(
                            addonId = addonId,
                            nativeItemId = native,
                        )
                    }
            }
        }
    }

    private suspend fun cwCanonicalArtworkRef(
        state: dev.infusion.core.model.watch.WatchState,
        titleId: dev.infusion.core.model.TitleId,
    ): dev.infusion.core.model.MediaArtworkRef? {
        val locale = metadataLocale()
        val type = if (state.watchKey.isEpisode) {
            dev.infusion.core.model.metadata.MediaType.SHOW
        } else {
            dev.infusion.core.model.metadata.MediaType.MOVIE
        }
        val identity = runCatching { mediaIdentityResolver.resolve(titleId, type, locale) }.getOrNull()
            ?: return null
        val artwork = runCatching {
            metadataRepository.observeDetails(identity, locale).first().details?.artwork
        }.getOrNull() ?: return null
        return dev.infusion.feature.vertical.home.canonicalCwArtworkRef(
            poster = artwork.poster,
            backdrop = artwork.backdrop,
        )
    }

    private fun cwEpisodeUnitArtworkRefs(
        state: dev.infusion.core.model.watch.WatchState,
    ): List<dev.infusion.core.model.MediaArtworkRef> {
        val anchor = state.anchor?.nativeItemId ?: return emptyList()
        val native = anchor.nativeId
        if (native.isBlank()) return emptyList()
        val jellyfinId = jellyfinSource?.sourceId
        if (jellyfinId != null && anchor.sourceId == jellyfinId) {
            return listOfNotNull(dev.infusion.feature.vertical.home.episodeCwStillRef(native))
        }
        val addonId = addonRegistry.runtimesFor(
            addonPorts.viewerProfileSnapshot(),
            addonPorts.primaryProfileSnapshot(),
        ).firstOrNull { runtime -> runtime.sourceId == anchor.sourceId }?.addonId
        return listOfNotNull(
            if (addonId != null) {
                dev.infusion.feature.vertical.home.episodeCwAddonStillRef(addonId, native)
            } else {
                dev.infusion.feature.vertical.home.episodeCwStillRef(native)
            },
        )
    }

    private suspend fun cwCanonicalEpisodeStillRef(
        state: dev.infusion.core.model.watch.WatchState,
    ): dev.infusion.core.model.MediaArtworkRef? {
        val key = state.watchKey
        val seriesId = key.seriesId ?: return null
        val seasonNumber = key.seasonNumber ?: return null
        val episodeNumber = key.episodeNumber ?: return null
        return canonicalEpisodeStillRef(seriesId, seasonNumber, episodeNumber)
    }

    private suspend fun canonicalEpisodeStillRef(
        seriesId: dev.infusion.core.model.TitleId,
        seasonNumber: Int,
        episodeNumber: Int,
    ): dev.infusion.core.model.MediaArtworkRef? {
        val locale = metadataLocale()
        val identity = runCatching {
            mediaIdentityResolver.resolve(
                seriesId,
                dev.infusion.core.model.metadata.MediaType.SHOW,
                locale,
            )
        }.getOrNull() ?: return null
        val season = runCatching {
            metadataRepository.seasonDetails(identity, seasonNumber, locale).valueOrNull()
        }.getOrNull() ?: return null
        val still = season.episodes
            .firstOrNull { it.episodeNumber == episodeNumber }
            ?.still
        return dev.infusion.feature.vertical.home.canonicalCwStillRef(still)
    }

    private fun cwDrawnSlot(
        kind: dev.infusion.core.model.TitleKind,
        ref: dev.infusion.core.model.MediaArtworkRef,
        policy: dev.infusion.tv.ui.presentation.ContentCardPresentation,
    ): dev.infusion.core.model.MediaArtworkRef? =
        when (policy.artworkSourceFor(kind)) {
            dev.infusion.tv.ui.presentation.ContentCardArtworkSource.PROJECTED -> ref
            dev.infusion.tv.ui.presentation.ContentCardArtworkSource.LANDSCAPE ->
                ref.landscapeSlot()
        }

    private fun cwCardArtworkTarget(
        kind: dev.infusion.core.model.TitleKind,
        policy: dev.infusion.tv.ui.presentation.ContentCardPresentation,
    ): androidx.compose.ui.unit.IntSize =
        dev.infusion.tv.ui.presentation.homeCwArtworkTargetPx(
            kind = kind,
            policy = policy,
            screenWidthDp = context.resources.configuration.screenWidthDp,
            density = context.resources.displayMetrics.density,
        )

    private suspend fun homeCwArtwork(
        state: dev.infusion.core.model.watch.WatchState,
    ): dev.infusion.core.model.MediaArtworkRef? {
        val key = state.watchKey
        val titleId = key.titleId ?: key.seriesId ?: return null
        return withContext(Dispatchers.IO) {
            val kind = if (key.isEpisode) {
                dev.infusion.core.model.TitleKind.EPISODE
            } else {
                dev.infusion.core.model.TitleKind.MOVIE
            }

            val presentation = appearanceStateHolder.preferences.value.toContentCardPresentation()

            val cardTarget = cwCardArtworkTarget(kind, presentation)
            dev.infusion.feature.vertical.home.selectCwArtwork(

                ownerRefs = if (key.isEpisode) {
                    cwEpisodeUnitArtworkRefs(state)
                } else {
                    cwOwnerArtworkRefs(titleId)
                },

                canonicalRef = {
                    if (key.isEpisode) {
                        cwCanonicalEpisodeStillRef(state)
                    } else {
                        cwCanonicalArtworkRef(state, titleId)
                    }
                },

                parentShowRef = { cwParentShowBackdropRef(state) },
                draws = { ref ->
                    val slot = cwDrawnSlot(kind, ref, presentation)

                    val cardRole = ref.policy == dev.infusion.core.model.ArtworkPolicy.BACKDROP
                    slot != null &&
                        artworkBytesSource.bytes(
                            dev.infusion.core.artworkimage.ArtworkRequest(
                                locator = slot.locator,
                                policy = slot.policy,
                                targetWidthPx = cardTarget.width,
                                targetHeightPx = cardTarget.height,
                                retainsPreviousSuccess = if (cardRole) false else true,
                                presentationRole = if (cardRole) {
                                    dev.infusion.core.model.ArtworkPresentationRole.LANDSCAPE_CARD
                                } else {
                                    dev.infusion.core.model.ArtworkPresentationRole.BACKGROUND
                                },
                            ),
                        ) != null
                },
            )
        }
    }

    private suspend fun cwParentShowBackdropRef(
        state: dev.infusion.core.model.watch.WatchState,
    ): dev.infusion.core.model.MediaArtworkRef? {
        val key = state.watchKey
        if (!key.isEpisode) return null
        val seriesId = key.seriesId ?: return null
        val cardTitle = runCatching {
            homeCwBaseCatalog().observeTitleDetails(seriesId).firstOrNull()?.title?.displayTitle
        }.getOrNull()
        return parentShowBackdropArtworkRef(seriesId, cardTitle)
    }

    private suspend fun homeCwProvenance(titleId: dev.infusion.core.model.TitleId?): String? {
        if (titleId == null) return null
        return withContext(Dispatchers.IO) {
            val jellyfinId = jellyfinSource?.sourceId
            val descriptors = addonRegistry.runtimesFor(
                addonPorts.viewerProfileSnapshot(),
                addonPorts.primaryProfileSnapshot(),
            )
            titleAvailabilityLookup.byTitle(titleId).firstNotNullOfOrNull { row ->
                when {
                    row.sourceId == jellyfinId -> "Jellyfin"
                    else -> descriptors.firstOrNull { it.sourceId == row.sourceId }?.displayName
                }
            }
        }
    }

    private var retainedHomeTab: Pair<String, RetainedHomeTabState>? = null
    private var retainedHomeTabLifetime: CoroutineScope? = null

    private var retainedDiscoverTab: Pair<String, RetainedDiscoverTabState>? = null
    private var retainedDiscoverTabLifetime: CoroutineScope? = null

    private fun retainedTabLifetime(): CoroutineScope =
        CoroutineScope(SupervisorJob(appScope.coroutineContext[Job]) + Dispatchers.Default)

    fun retainedHomeTabState(): RetainedHomeTabState {
        val profileId = profileScope.activeProfileId
        retainedHomeTab?.let { (id, state) -> if (id == profileId) return state }
        retainedHomeTabLifetime?.cancel()
        val lifetime = retainedTabLifetime().also { retainedHomeTabLifetime = it }
        val state = RetainedHomeTabState(
            continueWatching = homeContinueWatchingStateHolder(lifetime),
            catalogRows = homeCatalogRowsStateHolder(lifetime),
            upNext = homeUpNextStateHolder(lifetime),
            presentation = homePresentationStateHolder(lifetime),

            spotlight = dev.infusion.tv.ui.screens.HomeSpotlightStateHolder(),
        )
        retainedHomeTab = profileId to state
        return state
    }

    fun retainedDiscoverTabState(): RetainedDiscoverTabState {
        val profileId = profileScope.activeProfileId
        retainedDiscoverTab?.let { (id, state) -> if (id == profileId) return state }
        retainedDiscoverTabLifetime?.cancel()
        val lifetime = retainedTabLifetime().also { retainedDiscoverTabLifetime = it }
        val state = RetainedDiscoverTabState(holder = discoverStateHolder(lifetime))
        retainedDiscoverTab = profileId to state
        return state
    }

    private var retainedWatchlistTab: Pair<String, RetainedWatchlistTabState>? = null
    private var retainedWatchlistTabLifetime: CoroutineScope? = null

    fun retainedWatchlistTabState(): RetainedWatchlistTabState {
        val profileId = profileScope.activeProfileId
        retainedWatchlistTab?.let { (id, state) -> if (id == profileId) return state }
        retainedWatchlistTabLifetime?.cancel()
        val lifetime = retainedTabLifetime().also { retainedWatchlistTabLifetime = it }
        val state = RetainedWatchlistTabState(
            recentlyWatched = watchlistRecentlyWatchedStateHolder(
                lifetime,

                playbackActive = videoPlaybackMediaSession.destinationActive,
            ),

            membershipArtwork = WatchlistMembershipArtworkMemory(),
        )
        retainedWatchlistTab = profileId to state
        return state
    }

    private var retainedJellyfinTab: JellyfinTabStateHolder? = null

    fun retainedJellyfinTabStateHolder(): JellyfinTabStateHolder =
        retainedJellyfinTab ?: JellyfinTabStateHolder(port = jellyfinTabPort(), scope = appScope)
            .also { retainedJellyfinTab = it }

    private val retainedLibraryHolders by lazy {
        object : LinkedHashMap<String, dev.infusion.feature.vertical.jellyfin.library.LibraryBrowseStateHolder>(
            4,
            0.75f,
            true,
        ) {}
    }

    private val retainedLibraryHolderLimit = 3

    fun retainedLibraryBrowseStateHolder(
        libraryNativeId: String,
        libraryName: String,
        collectionType: String?,
    ): dev.infusion.feature.vertical.jellyfin.library.LibraryBrowseStateHolder {
        val session = jellyfinSource?.source?.session()

        val cacheKey = "${session?.serverId.orEmpty()}|${session?.userId.orEmpty()}|$libraryNativeId|${collectionType.orEmpty()}"
        retainedLibraryHolders[cacheKey]?.let { return it }
        val holder = dev.infusion.feature.vertical.jellyfin.library.LibraryBrowseStateHolder(
            port = libraryBrowsePort(libraryNativeId),
            scope = appScope,
            serverId = session?.serverId ?: "jellyfin",
            userId = session?.userId ?: "",
            libraryNativeId = libraryNativeId,
            libraryName = libraryName,
            collectionType = collectionType,
        )
        retainedLibraryHolders[cacheKey] = holder
        while (retainedLibraryHolders.size > retainedLibraryHolderLimit) {
            val oldest = retainedLibraryHolders.keys.firstOrNull() ?: break
            retainedLibraryHolders.remove(oldest)
        }
        return holder
    }

    fun invalidateLibraryBrowse() {
        retainedLibraryHolders.clear()

        runCatching { musicQueueStateHolder.stop() }
    }

    @Volatile
    private var videoAttemptReleaser: (() -> Unit)? = null

    fun installVideoAttemptReleaser(releaser: () -> Unit) {
        videoAttemptReleaser = releaser
    }

    val musicQueueStateHolder: dev.infusion.feature.vertical.music.MusicQueueStateHolder by lazy {
        dev.infusion.feature.vertical.music.MusicQueueStateHolder(
            port = musicQueuePort,
            engine = dev.infusion.core.playback.media3.Media3PlaybackEngine(
                context = context,

                audioAttributes = androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                handleAudioFocus = false,
            ),
            scope = appScope,
            profileId = { profileScope.activeProfileId },
            reporterFor = { identity ->
                jellyfinSource?.musicReporterFor(identity, appScope)
            },
            audioFocus = dev.infusion.tv.music.AndroidMusicAudioFocus(context),
            onTakeAudibleOwnership = {

                runCatching { videoAttemptReleaser?.invoke() }
            },
            dispatcher = Dispatchers.Main,
        ).also { holder ->
            holder.attachAudioFocus()
            holder.onHandlerFailure = { failure ->
                android.util.Log.e("INFUSION-MUSIC", "queue handler failure", failure)
            }
        }
    }

    val musicMediaSessionHost: dev.infusion.tv.music.MusicMediaSessionHost by lazy {
        dev.infusion.tv.music.MusicMediaSessionHost(
            context = context,
            holder = musicQueueStateHolder,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        ).also { host -> host.start() }
    }

    val musicNowPlayingPresentation: dev.infusion.tv.music.MusicNowPlayingPresentation by lazy {
        dev.infusion.tv.music.MusicNowPlayingPresentation()
    }

    val videoPlaybackMediaSession: dev.infusion.tv.playback.VideoPlaybackMediaSessionHost by lazy {
        dev.infusion.tv.playback.VideoPlaybackMediaSessionHost(context)
    }

    private val musicQueuePort: dev.infusion.core.domain.music.MusicQueuePort =
        object : dev.infusion.core.domain.music.MusicQueuePort {
            private fun graph(): dev.infusion.source.jellyfin.music.JellyfinMusicQueuePort? =
                jellyfinSource?.musicQueue

            override suspend fun page(
                context: dev.infusion.core.domain.music.MusicQueueContext,
                offset: Int,
                limit: Int,
            ): dev.infusion.core.domain.music.MusicPageResult =
                graph()?.page(context, offset, limit) ?: dev.infusion.core.domain.music.MusicPageResult.Failed(
                    "Jellyfin is not connected.",
                    dev.infusion.core.domain.music.MusicPageFailure.DISCONNECTED,
                )

            override suspend fun transportFor(
                entry: dev.infusion.core.domain.music.MusicQueueEntry,
                requestId: String,
            ): dev.infusion.core.domain.music.MusicTransportResult =
                graph()?.transportFor(entry, requestId)
                    ?: dev.infusion.core.domain.music.MusicTransportResult.Failed(
                        "Jellyfin is not connected.",
                        dev.infusion.core.domain.music.MusicTransportFailure.DISCONNECTED,
                    )

            override suspend fun lyrics(
                trackNativeId: String,
            ): dev.infusion.core.domain.music.MusicLyricsResult =
                graph()?.lyrics(trackNativeId)
                    ?: dev.infusion.core.domain.music.MusicLyricsResult.Failed("Jellyfin is not connected.")
        }

    private fun libraryBrowsePort(
        libraryNativeId: String,
    ): dev.infusion.feature.vertical.jellyfin.library.LibraryBrowsePort =
        object : dev.infusion.feature.vertical.jellyfin.library.LibraryBrowsePort {

            private fun graph(): dev.infusion.source.jellyfin.catalog.JellyfinCatalogPort? =
                (jellyfinSource?.catalog as? dev.infusion.source.jellyfin.catalog.JellyfinCatalogPort)

            private fun dev.infusion.source.jellyfin.catalog.JellyfinRowItem.toLibraryItem(): JellyfinTabRowItem =
                toTabItem(keyPrefix = "library").let { item ->

                    val occurrence = playlistItemId?.takeIf { it.isNotBlank() }
                    if (occurrence == null) item else item.copy(key = "${item.key}#$occurrence")
                }

            override suspend fun page(
                key: dev.infusion.feature.vertical.jellyfin.library.LibraryQueryKey,
                meta: dev.infusion.feature.vertical.jellyfin.library.LibraryRequestMeta,
            ): dev.infusion.feature.vertical.jellyfin.library.LibraryFetch {
                val catalog = graph() ?: return dev.infusion.feature.vertical.jellyfin.library.LibraryFetch
                    .Failed("Jellyfin is not connected.")
                val level = key.level
                val parameters = buildMap {
                    putAll(key.filters.toQueryParameters())

                    putAll(key.viewParameters())
                }
                val result = when (key.source) {

                    dev.infusion.feature.vertical.jellyfin.library.LibraryQuerySource.GENRES ->
                        catalog.libraryGenreCategories(
                            parentId = key.libraryNativeId,
                            includeItemTypes = key.itemTypes,
                            startIndex = meta.startIndex,
                            limit = meta.limit,
                        )

                    dev.infusion.feature.vertical.jellyfin.library.LibraryQuerySource.STUDIOS ->
                        catalog.libraryStudioCategories(
                            parentId = key.libraryNativeId,
                            includeItemTypes = key.itemTypes,
                            startIndex = meta.startIndex,
                            limit = meta.limit,
                        )

                    dev.infusion.feature.vertical.jellyfin.library.LibraryQuerySource.ARTISTS ->
                        catalog.libraryArtistCategories(
                            parentId = key.libraryNativeId,
                            albumArtists = false,
                            startIndex = meta.startIndex,
                            limit = meta.limit,
                        )

                    dev.infusion.feature.vertical.jellyfin.library.LibraryQuerySource.ALBUM_ARTISTS ->
                        catalog.libraryArtistCategories(
                            parentId = key.libraryNativeId,
                            albumArtists = true,
                            startIndex = meta.startIndex,
                            limit = meta.limit,
                        )

                    dev.infusion.feature.vertical.jellyfin.library.LibraryQuerySource.PLAYLIST_ITEMS ->
                        catalog.libraryPlaylistItems(
                            playlistId = (level as dev.infusion.feature.vertical.jellyfin.library.LibraryLevel.Playlist).playlistId,
                            startIndex = meta.startIndex,
                            limit = meta.limit,
                        )

                    else -> catalog.libraryItemPage(
                        parentId = key.queryParentId,
                        recursive = key.recursive,
                        includeItemTypes = key.itemTypes,
                        startIndex = meta.startIndex,
                        limit = meta.limit,
                        sortBy = key.sortBy,
                        sortOrder = key.sortOrder.serverValue,
                        fields = dev.infusion.feature.vertical.jellyfin.library.LibraryCapabilities
                            .itemFields(key.type, key.view, level),
                        parameters = parameters,
                        enableTotalRecordCount = meta.enableTotalRecordCount,
                    )
                }
                return when (result) {
                    is dev.infusion.source.jellyfin.client.ApiResult.Ok -> {
                        val page = result.value
                        dev.infusion.feature.vertical.jellyfin.library.LibraryFetch.Ok(
                            page = dev.infusion.feature.vertical.jellyfin.library.LibraryPage(
                                pageIndex = meta.pageIndex,
                                startIndex = meta.startIndex,
                                items = page.items.map { it.toLibraryItem() },
                            ),

                            total = if (meta.enableTotalRecordCount) page.totalRecordCount else null,
                        )
                    }
                    is dev.infusion.source.jellyfin.client.ApiResult.Err ->
                        dev.infusion.feature.vertical.jellyfin.library.LibraryFetch.Failed(result.failure.message)
                }
            }

            override suspend fun filterOptions(
                key: dev.infusion.feature.vertical.jellyfin.library.LibraryQueryKey,
            ): dev.infusion.feature.vertical.jellyfin.library.LibraryFilterOptionsFetch {
                val catalog = graph() ?: return dev.infusion.feature.vertical.jellyfin.library.LibraryFilterOptionsFetch
                    .Failed("Jellyfin is not connected.")
                return when (val result = catalog.libraryFilterValues(libraryNativeId, key.itemTypes)) {
                    is dev.infusion.source.jellyfin.client.ApiResult.Ok -> {
                        val values = result.value
                        dev.infusion.feature.vertical.jellyfin.library.LibraryFilterOptionsFetch.Ok(
                            dev.infusion.feature.vertical.jellyfin.library.LibraryFilterOptions(
                                genres = values.genres.map {
                                    dev.infusion.feature.vertical.jellyfin.library.LibraryFilterOption(it)
                                },
                                officialRatings = values.officialRatings.map {
                                    dev.infusion.feature.vertical.jellyfin.library.LibraryFilterOption(it)
                                },
                                tags = values.tags.map {
                                    dev.infusion.feature.vertical.jellyfin.library.LibraryFilterOption(it)
                                },
                                years = values.years,
                            ),
                        )
                    }
                    is dev.infusion.source.jellyfin.client.ApiResult.Err ->
                        dev.infusion.feature.vertical.jellyfin.library.LibraryFilterOptionsFetch.Failed(result.failure.message)
                }
            }

            override suspend fun genreArtwork(
                key: dev.infusion.feature.vertical.jellyfin.library.LibraryQueryKey,
                genreIds: List<String>,
            ): dev.infusion.feature.vertical.jellyfin.library.LibraryGenreArtworkFetch {
                val catalog = graph()
                    ?: return dev.infusion.feature.vertical.jellyfin.library.LibraryGenreArtworkFetch
                        .Failed("Jellyfin is not connected.")
                val distinct = genreIds.distinct().filter { it.isNotBlank() }
                if (distinct.isEmpty()) {
                    return dev.infusion.feature.vertical.jellyfin.library.LibraryGenreArtworkFetch.Ok(emptyMap())
                }
                val permits = kotlinx.coroutines.sync.Semaphore(LIBRARY_GENRE_ARTWORK_PARALLELISM)

                val imageType = if (key.type == dev.infusion.feature.vertical.jellyfin.library.LibraryType.MUSIC) {
                    dev.infusion.core.model.ArtworkLocator.DEFAULT_CATALOG_TYPE
                } else {
                    dev.infusion.core.model.ArtworkLocator.CATALOG_BACKDROP_TYPE
                }
                val resolved = coroutineScope {
                    distinct.map { genreId ->
                        async(Dispatchers.IO) {
                            permits.withPermit {
                                when (
                                    val result = catalog.libraryGenreArtworkItems(
                                        parentId = libraryNativeId,
                                        includeItemTypes = key.itemTypes,
                                        genreId = genreId,
                                        limit = dev.infusion.feature.vertical.jellyfin.library
                                            .LibraryGenreArtwork.CANDIDATES,
                                        imageType = imageType,
                                    )
                                ) {
                                    is dev.infusion.source.jellyfin.client.ApiResult.Ok ->
                                        result.value.filter { it.isNotBlank() }
                                            .takeIf { it.isNotEmpty() }
                                            ?.let { candidates -> genreId to candidates }
                                    is dev.infusion.source.jellyfin.client.ApiResult.Err -> null
                                }
                            }
                        }
                    }.awaitAll()
                }.filterNotNull().toMap()
                if (resolved.isEmpty()) {
                    return dev.infusion.feature.vertical.jellyfin.library.LibraryGenreArtworkFetch
                        .Failed("Could not load genre artwork.")
                }
                return dev.infusion.feature.vertical.jellyfin.library.LibraryGenreArtworkFetch.Ok(resolved)
            }

            override suspend fun sections(
                key: dev.infusion.feature.vertical.jellyfin.library.LibraryQueryKey,
            ): dev.infusion.feature.vertical.jellyfin.library.LibrarySuggestionsFetch {
                val catalog = graph() ?: return dev.infusion.feature.vertical.jellyfin.library.LibrarySuggestionsFetch
                    .Failed("Jellyfin is not connected.")

                fun section(
                    key: String,
                    title: String,
                    result: dev.infusion.source.jellyfin.client.ApiResult<dev.infusion.source.jellyfin.catalog.JellyfinRowPage>,
                    failures: MutableList<String>,
                ): dev.infusion.feature.vertical.jellyfin.library.LibrarySuggestionSection =
                    when (result) {
                        is dev.infusion.source.jellyfin.client.ApiResult.Ok ->
                            dev.infusion.feature.vertical.jellyfin.library.LibrarySuggestionSection(
                                key = key,
                                title = title,
                                items = result.value.items.map { it.toLibraryItem() },
                            )
                        is dev.infusion.source.jellyfin.client.ApiResult.Err -> {
                            failures += result.failure.message
                            dev.infusion.feature.vertical.jellyfin.library.LibrarySuggestionSection(key, title, emptyList())
                        }
                    }

                val failures = mutableListOf<String>()

                val musicSongFields = dev.infusion.feature.vertical.jellyfin.library.LibraryCapabilities
                    .itemFields(
                        dev.infusion.feature.vertical.jellyfin.library.LibraryType.MUSIC,
                        dev.infusion.feature.vertical.jellyfin.library.LibraryView.SONGS,
                        dev.infusion.feature.vertical.jellyfin.library.LibraryLevel.Root,
                    )
                val sections =
                    if (key.view == dev.infusion.feature.vertical.jellyfin.library.LibraryView.UPCOMING) {

                        val upcoming = catalog.libraryUpcomingSection(
                            parentId = libraryNativeId,
                            limit = UPCOMING_LIMIT,
                        )
                        when (upcoming) {
                            is dev.infusion.source.jellyfin.client.ApiResult.Ok -> {
                                val dated = upcoming.value.items.map { it.toLibraryItem() }
                                dated.groupBy { it.airDate }
                                    .entries
                                    .sortedByDescending { it.key }
                                    .map { (date, items) ->
                                        dev.infusion.feature.vertical.jellyfin.library.LibrarySuggestionSection(
                                            key = "upcoming:${date ?: "undated"}",
                                            title = upcomingSectionTitle(date),
                                            items = items,
                                        )
                                    }
                            }
                            is dev.infusion.source.jellyfin.client.ApiResult.Err -> {
                                failures += upcoming.failure.message
                                emptyList()
                            }
                        }
                    } else {
                        when (key.type) {
                            dev.infusion.feature.vertical.jellyfin.library.LibraryType.MOVIES -> listOf(
                                section(
                                    "suggestions:resume",
                                    "Continue watching",
                                    catalog.libraryResumeSection(libraryNativeId, listOf("Movie")),
                                    failures,
                                ),
                                section(
                                    "suggestions:latest",
                                    "Recently added",
                                    catalog.libraryLatestSection(libraryNativeId, listOf("Movie")),
                                    failures,
                                ),
                                section(
                                    "suggestions:recommended",
                                    "Recommended",
                                    catalog.libraryRecommendationsSection(libraryNativeId),
                                    failures,
                                ),
                            )
                            dev.infusion.feature.vertical.jellyfin.library.LibraryType.SHOWS -> listOf(
                                section(
                                    "suggestions:resume",
                                    "Continue watching",
                                    catalog.libraryResumeSection(libraryNativeId, listOf("Episode")),
                                    failures,
                                ),
                                section(
                                    "suggestions:latest",
                                    "Recently added",
                                    catalog.libraryLatestSection(libraryNativeId, listOf("Episode"), limit = 30),
                                    failures,
                                ),
                                section(
                                    "suggestions:next-up",
                                    "Next Up",
                                    catalog.libraryNextUpSection(libraryNativeId),
                                    failures,
                                ),
                            )
                            dev.infusion.feature.vertical.jellyfin.library.LibraryType.MUSIC -> listOf(
                                section(
                                    "suggestions:recently-played",
                                    "Recently played",
                                    catalog.librarySection(
                                        libraryNativeId, listOf("Audio"), limit = 18,
                                        filters = "IsPlayed", sortBy = "DatePlayed", sortOrder = "Descending",

                                        fields = musicSongFields,
                                    ),
                                    failures,
                                ),
                                section(
                                    "suggestions:most-played",
                                    "Most played",
                                    catalog.librarySection(
                                        libraryNativeId, listOf("Audio"), limit = 18,
                                        filters = "IsPlayed", sortBy = "PlayCount", sortOrder = "Descending",
                                        fields = musicSongFields,
                                    ),
                                    failures,
                                ),
                                section(
                                    "suggestions:latest",
                                    "Recently added",
                                    catalog.libraryLatestSection(libraryNativeId, listOf("Audio")),
                                    failures,
                                ),
                                section(
                                    "suggestions:favourite-albums",
                                    "Favourite albums",
                                    catalog.librarySection(
                                        libraryNativeId, listOf("MusicAlbum"), limit = 18,
                                        isFavorite = true,
                                    ),
                                    failures,
                                ),
                            )
                        }
                    }
                if (failures.size == sections.size && failures.isNotEmpty()) {
                    return dev.infusion.feature.vertical.jellyfin.library.LibrarySuggestionsFetch
                        .Failed("Could not load this view.")
                }
                return dev.infusion.feature.vertical.jellyfin.library.LibrarySuggestionsFetch.Ok(sections)
            }
        }

    private val UPCOMING_LIMIT = 60

    private fun upcomingSectionTitle(date: java.time.LocalDate?): String {
        if (date == null) return "No air date"
        val today = java.time.LocalDate.now()
        return when {
            date == today -> "Today"
            date == today.minusDays(1) -> "Yesterday"
            date == today.plusDays(1) -> "Tomorrow"
            else -> date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy"))
        }
    }

    private fun jellyfinTabPort(): JellyfinTabPort = object : JellyfinTabPort {
        private fun graph(): dev.infusion.source.jellyfin.catalog.JellyfinCatalogPort? =
            (jellyfinSource?.catalog as? dev.infusion.source.jellyfin.catalog.JellyfinCatalogPort)

        private suspend fun row(
            fetch: suspend (dev.infusion.source.jellyfin.catalog.JellyfinCatalogPort) ->
                dev.infusion.source.jellyfin.client.ApiResult<dev.infusion.source.jellyfin.catalog.JellyfinRowPage>,
            keyPrefix: String,
        ): JellyfinRowFetch {
            val catalog = graph()
                ?: return JellyfinRowFetch.Failed("Jellyfin is not connected.")
            return when (val result = fetch(catalog)) {
                is dev.infusion.source.jellyfin.client.ApiResult.Ok ->
                    JellyfinRowFetch.Ok(result.value.items.map { item -> item.toTabItem(keyPrefix) })
                is dev.infusion.source.jellyfin.client.ApiResult.Err ->
                    JellyfinRowFetch.Failed(result.failure.message)
            }
        }

        override suspend fun continueWatching(): JellyfinRowFetch = row(
            fetch = { it.resumeItems() },
            keyPrefix = "resume",
        )

        override suspend fun nextUp(): JellyfinRowFetch = row(
            fetch = { it.nextUp() },
            keyPrefix = "next-up",
        )

        override suspend fun recentlyAddedMovies(): JellyfinRowFetch = row(
            fetch = { it.latestItems(includeItemTypes = listOf("Movie")) },
            keyPrefix = "recent-movies",
        )

        override suspend fun recentlyAddedShows(): JellyfinRowFetch = row(
            fetch = { it.latestItems(includeItemTypes = listOf("Series")) },
            keyPrefix = "recent-shows",
        )

        override suspend fun recentlyAddedMusic(): JellyfinRowFetch = row(
            fetch = { it.latestItems(includeItemTypes = listOf("Audio", "MusicAlbum")) },
            keyPrefix = "recent-music",
        )

        override suspend fun myMedia(): JellyfinRowFetch = row(
            fetch = { it.libraryViews() },
            keyPrefix = "media",
        )
    }

    private fun dev.infusion.source.jellyfin.catalog.JellyfinRowItem.toTabItem(
        keyPrefix: String,
    ): JellyfinTabRowItem = JellyfinTabRowItem(
        key = "$keyPrefix:$nativeItemId",
        nativeItemId = nativeItemId,
        titleId = titleId,
        displayTitle = displayTitle,
        kind = kind,
        year = year,
        isFolder = isFolder,
        collectionType = collectionType,
        childCount = childCount,
        seriesTitleId = seriesTitleId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        playedPercentage = playedPercentage,
        resumePositionTicks = resumePositionTicks,
        runtimeTicks = runtimeTicks,
        seriesName = seriesName,
        airDate = airDate,
        lastPlayedDate = lastPlayedDate,
        canonicalTitleId = canonicalTitleId,
        canonicalSeriesTitleId = canonicalSeriesTitleId,

        albumName = albumName,
        albumArtistName = albumArtistName,
        parentNativeId = parentNativeId,

        nativeType = nativeType,
        albumArtistNativeId = albumArtistId,
        imageTypes = imageTypes,
        playlistItemId = playlistItemId,
        unavailable = unavailable,
    )

    fun addonSearchStateHolder(scope: CoroutineScope): dev.infusion.feature.vertical.search.AddonSearchStateHolder {
        return dev.infusion.feature.vertical.search.AddonSearchStateHolder(
            port = addonPorts.searchPort(
                searchService,
                canonicalSearchDiscovery,
                addonGroupDestinations,

                peopleSearchDiscovery,
            ),
            scope = scope,

            historyStore = searchHistoryStore,
        )
    }

    private val peopleSearchDiscovery: dev.infusion.source.aggregation.search.PeopleSearchDiscovery by lazy {
        dev.infusion.source.aggregation.search.PeopleSearchDiscoveryService(
            provider = metadataProvider,
            locale = metadataLocale,
        )
    }

    private val searchHistoryStore: dev.infusion.core.domain.search.SearchHistoryStore by lazy {
        dev.infusion.core.database.search.SharedPreferencesSearchHistoryStore(context)
    }

    private val addonGroupDestinations: dev.infusion.source.aggregation.search.AddonGroupDestinations by lazy {
        dev.infusion.source.aggregation.search.CanonicalGroupDestinationResolver(
            resolver = mediaIdentityResolver,
            locale = metadataLocale,
        )
    }

    private val canonicalSearchDiscovery: dev.infusion.source.aggregation.search.CanonicalSearchDiscovery by lazy {
        dev.infusion.source.aggregation.search.CanonicalSearchDiscoveryService(
            provider = metadataProvider,
            resolver = mediaIdentityResolver,
            locale = metadataLocale,
        )
    }

    fun addonManagementStateHolder(scope: CoroutineScope): dev.infusion.feature.vertical.addon.AddonManagementStateHolder {
        return dev.infusion.feature.vertical.addon.AddonManagementStateHolder(
            port = addonPorts.managementPort(),
            scope = scope,
        )
    }

    fun sourcePickerStateHolder(
        titleId: dev.infusion.core.model.TitleId,
        editionId: dev.infusion.core.model.EditionId?,
        unitKey: String = movieUnitKey(titleId),
    ): SourcePickerStateHolder {
        val addonResolver = addonCandidateResolver(unitKey)
        return SourcePickerStateHolder(
            titleId = titleId,
            editionId = editionId,
            resolver = dev.infusion.feature.vertical.sourcepicker.CompositeCandidateResolver(
                existing = resolver,
                addons = addonResolver,
            ),
            scope = appScope,
            providerStates = addonResolver.providerStates,

            onSelectionPersisted = { candidate ->
                candidate.editionId?.let { selectedEditionId ->
                    versionPreferences.setPreferredVersion(
                        profileScope.activeProfileId,
                        unitKey,
                        dev.infusion.core.domain.catalog.VersionPreference(
                            editionId = selectedEditionId,
                            sourceId = candidate.sourceId,
                        ),
                    )
                }
            },
        )
    }

    fun playbackStartStateHolder(
        titleId: dev.infusion.core.model.TitleId,
        editionId: dev.infusion.core.model.EditionId?,
        unitKey: String = movieUnitKey(titleId),
    ): dev.infusion.feature.vertical.sourcepicker.PlaybackStartStateHolder {
        val preferences = sourceAutoplaySettingsStateHolder.preferences.value
        val addonResolver = addonCandidateResolver(unitKey)
        return dev.infusion.feature.vertical.sourcepicker.PlaybackStartStateHolder(
            titleId = titleId,
            editionId = editionId,
            resolver = dev.infusion.feature.vertical.sourcepicker.CompositeCandidateResolver(
                existing = resolver,
                addons = addonResolver,
            ),
            plan = dev.infusion.feature.vertical.sourcepicker.AutoplaySelection(
                policy = preferences.policy,
                preferJellyfin = preferences.preferJellyfin,
                jellyfinSourceId = jellyfinSource?.sourceId?.value?.toString(),
            ),
            scope = appScope,
        )
    }

    private fun addonCandidateResolver(
        unitKey: String,
    ): dev.infusion.source.aggregation.resolve.AddonCandidateResolver {
        val liveRegistry = dev.infusion.source.addon.shells.LiveProfileAddonRegistry(
            base = addonRegistry,
            viewerProvider = { addonPorts.viewerProfileSnapshot() },
            primaryProvider = { addonPorts.primaryProfileSnapshot() },
        )
        val addonResolver = dev.infusion.source.aggregation.resolve.AddonCandidateResolver(
            registry = liveRegistry,
            availabilityStore = addonAvailabilityStore,
            streamRepository = addonResources.stream,
            lookup = addonLookup,

            viewer = placeholderProfile(),
            primary = placeholderProfile(),

            episodeAddress = dev.infusion.core.domain.catalog.parseEpisodeUnitKey(unitKey)?.let {
                dev.infusion.source.aggregation.resolve.EpisodeAddress(
                    seasonNumber = it.seasonNumber,
                    episodeNumber = it.episodeNumber,
                    seriesTitleId = it.seriesTitleId,
                )
            },
        )

        appScope.launch {
            addonResolver.lastObservation.collect { obs ->
                if (obs != null) streamLimitObservationJournal.record(obs)
            }
        }
        return addonResolver
    }

    fun createPlayerEngine(): PlaybackEngine {
        jellyfinSource?.let { graph ->
            originRoutingRegistry.registerSeed(
                sourceId = graph.sourceId.value.toString(),
                client = graph.source.client.hardenedTransport,
                origins = listOf(graph.source.config.origin),
            )
        }

        for (addonId in addonManager.installedAddonIds()) {
            val runtime = addonManager.runtimeFor(addonId) ?: continue
            if (!runtime.installed.enabled) continue
            originRoutingRegistry.registerSeed(
                sourceId = addonId,
                client = runtime.hardenedTransport,
                origins = listOf(runtime.installed.manifestUrl),
            )
        }

        return dev.infusion.core.playback.media3.HardenedPlaybackEngineFactory.create(
            context = context,
            transport = {
                dev.infusion.source.addon.shells.OriginRoutingHardenedClient(
                    registry = originRoutingRegistry,
                )
            },
            sensitiveHeaders = JELLYFIN_SENSITIVE_HEADERS,
            dolbyVisionProfile7Fallback = dolbyVisionProfile7FallbackProvider,
        )
    }

    private fun registerAddonMediaHosts(
        candidate: dev.infusion.core.model.PlayableCandidate,
        attempt: String,
    ): AttemptRouting {
        val addonId = candidate.sourceId.value.toString()
        val runtime = addonManager.runtimeFor(addonId)
            ?: throw IllegalArgumentException("add-on candidate source not wired: $addonId")
        if (!runtime.installed.enabled) {
            throw IllegalArgumentException("add-on candidate source disabled: $addonId")
        }
        val client = runtime.hardenedTransport
        val origins = LinkedHashSet<dev.infusion.core.network.CanonicalUrl>()
        try {
            origins += dev.infusion.core.network.UrlParser.parse(candidate.transport.value)
            candidate.embeddedSubtitles.forEach { sub ->
                origins += dev.infusion.core.network.UrlParser.parse(sub.url)
            }
        } catch (e: dev.infusion.core.network.NetworkPolicyException) {
            throw IllegalArgumentException("add-on candidate origin rejected by policy: ${e.reason.name}")
        }
        originRoutingRegistry.registerForAttempt(
            sourceId = addonId,
            attempt = attempt,
            client = client,
            origins = origins,
        )
        return AttemptRouting(originRoutingRegistry, addonId, attempt)
    }

    private class AttemptRouting(
        private val registry: dev.infusion.source.addon.shells.OriginRoutingRegistry,
        private val sourceId: String,
        private val attempt: String,
    ) : AutoCloseable {
        override fun close() {
            registry.retireAttempt(sourceId, attempt)
        }
    }

    fun newWatchLineage(): dev.infusion.source.aggregation.select.FallbackCoordinator =
        dev.infusion.source.aggregation.select.FallbackCoordinator(
            ledger = RoomWatchEventLedger(db.watchEventDao(), profileScope, titleSupersession),
            handoffStore = candidateHandoff,
            contextStore = RoomPlaybackSessionContextStore(db.playSessionContextDao()),

            sourceRegistry = dev.infusion.core.database.store.RoomPlaybackSourceRegistry(
                sourceDao = db.sourceDao(),
            ),
        )

    fun close() {
        if (closed.get()) return
        closed.set(true)

        activeAttemptRoute?.close()
        activeAttemptRoute = null
        retireAllRoutingOwnership()

        if (updateControllerDelegate.isInitialized()) {
            runCatching { updateControllerDelegate.value.close() }
        }

        jellyfinSourceRecord?.let { (graph, _) ->
            runCatching {
                graph.watchConnector.stopDraining()
                graph.watchConnector.stopPinging()
                graph.close()
            }
        }

        appScope.cancel()
        phoneSetupScope.cancel()

        runCatching { addonInfrastructure.close() }

        kotlinx.coroutines.runBlocking(drainDispatcher) {
            appScope.coroutineContext[Job]?.cancelAndJoin()
            phoneSetupScope.coroutineContext[Job]?.cancelAndJoin()
        }
        drainExecutor.shutdown()

        if (ownsDatabase) {
            runCatching { db.close() }
        }
    }

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    private val ownsDatabase: Boolean = database == null

    private val drainExecutor: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "app-container-drain").apply { isDaemon = true }
        }
    }

    private val drainDispatcher: kotlinx.coroutines.CoroutineDispatcher by lazy {
        drainExecutor.asCoroutineDispatcher()
    }

    private val originRoutingRegistry = dev.infusion.source.addon.shells.OriginRoutingRegistry()

    private var activeAttemptRoute: AutoCloseable? = null

    private fun retireAllRoutingOwnership() {
        for (addonId in addonManager.installedAddonIds()) {
            originRoutingRegistry.retireSource(addonId)
        }
        jellyfinSourceRecord?.first?.let { graph ->
            originRoutingRegistry.retireSource(graph.sourceId.value.toString())
        }
    }

    val originRoutingRegistryForTest: dev.infusion.source.addon.shells.OriginRoutingRegistry
        get() = originRoutingRegistry

    private val libraryRefreshReconcilers:
        List<dev.infusion.core.domain.refresh.LibraryRefreshReconciler> by lazy {
        listOf(
            dev.infusion.tv.refresh.JellyfinRefreshReconciler(
                configured = { jellyfinSource != null },

                refreshNativeItems = { ids -> jellyfinSource?.catalog?.refreshNativeItems(ids) ?: 0 },
                removeNativeItems = { ids -> jellyfinSource?.catalog?.removeNativeItems(ids) ?: 0 },
                mirrorRecentItems = { jellyfinSource?.catalog?.mirrorRecentItems() ?: 0 },
                refreshHome = { jellyfinSource?.catalog?.refreshHome() },

                refreshMappedNativeId = { titleId ->
                    val canonical = runCatching {
                        dev.infusion.core.model.TitleId.fromString(titleId)
                    }.getOrNull()
                    val nativeId = canonical?.let {
                        runCatching { jellyfinSource?.catalog?.nativeItemIdFor(it) }.getOrNull()
                    }
                    if (nativeId != null) {
                        jellyfinSource?.catalog?.refreshNativeItems(listOf(nativeId)) ?: 0
                    } else {
                        0
                    }
                },

                isTargetAvailable = { target ->
                    val shared = cardAvailabilityState.value
                    when (target.kind) {
                        dev.infusion.core.domain.refresh.ProvenImportKind.MOVIE ->
                            shared.isUnitProven(target.titleId)
                        dev.infusion.core.domain.refresh.ProvenImportKind.EPISODE ->
                            shared.isEpisodeProven(target.titleId, target.seasonNumber, target.episodeNumber)
                        dev.infusion.core.domain.refresh.ProvenImportKind.SERIES ->
                            shared.isSeriesProven(target.titleId)

                        dev.infusion.core.domain.refresh.ProvenImportKind.SEASON -> false
                    }
                },
                onDiagnostic = { reason -> android.util.Log.i("live-refresh", reason) },
            ),
            dev.infusion.tv.refresh.AcquisitionRefreshReconciler(
                acquisition = { acquisitionCoordinator },
                configuredServiceIds = {
                    listOf(
                        dev.infusion.core.domain.acquisition.AcquisitionService.RADARR,
                        dev.infusion.core.domain.acquisition.AcquisitionService.SONARR,
                    ).filter { service ->
                        runCatching { acquisitionConnectionStore.connection(service.id) }.getOrNull() != null
                    }.map { it.id }.toSet()
                },

                hasNonTerminalWork = { serviceId -> acquisitionCoordinator.hasNonTerminal(serviceId) },
                onDiagnostic = { reason -> android.util.Log.i("live-refresh", reason) },
            ),
        )
    }

    val libraryRefreshCoordinator: dev.infusion.tv.refresh.LibraryRefreshCoordinator by lazy {
        dev.infusion.tv.refresh.LibraryRefreshCoordinator(
            scope = appScope,
            feedsProvider = { buildServiceFeeds() },
            reconcilers = libraryRefreshReconcilers,
            onDiagnostic = { reason -> android.util.Log.i("live-refresh", reason) },
        )
    }

    private fun buildServiceFeeds(): List<dev.infusion.core.domain.refresh.ServiceFeed> {
        val feeds = mutableListOf<dev.infusion.core.domain.refresh.ServiceFeed>()
        jellyfinSource?.let { graph ->
            val instance = graph.canonicalSource.endpoint ?: graph.canonicalSource.sourceId.value.toString()
            feeds += dev.infusion.core.domain.refresh.ServiceFeed(
                serviceId = dev.infusion.source.jellyfin.events.JellyfinLibraryChangeFeed.SERVICE_ID,
                serviceInstance = instance,
                feed = graph.libraryChangeFeed(
                    onHint = { hint ->

                        android.util.Log.i(
                            "live-refresh-feed",
                            "service=${dev.infusion.source.jellyfin.events.JellyfinLibraryChangeFeed.SERVICE_ID} " +
                                "hint changed=${hint.changedNativeIds.size} " +
                                "removed=${hint.removedNativeIds.size} scope=${hint.scopeOnly}",
                        )
                        libraryRefreshCoordinator.onHint(hint)
                    },
                    onStatus = { status ->
                        libraryRefreshCoordinator.onFeedStatus(
                            dev.infusion.source.jellyfin.events.JellyfinLibraryChangeFeed.SERVICE_ID,
                            instance,
                            status,
                        )
                    },
                ),
            )
        }
        for (service in listOf(
            dev.infusion.core.domain.acquisition.AcquisitionService.RADARR,
            dev.infusion.core.domain.acquisition.AcquisitionService.SONARR,
        )) {
            val stored = runCatching { acquisitionConnectionStore.connection(service.id) }.getOrNull()
                ?: continue
            val apiKey = runCatching { acquisitionConnectionStore.apiKey(service.id) }.getOrNull()
                ?: continue
            val instance = runCatching {
                dev.infusion.core.network.UrlParser.parse(stored.connection.url).origin
            }.getOrNull() ?: continue
            val feed = runCatching {
                dev.infusion.source.acquisition.events.signalRLibraryChangeFeed(
                    connection = stored.connection,
                    apiKey = apiKey,
                    serviceInstance = instance,
                    parentScope = appScope,
                    onHint = { hint ->

                        android.util.Log.i(
                            "live-refresh-feed",
                            "service=${service.id} hint changed=${hint.changedNativeIds.size} " +
                                "removed=${hint.removedNativeIds.size} scope=${hint.scopeOnly}",
                        )
                        libraryRefreshCoordinator.onHint(hint)
                    },
                    onStatus = { status ->
                        android.util.Log.i("live-refresh-feed", "service=${service.id} status=${status.name}")
                        libraryRefreshCoordinator.onFeedStatus(service.id, instance, status)
                    },
                    onDiagnostic = { reason ->

                        android.util.Log.i("live-refresh-feed", "service=${service.id} diagnostic=$reason")
                    },
                ).getOrNull()
            }.getOrNull() ?: continue
            feeds += dev.infusion.core.domain.refresh.ServiceFeed(service.id, instance, feed)
        }
        return feeds
    }

    private val refreshConfigurationWired = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun wireRefreshConfigurationOnce() {
        if (!refreshConfigurationWired.compareAndSet(false, true)) return
        appScope.launch {
            runCatching {
                profileScope.activeProfileFlow.collect { libraryRefreshCoordinator.onConfigurationChanged() }
            }
        }
        appScope.launch {
            runCatching {
                acquisitionConnectionStore.connectionChanges()
                    .collect { libraryRefreshCoordinator.onConfigurationChanged() }
            }
        }

        appScope.launch {
            runCatching {
                trackingCredentialStore.connectionChanges()
                    .collect { personalRatingsCoordinator.onTrackerConnectionChanged() }
            }
        }

        appScope.launch {
            runCatching {
                profileScope.activeProfileFlow.collect { personalRatingsCoordinator.reloadForActiveProfile() }
            }
        }
    }

    private companion object {

        const val WATCHED_HISTORY_TAG = "InfusionWatchedHistory"

        val JELLYFIN_SENSITIVE_HEADERS = setOf("x-emby-token", "x-emby-authorization")

        const val DEEP_LINK_BUFFER_CAPACITY = 64

        const val COLLECTION_PAGE_HOLDER_LIMIT = 4

        const val UP_NEXT_RETENTION_LIMIT = 512
        const val UP_NEXT_RETENTION_INITIAL_CAPACITY = 64

        private fun placeholderProfile(): dev.infusion.core.model.profile.Profile =
            dev.infusion.core.model.profile.Profile(
                profileId = dev.infusion.core.domain.profile.ProfilePolicy.DEFAULT_PROFILE_ID,
                name = "Primary",
                isDefault = true,
                profileIndex = 1,
                usesPrimarySources = true,
                pinProtected = false,
                createdAtMs = 0L,
            )
    }
}

data class EpisodePlayFromBeginningRequest(
    val titleId: dev.infusion.core.model.TitleId,
    val unitKey: String,
)

data class EpisodeBrowseShowRequest(
    val seriesTitleId: dev.infusion.core.model.TitleId,
)