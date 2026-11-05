package dev.infusion.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.infusion.core.domain.format.DurationFormat
import dev.infusion.core.domain.metadata.MetadataSection
import dev.infusion.feature.vertical.details.DetailsStateHolder
import dev.infusion.feature.vertical.media.CanonicalDetailsStateHolder
import dev.infusion.feature.vertical.media.CanonicalDetailsUiState
import dev.infusion.feature.vertical.media.CompanyLogoUi
import dev.infusion.feature.vertical.media.MediaCardUi
import dev.infusion.feature.vertical.media.PersonCardUi
import dev.infusion.feature.vertical.media.SectionState
import dev.infusion.feature.vertical.media.TrailerUi
import dev.infusion.feature.vertical.model.DetailsPhase
import dev.infusion.feature.vertical.model.DetailsUiState
import dev.infusion.feature.vertical.model.WatchedDisplay
import dev.infusion.tv.ui.focus.LocalTvZoneEntry
import dev.infusion.tv.ui.focus.RequestFocusOnKey
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.focus.rememberTvZoneEntry
import dev.infusion.tv.ui.presentation.LocalCardWatchedState
import dev.infusion.tv.ui.presentation.LocalContentCardPresentation
import dev.infusion.tv.ui.presentation.LocalTvRowNavigation
import dev.infusion.tv.ui.presentation.hideWatchedMediaCards
import dev.infusion.tv.ui.presentation.restoreRevealTarget
import dev.infusion.tv.ui.presentation.centredBrowseBringIntoViewSpec
import dev.infusion.tv.ui.presentation.stageOwnedVerticalFramingSpec
import dev.infusion.tv.ui.presentation.tvBrowseNonRailInset
import dev.infusion.tv.ui.theme.InfusionTvDetailsMetrics
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

internal fun resolveDetailsFocusKey(armedKey: String?, state: DetailsUiState): String? = when {
    armedKey == null -> null
    armedKey != DetailsBackKey -> armedKey

    state.phase is DetailsPhase.Loading -> null

    else -> detailsWatchedActionKey(state)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvDetailsScreen(
    stateHolder: DetailsStateHolder,
    onPrimaryAction: () -> Unit,
    onChooseSource: () -> Unit,
    onPlayFromBeginning: () -> Unit = onPrimaryAction,
    onMarkWatched: () -> Unit = {},
    onMarkUnwatched: () -> Unit = {},

    canonicalHolder: CanonicalDetailsStateHolder? = null,

    onOpenCanonicalCard: (MediaCardUi) -> Unit = {},

    onOpenCanonicalCardOccurrence: ((MediaCardUi, String) -> Unit)? = null,

    onOpenCanonicalPerson: (PersonCardUi, String) -> Unit = { _, _ -> },

    onOpenCollection: (Long) -> Unit = {},

    onOpenCompany: (CompanyLogoUi) -> Unit = {},

    focusRestoreKey: String? = null,

    onTrailerClick: (TrailerUi) -> Unit = {},
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()

    val artwork by stateHolder.artworkState.collectAsStateWithLifecycle()

    val canonical: CanonicalDetailsUiState? = if (canonicalHolder != null) {
        canonicalHolder.state.collectAsStateWithLifecycle().value
    } else {
        null
    }
    val requesters = rememberFocusRequesters()

    val canonicalHeroOwnsFacts = canonical?.hasHero == true

    val watchlistAction = rememberDetailsWatchlistAction(
        kind = dev.infusion.core.model.TitleKind.MOVIE,
        titleId = state.titleId,
        displayTitle = state.title ?: canonical?.hero?.title.orEmpty(),
    )

    val directorsBandKey = DetailsStageTwoRegionKeys.DIRECTORS
    val creatorsBandKey = DetailsStageTwoRegionKeys.CREATORS
    val castBandKey = DetailsStageTwoRegionKeys.CAST

    val openPersonOccurrence: (PersonCardUi, String) -> Unit = { person, occurrenceKey ->
        onOpenCanonicalPerson(person, occurrenceKey)
    }
    val hideWatchedTitles = LocalContentCardPresentation.current.hideWatchedTitles
    val cardWatchedState = LocalCardWatchedState.current
    val visibleCollectionMembers = remember(canonical, hideWatchedTitles, cardWatchedState) {
        canonical?.collection?.valueOrNull()?.let { collection ->
            hideWatchedMediaCards(
                cards = collection.members,
                hideWatchedTitles = hideWatchedTitles,
                watchedState = cardWatchedState,
            )
        }.orEmpty()
    }
    val stageTwoRegions = remember(canonical, visibleCollectionMembers) {

        detailsStageTwoRegions(canonical, emptyList(), visibleCollectionMembers)
    }
    val stageTwoHierarchy = rememberDetailsStageTwoHierarchy(
        regions = stageTwoRegions,

        episodeParentChipKeys = emptyMap(),
        requesters = requesters,
    )

    val pageEntryFocusKey = resolveDetailsFocusKey(state.initialFocusKey, state)
    RequestFocusOnKey(
        key = focusRestoreKey ?: pageEntryFocusKey,
        requesters = requesters,

        restoration = focusRestoreKey != null,
    )

    val zone = rememberTvZoneEntry(requesters)

    val scrollState = rememberScrollState()

    var departureScrollPx by rememberSaveable { mutableIntStateOf(0) }
    DisposableEffect(scrollState) {
        onDispose { departureScrollPx = scrollState.value }
    }
    LaunchedEffect(focusRestoreKey, scrollState) {
        if (focusRestoreKey == null) return@LaunchedEffect
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
    var heroHeightPx by remember { mutableIntStateOf(0) }

    var stageTopBandTopPx by remember { mutableIntStateOf(0) }
    var stageTopBandHeightPx by remember { mutableIntStateOf(0) }
    val stageTopBandProbe = Modifier.onGloballyPositioned { coordinates ->
        stageTopBandTopPx = (coordinates.positionInRoot().y + scrollState.value).roundToInt()
        stageTopBandHeightPx = coordinates.size.height
    }
    val firstPresentRegionKey = stageTwoRegions.firstOrNull()?.key

    fun stageTopBandSlot(vararg regionKeys: String): Modifier =
        if (firstPresentRegionKey != null && regionKeys.any { it == firstPresentRegionKey }) {
            stageTopBandProbe
        } else {
            Modifier
        }
    val scrolledPastHero by remember(scrollState) {

        derivedStateOf { isScrolledPastHero(scrollState.value, heroHeightPx) }
    }

    val scope = rememberCoroutineScope()

    val stageTwoEntryFocusKey = detailsStageTwoEntryFocusKey(stageTwoRegions)

    Box(modifier = Modifier.fillMaxSize()) {

        TvDetailsHeroCanvas(
            hero = canonical?.hero,

            primarySettled = canonical?.primarySettled == true,
            underlay = artwork,
            scrolledPastHero = scrolledPastHero,

            posterPresentation = HeroPosterPresentation.DetailsEdge,
        )

        var viewportTopInRootPx by remember { mutableIntStateOf(0) }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { viewportTopInRootPx = it.positionInRoot().y.roundToInt() },
        ) {

            val viewportHeight = maxHeight
            val density = LocalDensity.current
            val viewportPx = with(density) { maxHeight.roundToPx() }

            val revealRestoredRow: (topPx: Int, heightPx: Int) -> Unit = { topPx, heightPx ->
                if (focusRestoreKey != null && requesters.focusedKey == focusRestoreKey) {
                    val target = restoreRevealTarget(
                        rowTopPx = topPx,
                        rowHeightPx = heightPx,
                        viewportTopPx = viewportTopInRootPx,
                        viewportHeightPx = viewportPx,
                        scrollPx = scrollState.value,
                        maxScrollPx = scrollState.maxValue,
                    )
                    if (target != null && target != scrollState.value) {

                        scrollState.dispatchRawDelta((target - scrollState.value).toFloat())
                    }
                }
            }

            val stageItemSpacingPx = with(density) { TvDetailsStageItemSpacing.roundToPx() }
            val stageTwoTopPx = heroHeightPx + stageItemSpacingPx

            val stageTwoTopByState by rememberUpdatedState(stageTwoTopPx)
            val viewportPxByState by rememberUpdatedState(viewportPx)

            val ambientBringIntoViewSpec = LocalBringIntoViewSpec.current

            val pageBringIntoViewSpec = remember(ambientBringIntoViewSpec) {
                stageOwnedVerticalFramingSpec(
                    ambient = ambientBringIntoViewSpec,

                    returnInProgress = {
                        focusRestoreKey != null && requesters.restorationKey != null
                    },
                    earlierStageBottomPx = { heroHeightPx - scrollState.value.toFloat() },
                    stageBottomPx = {
                        if (scrollState.value >= stageTwoTopByState) {
                            viewportPxByState.toFloat()
                        } else {

                            heroHeightPx.toFloat()
                        }
                    },
                    stageIsPresented = {
                        val scroll = scrollState.value
                        scroll == 0 ||
                            scroll >= stageTwoTopByState ||

                            (
                                stageTopBandHeightPx > 0 &&
                                    scroll < stageTopBandTopPx - stageItemSpacingPx
                                )
                    },
                    atRestAnchorDistancePx = { -scrollState.value.toFloat() },

                    laterStageTopBandBottomPx = {
                        if (stageTopBandHeightPx <= 0) {

                            Float.NEGATIVE_INFINITY
                        } else {

                            (stageTopBandTopPx + stageTopBandHeightPx + stageItemSpacingPx) -
                                scrollState.value.toFloat()
                        }
                    },

                    laterStageAnchorDistancePx = {
                        stageTwoTopByState - scrollState.value.toFloat()
                    },
                )
            }
            CompositionLocalProvider(
                LocalBringIntoViewSpec provides pageBringIntoViewSpec,

                LocalTvZoneEntry provides zone,
                LocalTvRowNavigation provides stageTwoHierarchy,
            ) {

                val focusedKeyNow = requesters.focusedKey
                val stageOneReturnKey = requesters.firstLive(
                    DetailsPrimaryActionKey,
                    detailsWatchedActionKey(state),
                )
                val backChainActive = stageTwoHierarchy != null &&
                    stageTwoHierarchy.regionOf(focusedKeyNow) != null &&
                    stageOneReturnKey != null
                BackHandler(enabled = backChainActive) {
                    scope.launch {
                        scrollState.scrollTo(0)
                        requesters.requestFirst(
                            DetailsPrimaryActionKey,
                            detailsWatchedActionKey(state),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()

                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(TvDetailsStageItemSpacing),
                ) {

                    CompositionLocalProvider(LocalBringIntoViewSpec provides ambientBringIntoViewSpec) {

                        TvCanonicalHero(
                            hero = canonical?.hero,
                            titleFallback = state.title,
                            modifier = Modifier

                                .padding(horizontal = TvDetailsPageHorizontalPadding)
                                .onSizeChanged { heroHeightPx = it.height },
                            minHeight = viewportHeight,

                            logoWidthFractionOfScreen = InfusionTvScaleCalibration.ClearLogo.MaxScreenWidthFraction,
                        ) {

                            DetailsStageGenre(
                                canonical = canonical,
                                canonicalHeroOwnsFacts = canonicalHeroOwnsFacts,
                                sourceGenres = state.genres,
                            )

                            canonical?.let { heroState -> TvCanonicalHeroTagline(heroState) }

                            DetailsOverview(synopsis = state.synopsis, canonical = canonical)

                            DetailsStageMetadataLine(
                                canonical = canonical,
                                canonicalHeroOwnsFacts = canonicalHeroOwnsFacts,
                                sourceFacts = detailsStageSourceFacts(state, canonicalHeroOwnsFacts),
                                selectedEditionLabel = state.selectedEditionLabel,
                                ratingItems = if (canonicalHeroOwnsFacts) {
                                    detailsRatingItems(canonical.hero?.ratings.orEmpty())
                                } else {
                                    legacyRatingItems(state.rating)
                                },
                            )

                            canonical?.let { heroState -> TvCanonicalHeroQuietFacts(heroState) }

                            state.unplayableReason?.let {
                                Text(it, style = InfusionTvScaleCalibration.Type.Body)
                            }
                            when (val phase = state.phase) {
                                is DetailsPhase.Loading -> Text("Loading…", style = InfusionTvScaleCalibration.Type.Body)
                                is DetailsPhase.Error -> Text(
                                    "Could not load details: ${phase.message}",
                                    style = InfusionTvScaleCalibration.Type.Body,
                                )
                                DetailsPhase.NoSource -> Text("No playable source.", style = InfusionTvScaleCalibration.Type.Body)
                                null -> Unit
                            }

                            DetailsStageOneActionLine(
                                items = detailsStageActions(
                                    state,
                                    hasTrailer = canonical?.trailer != null,
                                    watchlisted = watchlistAction.watchlisted,
                                ),
                                requesters = requesters,
                                onPrimaryAction = onPrimaryAction,
                                onRestart = onPlayFromBeginning,
                                onSeen = onMarkWatched,
                                onNotSeen = onMarkUnwatched,
                                onSources = onChooseSource,
                                onTrailer = { canonical?.trailer?.let(onTrailerClick) },

                                onWatchlist = watchlistAction.onToggle,
                                onStageDown = stageTwoEntryFocusKey?.let { entryKey ->
                                    {
                                        scope.launch {

                                            scrollState.scrollTo(stageTwoTopPx)

                                            if (!requesters.request(entryKey)) {
                                                requesters.arm(entryKey)
                                            }
                                        }
                                    }
                                },
                            )
                        }

                        DetailsStageTwoBlock(
                            hero = canonical?.hero,

                            minHeight = viewportHeight,

                            stageHeadPresented = {
                                scrollState.value <=
                                    stageTwoTopByState + StageTwoHeadOnScreenTolerancePx
                            },

                            onReturnToStageOne = {
                                scope.launch {
                                    scrollState.scrollTo(0)
                                    requesters.requestFirst(
                                        DetailsPrimaryActionKey,
                                        detailsWatchedActionKey(state),
                                    )
                                }
                            },

                            regionNavigation = stageTwoHierarchy,
                            stageTwoTopBoundary = {
                                stageTwoHierarchy?.atStageTwoTopBoundary(requesters.lastFocusedKey) == true
                            },
                        ) {

                            if (canonical != null) {
                                Box(modifier = stageTopBandSlot(DetailsStageTwoRegionKeys.REVIEWS)) {
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
                            }

                            Column(
                                verticalArrangement = Arrangement.spacedBy(TvDetailsStageItemSpacing),
                            ) {
                                canonical?.takeIf { it.hasHero }?.let { heroState ->

                                    Box(
                                        modifier = stageTopBandSlot(
                                            DetailsStageTwoRegionKeys.DIRECTORS,
                                            DetailsStageTwoRegionKeys.CREATORS,
                                        ),
                                    ) {
                                        TvCanonicalCredits(
                                            state = heroState,
                                            requesters = requesters,

                                            onOpenPersonOccurrence = openPersonOccurrence,
                                            modifier = Modifier.tvBrowseNonRailInset(),
                                            directorsBandKey = directorsBandKey,
                                            creatorsBandKey = creatorsBandKey,
                                        )
                                    }
                                }

                                if (canonical != null) {
                                    Box(modifier = stageTopBandSlot(DetailsStageTwoRegionKeys.CAST)) {
                                        TvCanonicalCastRow(
                                            cast = canonical.cast,
                                            requesters = requesters,

                                            onOpenPersonOccurrence = openPersonOccurrence,
                                            bandKey = castBandKey,
                                        )
                                    }

                                    Box(
                                        modifier = stageTopBandSlot(
                                            DetailsStageTwoRegionKeys.STUDIOS,
                                            DetailsStageTwoRegionKeys.NETWORKS,
                                        ),
                                    ) {
                                        TvCanonicalStudiosNetworks(
                                            canonical,
                                            modifier = Modifier.tvBrowseNonRailInset(),
                                            requesters = requesters,
                                            onOpenEntity = onOpenCompany,
                                        )
                                    }

                                    Box(
                                        modifier = stageTopBandSlot(
                                            DetailsStageTwoRegionKeys.COLLECTION_TITLE,
                                            DetailsStageTwoRegionKeys.COLLECTION,
                                        ),
                                    ) {
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
                                    Box(modifier = stageTopBandSlot(DetailsStageTwoRegionKeys.RECOMMENDED)) {
                                        TvCanonicalCardRow(
                                            title = "Recommended",
                                            focusPrefix = RecommendationsFocusPrefix,
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
                                    }
                                    Box(modifier = stageTopBandSlot(DetailsStageTwoRegionKeys.SIMILAR)) {
                                        TvCanonicalCardRow(
                                            title = "Similar",
                                            focusPrefix = SimilarFocusPrefix,
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
}

internal val TvDetailsPageHorizontalPadding = InfusionTvDetailsMetrics.PageHorizontalPadding

@Composable
internal fun ColumnScope.DetailsStageGenre(
    canonical: CanonicalDetailsUiState?,
    canonicalHeroOwnsFacts: Boolean,
    sourceGenres: List<String>,
) {
    if (canonicalHeroOwnsFacts) {
        canonical?.let { heroState -> TvCanonicalHeroGenreRow(heroState) }
        return
    }

    val line = genreLine(sourceGenres) ?: return
    Text(line, style = InfusionTvScaleCalibration.Type.Body)
}

internal fun detailsStageSourceFacts(
    state: DetailsUiState,
    canonicalHeroOwnsFacts: Boolean,
): List<String> = listOfNotNull(
    if (canonicalHeroOwnsFacts) null else state.year?.toString(),
    state.subtitle,
    state.editionLabel,
)

@Composable
internal fun ColumnScope.DetailsStageMetadataLine(
    canonical: CanonicalDetailsUiState?,
    canonicalHeroOwnsFacts: Boolean,
    sourceFacts: List<String>,
    selectedEditionLabel: String?,
    ratingItems: List<DetailsRatingItem>,

    trailingFact: String? = null,
) {
    val hero = canonical?.hero

    val facts: List<String> = buildList {
        if (canonicalHeroOwnsFacts && hero != null) {
            hero.originalTitle?.takeIf { it != hero.title }?.let { add(it) }
            if (hero.metadataLine.isNotEmpty()) add(hero.metadataLine)
        }

        addAll(sourceFacts.filter { it.isNotBlank() })
        selectedEditionLabel?.takeIf { it.isNotBlank() }?.let { add("Selected: $it") }

        trailingFact?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    val factLine = facts.takeIf { it.isNotEmpty() }?.joinToString(GenreSeparator)
    if (factLine == null && ratingItems.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DetailsMetaRatingsLineTag),
        horizontalArrangement = Arrangement.spacedBy(DetailsMetaRatingsLineGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        factLine?.let { line ->
            Text(
                line,
                style = InfusionTvScaleCalibration.Type.Body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(DetailsMetaRatingsLineTextTag),
            )
        }

        DetailsStageOneRatingsRow(items = ratingItems)
    }
}

private val DetailsMetaRatingsLineGap = 12.dp

@Composable
internal fun DetailsOverview(synopsis: String?, canonical: CanonicalDetailsUiState?) {
    TvCanonicalOverviewBlock(synopsis = synopsis, canonical = canonical)
}
