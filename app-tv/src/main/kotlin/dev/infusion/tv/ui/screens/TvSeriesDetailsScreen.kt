@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package dev.infusion.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.infusion.core.artworkimage.ArtworkImage
import dev.infusion.core.artworkimage.ArtworkRequest
import dev.infusion.core.domain.format.DurationFormat
import dev.infusion.core.model.ArtworkLocator
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.tv.ui.focus.LocalTvZoneEntry
import dev.infusion.tv.ui.focus.rememberTvZoneEntry
import dev.infusion.tv.ui.presentation.InfusionCardAvailabilityBadge
import dev.infusion.tv.ui.presentation.InfusionCardContextMenuHint
import dev.infusion.tv.ui.presentation.centredBrowseBringIntoViewSpec
import dev.infusion.tv.ui.presentation.InfusionCardWatchlistBadge
import dev.infusion.tv.ui.presentation.InfusionCardWatchedMark
import dev.infusion.tv.ui.presentation.LocalCardAvailability
import dev.infusion.tv.ui.presentation.LocalCardWatchedState
import dev.infusion.tv.ui.presentation.LocalCardWatchlistMembership
import dev.infusion.tv.ui.presentation.LocalContentCardPresentation
import dev.infusion.tv.ui.presentation.artworkTargetPx
import dev.infusion.tv.ui.presentation.cardWatchedUnitOrNull
import dev.infusion.tv.ui.presentation.contentCardArtworkTarget
import dev.infusion.tv.ui.presentation.contentCardAvailabilityBadgeKey
import dev.infusion.tv.ui.presentation.contentCardContextMenuHintKey
import dev.infusion.tv.ui.presentation.contentCardWatchedMarkKey
import dev.infusion.tv.ui.presentation.contentCardWatchlistBadgeKey
import dev.infusion.tv.ui.presentation.contextMenuHintVisible
import dev.infusion.tv.ui.presentation.hideWatchedEpisodeRows
import dev.infusion.tv.ui.presentation.hideWatchedHierarchyEpisodeRows
import dev.infusion.tv.ui.presentation.hideWatchedMediaCards
import dev.infusion.tv.ui.presentation.cardContextActionModifier
import dev.infusion.tv.ui.presentation.rememberCardActionRequest
import dev.infusion.tv.ui.presentation.restoreRevealTarget
import dev.infusion.tv.ui.presentation.stageOwnedVerticalFramingSpec
import dev.infusion.tv.ui.presentation.LocalTvRowNavigation
import dev.infusion.tv.ui.focus.RequestFocusOnKey
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.presentation.jellyfinAvailableFor
import dev.infusion.tv.ui.presentation.watchedMarkUnitFor
import dev.infusion.tv.ui.presentation.watchlistBadgeFor
import dev.infusion.tv.ui.presentation.watchlistEntryKey
import dev.infusion.tv.ui.theme.InfusionTvFilterChip
import dev.infusion.tv.ui.theme.InfusionTvDetailsMetrics
import dev.infusion.tv.ui.theme.InfusionTvHeroContextualAction
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import androidx.tv.material3.Card
import androidx.tv.material3.Text
import dev.infusion.core.domain.catalog.CatalogImage
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.domain.metadata.MetadataSection
import dev.infusion.feature.vertical.media.CanonicalDetailsStateHolder
import dev.infusion.feature.vertical.media.CanonicalDetailsUiState
import dev.infusion.feature.vertical.media.CompanyLogoUi
import dev.infusion.feature.vertical.media.MediaCardUi
import dev.infusion.feature.vertical.media.PersonCardUi
import dev.infusion.feature.vertical.media.SectionState
import dev.infusion.feature.vertical.media.TrailerUi
import dev.infusion.feature.vertical.media.episodePlayability
import dev.infusion.feature.vertical.media.playableEpisodeRows
import dev.infusion.feature.vertical.details.SeriesDetailsStateHolder
import dev.infusion.feature.vertical.model.EpisodeIdentityUi
import dev.infusion.feature.vertical.model.EpisodeRowUi
import dev.infusion.feature.vertical.model.DetailsArtwork
import dev.infusion.feature.vertical.model.SeasonEpisodesPhase
import dev.infusion.feature.vertical.model.SeriesDetailsUiState
import dev.infusion.feature.vertical.model.SeriesHierarchyPhase
import dev.infusion.feature.vertical.model.WatchedDisplay
import dev.infusion.tv.ui.focus.RequestFocusOnKey
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.presentation.ContentCardPresentation
import dev.infusion.tv.ui.presentation.tvBrowseNonRailInset
import dev.infusion.tv.ui.presentation.tvRailContentPadding
import dev.infusion.tv.ui.theme.infusionTvCardBorder
import dev.infusion.tv.ui.theme.infusionTvCardColors
import dev.infusion.tv.ui.theme.infusionTvCardScale
import dev.infusion.tv.ui.theme.infusionTvCardShape
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val EpisodeStillWidth = 192.dp
private val EpisodeStillHeight = EpisodeStillWidth / ContentCardPresentation.LANDSCAPE_ASPECT_RATIO

@Composable
fun TvSeriesDetailsScreen(
    stateHolder: SeriesDetailsStateHolder,
    onOpenEpisode: (season: Int, episode: Int, episodeTitleId: TitleId) -> Unit,
    onBack: () -> Unit,

    canonicalHolder: CanonicalDetailsStateHolder? = null,

    onOpenCanonicalCard: (MediaCardUi) -> Unit = {},

    onOpenCanonicalCardOccurrence: ((MediaCardUi, String) -> Unit)? = null,

    onOpenCanonicalPerson: (PersonCardUi, focusOccurrenceKey: String) -> Unit = { _, _ -> },

    onOpenCollection: (Long) -> Unit = {},

    onOpenCompany: (CompanyLogoUi) -> Unit = {},

    focusRestoreKey: String? = null,

    restoreSeasonNumber: Int? = null,

    onTrailerClick: (TrailerUi) -> Unit = {},
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    val canonical: CanonicalDetailsUiState? = if (canonicalHolder != null) {
        canonicalHolder.state.collectAsStateWithLifecycle().value
    } else {
        null
    }

    val canonicalHeroOwnsFacts = canonical?.hasHero == true

    val episodePlayability = remember(state.episodesByKey, state.titleId) {
        state.episodePlayability(state.titleId)
    }
    val canonicalEpisodeRows = canonical?.playableEpisodeRows(episodePlayability).orEmpty()

    val hideWatchedTitles = LocalContentCardPresentation.current.hideWatchedTitles
    val cardWatchedState = LocalCardWatchedState.current
    val visibleEpisodeRows = canonical?.let { state ->
        hideWatchedEpisodeRows(
            rows = canonicalEpisodeRows,
            hideWatchedTitles = hideWatchedTitles,
            watchedState = cardWatchedState,
            seriesTitleId = state.titleId,
        )
    }.orEmpty()
    val visibleCollectionMembers = canonical?.collection?.valueOrNull()?.let { collection ->
        hideWatchedMediaCards(
            cards = collection.members,
            hideWatchedTitles = hideWatchedTitles,
            watchedState = cardWatchedState,
        )
    }.orEmpty()

    val canonicalSeasonsPrimary = canonical?.hasSeasons == true
    val requesters = rememberFocusRequesters()

    val directorsBandKey =
        if (canonicalSeasonsPrimary) DetailsStageTwoRegionKeys.DIRECTORS else null
    val creatorsBandKey =
        if (canonicalSeasonsPrimary) DetailsStageTwoRegionKeys.CREATORS else null
    val castBandKey = if (canonicalSeasonsPrimary) DetailsStageTwoRegionKeys.CAST else null

    val openPersonOccurrence: (PersonCardUi, String) -> Unit = { person, occurrenceKey ->
        onOpenCanonicalPerson(person, occurrenceKey)
    }

    val stageTwoRegions = remember(
        canonical,
        visibleEpisodeRows,
        visibleCollectionMembers,
        canonicalSeasonsPrimary,
    ) {
        if (canonicalSeasonsPrimary) {
            detailsStageTwoRegions(canonical, visibleEpisodeRows, visibleCollectionMembers)
        } else {
            emptyList()
        }
    }
    val episodeParentChipKeys = remember(canonical, visibleEpisodeRows) {
        detailsEpisodeParentChipKeys(canonical, visibleEpisodeRows)
    }
    val stageTwoHierarchy = rememberDetailsStageTwoHierarchy(
        regions = stageTwoRegions,
        episodeParentChipKeys = episodeParentChipKeys,
        requesters = requesters,
    )

    val zone = rememberTvZoneEntry(requesters)

    val continuationSeason = state.nextUnwatched?.seasonNumber
    LaunchedEffect(continuationSeason, canonicalSeasonsPrimary, canonicalHolder) {
        if (canonicalSeasonsPrimary && continuationSeason != null) {
            canonicalHolder?.openContinuationSeason(continuationSeason)
        }
    }

    LaunchedEffect(restoreSeasonNumber, canonicalSeasonsPrimary, canonicalHolder) {
        if (canonicalSeasonsPrimary && restoreSeasonNumber != null) {
            val holder = canonicalHolder ?: return@LaunchedEffect

            if (holder.state.value.selectedSeasonNumber != restoreSeasonNumber) {
                holder.selectSeason(restoreSeasonNumber)
            }
        }
    }

    val watchlistAction = rememberDetailsWatchlistAction(
        kind = TitleKind.SERIES,
        titleId = state.titleId,
        displayTitle = state.title.orEmpty(),
    )
    val seriesActions = seriesStageActions(
        hasContinuationEpisode = state.nextUnwatched?.episodeTitleId != null,
        hasTrailer = canonical?.trailer != null,

        nextEpisodeCode = state.nextUnwatched
            ?.takeIf { it.episodeTitleId != null }
            ?.let { episodeCoordinateCode(it.seasonNumber, it.episodeNumber) },
        watchlisted = watchlistAction.watchlisted,
    )
    val entryTargetKey = seriesEntryFocusKey(
        continuationEpisodeResolved = state.nextUnwatched?.episodeTitleId != null,
        hierarchyLoading = state.hierarchy is SeriesHierarchyPhase.Loading,
        actionLineEntryKey = seriesActionLineEntryKey(seriesActions),
    )

    val stageOneFallbackKey = seriesStageOneFallbackKey(
        hierarchyLoading = state.hierarchy is SeriesHierarchyPhase.Loading,
        canonicalSeasonsPrimary = canonicalSeasonsPrimary,
        canonicalSeasonFocusKey = canonical?.initialSeasonFocusKey,
        sourceFocusKey = state.initialFocusKey,
    )

    val scrollState = rememberScrollState()
    var heroHeightPx by remember { mutableIntStateOf(0) }
    var seasonsSectionTopPx by remember { mutableIntStateOf(0) }
    var seasonsSectionHeightPx by remember { mutableIntStateOf(0) }
    var episodeRowTopPx by remember { mutableIntStateOf(0) }
    var episodeRowPx by remember { mutableIntStateOf(0) }

    var viewportMeasuredPx by remember { mutableIntStateOf(0) }

    var viewportTopInRootPx by remember { mutableIntStateOf(0) }

    var departureScrollPx by rememberSaveable { mutableIntStateOf(0) }
    DisposableEffect(scrollState) {
        onDispose { departureScrollPx = scrollState.value }
    }
    LaunchedEffect(focusRestoreKey, scrollState) {
        if (focusRestoreKey == null || restoreSeasonNumber != null) return@LaunchedEffect
        var restored = false
        snapshotFlow { scrollState.maxValue }.collect { maxValue ->
            if (restored) return@collect
            val target = departureScrollPx.coerceAtMost(maxValue)
            if (target > scrollState.value) {
                scrollState.dispatchRawDelta((target - scrollState.value).toFloat())
            }
            if (scrollState.value >= departureScrollPx) restored = true
        }
    }
    val density = LocalDensity.current
    val stageItemSpacingPx = with(density) { TvDetailsStageItemSpacing.roundToPx() }
    val nextStageTopPx = heroHeightPx + stageItemSpacingPx

    val nextStageTopByState by rememberUpdatedState(nextStageTopPx)

    val episodeRowComposed = seriesSeasonsComposeEpisodeRow(
        canonicalSeasonsPrimary = canonicalSeasonsPrimary,

        canonicalEpisodesReady = (canonical?.episodes as? SectionState.Ready)?.value?.isNotEmpty() == true,
        sourceHierarchyLoaded = state.hierarchy is SeriesHierarchyPhase.Loaded,
        sourceEpisodesLoaded = state.episodesPhase is SeasonEpisodesPhase.Loaded,
        sourceEpisodeCount = state.episodes.size,
    )
    val episodeRowComposedNow = rememberUpdatedState(episodeRowComposed)
    val focusRestoreKeyNow = rememberUpdatedState(focusRestoreKey)

    val canonicalSeasonOpening = canonical?.episodes is SectionState.Absent &&
        continuationSeason != null &&
        canonical?.seasons?.any { it.seasonNumber == continuationSeason } == true
    val episodeRowPending = seriesSeasonsEpisodeRowPending(
        canonicalSeasonsPrimary = canonicalSeasonsPrimary,
        canonicalEpisodesLoading = canonical?.episodes is SectionState.Loading,
        canonicalSeasonOpening = canonicalSeasonOpening,
        sourceHierarchyLoading = state.hierarchy is SeriesHierarchyPhase.Loading,
        sourceEpisodesLoading = state.episodesPhase is SeasonEpisodesPhase.Loading,
    )

    val atRestFramingEstablished = seriesAtRestFramingEstablished(
        stageOneBlockPx = heroHeightPx,
        viewportPx = viewportMeasuredPx,
        seasonsSectionMeasured = seasonsSectionTopPx > 0,
        episodeRowPending = episodeRowPending,
        episodeRowComposed = episodeRowComposed,
        episodeRowMeasured = episodeRowPx > 0,
    )

    var entryClaimConsumed by remember { mutableStateOf(false) }

    val entryTargetKeyNow = rememberUpdatedState(entryTargetKey)
    LaunchedEffect(requesters) {

        snapshotFlow { requesters.lastFocusedKey }
            .first { focused -> focused != null && focused == entryTargetKeyNow.value }
        entryClaimConsumed = true
    }
    val entryClaimKey = if (atRestFramingEstablished && !entryClaimConsumed) entryTargetKey else null
    val entryClaimKeyNow = rememberUpdatedState(entryClaimKey)
    val atRestFramingEstablishedNow = rememberUpdatedState(atRestFramingEstablished)

    val restoreSeasonNumberNow = rememberUpdatedState(restoreSeasonNumber)

    var entryFramingEstablished by remember { mutableStateOf(false) }

    RequestFocusOnKey(
        key = focusRestoreKey ?: entryClaimKey,
        requesters = requesters,

        restoration = focusRestoreKey != null,
        before = {

            if (focusRestoreKeyNow.value != null && !entryFramingEstablished) {

                if (restoreSeasonNumberNow.value != null) {
                    entryFramingEstablished = true

                    snapshotFlow {
                        atRestFramingEstablishedNow.value && nextStageTopByState > 0
                    }.first { it }
                    scrollState.scrollTo(nextStageTopByState)
                }
            } else if (focusRestoreKeyNow.value == null && !entryFramingEstablished) {
                when (
                    seriesEntryFraming(
                        entryKey = entryClaimKeyNow.value,
                        episodeRowComposed = episodeRowComposedNow.value,
                    )
                ) {
                    SeriesEntryFraming.STAGE_TWO -> {
                        entryFramingEstablished = true
                        scrollState.scrollTo(nextStageTopByState)
                    }

                    SeriesEntryFraming.AT_REST -> {
                        entryFramingEstablished = true
                        scrollState.scrollTo(0)
                    }

                    null -> Unit
                }
            }
        },
    )

    LaunchedEffect(restoreSeasonNumber, focusRestoreKey) {
        val restoredKey = focusRestoreKey ?: return@LaunchedEffect
        if (restoreSeasonNumber == null) return@LaunchedEffect

        snapshotFlow { requesters.lastFocusedKey == restoredKey }.first { it }

        snapshotFlow { (requesters.lastFocusedKey == restoredKey) to nextStageTopByState }
            .takeWhile { (restorationHolds, _) -> restorationHolds }
            .collect { (_, anchor) ->
                if (anchor > 0) {

                    val target = anchor.coerceIn(0, scrollState.maxValue)
                    scrollState.dispatchRawDelta((target - scrollState.value).toFloat())
                }
            }
    }

    val scrolledPastHero by remember(scrollState) {

        derivedStateOf { isScrolledPastHero(scrollState.value, heroHeightPx) }
    }

    val heroArtwork = remember(state.artworkBytes) { DetailsArtwork(bytes = state.artworkBytes) }

    val scope = rememberCoroutineScope()

    val revealRestoredRow: (topPx: Int, heightPx: Int) -> Unit = { topPx, heightPx ->
        if (focusRestoreKey != null && requesters.focusedKey == focusRestoreKey) {
            val target = restoreRevealTarget(
                rowTopPx = topPx,
                rowHeightPx = heightPx,
                viewportTopPx = viewportTopInRootPx,
                viewportHeightPx = viewportMeasuredPx,
                scrollPx = scrollState.value,
                maxScrollPx = scrollState.maxValue,
            )
            if (target != null && target != scrollState.value) {

                scrollState.dispatchRawDelta((target - scrollState.value).toFloat())
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TvDetailsHeroCanvas(
            hero = canonical?.hero,

            primarySettled = canonical?.primarySettled == true,
            underlay = heroArtwork,
            scrolledPastHero = scrolledPastHero,

            posterPresentation = HeroPosterPresentation.DetailsEdge,
        )

        @OptIn(ExperimentalFoundationApi::class)
        BoxWithConstraints(

            modifier = Modifier
                .fillMaxSize()

                .onGloballyPositioned {
                    viewportMeasuredPx = it.size.height
                    viewportTopInRootPx = it.positionInRoot().y.roundToInt()
                },
        ) {

            val viewportHeight = maxHeight
            val viewportPx = with(density) { maxHeight.roundToPx() }

            val episodeRowGapPx = if (episodeRowTopPx > 0 && seasonsSectionTopPx > 0) {
                (episodeRowTopPx - seasonsSectionTopPx).coerceAtLeast(0) + stageItemSpacingPx
            } else {

                0
            }
            val stageHeight = with(density) {
                SeriesStageOneComposition.stageHeightPx(
                    viewportPx = viewportPx,
                    episodeRowGapPx = episodeRowGapPx,
                    episodeRowPx = episodeRowPx,
                ).toDp()
            }

            val actionRowLeadIn = SeriesStageOneComposition.actionRowLeadIn(
                lineHeight = with(density) { InfusionTvScaleCalibration.Type.Body.lineHeight.toDp() },
                freeSpaceBelowRow = InfusionTvDetailsMetrics.HeroContentBottomPadding,
            )

            val heroContentBottomPadding = if (seriesActions.isNotEmpty()) {
                InfusionTvDetailsMetrics.HeroContentBottomPadding - actionRowLeadIn
            } else {
                InfusionTvDetailsMetrics.HeroContentBottomPadding
            }

            val nextStageEntryKey = if (canonicalSeasonsPrimary) {
                canonical.focusedSeasonNumber?.let(::seasonFocusKey)
            } else {
                state.selectedSeasonNumber?.let(::seasonFocusKey)
            }

            val viewportPxByState by rememberUpdatedState(viewportPx)

            val nextStageAnchorKnown by rememberUpdatedState(heroHeightPx > 0)

            val stageTwoPresented by remember(scrollState) {
                derivedStateOf {

                    val scroll = if (focusRestoreKey != null && requesters.restorationKey != null) {
                        departureScrollPx
                    } else {
                        scrollState.value
                    }
                    nextStageAnchorKnown && scroll >= nextStageTopByState
                }
            }

            val ambientBringIntoViewSpec = LocalBringIntoViewSpec.current

            val pageBringIntoViewSpec = remember(ambientBringIntoViewSpec) {
                stageOwnedVerticalFramingSpec(
                    ambient = ambientBringIntoViewSpec,
                    earlierStageBottomPx = { heroHeightPx - scrollState.value.toFloat() },
                    stageBottomPx = { viewportPxByState.toFloat() },
                    stageIsPresented = {
                        val scroll = scrollState.value
                        scroll == 0 || (nextStageAnchorKnown && scroll >= nextStageTopByState)
                    },
                    atRestAnchorDistancePx = { -scrollState.value.toFloat() },

                    stillEstablishingAtRest = {
                        !atRestFramingEstablishedNow.value && scrollState.value == 0
                    },

                    returnInProgress = {
                        focusRestoreKeyNow.value != null && requesters.restorationKey != null
                    },

                    laterStageTopBandBottomPx = {
                        val sectionBottomPx = seasonsSectionTopPx + seasonsSectionHeightPx
                        if (seasonsSectionHeightPx <= 0) {

                            Float.NEGATIVE_INFINITY
                        } else {
                            sectionBottomPx - scrollState.value.toFloat()
                        }
                    },
                    laterStageAnchorDistancePx = {
                        nextStageTopByState - scrollState.value.toFloat()
                    },
                )
            }
            CompositionLocalProvider(
                LocalBringIntoViewSpec provides pageBringIntoViewSpec,

                LocalTvZoneEntry provides zone,
                LocalTvRowNavigation provides stageTwoHierarchy,
            ) {

            val focusedKeyNow = requesters.focusedKey
            val backParentKey = stageTwoHierarchy?.backToParent(focusedKeyNow)
            val stageOneReturnKey = requesters.firstLive(
                SeriesNextActionKey,
                TrailerActionKey,
            )?.takeIf { stageTwoHierarchy?.regionOf(it) == null }

            val backStep = seriesBackStep(
                focusedInRegion = stageTwoHierarchy?.regionOf(focusedKeyNow) != null,
                parentChipKey = backParentKey,
                stageOneControlKey = stageOneReturnKey,
            )
            BackHandler(enabled = backStep != SeriesBackStep.LEAVE) {
                when (backStep) {
                    SeriesBackStep.PARENT_CHIP -> {
                        if (backParentKey != null && !requesters.request(backParentKey)) {
                            requesters.arm(backParentKey)
                        }
                    }

                    SeriesBackStep.STAGE_ONE -> scope.launch {
                        scrollState.scrollTo(0)
                        requesters.requestFirst(SeriesNextActionKey, TrailerActionKey, stageOneFallbackKey)
                    }

                    SeriesBackStep.LEAVE -> Unit
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()

                    .verticalScroll(scrollState)

                    .padding(bottom = TvDetailsStageItemSpacing),
                verticalArrangement = Arrangement.spacedBy(TvDetailsStageItemSpacing),
            ) {

                CompositionLocalProvider(LocalBringIntoViewSpec provides ambientBringIntoViewSpec) {

                Box(modifier = Modifier.testTag(SeriesStageOneComposition.StageOneTag)) {
                TvCanonicalHero(
                    hero = canonical?.hero,
                    titleFallback = state.title,
                    modifier = Modifier

                        .padding(horizontal = TvDetailsPageHorizontalPadding)
                        .onSizeChanged { heroHeightPx = it.height },
                    minHeight = stageHeight,

                    logoWidthFractionOfScreen = InfusionTvScaleCalibration.ClearLogo.MaxScreenWidthFraction,

                    contentBottomPadding = heroContentBottomPadding,
                ) {

                    DetailsStageGenre(
                        canonical = canonical,
                        canonicalHeroOwnsFacts = canonicalHeroOwnsFacts,
                        sourceGenres = state.genres,
                    )

                    canonical?.let { heroState -> TvCanonicalHeroTagline(heroState) }

                    DetailsOverview(synopsis = state.synopsis, canonical = canonical)

                    val stageOneRatingItems = if (canonicalHeroOwnsFacts) {
                        detailsRatingItems(canonical.hero?.ratings.orEmpty())
                    } else {
                        legacyRatingItems(state.rating)
                    }
                    DetailsStageMetadataLine(
                        canonical = canonical,
                        canonicalHeroOwnsFacts = canonicalHeroOwnsFacts,
                        sourceFacts = seriesStageSourceFacts(state, canonicalHeroOwnsFacts),

                        selectedEditionLabel = null,

                        trailingFact = canonical?.hero?.status,

                        ratingItems = emptyList(),
                    )
                    canonical?.let { heroState -> TvCanonicalHeroQuietFacts(heroState) }

                    if (stageOneRatingItems.isNotEmpty()) {
                        DetailsStageOneRatingsRow(items = stageOneRatingItems)
                    }

                    if (seriesActions.isNotEmpty()) {

                        Box(modifier = Modifier.padding(top = actionRowLeadIn)) {
                            DetailsStageOneActionLine(
                                items = seriesActions,
                                lineTag = SeriesActionLineTag,
                                requesters = requesters,
                                onPrimaryAction = {
                                    state.nextUnwatched?.let { next ->
                                        next.episodeTitleId?.let { episodeTitleId ->
                                            onOpenEpisode(next.seasonNumber, next.episodeNumber, episodeTitleId)
                                        }
                                    }
                                },

                                onRestart = {},
                                onSeen = {},
                                onNotSeen = {},
                                onSources = {},
                                onTrailer = { canonical?.trailer?.let(onTrailerClick) },

                                onWatchlist = watchlistAction.onToggle,

                                onStageDown = nextStageEntryKey?.let { entryKey ->
                                    {
                                        scope.launch {
                                            scrollState.scrollTo(nextStageTopPx)
                                            if (!requesters.request(entryKey)) {
                                                requesters.arm(entryKey)
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                }

            DetailsStageTwoBlock(

                hero = canonical?.hero,

                minHeight = viewportHeight,

                stageHeadPresented = {
                    scrollState.value <= nextStageTopByState + StageTwoHeadOnScreenTolerancePx
                },

                onReturnToStageOne = {
                    scope.launch {
                        scrollState.scrollTo(0)
                        requesters.requestFirst(SeriesNextActionKey, TrailerActionKey, stageOneFallbackKey)
                    }
                },

                headInFlow = stageTwoPresented,

                regionNavigation = stageTwoHierarchy,
                stageTwoTopBoundary = {
                    stageTwoHierarchy?.atStageTwoTopBoundary(requesters.lastFocusedKey) == true
                },
            ) {

                Column(
                    modifier = seasonsSectionTopProbe(scrollState) { topPx, heightPx ->
                        seasonsSectionTopPx = topPx
                        seasonsSectionHeightPx = heightPx
                    },
                ) {
                if (canonical != null && canonicalSeasonsPrimary) {
                    TvCanonicalSeasonsSection(
                        state = canonical,

                        rows = visibleEpisodeRows,
                        requesters = requesters,
                        onSelectSeason = { season -> canonicalHolder?.selectSeason(season) },
                        onOpenEpisode = { row ->

                            onOpenEpisode(
                                row.episode.seasonNumber,
                                row.episode.episodeNumber,
                                row.playableTitleId ?: state.titleId,
                            )
                        },
                        onRetry = { canonicalHolder?.retry(MetadataSection.SEASONS) },
                        onEpisodeFocused = { season, key -> canonicalHolder?.onEpisodeFocused(season, key) },

                        rowModifier = episodeRowProbe(scrollState) { topPx, heightPx ->
                            episodeRowTopPx = topPx
                            episodeRowPx = heightPx
                        },
                    )

                        (state.hierarchy as? SeriesHierarchyPhase.Failed)?.let { failed ->
                            Row(
                                modifier = Modifier.tvBrowseNonRailInset(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(failed.message, style = InfusionTvScaleCalibration.Type.Body)
                                InfusionTvHeroContextualAction(
                                    onClick = { stateHolder.retry() },
                                    icon = Icons.Filled.Refresh,
                                    modifier = stableFocusModifier(
                                        key = "retry",
                                        requesters = requesters,
                                        onFocusedKeyChanged = {},
                                    ),
                                ) { Text("Retry") }
                            }
                        }
                    } else {
                        TvSourceSeasonsSection(
                            state = state,
                            requesters = requesters,
                            onRetryHierarchy = { stateHolder.retry() },
                            onSelectSeason = { season -> stateHolder.selectSeason(season) },
                            onOpenEpisode = onOpenEpisode,
                            rowModifier = episodeRowProbe(scrollState) { topPx, heightPx ->
                                episodeRowTopPx = topPx
                                episodeRowPx = heightPx
                            },
                        )
                    }
                }

                if (canonical != null) {
                    TvCanonicalReviewsSection(
                        section = canonical.reviews,
                        requesters = requesters,
                        onRetry = { section -> canonicalHolder?.retry(section) },
                        onLoadMore = { canonicalHolder?.loadMoreReviews() },
                        hasMore = canonical.reviewsHasMore,
                        pagingFailure = canonical.reviewsPagingFailure,

                        contentAlignmentInset = TvDetailsPageHorizontalPadding,
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(TvDetailsStageItemSpacing),
                ) {
                    canonical?.takeIf { it.hasHero }?.let { heroState ->
                        TvCanonicalCredits(
                            state = heroState,
                            requesters = requesters,

                            onOpenPersonOccurrence = openPersonOccurrence,
                            modifier = Modifier.tvBrowseNonRailInset(),
                            directorsBandKey = directorsBandKey,
                            creatorsBandKey = creatorsBandKey,
                        )
                    }

                if (canonical != null) {
                    TvCanonicalCastRow(
                        cast = canonical.cast,
                        requesters = requesters,
                        onOpenPersonOccurrence = openPersonOccurrence,
                        bandKey = castBandKey,
                    )

                    TvCanonicalStudiosNetworks(
                        canonical,
                        modifier = Modifier.tvBrowseNonRailInset(),
                        requesters = requesters,
                        onOpenEntity = onOpenCompany,
                    )

                    TvCanonicalCollectionSection(
                        collectionName = canonical.collectionName,
                        collectionId = canonical.collectionId,
                        collection = canonical.collection,
                        requesters = requesters,
                        onOpenCollection = onOpenCollection,
                        onOpenCard = onOpenCanonicalCard,
                        onRetry = { section -> canonicalHolder?.retry(section) },
                    )
                }

                    if (canonical != null) {
                        TvCanonicalCardRow(
                            title = "Recommended",
                            focusPrefix = "canonical:recommendations",
                            section = canonical.recommendations,
                            requesters = requesters,
                            sectionId = MetadataSection.RECOMMENDATIONS,
                            onOpenCard = onOpenCanonicalCard,

                            onOpenCardOccurrence = onOpenCanonicalCardOccurrence,
                            onRetry = { section -> canonicalHolder?.retry(section) },
                            onLoadMore = { canonicalHolder?.loadMoreRecommendations() },
                            hasMore = canonical.recommendationsHasMore,
                            pagingFailure = canonical.recommendationsPagingFailure,

                            restoreFocusKey = focusRestoreKey,
                            onRestoredRowBounds = revealRestoredRow,
                        )
                        TvCanonicalCardRow(
                            title = "Similar",
                            focusPrefix = "canonical:similar",
                            section = canonical.similar,
                            requesters = requesters,
                            sectionId = MetadataSection.SIMILAR,
                            onOpenCard = onOpenCanonicalCard,
                            onOpenCardOccurrence = onOpenCanonicalCardOccurrence,
                            onRetry = { section -> canonicalHolder?.retry(section) },
                            onLoadMore = { canonicalHolder?.loadMoreSimilar() },
                            hasMore = canonical.similarHasMore,
                            pagingFailure = canonical.similarPagingFailure,
                            restoreFocusKey = focusRestoreKey,
                            onRestoredRowBounds = revealRestoredRow,
                        )
                    }

                }
            }
                }
            }
            }
        }
    }
}

internal fun seriesEntryFocusKey(
    continuationEpisodeResolved: Boolean,
    hierarchyLoading: Boolean,
    actionLineEntryKey: String?,
): String? = when {
    continuationEpisodeResolved -> SeriesNextActionKey
    hierarchyLoading -> null
    else -> actionLineEntryKey
}

internal fun seriesActionLineEntryKey(actions: List<DetailsStageActionItem>): String? =
    actions.firstOrNull()?.key

internal fun seriesStageOneFallbackKey(
    hierarchyLoading: Boolean,
    canonicalSeasonsPrimary: Boolean,
    canonicalSeasonFocusKey: String?,
    sourceFocusKey: String?,
): String? = when {
    hierarchyLoading -> null
    canonicalSeasonsPrimary -> canonicalSeasonFocusKey ?: sourceFocusKey
    else -> sourceFocusKey
}

internal fun seriesStageSourceFacts(
    state: SeriesDetailsUiState,
    canonicalHeroOwnsFacts: Boolean,
): List<String> = listOfNotNull(
    if (canonicalHeroOwnsFacts) null else state.year?.toString(),
)

private fun seasonFocusKey(seasonNumber: Int): String = "season:$seasonNumber"

private fun episodeRowProbe(
    scrollState: androidx.compose.foundation.ScrollState,
    onMeasured: (topPx: Int, heightPx: Int) -> Unit,
): Modifier = Modifier.onGloballyPositioned { coordinates ->
    onMeasured(
        (coordinates.positionInRoot().y + scrollState.value).roundToInt(),
        coordinates.size.height,
    )
}

private fun seasonsSectionTopProbe(
    scrollState: androidx.compose.foundation.ScrollState,
    onMeasured: (topPx: Int, heightPx: Int) -> Unit,
): Modifier = Modifier.onGloballyPositioned { coordinates ->
    onMeasured(
        (coordinates.positionInRoot().y + scrollState.value).roundToInt(),
        coordinates.size.height,
    )
}

@Composable
private fun TvSourceSeasonsSection(
    state: dev.infusion.feature.vertical.model.SeriesDetailsUiState,
    requesters: MutableMap<String, androidx.compose.ui.focus.FocusRequester>,
    onRetryHierarchy: () -> Unit,
    onSelectSeason: (Int) -> Unit,
    onOpenEpisode: (season: Int, episode: Int, episodeTitleId: TitleId) -> Unit,
    rowModifier: Modifier = Modifier,
) {
    when (state.hierarchy) {
        SeriesHierarchyPhase.Loading -> Text(
            "Loading seasons…",
            style = InfusionTvScaleCalibration.Type.Body,
            modifier = Modifier.tvBrowseNonRailInset(),
        )

        SeriesHierarchyPhase.Empty -> Text(
            "No seasons.",
            style = InfusionTvScaleCalibration.Type.Body,
            modifier = Modifier.tvBrowseNonRailInset(),
        )

        is SeriesHierarchyPhase.Failed -> {
            Text(
                (state.hierarchy as SeriesHierarchyPhase.Failed).message,
                style = InfusionTvScaleCalibration.Type.Body,
                modifier = Modifier.tvBrowseNonRailInset(),
            )
            InfusionTvHeroContextualAction(
                onClick = onRetryHierarchy,
                icon = Icons.Filled.Refresh,
                modifier = stableFocusModifier(
                    key = "retry",
                    requesters = requesters,
                    onFocusedKeyChanged = {},
                ).tvBrowseNonRailInset(),
            ) { Text("Retry") }
        }

        SeriesHierarchyPhase.Loaded -> {

            LazyRow(
                modifier = Modifier.tvBrowseNonRailInset().testTag(DetailsSeasonStripTag),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.seasons, key = { it.key }) { season ->
                    InfusionTvFilterChip(
                        selected = season.seasonNumber == state.selectedSeasonNumber,
                        onClick = {
                            onSelectSeason(season.seasonNumber)
                        },
                        modifier = stableFocusModifier(
                            key = season.key,
                            requesters = requesters,
                            onFocusedKeyChanged = {},
                        ),
                    ) {
                        Text(
                            season.title ?: "Season ${season.seasonNumber}",
                            style = InfusionTvScaleCalibration.Type.Emphasis,
                        )
                    }
                }
            }

            when (val phase = state.episodesPhase) {
                SeasonEpisodesPhase.Loading -> Text(
                    "Loading episodes…",
                    style = InfusionTvScaleCalibration.Type.Body,
                )

                SeasonEpisodesPhase.Empty -> Text(
                    "No episodes in this season.",
                    style = InfusionTvScaleCalibration.Type.Body,
                )

                is SeasonEpisodesPhase.Failed -> Text(
                    phase.message,
                    style = InfusionTvScaleCalibration.Type.Body,
                )

                SeasonEpisodesPhase.Loaded -> {

                    val visibleEpisodes = hideWatchedHierarchyEpisodeRows(
                        rows = state.episodes,
                        hideWatchedTitles = LocalContentCardPresentation.current.hideWatchedTitles,
                        watchedState = LocalCardWatchedState.current,
                        seriesTitleId = state.titleId,
                    )
                    if (visibleEpisodes.isEmpty()) {
                        Text(
                            "No episodes in this season.",
                            style = InfusionTvScaleCalibration.Type.Body,
                            modifier = Modifier.tvBrowseNonRailInset(),
                        )
                    } else {

                        LazyRow(
                            modifier = rowModifier.fillMaxWidth().testTag(DetailsEpisodeRailTag),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = tvRailContentPadding(vertical = 8.dp),
                        ) {
                            items(visibleEpisodes, key = { it.key }) { row ->
                                EpisodeLandscapeCard(
                                    row = row,
                                    requesters = requesters,
                                    onClick = {
                                        onOpenEpisode(row.seasonNumber, row.episodeNumber, row.titleId)
                                    },

                                    seriesTitleId = state.titleId,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun EpisodeLandscapeCard(
    row: EpisodeRowUi,
    requesters: MutableMap<String, androidx.compose.ui.focus.FocusRequester>,
    onClick: () -> Unit,

    seriesTitleId: dev.infusion.core.model.TitleId? = null,
) {

    val stillSize = DpSize(EpisodeStillWidth, EpisodeStillHeight)
    val stillTarget = artworkTargetPx(stillSize)
    val stillRequest = row.nativeItemId?.let { native ->
        ArtworkRequest(
            locator = ArtworkLocator.still(native),
            policy = ArtworkPolicy.STILL,
            targetWidthPx = stillTarget.width,
            targetHeightPx = stillTarget.height,
        )
    }

    val markUnit = LocalContentCardPresentation.current.watchedMarkUnitFor(
        unit = seriesTitleId?.let { row.cardWatchedUnitOrNull(it) },
        watchedState = LocalCardWatchedState.current,
    )
    var artworkSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    var cardFocused by remember { mutableStateOf(false) }

    val badgePolicy = LocalContentCardPresentation.current
    val jellyfinAvailable = badgePolicy.jellyfinAvailableFor(
        unitTitleId = row.titleId.value.toString(),
        seriesTitleId = seriesTitleId?.value?.toString(),
        seasonNumber = row.seasonNumber,
        episodeNumber = row.episodeNumber,
        availability = LocalCardAvailability.current,
    )
    val watchlistMembership = LocalCardWatchlistMembership.current
    val watchlistVariant = badgePolicy.watchlistBadgeFor(
        entryKey = seriesTitleId?.value?.toString()?.let {
            dev.infusion.tv.ui.presentation.watchlistEntryKey(
                dev.infusion.core.domain.watchlist.WatchlistKind.SHOW,
                it,
            )
        },
        membership = watchlistMembership,
    )

    val actionRequest = rememberCardActionRequest(
        surface = dev.infusion.core.domain.watchlist.CardActionSurface.DETAILS,
        displayTitle = "S${row.seasonNumber}E${row.episodeNumber} — ${row.displayTitle}",
        titleId = row.titleId,
        kind = dev.infusion.core.model.TitleKind.EPISODE,
        watchKey = seriesTitleId?.let {
            dev.infusion.core.model.watch.WatchKey.episode(it, row.seasonNumber, row.episodeNumber)
        },

        playableTitleId = row.titleId,
    )

    val showContextMenuHint = contextMenuHintVisible(
        focused = cardFocused,
        menuAvailable = actionRequest != null,
        cardBadges = badgePolicy.cardBadges,
        contextMenuHint = badgePolicy.contextMenuBadge,
    )

    Card(
        onClick = onClick,
        modifier = stableFocusModifier(
            key = row.key,
            requesters = requesters,
            onFocusedKeyChanged = {},
        ).then(cardContextActionModifier(row.key, actionRequest))

            .onFocusChanged { state -> cardFocused = state.isFocused },

        colors = infusionTvCardColors(),
        shape = infusionTvCardShape(),
        scale = infusionTvCardScale(),
        border = infusionTvCardBorder(),
    ) {
        Row(modifier = Modifier.padding(ContentCardPresentation.ContainerPadding)) {

            Box(modifier = Modifier.size(stillSize).onSizeChanged { artworkSize = it }) {
                ArtworkImage(
                    request = stillRequest,
                    contentDescription = "Episode still",
                    modifier = Modifier.fillMaxSize(),
                )
                if (markUnit != null) {
                    InfusionCardWatchedMark(
                        artworkSize = artworkSize,
                        tag = contentCardWatchedMarkKey(row.key),
                    )
                }

                if (watchlistVariant != null) {
                    InfusionCardWatchlistBadge(
                        artworkSize = artworkSize,
                        variant = watchlistVariant,
                        providerId = watchlistMembership.activeTracker,
                        tag = contentCardWatchlistBadgeKey(row.key),
                    )
                }
                if (jellyfinAvailable) {
                    InfusionCardAvailabilityBadge(
                        artworkSize = artworkSize,
                        tag = contentCardAvailabilityBadgeKey(row.key),
                    )
                }

                if (showContextMenuHint) {
                    InfusionCardContextMenuHint(
                        artworkSize = artworkSize,
                        tag = contentCardContextMenuHintKey(row.key),
                    )
                }
            }
            Column(
                modifier = Modifier.padding(start = 12.dp).width(280.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "S${row.seasonNumber}E${row.episodeNumber} — ${row.displayTitle}",
                    style = InfusionTvScaleCalibration.Type.Emphasis,
                    maxLines = 2,
                )
                when (val w = row.watchedStatus) {
                    WatchedDisplay.Unseen -> Text("Not watched", style = InfusionTvScaleCalibration.Type.Body)
                    WatchedDisplay.Completed -> Text("Watched", style = InfusionTvScaleCalibration.Type.Body)

                    is WatchedDisplay.InProgress -> Text(
                        "In progress — ${DurationFormat.formatDuration(w.positionMs)}",
                        style = InfusionTvScaleCalibration.Type.Body,
                    )
                }
            }
        }
    }
}
