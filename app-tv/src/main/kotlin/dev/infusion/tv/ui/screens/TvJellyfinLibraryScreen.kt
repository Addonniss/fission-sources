@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package dev.infusion.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.infusion.feature.vertical.jellyfin.JellyfinTabRowItem
import dev.infusion.feature.vertical.jellyfin.library.LibraryBrowseStateHolder
import dev.infusion.feature.vertical.jellyfin.library.LibraryBrowseUiState
import dev.infusion.feature.vertical.jellyfin.library.LibraryCapabilities
import dev.infusion.feature.vertical.jellyfin.library.LibraryContentPhase
import dev.infusion.feature.vertical.jellyfin.library.LibraryEpisodeFlag
import dev.infusion.feature.vertical.jellyfin.library.LibraryFeature
import dev.infusion.feature.vertical.jellyfin.library.LibraryFilterGroup
import dev.infusion.feature.vertical.jellyfin.library.LibraryFilterOptions
import dev.infusion.feature.vertical.jellyfin.library.LibraryFilterValues
import dev.infusion.feature.vertical.jellyfin.library.LibraryFooter
import dev.infusion.feature.vertical.jellyfin.library.LibraryLevel
import dev.infusion.feature.vertical.jellyfin.library.LibraryPresentation
import dev.infusion.feature.vertical.jellyfin.library.LibraryProjector
import dev.infusion.feature.vertical.jellyfin.library.LibraryProjection
import dev.infusion.feature.vertical.jellyfin.library.LibrarySeriesStatus
import dev.infusion.feature.vertical.jellyfin.library.LibrarySortField
import dev.infusion.feature.vertical.jellyfin.library.LibrarySortOrder
import dev.infusion.feature.vertical.jellyfin.library.LibraryStatusFilter
import dev.infusion.feature.vertical.jellyfin.library.LibrarySupport
import dev.infusion.feature.vertical.jellyfin.library.LibraryVideoType
import dev.infusion.feature.vertical.jellyfin.library.LibraryView
import dev.infusion.tv.ui.focus.FocusKeyRegistry
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.presentation.ContentRowDensity
import dev.infusion.tv.ui.presentation.InfusionContentRow
import dev.infusion.tv.ui.presentation.LibraryOverlayBottomMargin
import dev.infusion.tv.ui.presentation.LibraryOverlayGap
import dev.infusion.tv.ui.presentation.LibraryOverlayMaxWidth
import dev.infusion.tv.ui.presentation.LibraryOverlayMinHeight
import dev.infusion.tv.ui.presentation.LibrarySelectorLabel
import dev.infusion.tv.ui.presentation.LibraryStatusLine
import dev.infusion.tv.ui.presentation.LocalCardWatchedState
import dev.infusion.tv.ui.presentation.LocalContentCardPresentation
import dev.infusion.tv.ui.presentation.SuggestionPosition
import dev.infusion.tv.ui.presentation.SuggestionStep
import dev.infusion.tv.ui.presentation.LibrarySuggestionNavigation
import dev.infusion.tv.ui.presentation.TvMediaGrid
import dev.infusion.tv.ui.presentation.TvMediaList
import dev.infusion.tv.ui.presentation.TvBrowseContentAlignmentInset
import dev.infusion.tv.ui.presentation.TvOverlayOption
import dev.infusion.tv.ui.presentation.TvOverlayWindow
import dev.infusion.tv.ui.presentation.gridColumns
import dev.infusion.tv.ui.presentation.hideWatchedJellyfinItems
import dev.infusion.tv.ui.presentation.ContentCardShape
import dev.infusion.tv.ui.presentation.libraryCategoryTiles
import dev.infusion.tv.ui.presentation.libraryGridShape
import dev.infusion.tv.ui.presentation.libraryListRowArtwork
import dev.infusion.tv.ui.presentation.requestFocusEventually
import dev.infusion.tv.ui.presentation.withoutHiddenWatchedJellyfinItems
import dev.infusion.tv.ui.presentation.tvBrowseNonRailInset
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val KEY_VIEW_SELECTOR = "library-control:view"
private const val KEY_FILTERS = "library-control:filters"
private const val KEY_SORT = "library-control:sort"

private const val KEY_PLAY_CONTEXT = "library-control:play"
private const val KEY_SHUFFLE_CONTEXT = "library-control:shuffle"
private const val KEY_NOW_PLAYING = "library-control:now-playing"

private const val KEY_CONTENT_RETRY = "library-content:retry"
private const val KEY_GRID_FOOTER = "library-content:footer"
private const val KEY_EMPTY_RESET = "library-content:reset"

private const val KEY_FILTER_RESET = "library-filter:reset"
private const val KEY_FILTER_RETRY = "library-filter:retry"
private const val KEY_FILTER_ANY_GENRE = "library-filter:genre:any"
private const val KEY_FILTER_ANY_RATING = "library-filter:rating:any"
private const val KEY_FILTER_ANY_TAG = "library-filter:tag:any"
private const val KEY_FILTER_ANY_VIDEO = "library-filter:video:any"

private enum class LibraryOverlay { VIEWS, FILTERS, SORT }

private enum class LibraryFocusZone { CONTROLS, CONTENT }

@Composable
fun TvJellyfinLibraryScreen(
    stateHolder: LibraryBrowseStateHolder,
    onOpenItem: (JellyfinTabRowItem) -> Unit,
    onOpenItemDetails: (JellyfinTabRowItem) -> Unit,
    onBack: () -> Unit,

    musicQueue: dev.infusion.feature.vertical.music.MusicQueueState? = null,

    onMusicNowPlaying: () -> Unit = {},

    onMusicPlayAll: (() -> Unit)? = null,

    onMusicShuffleAll: (() -> Unit)? = null,

    onMusicPlaySong: ((JellyfinTabRowItem, Int?) -> Unit)? = null,

    exitOnBackFromLevel: dev.infusion.feature.vertical.jellyfin.library.LibraryLevel? = null,
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    val requesters: FocusKeyRegistry = rememberFocusRequesters()
    val scope = rememberCoroutineScope()
    var overlay by remember { mutableStateOf<LibraryOverlay?>(null) }
    var openFilterGroup by remember { mutableStateOf<LibraryFilterGroup?>(null) }
    var lastControlKey by remember { mutableStateOf<String?>(null) }
    var focusedItemId by remember { mutableStateOf<String?>(null) }
    var focusedServerIndex by remember { mutableStateOf<Int?>(null) }
    var firstVisibleServerIndex by remember { mutableStateOf<Int?>(null) }

    var focusZone by remember { mutableStateOf(LibraryFocusZone.CONTROLS) }
    var lastContentKey by remember { mutableStateOf<String?>(null) }
    var gridFocusKey by remember { mutableStateOf<String?>(null) }
    var gridFocusRevision by remember { mutableIntStateOf(0) }

    var suggestionEntry by remember { mutableStateOf<SuggestionEntry?>(null) }

    var controlsBottomPx by remember { mutableIntStateOf(0) }

    var pendingFocusKey by remember { mutableStateOf<String?>(null) }
    var pendingFocusRevision by remember { mutableIntStateOf(0) }

    val gridState = remember(state.window.key) { androidx.compose.foundation.lazy.LazyListState() }
    val density = LocalDensity.current

    val cardPresentation = LocalContentCardPresentation.current
    val cardWatchedState = LocalCardWatchedState.current
    val projection: LibraryProjection = remember(
        state.window,
        state.windowRevision,
        cardPresentation.hideWatchedTitles,
        cardWatchedState,
    ) {

        LibraryProjector.project(
            window = state.window,
            hidingEnabled = cardPresentation.hideWatchedTitles,
        ) { item ->
            withoutHiddenWatchedJellyfinItems(
                items = listOf(item),
                hideWatchedTitles = cardPresentation.hideWatchedTitles,
                watchedState = cardWatchedState,
            ).isEmpty()
        }
    }

    val shape = libraryGridShape(state.type, state.view, state.level, cardPresentation)

    val categoryTiles = remember(
        projection,
        state.type,
        state.view,
        state.level,
        state.genreArtworkCandidates,
    ) {
        libraryCategoryTiles(
            type = state.type,
            view = state.view,
            level = state.level,
            items = projection.cells.mapNotNull { it.item },
            genreArtworkCandidates = state.genreArtworkCandidates,
        )
    }

    val musicPlayAvailable = onMusicPlayAll != null &&
        state.presentation == LibraryPresentation.LIST &&
        state.type == dev.infusion.feature.vertical.jellyfin.library.LibraryType.MUSIC
    val musicShuffleAvailable = musicPlayAvailable && onMusicShuffleAll != null

    fun controlKeys(): List<String> = buildList {
        add(KEY_VIEW_SELECTOR)
        if (state.hasFilters) add(KEY_FILTERS)
        if (state.hasSort) add(KEY_SORT)
        if (musicPlayAvailable) add(KEY_PLAY_CONTEXT)
        if (musicShuffleAvailable) add(KEY_SHUFFLE_CONTEXT)
        if (musicQueue?.hasQueue == true) add(KEY_NOW_PLAYING)
    }

    fun neighbourControl(key: String, delta: Int): String? {
        val keys = controlKeys()
        val index = keys.indexOf(key)
        if (index < 0) return null
        val target = index + delta
        return if (target in keys.indices) keys[target] else null
    }

    fun moveControl(key: String, delta: Int): Boolean {
        neighbourControl(key, delta)?.let { target ->
            requesters.arm(target)
            if (requesters.request(target)) requesters.disarm()
        }
        return true
    }

    fun focusKey(key: String) {
        requesters.arm(key)
        if (requesters.request(key)) requesters.disarm()
    }

    fun requestFocusAcross(key: String) {
        pendingFocusKey = key
        pendingFocusRevision++
    }

    LaunchedEffect(pendingFocusRevision) {
        val key = pendingFocusKey ?: return@LaunchedEffect
        requesters.requestFocusEventually(key)
    }

    LaunchedEffect(openFilterGroup, state.filterOptions) {
        val open = openFilterGroup ?: return@LaunchedEffect
        if (filterGroupEntryKey(open, state.filterOptions) == null) openFilterGroup = null
    }

    fun enterContent() {
        focusZone = LibraryFocusZone.CONTENT
        if (state.presentation == LibraryPresentation.SECTIONS) {
            suggestionEntry = SuggestionEntry(preferredItemKey = lastContentKey)
            return
        }
        val remembered = lastContentKey?.takeIf { key -> projection.cells.any { it.item?.key == key } }
        val target = remembered ?: projection.cells.firstOrNull { it.isFocusable }?.item?.key
        if (target != null) {
            gridFocusKey = target
            gridFocusRevision++
        } else {
            when (state.phase) {
                is LibraryContentPhase.Failed -> focusKey(KEY_CONTENT_RETRY)
                LibraryContentPhase.Empty -> focusKey(KEY_EMPTY_RESET)

                else -> stateHolder.onForwardHorizon()
            }
        }
    }

    fun exitToControls() {
        focusZone = LibraryFocusZone.CONTROLS
        val target = lastControlKey?.takeIf { controlKeys().contains(it) } ?: KEY_VIEW_SELECTOR
        focusKey(target)
    }

    fun overlayEntryKey(kind: LibraryOverlay): String? = when (kind) {
        LibraryOverlay.VIEWS -> viewKey(state.views.firstOrNull { it == state.view } ?: state.views.firstOrNull())
        LibraryOverlay.FILTERS -> groupKey(state.filterGroups.firstOrNull())
        LibraryOverlay.SORT -> sortKey(state.sortFields.firstOrNull { it == state.sort.field } ?: state.sortFields.firstOrNull())
    }

    fun openOverlay(kind: LibraryOverlay) {

        val entry = overlayEntryKey(kind) ?: return
        overlay = kind
        openFilterGroup = null
        requestFocusAcross(entry)
    }

    fun closeOverlay(returnKey: String) {
        overlay = null
        openFilterGroup = null
        requestFocusAcross(returnKey)
    }

    LaunchedEffect(Unit) {
        if (!stateHolder.hasPendingReturn()) focusKey(KEY_VIEW_SELECTOR)
    }

    val backHandler: (KeyEvent) -> Boolean = { event ->
        if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
            when {
                openFilterGroup != null -> {
                    val restore = groupKey(openFilterGroup)
                    openFilterGroup = null
                    if (restore != null) requestFocusAcross(restore)
                    true
                }
                overlay != null -> {
                    val restore = when (overlay) {
                        LibraryOverlay.VIEWS -> KEY_VIEW_SELECTOR
                        LibraryOverlay.FILTERS -> KEY_FILTERS
                        LibraryOverlay.SORT -> KEY_SORT
                        null -> KEY_VIEW_SELECTOR
                    }
                    closeOverlay(restore)
                    true
                }
                state.level !is LibraryLevel.Root && state.level == exitOnBackFromLevel -> {

                    onBack()
                    true
                }
                state.level !is LibraryLevel.Root -> {
                    stateHolder.backToParent()
                    true
                }
                focusZone == LibraryFocusZone.CONTENT -> {
                    exitToControls()
                    true
                }
                else -> {
                    onBack()
                    true
                }
            }
        } else {
            false
        }
    }

    val overlayTopPx = controlsBottomPx + with(density) { LibraryOverlayGap.roundToPx() }
    val insetPx = with(density) { TvBrowseContentAlignmentInset.roundToPx() }
    val gapPx = with(density) { LibraryOverlayGap.roundToPx() }
    var filtersWindowWidthPx by remember { mutableIntStateOf(0) }

    val overlayTopDp = with(density) { overlayTopPx.toDp() }
    val viewportHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val overlayMaxHeight = (viewportHeightDp - overlayTopDp - LibraryOverlayBottomMargin)
        .coerceAtLeast(LibraryOverlayMinHeight)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val usableWidth = ContentRowDensity.densityReferenceWidth(maxWidth)
        val columns = gridColumns(shape, usableWidth)

        LaunchedEffect(state.level, state.windowRevision) {
            val snapshot = state.returnSnapshot ?: return@LaunchedEffect
            if (snapshot.key.level != state.level) return@LaunchedEffect
            val consumed = stateHolder.consumeReturn() ?: return@LaunchedEffect
            if (consumed.key.presentation == LibraryPresentation.SECTIONS) {

                lastContentKey = consumed.focusedItemId
                suggestionEntry = SuggestionEntry(preferredItemKey = consumed.focusedItemId)
                return@LaunchedEffect
            }

            val firstCell = consumed.firstVisibleVisibleIndex.coerceAtLeast(0)
            val row = if (state.presentation == LibraryPresentation.LIST) {
                firstCell
            } else {
                (firstCell / columns).coerceAtLeast(0)
            }
            scope.launch { gridState.scrollToItem(row) }
            consumed.focusedItemId?.let { id ->
                lastContentKey = id
                gridFocusKey = id
                gridFocusRevision++
            }
        }

        Column(modifier = Modifier.fillMaxSize().onPreviewKeyEvent(backHandler)) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .tvBrowseNonRailInset()
                    .padding(vertical = 8.dp)

                    .onGloballyPositioned { coordinates ->
                        controlsBottomPx = coordinates.boundsInWindow().bottom.roundToInt()
                    },
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LibraryControl(
                    label = activeViewLabel(state),
                    focusKey = KEY_VIEW_SELECTOR,
                    selected = overlay == LibraryOverlay.VIEWS,
                    requesters = requesters,
                    onFocused = {
                        lastControlKey = it
                        focusZone = LibraryFocusZone.CONTROLS
                    },
                    onLeft = { true },
                    onRight = { moveControl(KEY_VIEW_SELECTOR, +1) },
                    onDown = { enterContent(); true },
                    onUp = { true },
                    onActivate = {
                        if (overlay == LibraryOverlay.VIEWS) {
                            closeOverlay(KEY_VIEW_SELECTOR)
                        } else {
                            openOverlay(LibraryOverlay.VIEWS)
                        }
                    },
                    tag = "library-control-view",

                    selectorMark = true,
                )
                Spacer(Modifier.weight(1f))
                if (state.hasFilters) {
                    LibraryControl(
                        label = if (state.activeFilterCount > 0) {
                            "Filters (${state.activeFilterCount})"
                        } else {
                            "Filters"
                        },
                        focusKey = KEY_FILTERS,
                        selected = overlay == LibraryOverlay.FILTERS,
                        requesters = requesters,
                        onFocused = {
                            lastControlKey = it
                            focusZone = LibraryFocusZone.CONTROLS
                        },
                        onLeft = {
                            focusKey(KEY_VIEW_SELECTOR)
                            true
                        },
                        onRight = { moveControl(KEY_FILTERS, +1) },
                        onDown = { enterContent(); true },
                        onUp = { true },
                        onActivate = {
                            if (overlay == LibraryOverlay.FILTERS) {
                                closeOverlay(KEY_FILTERS)
                            } else {
                                openOverlay(LibraryOverlay.FILTERS)
                            }
                        },
                        tag = "library-control-filters",
                    )
                }
                if (state.hasSort) {
                    LibraryControl(
                        label = "Sort: ${state.sort.field.label} ${directionMark(state.sort.order)}",
                        focusKey = KEY_SORT,
                        selected = overlay == LibraryOverlay.SORT,
                        requesters = requesters,
                        onFocused = {
                            lastControlKey = it
                            focusZone = LibraryFocusZone.CONTROLS
                        },
                        onLeft = {
                            focusKey(if (state.hasFilters) KEY_FILTERS else KEY_VIEW_SELECTOR)
                            true
                        },
                        onRight = { moveControl(KEY_SORT, +1) },
                        onDown = { enterContent(); true },
                        onUp = { true },
                        onActivate = {
                            if (overlay == LibraryOverlay.SORT) {
                                closeOverlay(KEY_SORT)
                            } else {
                                openOverlay(LibraryOverlay.SORT)
                            }
                        },
                        tag = "library-control-sort",
                    )
                }
                if (musicPlayAvailable) {
                    LibraryControl(
                        label = "Play",
                        focusKey = KEY_PLAY_CONTEXT,
                        selected = false,
                        requesters = requesters,
                        onFocused = {
                            lastControlKey = it
                            focusZone = LibraryFocusZone.CONTROLS
                        },
                        onLeft = { moveControl(KEY_PLAY_CONTEXT, -1) },
                        onRight = { moveControl(KEY_PLAY_CONTEXT, +1) },
                        onDown = { enterContent(); true },
                        onUp = { true },
                        onActivate = { onMusicPlayAll?.invoke() },
                        tag = "library-control-play",
                    )
                }
                if (musicShuffleAvailable) {
                    LibraryControl(
                        label = "Shuffle",
                        focusKey = KEY_SHUFFLE_CONTEXT,
                        selected = musicQueue?.shuffleEnabled == true,
                        requesters = requesters,
                        onFocused = {
                            lastControlKey = it
                            focusZone = LibraryFocusZone.CONTROLS
                        },
                        onLeft = { moveControl(KEY_SHUFFLE_CONTEXT, -1) },
                        onRight = { moveControl(KEY_SHUFFLE_CONTEXT, +1) },
                        onDown = { enterContent(); true },
                        onUp = { true },
                        onActivate = { onMusicShuffleAll?.invoke() },
                        tag = "library-control-shuffle",
                    )
                }
                if (musicQueue?.hasQueue == true) {
                    LibraryControl(
                        label = "Now Playing",
                        focusKey = KEY_NOW_PLAYING,
                        selected = false,
                        requesters = requesters,
                        onFocused = {
                            lastControlKey = it
                            focusZone = LibraryFocusZone.CONTROLS
                        },
                        onLeft = { moveControl(KEY_NOW_PLAYING, -1) },
                        onRight = { true },
                        onDown = { enterContent(); true },
                        onUp = { true },
                        onActivate = { onMusicNowPlaying() },
                        tag = "library-control-now-playing",
                    )
                }
            }

            if (state.support is LibrarySupport.Supported &&
                state.presentation != LibraryPresentation.SECTIONS
            ) {
                projection.countLabel?.let { label ->
                    Text(
                        text = "$label items",
                        style = InfusionTvScaleCalibration.Type.Body,
                        modifier = Modifier.padding(start = 52.dp, bottom = 4.dp),
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {

                    state.support is LibrarySupport.Unsupported -> LibraryStatusLine(
                        text = "This library type is not supported yet.",
                        modifier = Modifier.testTag("library-unsupported"),
                    )

                    state.presentation == LibraryPresentation.SECTIONS -> SectionsContent(
                        state = state,
                        requesters = requesters,
                        entryRequest = suggestionEntry,
                        onEntryConsumed = { request ->

                            if (suggestionEntry === request) suggestionEntry = null
                        },
                        onFocusReport = { itemKey ->
                            focusZone = LibraryFocusZone.CONTENT
                            lastContentKey = itemKey
                        },
                        onOpenItem = { item ->
                            stateHolder.captureReturn(
                                focusedItemId = item.key,
                                focusedServerIndex = null,
                                firstVisibleVisibleIndex = 0,
                                firstVisibleServerIndex = null,
                                firstVisibleScrollOffsetPx = 0,
                            )

                            if (item.unavailable) onOpenItemDetails(item) else onOpenItem(item)
                        },
                        onExitUp = { exitToControls(); true },
                        onRetry = { stateHolder.retrySections() },
                    )

                    state.presentation == LibraryPresentation.LIST -> ListContent(
                        state = state,
                        projection = projection,
                        gridState = gridState,
                        requesters = requesters,
                        focusTargetKey = gridFocusKey,
                        focusTargetRevision = gridFocusRevision,
                        onFocusChanged = { item, absolute ->
                            focusedItemId = item?.key
                            focusedServerIndex = absolute
                            if (item != null) {
                                focusZone = LibraryFocusZone.CONTENT
                                lastContentKey = item.key
                            }
                            if (item != null && absolute != null) {
                                stateHolder.onAnchors(
                                    firstVisibleServerIndex = firstVisibleServerIndex,
                                    focusedServerIndex = absolute,
                                    focusedItemId = item.key,
                                )
                            }
                        },
                        onFirstVisibleChanged = { absolute ->
                            firstVisibleServerIndex = absolute
                            stateHolder.onAnchors(
                                firstVisibleServerIndex = absolute,
                                focusedServerIndex = focusedServerIndex,
                                focusedItemId = focusedItemId,
                            )
                        },
                        onNearEnd = { stateHolder.onForwardHorizon() },
                        onUnloadedEntered = { stateHolder.ensureLoaded(it) },
                        onFooterActivated = {
                            when (state.footer) {
                                is LibraryFooter.Retry -> stateHolder.retryAppend()
                                LibraryFooter.Continue -> stateHasContinuation(stateHolder)
                                else -> Unit
                            }
                        },
                        onEmptyReset = {
                            stateHolder.resetFilters()
                            focusKey(KEY_VIEW_SELECTOR)
                        },
                        onRetryFirstPage = { stateHolder.retryFirstPage() },
                        onExitUp = { exitToControls(); true },
                        onOpenItem = { item ->
                            stateHolder.captureReturn(
                                focusedItemId = item.key,
                                focusedServerIndex = focusedServerIndex,
                                firstVisibleVisibleIndex = (projection.cells.indexOfFirst {
                                    it.absoluteServerIndex == firstVisibleServerIndex
                                }).coerceAtLeast(0),
                                firstVisibleServerIndex = firstVisibleServerIndex,
                                firstVisibleScrollOffsetPx = 0,
                            )

                            val playSong = onMusicPlaySong
                            if (playSong != null && item.isSongRow()) {
                                playSong(item, focusedServerIndex)
                            } else {
                                onOpenItem(item)
                            }
                        },
                    )

                    else -> GridContent(
                        state = state,
                        projection = projection,
                        columns = columns,
                        shape = shape,
                        categoryTiles = categoryTiles,
                        gridState = gridState,
                        requesters = requesters,
                        focusTargetKey = gridFocusKey,
                        focusTargetRevision = gridFocusRevision,
                        onFocusChanged = { item, absolute ->
                            focusedItemId = item?.key
                            focusedServerIndex = absolute
                            if (item != null) {
                                focusZone = LibraryFocusZone.CONTENT
                                lastContentKey = item.key
                            }
                            if (item != null && absolute != null) {
                                stateHolder.onAnchors(
                                    firstVisibleServerIndex = firstVisibleServerIndex,
                                    focusedServerIndex = absolute,
                                    focusedItemId = item.key,
                                )
                            }
                        },
                        onFirstVisibleChanged = { absolute ->
                            firstVisibleServerIndex = absolute
                            stateHolder.onAnchors(
                                firstVisibleServerIndex = absolute,
                                focusedServerIndex = focusedServerIndex,
                                focusedItemId = focusedItemId,
                            )
                        },
                        onNearEnd = { stateHolder.onForwardHorizon() },
                        onUnloadedEntered = { stateHolder.ensureLoaded(it) },
                        onFooterActivated = {
                            when (state.footer) {
                                is LibraryFooter.Retry -> stateHolder.retryAppend()
                                LibraryFooter.Continue -> stateHasContinuation(stateHolder)
                                else -> Unit
                            }
                        },
                        onEmptyReset = {
                            stateHolder.resetFilters()
                            focusKey(KEY_VIEW_SELECTOR)
                        },
                        onRetryFirstPage = { stateHolder.retryFirstPage() },
                        onExitUp = { exitToControls(); true },
                        onOpenItem = { item ->
                            stateHolder.captureReturn(
                                focusedItemId = item.key,
                                focusedServerIndex = focusedServerIndex,
                                firstVisibleVisibleIndex = (projection.cells.indexOfFirst {
                                    it.absoluteServerIndex == firstVisibleServerIndex
                                }).coerceAtLeast(0),
                                firstVisibleServerIndex = firstVisibleServerIndex,
                                firstVisibleScrollOffsetPx = 0,
                            )
                            onOpenItem(item)
                        },
                        onOpenCategory = { item ->

                            stateHolder.captureReturn(
                                focusedItemId = item.key,
                                focusedServerIndex = focusedServerIndex,
                                firstVisibleVisibleIndex = (projection.cells.indexOfFirst {
                                    it.absoluteServerIndex == firstVisibleServerIndex
                                }).coerceAtLeast(0),
                                firstVisibleServerIndex = firstVisibleServerIndex,
                                firstVisibleScrollOffsetPx = 0,
                            )
                            val type = state.type
                            val child = if (type != null) {
                                LibraryCapabilities.childLevel(
                                    type = type,
                                    view = state.view,
                                    level = state.level,
                                    nativeId = item.nativeItemId,
                                    title = item.displayTitle,
                                )
                            } else {
                                null
                            }
                            if (child != null) stateHolder.openLevel(child)
                        },
                    )
                }
            }
        }

        when (overlay) {
            LibraryOverlay.VIEWS -> ViewSelectorOverlay(
                views = state.views,
                active = state.view,
                requesters = requesters,
                offset = IntOffset(insetPx, overlayTopPx),
                maxHeight = overlayMaxHeight,
                onSelect = { view ->
                    stateHolder.selectView(view)
                    closeOverlay(KEY_VIEW_SELECTOR)
                },
                onDismiss = { closeOverlay(KEY_VIEW_SELECTOR) },
            )

            LibraryOverlay.FILTERS -> {
                FilterGroupsOverlay(
                    groups = state.filterGroups,
                    options = state.filterOptions,
                    phase = state.filterOptionsPhase,
                    filters = state.filters,
                    requesters = requesters,
                    offset = IntOffset(-insetPx, overlayTopPx),
                    maxHeight = overlayMaxHeight,

                    onMeasured = { widthPx -> filtersWindowWidthPx = widthPx },
                    childOpen = openFilterGroup != null,
                    onOpenGroup = { group ->
                        openFilterGroup = group
                        filterGroupEntryKey(group, state.filterOptions)?.let { requestFocusAcross(it) }
                    },
                    onReset = { stateHolder.resetFilters() },
                    onRetry = { stateHolder.retryFilterOptions() },
                    onDismiss = { closeOverlay(KEY_FILTERS) },
                )
                openFilterGroup?.let { group ->
                    FilterValuesOverlay(
                        group = group,
                        options = state.filterOptions ?: LibraryFilterOptions(),
                        filters = state.filters,
                        holder = stateHolder,
                        requesters = requesters,

                        offset = IntOffset(
                            -(
                                insetPx + gapPx +
                                    (filtersWindowWidthPx.takeIf { it > 0 }
                                        ?: with(density) { LibraryOverlayMaxWidth.roundToPx() })
                                ),
                            overlayTopPx,
                        ),
                        maxHeight = overlayMaxHeight,
                        onDismiss = {
                            val restore = groupKey(group)
                            openFilterGroup = null
                            if (restore != null) requestFocusAcross(restore)
                        },
                    )
                }
            }

            LibraryOverlay.SORT -> SortOverlay(
                fields = state.sortFields,
                active = state.sort.field,
                order = state.sort.order,
                requesters = requesters,
                offset = IntOffset(-insetPx, overlayTopPx),
                maxHeight = overlayMaxHeight,
                onSelect = { field ->
                    stateHolder.selectSort(field)
                    closeOverlay(KEY_SORT)
                },
                onDismiss = { closeOverlay(KEY_SORT) },
            )

            null -> Unit
        }
    }
}

private fun JellyfinTabRowItem.isSongRow(): Boolean =
    !isFolder && (albumName != null || albumArtistName != null)

private fun activeViewLabel(state: LibraryBrowseUiState): String = when (val level = state.level) {
    LibraryLevel.Root -> state.view.label
    is LibraryLevel.Genre -> level.genreName
    is LibraryLevel.BoxSet -> level.boxSetName
    is LibraryLevel.Network -> level.networkName
    is LibraryLevel.Artist -> level.artistName
    is LibraryLevel.Album -> level.albumName
    is LibraryLevel.Playlist -> level.playlistName
}

private fun directionMark(order: LibrarySortOrder): String =
    if (order == LibrarySortOrder.ASCENDING) "▲" else "▼"

private fun stateHasContinuation(holder: LibraryBrowseStateHolder) {
    holder.onForwardHorizon()
}

private fun viewKey(view: LibraryView?): String? = view?.let { "library-view:${it.name.lowercase()}" }

private fun sortKey(field: LibrarySortField?): String? = field?.let { "library-sort:${it.name.lowercase()}" }

private fun groupKey(group: LibraryFilterGroup?): String? = group?.let { "library-filter-group:${it.name.lowercase()}" }

@Composable
private fun LibraryControl(
    label: String,
    focusKey: String,
    selected: Boolean,
    requesters: FocusKeyRegistry,
    onFocused: (String) -> Unit,
    onLeft: () -> Boolean,
    onRight: () -> Boolean,
    onDown: () -> Boolean,
    onUp: () -> Boolean,
    onActivate: () -> Unit,
    tag: String,
    selectorMark: Boolean = false,
) {
    InfusionTvButton(
        onClick = onActivate,
        selected = selected,
        modifier = stableFocusModifier(
            key = focusKey,
            requesters = requesters,
            onFocusedKeyChanged = onFocused,
        )
            .testTag(tag)
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (event.type != KeyEventType.KeyDown) true else onLeft()
                    }
                    Key.DirectionRight -> {
                        if (event.type != KeyEventType.KeyDown) true else onRight()
                    }
                    Key.DirectionDown -> {
                        if (event.type != KeyEventType.KeyDown) true else onDown()
                    }
                    Key.DirectionUp -> {
                        if (event.type != KeyEventType.KeyDown) true else onUp()
                    }
                    else -> false
                }
            },
    ) {
        LibrarySelectorLabel(label = label, withChevron = selectorMark)
    }
}

@Composable
private fun ViewSelectorOverlay(
    views: List<LibraryView>,
    active: LibraryView,
    requesters: FocusKeyRegistry,
    offset: IntOffset,
    maxHeight: Dp,
    onSelect: (LibraryView) -> Unit,
    onDismiss: () -> Unit,
) {
    TvOverlayWindow(
        tag = "library-view-selector",
        alignment = Alignment.TopStart,
        offset = offset,
        maxHeight = maxHeight,
        onDismiss = onDismiss,
    ) {
        views.forEach { view ->
            val key = viewKey(view) ?: return@forEach
            TvOverlayOption(
                label = view.label,
                selected = view == active,
                focusKey = key,
                requesters = requesters,
                onClick = { onSelect(view) },
            )
        }
    }
}

@Composable
private fun FilterGroupsOverlay(
    groups: List<LibraryFilterGroup>,
    options: LibraryFilterOptions?,
    phase: LibraryContentPhase,
    filters: LibraryFilterValues,
    requesters: FocusKeyRegistry,
    offset: IntOffset,
    maxHeight: Dp,

    onMeasured: (Int) -> Unit,

    childOpen: Boolean,
    onOpenGroup: (LibraryFilterGroup) -> Unit,
    onReset: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    TvOverlayWindow(
        tag = "library-filter-panel",
        alignment = Alignment.TopEnd,
        offset = offset,
        maxHeight = maxHeight,
        modifier = Modifier.onGloballyPositioned { coordinates -> onMeasured(coordinates.size.width) },
        onDismiss = onDismiss,

        contentAlpha = if (childOpen) 0.55f else 1f,
    ) {
        groups.forEach { group ->
            val key = groupKey(group) ?: return@forEach
            val summary = filterGroupSummary(group, filters)
            val enabled = filterGroupEnabled(group, options)
            TvOverlayOption(
                label = if (summary != null) "${group.label} · $summary" else group.label,
                selected = summary != null,
                enabled = enabled,
                focusKey = key,
                requesters = requesters,
                opensChild = true,
                onClick = { if (enabled) onOpenGroup(group) },
            )
        }
        if (phase is LibraryContentPhase.Failed) {
            TvOverlayOption(
                label = "Retry filter values",
                selected = false,
                focusKey = KEY_FILTER_RETRY,
                requesters = requesters,
                onClick = onRetry,
            )
        }
        TvOverlayOption(
            label = "Reset filters",
            selected = false,
            focusKey = KEY_FILTER_RESET,
            requesters = requesters,
            onClick = onReset,
        )
    }
}

@Composable
private fun FilterValuesOverlay(
    group: LibraryFilterGroup,
    options: LibraryFilterOptions,
    filters: LibraryFilterValues,
    holder: LibraryBrowseStateHolder,
    requesters: FocusKeyRegistry,
    offset: IntOffset,
    maxHeight: Dp,
    onDismiss: () -> Unit,
) {
    TvOverlayWindow(
        tag = "library-filter-values",
        alignment = Alignment.TopEnd,
        offset = offset,
        maxHeight = maxHeight,
        onDismiss = onDismiss,
    ) {
        when (group) {
            LibraryFilterGroup.STATUS -> options.statuses.forEach { status ->
                TvOverlayOption(
                    label = status.label,
                    selected = status in filters.statuses,
                    focusKey = statusKey(status),
                    requesters = requesters,
                    onClick = { holder.toggleStatus(status) },
                )
            }

            LibraryFilterGroup.SERIES_STATUS -> options.seriesStatuses.forEach { status ->
                TvOverlayOption(
                    label = status.label,
                    selected = filters.seriesStatus == status,
                    focusKey = seriesStatusKey(status),
                    requesters = requesters,

                    onClick = { holder.selectSeriesStatus(status) },
                )
            }

            LibraryFilterGroup.EPISODE_FLAGS -> options.episodeFlags.forEach { flag ->
                TvOverlayOption(
                    label = flag.label,
                    selected = flag in filters.episodeFlags,
                    focusKey = episodeFlagKey(flag),
                    requesters = requesters,
                    onClick = { holder.toggleEpisodeFlag(flag) },
                )
            }

            LibraryFilterGroup.FEATURES -> options.features.forEach { feature ->
                TvOverlayOption(
                    label = feature.label,
                    selected = feature in filters.features,
                    focusKey = featureKey(feature),
                    requesters = requesters,
                    onClick = { holder.toggleFeature(feature) },
                )
            }

            LibraryFilterGroup.GENRES -> {
                TvOverlayOption(
                    label = "All genres",
                    selected = filters.genre == null,
                    focusKey = KEY_FILTER_ANY_GENRE,
                    requesters = requesters,
                    onClick = { holder.selectGenreFilter(null) },
                )
                options.genres.forEach { genre ->
                    TvOverlayOption(
                        label = genre.label,
                        selected = filters.genre == genre.value,
                        focusKey = "library-filter:genre:${genre.value.lowercase()}",
                        requesters = requesters,
                        onClick = {
                            holder.selectGenreFilter(if (filters.genre == genre.value) null else genre.value)
                        },
                    )
                }
            }

            LibraryFilterGroup.PARENTAL -> {
                TvOverlayOption(
                    label = "All ratings",
                    selected = filters.officialRating == null,
                    focusKey = KEY_FILTER_ANY_RATING,
                    requesters = requesters,
                    onClick = { holder.selectOfficialRating(null) },
                )
                options.officialRatings.forEach { rating ->
                    TvOverlayOption(
                        label = rating.label,
                        selected = filters.officialRating == rating.value,
                        focusKey = "library-filter:rating:${rating.value.lowercase()}",
                        requesters = requesters,
                        onClick = {
                            holder.selectOfficialRating(
                                if (filters.officialRating == rating.value) null else rating.value,
                            )
                        },
                    )
                }
            }

            LibraryFilterGroup.TAGS -> {
                TvOverlayOption(
                    label = "All tags",
                    selected = filters.tag == null,
                    focusKey = KEY_FILTER_ANY_TAG,
                    requesters = requesters,
                    onClick = { holder.selectTag(null) },
                )
                options.tags.forEach { tag ->
                    TvOverlayOption(
                        label = tag.label,
                        selected = filters.tag == tag.value,
                        focusKey = "library-filter:tag:${tag.value.lowercase().hashCode()}",
                        requesters = requesters,
                        onClick = { holder.selectTag(if (filters.tag == tag.value) null else tag.value) },
                    )
                }
            }

            LibraryFilterGroup.VIDEO_TYPE -> {
                TvOverlayOption(
                    label = "All video types",
                    selected = filters.videoType == null,
                    focusKey = KEY_FILTER_ANY_VIDEO,
                    requesters = requesters,
                    onClick = { holder.selectVideoType(null) },
                )
                options.videoTypes.forEach { type ->
                    TvOverlayOption(
                        label = type.label,
                        selected = filters.videoType == type,
                        focusKey = "library-filter:video:${type.name.lowercase()}",
                        requesters = requesters,
                        onClick = { holder.selectVideoType(if (filters.videoType == type) null else type) },
                    )
                }
            }

            LibraryFilterGroup.YEARS -> options.years.forEach { year ->
                TvOverlayOption(
                    label = year.toString(),
                    selected = year in filters.years,
                    focusKey = "library-filter:year:$year",
                    requesters = requesters,
                    onClick = { holder.toggleYear(year) },
                )
            }
        }
    }
}

@Composable
private fun SortOverlay(
    fields: List<LibrarySortField>,
    active: LibrarySortField,
    order: LibrarySortOrder,
    requesters: FocusKeyRegistry,
    offset: IntOffset,
    maxHeight: Dp,
    onSelect: (LibrarySortField) -> Unit,
    onDismiss: () -> Unit,
) {
    TvOverlayWindow(
        tag = "library-sort-panel",
        alignment = Alignment.TopEnd,
        offset = offset,
        maxHeight = maxHeight,
        onDismiss = onDismiss,
    ) {
        fields.forEach { field ->
            val key = sortKey(field) ?: return@forEach
            TvOverlayOption(
                label = if (field == active) "${field.label} ${directionMark(order)}" else field.label,
                selected = field == active,
                focusKey = key,
                requesters = requesters,
                onClick = { onSelect(field) },
            )
        }
    }
}

private fun filterGroupSummary(group: LibraryFilterGroup, filters: LibraryFilterValues): String? = when (group) {
    LibraryFilterGroup.STATUS -> filters.statuses
        .takeIf { it.isNotEmpty() }
        ?.let { set -> set.joinToString(", ") { it.label } }

    LibraryFilterGroup.SERIES_STATUS -> filters.seriesStatus?.label

    LibraryFilterGroup.EPISODE_FLAGS -> filters.episodeFlags.size.takeIf { it > 0 }?.let { count ->
        if (count == 1) filters.episodeFlags.first().label else "$count selected"
    }

    LibraryFilterGroup.FEATURES -> filters.features.size.takeIf { it > 0 }?.let { count ->
        if (count == 1) filters.features.first().label else "$count selected"
    }

    LibraryFilterGroup.GENRES -> filters.genre
    LibraryFilterGroup.PARENTAL -> filters.officialRating
    LibraryFilterGroup.TAGS -> filters.tag
    LibraryFilterGroup.VIDEO_TYPE -> filters.videoType?.label
    LibraryFilterGroup.YEARS -> filters.years.size.takeIf { it > 0 }?.let { count ->
        if (count == 1) filters.years.first().toString() else "$count selected"
    }
}

private fun filterGroupEnabled(group: LibraryFilterGroup, options: LibraryFilterOptions?): Boolean = when (group) {
    LibraryFilterGroup.GENRES -> options?.genres?.isNotEmpty() == true
    LibraryFilterGroup.PARENTAL -> options?.officialRatings?.isNotEmpty() == true
    LibraryFilterGroup.TAGS -> options?.tags?.isNotEmpty() == true
    LibraryFilterGroup.YEARS -> options?.years?.isNotEmpty() == true
    LibraryFilterGroup.STATUS, LibraryFilterGroup.SERIES_STATUS, LibraryFilterGroup.EPISODE_FLAGS,
    LibraryFilterGroup.FEATURES, LibraryFilterGroup.VIDEO_TYPE,
    -> true
}

private fun filterGroupEntryKey(group: LibraryFilterGroup, options: LibraryFilterOptions?): String? = when (group) {
    LibraryFilterGroup.STATUS -> statusKey(options?.statuses?.firstOrNull() ?: LibraryStatusFilter.entries.first())
    LibraryFilterGroup.SERIES_STATUS ->
        seriesStatusKey(options?.seriesStatuses?.firstOrNull() ?: LibrarySeriesStatus.entries.first())
    LibraryFilterGroup.EPISODE_FLAGS ->
        episodeFlagKey(options?.episodeFlags?.firstOrNull() ?: LibraryEpisodeFlag.entries.first())
    LibraryFilterGroup.FEATURES -> featureKey(options?.features?.firstOrNull() ?: LibraryFeature.entries.first())
    LibraryFilterGroup.GENRES -> KEY_FILTER_ANY_GENRE
    LibraryFilterGroup.PARENTAL -> KEY_FILTER_ANY_RATING
    LibraryFilterGroup.TAGS -> KEY_FILTER_ANY_TAG
    LibraryFilterGroup.VIDEO_TYPE -> KEY_FILTER_ANY_VIDEO
    LibraryFilterGroup.YEARS -> options?.years?.firstOrNull()?.let { "library-filter:year:$it" }
}

private fun statusKey(status: LibraryStatusFilter): String = "library-filter:status:${status.name.lowercase()}"

private fun seriesStatusKey(status: LibrarySeriesStatus): String =
    "library-filter:series-status:${status.name.lowercase()}"

private fun episodeFlagKey(flag: LibraryEpisodeFlag): String = "library-filter:episode-flag:${flag.name.lowercase()}"

private fun featureKey(feature: LibraryFeature): String = "library-filter:feature:${feature.name.lowercase()}"

@Composable
private fun GridContent(
    state: LibraryBrowseUiState,
    projection: LibraryProjection,
    columns: Int,
    shape: dev.infusion.tv.ui.presentation.ContentCardShape,
    categoryTiles: Map<String, dev.infusion.tv.ui.presentation.LibraryCategoryTile>,
    gridState: androidx.compose.foundation.lazy.LazyListState,
    requesters: FocusKeyRegistry,
    focusTargetKey: String?,
    focusTargetRevision: Int,
    onFocusChanged: (JellyfinTabRowItem?, Int?) -> Unit,
    onFirstVisibleChanged: (Int?) -> Unit,
    onNearEnd: () -> Unit,
    onUnloadedEntered: (Int) -> Unit,
    onFooterActivated: () -> Unit,
    onEmptyReset: () -> Unit,
    onRetryFirstPage: () -> Unit,
    onExitUp: () -> Boolean,
    onOpenItem: (JellyfinTabRowItem) -> Unit,
    onOpenCategory: (JellyfinTabRowItem) -> Unit,
) {

    val opensLevel = state.type?.let {
        LibraryCapabilities.opensChildLevel(it, state.view, state.level)
    } == true
    val open: (JellyfinTabRowItem) -> Unit = { item ->
        if (opensLevel) onOpenCategory(item) else onOpenItem(item)
    }

    when (val phase = state.phase) {
        LibraryContentPhase.Loading -> if (projection.cells.isEmpty()) {
            LibraryStatusLine("Loading…", Modifier.testTag("library-loading"))
        } else {
            GridBody(state, projection, columns, shape, categoryTiles, gridState, requesters, focusTargetKey, focusTargetRevision, onFocusChanged, onFirstVisibleChanged, onNearEnd, onUnloadedEntered, onFooterActivated, onExitUp, open)
        }

        is LibraryContentPhase.Failed -> Column(Modifier.tvBrowseNonRailInset()) {
            LibraryStatusLine("Couldn't load this library: ${phase.message}")
            InfusionTvButton(
                onClick = onRetryFirstPage,
                modifier = stableFocusModifier(
                    key = KEY_CONTENT_RETRY,
                    requesters = requesters,

                    onFocusedKeyChanged = {
                        requesters.onFocusReport(it)
                        onFocusChanged(null, null)
                    },
                ).testTag("library-retry"),
            ) { Text("Retry") }
        }

        LibraryContentPhase.Empty -> Column(Modifier.tvBrowseNonRailInset()) {

            LibraryStatusLine(
                text = if (!state.filters.isEmpty) {
                    "No ${activeViewLabel(state)} match these filters."
                } else {
                    "There are no ${activeViewLabel(state)} in this library."
                },
                modifier = Modifier.testTag("library-empty"),
            )
            InfusionTvButton(
                onClick = onEmptyReset,
                modifier = stableFocusModifier(
                    key = KEY_EMPTY_RESET,
                    requesters = requesters,
                    onFocusedKeyChanged = {
                        requesters.onFocusReport(it)
                        onFocusChanged(null, null)
                    },
                ).testTag("library-empty-reset"),
            ) { Text(if (!state.filters.isEmpty) "Reset filters" else "Clear view") }
        }

        LibraryContentPhase.Loaded -> if (projection.cells.isEmpty()) {

            Column(Modifier.tvBrowseNonRailInset()) {
                LibraryStatusLine(
                    text = "Every item loaded so far is hidden by the watched filter.",
                    modifier = Modifier.testTag("library-all-hidden"),
                )
                InfusionTvButton(
                    onClick = onNearEnd,
                    modifier = stableFocusModifier(
                        key = KEY_GRID_FOOTER,
                        requesters = requesters,
                        onFocusedKeyChanged = {
                            requesters.onFocusReport(it)
                            onFocusChanged(null, null)
                        },
                    ),
                ) { Text("Load more") }
            }
        } else {
            GridBody(state, projection, columns, shape, categoryTiles, gridState, requesters, focusTargetKey, focusTargetRevision, onFocusChanged, onFirstVisibleChanged, onNearEnd, onUnloadedEntered, onFooterActivated, onExitUp, open)
        }
    }
}

@Composable
private fun GridBody(
    state: LibraryBrowseUiState,
    projection: LibraryProjection,
    columns: Int,
    shape: dev.infusion.tv.ui.presentation.ContentCardShape,
    categoryTiles: Map<String, dev.infusion.tv.ui.presentation.LibraryCategoryTile>,
    gridState: androidx.compose.foundation.lazy.LazyListState,
    requesters: FocusKeyRegistry,
    focusTargetKey: String?,
    focusTargetRevision: Int,
    onFocusChanged: (JellyfinTabRowItem?, Int?) -> Unit,
    onFirstVisibleChanged: (Int?) -> Unit,
    onNearEnd: () -> Unit,
    onUnloadedEntered: (Int) -> Unit,
    onFooterActivated: () -> Unit,
    onExitUp: () -> Boolean,
    onOpenItem: (JellyfinTabRowItem) -> Unit,
) {
    TvMediaGrid(
        cells = projection.cells,
        columns = columns,
        shape = shape,
        categoryTiles = categoryTiles,
        footer = state.footer,
        requesters = requesters,
        footerKey = if (state.footer != null) KEY_GRID_FOOTER else null,
        hasMore = projection.hasMore,
        focusTargetKey = focusTargetKey,
        focusTargetRevision = focusTargetRevision,
        onFocusChanged = onFocusChanged,
        onFirstVisibleChanged = onFirstVisibleChanged,
        onNearEnd = onNearEnd,
        onUnloadedEntered = onUnloadedEntered,
        onOpenItem = onOpenItem,
        onFooterActivated = onFooterActivated,
        onExitUp = onExitUp,
    )
}

@Composable
private fun ListContent(
    state: LibraryBrowseUiState,
    projection: LibraryProjection,
    gridState: androidx.compose.foundation.lazy.LazyListState,
    requesters: FocusKeyRegistry,
    focusTargetKey: String?,
    focusTargetRevision: Int,
    onFocusChanged: (JellyfinTabRowItem?, Int?) -> Unit,
    onFirstVisibleChanged: (Int?) -> Unit,
    onNearEnd: () -> Unit,
    onUnloadedEntered: (Int) -> Unit,
    onFooterActivated: () -> Unit,
    onEmptyReset: () -> Unit,
    onRetryFirstPage: () -> Unit,
    onExitUp: () -> Boolean,
    onOpenItem: (JellyfinTabRowItem) -> Unit,
) {
    when (val phase = state.phase) {
        LibraryContentPhase.Loading -> if (projection.cells.isEmpty()) {
            LibraryStatusLine("Loading…", Modifier.testTag("library-loading"))
        } else {
            ListBody(
                state, projection, gridState, requesters, focusTargetKey, focusTargetRevision,
                onFocusChanged, onFirstVisibleChanged, onNearEnd, onUnloadedEntered,
                onFooterActivated, onExitUp, onOpenItem,
            )
        }

        is LibraryContentPhase.Failed -> Column(Modifier.tvBrowseNonRailInset()) {
            LibraryStatusLine("Couldn't load this library: ${phase.message}")
            InfusionTvButton(
                onClick = onRetryFirstPage,
                modifier = stableFocusModifier(
                    key = KEY_CONTENT_RETRY,
                    requesters = requesters,
                    onFocusedKeyChanged = {
                        requesters.onFocusReport(it)
                        onFocusChanged(null, null)
                    },
                ).testTag("library-retry"),
            ) { Text("Retry") }
        }

        LibraryContentPhase.Empty -> Column(Modifier.tvBrowseNonRailInset()) {
            LibraryStatusLine(
                text = if (!state.filters.isEmpty) {
                    "No ${activeViewLabel(state)} match these filters."
                } else {
                    "There are no ${activeViewLabel(state)} in this library."
                },
                modifier = Modifier.testTag("library-empty"),
            )
            InfusionTvButton(
                onClick = onEmptyReset,
                modifier = stableFocusModifier(
                    key = KEY_EMPTY_RESET,
                    requesters = requesters,
                    onFocusedKeyChanged = {
                        requesters.onFocusReport(it)
                        onFocusChanged(null, null)
                    },
                ).testTag("library-empty-reset"),
            ) { Text(if (!state.filters.isEmpty) "Reset filters" else "Clear view") }
        }

        LibraryContentPhase.Loaded -> if (projection.cells.isEmpty()) {
            Column(Modifier.tvBrowseNonRailInset()) {
                LibraryStatusLine(
                    text = "Every item loaded so far is hidden by the watched filter.",
                    modifier = Modifier.testTag("library-all-hidden"),
                )
                InfusionTvButton(
                    onClick = onNearEnd,
                    modifier = stableFocusModifier(
                        key = KEY_GRID_FOOTER,
                        requesters = requesters,
                        onFocusedKeyChanged = {
                            requesters.onFocusReport(it)
                            onFocusChanged(null, null)
                        },
                    ),
                ) { Text("Load more") }
            }
        } else {
            ListBody(
                state, projection, gridState, requesters, focusTargetKey, focusTargetRevision,
                onFocusChanged, onFirstVisibleChanged, onNearEnd, onUnloadedEntered,
                onFooterActivated, onExitUp, onOpenItem,
            )
        }
    }
}

@Composable
private fun ListBody(
    state: LibraryBrowseUiState,
    projection: LibraryProjection,
    gridState: androidx.compose.foundation.lazy.LazyListState,
    requesters: FocusKeyRegistry,
    focusTargetKey: String?,
    focusTargetRevision: Int,
    onFocusChanged: (JellyfinTabRowItem?, Int?) -> Unit,
    onFirstVisibleChanged: (Int?) -> Unit,
    onNearEnd: () -> Unit,
    onUnloadedEntered: (Int) -> Unit,
    onFooterActivated: () -> Unit,
    onExitUp: () -> Boolean,
    onOpenItem: (JellyfinTabRowItem) -> Unit,
) {
    TvMediaList(
        cells = projection.cells,
        footer = state.footer,
        requesters = requesters,
        footerKey = if (state.footer != null) KEY_GRID_FOOTER else null,
        hasMore = projection.hasMore,
        onFocusChanged = onFocusChanged,
        onFirstVisibleChanged = onFirstVisibleChanged,
        onNearEnd = onNearEnd,
        onUnloadedEntered = onUnloadedEntered,
        onOpenItem = onOpenItem,
        onFooterActivated = onFooterActivated,
        onExitUp = onExitUp,
        listState = gridState,
        focusTargetKey = focusTargetKey,
        focusTargetRevision = focusTargetRevision,
    )
}

private data class SuggestionEntry(val preferredItemKey: String?)

private fun suggestionCardFocusKey(sectionKey: String, itemKey: String): String =
    "library-suggestion:$sectionKey:$itemKey"

@Composable
private fun SectionsContent(
    state: LibraryBrowseUiState,
    requesters: FocusKeyRegistry,
    entryRequest: SuggestionEntry?,
    onEntryConsumed: (SuggestionEntry) -> Unit,
    onFocusReport: (String) -> Unit,
    onOpenItem: (JellyfinTabRowItem) -> Unit,
    onExitUp: () -> Boolean,
    onRetry: () -> Unit,
) {
    when (val phase = state.sections) {
        LibraryContentPhase.Loading -> LibraryStatusLine("Loading…", Modifier.testTag("library-sections-loading"))
        is LibraryContentPhase.Failed -> Column(Modifier.tvBrowseNonRailInset()) {
            LibraryStatusLine("Couldn't load this view: ${phase.message}")
            InfusionTvButton(
                onClick = onRetry,
                modifier = stableFocusModifier(
                    key = KEY_CONTENT_RETRY,
                    requesters = requesters,
                    onFocusedKeyChanged = {
                        requesters.onFocusReport(it)
                        onFocusReport(KEY_CONTENT_RETRY)
                    },
                ),
            ) { Text("Retry") }
        }
        LibraryContentPhase.Empty -> LibraryStatusLine(
            "There is nothing to show in ${activeViewLabel(state)}.",
            Modifier.testTag("library-sections-empty"),
        )
        LibraryContentPhase.Loaded -> SectionsRows(
            state = state,
            requesters = requesters,
            entryRequest = entryRequest,
            onEntryConsumed = onEntryConsumed,
            onFocusReport = onFocusReport,
            onOpenItem = onOpenItem,
            onExitUp = onExitUp,
        )
    }
}

@Composable
private fun SectionsRows(
    state: LibraryBrowseUiState,
    requesters: FocusKeyRegistry,
    entryRequest: SuggestionEntry?,
    onEntryConsumed: (SuggestionEntry) -> Unit,
    onFocusReport: (String) -> Unit,
    onOpenItem: (JellyfinTabRowItem) -> Unit,
    onExitUp: () -> Boolean,
) {

    val sections = state.sectionRows
        .map { section -> section to hideWatchedJellyfinItems(section.items) }
        .filter { (_, items) -> items.isNotEmpty() }

    if (sections.isEmpty()) {
        LibraryStatusLine(
            text = "Every item loaded so far is hidden by the watched filter.",
            modifier = Modifier.testTag("library-sections-all-hidden"),
        )
        return
    }

    val rows = remember(sections) { sections.map { (_, items) -> items.map { it.key } } }

    val listState = remember(state.window.key) { androidx.compose.foundation.lazy.LazyListState() }
    val scope = rememberCoroutineScope()
    val rememberedKeys = remember { mutableStateMapOf<Int, String>() }
    var lastSection by remember { mutableIntStateOf(0) }

    fun focusAt(section: Int, preferredItemKey: String?) {
        if (section !in sections.indices) return
        val sectionKey = sections[section].first.key
        val items = sections[section].second
        fun focusKeyOf(item: JellyfinTabRowItem): String = suggestionCardFocusKey(sectionKey, item.key)
        val targetIndex = items.indexOfFirst { it.key == preferredItemKey && requesters.isLive(focusKeyOf(it)) }
            .takeIf { it >= 0 }
            ?: rememberedKeys[section]?.let { key ->
                items.indexOfFirst { it.key == key && requesters.isLive(focusKeyOf(it)) }.takeIf { it >= 0 }
            }
            ?: items.indexOfFirst { requesters.isLive(focusKeyOf(it)) }.takeIf { it >= 0 }
            ?: 0
        val target = items[targetIndex]
        lastSection = section
        rememberedKeys[section] = target.key
        onFocusReport(target.key)
        val focusKey = focusKeyOf(target)
        requesters.arm(focusKey)
        if (requesters.request(focusKey)) {
            requesters.disarm()
        } else {

            scope.launch { listState.scrollToItem(section) }
        }
    }

    LaunchedEffect(entryRequest) {
        val request = entryRequest ?: return@LaunchedEffect
        onEntryConsumed(request)
        val position = LibrarySuggestionNavigation.entry(
            rows = rows,
            preferredItemKey = request.preferredItemKey,
            rememberedSection = lastSection,
        ) ?: return@LaunchedEffect
        focusAt(position.section, rows[position.section].getOrNull(position.item))
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("library-suggestions"),
        verticalArrangement = Arrangement.spacedBy(ContentRowDensity.CardSpacing),
    ) {
        itemsIndexed(sections) { sectionIndex, entry ->
            val section = entry.first
            val items = entry.second
            Column {
                Text(
                    section.title,
                    style = InfusionTvScaleCalibration.Type.Headline,
                    modifier = Modifier.padding(start = 48.dp, top = 12.dp, bottom = 4.dp),
                )
                InfusionContentRow(
                    items = items,
                    key = { it.key },
                    modifier = Modifier.fillMaxWidth(),
                ) { item ->
                    val column = items.indexOfFirst { it.key == item.key }.coerceAtLeast(0)
                    Box(
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            when (event.key) {
                                Key.DirectionUp, Key.DirectionDown -> {
                                    if (event.type != KeyEventType.KeyDown) {
                                        true
                                    } else {
                                        val step = LibrarySuggestionNavigation.step(
                                            current = SuggestionPosition(sectionIndex, column),
                                            up = event.key == Key.DirectionUp,
                                            rows = rows,
                                        )
                                        when (step) {
                                            is SuggestionStep.Move -> {
                                                focusAt(
                                                    section = step.position.section,
                                                    preferredItemKey = rows[step.position.section]
                                                        .getOrNull(step.position.item),
                                                )
                                                true
                                            }

                                            SuggestionStep.ExitUp -> onExitUp()

                                            SuggestionStep.Edge -> true
                                        }
                                    }
                                }
                                else -> false
                            }
                        },
                    ) {
                        JellyfinRowCard(

                            preview = if (state.type == dev.infusion.feature.vertical.jellyfin.library.LibraryType.MUSIC) {
                                item.preview.copy(artwork = libraryListRowArtwork(item))
                            } else {
                                item.preview
                            },
                            cardShape = if (state.type == dev.infusion.feature.vertical.jellyfin.library.LibraryType.MUSIC) {
                                ContentCardShape.SQUARE
                            } else {
                                null
                            },

                            focusKey = suggestionCardFocusKey(section.key, item.key),
                            requesters = requesters,
                            onClick = { onOpenItem(item) },
                            onFocusedKeyChanged = {
                                requesters.onFocusReport(it)
                                rememberedKeys[sectionIndex] = item.key
                                lastSection = sectionIndex
                                onFocusReport(item.key)
                            },
                        )
                    }
                }
            }
        }
    }
}
