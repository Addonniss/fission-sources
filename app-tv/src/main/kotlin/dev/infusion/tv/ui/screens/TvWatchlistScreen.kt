package dev.infusion.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.infusion.feature.vertical.media.CanonicalHeroUi
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import dev.infusion.feature.vertical.model.DetailsArtwork
import dev.infusion.core.model.metadata.MediaIdentity
import dev.infusion.core.domain.metadata.MediaIdentityResolver
import dev.infusion.core.domain.metadata.MetadataLocale
import dev.infusion.core.domain.watchlist.WatchlistIdentityResolver
import dev.infusion.core.model.metadata.ExternalIds
import dev.infusion.core.domain.metadata.MetadataRepository
import dev.infusion.core.domain.watchlist.CardActionSurface
import dev.infusion.core.domain.watchlist.WatchlistEntry
import dev.infusion.core.domain.watch.RecentlyWatchedKind
import dev.infusion.core.domain.watchlist.WatchlistKind
import dev.infusion.core.domain.watchlist.WatchlistRemoteState
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.metadata.MediaArtwork
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.tv.ui.focus.FocusKeyRegistry
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.presentation.InfusionContentRow
import dev.infusion.tv.ui.presentation.InfusionMediaCard
import dev.infusion.tv.ui.presentation.IdentityMetadataState
import dev.infusion.tv.ui.presentation.MediaCardArtwork
import dev.infusion.tv.ui.presentation.TvBrowseColumn
import dev.infusion.tv.ui.presentation.TvBrowseRowSpacingDp
import dev.infusion.tv.ui.presentation.contentCardArtworkKey
import dev.infusion.tv.ui.presentation.contentCardArtworkTarget
import dev.infusion.tv.ui.presentation.rememberRowPaging
import dev.infusion.tv.ui.presentation.rememberTvBrowseListState
import dev.infusion.tv.ui.presentation.tvBrowseContentPadding
import dev.infusion.tv.ui.presentation.tvBrowseNonRailInset
import dev.infusion.feature.vertical.watchlist.RecentlyWatchedCard
import dev.infusion.feature.vertical.watchlist.RecentlyWatchedStateHolder
import dev.infusion.feature.vertical.watchlist.RecentlyWatchedUiState
import dev.infusion.tv.ui.shell.InfusionTvShell
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.presentation.HideWatchedCollection
import dev.infusion.tv.ui.presentation.LocalCardWatchedState
import dev.infusion.tv.ui.presentation.LocalContentCardPresentation
import dev.infusion.tv.ui.presentation.cardWatchedUnitOrNull
import dev.infusion.tv.ui.presentation.withoutHiddenWatchedIn
import dev.infusion.tv.watchlist.WatchlistCoordinator
import dev.infusion.tv.watchlist.WatchlistReadReport

@Composable
fun TvWatchlistScreen(
    model: TvTopNavModel,
    jellyfinConnected: Boolean,
    coordinator: WatchlistCoordinator,
    metadata: MetadataRepository,
    mediaIdentityResolver: MediaIdentityResolver,
    watchlistIdentity: WatchlistIdentityResolver,
    heroMetadataFactory: HomeHeroMetadataFactory? = null,
    recentlyWatched: RecentlyWatchedStateHolder? = null,

    membershipArtwork: dev.infusion.tv.watchlist.WatchlistMembershipArtworkMemory? = null,

    onGenuineVisit: () -> Unit = {},
    onNavigate: (TopLevelDestination) -> Unit,
    onOpenDetails: (TitleId, TitleKind?, MediaPreview) -> Unit,
    onExit: () -> Unit,
    listState: LazyListState = rememberTvBrowseListState(),
) {
    val chrome = rememberTopLevelPageChrome(TopLevelDestination.WATCHLIST, model)
    val requesters = rememberFocusRequesters()
    val pageScope = rememberCoroutineScope()

    val rowMemory = remember(model) { model.rowMemoryStore(TopLevelDestination.WATCHLIST) }
    val snapshot by coordinator.state.collectAsStateWithLifecycle()
    val remoteState by coordinator.remoteState.collectAsStateWithLifecycle()

    val report by coordinator.report.collectAsStateWithLifecycle()

    val recent: RecentlyWatchedUiState by
        (recentlyWatched?.state ?: EmptyRecentlyWatchedState).collectAsStateWithLifecycle()

    val isChildReturn = chrome.entryIsChildReturn
    DisposableEffect(Unit) {
        coordinator.onPageVisible(genuineVisit = !isChildReturn)

        recentlyWatched?.onSurfacePresent()
        onDispose {
            coordinator.onPageHidden()
            recentlyWatched?.onSurfaceAbsent()
        }
    }

    LaunchedEffect(Unit) {
        if (chrome.entryIsChildReturn) return@LaunchedEffect
        recentlyWatched?.refresh()
        onGenuineVisit()
    }

    val compositionArtwork = remember { mutableStateMapOf<String, MediaArtworkRef>() }
    val enriched: SnapshotStateMap<String, MediaArtworkRef> =
        membershipArtwork?.resolved ?: compositionArtwork

    val attempted = remember { mutableSetOf<String>() }
    val entryIds = snapshot.entries.map { it.entryId }

    LaunchedEffect(entryIds) {

        membershipArtwork?.retainOnly(entryIds)
        val todo = entryIds.filter { entryId ->
            val entry = snapshot.entries.firstOrNull { it.entryId == entryId } ?: return@filter false
            val needsArtwork = entry.artworkLocator == null ||

                (entry.kind == WatchlistKind.SHOW && entry.landscapeLocator == null)
            needsArtwork && entryId !in enriched && entryId !in attempted
        }
        for (batch in todo.chunked(ENRICHMENT_BATCH)) {
            for (entryId in batch) {
                attempted += entryId
                val entry = snapshot.entries.firstOrNull { it.entryId == entryId } ?: continue
                val artwork = runCatching {
                    enrichmentArtwork(metadata, mediaIdentityResolver, watchlistIdentity, entry)
                }.getOrNull()
                if (artwork != null) enriched[entryId] = artwork
            }
        }
    }

    val movies = snapshot.row(WatchlistKind.MOVIE)
    val shows = snapshot.row(WatchlistKind.SHOW)

    val rows: List<WatchlistPageRow> = listOf(
        WatchlistMembershipRow(
            WatchlistRowUi(ROW_MOVIES, WatchlistKind.MOVIE, "Movies", "Your Movies watchlist is empty.", movies),
        ),
        WatchlistMembershipRow(
            WatchlistRowUi(ROW_SHOWS, WatchlistKind.SHOW, "Shows", "Your Shows watchlist is empty.", shows),
        ),
        WatchlistRecentRow(
            id = ROW_RECENT_MOVIES,
            title = "Recently Watched Movies",
            emptyText = "Nothing watched yet.",
            axis = RecentRowAxis.MOVIES,
            cards = recent.movies,
            loaded = recent.loaded,
            hasMore = recent.moviesHasMore,
        ),
        WatchlistRecentRow(
            id = ROW_RECENT_SHOWS,
            title = "Recently Watched Shows",
            emptyText = "Nothing watched yet.",
            axis = RecentRowAxis.SHOWS,
            cards = recent.shows,
            loaded = recent.loaded,
            hasMore = recent.showsHasMore,
        ),
    )

    var focusedKey by remember { mutableStateOf<String?>(null) }
    val heroMetadata = remember(pageScope) { HomeHeroMetadataStateHolder(pageScope, heroMetadataFactory) }
    DisposableEffect(heroMetadata) { onDispose { heroMetadata.release() } }

    val focusedHero: HomeHeroCandidate? = remember(focusedKey, snapshot.entries, recent.movies, recent.shows) {
        val key = focusedKey ?: return@remember null
        snapshot.entries.firstOrNull { cardKey(it) == key }
            ?.let { WatchlistHeroCandidate.of(it, artworkRef(it, enriched)) }
            ?: (recent.movies + recent.shows).firstOrNull { it.key == key }
                ?.let { WatchlistHeroCandidate.of(it) }
    }
    val heroCandidate = focusedHero ?: rows.firstNotNullOfOrNull { it.firstHeroCandidate(enriched) }
    LaunchedEffect(heroCandidate?.key) {
        heroMetadata.feature(
            key = heroCandidate?.key ?: WATCHLIST_CONTEXT_KEY,
            anchor = heroCandidate?.anchor,
            preview = heroCandidate?.preview,
        )
    }

    val heroPresentation = heroMetadata.presentation.collectAsStateWithLifecycle().value
    val canonicalHero = heroPresentation.hero
    val heroMetadataState = heroPresentation.metadata

    val orderedCardKeys = rows.flatMap { row -> row.itemKeys }
    var previouslyOrderedKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(orderedCardKeys) {
        val gone = previouslyOrderedKeys.filter { it !in orderedCardKeys }
        val indexBefore = gone.firstOrNull()?.let { previouslyOrderedKeys.indexOf(it) }
        val focusedGone = focusedKey != null && focusedKey in gone
        previouslyOrderedKeys = orderedCardKeys
        if (!focusedGone || indexBefore == null) return@LaunchedEffect
        val successor = orderedCardKeys.getOrNull(indexBefore)
            ?: orderedCardKeys.getOrNull(indexBefore - 1)
            ?: rows.firstOrNull { it is WatchlistMembershipRow && it.row.entries.isEmpty() }
                ?.let { rowStatusKey(it.id) }
        if (successor != null) requesters[successor]?.requestFocus()
    }

    val entryKey = rows.asSequence()
        .flatMap { it.itemKeys.asSequence() }
        .firstOrNull()
        ?: WatchlistHeroFocusKey

    InfusionTvShell(
        page = TopLevelDestination.WATCHLIST,
        jellyfinConnected = jellyfinConnected,
        model = model,
        requesters = requesters,
        chrome = chrome,
        entryDefaultKey = entryKey,
        onNavigateTo = onNavigate,
        onExit = onExit,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val viewportPx = with(density) { maxHeight.roundToPx() }
            val viewportTarget = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
            val contentTopPaddingPx = with(density) {
                tvBrowseContentPadding().calculateTopPadding().roundToPx()
            }
            val rowSpacingPx = with(density) { TvBrowseRowSpacingDp.roundToPx() }
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
            val browsePadding = tvBrowseContentPadding()
            val layoutDirection = LocalLayoutDirection.current
            val contentPadding = PaddingValues(
                start = browsePadding.calculateStartPadding(layoutDirection),
                end = browsePadding.calculateEndPadding(layoutDirection),
                top = browsePadding.calculateTopPadding(),
                bottom = browsePadding.calculateBottomPadding() + pageEndAllowance,
            )

            val heroMinHeight = with(density) {
                HomeVerticalComposition.heroBlockHeightPx(
                    viewportPx = viewportPx,
                    contentTopPaddingPx = contentTopPaddingPx,
                    rowSpacingPx = rowSpacingPx,
                    firstSectionStagedHeightPx = sectionStagePx,
                ).toDp()
            }

            val rowIds = remember(rows) { rows.map { it.id } }

            val childReturnRestorationOwed = chrome.childReturnRestorationOwed
            val childReturnKey = if (childReturnRestorationOwed) {
                model.lastContentFocusFor(TopLevelDestination.WATCHLIST)
            } else {
                null
            }
            val anchorAct = remember(
                focusedKey,
                childReturnKey,
                rowIds,
                contentTopPaddingPx,
                rowSpacingPx,
            ) {
                watchlistAnchorAct(
                    focusedKey = focusedKey,
                    childReturnKey = childReturnKey,

                    childReturnOriginPending = childReturnRestorationOwed && focusedKey == null,
                    heroAnchorKey = WatchlistHeroFocusKey,
                    rowIds = rowIds,
                    headerCount = 1,
                    contentTopPaddingPx = contentTopPaddingPx,
                    rowSpacingPx = rowSpacingPx,
                )
            }
            androidx.compose.runtime.SideEffect {
                val slot = anchorAct.slot ?: return@SideEffect
                if (listState.firstVisibleItemIndex != slot.index ||
                    listState.firstVisibleItemScrollOffset != slot.offsetPx
                ) {
                    listState.requestScrollToItem(slot.index, slot.offsetPx)
                }
            }
            val activeRowId = anchorAct.presentedRowId

            val identityReservedPx = with(density) {
                homeRowItemIdentityReservedHeight().roundToPx()
            }

            val focusedArtworkRef = focusedHero?.preview?.artwork
            val heroArtwork = rememberHomeHeroArtwork(focusedArtworkRef, viewportTarget)
            TvDetailsHeroCanvas(
                hero = canonicalHero,

                primarySettled = heroMetadataState == IdentityMetadataState.SETTLED,
                underlay = DetailsArtwork(ref = heroArtwork),
                scrolledPastHero = false,

                posterPresentation = HeroPosterPresentation.Corner,
            )

            TvBrowseColumn(
                rows = rows,
                rowKey = { it.id },
                rowItemKeys = { row -> row.itemKeys.ifEmpty { listOf(rowStatusKey(row.id)) } },
                modifier = Modifier.fillMaxSize(),
                listTag = "watchlist-rows",
                listState = listState,
                pageOwnsVerticalFraming = true,

                sharedRowRules = true,

                rememberedRowMemory = rowMemory,

                originRowArrivalIndeterminate = remoteState == WatchlistRemoteState.IDLE ||
                    remoteState == WatchlistRemoteState.LOADING,
                contentPadding = contentPadding,
                header = {
                    TvHomeHeroBlock(
                        hero = canonicalHero,
                        preview = heroCandidate?.preview,

                        metadata = heroMetadataState,
                        minHeight = heroMinHeight,
                        requesters = requesters,
                        requester = remember { FocusRequester() },
                        onFocusedKeyChanged = { key -> focusedKey = key; chrome.onContentKeyFocused(key) },
                        heroFocusKey = WatchlistHeroFocusKey,
                        modifier = Modifier.tvBrowseNonRailInset(),
                    )
                },
            ) { row ->
                when (row) {
                    is WatchlistMembershipRow -> WatchlistRow(
                        row = row.row,
                        remoteState = remoteState,
                        report = report,
                        isActive = row.id == activeRowId,
                        stageMinHeight = sectionStageMinHeight,
                        requesters = requesters,
                        enriched = enriched,
                        onFocusedKeyChanged = { key -> focusedKey = key; chrome.onContentKeyFocused(key) },
                        onOpenDetails = onOpenDetails,

                        viewportPx = viewportPx,
                        identityReservedPx = identityReservedPx,
                        identityHero = canonicalHero,
                        identityPreview = heroCandidate?.preview,
                        identityMetadata = heroMetadataState,
                    )

                    is WatchlistRecentRow -> RecentlyWatchedRow(
                        row = row,
                        isActive = row.id == activeRowId,
                        stageMinHeight = sectionStageMinHeight,
                        requesters = requesters,
                        onFocusedKeyChanged = { key -> focusedKey = key; chrome.onContentKeyFocused(key) },
                        onOpenDetails = onOpenDetails,
                        onPresentedWindowChanged = { materialized ->

                            recentlyWatched?.reportPresented(
                                movies = if (row.axis == RecentRowAxis.MOVIES) materialized else 0,
                                shows = if (row.axis == RecentRowAxis.SHOWS) materialized else 0,
                            )
                        },

                        onLoadMore = {
                            recentlyWatched?.loadMore(
                                if (row.axis == RecentRowAxis.MOVIES) {
                                    RecentlyWatchedKind.MOVIE
                                } else {
                                    RecentlyWatchedKind.SHOW
                                },
                            )
                        },
                        viewportPx = viewportPx,
                        identityReservedPx = identityReservedPx,
                        identityHero = canonicalHero,
                        identityPreview = heroCandidate?.preview,
                        identityMetadata = heroMetadataState,
                    )
                }
            }
        }
    }
}

private data class WatchlistRowUi(
    val id: String,
    val kind: WatchlistKind,
    val title: String,
    val emptyText: String,
    val entries: List<WatchlistEntry>,
)

private sealed interface WatchlistPageRow {
    val id: String
    val itemKeys: List<String>

    fun firstHeroCandidate(enriched: SnapshotStateMap<String, MediaArtworkRef>): HomeHeroCandidate?
}

private data class WatchlistMembershipRow(val row: WatchlistRowUi) : WatchlistPageRow {
    override val id: String get() = row.id
    override val itemKeys: List<String> get() = row.entries.map(::cardKey)
    override fun firstHeroCandidate(enriched: SnapshotStateMap<String, MediaArtworkRef>): HomeHeroCandidate? =
        row.entries.firstOrNull()?.let { WatchlistHeroCandidate.of(it, artworkRef(it, enriched)) }
}

private enum class RecentRowAxis { MOVIES, SHOWS }

private data class WatchlistRecentRow(
    override val id: String,
    val title: String,
    val emptyText: String,
    val axis: RecentRowAxis,
    val cards: List<RecentlyWatchedCard>,
    val loaded: Boolean,

    val hasMore: Boolean,
) : WatchlistPageRow {
    override val itemKeys: List<String> get() = cards.map { it.key }
    override fun firstHeroCandidate(enriched: SnapshotStateMap<String, MediaArtworkRef>): HomeHeroCandidate? =
        cards.firstOrNull()?.let { WatchlistHeroCandidate.of(it) }
}

@Composable
private fun WatchlistRow(
    row: WatchlistRowUi,
    remoteState: WatchlistRemoteState,
    report: WatchlistReadReport,
    isActive: Boolean,
    stageMinHeight: androidx.compose.ui.unit.Dp,
    requesters: FocusKeyRegistry,
    enriched: SnapshotStateMap<String, MediaArtworkRef>,
    onFocusedKeyChanged: (String) -> Unit,
    onOpenDetails: (TitleId, TitleKind?, MediaPreview) -> Unit,

    viewportPx: Int,

    identityReservedPx: Int,

    identityHero: CanonicalHeroUi?,

    identityPreview: MediaPreview?,

    identityMetadata: IdentityMetadataState = IdentityMetadataState.SETTLED,
) {

    val density = LocalDensity.current
    var rowContentPx by remember(row.id) { mutableStateOf(0) }
    val identityRoomPx = viewportPx - (rowContentPx + identityReservedPx)

    val identityPresented = isActive && rowContentPx > 0 && identityRoomPx >= 0

    Box(
        modifier = Modifier.heightIn(min = stageMinHeight),
        contentAlignment = if (isActive) Alignment.BottomStart else Alignment.TopStart,
    ) {
        if (identityPresented) {

            Box(modifier = Modifier.matchParentSize()) {
                TvHomeRowItemIdentity(
                    hero = identityHero,
                    preview = identityPreview,
                    metadata = identityMetadata,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(y = -with(density) { rowContentPx.toDp() })
                        .tvBrowseNonRailInset(),
                )
            }
        }
        Column(
            modifier = Modifier.onSizeChanged { size -> rowContentPx = size.height },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {

            val rowIncomplete = remoteState == WatchlistRemoteState.FAILED ||
                (report.state == WatchlistRemoteState.LOADED && row.kind !in report.completeKinds)
            val rowLoading = remoteState == WatchlistRemoteState.LOADING
            Text(
                row.title,
                style = InfusionTvScaleCalibration.Type.Emphasis,
                modifier = Modifier.tvBrowseNonRailInset(),
            )

            if (row.entries.isNotEmpty() && rowIncomplete) {
                Text(
                    "Could not load your watchlist.",
                    style = InfusionTvScaleCalibration.Type.Body,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.tvBrowseNonRailInset(),
                )
            }
            if (row.entries.isNotEmpty()) {
                InfusionContentRow(items = row.entries, key = { cardKey(it) }) { entry ->
                    val kind = entryKind(entry)
                    val preview = previewOf(entry, artworkRef(entry, enriched))
                    InfusionMediaCard(
                        onClick = { onOpenDetails(entry.titleId, kind, preview) },
                        focusKey = cardKey(entry),
                        kind = kind,
                        preview = preview,
                        requesters = requesters,
                        onFocusedKeyChanged = onFocusedKeyChanged,
                        artworkTag = contentCardArtworkKey(cardKey(entry)),
                        contextSurface = CardActionSurface.WATCHLIST,
                        artwork = {
                            MediaCardArtwork(
                                preview = preview,
                                targetSize = contentCardArtworkTarget(kind),
                                contentDescription = preview.displayTitle,
                                modifier = Modifier.fillMaxSize(),
                            )
                        },
                    )
                }
            } else {

                Box(
                    modifier = stableFocusModifier(
                        key = rowStatusKey(row.id),
                        requesters = requesters,
                        onFocusedKeyChanged = onFocusedKeyChanged,
                    ).padding(vertical = 12.dp),
                ) {
                    val failed = rowIncomplete
                    Text(
                        text = when {
                            failed -> "Could not load your watchlist."
                            rowLoading -> "Loading…"
                            else -> row.emptyText
                        },
                        style = InfusionTvScaleCalibration.Type.Body,
                        color = if (failed) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
                        modifier = Modifier.tvBrowseNonRailInset(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentlyWatchedRow(
    row: WatchlistRecentRow,
    isActive: Boolean,
    stageMinHeight: androidx.compose.ui.unit.Dp,
    requesters: FocusKeyRegistry,
    onFocusedKeyChanged: (String) -> Unit,
    onOpenDetails: (TitleId, TitleKind?, MediaPreview) -> Unit,

    onPresentedWindowChanged: (Int) -> Unit,

    onLoadMore: () -> Unit,
    viewportPx: Int,
    identityReservedPx: Int,
    identityHero: CanonicalHeroUi?,
    identityPreview: MediaPreview?,
    identityMetadata: IdentityMetadataState = IdentityMetadataState.SETTLED,
) {
    val density = LocalDensity.current
    var rowContentPx by remember(row.id) { mutableStateOf(0) }
    val identityRoomPx = viewportPx - (rowContentPx + identityReservedPx)
    val identityPresented = isActive && rowContentPx > 0 && identityRoomPx >= 0

    val cards = withoutHiddenWatchedIn(
        collection = HideWatchedCollection.WATCHLIST_RECENTLY_WATCHED,
        items = row.cards,
        hideWatchedTitles = LocalContentCardPresentation.current.hideWatchedTitles,
        watchedState = LocalCardWatchedState.current,
        unitOf = { card -> card.preview.cardWatchedUnitOrNull() },
    )

    val itemKeys = remember(cards) { cards.map { card -> card.key } }
    val paging = rememberRowPaging(
        itemKeys = itemKeys,
        hasMore = row.hasMore,
        onLoadMore = onLoadMore,
    )

    Box(
        modifier = Modifier.heightIn(min = stageMinHeight),
        contentAlignment = if (isActive) Alignment.BottomStart else Alignment.TopStart,
    ) {
        if (identityPresented) {
            Box(modifier = Modifier.matchParentSize()) {
                TvHomeRowItemIdentity(
                    hero = identityHero,
                    preview = identityPreview,
                    metadata = identityMetadata,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(y = -with(density) { rowContentPx.toDp() })
                        .tvBrowseNonRailInset(),
                )
            }
        }
        Column(
            modifier = Modifier.onSizeChanged { size -> rowContentPx = size.height },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                row.title,
                style = InfusionTvScaleCalibration.Type.Emphasis,
                modifier = Modifier.tvBrowseNonRailInset(),
            )
            if (cards.isNotEmpty()) {
                InfusionContentRow(
                    items = cards,
                    key = { it.key },
                    onWindowChanged = { _, materialized -> onPresentedWindowChanged(materialized) },
                    paging = paging,
                ) { card ->
                    InfusionMediaCard(
                        onClick = { onOpenDetails(card.titleId, card.kind, card.preview) },
                        focusKey = card.key,
                        kind = card.kind,
                        preview = card.preview,
                        requesters = requesters,
                        onFocusedKeyChanged = { key ->
                            onFocusedKeyChanged(key)
                            paging.onFocusedKey(key)
                        },
                        artworkTag = contentCardArtworkKey(card.key),

                        contextSurface = CardActionSurface.BROWSE,
                        artwork = {
                            MediaCardArtwork(
                                preview = card.preview,
                                targetSize = contentCardArtworkTarget(card.kind),
                                contentDescription = card.preview.displayTitle,
                                modifier = Modifier.fillMaxSize(),
                            )
                        },
                    )
                }
            } else {

                Box(
                    modifier = stableFocusModifier(
                        key = rowStatusKey(row.id),
                        requesters = requesters,
                        onFocusedKeyChanged = onFocusedKeyChanged,
                    ).padding(vertical = 12.dp),
                ) {
                    Text(
                        text = if (row.loaded) row.emptyText else "Loading…",
                        style = InfusionTvScaleCalibration.Type.Body,
                        modifier = Modifier.tvBrowseNonRailInset(),
                    )
                }
            }
        }
    }
}

internal data class WatchlistHeroCandidate(
    override val key: String,
    override val preview: MediaPreview,
    override val anchor: HomeHeroAnchor,
) : HomeHeroCandidate {

    companion object {

        fun of(entry: WatchlistEntry, artwork: MediaArtworkRef?): WatchlistHeroCandidate =
            WatchlistHeroCandidate(
                key = cardKey(entry),
                preview = previewOf(entry, artwork),
                anchor = HomeHeroAnchor(
                    titleId = entry.titleId,
                    mediaType = if (entry.kind == WatchlistKind.MOVIE) MediaType.MOVIE else MediaType.SHOW,
                ),
            )

        fun of(card: RecentlyWatchedCard): WatchlistHeroCandidate =
            WatchlistHeroCandidate(
                key = card.key,
                preview = card.preview,
                anchor = HomeHeroAnchor(
                    titleId = card.titleId,
                    mediaType = if (card.kind == TitleKind.MOVIE) MediaType.MOVIE else MediaType.SHOW,
                ),
            )
    }
}

private fun cardKey(entry: WatchlistEntry): String =
    "${if (entry.kind == WatchlistKind.MOVIE) ROW_MOVIES else ROW_SHOWS}:item:${entry.entryId}"

private fun rowStatusKey(rowId: String): String = "$rowId:status"

private fun entryKind(entry: WatchlistEntry): TitleKind =
    if (entry.kind == WatchlistKind.MOVIE) TitleKind.MOVIE else TitleKind.SERIES

private fun artworkRef(
    entry: WatchlistEntry,
    enriched: SnapshotStateMap<String, MediaArtworkRef>,
): MediaArtworkRef? {
    val stored = entry.artworkLocator?.let {
        MediaArtworkRef(it, entry.artworkPolicy, entry.landscapeLocator)
    }

    val needsLandscape = entry.kind == WatchlistKind.SHOW
    if (stored != null && (!needsLandscape || stored.landscapeLocator != null)) return stored
    return enriched[entry.entryId] ?: stored
}

private fun previewOf(entry: WatchlistEntry, artwork: MediaArtworkRef?): MediaPreview = MediaPreview(
    titleId = entry.titleId,
    kind = entryKind(entry),
    displayTitle = entry.title,
    year = entry.year,
    subtitle = null,
    artwork = artwork,
    progressFraction = null,
)

private suspend fun enrichmentArtwork(
    metadata: MetadataRepository,
    identityResolver: MediaIdentityResolver,
    watchlistIdentity: WatchlistIdentityResolver,
    entry: WatchlistEntry,
): MediaArtworkRef? {
    val type = if (entry.kind == WatchlistKind.MOVIE) MediaType.MOVIE else MediaType.SHOW

    val proven = runCatching { watchlistIdentity.aliasesFor(entry.titleId, entry.kind) }.getOrNull()
    val identity = when {
        proven != null -> MediaIdentity(
            type = type,
            titleId = proven.titleId,
            externalIds = ExternalIds(
                tmdbId = proven.externalIds.tmdbId,
                imdbId = proven.externalIds.imdbId,
                tvdbId = proven.externalIds.tvdbId,
            ),
        )
        else -> runCatching { identityResolver.resolve(entry.titleId, type, MetadataLocale.Default) }
            .getOrNull() ?: MediaIdentity(type = type, titleId = entry.titleId)
    }
    val details = metadata.details(identity, MetadataLocale.Default) ?: return null
    val artwork: MediaArtwork? = details.artwork
    val poster = artwork?.poster
    val backdrop = artwork?.backdrop
    return when {
        poster != null -> MediaArtworkRef.metadata(
            provider = poster.provider,
            path = poster.path,
            policy = ArtworkPolicy.POSTER,
            landscapePath = backdrop?.path,
        )
        backdrop != null -> MediaArtworkRef.metadata(
            provider = backdrop.provider,
            path = backdrop.path,
            policy = ArtworkPolicy.BACKDROP,
        )
        else -> null
    }
}

internal data class WatchlistAnchorAct(

    val presentedRowId: String?,

    val slot: HomePageScrollTarget?,
)

internal fun watchlistRowIdOf(key: String?): String? = when {
    key == null -> null
    key == ROW_MOVIES || key.startsWith("$ROW_MOVIES:") -> ROW_MOVIES
    key == ROW_SHOWS || key.startsWith("$ROW_SHOWS:") -> ROW_SHOWS

    key == ROW_RECENT_MOVIES ||
        key.startsWith(RecentlyWatchedStateHolder.cardKeyPrefix(RecentlyWatchedKind.MOVIE)) ||
        key.startsWith("$ROW_RECENT_MOVIES:") -> ROW_RECENT_MOVIES
    key == ROW_RECENT_SHOWS ||
        key.startsWith(RecentlyWatchedStateHolder.cardKeyPrefix(RecentlyWatchedKind.SHOW)) ||
        key.startsWith("$ROW_RECENT_SHOWS:") -> ROW_RECENT_SHOWS
    else -> null
}

internal fun watchlistAnchorAct(

    focusedKey: String?,

    childReturnKey: String?,

    childReturnOriginPending: Boolean,

    heroAnchorKey: String,

    rowIds: List<String>,

    headerCount: Int,
    contentTopPaddingPx: Int,
    rowSpacingPx: Int,
): WatchlistAnchorAct {

    val owedKey = if (childReturnOriginPending) childReturnKey else null
    val lawKey = focusedKey ?: owedKey
    if (lawKey == null || lawKey == heroAnchorKey) {
        return if (childReturnOriginPending) {

            WatchlistAnchorAct(presentedRowId = null, slot = null)
        } else {
            WatchlistAnchorAct(
                presentedRowId = null,
                slot = HomeVerticalComposition.heroAnchorScrollTarget(),
            )
        }
    }

    val rowId = watchlistRowIdOf(lawKey) ?: return WatchlistAnchorAct(
        presentedRowId = null,
        slot = if (childReturnOriginPending) {
            null
        } else {
            HomeVerticalComposition.heroAnchorScrollTarget()
        },
    )
    val rowIndex = rowIds.indexOf(rowId)
    if (rowIndex < 0) {
        return WatchlistAnchorAct(
            presentedRowId = null,
            slot = if (childReturnOriginPending) {
                null
            } else {
                HomeVerticalComposition.heroAnchorScrollTarget()
            },
        )
    }
    return WatchlistAnchorAct(
        presentedRowId = rowId,
        slot = HomeVerticalComposition.sectionAnchorScrollTarget(
            itemIndex = rowIndex + headerCount,
            contentTopPaddingPx = contentTopPaddingPx,
            rowSpacingPx = rowSpacingPx,
        ),
    )
}

private const val WatchlistHeroFocusKey = "watchlist:hero"

private const val WATCHLIST_CONTEXT_KEY = "watchlist:context"

private const val ROW_MOVIES = "watchlist:movies"
private const val ROW_SHOWS = "watchlist:shows"

private const val ROW_RECENT_MOVIES = "watchlist:recent-movies"
private const val ROW_RECENT_SHOWS = "watchlist:recent-shows"

private val EmptyRecentlyWatchedState = kotlinx.coroutines.flow.MutableStateFlow(RecentlyWatchedUiState())

private const val ENRICHMENT_BATCH = 4
