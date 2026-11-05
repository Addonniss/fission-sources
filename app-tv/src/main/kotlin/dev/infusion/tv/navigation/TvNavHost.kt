package dev.infusion.tv.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.Text
import dev.infusion.core.model.CandidateId
import dev.infusion.core.model.EditionId
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.core.model.metadata.PersonId
import dev.infusion.core.model.watch.WatchKey
import dev.infusion.feature.vertical.home.HomeCwOrigin
import dev.infusion.core.domain.music.MusicQueueContext
import dev.infusion.feature.vertical.music.musicQueueContext
import dev.infusion.feature.vertical.music.musicLibraryTarget
import dev.infusion.tv.ui.screens.TvNowPlayingScreen
import dev.infusion.feature.vertical.collections.entityCollectionPageId
import dev.infusion.feature.vertical.media.MediaCardUi
import dev.infusion.feature.vertical.media.TrailerUi
import dev.infusion.feature.vertical.media.collectionFocusKey
import dev.infusion.feature.vertical.media.companyFocusKey
import dev.infusion.feature.vertical.media.episodeFocusKey
import dev.infusion.feature.vertical.player.PlaybackStartMode
import dev.infusion.feature.vertical.model.UpNextAvailability
import dev.infusion.feature.vertical.setup.JellyfinConnectedUser
import dev.infusion.feature.vertical.setup.JellyfinConnectOutcome
import dev.infusion.feature.vertical.setup.JellyfinConnectionActions
import dev.infusion.feature.vertical.setup.JellyfinConnectionMethod
import dev.infusion.feature.vertical.setup.JellyfinConnectionStateHolder
import dev.infusion.feature.vertical.setup.JellyfinSetupStateHolder
import dev.infusion.feature.vertical.setup.JellyfinSetupStatus
import dev.infusion.feature.vertical.setup.JellyfinUserChoice
import dev.infusion.feature.vertical.setup.JellyfinUsersOutcome
import dev.infusion.feature.vertical.setup.SourceConnectOutcome
import dev.infusion.feature.vertical.sourcepicker.SourcePickerStateHolder
import dev.infusion.tv.di.AppContainer
import dev.infusion.tv.playback.DvUiCompensationExperiment
import dev.infusion.tv.playback.describe
import dev.infusion.tv.playback.describeForDiagnostics
import dev.infusion.core.model.TitleKind
import dev.infusion.tv.ui.screens.TopLevelDestination
import dev.infusion.tv.ui.screens.TvCollectionPageScreen
import dev.infusion.tv.ui.screens.TvCollectionScreen
import dev.infusion.tv.ui.screens.TvDetailsScreen
import dev.infusion.tv.ui.screens.TvDiagnosticsScreen
import dev.infusion.tv.ui.screens.TvEpisodeDetailsScreen
import dev.infusion.tv.ui.screens.HomeHeroMetadataFactory
import dev.infusion.tv.ui.screens.TvHomeScreen
import dev.infusion.tv.ui.screens.TvWatchlistScreen
import dev.infusion.tv.ui.screens.TvJellyfinScreen
import dev.infusion.tv.ui.screens.TvJellyfinLibraryScreen
import dev.infusion.tv.ui.screens.TvLibraryScreen
import dev.infusion.tv.ui.screens.TvPhoneSetupScreen
import dev.infusion.tv.ui.screens.TvPersonScreen
import dev.infusion.tv.ui.screens.personCreditFocusKey
import dev.infusion.tv.ui.screens.TvPlayerScreen
import dev.infusion.tv.ui.screens.TvPlaybackPreparationScreen
import dev.infusion.tv.ui.screens.TrailerActionKey
import dev.infusion.tv.ui.screens.TvTopNavModel
import dev.infusion.tv.ui.screens.TvTrailerScreen
import dev.infusion.tv.ui.screens.SettingsRailAddons
import dev.infusion.tv.ui.screens.SettingsRailDiagnostics
import dev.infusion.tv.ui.screens.SettingsRailGeneral
import dev.infusion.tv.ui.screens.SettingsRailPairing
import dev.infusion.tv.ui.screens.TvSeriesDetailsScreen
import dev.infusion.tv.ui.screens.TvSettingsScreen
import dev.infusion.tv.ui.screens.TvSourcePickerScreen
import dev.infusion.tv.ui.shell.ColdStartSplash
import dev.infusion.tv.ui.shell.LocalColdStartSplashController
import dev.infusion.tv.ui.shell.blocksStartupInput
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun disposeTvPlayerDestination(player: dev.infusion.feature.vertical.player.PlayerStateHolder) {
    player.stopAndRelease()
}

internal fun shouldClosePlaybackAtEndOfMedia(
    phase: dev.infusion.core.playback.api.PlaybackState,
    upNextVisible: Boolean,
    alreadyClosed: Boolean,
): Boolean = phase == dev.infusion.core.playback.api.PlaybackState.ENDED &&
    !upNextVisible &&
    !alreadyClosed

private fun openJellyfinTabItem(
    container: AppContainer,
    navController: NavHostController,
    item: dev.infusion.feature.vertical.jellyfin.JellyfinTabRowItem,

    musicLibrary: dev.infusion.feature.vertical.jellyfin.JellyfinTabRowItem? = null,
) {

    when (item.musicLibraryTarget()) {
        dev.infusion.feature.vertical.music.MusicLibraryTarget.TRACK -> {

            val sourceId = container.jellyfinSource?.sourceId
            val albumId = item.parentNativeId
            if (sourceId != null && albumId != null) {
                container.musicQueueStateHolder.startAtTrack(
                    dev.infusion.core.domain.music.MusicQueueContext.Album(
                        sourceId = sourceId,
                        albumNativeId = albumId,
                        label = item.albumName ?: item.displayTitle,
                    ),
                    trackNativeId = item.nativeItemId,
                )
                if (navController.currentBackStackEntry?.destination?.route != TvRoutes.NOW_PLAYING) {
                    navController.navigate(TvRoutes.NOW_PLAYING) { launchSingleTop = true }
                }
            }
            return
        }
        dev.infusion.feature.vertical.music.MusicLibraryTarget.ALBUM,
        dev.infusion.feature.vertical.music.MusicLibraryTarget.ARTIST,
        dev.infusion.feature.vertical.music.MusicLibraryTarget.PLAYLIST,
        -> {

            val library = musicLibrary ?: return
            navController.navigate(
                TvRoutes.libraryGrid(
                    libraryId = library.nativeItemId,
                    libraryTitle = library.displayTitle,
                    collectionType = library.collectionType ?: "music",
                    musicTarget = item.musicLibraryTarget(),
                    musicId = item.nativeItemId,
                    musicName = item.displayTitle,
                ),
            )
            return
        }
        null -> Unit
    }
    when {
        item.kind == dev.infusion.core.model.TitleKind.SERIES ->

            item.canonicalOrDeterministicTitleId?.let { navController.navigate(TvRoutes.seriesDetails(it)) }

        item.isFolder && item.collectionType != null ->
            navController.navigate(
                TvRoutes.libraryGrid(item.nativeItemId, item.displayTitle, item.collectionType),
            )
        item.isFolder && item.kind != dev.infusion.core.model.TitleKind.EPISODE ->
            navController.navigate(TvRoutes.library(item.nativeItemId))
        item.kind == dev.infusion.core.model.TitleKind.EPISODE &&
            item.seriesTitleId != null &&
            item.seasonNumber != null &&
            item.episodeNumber != null &&
            item.titleId != null -> {

            val seriesId = item.canonicalOrDeterministicSeriesTitleId
            val seasonNumber = item.seasonNumber
            val episodeNumber = item.episodeNumber
            val episodeTitleId = item.canonicalOrDeterministicTitleId
            if (seriesId != null && seasonNumber != null &&
                episodeNumber != null && episodeTitleId != null
            ) {

                startTitlePlayback(
                    container = container,
                    navController = navController,
                    titleId = episodeTitleId,
                    editionId = null,
                    unitKey = dev.infusion.core.domain.catalog.episodeUnitKey(
                        seriesId,
                        seasonNumber,
                        episodeNumber,
                    ),
                    startMode = PlaybackStartMode.RESUME,
                    titleText = item.preview.displayTitle,
                )
            }
        }

        else -> {

            item.canonicalOrDeterministicTitleId?.let { titleId ->
                navController.navigate(
                    TvRoutes.details(
                        titleId,

                    resumeHintMs = item.resumePositionTicks?.div(10_000L)?.takeIf { ms -> ms > 0L },
                    ),
                )
            }
        }
    }
}

private fun musicLibraryOf(
    state: dev.infusion.feature.vertical.jellyfin.JellyfinTabUiState,
): dev.infusion.feature.vertical.jellyfin.JellyfinTabRowItem? =
    state.rows
        .firstOrNull { row -> row.kind == dev.infusion.feature.vertical.jellyfin.JellyfinRowKind.MY_MEDIA }
        ?.let { row -> row.state as? dev.infusion.feature.vertical.jellyfin.JellyfinRowState.Loaded }
        ?.items
        ?.firstOrNull { item -> item.collectionType?.equals("music", ignoreCase = true) == true }

private fun openUpNextCard(
    container: AppContainer,
    navController: NavHostController,
    card: dev.infusion.feature.vertical.home.UpNextRowCard,
) {
    val episodeTitleId = card.episodeTitleId

    startTitlePlayback(
        container = container,
        navController = navController,
        titleId = episodeTitleId ?: card.seriesTitleId,
        editionId = null,
        unitKey = dev.infusion.core.domain.catalog.episodeUnitKey(
            card.seriesTitleId,
            card.seasonNumber,
            card.episodeNumber,
        ),
        startMode = PlaybackStartMode.RESUME,
        titleText = card.episodeTitle ?: card.preview.displayTitle,
    )
}

private fun openLocalCwCard(
    container: AppContainer,
    navController: NavHostController,
    origin: dev.infusion.feature.vertical.home.HomeCwOrigin.Local,
) {
    if (origin.state.watchKey.isEpisode) {
        val seriesId = origin.seriesTitleId
        val seasonNumber = origin.seasonNumber
        val episodeNumber = origin.episodeNumber
        val episodeTitleId = origin.episodeTitleId
        if (seriesId != null && seasonNumber != null && episodeNumber != null) {

            startTitlePlayback(
                container = container,
                navController = navController,
                titleId = episodeTitleId ?: seriesId,
                editionId = null,
                unitKey = dev.infusion.core.domain.catalog.episodeUnitKey(seriesId, seasonNumber, episodeNumber),
                startMode = PlaybackStartMode.RESUME,
            )
        } else {
            origin.seriesTitleId?.let { navController.navigate(TvRoutes.seriesDetails(it)) }
        }
    } else {
        navController.navigate(TvRoutes.details(origin.titleId))
    }
}

object TvRoutes {
    const val HOME = "home"
    const val SETTINGS = "settings"

    const val WATCHLIST = "watchlist"

    const val JELLYFIN = "jellyfin"
    const val LIBRARY = "library/{libraryId}"

    const val LIBRARY_GRID =
        "libraryGrid/{libraryId}/{libraryTitle}/{collectionType}" +
            "?musicKind={musicKind}&musicId={musicId}&musicName={musicName}"

    const val NOW_PLAYING = "nowPlaying"
    const val PAIRING = "pairing"
    const val DISCOVER = "discover"
    const val SEARCH = "search"
    const val DIAGNOSTICS = "diagnostics"

    const val DETAILS = "details/{titleId}?resumeMs={resumeMs}"

    const val SOURCE_PICKER =
        "sourcePicker/{titleId}/{editionId}/{unitKey}/{startMode}?logo={logo}"

    const val PLAYER =
        "player/{titleId}/{editionId}/{candidateId}/{episodeKey}/{startMode}" +
            "?prepLogo={prepLogo}&prepBackdrop={prepBackdrop}&prepTitle={prepTitle}&prepProvider={prepProvider}" +
            "&prepFile={prepFile}"

    const val PLAYBACK_START =
        "playbackStart/{titleId}/{editionId}/{unitKey}/{startMode}" +
            "?logo={logo}&backdrop={backdrop}&titleText={titleText}"

    const val SERIES_DETAILS = "series/{titleId}"

    const val EPISODE_DETAILS =
        "episode/{seriesTitleId}/{seasonNumber}/{episodeNumber}/{episodeTitleId}?resumeMs={resumeMs}"

    const val PERSON = "person/{personAuthority}/{personId}"
    const val COLLECTION = "collection/{collectionId}/{currentTitleKey}"

    const val COLLECTION_PAGE = "collectionPage/{pageId}"

    const val TRAILER = "trailer/{titleId}/{site}/{key}"

    fun library(libraryId: String) = "library/${encodeNavSegment(libraryId)}"

    fun libraryGrid(
        libraryId: String,
        libraryTitle: String,
        collectionType: String?,
        musicTarget: dev.infusion.feature.vertical.music.MusicLibraryTarget? = null,
        musicId: String? = null,
        musicName: String? = null,
    ) = "libraryGrid/${encodeNavSegment(libraryId)}/${encodeNavSegment(libraryTitle)}/" +
        encodeNavSegment(collectionType?.takeIf { it.isNotBlank() } ?: ARG_NO_COLLECTION_TYPE) +
        "?musicKind=${musicTarget?.name ?: ARG_NO_MUSIC_TARGET}" +
        "&musicId=${musicId?.takeIf { it.isNotBlank() }?.let { encodeNavSegment(it) } ?: ARG_NO_MUSIC_TARGET}" +
        "&musicName=${musicName?.takeIf { it.isNotBlank() }?.let { encodeNavSegment(it) } ?: ARG_NO_MUSIC_TARGET}"

    const val ARG_NO_MUSIC_TARGET = "-"

    const val ARG_MUSIC_KIND = "musicKind"
    const val ARG_MUSIC_ID = "musicId"
    const val ARG_MUSIC_NAME = "musicName"

    const val ARG_NO_COLLECTION_TYPE = "-"

    const val ARG_SHOWS_LIBRARY_TITLE = "libraryTitle"

    const val ARG_LIBRARY_COLLECTION_TYPE = "collectionType"

    fun details(titleId: TitleId, resumeHintMs: Long? = null) =
        "details/${titleId.value}?resumeMs=${resumeHintMs?.takeIf { it > 0L } ?: ARG_RESUME_MS_NONE}"
    fun sourcePicker(
        titleId: TitleId,
        editionId: EditionId?,
        unitKey: String = movieUnitKey(titleId),
        startMode: PlaybackStartMode = PlaybackStartMode.RESUME,
        logoLocator: String? = null,
    ) = "sourcePicker/${titleId.value}/${editionId?.value ?: "none"}/${encodeNavSegment(unitKey)}/" +
        "${startMode.wireValue}?logo=${logoLocator?.let { encodeNavSegment(it) } ?: ARG_LOGO_NONE}"
    fun playbackStart(
        titleId: TitleId,
        editionId: EditionId?,
        unitKey: String = movieUnitKey(titleId),
        startMode: PlaybackStartMode = PlaybackStartMode.RESUME,
        logoLocator: String? = null,
        backdropLocator: String? = null,
        titleText: String? = null,
    ) = "playbackStart/${titleId.value}/${editionId?.value ?: "none"}/${encodeNavSegment(unitKey)}/" +
        "${startMode.wireValue}" +
        "?logo=${logoLocator?.let { encodeNavSegment(it) } ?: ARG_LOGO_NONE}" +
        "&backdrop=${backdropLocator?.let { encodeNavSegment(it) } ?: ARG_LOGO_NONE}" +
        "&titleText=${titleText?.let { encodeNavSegment(it) } ?: ARG_LOGO_NONE}"
    fun player(
        titleId: TitleId,
        editionId: EditionId?,
        candidateId: CandidateId,
        watchKey: WatchKey? = null,
        startMode: PlaybackStartMode = PlaybackStartMode.RESUME,
        prepLogoLocator: String? = null,
        prepBackdropLocator: String? = null,
        prepTitleText: String? = null,
        prepProviderLabel: String? = null,
        prepFileLabel: String? = null,
    ): String {
        val episode = watchKey?.let {
            if (it.isEpisode) "S:${it.seriesId!!.value}:${it.seasonNumber}:${it.episodeNumber}" else null
        } ?: "none"
        return "player/${titleId.value}/${editionId?.value ?: "none"}/${candidateId.value}/" +
            "${encodeNavSegment(episode)}/${startMode.wireValue}" +
            "?prepLogo=${prepLogoLocator?.let { encodeNavSegment(it) } ?: ARG_LOGO_NONE}" +
            "&prepBackdrop=${prepBackdropLocator?.let { encodeNavSegment(it) } ?: ARG_LOGO_NONE}" +
            "&prepTitle=${prepTitleText?.let { encodeNavSegment(it) } ?: ARG_LOGO_NONE}" +
            "&prepProvider=${prepProviderLabel?.let { encodeNavSegment(it) } ?: ARG_LOGO_NONE}" +
            "&prepFile=${prepFileLabel?.let { encodeNavSegment(it) } ?: ARG_LOGO_NONE}"
    }
    fun seriesDetails(titleId: TitleId) = "series/${titleId.value}"

    fun person(personId: PersonId) =
        "person/${encodeNavSegment(personId.authority)}/${encodeNavSegment(personId.value)}"

    fun collection(collectionId: Long, currentTitleKey: String? = null) =
        "collection/$collectionId/${currentTitleKey?.let(::encodeNavSegment) ?: "none"}"

    fun collectionPage(pageId: String) = "collectionPage/${encodeNavSegment(pageId)}"

    fun trailer(titleId: TitleId, site: String, key: String) =
        "trailer/${titleId.value}/${encodeNavSegment(site)}/${encodeNavSegment(key)}"

    fun episodeDetails(
        seriesTitleId: TitleId,
        seasonNumber: Int,
        episodeNumber: Int,
        episodeTitleId: TitleId,
        resumeHintMs: Long? = null,
    ) = "episode/${seriesTitleId.value}/$seasonNumber/$episodeNumber/${episodeTitleId.value}" +
        "?resumeMs=${resumeHintMs?.takeIf { it > 0L } ?: ARG_RESUME_MS_NONE}"

    fun movieUnitKey(titleId: TitleId): String = titleId.value.toString()

    fun encodeNavSegment(raw: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.toByteArray(Charsets.UTF_8))

    fun decodeNavSegment(encoded: String): String =
        String(java.util.Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)

    const val ARG_TITLE = "titleId"
    const val ARG_EDITION = "editionId"
    const val ARG_CANDIDATE = "candidateId"
    const val ARG_UNIT_KEY = "unitKey"
    const val ARG_EPISODE_KEY = "episodeKey"
    const val ARG_START_MODE = "startMode"
    const val ARG_PERSON_AUTHORITY = "personAuthority"
    const val ARG_PERSON_ID = "personId"
    const val ARG_COLLECTION_ID = "collectionId"
    const val ARG_COLLECTION_CURRENT = "currentTitleKey"
    const val ARG_COLLECTION_PAGE = "pageId"
    const val ARG_SITE = "site"
    const val ARG_KEY = "key"
    const val ARG_LIBRARY = "libraryId"
    const val ARG_SERIES_TITLE = "seriesTitleId"
    const val ARG_SEASON_NUMBER = "seasonNumber"
    const val ARG_EPISODE_NUMBER = "episodeNumber"
    const val ARG_EPISODE_TITLE = "episodeTitleId"

    const val ARG_RESUME_MS = "resumeMs"
    const val ARG_RESUME_MS_NONE = -1L

    const val ARG_LOGO = "logo"
    const val ARG_LOGO_NONE = "none"

    const val ARG_BACKDROP = "backdrop"
    const val ARG_TITLE_TEXT = "titleText"

    const val ARG_PREP_LOGO = "prepLogo"
    const val ARG_PREP_BACKDROP = "prepBackdrop"
    const val ARG_PREP_TITLE = "prepTitle"
    const val ARG_PREP_PROVIDER = "prepProvider"

    const val ARG_PREP_FILE = "prepFile"
}

internal fun topLevelPageOfRoute(route: String?): TopLevelDestination? = when (route) {
    TvRoutes.HOME -> TopLevelDestination.HOME
    TvRoutes.JELLYFIN -> TopLevelDestination.JELLYFIN
    TvRoutes.WATCHLIST -> TopLevelDestination.WATCHLIST
    TvRoutes.DISCOVER -> TopLevelDestination.DISCOVER
    TvRoutes.SEARCH -> TopLevelDestination.SEARCH
    TvRoutes.SETTINGS -> TopLevelDestination.SETTINGS
    else -> null
}

internal fun childDepartureOf(previousRoute: String?, currentRoute: String?): TopLevelDestination? {
    val departed = topLevelPageOfRoute(previousRoute) ?: return null
    return if (topLevelPageOfRoute(currentRoute) == null) departed else null
}

internal fun startTitlePlayback(
    container: AppContainer,
    navController: NavHostController,
    titleId: TitleId,
    editionId: EditionId?,
    unitKey: String,
    startMode: PlaybackStartMode,
    logoLocator: String? = null,
    backdropLocator: String? = null,
    titleText: String? = null,

    popUpToRoute: String? = null,
    popUpToInclusive: Boolean = false,
) {
    val navigate: (String) -> Unit = { route ->
        navController.navigate(route) {
            if (popUpToRoute != null) {
                popUpTo(popUpToRoute) { inclusive = popUpToInclusive }
            }
        }
    }
    if (container.sourceAutoplaySettingsStateHolder.preferences.value.policy.enabled) {
        navigate(
            TvRoutes.playbackStart(
                titleId = titleId,
                editionId = editionId,
                unitKey = unitKey,
                startMode = startMode,
                logoLocator = logoLocator,
                backdropLocator = backdropLocator,
                titleText = titleText,
            ),
        )
    } else {
        navigate(
            TvRoutes.sourcePicker(
                titleId = titleId,
                editionId = editionId,
                unitKey = unitKey,
                startMode = startMode,
                logoLocator = logoLocator,
            ),
        )
    }
}

private fun decodeOptionalSegment(raw: String?): String? =
    raw?.takeIf { it != TvRoutes.ARG_LOGO_NONE }
        ?.let { runCatching { TvRoutes.decodeNavSegment(it) }.getOrNull() }

@Composable
private fun rememberSeriesPreparationArtwork(
    container: AppContainer,
    seriesId: TitleId?,
    enabled: Boolean,
): dev.infusion.feature.vertical.media.CanonicalHeroUi? {
    if (!enabled || seriesId == null) return null
    val scope = rememberCoroutineScope()
    val holder = remember(seriesId) {
        container.canonicalDetailsStateHolder(
            titleId = seriesId,
            mediaType = dev.infusion.core.model.metadata.MediaType.SHOW,
            holderScope = scope,
            loadSecondarySections = false,
        )
    }
    return holder.state.collectAsStateWithLifecycle().value.hero
}

@Composable
private fun rememberSourcesPosterLocator(
    container: AppContainer,
    titleId: TitleId,
    unitKey: String,
): String? {
    val episode = remember(unitKey) {
        dev.infusion.core.domain.catalog.parseEpisodeUnitKey(unitKey)
    }
    val artworkTitleId = episode?.seriesTitleId ?: titleId
    val mediaType = if (episode != null) MediaType.SHOW else MediaType.MOVIE
    val seam = remember(artworkTitleId, mediaType) {
        container.sourcesPosterSeamFor(artworkTitleId, mediaType)
    }
    var locator by remember(artworkTitleId, mediaType) { mutableStateOf<String?>(null) }
    LaunchedEffect(seam) { locator = seam() }
    return locator
}

@Composable
private fun rememberSourcesHeaderLogo(
    container: AppContainer,
    routeLogoLocator: String?,
    episode: dev.infusion.core.domain.catalog.EpisodeUnitCoordinate?,
): String? {
    val seriesId = if (routeLogoLocator == null) episode?.seriesTitleId else null
    val seam = remember(seriesId) { seriesId?.let { container.sourcesLogoSeamFor(it) } }
    var locator by remember(seriesId) { mutableStateOf<String?>(null) }
    LaunchedEffect(seam) { locator = seam?.invoke() }
    return locator
}

internal data class DetailsRestoration(
    val focusKey: String,
    val seasonNumber: Int? = null,
) : java.io.Serializable

private const val DETAILS_RESTORE_SLOT = "details:return"

internal fun rememberDetailsReturn(handle: androidx.lifecycle.SavedStateHandle, restoration: DetailsRestoration) {
    handle[DETAILS_RESTORE_SLOT] = restoration
}

internal fun consumeDetailsReturn(handle: androidx.lifecycle.SavedStateHandle): DetailsRestoration? =
    handle.remove(DETAILS_RESTORE_SLOT)

private const val PERSON_CREDIT_RESTORE_KEY = "person:credit-restore"

@Composable
fun TvNavHost(
    container: AppContainer,
    navController: NavHostController = rememberNavController(),
) {
    val ratingAppearance by container.ratingsAppearance.collectAsStateWithLifecycle()

    val artworkAppearance by container.appearanceStateHolder.preferences.collectAsStateWithLifecycle()
    val artworkSourceVariants = remember(
        artworkAppearance.tmdbBackdropQuality,
        artworkAppearance.tmdbLandscapeCardQuality,
    ) {
        container.artworkSourceVariantsFor(
            backdropQuality = artworkAppearance.tmdbBackdropQuality,
            landscapeCardQuality = artworkAppearance.tmdbLandscapeCardQuality,
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(
        dev.infusion.core.artworkimage.LocalArtworkLoader provides container.artworkLoader,
        dev.infusion.core.artworkimage.LocalArtworkSourceVariants provides artworkSourceVariants,

        dev.infusion.tv.ui.presentation.LocalRatingAppearance provides ratingAppearance,
    ) {
        TvNavContent(container, navController)
    }
}

@Composable
private fun TvNavContent(
    container: AppContainer,
    navController: NavHostController,
) {

    LaunchedEffect(container) {
        container.startupProfileRestore.restore()
    }

    LaunchedEffect(container) {
        container.startTrackingDelivery()
    }

    var gate by remember { mutableStateOf<AddonStartupGate>(AddonStartupGate.Loading) }
    LaunchedEffect(container) {
        gate = try {

            val state = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                container.ensureAddonsRestored()
            }
            when (state) {
                is dev.infusion.source.addon.repository.AddonRepositoryState.Ready -> AddonStartupGate.Ready
                is dev.infusion.source.addon.repository.AddonRepositoryState.Failed -> AddonStartupGate.Failed
                is dev.infusion.source.addon.repository.AddonRepositoryState.Restoring -> AddonStartupGate.Failed
            }
        } catch (e: Exception) {

            android.util.Log.w("TvNavHost", "add-on restore failed; add-ons unavailable", e)
            AddonStartupGate.Failed
        }
    }

    val coldStartSplash = LocalColdStartSplashController.current
    LaunchedEffect(gate, coldStartSplash) {
        when (gate) {
            AddonStartupGate.Loading -> Unit
            AddonStartupGate.Ready -> coldStartSplash?.onStartupReady()
            AddonStartupGate.Failed -> coldStartSplash?.onStartupTerminal()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { coldStartSplash?.blocksStartupInput() == true },
    ) {
    when (val current = gate) {

        AddonStartupGate.Loading -> Box(
            modifier = Modifier
                .fillMaxSize()
                .background(dev.infusion.tv.ui.theme.InfusionTvAppearance.OledBlack),
        )
        AddonStartupGate.Failed -> TvAddonsUnavailableSurface()
        AddonStartupGate.Ready -> {

            val addonsScope = rememberCoroutineScope()
            val addonsManagement = remember { container.addonManagementStateHolder(addonsScope) }

            val playbackRetention = remember { PlaybackRetentionRegistry() }
            androidx.compose.runtime.DisposableEffect(playbackRetention) {

                container.installVideoAttemptReleaser { playbackRetention.releaseRetained() }
                onDispose {
                    playbackRetention.releaseRetained()
                    container.installVideoAttemptReleaser { }
                }
            }

            val searchScope = rememberCoroutineScope()
            val searchHolder = remember { container.addonSearchStateHolder(searchScope) }

            val activeSearchProfileId by container.activeProfileFlow
                .collectAsStateWithLifecycle(initialValue = container.activeProfileId)
            LaunchedEffect(activeSearchProfileId) {
                searchHolder.onProfileChanged(activeSearchProfileId)
            }

            val topNavModel = remember { TvTopNavModel() }

            val jellyfinConnected by container.jellyfinConnected.collectAsStateWithLifecycle()

            val previewHandoff = remember { container.titlePreviewHandoff }

            LaunchedEffect(jellyfinConnected) {
                if (!jellyfinConnected) topNavModel.forget(TopLevelDestination.JELLYFIN)
            }

            LaunchedEffect(navController, topNavModel) {
                var previousRoute: String? = null
                navController.currentBackStackEntryFlow.collect { entry ->
                    val route = entry.destination.route
                    childDepartureOf(previousRoute, route)?.let(topNavModel::markChildDeparture)
                    previousRoute = route
                }
            }

            var settingsSection by rememberSaveable { mutableStateOf(SettingsRailGeneral) }

            val activity = androidx.activity.compose.LocalActivity.current

            val navigateTopLevel: (TopLevelDestination) -> Unit = { destination ->
                when (destination) {
                    TopLevelDestination.HOME -> navController.navigate(TvRoutes.HOME) {
                        popUpTo(TvRoutes.HOME) { inclusive = false }
                        launchSingleTop = true
                    }
                    TopLevelDestination.JELLYFIN -> if (jellyfinConnected) {
                        navController.navigate(TvRoutes.JELLYFIN) {
                            popUpTo(TvRoutes.HOME) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                    TopLevelDestination.DISCOVER -> navController.navigate(TvRoutes.DISCOVER) {
                        popUpTo(TvRoutes.HOME) { inclusive = false }
                        launchSingleTop = true
                    }
                    TopLevelDestination.SEARCH -> navController.navigate(TvRoutes.SEARCH) {
                        popUpTo(TvRoutes.HOME) { inclusive = false }
                        launchSingleTop = true
                    }
                    TopLevelDestination.WATCHLIST -> navController.navigate(TvRoutes.WATCHLIST) {
                        popUpTo(TvRoutes.HOME) { inclusive = false }
                        launchSingleTop = true
                    }
                    TopLevelDestination.SETTINGS -> navController.navigate(TvRoutes.SETTINGS) {
                        popUpTo(TvRoutes.HOME) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }

            val homeTileFocusRestoration = remember { mutableStateOf<String?>(null) }
            val collectionPageFocusRestoration = remember { mutableStateMapOf<String, String>() }

            LaunchedEffect(container) {
                container.addonDeepLinks.collect { url ->
                    settingsSection = SettingsRailAddons
                    if (navController.currentDestination?.route != TvRoutes.SETTINGS) {
                        navController.navigate(TvRoutes.SETTINGS) {
                            popUpTo(TvRoutes.HOME) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                    addonsManagement.startReviewFor(url)
                }
            }

            LaunchedEffect(container) {
                container.episodePlayFromBeginning.collect { req ->

                    startTitlePlayback(
                        container = container,
                        navController = navController,
                        titleId = req.titleId,
                        editionId = null,
                        unitKey = req.unitKey,
                        startMode = PlaybackStartMode.FROM_BEGINNING,
                    )
                }
            }

            LaunchedEffect(container) {
                container.episodeBrowseShow.collect { req ->
                    navController.navigate(TvRoutes.seriesDetails(req.seriesTitleId))
                }
            }

            LaunchedEffect(container) {
                container.openNowPlaying.collect { originTabKey ->
                    topNavModel.requestReturnFocus(originTabKey)
                    navController.navigate(TvRoutes.NOW_PLAYING) {
                        launchSingleTop = true
                    }
                }
            }
            NavHost(
                navController = navController,
                startDestination = TvRoutes.HOME,
            ) {
        composable(TvRoutes.HOME) {

            val coldStartSplash = LocalColdStartSplashController.current
            LaunchedEffect(coldStartSplash) {
                coldStartSplash?.onHomeSurfaceReady()
            }

            val homeTileRestore = remember {
                homeTileFocusRestoration.value.also { homeTileFocusRestoration.value = null }
            }

            val seam = remember { container.profileSwitchingSeam }
            val seamHome by seam.home.current.collectAsStateWithLifecycle()

            val homeTab = remember(seamHome) { container.retainedHomeTabState() }
            val homeCw = homeTab.continueWatching
            val homeCatalogRows = homeTab.catalogRows

            val homeUpNext = homeTab.upNext
            val upNextState by homeUpNext.state.collectAsStateWithLifecycle()

            val homePresentation = homeTab.presentation
            val presentation by homePresentation.state.collectAsStateWithLifecycle()

            val presentationRead by homePresentation.preferencesRead.collectAsStateWithLifecycle()

            LaunchedEffect(seamHome, jellyfinConnected) {
                homeCw.refresh(includeJellyfin = jellyfinConnected)
                homeCatalogRows.refresh()
                homeUpNext.refresh()
            }

            DisposableEffect(homeTab) {
                homeCw.onSurfacePresent()
                homeCatalogRows.onSurfacePresent()
                homeUpNext.onSurfacePresent()
                onDispose {
                    homeCw.onSurfaceAbsent()
                    homeCatalogRows.onSurfaceAbsent()
                    homeUpNext.onSurfaceAbsent()
                }
            }
            TvHomeScreen(
                stateHolder = homeCw,
                homeCatalogRows = homeCatalogRows,
                upNext = upNextState,
                jellyfinConnected = jellyfinConnected,
                model = topNavModel,
                onNavigate = navigateTopLevel,
                presentation = presentation,

                spotlightPreferenceRead = presentationRead,

                spotlightState = homeTab.spotlight,

                onContinueWatchingRowWindow = { shelfKey, materialized ->
                    homeCw.reportPresentationWindow(shelfKey, materialized)
                },
                onUpNextRowWindow = { materialized ->
                    homeUpNext.reportPresentationWindow(materialized)
                },

                onSpotlightSourceUnavailable = {
                    homePresentation.resolveUnavailableSpotlightSource()
                },

                heroMetadataFactory = HomeHeroMetadataFactory { titleId, mediaType, holderScope, hint ->
                    container.canonicalDetailsStateHolder(
                        titleId,
                        mediaType,
                        holderScope,
                        identityHint = hint,
                        loadSecondarySections = false,
                    )
                },

                heroPresentationCache = container.heroPresentationCache,
                onOpenCard = { card ->

                    card.preview.titleId?.let { previewHandoff.put(it, card.preview) }
                    when (val origin = card.origin) {

                        is HomeCwOrigin.Jellyfin -> openJellyfinTabItem(container, navController, origin.item)

                        is HomeCwOrigin.Local -> openLocalCwCard(container, navController, origin)
                    }
                },
                onOpenAddonItem = { item ->

                    item.titleId?.let { titleId ->

                        previewHandoff.put(titleId, item.preview)
                        if (item.kind == dev.infusion.core.model.TitleKind.SERIES) {
                            navController.navigate(TvRoutes.seriesDetails(titleId))
                        } else {
                            navController.navigate(TvRoutes.details(titleId))
                        }
                    }
                },

                onOpenCollectionPage = { pageId, tileFocusKey ->

                    homeTileFocusRestoration.value = tileFocusKey
                    navController.navigate(TvRoutes.collectionPage(pageId))
                },

                focusRestoreKey = homeTileRestore,

                onOpenUpNext = { card ->

                    card.preview.titleId?.let { titleId -> previewHandoff.put(titleId, card.preview) }
                    openUpNextCard(container, navController, card)
                },
                onExit = { activity?.finish() },
            )
        }
        composable(TvRoutes.JELLYFIN) {

            LaunchedEffect(jellyfinConnected) {
                if (!jellyfinConnected) {
                    navController.popBackStack(TvRoutes.HOME, inclusive = false)
                }
            }

            val jellyfinTab = remember { container.retainedJellyfinTabStateHolder() }
            TvJellyfinScreen(
                stateHolder = jellyfinTab,
                jellyfinConnected = jellyfinConnected,
                model = topNavModel,
                onNavigate = navigateTopLevel,
                onExit = { activity?.finish() },
                onOpenItem = { item ->

                    item.preview.titleId?.let { previewHandoff.put(it, item.preview) }

                    openJellyfinTabItem(
                        container = container,
                        navController = navController,
                        item = item,
                        musicLibrary = musicLibraryOf(jellyfinTab.state.value),
                    )
                },
            )
        }
        composable(
            route = TvRoutes.LIBRARY,
            arguments = listOf(navArgument(TvRoutes.ARG_LIBRARY) { type = NavType.StringType }),
        ) { entry ->

            val libraryId = entry.arguments?.getString(TvRoutes.ARG_LIBRARY)
                ?.let { runCatching { TvRoutes.decodeNavSegment(it) }.getOrDefault(it) }
            TvLibraryScreen(
                libraryNativeId = libraryId,
                browse = { parent, start, limit -> container.browseCatalog(parent, start, limit) },
                search = { term, start, limit -> container.searchCatalog(term, start, limit) },
                children = { parent, start, limit -> container.catalogChildren(parent, start, limit) },
                onOpenDetails = { titleId, kind, preview ->

                    previewHandoff.put(titleId, preview)
                    if (kind == dev.infusion.core.model.TitleKind.SERIES) {
                        navController.navigate(TvRoutes.seriesDetails(titleId))
                    } else {
                        navController.navigate(TvRoutes.details(titleId))
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = TvRoutes.LIBRARY_GRID,
            arguments = listOf(
                navArgument(TvRoutes.ARG_LIBRARY) { type = NavType.StringType },
                navArgument(TvRoutes.ARG_SHOWS_LIBRARY_TITLE) { type = NavType.StringType },
                navArgument(TvRoutes.ARG_LIBRARY_COLLECTION_TYPE) { type = NavType.StringType },

                navArgument(TvRoutes.ARG_MUSIC_KIND) {
                    type = NavType.StringType
                    defaultValue = TvRoutes.ARG_NO_MUSIC_TARGET
                },
                navArgument(TvRoutes.ARG_MUSIC_ID) {
                    type = NavType.StringType
                    defaultValue = TvRoutes.ARG_NO_MUSIC_TARGET
                },
                navArgument(TvRoutes.ARG_MUSIC_NAME) {
                    type = NavType.StringType
                    defaultValue = TvRoutes.ARG_NO_MUSIC_TARGET
                },
            ),
        ) { entry ->

            val libraryId = entry.arguments?.getString(TvRoutes.ARG_LIBRARY)
                ?.let { runCatching { TvRoutes.decodeNavSegment(it) }.getOrDefault(it) }
            val libraryTitle = entry.arguments?.getString(TvRoutes.ARG_SHOWS_LIBRARY_TITLE)
                ?.let { runCatching { TvRoutes.decodeNavSegment(it) }.getOrDefault(it) }
            val collectionType = entry.arguments?.getString(TvRoutes.ARG_LIBRARY_COLLECTION_TYPE)
                ?.let { runCatching { TvRoutes.decodeNavSegment(it) }.getOrDefault(it) }
                ?.takeIf { it.isNotBlank() && it != TvRoutes.ARG_NO_COLLECTION_TYPE }

            val musicKind = entry.arguments?.getString(TvRoutes.ARG_MUSIC_KIND)
                ?.takeIf { it.isNotBlank() && it != TvRoutes.ARG_NO_MUSIC_TARGET }
                ?.let { wire ->
                    dev.infusion.feature.vertical.music.MusicLibraryTarget.entries
                        .firstOrNull { it.name == wire }
                }
            val musicId = entry.arguments?.getString(TvRoutes.ARG_MUSIC_ID)
                ?.takeIf { it.isNotBlank() && it != TvRoutes.ARG_NO_MUSIC_TARGET }
                ?.let { runCatching { TvRoutes.decodeNavSegment(it) }.getOrDefault(it) }
            val musicName = entry.arguments?.getString(TvRoutes.ARG_MUSIC_NAME)
                ?.takeIf { it.isNotBlank() && it != TvRoutes.ARG_NO_MUSIC_TARGET }
                ?.let { runCatching { TvRoutes.decodeNavSegment(it) }.getOrDefault(it) }
            if (libraryId == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {

                val libraryHolder = remember(libraryId, libraryTitle, collectionType) {
                    container.retainedLibraryBrowseStateHolder(
                        libraryNativeId = libraryId,
                        libraryName = libraryTitle ?: "Library",
                        collectionType = collectionType,
                    )
                }

                var musicEntryApplied by remember(libraryId, musicKind, musicId) {
                    androidx.compose.runtime.mutableStateOf(false)
                }

                var musicEntryLevel by remember(libraryId, musicKind, musicId) {
                    androidx.compose.runtime.mutableStateOf<
                        dev.infusion.feature.vertical.jellyfin.library.LibraryLevel?>(null)
                }
                LaunchedEffect(libraryHolder, musicKind, musicId) {
                    if (musicEntryApplied) return@LaunchedEffect
                    val id = musicId ?: return@LaunchedEffect
                    musicEntryApplied = true
                    val name = musicName ?: ""
                    when (musicKind) {
                        dev.infusion.feature.vertical.music.MusicLibraryTarget.ALBUM ->
                            libraryHolder.openMusicAlbum(id, name)
                        dev.infusion.feature.vertical.music.MusicLibraryTarget.ARTIST ->
                            libraryHolder.openMusicArtist(id, name)
                        dev.infusion.feature.vertical.music.MusicLibraryTarget.PLAYLIST ->
                            libraryHolder.openMusicPlaylist(id, name)

                        dev.infusion.feature.vertical.music.MusicLibraryTarget.TRACK, null -> Unit
                    }
                    musicEntryLevel = libraryHolder.state.value.level
                }

                val musicQueueState by container.musicQueueStateHolder.state.collectAsStateWithLifecycle()

                val openNowPlayingNow: () -> Unit = {
                    if (navController.currentBackStackEntry?.destination?.route != TvRoutes.NOW_PLAYING) {
                        navController.navigate(TvRoutes.NOW_PLAYING) { launchSingleTop = true }
                    }
                }
                TvJellyfinLibraryScreen(
                    stateHolder = libraryHolder,

                    onOpenItem = { item ->

                        val musicTarget = item.musicLibraryTarget()
                        if (musicTarget != null) {
                            libraryHolder.captureReturn(
                                focusedItemId = item.key,
                                focusedServerIndex = null,
                                firstVisibleVisibleIndex = 0,
                                firstVisibleServerIndex = null,
                                firstVisibleScrollOffsetPx = 0,
                            )
                            val sourceId = container.jellyfinSource?.sourceId
                            when (musicTarget) {
                                dev.infusion.feature.vertical.music.MusicLibraryTarget.ALBUM ->
                                    libraryHolder.openMusicAlbum(item.nativeItemId, item.displayTitle)
                                dev.infusion.feature.vertical.music.MusicLibraryTarget.ARTIST ->
                                    libraryHolder.openMusicArtist(item.nativeItemId, item.displayTitle)
                                dev.infusion.feature.vertical.music.MusicLibraryTarget.PLAYLIST ->
                                    libraryHolder.openMusicPlaylist(item.nativeItemId, item.displayTitle)
                                dev.infusion.feature.vertical.music.MusicLibraryTarget.TRACK -> {
                                    val albumId = item.parentNativeId
                                    if (sourceId != null && albumId != null) {
                                        container.musicQueueStateHolder.startAtTrack(
                                            dev.infusion.core.domain.music.MusicQueueContext.Album(
                                                sourceId = sourceId,
                                                albumNativeId = albumId,
                                                label = item.albumName ?: item.displayTitle,
                                            ),
                                            trackNativeId = item.nativeItemId,
                                        )
                                        openNowPlayingNow()
                                    }
                                }
                            }
                            return@TvJellyfinLibraryScreen
                        }

                        item.preview.titleId?.let { previewHandoff.put(it, item.preview) }

                        when {
                            item.kind == dev.infusion.core.model.TitleKind.SERIES ->
                                item.canonicalOrDeterministicTitleId?.let { seriesId ->
                                    navController.navigate(TvRoutes.seriesDetails(seriesId))
                                }
                            item.kind == dev.infusion.core.model.TitleKind.EPISODE &&
                                item.canonicalOrDeterministicSeriesTitleId != null &&
                                item.seasonNumber != null &&
                                item.episodeNumber != null -> {
                                val seriesId = item.canonicalOrDeterministicSeriesTitleId
                                val seasonNumber = item.seasonNumber
                                val episodeNumber = item.episodeNumber
                                val episodeTitleId = item.canonicalOrDeterministicTitleId
                                if (seriesId != null && seasonNumber != null &&
                                    episodeNumber != null && episodeTitleId != null
                                ) {

                                    startTitlePlayback(
                                        container = container,
                                        navController = navController,
                                        titleId = episodeTitleId,
                                        editionId = null,
                                        unitKey = dev.infusion.core.domain.catalog.episodeUnitKey(
                                            seriesId,
                                            seasonNumber,
                                            episodeNumber,
                                        ),
                                        startMode = PlaybackStartMode.RESUME,
                                        titleText = item.preview.displayTitle,
                                    )
                                }
                            }
                            else -> item.canonicalOrDeterministicTitleId?.let { titleId ->
                                navController.navigate(TvRoutes.details(titleId))
                            }
                        }
                    },

                    onOpenItemDetails = { item ->
                        item.preview.titleId?.let { previewHandoff.put(it, item.preview) }
                        when {
                            item.kind == dev.infusion.core.model.TitleKind.SERIES ->
                                item.canonicalOrDeterministicTitleId?.let { seriesId ->
                                    navController.navigate(TvRoutes.seriesDetails(seriesId))
                                }
                            item.kind == dev.infusion.core.model.TitleKind.EPISODE ->
                                (item.canonicalOrDeterministicSeriesTitleId
                                    ?: item.canonicalOrDeterministicTitleId)?.let { seriesId ->
                                    navController.navigate(TvRoutes.seriesDetails(seriesId))
                                }
                            else -> item.canonicalOrDeterministicTitleId?.let { titleId ->
                                navController.navigate(TvRoutes.details(titleId))
                            }
                        }
                    },

                    musicQueue = musicQueueState,
                    onMusicNowPlaying = { openNowPlayingNow() },
                    onMusicPlayAll = {
                        musicQueueContextOf(libraryHolder, container)?.let { context ->
                            container.musicQueueStateHolder.start(context, startIndex = 0, shuffle = false)
                            openNowPlayingNow()
                        }
                    },
                    onMusicShuffleAll = {
                        musicQueueContextOf(libraryHolder, container)?.let { context ->
                            container.musicQueueStateHolder.start(context, startIndex = 0, shuffle = true)
                            openNowPlayingNow()
                        }
                    },
                    onMusicPlaySong = { item, absoluteIndex ->
                        val context = musicQueueContextOf(libraryHolder, container)
                        if (context != null && absoluteIndex != null) {
                            val queue = container.musicQueueStateHolder.state.value

                            if (queue.current?.trackNativeId == item.nativeItemId &&
                                queue.absoluteIndex == absoluteIndex
                            ) {

                                openNowPlayingNow()
                            } else {
                                container.musicQueueStateHolder.start(context, absoluteIndex, shuffle = false)
                                openNowPlayingNow()
                            }
                        }
                    },
                    onBack = { navController.popBackStack() },

                    exitOnBackFromLevel = musicEntryLevel,
                )
            }
        }
        composable(TvRoutes.WATCHLIST) {

            val watchlistTab = remember { container.retainedWatchlistTabState() }

            val visitScope = rememberCoroutineScope()
            TvWatchlistScreen(
                model = topNavModel,
                jellyfinConnected = jellyfinConnected,
                coordinator = container.watchlistCoordinator,
                recentlyWatched = watchlistTab.recentlyWatched,

                membershipArtwork = watchlistTab.membershipArtwork,

                onGenuineVisit = {
                    android.util.Log.i("InfusionWatchedHistory", "trigger source=watchlist_page")
                    visitScope.launch {
                        runCatching { container.sweepActiveTrackerWatchedHistory() }
                            .onFailure { failure ->
                                android.util.Log.w(
                                    "InfusionWatchedHistory",
                                    "trigger_failed type=${failure.javaClass.simpleName} " +
                                        "message=${failure.message}",
                                )
                            }
                    }
                },

                metadata = container.metadataRepository,
                mediaIdentityResolver = container.mediaIdentityResolver,
                watchlistIdentity = container.watchlistIdentityResolver,
                heroMetadataFactory = HomeHeroMetadataFactory { titleId, mediaType, holderScope, hint ->
                    container.canonicalDetailsStateHolder(
                        titleId,
                        mediaType,
                        holderScope,
                        identityHint = hint,
                        loadSecondarySections = false,
                    )
                },
                onNavigate = navigateTopLevel,
                onOpenDetails = { titleId, kind, preview ->

                    previewHandoff.put(titleId, preview)
                    if (kind == TitleKind.SERIES) {
                        navController.navigate(TvRoutes.seriesDetails(titleId))
                    } else {
                        navController.navigate(TvRoutes.details(titleId))
                    }
                },
                onExit = { activity?.finish() },
            )
        }
        composable(TvRoutes.SETTINGS) {
            val scope = rememberCoroutineScope()
            val setup = remember {
                JellyfinSetupStateHolder(
                    scope = scope,
                    connectAction = { url, user, pass, consent ->
                        when (val r = container.connectJellyfin(url, user, pass, consent)) {
                            is dev.infusion.source.jellyfin.SetupResult.Ok ->

                                SourceConnectOutcome.Connected(
                                    versionLabel = r.graph.source.serverVersion,
                                    qualificationLabel = r.graph.source.versionQualification?.name,
                                )
                            is dev.infusion.source.jellyfin.SetupResult.Failed -> SourceConnectOutcome.Failed(r.failure.message)
                            is dev.infusion.source.jellyfin.SetupResult.Invalid -> SourceConnectOutcome.Invalid(r.reason)
                        }
                    },
                    removeAction = { container.removeJellyfin() },
                    initialStatus = if (container.jellyfinSource != null) {
                        JellyfinSetupStatus.Connected(
                            versionLabel = container.jellyfinSource?.source?.serverVersion,
                            qualificationLabel = container.jellyfinSource?.source?.versionQualification?.name,
                        )
                    } else {
                        JellyfinSetupStatus.Idle
                    },
                )
            }

            val jellyfinConnection = remember {
                JellyfinConnectionStateHolder(
                    scope = scope,
                    method = JellyfinConnectionMethod.CREDENTIALS,
                    activeProfileId = { container.activeProfileId },
                    actions = object : JellyfinConnectionActions {
                        override suspend fun testCredentials(
                            serverUrl: String,
                            username: String,
                            password: String,
                            consentLan: Boolean,
                        ): JellyfinConnectOutcome =
                            container.jellyfinCoordinator
                                .testPassword(serverUrl, username, password, consentLan)
                                .toOutcome()

                        override suspend fun connectCredentials(
                            serverUrl: String,
                            username: String,
                            password: String,
                            consentLan: Boolean,
                        ): JellyfinConnectOutcome =
                            container.connectJellyfin(serverUrl, username, password, consentLan).toOutcome()

                        override suspend fun testApiKey(
                            serverUrl: String,
                            apiKey: String,
                            targetUserId: String?,
                            consentLan: Boolean,
                        ): JellyfinConnectOutcome =
                            container.jellyfinCoordinator
                                .testApiKey(serverUrl, apiKey, targetUserId, consentLan)
                                .toOutcome()

                        override suspend fun connectApiKey(
                            serverUrl: String,
                            apiKey: String,
                            targetUserId: String?,
                            consentLan: Boolean,
                        ): JellyfinConnectOutcome =
                            container.connectJellyfinWithApiKey(serverUrl, apiKey, targetUserId, consentLan)
                                .toOutcome()

                        override suspend fun enumerateUsers(
                            serverUrl: String,
                            apiKey: String,
                            consentLan: Boolean,
                        ): JellyfinUsersOutcome =
                            when (
                                val r = container.jellyfinCoordinator
                                    .enumerateUsers(serverUrl, apiKey, consentLan)
                            ) {
                                is dev.infusion.source.jellyfin.JellyfinSourceCoordinator.UserEnumerationResult.Ok ->
                                    JellyfinUsersOutcome.Ok(
                                        r.users.map {
                                            JellyfinUserChoice(
                                                userId = it.userId,
                                                name = it.name,
                                                disabled = it.disabled,
                                                hidden = it.hidden,
                                            )
                                        },
                                    )
                                is dev.infusion.source.jellyfin.JellyfinSourceCoordinator.UserEnumerationResult.Failed ->
                                    JellyfinUsersOutcome.Failed(r.message)
                                is dev.infusion.source.jellyfin.JellyfinSourceCoordinator.UserEnumerationResult.Unsupported ->
                                    JellyfinUsersOutcome.Unsupported(r.reason)
                            }

                        override suspend fun connectQuickConnect(
                            serverUrl: String,
                            consentLan: Boolean,
                            onCode: (String) -> Unit,
                            shouldCancel: () -> Boolean,
                        ): JellyfinConnectOutcome =
                            container.connectJellyfinQuickConnect(serverUrl, consentLan, onCode, shouldCancel)
                                .toOutcome()
                    },
                )
            }

            val connectedUser by container.jellyfinConnectedUser.collectAsStateWithLifecycle()

            val acquisition = remember {
                listOf(
                    container.acquisitionConnectionStateHolder(
                        scope,
                        dev.infusion.core.domain.acquisition.AcquisitionService.RADARR,
                    ),
                    container.acquisitionConnectionStateHolder(
                        scope,
                        dev.infusion.core.domain.acquisition.AcquisitionService.SONARR,
                    ),
                )
            }

            val activeProfileId by container.activeProfileFlow.collectAsStateWithLifecycle(
                initialValue = container.activeProfileId,
            )
            LaunchedEffect(activeProfileId) {
                acquisition.forEach { it.reload() }
                container.refreshJellyfinConnectedUser()
            }

            val jellyfinDiscovery = remember {
                dev.infusion.feature.vertical.setup.JellyfinServerDiscoveryStateHolder(
                    scope = scope,
                    discovery = container.jellyfinDiscoveryPort,

                    lanDiscoveryAllowed = { true },
                )
            }

            val management = remember { container.profileManagementStateHolder(scope) }
            val managementState by management.state.collectAsStateWithLifecycle()

            val sourceLabel = container.jellyfinSource?.let { graph -> graph.canonicalSource.displayName }

            val homeCustomization = remember { container.homeCustomizationStateHolder(scope) }

            val widgetBuilder = remember { container.widgetBuilderStateHolder(scope) }

            val collectionPackManager = remember { container.collectionPackManagerStateHolder(scope) }

            val collectionRowSettings = remember { container.collectionRowSettingsStateHolder(scope) }

            val upNextSettings = remember { container.upNextSettingsStateHolder(scope) }

            val tracking = remember { container.trackingSettingsStateHolders(scope) }

            val appearance by container.appearanceStateHolder.preferences.collectAsStateWithLifecycle()

            val displayMatchingPolicy by container.displayMatchingStateHolder.policy.collectAsStateWithLifecycle()

            val dolbyVisionProfile7Fallback by container.dolbyVisionProfile7FallbackStateHolder.fallback
                .collectAsStateWithLifecycle()

            val audioPreferences by container.audioLanguagePreferenceStateHolder.preferences
                .collectAsStateWithLifecycle()

            val subtitlePreferences by container.subtitlePreferenceStateHolder.preferences
                .collectAsStateWithLifecycle()

            val completionThresholds by container.completionThresholdStateHolder.thresholds
                .collectAsStateWithLifecycle()

            val playbackControlsAutoHide by container.playbackControlsAutoHideStateHolder.autoHide
                .collectAsStateWithLifecycle()

            val relativeSeekStep by container.relativeSeekStepStateHolder.step
                .collectAsStateWithLifecycle()

            val segmentPreferences by container.segmentEvidenceSettingsStateHolder.preferences
                .collectAsStateWithLifecycle()

            val sourceAutoplayPreferences by container.sourceAutoplaySettingsStateHolder.preferences
                .collectAsStateWithLifecycle()

            val nextEpisodePreferences by container.nextEpisodeSettingsStateHolder.preferences
                .collectAsStateWithLifecycle()

            val subtitleAppearance by container.subtitleAppearanceStateHolder.appearance
                .collectAsStateWithLifecycle()

            val dimSubtitlesInHdr by container.hdrSubtitleDimmingStateHolder.dimming
                .collectAsStateWithLifecycle()

            TvSettingsScreen(
                model = topNavModel,
                jellyfinConnected = jellyfinConnected,
                onNavigate = navigateTopLevel,
                onExit = { activity?.finish() },
                setup = setup,

                jellyfinConnection = jellyfinConnection,
                jellyfinConnectedUser = connectedUser,
                acquisition = acquisition,
                jellyfinDiscovery = jellyfinDiscovery,

                sourceLabel = sourceLabel,

                tracking = tracking,

                ratingsSettings = container.ratingsSettings,
                profileManagement = management,
                pinEntryFactory = { profileId: String ->
                    container.pinEntryStateHolder(profileId, scope)
                },
                homeCustomization = homeCustomization,
                widgetBuilder = widgetBuilder,
                collectionPackManager = collectionPackManager,
                collectionRowSettings = collectionRowSettings,
                upNextSettings = upNextSettings,
                accent = appearance.accent,
                onAccentChange = { container.appearanceStateHolder.setAccent(it) },

                topShelfAppearance = appearance.topShelfAppearance,
                onTopShelfAppearanceChange = {
                    container.appearanceStateHolder.setTopShelfAppearance(it)
                },

                splashScreen = appearance.splashScreen,
                onSplashScreenChange = { container.appearanceStateHolder.setSplashScreen(it) },
                moviesArtwork = appearance.moviesArtwork,
                onMoviesArtworkChange = { container.appearanceStateHolder.setMoviesArtwork(it) },
                seriesArtwork = appearance.seriesArtwork,
                onSeriesArtworkChange = { container.appearanceStateHolder.setSeriesArtwork(it) },

                tmdbBackdropQuality = appearance.tmdbBackdropQuality,
                onTmdbBackdropQualityChange = {
                    container.appearanceStateHolder.setTmdbBackdropQuality(it)
                },

                tmdbLandscapeCardQuality = appearance.tmdbLandscapeCardQuality,
                onTmdbLandscapeCardQualityChange = {
                    container.appearanceStateHolder.setTmdbLandscapeCardQuality(it)
                },
                hideTitles = appearance.hideTitles,
                onHideTitlesChange = { container.appearanceStateHolder.setHideTitles(it) },

                hideEpisodeTitles = appearance.hideEpisodeTitles,
                onHideEpisodeTitlesChange = {
                    container.appearanceStateHolder.setHideEpisodeTitles(it)
                },

                cardBadges = appearance.cardBadges,
                onCardBadgesChange = { container.appearanceStateHolder.setCardBadges(it) },
                watchedCheckmark = appearance.watchedCheckmark,
                onWatchedCheckmarkChange = {
                    container.appearanceStateHolder.setWatchedCheckmark(it)
                },

                watchlistBadge = appearance.watchlistBadge,
                onWatchlistBadgeChange = {
                    container.appearanceStateHolder.setWatchlistBadge(it)
                },
                jellyfinAvailabilityBadge = appearance.jellyfinAvailabilityBadge,
                onJellyfinAvailabilityBadgeChange = {
                    container.appearanceStateHolder.setJellyfinAvailabilityBadge(it)
                },
                progressBar = appearance.progressBar,
                onProgressBarChange = {
                    container.appearanceStateHolder.setProgressBar(it)
                },

                contextMenuBadge = appearance.contextMenuBadge,
                onContextMenuBadgeChange = {
                    container.appearanceStateHolder.setContextMenuBadge(it)
                },

                hideWatchedTitles = appearance.hideWatchedTitles,
                onHideWatchedTitlesChange = {
                    container.appearanceStateHolder.setHideWatchedTitles(it)
                },

                displayMatching = displayMatchingPolicy,
                onDisplayMatchingChange = { container.displayMatchingStateHolder.setPolicy(it) },

                dolbyVisionProfile7Fallback = dolbyVisionProfile7Fallback,
                onDolbyVisionProfile7FallbackChange = {
                    container.dolbyVisionProfile7FallbackStateHolder.setFallback(it)
                },

                audioLanguage = audioPreferences.primary,
                onAudioLanguageChange = { container.audioLanguagePreferenceStateHolder.setPrimary(it) },
                secondaryAudioLanguage = audioPreferences.secondary,
                onSecondaryAudioLanguageChange = { container.audioLanguagePreferenceStateHolder.setSecondary(it) },

                subtitlePreferences = subtitlePreferences,
                onSubtitleModeChange = { container.subtitlePreferenceStateHolder.setMode(it) },
                onSubtitleLanguageChange = { container.subtitlePreferenceStateHolder.setPrimary(it) },
                onSecondarySubtitleLanguageChange = { container.subtitlePreferenceStateHolder.setSecondary(it) },
                onSubtitleSameLanguageChange = { container.subtitlePreferenceStateHolder.setSameLanguage(it) },
                onSubtitleTypeChange = { container.subtitlePreferenceStateHolder.setType(it) },

                subtitleAppearance = subtitleAppearance,
                onSubtitleAppearanceModeChange = { container.subtitleAppearanceStateHolder.setMode(it) },
                onSubtitleSizeChange = { container.subtitleAppearanceStateHolder.setSize(it) },
                onSubtitleFontChange = { container.subtitleAppearanceStateHolder.setFont(it) },
                onSubtitleTextColorChange = { container.subtitleAppearanceStateHolder.setTextColor(it) },
                onSubtitleBackgroundChange = { container.subtitleAppearanceStateHolder.setBackground(it) },
                onSubtitleBackgroundOpacityChange = {
                    container.subtitleAppearanceStateHolder.setBackgroundOpacity(it)
                },
                onSubtitleEdgeChange = { container.subtitleAppearanceStateHolder.setEdge(it) },
                onSubtitlePositionChange = { container.subtitleAppearanceStateHolder.setPosition(it) },

                dimSubtitlesInHdr = dimSubtitlesInHdr,
                onDimSubtitlesInHdrChange = { container.hdrSubtitleDimmingStateHolder.setDimming(it) },

                movieCompletion = completionThresholds.movie,
                onMovieCompletionChange = { container.completionThresholdStateHolder.setMovie(it) },
                episodeCompletion = completionThresholds.episode,
                onEpisodeCompletionChange = { container.completionThresholdStateHolder.setEpisode(it) },

                controlsAutoHide = playbackControlsAutoHide,
                onControlsAutoHideChange = { container.playbackControlsAutoHideStateHolder.set(it) },

                relativeSeekStep = relativeSeekStep,
                onRelativeSeekStepChange = { container.relativeSeekStepStateHolder.set(it) },

                segmentPreferences = segmentPreferences,
                sourceAutoplayPreferences = sourceAutoplayPreferences,

                nextEpisodePreferences = nextEpisodePreferences,
                onEnableNextEpisodeChange = { container.nextEpisodeSettingsStateHolder.setEnableNextEpisode(it) },
                onNextOnlinePriorityChange = {
                    container.nextEpisodeSettingsStateHolder.setOnlineNextMetadataPriority(it)
                },
                onAutoPlayNextChange = { container.nextEpisodeSettingsStateHolder.setAutoPlayNextEpisode(it) },
                onAutoPlayDelayChange = { container.nextEpisodeSettingsStateHolder.setAutoPlayDelaySeconds(it) },
                onNextFallbackPercentChange = {
                    container.nextEpisodeSettingsStateHolder.setFallbackTriggerPercent(it)
                },
                onSegmentOnlinePriorityChange = {
                    container.segmentEvidenceSettingsStateHolder.setOnlineIntroMetadataPriority(it)
                },
                onSegmentManualFallbackChange = {
                    container.segmentEvidenceSettingsStateHolder.setManualFallbackEnabled(it)
                },
                onSegmentFallbackStartChange = {
                    container.segmentEvidenceSettingsStateHolder.setFallbackStart(it)
                },
                onSegmentFallbackEndChange = {
                    container.segmentEvidenceSettingsStateHolder.setFallbackEnd(it)
                },
                onSegmentDisplayChange = {
                    container.segmentEvidenceSettingsStateHolder.setDisplayMode(it)
                },
                onSegmentLegendChange = {
                    container.segmentEvidenceSettingsStateHolder.setLegendEnabled(it)
                },

                onEnableSkipIntroChange = {
                    container.segmentEvidenceSettingsStateHolder.setEnableSkipIntro(it)
                },
                onAutoSkipIntroChange = {
                    container.segmentEvidenceSettingsStateHolder.setAutoSkipIntro(it)
                },
                onAutoSkipDelayChange = {
                    container.segmentEvidenceSettingsStateHolder.setAutoSkipDelaySeconds(it)
                },
                onPersistentSkipButtonChange = {
                    container.segmentEvidenceSettingsStateHolder.setPersistentSkipButton(it)
                },
                onEnableStingerAlertsChange = {
                    container.segmentEvidenceSettingsStateHolder.setEnableStingerAlerts(it)
                },
                onStingerTriggerPercentChange = {
                    container.segmentEvidenceSettingsStateHolder.setStingerTriggerPercent(it)
                },
                onStingerEarlyNotifyChange = {
                    container.segmentEvidenceSettingsStateHolder.setStingerEarlyNotifySeconds(it)
                },
                onStingerToastDurationChange = {
                    container.segmentEvidenceSettingsStateHolder.setStingerToastDurationSeconds(it)
                },

                onSourceAutoplayPolicyChange = {
                    container.sourceAutoplaySettingsStateHolder.setPolicy(it)
                },
                onPreferJellyfinChange = {
                    container.sourceAutoplaySettingsStateHolder.setPreferJellyfin(it)
                },

                selectedSection = settingsSection,
                onSelectedSectionChange = { settingsSection = it },
                onOpenPairing = {
                    settingsSection = SettingsRailPairing

                    container.phoneSetupStateHolder.start()

                    navController.navigate(TvRoutes.PAIRING)
                },

                addonsManagement = addonsManagement,

                onOpenDiagnostics = {
                    settingsSection = SettingsRailDiagnostics
                    navController.navigate(TvRoutes.DIAGNOSTICS)
                },
            )
        }
        composable(TvRoutes.DIAGNOSTICS) {

            TvDiagnosticsScreen(
                holder = container.diagnosticExportStateHolder(),
                onBack = { navController.popBackStack() },
                streamLimitObservations = container.streamLimitObservationJournal.toLogLines(),
            )
        }

        composable(TvRoutes.DISCOVER) {

            val activeProfileId by container.activeProfileFlow.collectAsStateWithLifecycle(
                initialValue = container.activeProfileId,
            )
            val discover = remember(activeProfileId) { container.retainedDiscoverTabState().holder }
            dev.infusion.tv.ui.screens.TvDiscoverScreen(
                stateHolder = discover,
                jellyfinConnected = jellyfinConnected,
                model = topNavModel,
                onNavigate = navigateTopLevel,
                onOpenDetails = { titleId, kind, preview ->

                    previewHandoff.put(titleId, preview)

                    if (kind == dev.infusion.core.model.TitleKind.SERIES) {
                        navController.navigate(TvRoutes.seriesDetails(titleId))
                    } else {
                        navController.navigate(TvRoutes.details(titleId))
                    }
                },
                onExit = { activity?.finish() },
            )
        }
        composable(TvRoutes.SEARCH) {

            dev.infusion.tv.ui.screens.TvAddonSearchScreen(
                stateHolder = searchHolder,
                jellyfinConnected = jellyfinConnected,
                model = topNavModel,
                onNavigate = navigateTopLevel,
                onOpenDestination = { destination ->

                    previewHandoff.put(destination.titleId, destination.preview)

                    if (destination.kind == dev.infusion.core.model.TitleKind.SERIES) {
                        navController.navigate(TvRoutes.seriesDetails(destination.titleId))
                    } else {
                        navController.navigate(TvRoutes.details(destination.titleId))
                    }
                },

                onOpenPerson = { personId ->
                    navController.navigate(TvRoutes.person(personId))
                },
                onExit = { activity?.finish() },
            )
        }

        composable(
            route = TvRoutes.PERSON,
            arguments = listOf(
                navArgument(TvRoutes.ARG_PERSON_AUTHORITY) { type = NavType.StringType },
                navArgument(TvRoutes.ARG_PERSON_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            val authority = entry.arguments?.getString(TvRoutes.ARG_PERSON_AUTHORITY)
                ?.takeIf { it.isNotBlank() }
                ?.let { TvRoutes.decodeNavSegment(it) }
                ?: return@composable
            val value = entry.arguments?.getString(TvRoutes.ARG_PERSON_ID)
                ?.takeIf { it.isNotBlank() }
                ?.let { TvRoutes.decodeNavSegment(it) }
                ?: return@composable
            val personId = PersonId(authority, value)
            val scope = rememberCoroutineScope()
            val holder = remember(personId) { container.personDetailsStateHolder(personId, scope) }

            val personCreditRestoreKey = remember(entry) {
                entry.savedStateHandle.remove<String>(PERSON_CREDIT_RESTORE_KEY)
            }
            TvPersonScreen(
                stateHolder = holder,
                focusRestoreKey = personCreditRestoreKey,
                onOpenCredit = { credit ->

                    personCreditFocusKey(credit)?.let { key ->
                        entry.savedStateHandle[PERSON_CREDIT_RESTORE_KEY] = key
                    }

                    scope.launch {
                        val target = container.canonicalCardTarget(credit.card) ?: return@launch

                        previewHandoff.put(
                            target,
                            credit.card.destinationPreview(target),
                            credit.card.identity,
                        )

                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (credit.mediaType == MediaType.SHOW) {
                                navController.navigate(TvRoutes.seriesDetails(target))
                            } else {
                                navController.navigate(TvRoutes.details(target))
                            }
                        }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = TvRoutes.COLLECTION,
            arguments = listOf(
                navArgument(TvRoutes.ARG_COLLECTION_ID) { type = NavType.LongType },
                navArgument(TvRoutes.ARG_COLLECTION_CURRENT) { type = NavType.StringType },
            ),
        ) { entry ->
            val collectionId = entry.arguments?.getLong(TvRoutes.ARG_COLLECTION_ID) ?: return@composable

            val originKey = entry.arguments?.getString(TvRoutes.ARG_COLLECTION_CURRENT)
                ?.takeIf { it.isNotBlank() && it != "none" }
                ?.let { TvRoutes.decodeNavSegment(it) }
            val scope = rememberCoroutineScope()
            val holder = remember(collectionId, originKey) {
                container.collectionDetailsStateHolder(collectionId, originKey, scope)
            }
            TvCollectionScreen(
                stateHolder = holder,
                onOpenCard = { card ->
                    scope.launch {
                        val target = container.canonicalCardTarget(card) ?: return@launch

                        previewHandoff.put(target, card.destinationPreview(target), card.identity)

                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (card.identity.type == MediaType.SHOW) {
                                navController.navigate(TvRoutes.seriesDetails(target))
                            } else {
                                navController.navigate(TvRoutes.details(target))
                            }
                        }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = TvRoutes.COLLECTION_PAGE,
            arguments = listOf(navArgument(TvRoutes.ARG_COLLECTION_PAGE) { type = NavType.StringType }),
        ) { entry ->
            val pageId = entry.arguments?.getString(TvRoutes.ARG_COLLECTION_PAGE)
                ?.let { runCatching { TvRoutes.decodeNavSegment(it) }.getOrDefault(it) }
                ?: return@composable

            val pageHolder = container.collectionPageStateHolder(pageId)
            LaunchedEffect(pageId) { pageHolder.open(container.activeProfileId, pageId) }

            val pageCardRestore = remember(pageId) { collectionPageFocusRestoration.remove(pageId) }
            TvCollectionPageScreen(
                stateHolder = pageHolder,
                focusRestoreKey = pageCardRestore,

                heroMetadataFactory = HomeHeroMetadataFactory { titleId, mediaType, holderScope, hint ->
                    container.canonicalDetailsStateHolder(
                        titleId,
                        mediaType,
                        holderScope,
                        identityHint = hint,
                        loadSecondarySections = false,
                    )
                },
                onOpenItem = { item ->
                    item.titleId?.let { titleId ->

                        collectionPageFocusRestoration[pageId] = item.key

                        previewHandoff.put(titleId, item.preview)
                        if (item.kind == dev.infusion.core.model.TitleKind.SERIES) {
                            navController.navigate(TvRoutes.seriesDetails(titleId))
                        } else {
                            navController.navigate(TvRoutes.details(titleId))
                        }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(TvRoutes.PAIRING) {

            TvPhoneSetupScreen(
                stateHolder = container.phoneSetupStateHolder,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = TvRoutes.DETAILS,
            arguments = listOf(
                navArgument(TvRoutes.ARG_TITLE) { type = NavType.StringType },

                navArgument(TvRoutes.ARG_RESUME_MS) {
                    type = NavType.LongType
                    defaultValue = TvRoutes.ARG_RESUME_MS_NONE
                },
            ),
        ) { entry ->
            val titleId = TitleId(UUID.fromString(entry.arguments?.getString(TvRoutes.ARG_TITLE)))

            val resumeHintMs = entry.arguments
                ?.getLong(TvRoutes.ARG_RESUME_MS, TvRoutes.ARG_RESUME_MS_NONE)
                ?.takeIf { it > 0L }

            val identityHint = remember(titleId) {
                previewHandoff.peek(titleId)?.toIdentityHint(MediaType.MOVIE)
            }

            val seam = remember { container.profileSwitchingSeam }
            val detailsHolder by seam.details(titleId).current.collectAsStateWithLifecycle()
            val stateHolder = detailsHolder ?: return@composable

            LaunchedEffect(stateHolder, resumeHintMs) {

                stateHolder.refreshServerResume()
                stateHolder.publishResumeHint(resumeHintMs)
            }
            val scope = rememberCoroutineScope()

            val canonicalHolder = remember(titleId) {
                container.canonicalDetailsStateHolder(
                    titleId,
                    MediaType.MOVIE,
                    scope,
                    identityHint = identityHint,

                    eagerSecondarySections = entry.savedStateHandle
                        .get<DetailsRestoration>(DETAILS_RESTORE_SLOT) != null,

                    retainAcrossVisits = true,
                )
            }

            val restoration = remember(entry) {
                entry.savedStateHandle.remove<DetailsRestoration>(DETAILS_RESTORE_SLOT)
            }
            val focusRestoreKey = restoration?.focusKey
            val movieWatchKey = WatchKey.movie(titleId, null)

            val openCanonicalCard: (MediaCardUi, String?) -> Unit = { card, occurrenceKey ->
                scope.launch {
                    val target = container.canonicalCardTarget(card)
                    if (target != null && target != titleId) {

                        previewHandoff.put(target, card.destinationPreview(target), card.identity)

                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (occurrenceKey != null) {
                                rememberDetailsReturn(
                                    entry.savedStateHandle,
                                    DetailsRestoration(occurrenceKey),
                                )
                            }
                            if (card.identity.type == MediaType.SHOW) {
                                navController.navigate(TvRoutes.seriesDetails(target))
                            } else {
                                navController.navigate(TvRoutes.details(target))
                            }
                        }
                    }
                }
            }
            TvDetailsScreen(
                stateHolder = stateHolder,
                canonicalHolder = canonicalHolder,

                onOpenCanonicalCard = { card -> openCanonicalCard(card, null) },

                onOpenCanonicalCardOccurrence = { card, occurrenceKey ->
                    openCanonicalCard(card, occurrenceKey)
                },
                onOpenCanonicalPerson = { person, focusOccurrenceKey ->

                    val personId = person.personId
                    if (personId != null) {
                        rememberDetailsReturn(
                            entry.savedStateHandle,
                            DetailsRestoration(focusOccurrenceKey),
                        )
                        navController.navigate(TvRoutes.person(personId))
                    }
                },
                onOpenCollection = { collectionId ->

                    rememberDetailsReturn(
                        entry.savedStateHandle,
                        DetailsRestoration(collectionFocusKey(collectionId)),
                    )
                    navController.navigate(
                        TvRoutes.collection(collectionId, canonicalHolder.state.value.identity?.cacheKey()),
                    )
                },

                onOpenCompany = { company ->
                    company.entityRef?.let { ref ->
                        rememberDetailsReturn(
                            entry.savedStateHandle,
                            DetailsRestoration(companyFocusKey(ref)),
                        )
                        navController.navigate(
                            TvRoutes.collectionPage(
                                entityCollectionPageId(ref, company.name, company.logoLocator),
                            ),
                        )
                    }
                },
                focusRestoreKey = focusRestoreKey,

                onPrimaryAction = {
                    startTitlePlayback(
                        container = container,
                        navController = navController,
                        titleId = titleId,
                        editionId = null,
                        unitKey = TvRoutes.movieUnitKey(titleId),
                        startMode = PlaybackStartMode.RESUME,

                        logoLocator = canonicalHolder.state.value.hero?.logoLocator,
                        backdropLocator = canonicalHolder.state.value.hero?.backdropLocator,
                        titleText = canonicalHolder.state.value.hero?.title,
                    )
                },
                onPlayFromBeginning = {
                    startTitlePlayback(
                        container = container,
                        navController = navController,
                        titleId = titleId,
                        editionId = null,
                        unitKey = TvRoutes.movieUnitKey(titleId),
                        startMode = PlaybackStartMode.FROM_BEGINNING,
                        logoLocator = canonicalHolder.state.value.hero?.logoLocator,
                        backdropLocator = canonicalHolder.state.value.hero?.backdropLocator,
                        titleText = canonicalHolder.state.value.hero?.title,
                    )
                },
                onChooseSource = {
                    navController.navigate(
                        TvRoutes.sourcePicker(
                            titleId,
                            null,
                            startMode = PlaybackStartMode.RESUME,
                            logoLocator = canonicalHolder.state.value.hero?.logoLocator,
                        ),
                    )
                },
                onMarkWatched = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        dev.infusion.feature.vertical.details.MarkWatchedActions.markWatched(container.coordinator, movieWatchKey)
                    }
                },
                onMarkUnwatched = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        dev.infusion.feature.vertical.details.MarkWatchedActions.markUnwatched(container.coordinator, movieWatchKey)
                    }
                },

                onTrailerClick = { trailer ->
                    rememberDetailsReturn(entry.savedStateHandle, DetailsRestoration(TrailerActionKey))
                    navController.navigate(TvRoutes.trailer(titleId, trailer.site, trailer.key))
                },
            )
        }
        composable(
            route = TvRoutes.SERIES_DETAILS,
            arguments = listOf(navArgument(TvRoutes.ARG_TITLE) { type = NavType.StringType }),
        ) { entry ->
            val titleId = TitleId(UUID.fromString(entry.arguments?.getString(TvRoutes.ARG_TITLE)))

            val identityHint = remember(titleId) {
                previewHandoff.peek(titleId)?.toIdentityHint(MediaType.SHOW)
            }
            val scope = rememberCoroutineScope()

            val holder = remember(titleId) {
                container.seriesDetailsStateHolder(titleId, scope)
            }

            val canonicalHolder = remember(titleId) {
                container.canonicalDetailsStateHolder(
                    titleId,
                    MediaType.SHOW,
                    scope,
                    identityHint = identityHint,

                    eagerSecondarySections = entry.savedStateHandle
                        .get<DetailsRestoration>(DETAILS_RESTORE_SLOT) != null,

                    retainAcrossVisits = true,
                )
            }

            val restoration = remember(entry) {
                entry.savedStateHandle.remove<DetailsRestoration>(DETAILS_RESTORE_SLOT)
            }
            val focusRestoreKey = restoration?.focusKey

            val restoreSeasonNumber = restoration?.seasonNumber

            val openCanonicalCard: (MediaCardUi, String?) -> Unit = { card, occurrenceKey ->
                scope.launch {
                    val target = container.canonicalCardTarget(card)
                    if (target != null && target != titleId) {

                        previewHandoff.put(target, card.destinationPreview(target))

                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (occurrenceKey != null) {
                                rememberDetailsReturn(
                                    entry.savedStateHandle,
                                    DetailsRestoration(occurrenceKey),
                                )
                            }
                            if (card.identity.type == MediaType.SHOW) {
                                navController.navigate(TvRoutes.seriesDetails(target))
                            } else {
                                navController.navigate(TvRoutes.details(target))
                            }
                        }
                    }
                }
            }
            TvSeriesDetailsScreen(
                stateHolder = holder,
                canonicalHolder = canonicalHolder,

                onOpenCanonicalCard = { card -> openCanonicalCard(card, null) },

                onOpenCanonicalCardOccurrence = { card, occurrenceKey ->
                    openCanonicalCard(card, occurrenceKey)
                },
                onOpenCanonicalPerson = { person, focusOccurrenceKey ->

                    val personId = person.personId
                    if (personId != null) {
                        rememberDetailsReturn(
                            entry.savedStateHandle,
                            DetailsRestoration(focusOccurrenceKey),
                        )
                        navController.navigate(TvRoutes.person(personId))
                    }
                },
                onOpenCollection = { collectionId ->
                    rememberDetailsReturn(
                        entry.savedStateHandle,
                        DetailsRestoration(collectionFocusKey(collectionId)),
                    )
                    navController.navigate(
                        TvRoutes.collection(collectionId, canonicalHolder.state.value.identity?.cacheKey()),
                    )
                },

                onOpenCompany = { company ->
                    company.entityRef?.let { ref ->
                        rememberDetailsReturn(
                            entry.savedStateHandle,
                            DetailsRestoration(companyFocusKey(ref)),
                        )
                        navController.navigate(
                            TvRoutes.collectionPage(
                                entityCollectionPageId(ref, company.name, company.logoLocator),
                            ),
                        )
                    }
                },
                focusRestoreKey = focusRestoreKey,
                restoreSeasonNumber = restoreSeasonNumber,
                onOpenEpisode = { season, episode, episodeTitleId ->

                    rememberDetailsReturn(
                        entry.savedStateHandle,
                        DetailsRestoration(episodeFocusKey(season, episode), season),
                    )

                    val hero = canonicalHolder.state.value.hero
                    startTitlePlayback(
                        container = container,
                        navController = navController,
                        titleId = episodeTitleId,
                        editionId = null,
                        unitKey = dev.infusion.core.domain.catalog.episodeUnitKey(titleId, season, episode),
                        startMode = PlaybackStartMode.RESUME,
                        logoLocator = hero?.logoLocator,
                        backdropLocator = hero?.backdropLocator,
                        titleText = hero?.title,
                    )
                },
                onBack = { navController.popBackStack() },

                onTrailerClick = { trailer ->
                    rememberDetailsReturn(entry.savedStateHandle, DetailsRestoration(TrailerActionKey))
                    navController.navigate(TvRoutes.trailer(titleId, trailer.site, trailer.key))
                },
            )
        }

        composable(
            route = TvRoutes.TRAILER,
            arguments = listOf(
                navArgument(TvRoutes.ARG_TITLE) { type = NavType.StringType },
                navArgument(TvRoutes.ARG_SITE) { type = NavType.StringType },
                navArgument(TvRoutes.ARG_KEY) { type = NavType.StringType },
            ),
        ) { entry ->
            val titleId = TitleId(UUID.fromString(entry.arguments?.getString(TvRoutes.ARG_TITLE)))

            val target = remember(titleId) {
                trailerTargetFor(
                    TrailerUi(
                        site = entry.arguments?.getString(TvRoutes.ARG_SITE).decodeTrailerSegment(),
                        key = entry.arguments?.getString(TvRoutes.ARG_KEY).decodeTrailerSegment(),
                        title = null,
                        kindLabel = "Trailer",
                    ),
                )
            }
            TvTrailerScreen(
                target = target,

                onBack = {
                    if (navController.currentDestination?.route == TvRoutes.TRAILER) {
                        navController.popBackStack()
                    }
                },
            )
        }
        composable(
            route = TvRoutes.EPISODE_DETAILS,
            arguments = listOf(
                navArgument(TvRoutes.ARG_SERIES_TITLE) { type = NavType.StringType },
                navArgument(TvRoutes.ARG_SEASON_NUMBER) { type = NavType.IntType },
                navArgument(TvRoutes.ARG_EPISODE_NUMBER) { type = NavType.IntType },
                navArgument(TvRoutes.ARG_EPISODE_TITLE) { type = NavType.StringType },

                navArgument(TvRoutes.ARG_RESUME_MS) {
                    type = NavType.LongType
                    defaultValue = TvRoutes.ARG_RESUME_MS_NONE
                },
            ),
        ) { entry ->
            val seriesTitleId = TitleId(UUID.fromString(entry.arguments?.getString(TvRoutes.ARG_SERIES_TITLE)))
            val seasonNumber = entry.arguments?.getInt(TvRoutes.ARG_SEASON_NUMBER) ?: 1
            val episodeNumber = entry.arguments?.getInt(TvRoutes.ARG_EPISODE_NUMBER) ?: 1
            val episodeTitleId = TitleId(UUID.fromString(entry.arguments?.getString(TvRoutes.ARG_EPISODE_TITLE)))

            val resumeHintMs = entry.arguments
                ?.getLong(TvRoutes.ARG_RESUME_MS, TvRoutes.ARG_RESUME_MS_NONE)
                ?.takeIf { it > 0L }
            val scope = rememberCoroutineScope()
            val holder = remember(seriesTitleId, seasonNumber, episodeNumber, episodeTitleId, resumeHintMs) {
                container.episodeDetailsStateHolder(
                    seriesTitleId,
                    seasonNumber,
                    episodeNumber,
                    episodeTitleId,
                    scope,
                    resumeHintMs,
                )
            }
            val watchKey = WatchKey.episode(seriesTitleId, seasonNumber, episodeNumber)

            val episodeUnitKey = dev.infusion.core.domain.catalog.episodeUnitKey(seriesTitleId, seasonNumber, episodeNumber)
            TvEpisodeDetailsScreen(
                stateHolder = holder,

                onPrimaryAction = {
                    startTitlePlayback(
                        container = container,
                        navController = navController,
                        titleId = episodeTitleId,
                        editionId = null,
                        unitKey = episodeUnitKey,
                        startMode = PlaybackStartMode.RESUME,
                        logoLocator = holder.state.value.parentShowLogoLocator,
                        backdropLocator = holder.state.value.parentShowBackdropLocator,
                        titleText = holder.state.value.displayTitle ?: holder.state.value.seriesTitle,
                    )
                },
                onPlayFromBeginning = {
                    startTitlePlayback(
                        container = container,
                        navController = navController,
                        titleId = episodeTitleId,
                        editionId = null,
                        unitKey = episodeUnitKey,
                        startMode = PlaybackStartMode.FROM_BEGINNING,
                        logoLocator = holder.state.value.parentShowLogoLocator,
                        backdropLocator = holder.state.value.parentShowBackdropLocator,
                        titleText = holder.state.value.displayTitle ?: holder.state.value.seriesTitle,
                    )
                },
                onChooseSource = {
                    navController.navigate(
                        TvRoutes.sourcePicker(episodeTitleId, null, episodeUnitKey, PlaybackStartMode.RESUME),
                    )
                },
                onMarkWatched = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        dev.infusion.feature.vertical.details.MarkWatchedActions.markWatched(container.coordinator, watchKey)
                    }
                },
                onMarkUnwatched = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        dev.infusion.feature.vertical.details.MarkWatchedActions.markUnwatched(container.coordinator, watchKey)
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = TvRoutes.SOURCE_PICKER,
            arguments = listOf(
                navArgument(TvRoutes.ARG_TITLE) { type = NavType.StringType },
                navArgument(TvRoutes.ARG_EDITION) { type = NavType.StringType },
                navArgument(TvRoutes.ARG_UNIT_KEY) { type = NavType.StringType },
                navArgument(TvRoutes.ARG_START_MODE) { type = NavType.StringType },

                navArgument(TvRoutes.ARG_LOGO) {
                    type = NavType.StringType
                    defaultValue = TvRoutes.ARG_LOGO_NONE
                },
            ),
        ) { entry ->
            val titleId = TitleId(UUID.fromString(entry.arguments?.getString(TvRoutes.ARG_TITLE)))
            val editionId = entry.arguments?.getString(TvRoutes.ARG_EDITION)
                ?.takeIf { it != "none" }
                ?.let { EditionId(UUID.fromString(it)) }

            val headerLogoLocator = entry.arguments?.getString(TvRoutes.ARG_LOGO)
                ?.takeIf { it != TvRoutes.ARG_LOGO_NONE }
                ?.let { runCatching { TvRoutes.decodeNavSegment(it) }.getOrNull() }

            val unitKey = entry.arguments?.getString(TvRoutes.ARG_UNIT_KEY)
                ?.let { runCatching { TvRoutes.decodeNavSegment(it) }.getOrDefault(it) }
                ?: TvRoutes.movieUnitKey(titleId)

            val startMode = PlaybackStartMode.fromWire(
                entry.arguments?.getString(TvRoutes.ARG_START_MODE),
            )
            val picker: SourcePickerStateHolder = remember {
                container.sourcePickerStateHolder(titleId, editionId, unitKey)
            }

            val posterLocator = rememberSourcesPosterLocator(container, titleId, unitKey)

            val episodeUnit = remember(unitKey) {
                dev.infusion.core.domain.catalog.parseEpisodeUnitKey(unitKey)
            }
            val resolvedHeaderLogo =
                rememberSourcesHeaderLogo(container, headerLogoLocator, episodeUnit)
            val headerLogo = headerLogoLocator ?: resolvedHeaderLogo

            val scope = rememberCoroutineScope()

            val context = androidx.compose.ui.platform.LocalContext.current
            var externalLinkCandidate by remember { mutableStateOf<PlayableCandidate?>(null) }
            TvSourcePickerScreen(
                stateHolder = picker,
                externalLinkCandidate = externalLinkCandidate,

                headerLogoLocator = headerLogo,

                posterLocator = posterLocator,
                onDismissExternalLink = { externalLinkCandidate = null },
                onOpenExternalLink = { external ->
                    try {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(external.transport.value),
                        )
                        context.startActivity(intent)
                    } catch (e: android.content.ActivityNotFoundException) {

                        android.widget.Toast.makeText(
                            context,
                            "No browser available to open this link",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                    externalLinkCandidate = null
                },
                onStarted = { candidate ->

                    val jellyfinSourceId = container.jellyfinSource?.sourceId?.value?.toString()
                    val prepProviderLabel = if (candidate.sourceId.value.toString() == jellyfinSourceId) {
                        "Jellyfin"
                    } else {
                        candidate.providerName
                    }
                    val prepFileLabel = dev.infusion.feature.vertical.sourcepicker.SelectedSourceFileLabel
                        .of(candidate, jellyfinSourceId)
                    scope.launch {
                        container.saveCandidate(candidate)

                        withContext(kotlinx.coroutines.Dispatchers.Main) {

                            val episodeWatchKey = if (unitKey.startsWith("S:")) {
                                val parts = unitKey.removePrefix("S:").split(':')
                                if (parts.size == 3) {
                                    WatchKey.episode(
                                        TitleId(UUID.fromString(parts[0])),
                                        parts[1].toInt(),
                                        parts[2].toInt(),
                                    )
                                } else null
                            } else null
                            navController.navigate(
                                TvRoutes.player(
                                    titleId,
                                    editionId,
                                    candidate.candidateId,
                                    watchKey = episodeWatchKey,
                                    startMode = startMode,

                                    prepLogoLocator = headerLogo,
                                    prepProviderLabel = prepProviderLabel,
                                    prepFileLabel = prepFileLabel,
                                ),
                            ) {

                                popUpTo(TvRoutes.SOURCE_PICKER) { inclusive = true }
                            }
                        }
                    }
                },
                onCancel = { navController.popBackStack() },
                onExternalLink = { externalLinkCandidate = it },
            )
        }

        composable(
            route = TvRoutes.PLAYBACK_START,
            arguments = listOf(
                navArgument(TvRoutes.ARG_TITLE) { type = NavType.StringType },
                navArgument(TvRoutes.ARG_EDITION) { type = NavType.StringType },
                navArgument(TvRoutes.ARG_UNIT_KEY) { type = NavType.StringType },
                navArgument(TvRoutes.ARG_START_MODE) { type = NavType.StringType },
                navArgument(TvRoutes.ARG_LOGO) {
                    type = NavType.StringType
                    defaultValue = TvRoutes.ARG_LOGO_NONE
                },
                navArgument(TvRoutes.ARG_BACKDROP) {
                    type = NavType.StringType
                    defaultValue = TvRoutes.ARG_LOGO_NONE
                },
                navArgument(TvRoutes.ARG_TITLE_TEXT) {
                    type = NavType.StringType
                    defaultValue = TvRoutes.ARG_LOGO_NONE
                },
            ),
        ) { entry ->
            val titleId = TitleId(UUID.fromString(entry.arguments?.getString(TvRoutes.ARG_TITLE)))
            val editionId = entry.arguments?.getString(TvRoutes.ARG_EDITION)
                ?.takeIf { it != "none" }
                ?.let { EditionId(UUID.fromString(it)) }
            val unitKey = entry.arguments?.getString(TvRoutes.ARG_UNIT_KEY)
                ?.let { runCatching { TvRoutes.decodeNavSegment(it) }.getOrDefault(it) }
                ?: TvRoutes.movieUnitKey(titleId)
            val startMode = PlaybackStartMode.fromWire(
                entry.arguments?.getString(TvRoutes.ARG_START_MODE),
            )
            val logoLocator = decodeOptionalSegment(entry.arguments?.getString(TvRoutes.ARG_LOGO))
            val backdropLocator = decodeOptionalSegment(entry.arguments?.getString(TvRoutes.ARG_BACKDROP))
            val titleText = decodeOptionalSegment(entry.arguments?.getString(TvRoutes.ARG_TITLE_TEXT))

            val episodeSeriesId: TitleId? = remember(unitKey) {
                if (unitKey.startsWith("S:")) {
                    val parts = unitKey.removePrefix("S:").split(':')
                    if (parts.size == 3) {
                        runCatching { TitleId(UUID.fromString(parts[0])) }.getOrNull()
                    } else null
                } else null
            }
            val seriesHero = rememberSeriesPreparationArtwork(
                container = container,
                seriesId = episodeSeriesId,
                enabled = logoLocator == null && backdropLocator == null,
            )
            val effectiveLogoLocator = logoLocator ?: seriesHero?.logoLocator
            val effectiveBackdropLocator = backdropLocator ?: seriesHero?.backdropLocator
            val effectiveTitleText = titleText ?: seriesHero?.title

            val autoplayEnabled =
                container.sourceAutoplaySettingsStateHolder.preferences.value.policy.enabled
            if (!autoplayEnabled) {
                LaunchedEffect(entry) {
                    navController.navigate(
                        TvRoutes.sourcePicker(titleId, editionId, unitKey, startMode, logoLocator),
                    ) {
                        popUpTo(TvRoutes.PLAYBACK_START) { inclusive = true }
                    }
                }
            } else {
                val journey = remember(titleId, editionId, unitKey) {
                    container.playbackStartStateHolder(titleId, editionId, unitKey)
                }
                DisposableEffect(journey) {
                    onDispose { journey.cancel() }
                }
                val holderState by journey.state.collectAsStateWithLifecycle()
                val phase = holderState.phase
                val scope = rememberCoroutineScope()
                LaunchedEffect(phase) {
                    when (phase) {
                        is dev.infusion.feature.vertical.sourcepicker.PlaybackStartPhase.Selected -> {
                            val candidate =
                                (phase as dev.infusion.feature.vertical.sourcepicker.PlaybackStartPhase.Selected).candidate
                            val providerLabel =
                                if (candidate.sourceId.value.toString() == journey.jellyfinSourceId) {
                                    "Jellyfin"
                                } else {
                                    candidate.providerName
                                }

                            val fileLabel = dev.infusion.feature.vertical.sourcepicker.SelectedSourceFileLabel
                                .of(candidate, journey.jellyfinSourceId)
                            scope.launch {
                                container.saveCandidate(candidate)

                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    val episodeWatchKey = if (unitKey.startsWith("S:")) {
                                        val parts = unitKey.removePrefix("S:").split(':')
                                        if (parts.size == 3) {
                                            WatchKey.episode(
                                                TitleId(UUID.fromString(parts[0])),
                                                parts[1].toInt(),
                                                parts[2].toInt(),
                                            )
                                        } else null
                                    } else null
                                    navController.navigate(
                                        TvRoutes.player(
                                            titleId = titleId,
                                            editionId = editionId,
                                            candidateId = candidate.candidateId,
                                            watchKey = episodeWatchKey,
                                            startMode = startMode,
                                            prepLogoLocator = effectiveLogoLocator,
                                            prepBackdropLocator = effectiveBackdropLocator,
                                            prepTitleText = effectiveTitleText,
                                            prepProviderLabel = providerLabel,
                                            prepFileLabel = fileLabel,
                                        ),
                                    ) {
                                        popUpTo(TvRoutes.PLAYBACK_START) { inclusive = true }
                                    }
                                }
                            }
                        }
                        is dev.infusion.feature.vertical.sourcepicker.PlaybackStartPhase.ManualFallback,
                        is dev.infusion.feature.vertical.sourcepicker.PlaybackStartPhase.Failed -> {

                            navController.navigate(
                                TvRoutes.sourcePicker(titleId, editionId, unitKey, startMode, logoLocator),
                            ) {
                                popUpTo(TvRoutes.PLAYBACK_START) { inclusive = true }
                            }
                        }
                        dev.infusion.feature.vertical.sourcepicker.PlaybackStartPhase.Resolving -> Unit
                    }
                }
                TvPlaybackPreparationScreen(
                    phase = phase,
                    backdropLocator = effectiveBackdropLocator,
                    logoLocator = effectiveLogoLocator,
                    title = effectiveTitleText,
                    providerLabel = { candidate ->
                        if (candidate.sourceId.value.toString() == journey.jellyfinSourceId) "Jellyfin"
                        else candidate.providerName
                    },
                    fileLabel = { candidate ->
                        dev.infusion.feature.vertical.sourcepicker.SelectedSourceFileLabel
                            .of(candidate, journey.jellyfinSourceId)
                    },
                    onBack = {
                        journey.cancel()
                        navController.popBackStack()
                    },
                )
            }
        }

        composable(TvRoutes.NOW_PLAYING) {

            val controlsAutoHide by container.playbackControlsAutoHideStateHolder.autoHide
                .collectAsStateWithLifecycle()
            TvNowPlayingScreen(
                holder = container.musicQueueStateHolder,
                presentation = container.musicNowPlayingPresentation,
                onBack = { navController.popBackStack() },
                autoHide = controlsAutoHide,
            )
        }
        composable(
            route = TvRoutes.PLAYER,
            arguments = listOf(
                navArgument(TvRoutes.ARG_TITLE) { type = NavType.StringType },
                navArgument(TvRoutes.ARG_EDITION) { type = NavType.StringType },
                navArgument(TvRoutes.ARG_CANDIDATE) { type = NavType.StringType },
                navArgument(TvRoutes.ARG_EPISODE_KEY) { type = NavType.StringType },
                navArgument(TvRoutes.ARG_START_MODE) { type = NavType.StringType },

                navArgument(TvRoutes.ARG_PREP_LOGO) {
                    type = NavType.StringType
                    defaultValue = TvRoutes.ARG_LOGO_NONE
                },
                navArgument(TvRoutes.ARG_PREP_BACKDROP) {
                    type = NavType.StringType
                    defaultValue = TvRoutes.ARG_LOGO_NONE
                },
                navArgument(TvRoutes.ARG_PREP_TITLE) {
                    type = NavType.StringType
                    defaultValue = TvRoutes.ARG_LOGO_NONE
                },
                navArgument(TvRoutes.ARG_PREP_PROVIDER) {
                    type = NavType.StringType
                    defaultValue = TvRoutes.ARG_LOGO_NONE
                },
                navArgument(TvRoutes.ARG_PREP_FILE) {
                    type = NavType.StringType
                    defaultValue = TvRoutes.ARG_LOGO_NONE
                },
            ),
        ) { entry ->
            val titleId = TitleId(UUID.fromString(entry.arguments?.getString(TvRoutes.ARG_TITLE)))
            val editionId = entry.arguments?.getString(TvRoutes.ARG_EDITION)
                ?.takeIf { it != "none" }
                ?.let { EditionId(UUID.fromString(it)) }
            val candidateId = CandidateId(UUID.fromString(entry.arguments?.getString(TvRoutes.ARG_CANDIDATE)))

            val episodeKey = entry.arguments?.getString(TvRoutes.ARG_EPISODE_KEY)
                ?.let { runCatching { TvRoutes.decodeNavSegment(it) }.getOrDefault(it) }
            val watchKey: WatchKey = episodeKey?.takeIf { it != "none" }?.let { encoded ->
                val parts = encoded.removePrefix("S:").split(':')
                if (parts.size == 3) {
                    WatchKey.episode(
                        TitleId(UUID.fromString(parts[0])),
                        parts[1].toInt(),
                        parts[2].toInt(),
                    )
                } else {
                    WatchKey.movie(titleId, editionId)
                }
            } ?: WatchKey.movie(titleId, editionId)

            val startMode = PlaybackStartMode.fromWire(
                entry.arguments?.getString(TvRoutes.ARG_START_MODE),
            )

            var candidate by remember { mutableStateOf<PlayableCandidate?>(null) }
            LaunchedEffect(candidateId) {
                candidate = container.candidateById(candidateId)
            }

            val prepLogoLocator = decodeOptionalSegment(entry.arguments?.getString(TvRoutes.ARG_PREP_LOGO))
            val prepBackdropLocator = decodeOptionalSegment(entry.arguments?.getString(TvRoutes.ARG_PREP_BACKDROP))
            val prepTitleText = decodeOptionalSegment(entry.arguments?.getString(TvRoutes.ARG_PREP_TITLE))
            val prepProviderLabel = decodeOptionalSegment(entry.arguments?.getString(TvRoutes.ARG_PREP_PROVIDER))

            val prepFileLabel = decodeOptionalSegment(entry.arguments?.getString(TvRoutes.ARG_PREP_FILE))
            val playerScope = rememberCoroutineScope()

            val retainedAttempt = remember(watchKey) { playbackRetention.take(watchKey) }
            val player = remember(watchKey, candidate) {
                retainedAttempt?.holder ?: container.playerStateHolder(watchKey, candidate, playerScope)
            }

            DisposableEffect(player) {
                container.videoPlaybackMediaSession.attach(player)
                onDispose { container.videoPlaybackMediaSession.detach(player) }
            }

            val lineage = remember(watchKey) { retainedAttempt?.lineage ?: container.newWatchLineage() }
            var lineageSessionId by remember(watchKey) { mutableStateOf(retainedAttempt?.lineageSessionId) }

            val presentationHolder = remember(watchKey) {
                val canonical = if (watchKey.isEpisode) {
                    watchKey.seriesId?.let { seriesId ->
                        container.canonicalDetailsStateHolder(
                            titleId = seriesId,
                            mediaType = dev.infusion.core.model.metadata.MediaType.SHOW,
                            holderScope = playerScope,
                            loadSecondarySections = false,
                        )
                    }
                } else {
                    container.canonicalDetailsStateHolder(
                        titleId = titleId,
                        mediaType = dev.infusion.core.model.metadata.MediaType.MOVIE,
                        holderScope = playerScope,
                        loadSecondarySections = false,
                    )
                }
                container.playerPresentationStateHolder(
                    watchKey = watchKey,
                    playingTitleId = titleId,
                    canonical = canonical,
                    holderScope = playerScope,
                )
            }
            val metadata by presentationHolder.state.collectAsStateWithLifecycle()

            val preparation = remember(
                prepLogoLocator,
                prepBackdropLocator,
                prepTitleText,
                prepProviderLabel,
                prepFileLabel,
                candidate,
                metadata.hero?.backdropLocator,
                metadata.hero?.logoLocator,
                metadata.hero?.title,
            ) {
                if (prepLogoLocator == null && prepBackdropLocator == null &&
                    prepTitleText == null && prepProviderLabel == null && prepFileLabel == null
                ) {
                    null
                } else {
                    dev.infusion.tv.ui.screens.PlaybackPreparationPresentation(
                        backdropLocator = prepBackdropLocator ?: metadata.hero?.backdropLocator,
                        logoLocator = prepLogoLocator ?: metadata.hero?.logoLocator,
                        title = prepTitleText ?: metadata.hero?.title,
                        providerLabel = prepProviderLabel ?: candidate?.providerName.orEmpty(),

                        sourceLabel = prepFileLabel,
                    )
                }
            }

            var resumePositionMs by remember { mutableStateOf<Long?>(null) }

            var resumeObservedAt by remember { mutableStateOf<java.time.Instant?>(null) }
            var resumeLoaded by remember { mutableStateOf(false) }
            LaunchedEffect(watchKey, startMode) {

                val observation = if (startMode == PlaybackStartMode.RESUME) {
                    container.localResumeObservation(watchKey)
                } else {
                    null
                }
                resumePositionMs = observation?.positionMs
                resumeObservedAt = observation?.observedAt
                resumeLoaded = true
            }
            LaunchedEffect(candidate, resumeLoaded) {
                if (candidate != null && resumeLoaded && player.state.value == null) {

                    try {
                        val session = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            lineage.beginWatchAttempt(watchKey, candidate!!)
                        }
                        lineageSessionId = session
                        player.start(
                            candidate!!,
                            resumePositionMs?.takeIf { it > 0 },
                            sessionId = session,
                            startMode = startMode,

                            localResumeObservedAt = resumeObservedAt,
                        )
                    } catch (setup: dev.infusion.source.aggregation.select.WatchAttemptSetupException) {
                        android.util.Log.w("TvNavHost", "watch attempt setup failed", setup)
                        player.failSetup(setup.message ?: "Playback could not be started.")
                    }
                }
            }

            val playerState by player.state.collectAsStateWithLifecycle()

            var endOfMediaClosed by remember { mutableStateOf(false) }
            LaunchedEffect(playerState?.phase) {
                val s = playerState ?: return@LaunchedEffect
                if (s.phase == dev.infusion.core.playback.api.PlaybackState.ENDED) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        lineage.complete(s.positionMs, s.durationMs)
                    }

                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (shouldClosePlaybackAtEndOfMedia(
                                phase = s.phase,
                                upNextVisible = s.upNext != null,
                                alreadyClosed = endOfMediaClosed,
                            )
                        ) {
                            endOfMediaClosed = true
                            disposeTvPlayerDestination(player)
                            navController.popBackStack()
                        }
                    }
                }
            }

            val displayMatchingPolicy by container.displayMatchingStateHolder.policy.collectAsStateWithLifecycle()
            val trackInventory = playerState?.trackInventory
            val selectedVideoTrackId = playerState?.trackSelection?.videoTrackId

            val declaredVideo = remember(candidate) {
                dev.infusion.core.playback.api.DeclaredVideoMetadata.of(
                    frameRateHz = candidate?.facts?.frameRate,
                    resolution = candidate?.facts?.resolution,
                )
            }

            val measuredVideo = playerState?.measuredVideo
                ?: dev.infusion.core.playback.api.MeasuredVideoEvidence.NONE
            LaunchedEffect(
                trackInventory,
                selectedVideoTrackId,
                declaredVideo,
                measuredVideo,
                displayMatchingPolicy,
            ) {
                if (trackInventory == null) {
                    container.displayMatchingDiagnostics.log(
                        "effect policy=$displayMatchingPolicy phase=${playerState?.phase} inventory=none (no request)",
                    )

                    if (displayMatchingPolicy == dev.infusion.core.playback.api.DisplayMatchingPolicy.OFF) {
                        player.noteInitialDisplayMatchSettled(true)
                    }
                    return@LaunchedEffect
                }
                val target = dev.infusion.core.playback.api.ContentDisplayTarget.from(
                    inventory = trackInventory,
                    selectedVideoTrackId = selectedVideoTrackId,
                    declared = declaredVideo,
                    measured = measuredVideo,
                )

                container.displayMatchingDiagnostics.log(
                    "effect policy=$displayMatchingPolicy phase=${playerState?.phase} " +
                        "videoTracks=${trackInventory.videoTracks.size} " +
                        "[${trackInventory.videoTracks.joinToString { it.describeForDiagnostics() }}] " +
                        "selectedVideoTrack=$selectedVideoTrackId " +
                        "declared=$declaredVideo " +
                        "measured=${measuredVideo.describeForDiagnostics()} " +
                        "target=${target?.describe() ?: "none (no reliable cadence)"}",
                )
                container.displayMatchingCoordinator.apply(
                    policy = displayMatchingPolicy,
                    target = target,
                )

                dev.infusion.tv.playback.awaitDisplayModeApplication(container.displayMatchingCoordinator)
                player.noteInitialDisplayMatchSettled(
                    dev.infusion.tv.playback.displayMatchRevealSettled(
                        policy = displayMatchingPolicy,
                        hasVideoTracks = trackInventory.videoTracks.isNotEmpty(),
                        targetKnown = target != null,
                        requestInForce = container.displayMatchingCoordinator.requestInForce,
                    ),
                )
            }

            DisposableEffect(player) {

                onDispose {

                    val retained = playbackRetention.held
                    if (retained != null && retained.holder === player) {
                        return@onDispose
                    }

                    val continuesIntoNextItem = player.state.value?.upNext != null
                    disposeTvPlayerDestination(player)
                    if (!continuesIntoNextItem) {
                        container.displayMatchingCoordinator.release()
                    }
                }
            }

            var upNextPlayRequest by remember { mutableStateOf<dev.infusion.feature.vertical.model.EpisodeIdentityUi?>(null) }

            val nextTransition by player.nextTransition.collectAsStateWithLifecycle()
            LaunchedEffect(nextTransition?.requestId) {
                val request = nextTransition ?: return@LaunchedEffect
                val live = player.derivedUpNext
                val stillActionable = live?.nextEpisode != null &&
                    live.nextEpisode == request.nextEpisode &&
                    live.availability == UpNextAvailability.AVAILABLE
                if (stillActionable) upNextPlayRequest = request.nextEpisode
                player.acknowledgeNextTransition(request.requestId)
            }

            val subtitleAppearance by container.subtitleAppearanceStateHolder.appearance
                .collectAsStateWithLifecycle()

            val dimSubtitlesInHdr by container.hdrSubtitleDimmingStateHolder.dimming
                .collectAsStateWithLifecycle()

            val dvUiCompensation by DvUiCompensationExperiment.state.collectAsStateWithLifecycle()

            val subtitlePreferences by container.subtitlePreferenceStateHolder.preferences
                .collectAsStateWithLifecycle()

            val segmentPreferences by container.segmentEvidenceSettingsStateHolder.preferences
                .collectAsStateWithLifecycle()
            TvPlayerScreen(
                stateHolder = player,

                mediaKeysHandledBySession = {
                    container.videoPlaybackMediaSession.currentSession != null
                },

                metadata = metadata,
                subtitleAppearance = subtitleAppearance,

                dimSubtitlesInHdr = dimSubtitlesInHdr,

                dvUiCompensation = dvUiCompensation,
                subtitlePreferences = subtitlePreferences,

                segmentDisplay = segmentPreferences.displayMode,
                segmentLegendEnabled = segmentPreferences.legendEnabled,

                preparation = preparation,
                castReturnPersonKey = retainedAttempt?.castReturnPersonKey,
                onCastReturnConsumed = { retainedAttempt?.castReturnPersonKey = null },
                onOpenCastPerson = { person ->

                    val personId = person.personId
                    val playingCandidate = candidate
                    if (personId != null && playingCandidate != null) {
                        player.pause()
                        playbackRetention.retain(
                            RetainedPlaybackAttempt(
                                watchKey = watchKey,
                                holder = player,
                                candidate = playingCandidate,
                                lineage = lineage,
                                lineageSessionId = lineageSessionId,
                                castReturnPersonKey = person.activationKey,
                            ),
                        )
                        navController.navigate(TvRoutes.person(personId))
                    }
                },
                onBack = {

                    player.stop()
                    playerScope.launch(kotlinx.coroutines.Dispatchers.IO) { lineage.cancel() }
                    navController.popBackStack()
                },
                onChooseSource = {
                    player.stop()
                    playerScope.launch(kotlinx.coroutines.Dispatchers.IO) { lineage.cancel() }

                    val unitKey = if (watchKey.isEpisode) {
                        dev.infusion.core.domain.catalog.episodeUnitKey(
                            watchKey.seriesId!!,
                            watchKey.seasonNumber!!,
                            watchKey.episodeNumber!!,
                        )
                    } else {
                        TvRoutes.movieUnitKey(titleId)
                    }
                    navController.navigate(
                        TvRoutes.sourcePicker(titleId, editionId, unitKey, startMode),
                    ) {
                        launchSingleTop = true
                    }
                },
                onRetry = {
                    player.release()
                    candidate?.let {

                        playerScope.launch {

                            player.start(
                                it,
                                player.state.value?.positionMs,
                                sessionId = lineageSessionId,
                                startMode = startMode,
                            )
                        }
                    }
                },
                onPlayNext = {

                    val current = player.state.value?.upNext
                    upNextPlayRequest = current
                        ?.takeIf { it.availability == UpNextAvailability.AVAILABLE }
                        ?.nextEpisode
                },
                onDismissUpNext = {

                    player.dismissUpNext()
                    playerScope.launch(kotlinx.coroutines.Dispatchers.IO) { lineage.cancel() }
                    navController.popBackStack()
                },
            )
            upNextPlayRequest?.let { next ->
                val nextEpisodeTitleId = next.episodeTitleId
                LaunchedEffect(next, nextEpisodeTitleId) {

                    player.release()
                    startTitlePlayback(
                        container = container,
                        navController = navController,
                        titleId = nextEpisodeTitleId ?: next.seriesTitleId,
                        editionId = null,
                        unitKey = dev.infusion.core.domain.catalog.episodeUnitKey(
                            next.seriesTitleId,
                            next.seasonNumber,
                            next.episodeNumber,
                        ),
                        startMode = PlaybackStartMode.RESUME,
                        popUpToRoute = TvRoutes.PLAYER,
                        popUpToInclusive = true,
                    )
                    upNextPlayRequest = null
                }
            }
        }
            }
        }
    }

        coldStartSplash?.let { ColdStartSplash(it) }
    }
}

private enum class AddonStartupGate { Loading, Ready, Failed }

@Composable
private fun TvAddonsUnavailableSurface() {
    androidx.compose.foundation.layout.Box(
        modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            androidx.tv.material3.Text("Add-ons unavailable", style = InfusionTvScaleCalibration.Type.Emphasis)
            androidx.tv.material3.Text(
                "Your add-on sources could not be restored. Check the add-on configuration and restart.",
                style = InfusionTvScaleCalibration.Type.Body,
            )
        }
    }
}

private fun dev.infusion.feature.vertical.media.MediaCardUi.destinationPreview(
    titleId: TitleId,
): dev.infusion.core.model.MediaPreview = dev.infusion.core.model.MediaPreview(
    titleId = titleId,
    kind = if (identity.type == MediaType.SHOW) {
        dev.infusion.core.model.TitleKind.SERIES
    } else {
        dev.infusion.core.model.TitleKind.MOVIE
    },
    displayTitle = title,
    year = year,
    subtitle = null,

    artwork = imageLocator?.let {
        dev.infusion.core.model.MediaArtworkRef(it, dev.infusion.core.model.ArtworkPolicy.POSTER)
    },
    progressFraction = null,
)

internal fun dev.infusion.core.model.MediaPreview.toIdentityHint(
    type: MediaType,
): dev.infusion.core.domain.metadata.MetadataIdentityHint? {
    val usableTitle = displayTitle.trim().takeIf { it.isNotEmpty() } ?: return null
    return dev.infusion.core.domain.metadata.MetadataIdentityHint(
        type = type,
        title = usableTitle,
        year = year,
    )
}

internal fun dev.infusion.core.domain.preview.TitlePreviewHint.toIdentityHint(
    type: MediaType,
): dev.infusion.core.domain.metadata.MetadataIdentityHint? {
    val identity = identity
    val title = preview.displayTitle.trim().takeIf { it.isNotEmpty() }
    if (identity == null || identity.externalIds.isEmpty) {
        return preview.toIdentityHint(type)
    }
    return dev.infusion.core.domain.metadata.MetadataIdentityHint(
        type = type,

        externalIds = identity.externalIds,
        title = title,
        year = preview.year,
    )
}

private fun String?.decodeTrailerSegment(): String =
    this?.let { runCatching { TvRoutes.decodeNavSegment(it) }.getOrDefault(it) }.orEmpty()

private fun musicQueueContextOf(
    holder: dev.infusion.feature.vertical.jellyfin.library.LibraryBrowseStateHolder,
    container: AppContainer,
): MusicQueueContext? {
    val sourceId = container.jellyfinSource?.sourceId ?: return null
    return holder.state.value.window.key.musicQueueContext(sourceId)
}

private fun dev.infusion.source.jellyfin.JellyfinSourceCoordinator.ConnectionTestResult.toOutcome(): JellyfinConnectOutcome =
    when (this) {
        is dev.infusion.source.jellyfin.JellyfinSourceCoordinator.ConnectionTestResult.Ok ->
            JellyfinConnectOutcome.Ok(
                userId = userId,
                userName = userName,
                avatarTag = avatarTag,
                versionLabel = serverVersion,
                qualificationLabel = qualificationLabel,
            )
        is dev.infusion.source.jellyfin.JellyfinSourceCoordinator.ConnectionTestResult.Failed ->
            JellyfinConnectOutcome.Failed(message)
        is dev.infusion.source.jellyfin.JellyfinSourceCoordinator.ConnectionTestResult.Invalid ->
            JellyfinConnectOutcome.Invalid(reason)
    }

private fun dev.infusion.source.jellyfin.SetupResult.toOutcome(): JellyfinConnectOutcome =
    when (this) {
        is dev.infusion.source.jellyfin.SetupResult.Ok -> {
            val session = graph.source.session()
            JellyfinConnectOutcome.Ok(
                userId = session?.userId,
                userName = session?.userName,
                avatarTag = session?.avatarTag,
                versionLabel = graph.source.serverVersion,
                qualificationLabel = graph.source.versionQualification?.name,
            )
        }
        is dev.infusion.source.jellyfin.SetupResult.Failed -> JellyfinConnectOutcome.Failed(failure.message)
        is dev.infusion.source.jellyfin.SetupResult.Invalid -> JellyfinConnectOutcome.Invalid(reason)
    }
