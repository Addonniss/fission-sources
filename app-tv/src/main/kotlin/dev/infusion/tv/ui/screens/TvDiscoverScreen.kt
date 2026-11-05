package dev.infusion.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.infusion.tv.ui.presentation.MediaCardArtwork
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.feature.vertical.discover.DiscoverStateHolder
import dev.infusion.source.aggregation.discover.DiscoverShelf
import dev.infusion.source.aggregation.discover.ShelfState
import dev.infusion.tv.ui.focus.FocusKeyRegistry
import dev.infusion.tv.ui.focus.LocalTvZoneEntry
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.presentation.InfusionContentRow
import dev.infusion.tv.ui.presentation.InfusionMediaCard
import dev.infusion.tv.ui.presentation.LocalCardWatchedState
import dev.infusion.tv.ui.presentation.LocalContentCardPresentation
import dev.infusion.tv.ui.presentation.TvBrowseColumn
import dev.infusion.tv.ui.presentation.contentCardArtworkKey
import dev.infusion.tv.ui.presentation.contentCardArtworkTarget
import dev.infusion.tv.ui.presentation.hideWatchedShelf
import dev.infusion.tv.ui.presentation.rememberRowPaging
import dev.infusion.tv.ui.presentation.tvBrowseNonRailInset
import dev.infusion.tv.ui.shell.InfusionTvShell
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration

@Composable
fun TvDiscoverScreen(
    stateHolder: DiscoverStateHolder,
    jellyfinConnected: Boolean,
    model: TvTopNavModel,
    onNavigate: (TopLevelDestination) -> Unit,

    onOpenDetails: (TitleId, TitleKind, MediaPreview) -> Unit,
    onExit: () -> Unit,
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    val requesters = rememberFocusRequesters()
    val chrome = rememberTopLevelPageChrome(TopLevelDestination.DISCOVER, model)

    val rowMemory = remember(model) { model.rowMemoryStore(TopLevelDestination.DISCOVER) }

    LaunchedEffect(stateHolder) { stateHolder.onEnter() }

    val presentation = LocalContentCardPresentation.current
    val watchedState = LocalCardWatchedState.current
    val shelves = state.shelves.map { shelf ->
        hideWatchedShelf(shelf, presentation.hideWatchedTitles, watchedState)
    }

    val defaultFocus = shelves
        .firstOrNull { it.state is ShelfState.Loaded }
        ?.let { shelf ->

            (shelf.state as ShelfState.Loaded).items
                .firstOrNull { it.preview.titleId != null }
                ?.key
        }

    val freshEntryReady = discoverFreshEntryReady(
        declaredShelfKeys = state.declaredShelfKeys,
        shelves = shelves,
        generationComplete = !state.loading,
    )
    val freshEntryFocus = discoverFreshEntryKey(shelves, freshEntryReady)

    val zone = LocalTvZoneEntry.current
    LaunchedEffect(state.configurationGeneration) {
        if (state.configurationGeneration == 0) return@LaunchedEffect
        repeat(DISCOVER_DEPARTURE_REPAIR_FRAMES) {
            withFrameNanos { }
            val owned = shelves.flatMap { shelf ->
                (shelf.state as? ShelfState.Loaded)?.items?.map { it.key }.orEmpty()
            }
            val focused = requesters.lastFocusedKey

            if (focused != null && focused in owned) return@LaunchedEffect

            val fallback = zone?.nearbySurvivor(requesters.lastContentSlot)
                ?: freshEntryFocus
                ?: defaultFocus
                ?: return@LaunchedEffect
            if (requesters.request(fallback)) {

                withFrameNanos { }
                if (requesters.lastFocusedKey == fallback) return@LaunchedEffect
            } else {

                requesters.arm(fallback)
                return@LaunchedEffect
            }
        }
    }

    val arrivedShelfKeys = shelves.mapTo(mutableSetOf()) { it.key }
    val arrivingShelfKeys = state.declaredShelfKeys.filterNot { it in arrivedShelfKeys }.toSet()

    val topLevelBoundaryShelfKey = shelves.firstOrNull { shelf ->
        val shelfState = shelf.state
        shelfState is ShelfState.Loaded && shelfState.items.isNotEmpty()
    }?.key

    val browseRows = buildList {
        if (state.loading && shelves.isEmpty()) {
            add(
                DiscoverBrowseRow(key = "discover:loading", itemKeys = { emptyList() }) {
                    Text("Loading add-on shelves…", style = InfusionTvScaleCalibration.Type.Body)
                },
            )
        }
        shelves.forEach { shelf ->
            add(
                DiscoverBrowseRow(
                    key = shelf.key,
                    itemKeys = {
                        (shelf.state as? ShelfState.Loaded)?.items?.map { it.key }.orEmpty()
                    },
                ) {
                    PagedDiscoverShelfBlock(
                        shelf = shelf,
                        topLevelContentBoundary = shelf.key == topLevelBoundaryShelfKey,
                        requesters = requesters,
                        onOpenDetails = onOpenDetails,
                        onContentKeyFocused = { chrome.onContentKeyFocused(it) },

                        onLoadMore = { stateHolder.loadMore(shelf.key) },
                        pagingFailed = shelf.key in state.pagingFailedShelfKeys,

                        pagingFailureToken = state.pagingFailureTokens[shelf.key],
                    )
                },
            )
        }
        if (!state.loading && state.shelves.isEmpty()) {
            add(
                DiscoverBrowseRow(key = "discover:empty", itemKeys = { emptyList() }) {
                    Text(
                        "No add-on shelves. Install an add-on from Settings → Add-ons.",
                        style = InfusionTvScaleCalibration.Type.Body,

                        modifier = Modifier.tvBrowseNonRailInset(),
                    )
                },
            )
        }
    }

    InfusionTvShell(
        page = TopLevelDestination.DISCOVER,
        jellyfinConnected = jellyfinConnected,
        model = model,
        requesters = requesters,
        chrome = chrome,

        entryDefaultKey = defaultFocus,

        entryFreshKey = freshEntryFocus,
        entryFreshReady = freshEntryReady,
        onNavigateTo = onNavigate,
        onExit = onExit,
    ) {
        TvBrowseColumn(
            rows = browseRows,
            rowKey = { it.key },
            rowItemKeys = { it.itemKeys() },
            modifier = Modifier.fillMaxSize(),
            listTag = "discover-shelves",

            sharedRowRules = true,

            rememberedRowMemory = rowMemory,

            arrivingRowKeys = arrivingShelfKeys,

            originRowArrivalIndeterminate = state.loading,
        ) { row -> row.content() }
    }
}

internal fun discoverFreshEntryKey(
    shelves: List<DiscoverShelf>,
    ready: Boolean,
): String? {
    if (!ready) return null
    for (shelf in shelves) {
        val loaded = shelf.state as? ShelfState.Loaded ?: continue
        val actionable = loaded.items.firstOrNull { it.preview.titleId != null } ?: continue
        return actionable.key
    }
    return null
}

internal fun discoverFreshEntryReady(
    declaredShelfKeys: List<String>,
    shelves: List<DiscoverShelf>,
    generationComplete: Boolean,
): Boolean {
    if (generationComplete) return true
    if (declaredShelfKeys.isEmpty()) return true
    val arrived = shelves.mapTo(HashSet()) { it.key }
    val candidate = shelves.firstOrNull { shelf ->
        (shelf.state as? ShelfState.Loaded)?.items?.any { it.preview.titleId != null } == true
    } ?: return false
    val declaredIndex = declaredShelfKeys.indexOf(candidate.key)

    if (declaredIndex <= 0) return true
    return declaredShelfKeys.subList(0, declaredIndex).all { it in arrived }
}

private class DiscoverBrowseRow(
    val key: String,
    val itemKeys: () -> List<String>,
    val content: @Composable () -> Unit,
)

@Composable
private fun PagedDiscoverShelfBlock(
    shelf: DiscoverShelf,
    topLevelContentBoundary: Boolean,
    requesters: FocusKeyRegistry,
    onOpenDetails: (TitleId, TitleKind, MediaPreview) -> Unit,
    onContentKeyFocused: (String) -> Unit,
    onLoadMore: () -> Unit,
    pagingFailed: Boolean,
    pagingFailureToken: Long?,
) {
    val loaded = shelf.state as? ShelfState.Loaded
    if (loaded == null) {
        DiscoverShelfBlock(
            shelf = shelf,
            topLevelContentBoundary = topLevelContentBoundary,
            requesters = requesters,
            onOpenDetails = onOpenDetails,
            onContentKeyFocused = onContentKeyFocused,
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
    DiscoverShelfBlock(
        shelf = shelf,
        topLevelContentBoundary = topLevelContentBoundary,
        requesters = requesters,
        onOpenDetails = onOpenDetails,
        onContentKeyFocused = { key ->
            onContentKeyFocused(key)
            paging.onFocusedKey(key)
        },

        paging = paging,
    )
}

@Composable
private fun DiscoverShelfBlock(
    shelf: DiscoverShelf,
    topLevelContentBoundary: Boolean,
    requesters: FocusKeyRegistry,
    onOpenDetails: (TitleId, TitleKind, MediaPreview) -> Unit,
    onContentKeyFocused: (String) -> Unit,

    paging: dev.infusion.tv.ui.presentation.RowPagingState? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(

            "${shelf.title} — ${shelf.sourceLabel}",
            style = InfusionTvScaleCalibration.Type.Emphasis,

            modifier = Modifier.tvBrowseNonRailInset(),
        )
        when (val shelfState = shelf.state) {
            is ShelfState.Failed -> Text(
                "Could not load this shelf: ${shelfState.reason}",
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
                                item.titleId?.let { titleId ->

                                    onOpenDetails(titleId, item.kind ?: TitleKind.MOVIE, item.preview)
                                }
                            },
                            onFocusedKeyChanged = onContentKeyFocused,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AddonPosterCard(
    itemKey: String,
    preview: MediaPreview,
    requesters: MutableMap<String, androidx.compose.ui.focus.FocusRequester>,
    topLevelContentBoundary: Boolean = false,
    onClick: () -> Unit,
    onFocusedKeyChanged: (String) -> Unit,
) {

    val artworkTarget = contentCardArtworkTarget(preview.kind)

    val navigable = preview.titleId != null

    InfusionMediaCard(
        onClick = { if (navigable) onClick() },
        focusKey = itemKey,
        kind = preview.kind,

        preview = preview,
        requesters = requesters,
        onFocusedKeyChanged = onFocusedKeyChanged,
        topLevelContentBoundary = topLevelContentBoundary,
        focusable = navigable,

        artworkTag = contentCardArtworkKey(itemKey),

        artworkLabel = if (navigable) null else "Metadata only",
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

private const val DISCOVER_DEPARTURE_REPAIR_FRAMES = 3
