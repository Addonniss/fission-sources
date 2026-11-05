package dev.infusion.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.infusion.tv.ui.presentation.MediaCardArtwork
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.feature.vertical.collections.CollectionGroupRow
import dev.infusion.core.artworkimage.LocalArtworkLoadEnabled
import dev.infusion.feature.vertical.home.HomeCatalogRowsStateHolder
import dev.infusion.feature.vertical.home.HomeContinueWatchingRow
import dev.infusion.feature.vertical.home.HomeContinueWatchingStateHolder
import dev.infusion.feature.vertical.home.HomePresentationWindow
import dev.infusion.feature.vertical.home.shelfPresentationWindow
import dev.infusion.feature.vertical.home.HomeUpNextUiState
import dev.infusion.feature.vertical.home.UpNextRowCard
import dev.infusion.feature.vertical.home.UpNextRowState
import dev.infusion.feature.vertical.home.HomeCwCard
import dev.infusion.feature.vertical.home.HomeCwMixedTitle
import dev.infusion.feature.vertical.home.HomeCwOrigin
import dev.infusion.feature.vertical.home.HomeCwRow
import dev.infusion.feature.vertical.home.HomeCwShelfKey
import dev.infusion.feature.vertical.home.HomePresentationPreferences
import dev.infusion.feature.vertical.home.HomeRowDefinition
import dev.infusion.feature.vertical.home.homeContinueWatchingRows
import dev.infusion.feature.vertical.home.migratedHomeRowId
import dev.infusion.feature.vertical.model.DetailsArtwork
import dev.infusion.source.aggregation.discover.DiscoverItem
import dev.infusion.source.aggregation.discover.DiscoverShelf
import dev.infusion.source.aggregation.discover.ShelfState
import dev.infusion.tv.ui.focus.FocusKeyRegistry
import dev.infusion.tv.ui.focus.LocalTopLevelContentUpRequester
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.presentation.InfusionContentRow
import dev.infusion.tv.ui.presentation.InfusionMediaCard
import dev.infusion.tv.ui.presentation.IdentityMetadataState
import dev.infusion.tv.ui.presentation.LocalCardWatchedState
import dev.infusion.tv.ui.presentation.LocalContentCardPresentation
import dev.infusion.tv.ui.presentation.TvBrowseColumn
import dev.infusion.tv.ui.presentation.TvBrowseRowSpacingDp
import dev.infusion.tv.ui.presentation.contentCardArtworkKey
import dev.infusion.tv.ui.presentation.contentCardArtworkTarget
import dev.infusion.tv.ui.presentation.hideWatchedShelf
import dev.infusion.tv.ui.presentation.rememberRowPaging
import dev.infusion.tv.ui.presentation.rememberTvBrowseListState
import dev.infusion.tv.ui.presentation.tvBrowseContentPadding
import dev.infusion.tv.ui.presentation.tvBrowseNonRailInset
import dev.infusion.tv.ui.shell.InfusionTvShell
import dev.infusion.tv.ui.shell.LocalColdStartSplashController
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import kotlin.random.Random
import kotlinx.coroutines.delay

@Composable
fun TvHomeScreen(
    stateHolder: HomeContinueWatchingStateHolder,
    homeCatalogRows: HomeCatalogRowsStateHolder? = null,

    upNext: HomeUpNextUiState = HomeUpNextUiState(row = UpNextRowState.Hidden),
    onOpenUpNext: (UpNextRowCard) -> Unit = {},
    jellyfinConnected: Boolean,
    model: TvTopNavModel,
    onNavigate: (TopLevelDestination) -> Unit,
    onOpenCard: (HomeCwCard) -> Unit,
    onOpenAddonItem: (DiscoverItem) -> Unit = {},

    onOpenCollectionPage: (pageId: String, tileFocusKey: String) -> Unit = { _, _ -> },

    focusRestoreKey: String? = null,
    onExit: () -> Unit,

    presentation: HomePresentationPreferences = HomePresentationPreferences(),

    spotlightPreferenceRead: Boolean = true,

    heroMetadataFactory: HomeHeroMetadataFactory? = null,

    heroPresentationCache: dev.infusion.feature.vertical.home.HomeHeroPresentationCache? = null,

    listState: LazyListState = rememberTvBrowseListState(),

    spotlightState: HomeSpotlightStateHolder? = null,

    onContinueWatchingRowWindow: (shelfKey: String, materialized: Int) -> Unit = { _, _ -> },

    onUpNextRowWindow: (materialized: Int) -> Unit = {},

    onSpotlightSourceUnavailable: () -> Unit = {},
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    val addonRowsState = homeCatalogRows?.state?.collectAsStateWithLifecycle()?.value
    val requesters = rememberFocusRequesters()

    val homeRowMemory = remember(model) { model.rowMemoryStore(TopLevelDestination.HOME) }
    val chrome = rememberTopLevelPageChrome(TopLevelDestination.HOME, model)

    val heroSpotlightRequester = remember { FocusRequester() }

    val tabUpRequester = LocalTopLevelContentUpRequester.current
    val hasContinueWatchingContent = state.row is HomeContinueWatchingRow.Loaded &&
        (state.row as HomeContinueWatchingRow.Loaded).cards.isNotEmpty()

    val cwRows = (state.row as? HomeContinueWatchingRow.Loaded)
        ?.let { loaded -> homeContinueWatchingRows(loaded.cards, presentation.separateContinueWatching) }
        .orEmpty()

    val cardPresentation = LocalContentCardPresentation.current
    val cardWatchedState = LocalCardWatchedState.current

    val upNextComposition = homeUpNextComposition(upNext)
    val upNextCards = upNextComposition.cards
    val upNextFailure = upNextComposition.failure
    val browseShelves = addonRowsState?.rows.orEmpty().map { shelf ->
        hideWatchedShelf(
            shelf = shelf,
            hideWatchedTitles = cardPresentation.hideWatchedTitles,
            watchedState = cardWatchedState,
        )
    }

    val rowDefinitions = addonRowsState?.rowDefinitions.orEmpty()
    val migratedBoundaryRowId = migratedHomeRowId(
        definitions = rowDefinitions,
        publishedRowIds = browseShelves.mapTo(mutableSetOf()) { it.key },
    )

    val topLevelBoundaryAddonShelfKey = if (hasContinueWatchingContent || upNextCards.isNotEmpty()) {
        null
    } else {
        browseShelves.firstOrNull { shelf ->
            val shelfState = shelf.state
            shelfState is ShelfState.Loaded && shelfState.items.isNotEmpty()
        }?.key
    }
    val onContentKeyFocused: (String) -> Unit = { chrome.onContentKeyFocused(it) }

    val heroPool = remember(cwRows, addonRowsState?.rows, addonRowsState?.collectionRows, upNextCards) {
        homeHeroPool(
            cwRows = cwRows,
            shelves = addonRowsState?.rows.orEmpty(),

            upNextCards = upNextCards,
            collectionRows = addonRowsState?.collectionRows.orEmpty(),
        )
    }
    val heroPoolKeys = heroPool.map { it.key }
    val heroPoolKeySet = remember(heroPoolKeys) { heroPoolKeys.toSet() }

    val heroRowKeys = remember(cwRows, addonRowsState?.rows, addonRowsState?.collectionRows, upNextCards) {
        homeHeroRowKeys(cwRows, addonRowsState?.rows.orEmpty(), upNextCards, addonRowsState?.collectionRows.orEmpty())
    }

    val heroSpotlight = spotlightState ?: remember { HomeSpotlightStateHolder() }

    val coldStartSplash = LocalColdStartSplashController.current

    val spotlightEnabled = spotlightPreferenceRead && presentation.showSpotlight
    val cwHeroCandidates = remember(cwRows) { homeHeroCwCandidates(cwRows) }
    val catalogHeroCandidates = remember(addonRowsState?.rows) {
        homeHeroCatalogCandidates(addonRowsState?.rows.orEmpty())
    }
    val heroRowsTerminal = state.row !is HomeContinueWatchingRow.Loading &&
        addonRowsState?.loading != true

    val spotlightPresentation = remember(
        presentation.spotlightSource,
        addonRowsState?.rows,
        addonRowsState?.rowOrder,
        addonRowsState?.configuredRowIds,
        addonRowsState?.loading,
        cwHeroCandidates,
        catalogHeroCandidates,
        heroRowsTerminal,
    ) {
        homeSpotlightPresentation(
            saved = presentation.spotlightSource,
            rows = addonRowsState?.rows.orEmpty(),
            declaredRowKeys = addonRowsState?.rowOrder.orEmpty().toSet(),
            rowsSettled = addonRowsState?.loading == false,

            configuredRowKeys = addonRowsState?.configuredRowIds,
            cwCandidates = cwHeroCandidates,
            catalogCandidates = catalogHeroCandidates,
            cwRowsTerminal = heroRowsTerminal,
        )
    }

    LaunchedEffect(spotlightPresentation, spotlightEnabled) {
        if (!spotlightEnabled) return@LaunchedEffect
        val resolved = spotlightPresentation
        if (resolved.pending) {
            heroSpotlight.awaitSource(resolved.source)
            return@LaunchedEffect
        }
        if (!resolved.drawReady) return@LaunchedEffect
        heroSpotlight.present(resolved.source, resolved.candidates)
    }

    LaunchedEffect(spotlightPresentation.resolveStored, spotlightPreferenceRead) {
        if (!spotlightPreferenceRead) return@LaunchedEffect
        if (spotlightPresentation.resolveStored) onSpotlightSourceUnavailable()
    }
    val spotlight = heroSpotlight.drawn.orEmpty()

    var lastInputAtMs by remember { mutableStateOf(0L) }

    val candidateKeyFocused = chrome.focusedKey?.takeIf { it in heroPoolKeySet }
    val lastItemChangeAtMs = remember { mutableStateOf(0L) }
    val lastItemRowKey = remember { mutableStateOf<String?>(null) }
    val contentPresentedKey = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(candidateKeyFocused, chrome.focusedKey, heroRowKeys) {
        val key = candidateKeyFocused
        if (key == null) {

            if (homeHeroRevertsToSpotlight(chrome.focusedKey, contentPresentedKey.value)) {
                contentPresentedKey.value = null
            }
            return@LaunchedEffect
        }
        val rowKey = heroRowKeys[key]
        val nowMs = System.nanoTime() / 1_000_000
        val settleMs = HomeHeroFocus.settleMs(
            previousChangeAtMs = lastItemChangeAtMs.value,
            nowMs = nowMs,
            previousRowKey = lastItemRowKey.value,
            rowKey = rowKey,
        )
        lastItemChangeAtMs.value = nowMs
        lastItemRowKey.value = rowKey
        delay(settleMs)

        if (chrome.focusedKey != key) return@LaunchedEffect
        contentPresentedKey.value = key
    }

    LaunchedEffect(chrome.childReturnRestorationOwed, heroPoolKeySet) {

        if (!chrome.childReturnRestorationOwed) return@LaunchedEffect
        if (candidateKeyFocused != null) return@LaunchedEffect
        val remembered = model.lastContentFocusFor(TopLevelDestination.HOME) ?: return@LaunchedEffect
        if (contentPresentedKey.value == null && remembered in heroPoolKeySet) {
            contentPresentedKey.value = remembered
        }
    }

    val spotlightCurrent = if (spotlightEnabled) spotlight.getOrNull(heroSpotlight.index) else null

    val presentedContentKey = homeHeroPresentedContentKey(chrome.focusedKey, contentPresentedKey.value)
    val featured: HomeHeroCandidate? = presentedContentKey
        ?.let { key -> heroPool.firstOrNull { it.key == key } }
        ?: spotlightCurrent

    val heroOwnerSnapshot = HomeHeroOwnerSnapshot(
        focusedKey = chrome.focusedKey,
        rowCardFocused = candidateKeyFocused != null,
        contentKey = contentPresentedKey.value,
        presentedContentKey = presentedContentKey,
        dotIndex = heroSpotlight.index,
        dotCount = spotlight.size,
        presentedKey = featured?.key,
    )
    LaunchedEffect(heroOwnerSnapshot, coldStartSplash) {
        coldStartSplash?.note("hero-owner", heroOwnerSnapshot.describe())
    }

    val featuredArtworkRef: dev.infusion.core.model.MediaArtworkRef? = homeHeroArtworkRef(featured)

    val featuredCollectionLogo: String? = featured?.collectionLogoLocator

    val spotlightPrevious: () -> Unit = {
        val size = spotlight.size
        if (size > 1) {
            heroSpotlight.index = HomeHeroSpotlight.previousIndex(size, heroSpotlight.index)
        }
    }
    val spotlightNext: () -> Unit = {
        val size = spotlight.size
        if (size > 1) {
            heroSpotlight.index = HomeHeroSpotlight.nextIndex(size, heroSpotlight.index)
        }
    }

    val homeScope = rememberCoroutineScope()
    val heroMetadata = remember(heroMetadataFactory, heroPresentationCache) {
        HomeHeroMetadataStateHolder(
            scope = homeScope,
            factory = heroMetadataFactory,
            presentationCache = heroPresentationCache,

            trace = coldStartSplash?.let { splash -> { detail: String -> splash.note("hero-holder", detail) } },
        )
    }

    val heroMetadataKey = remember { mutableStateOf<String?>(null) }

    val heroUnderlayOutcome = remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(featured?.key) {
        val candidate = featured ?: return@LaunchedEffect
        heroMetadata.feature(candidate.key, candidate.anchor, candidate.preview)
        heroMetadataKey.value = candidate.key
    }

    val heroPresentation = heroMetadata.presentation.collectAsStateWithLifecycle().value
    val canonicalHero = heroPresentation.hero
    val heroMetadataState = heroPresentation.metadata

    var previousFocusedKey by remember { mutableStateOf<String?>(null) }
    var contentFocusedByUser by remember { mutableStateOf(false) }
    LaunchedEffect(chrome.focusedKey, chrome.entryToken) {
        val key = chrome.focusedKey
        contentFocusedByUser = key != null &&
            heroRowKeys.containsKey(key) &&
            HomeHeroFocus.isUserFocus(previousFocusedKey, chrome.entryToken)
        previousFocusedKey = key
    }

    val autoRollMs = if (spotlightEnabled) {
        HomeHeroRotation.intervalMs(presentation.spotlightAutoRollSeconds)
    } else {
        0L
    }

    val rotationSuspended = coldStartSplash?.visible == true ||
        (
            chrome.focusedKey != null &&
                chrome.focusedKey != HomeHeroSpotlightFocusKey &&
                heroRowKeys.containsKey(chrome.focusedKey)
            )

    val firstAdvanceServed = remember { mutableStateOf(false) }
    LaunchedEffect(spotlight.size, autoRollMs, rotationSuspended) {
        if (!HomeHeroRotation.canRotate(autoRollMs, spotlight.size, rotationSuspended)) {
            return@LaunchedEffect
        }
        var waitMs = if (firstAdvanceServed.value) {
            autoRollMs
        } else {
            HomeHeroRotation.firstAdvanceMs(autoRollMs)
        }
        while (true) {
            delay(waitMs)
            firstAdvanceServed.value = true
            waitMs = autoRollMs

            val idleForMs = System.nanoTime() / 1_000_000 - lastInputAtMs
            if (HomeHeroRotation.holdsForIdleWindow(autoRollMs, idleForMs)) {
                waitMs = HomeHeroRotation.idleHoldRemainingMs(autoRollMs, idleForMs)
                continue
            }
            val next = HomeHeroSpotlight.nextIndex(spotlight.size, heroSpotlight.index)
            if (next != heroSpotlight.index) {
                heroSpotlight.index = next
            }
        }
    }

    val browseRows = buildList {

        addAll(
            if (cwRows.isNotEmpty()) {
                cwRows.mapIndexed { index, cwRow ->
                    HomeBrowseRow(

                        key = cwRow.key,
                        itemKeys = { cwRow.cards.map { card -> card.key } },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ContinueWatchingRowBlock(
                                row = cwRow,
                                requesters = requesters,

                                topLevelContentBoundary = index == 0,
                                onOpenCard = onOpenCard,
                                onFocusedKeyChanged = onContentKeyFocused,

                                onWindowChanged = { materialized ->
                                    onContinueWatchingRowWindow(cwRow.key, materialized)
                                },
                            )

                            if (index == cwRows.lastIndex) {
                                state.segmentNote?.let { note ->
                                    Text(
                                        "Could not load Jellyfin Continue Watching: $note",
                                        style = InfusionTvScaleCalibration.Type.Body,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.tvBrowseNonRailInset(),
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                listOf(
                    HomeBrowseRow(

                        key = HomeCwShelfKey,
                        itemKeys = { emptyList() },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (presentation.separateContinueWatching) {

                                when (val row = state.row) {
                                    is HomeContinueWatchingRow.Loading,
                                    is HomeContinueWatchingRow.Failed,
                                    -> {

                                        Text(
                                            HomeCwMixedTitle,
                                            style = InfusionTvScaleCalibration.Type.Emphasis,
                                            modifier = Modifier.tvBrowseNonRailInset(),
                                        )
                                        Box(Modifier.tvBrowseNonRailInset()) { ContinueWatchingStatus(row) }
                                    }
                                    is HomeContinueWatchingRow.Loaded,
                                    is HomeContinueWatchingRow.Empty,
                                    -> Unit
                                }
                            } else {
                                Text(
                                    HomeCwMixedTitle,
                                    style = InfusionTvScaleCalibration.Type.Emphasis,
                                    modifier = Modifier.tvBrowseNonRailInset(),
                                )
                                Box(Modifier.tvBrowseNonRailInset()) { ContinueWatchingStatus(state.row) }
                            }

                            state.segmentNote?.let { note ->
                                Text(
                                    "Could not load Jellyfin Continue Watching: $note",
                                    style = InfusionTvScaleCalibration.Type.Body,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.tvBrowseNonRailInset(),
                                )
                            }
                        }
                    },
                )
            },
        )

        if (upNextCards.isNotEmpty()) {
            add(
                HomeBrowseRow(
                    key = HomeUpNextShelfKey,
                    itemKeys = { upNextCards.map { card -> card.key } },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            HomeUpNextTitle,
                            style = InfusionTvScaleCalibration.Type.Emphasis,
                            modifier = Modifier.tvBrowseNonRailInset(),
                        )

                        val inArtworkNeighbourhood = LocalArtworkLoadEnabled.current
                        var reportedLoaded by remember { mutableIntStateOf(0) }
                        var reportedMaterialized by remember { mutableIntStateOf(0) }
                        LaunchedEffect(inArtworkNeighbourhood, reportedLoaded, reportedMaterialized) {

                            onUpNextRowWindow(
                                presentedWindow(reportedMaterialized, inArtworkNeighbourhood),
                            )
                        }
                        InfusionContentRow(
                            items = upNextCards,
                            key = { it.key },

                            onWindowChanged = { loaded, materialized ->
                                android.util.Log.i(
                                    "INFUSION-UPNEXT",
                                    "row window loaded=$loaded materialized=$materialized " +
                                        "nearViewport=$inArtworkNeighbourhood",
                                )
                                reportedLoaded = loaded
                                reportedMaterialized = materialized
                            },
                        ) { card ->
                            HomeUpNextCardTile(
                                card = card,
                                requesters = requesters,

                                topLevelContentBoundary = !hasContinueWatchingContent,
                                onClick = { onOpenUpNext(card) },
                                onFocusedKeyChanged = onContentKeyFocused,
                            )
                        }

                        upNext.note?.let { note ->
                            Text(
                                "Could not load ${upNext.sourceLabel} Up Next: $note",
                                style = InfusionTvScaleCalibration.Type.Body,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.tvBrowseNonRailInset(),
                            )
                        }
                    }
                },
            )
        } else if (upNextFailure != null) {
            add(
                HomeBrowseRow(key = "home:up-next-error", itemKeys = { emptyList() }) {
                    Text(
                        "Could not load Up Next: $upNextFailure",
                        style = InfusionTvScaleCalibration.Type.Body,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.tvBrowseNonRailInset(),
                    )
                },
            )
        }

        if (upNextCards.isNotEmpty() || upNextFailure != null) {
            upNext.unavailableSourceNote?.let { unavailable ->
                if (upNextCards.isEmpty()) {
                    add(
                        HomeBrowseRow(key = "home:up-next-source-note", itemKeys = { emptyList() }) {
                            Text(
                                unavailable,
                                style = InfusionTvScaleCalibration.Type.Body,
                                modifier = Modifier.tvBrowseNonRailInset(),
                            )
                        },
                    )
                }
            }
        }

        if (addonRowsState != null) {
            if (addonRowsState.loading && addonRowsState.rows.isEmpty()) {
                add(
                    HomeBrowseRow(key = "home:rows-loading", itemKeys = { emptyList() }) {
                        Text(
                            "Loading selected Home rows…",
                            style = InfusionTvScaleCalibration.Type.Body,
                            modifier = Modifier.tvBrowseNonRailInset(),
                        )
                    },
                )
            }
            addonRowsState.error?.let { error ->
                add(
                    HomeBrowseRow(key = "home:rows-error", itemKeys = { emptyList() }) {
                        Text(
                            "Could not load selected Home rows: $error",
                            style = InfusionTvScaleCalibration.Type.Body,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.tvBrowseNonRailInset(),
                        )
                    },
                )
            }

            val shelvesByKey = browseShelves.associateBy { it.key }
            val collectionRowsByKey = addonRowsState.collectionRows.associateBy { it.rowKey }
            val orderedRowKeys = addonRowsState.rowOrder.ifEmpty { browseShelves.map { it.key } }
            orderedRowKeys.forEach { rowKey ->
                val row = shelvesByKey[rowKey]
                if (row == null) {

                    val groupRow = collectionRowsByKey[rowKey] ?: return@forEach
                    add(
                        HomeBrowseRow(
                            key = groupRow.rowKey,
                            itemKeys = {
                                groupRow.tiles.map { tile ->
                                    collectionTileFocusKey(groupRow.widgetId, groupRow.groupKey, tile.tileKey)
                                }
                            },
                        ) {
                            CollectionGroupTileRow(
                                row = groupRow,
                                requesters = requesters,
                                onOpenTile = onOpenCollectionPage,

                                onFocusedKeyChanged = onContentKeyFocused,
                            )
                        },
                    )
                    return@forEach
                }

                val definition = rowDefinitions
                    .firstOrNull { it.rowId == row.key }
                    ?.takeIf { row.key == migratedBoundaryRowId }
                add(
                    HomeBrowseRow(
                        key = definition?.rowId ?: row.key,
                        itemKeys = {
                            (row.state as? ShelfState.Loaded)?.items?.map { it.key }.orEmpty()
                        },
                    ) {
                        PagedHomeShelfBlock(
                            shelf = row,
                            rowDefinition = definition,
                            showProviderName = presentation.showProviderName,
                            topLevelContentBoundary = !hasContinueWatchingContent &&
                                row.key == topLevelBoundaryAddonShelfKey,
                            requesters = requesters,
                            onOpenAddonItem = onOpenAddonItem,
                            onContentKeyFocused = onContentKeyFocused,

                            onLoadMore = { homeCatalogRows?.loadMore(definition?.rowId ?: row.key) },
                            pagingFailed = row.key in addonRowsState.pagingFailedShelfKeys,

                            pagingFailureToken = addonRowsState.pagingFailureTokens[row.key],
                        )
                    },
                )
            }
        }
    }

    val arrivingRowKeys: Set<Any> = buildSet {
        addAll(addonRowsState?.rowOrder.orEmpty())
        addAll(browseShelves.map { it.key })
        add(HomeCwShelfKey)
        cwRows.forEach { add(it.key) }
        add(HomeUpNextShelfKey)
    } - browseRows.mapTo(mutableSetOf()) { it.key }

    val spotlightOffEntryKey = if (spotlightEnabled) {
        null
    } else {
        remember(
            cwRows,
            upNextCards,
            browseShelves,
            addonRowsState?.collectionRows,
            addonRowsState?.rowOrder,
        ) {
            homeSpotlightOffEntryKey(
                cwRows = cwRows,
                upNextCards = upNextCards,
                shelves = browseShelves,
                collectionRows = addonRowsState?.collectionRows.orEmpty(),
                rowOrder = addonRowsState?.rowOrder.orEmpty(),
            )
        }
    }
    val entryKey = when {
        spotlightOffEntryKey != null -> spotlightOffEntryKey
        spotlightEnabled && featured != null -> HomeHeroSpotlightFocusKey
        else -> null
    }

    val spotlightDrawSettledWithoutSet = !spotlightPresentation.pending &&
        !spotlightPresentation.drawReady &&
        heroRowsTerminal
    val initialHero = homeInitialHero(
        featuredKey = featured?.key,
        spotlightPreferenceRead = spotlightPreferenceRead,
        spotlightEnabled = spotlightEnabled,
        spotlightSetDrawn = heroSpotlight.drawn != null,
        spotlightSetEmpty = heroSpotlight.drawn?.isEmpty() == true,
        spotlightDrawSettledWithoutSet = spotlightDrawSettledWithoutSet,
        spotlightOffEntryKey = spotlightOffEntryKey,
        rowsLoading = !heroRowsTerminal,
    )
    LaunchedEffect(initialHero, featured?.key, coldStartSplash) {
        when (initialHero) {
            HomeInitialHero.Presented ->
                featured?.key?.let { key -> coldStartSplash?.onInitialHeroPresented(key) }

            HomeInitialHero.NoHero -> coldStartSplash?.onInitialHeroAbsent()
            HomeInitialHero.Pending -> Unit
        }
    }

    LaunchedEffect(spotlightPresentation, spotlightEnabled, spotlightPreferenceRead, heroRowsTerminal, coldStartSplash) {
        coldStartSplash?.note(
            "spotlight",
            "enabled=$spotlightEnabled read=$spotlightPreferenceRead" +
                " pending=${spotlightPresentation.pending}" +
                " drawReady=${spotlightPresentation.drawReady}" +
                " candidates=${spotlightPresentation.candidates.size}" +
                " rowsSettled=${addonRowsState?.loading == false}" +
                " rowsTerminal=$heroRowsTerminal" +
                " settledWithoutSet=$spotlightDrawSettledWithoutSet" +
                " drawn=${heroSpotlight.drawn?.size}",
        )
    }
    LaunchedEffect(canonicalHero, heroMetadataState, featured?.key, coldStartSplash) {
        if (featured == null) return@LaunchedEffect
        coldStartSplash?.note(
            "hero-metadata",
            "key=${dev.infusion.tv.perf.LogcatColdStartTrace.digest(featured.key)}" +
                " state=$heroMetadataState" +
                " backdrop=${canonicalHero?.backdropLocator != null}" +
                " logo=${canonicalHero?.logoLocator != null}" +
                " underlay=${featuredArtworkRef?.locator != null}",
        )
    }

    val heroImageReadiness: HeroImageReadiness? = featured?.key
        ?.takeIf { key -> key == heroMetadataKey.value }
        ?.let { key ->
            HeroImageReadiness(
                key = key,
                underlayAvailable = heroUnderlayOutcome.value,
            ) { reportedKey, loaded ->

                coldStartSplash?.onInitialHeroPresented(reportedKey)
                coldStartSplash?.onInitialHeroImageOutcome(reportedKey, loaded)
            }
        }

    val restorableTileKeys = remember(addonRowsState?.collectionRows) {
        addonRowsState?.collectionRows.orEmpty().flatMap { row ->
            row.tiles.map { tile -> collectionTileFocusKey(row.widgetId, row.groupKey, tile.tileKey) }
        }.toSet()
    }
    val shellEntryKey = if (chrome.entryActFramePending) {

        entryKey
    } else {
        homeEntryKey(
            entryKey = entryKey,
            focusRestoreKey = focusRestoreKey,
            restorableTileKeys = restorableTileKeys,
            rowsLoading = addonRowsState?.loading != false,
        )
    }

    InfusionTvShell(
        page = TopLevelDestination.HOME,
        jellyfinConnected = jellyfinConnected,
        model = model,
        requesters = requesters,
        chrome = chrome,

        entryDefaultKey = shellEntryKey,

        spotlightAtPageTop = spotlightEnabled,
        onNavigateTo = onNavigate,
        onExit = onExit,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()

                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        lastInputAtMs = System.nanoTime() / 1_000_000

                        coldStartSplash?.note(
                            "hero-input",
                            "key=${event.key} t=${android.os.SystemClock.elapsedRealtime()}",
                        )
                    }
                    false
                },
        ) {

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val viewportHeight = maxHeight
                val density = LocalDensity.current
                val viewportTarget: IntSize = with(density) {
                    IntSize(maxWidth.roundToPx(), maxHeight.roundToPx())
                }

                val heroArtwork = rememberHomeHeroArtwork(
                    featuredArtworkRef,
                    viewportTarget,
                    retention = heroSpotlight,

                    onResolved = { ready -> heroUnderlayOutcome.value = ready },
                )

                val viewportPx = with(density) { viewportHeight.roundToPx() }
                val contentTopPaddingPx = with(density) {
                    tvBrowseContentPadding().calculateTopPadding().roundToPx()
                }
                val rowSpacingPx = with(density) { TvBrowseRowSpacingDp.roundToPx() }

                val rowIdentityReservedPx = with(density) {
                    homeRowItemIdentityReservedHeight().roundToPx()
                }

                val sectionStagePx = HomeVerticalComposition.sectionStageHeightPx(
                    viewportPx = viewportPx,
                    rowSpacingPx = rowSpacingPx,
                )
                val sectionStageMinHeight = with(density) { sectionStagePx.toDp() }

                val pageEndAllowance = with(density) {
                    HomeVerticalComposition.pageEndAllowancePx(
                        viewportPx = viewportPx,
                        rowSpacingPx = rowSpacingPx,
                    ).toDp()
                }

                val layoutDirection = LocalLayoutDirection.current
                val browseContentPadding = tvBrowseContentPadding()
                val homeContentPadding = PaddingValues(
                    start = browseContentPadding.calculateStartPadding(layoutDirection),
                    end = browseContentPadding.calculateEndPadding(layoutDirection),
                    top = browseContentPadding.calculateTopPadding(),
                    bottom = browseContentPadding.calculateBottomPadding() + pageEndAllowance,
                )
                val measuredItemHeights = remember { mutableStateMapOf<String, Int>() }
                val publishMeasuredHeight: (String, Int) -> Unit = remember(measuredItemHeights) {
                    { key, heightPx ->
                        if (heightPx > 0 && measuredItemHeights[key] != heightPx) {
                            measuredItemHeights[key] = heightPx
                        }
                    }
                }

                val measuredContentHeights = remember { mutableStateMapOf<String, Int>() }
                val publishMeasuredContentHeight: (String, Int) -> Unit =
                    remember(measuredContentHeights) {
                        { key, heightPx ->
                            if (heightPx > 0 && measuredContentHeights[key] != heightPx) {
                                measuredContentHeights[key] = heightPx
                            }
                        }
                    }

                val measuredGeometry by remember {
                    derivedStateOf { measuredItemHeights.toMap() }
                }

                val firstSectionKey = cwRows.firstOrNull()?.key ?: topLevelBoundaryAddonShelfKey
                val firstSectionStagedHeightPx = if (firstSectionKey == null) {
                    0
                } else {
                    maxOf(measuredItemHeights[firstSectionKey] ?: 0, sectionStagePx)
                }

                val itemsAboveFirstSectionPx = firstSectionKey?.let { key ->
                    val index = browseRows.indexOfFirst { row -> row.key == key }
                    if (index <= 0) {
                        emptyList()
                    } else {
                        browseRows.take(index).map { row -> measuredItemHeights[row.key] ?: 0 }
                    }
                }.orEmpty()
                val heroBlockMinHeight = with(density) {
                    HomeVerticalComposition.heroBlockHeightPx(
                        viewportPx = viewportPx,
                        contentTopPaddingPx = contentTopPaddingPx,
                        rowSpacingPx = rowSpacingPx,
                        firstSectionStagedHeightPx = firstSectionStagedHeightPx,
                        itemsAboveFirstSectionPx = itemsAboveFirstSectionPx,
                    ).toDp()
                }

                val spotlightSetPending = spotlightEnabled && heroSpotlight.drawn == null
                val topCompositionSlotted = spotlightEnabled &&
                    (featured != null || (firstSectionKey != null && spotlightSetPending))

                val contentAnchorKey = chrome.focusedKey?.takeIf { key ->
                    key == HomeHeroSpotlightFocusKey || heroRowKeys.containsKey(key)
                }

                fun pageAnchorKeyFor(contentKey: String?): String? = contentKey?.takeIf { key ->
                    key == HomeHeroSpotlightFocusKey || heroRowKeys.containsKey(key)
                }

                val lastContentAnchorKey =
                    if (chrome.entryActFramePending) null else pageAnchorKeyFor(chrome.lastContentKey)

                val childReturnRestorationOwed = chrome.childReturnRestorationOwed
                val childReturnAnchorKey = if (childReturnRestorationOwed) {
                    pageAnchorKeyFor(model.lastContentFocusFor(TopLevelDestination.HOME))
                } else {
                    null
                }

                val childReturnOriginPending = childReturnRestorationOwed &&
                    model.lastContentFocusFor(TopLevelDestination.HOME) != null &&
                    childReturnAnchorKey == null &&
                    addonRowsState?.loading != false

                val atRestAnchorKey = if (spotlightEnabled || spotlightOffEntryKey == null) {
                    HomeHeroSpotlightFocusKey
                } else {
                    spotlightOffEntryKey
                }
                val compositionAnchorKey = homeAtRestAnchorOwner(
                    focusedKey = chrome.focusedKey,
                    contentAnchorKey = contentAnchorKey,
                    lastContentKey = chrome.lastContentKey,
                    lastContentAnchorKey = lastContentAnchorKey,
                    rememberedContentAnchorKey = childReturnAnchorKey,
                    childReturnOriginPending = childReturnOriginPending,
                    atRestAnchorKey = atRestAnchorKey,
                )
                val rowsForAnchor = rememberUpdatedState(browseRows)

                val headerForAnchor = rememberUpdatedState(topCompositionSlotted)

                val anchorAct = remember(compositionAnchorKey, measuredGeometry, listState) {
                    compositionAnchorKey?.let { anchorKey ->
                        homeAnchorAct(
                            anchorKey = anchorKey,
                            anchorRowKey = if (anchorKey == HomeHeroSpotlightFocusKey) {
                                null
                            } else {
                                heroRowKeys[anchorKey]
                            },
                            rowKeys = rowsForAnchor.value.map { row -> row.key },
                            headerCount = if (headerForAnchor.value) 1 else 0,
                            contentTopPaddingPx = contentTopPaddingPx,
                            rowSpacingPx = rowSpacingPx,
                        )
                    }
                }
                SideEffect {
                    val slot = anchorAct?.slot ?: return@SideEffect
                    if (listState.firstVisibleItemIndex != slot.index ||
                        listState.firstVisibleItemScrollOffset != slot.offsetPx
                    ) {
                        listState.requestScrollToItem(slot.index, slot.offsetPx)
                    }
                }

                HomeHeroEntryComposition(
                    listState = listState,

                    entryKey = if (spotlightEnabled) entryKey else null,
                    focusedKey = chrome.focusedKey,
                    entryToken = chrome.entryToken,
                    userDriven = contentFocusedByUser,
                    lastInputAtMs = { lastInputAtMs },
                )

                TvDetailsHeroCanvas(
                    hero = canonicalHero,

                    primarySettled = heroMetadataState == IdentityMetadataState.SETTLED,
                    underlay = DetailsArtwork(ref = heroArtwork),
                    scrolledPastHero = false,

                    posterPresentation = HeroPosterPresentation.Corner,

                    heroImageReadiness = heroImageReadiness,
                )

                val rowsUpRequester = if (spotlightEnabled && featured != null) {
                    heroSpotlightRequester
                } else {
                    tabUpRequester
                }

                val activeSectionKey = anchorAct?.presentedSectionKey
                CompositionLocalProvider(
                    LocalTopLevelContentUpRequester provides rowsUpRequester,
                ) {
                    TvBrowseColumn(
                        rows = browseRows,
                        rowKey = { it.key },
                        rowItemKeys = { it.itemKeys() },
                        modifier = Modifier.fillMaxSize(),
                        listTag = "home-rows",
                        listState = listState,

                        pageOwnsVerticalFraming = true,

                        sharedRowRules = true,

                        rememberedRowMemory = homeRowMemory,

                        downRestoresRemembered = true,

                        arrivingRowKeys = arrivingRowKeys,

                        originRowArrivalIndeterminate = addonRowsState?.loading != false,

                        contentPadding = homeContentPadding,
                        header = if (topCompositionSlotted) {
                            {
                                if (featured != null) {
                                    CompositionLocalProvider(
                                        LocalTopLevelContentUpRequester provides tabUpRequester,
                                    ) {
                                        TvHomeHeroBlock(
                                        hero = canonicalHero,

                                        metadata = heroMetadataState,
                                        preview = featured.preview,

                                        modifier = Modifier.tvBrowseNonRailInset(),

                                        minHeight = heroBlockMinHeight,
                                        requesters = requesters,
                                        requester = heroSpotlightRequester,

                                        spotlightSize = spotlight.size,
                                        spotlightIndex = heroSpotlight.index,
                                        onFocusedKeyChanged = onContentKeyFocused,

                                        collectionLogoLocator = featuredCollectionLogo,
                                        onPrevious = spotlightPrevious,
                                        onNext = spotlightNext,
                                        onOpen = {
                                            spotlightCurrent?.let { candidate ->
                                                openHeroCandidate(
                                                    candidate,
                                                    onOpenCard,
                                                    onOpenAddonItem,
                                                )
                                            }
                                        },
                                    )
                                    }
                                } else {

                                    Spacer(Modifier.heightIn(min = heroBlockMinHeight))
                                }
                            }
                        } else {
                            null
                        },
                    ) { row ->

                        val staged = row.isSection()

                        val activeRow = staged && row.key == activeSectionKey

                        val rowContentPx = measuredContentHeights[row.key] ?: 0
                        val identityRoomPx = viewportPx - (rowContentPx + rowIdentityReservedPx)
                        val identityPresented = activeRow &&
                            candidateKeyFocused != null &&
                            rowContentPx > 0 &&

                            identityRoomPx >= 0
                        Box(
                            modifier = Modifier
                                .then(
                                    if (staged) {
                                        Modifier.heightIn(min = sectionStageMinHeight)
                                    } else {
                                        Modifier
                                    },
                                )
                                .onSizeChanged { size ->
                                    publishMeasuredHeight(row.key, size.height)
                                },
                            contentAlignment = if (activeRow) {
                                Alignment.BottomStart
                            } else {
                                Alignment.TopStart
                            },
                        ) {
                            if (identityPresented) {
                                Box(modifier = Modifier.matchParentSize()) {
                                    TvHomeRowItemIdentity(
                                        hero = canonicalHero,
                                        preview = featured?.preview,

                                        metadata = heroMetadataState,

                                        collectionLogoLocator = featuredCollectionLogo,

                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .offset(
                                                y = -with(density) { rowContentPx.toDp() },
                                            )
                                            .tvBrowseNonRailInset(),
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier.onSizeChanged { size ->
                                    publishMeasuredContentHeight(row.key, size.height)
                                },
                            ) {
                                row.content()
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun openHeroCandidate(
    candidate: HomeHeroCandidate,
    onOpenCard: (HomeCwCard) -> Unit,
    onOpenAddonItem: (DiscoverItem) -> Unit,
) {
    when (candidate) {
        is HomeHeroCwCardCandidate -> onOpenCard(candidate.card)
        is HomeHeroAddonItemCandidate -> onOpenAddonItem(candidate.item)

        is HomeHeroUpNextCandidate, is HomeHeroCollectionTileCandidate -> Unit

        is WatchlistHeroCandidate -> Unit
    }
}

private class HomeBrowseRow(
    val key: String,
    val itemKeys: () -> List<String>,
    val content: @Composable () -> Unit,
) {

    fun isSection(): Boolean = itemKeys().isNotEmpty()
}

internal data class HomeAnchorAct(

    val anchorKey: String,

    val presentedSectionKey: String?,

    val slot: HomePageScrollTarget,
)

internal fun homeAnchorAct(
    anchorKey: String,
    anchorRowKey: String?,
    rowKeys: List<String>,
    headerCount: Int,
    contentTopPaddingPx: Int,
    rowSpacingPx: Int,
): HomeAnchorAct? {
    if (anchorKey == HomeHeroSpotlightFocusKey) {
        return HomeAnchorAct(
            anchorKey = anchorKey,
            presentedSectionKey = null,
            slot = HomeVerticalComposition.heroAnchorScrollTarget(),
        )
    }
    val rowKey = anchorRowKey ?: return null
    val rowIndex = rowKeys.indexOf(rowKey)
    if (rowIndex < 0) return null
    return HomeAnchorAct(
        anchorKey = anchorKey,
        presentedSectionKey = rowKey,
        slot = HomeVerticalComposition.sectionAnchorScrollTarget(
            itemIndex = rowIndex + headerCount,
            contentTopPaddingPx = contentTopPaddingPx,
            rowSpacingPx = rowSpacingPx,
        ),
    )
}

internal fun HomeAnchorAct.containerItemIndexOfPresentedSection(
    rowKeys: List<String>,
    headerCount: Int,
): Int? {
    val presented = presentedSectionKey ?: return null
    val rowIndex = rowKeys.indexOf(presented)
    if (rowIndex < 0) return null
    return rowIndex + headerCount
}

@Composable
private fun HomeShelfBlock(
    shelf: DiscoverShelf,
    showProviderName: Boolean,
    topLevelContentBoundary: Boolean,
    requesters: MutableMap<String, androidx.compose.ui.focus.FocusRequester>,
    onOpenAddonItem: (DiscoverItem) -> Unit,
    onFocusedKeyChanged: (String) -> Unit,

    rowDefinition: HomeRowDefinition? = null,

    paging: dev.infusion.tv.ui.presentation.RowPagingState? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        Text(

            when {
                rowDefinition != null -> rowDefinition.label
                showProviderName -> "${shelf.title} — ${shelf.sourceLabel}"
                else -> shelf.title
            },
            style = InfusionTvScaleCalibration.Type.Emphasis,
            modifier = Modifier.tvBrowseNonRailInset(),
        )
        when (val shelfState = shelf.state) {
            is ShelfState.Failed -> Text(
                "Could not load this Home row: ${shelfState.reason}",
                style = InfusionTvScaleCalibration.Type.Body,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.tvBrowseNonRailInset(),
            )
            is ShelfState.Loaded -> {
                if (shelfState.items.isEmpty()) {
                    Text(
                        "No items.",
                        style = InfusionTvScaleCalibration.Type.Body,
                        modifier = Modifier.tvBrowseNonRailInset(),
                    )
                } else {

                    InfusionContentRow(
                        items = shelfState.items,
                        key = { it.key },

                        paging = paging,
                    ) { item ->
                        AddonPosterCard(
                            itemKey = item.key,
                            preview = item.preview,
                            requesters = requesters,
                            topLevelContentBoundary = topLevelContentBoundary,
                            onClick = {
                                if (item.titleId != null) onOpenAddonItem(item)
                            },
                            onFocusedKeyChanged = onFocusedKeyChanged,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PagedHomeShelfBlock(
    shelf: DiscoverShelf,
    showProviderName: Boolean,
    topLevelContentBoundary: Boolean,
    requesters: FocusKeyRegistry,
    onOpenAddonItem: (DiscoverItem) -> Unit,
    onContentKeyFocused: (String) -> Unit,
    onLoadMore: () -> Unit,
    pagingFailed: Boolean,
    pagingFailureToken: Long?,

    rowDefinition: HomeRowDefinition? = null,
) {
    val loaded = shelf.state as? ShelfState.Loaded
    if (loaded == null) {
        HomeShelfBlock(
            shelf = shelf,
            showProviderName = showProviderName,
            topLevelContentBoundary = topLevelContentBoundary,
            requesters = requesters,
            onOpenAddonItem = onOpenAddonItem,
            onFocusedKeyChanged = onContentKeyFocused,
            rowDefinition = rowDefinition,
        )
        return
    }

    val itemKeys = remember(loaded.items) { loaded.items.map { it.key } }
    val paging = rememberRowPaging(
        itemKeys = itemKeys,
        hasMore = loaded.hasMore,
        onLoadMore = onLoadMore,
        pagingFailed = pagingFailed,
        pagingFailureToken = pagingFailureToken,
    )
    HomeShelfBlock(
        shelf = shelf,
        showProviderName = showProviderName,
        topLevelContentBoundary = topLevelContentBoundary,
        requesters = requesters,
        onOpenAddonItem = onOpenAddonItem,
        onFocusedKeyChanged = { key ->
            onContentKeyFocused(key)
            paging.onFocusedKey(key)
        },
        rowDefinition = rowDefinition,

        paging = paging,
    )
}

@Composable
private fun ContinueWatchingRowBlock(
    row: HomeCwRow,
    requesters: MutableMap<String, androidx.compose.ui.focus.FocusRequester>,
    topLevelContentBoundary: Boolean,
    onOpenCard: (HomeCwCard) -> Unit,
    onFocusedKeyChanged: (String) -> Unit,

    onWindowChanged: (Int) -> Unit = {},
) {

    val inArtworkNeighbourhood = LocalArtworkLoadEnabled.current
    var reportedLoaded by remember { mutableIntStateOf(0) }
    var reportedMaterialized by remember { mutableIntStateOf(0) }
    LaunchedEffect(inArtworkNeighbourhood, reportedLoaded, reportedMaterialized) {
        onWindowChanged(presentedWindow(reportedMaterialized, inArtworkNeighbourhood))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        Text(
            row.title,
            style = InfusionTvScaleCalibration.Type.Emphasis,
            modifier = Modifier.tvBrowseNonRailInset(),
        )
        InfusionContentRow(
            items = row.cards,
            key = { it.key },

            onWindowChanged = { loaded, materialized ->
                android.util.Log.i(
                    "INFUSION-CW",
                    "row window key=${row.key} loaded=$loaded materialized=$materialized " +
                        "nearViewport=$inArtworkNeighbourhood",
                )
                reportedLoaded = loaded
                reportedMaterialized = materialized
            },
        ) { card ->
            HomeCwCardTile(
                card = card,
                requesters = requesters,
                topLevelContentBoundary = topLevelContentBoundary,
                onClick = { onOpenCard(card) },
                onFocusedKeyChanged = onFocusedKeyChanged,
            )
        }
    }
}

private fun presentedWindow(materialized: Int, inArtworkNeighbourhood: Boolean): Int =
    shelfPresentationWindow(materialized, inArtworkNeighbourhood)

@Composable
private fun ContinueWatchingStatus(row: HomeContinueWatchingRow) {
    when (row) {
        is HomeContinueWatchingRow.Loading -> Text(
            "Loading…",
            style = InfusionTvScaleCalibration.Type.Body,
        )
        is HomeContinueWatchingRow.Empty -> Text(

            "Nothing to continue watching",
            style = InfusionTvScaleCalibration.Type.Body,
        )
        is HomeContinueWatchingRow.Failed -> Text(
            "Could not load Continue Watching: ${row.message}",
            style = InfusionTvScaleCalibration.Type.Body,
            color = MaterialTheme.colorScheme.error,
        )
        is HomeContinueWatchingRow.Loaded -> if (row.cards.isEmpty()) {
            Text(
                "Nothing to continue watching",
                style = InfusionTvScaleCalibration.Type.Body,
            )
        }
    }
}

internal const val HomeUpNextShelfKey: String = "home:up-next"

internal const val HomeUpNextTitle: String = "Up Next"

internal data class HomeUpNextComposition(
    val cards: List<UpNextRowCard> = emptyList(),

    val note: String? = null,

    val failure: String? = null,
)

internal fun homeUpNextComposition(upNext: HomeUpNextUiState): HomeUpNextComposition = when (val row = upNext.row) {
    is UpNextRowState.Loaded -> HomeUpNextComposition(cards = row.cards, note = upNext.note)
    is UpNextRowState.Failed -> HomeUpNextComposition(failure = row.message)
    UpNextRowState.Hidden, UpNextRowState.Loading, UpNextRowState.Empty -> HomeUpNextComposition()
}

@Composable
private fun HomeUpNextCardTile(
    card: UpNextRowCard,
    requesters: MutableMap<String, androidx.compose.ui.focus.FocusRequester>,
    topLevelContentBoundary: Boolean = false,
    onClick: () -> Unit,
    onFocusedKeyChanged: (String) -> Unit,
) {
    val preview = card.preview
    val artworkTarget = contentCardArtworkTarget(preview.kind)
    InfusionMediaCard(
        onClick = onClick,
        focusKey = card.key,
        kind = preview.kind,
        preview = preview,
        requesters = requesters,
        onFocusedKeyChanged = onFocusedKeyChanged,
        topLevelContentBoundary = topLevelContentBoundary,

        focusable = true,
        artworkTag = contentCardArtworkKey(card.key),
        watchedMarkEligible = false,
        artworkLabel = card.provenanceLabel,
        artwork = {
            MediaCardArtwork(
                preview = preview,
                targetSize = artworkTarget,
                contentDescription = "Episode still",
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}

@Composable
private fun HomeCwCardTile(
    card: HomeCwCard,
    requesters: MutableMap<String, androidx.compose.ui.focus.FocusRequester>,
    topLevelContentBoundary: Boolean = false,
    onClick: () -> Unit,
    onFocusedKeyChanged: (String) -> Unit,
) {
    val preview = card.preview

    val artworkTarget = contentCardArtworkTarget(preview.kind)

    InfusionMediaCard(
        onClick = onClick,
        focusKey = card.key,
        kind = preview.kind,

        preview = preview,
        requesters = requesters,
        onFocusedKeyChanged = onFocusedKeyChanged,
        topLevelContentBoundary = topLevelContentBoundary,

        focusable = card.reachesDestination,

        artworkTag = contentCardArtworkKey(card.key),

        watchedMarkEligible = false,

        artworkLabel = card.provenanceLabel,
        artwork = {

            MediaCardArtwork(
                preview = preview,
                targetSize = artworkTarget,
                contentDescription = "Poster",
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}

internal val HomeCwCard.reachesDestination: Boolean get() = when (val origin = origin) {
    is HomeCwOrigin.Local -> true
    is HomeCwOrigin.Jellyfin -> origin.item.titleId != null || origin.item.isFolder
}

internal fun homeEntryKey(
    entryKey: String?,
    focusRestoreKey: String?,
    restorableTileKeys: Set<String>,
    rowsLoading: Boolean,
): String? = when {
    focusRestoreKey == null -> entryKey
    focusRestoreKey in restorableTileKeys -> focusRestoreKey
    rowsLoading -> null
    else -> entryKey
}

internal fun homeSpotlightOffEntryKey(
    cwRows: List<HomeCwRow>,
    upNextCards: List<UpNextRowCard>,
    shelves: List<DiscoverShelf>,
    collectionRows: List<CollectionGroupRow>,
    rowOrder: List<String>,
): String? {
    cwRows.forEach { row ->
        row.cards.firstOrNull { card -> card.reachesDestination }?.let { card -> return card.key }
    }
    upNextCards.firstOrNull()?.let { card -> return card.key }
    val shelvesByKey = shelves.associateBy { shelf -> shelf.key }
    val collectionByKey = collectionRows.associateBy { row -> row.rowKey }
    val orderedRowKeys = rowOrder.ifEmpty { shelves.map { shelf -> shelf.key } }
    orderedRowKeys.forEach { rowKey ->
        val shelf = shelvesByKey[rowKey]
        if (shelf != null) {
            val loaded = shelf.state as? ShelfState.Loaded ?: return@forEach
            loaded.items.firstOrNull { item -> item.preview.titleId != null }
                ?.let { item -> return item.key }
            return@forEach
        }
        val group = collectionByKey[rowKey] ?: return@forEach
        group.tiles.firstOrNull()?.let { tile ->
            return collectionTileFocusKey(group.widgetId, group.groupKey, tile.tileKey)
        }
    }
    return null
}

internal fun homeHeroArtworkRef(
    featured: HomeHeroCandidate?,
): dev.infusion.core.model.MediaArtworkRef? =
    featured?.collectionBackdropLocator
        ?.let { locator -> dev.infusion.core.model.MediaArtworkRef.of(locator, ArtworkPolicy.BACKDROP) }
        ?: featured?.preview?.artwork

private data class HomeHeroOwnerSnapshot(

    val focusedKey: String?,

    val rowCardFocused: Boolean,

    val contentKey: String?,

    val presentedContentKey: String?,

    val dotIndex: Int,
    val dotCount: Int,

    val presentedKey: String?,
) {

    fun describe(): String =
        "t=${android.os.SystemClock.elapsedRealtime()}" +
            " focus=${digestOrNone(focusedKey)}" +
            " rowCard=$rowCardFocused" +
            " content=${digestOrNone(contentKey)}" +
            " dot=$dotIndex/$dotCount" +
            " owner=${if (presentedContentKey != null) "content" else "spotlight"}" +
            " presented=${digestOrNone(presentedKey)}" +
            " presentedIsContent=${presentedKey != null && presentedKey == presentedContentKey}"

    private fun digestOrNone(key: String?): String =
        key?.let { dev.infusion.tv.perf.LogcatColdStartTrace.digest(it) } ?: "none"
}
