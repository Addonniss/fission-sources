@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package dev.infusion.tv.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.infusion.core.artworkimage.ArtworkImage
import dev.infusion.core.artworkimage.ArtworkRequest
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.MediaPreview
import dev.infusion.feature.vertical.collections.CollectionPageRowNavigationMemory
import dev.infusion.feature.vertical.collections.CollectionPageRowUi
import dev.infusion.feature.vertical.collections.CollectionPageStateHolder
import dev.infusion.feature.vertical.collections.CollectionPageUiState
import dev.infusion.feature.vertical.media.CanonicalHeroUi
import dev.infusion.feature.vertical.model.DetailsArtwork
import dev.infusion.source.aggregation.discover.DiscoverItem
import dev.infusion.source.aggregation.discover.DiscoverShelf
import dev.infusion.source.aggregation.discover.ShelfState
import dev.infusion.tv.ui.focus.FocusKeyRegistry
import dev.infusion.tv.ui.focus.RequestFocusOnKey
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.presentation.InfusionContentRow
import dev.infusion.core.artworkimage.LocalArtworkLoadEnabled
import dev.infusion.tv.ui.presentation.LocalCardWatchedState
import dev.infusion.tv.ui.presentation.LocalContentCardPresentation
import dev.infusion.tv.ui.presentation.TvBrowseColumn
import dev.infusion.tv.ui.presentation.TvBrowsePageZone
import dev.infusion.tv.ui.presentation.TvBrowseRowSpacingDp
import dev.infusion.tv.ui.presentation.artworkTargetPx
import dev.infusion.tv.ui.presentation.hideWatchedShelf
import dev.infusion.tv.ui.presentation.IdentityMetadataState
import dev.infusion.tv.ui.presentation.RowMemoryStore
import dev.infusion.tv.ui.presentation.RowViewport
import dev.infusion.tv.ui.presentation.RowViewportMemory
import dev.infusion.tv.ui.presentation.rememberRowPaging
import dev.infusion.tv.ui.presentation.rememberTvBrowseListState
import dev.infusion.tv.ui.presentation.tvBrowseNonRailInset
import dev.infusion.tv.ui.presentation.tvRailLawPagePadding
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration

internal const val CollectionPageArtworkFocusKey = "collection-page:artwork"

private val CollectionPageLogoHeight = 72.dp
private val CollectionPageLogoMaxWidth = 320.dp
private val CollectionPageVerticalPadding = 32.dp

internal fun collectionPageEntryFocusKey(
    missing: Boolean,
    hasRows: Boolean,
    focusRestoreKey: String?,
    effectiveRowKeys: List<String>? = null,
    orderedRowKeys: List<String> = emptyList(),
): String? = when {
    missing -> null
    focusRestoreKey != null -> {
        if (effectiveRowKeys == null) {
            focusRestoreKey
        } else {
            val restoreRowKey = collectionPageRestoreRowKey(focusRestoreKey, effectiveRowKeys, orderedRowKeys)
            when {

                restoreRowKey == null -> CollectionPageArtworkFocusKey
                restoreRowKey == focusRestoreKey.substringBeforeLast(":item:", "") -> focusRestoreKey
                else -> collectionRowStatusFocusKey(restoreRowKey)
            }
        }
    }
    hasRows -> CollectionPageArtworkFocusKey
    else -> null
}

internal fun collectionPageRestoreRowKey(
    focusRestoreKey: String?,
    effectiveRowKeys: List<String>,
    orderedRowKeys: List<String>,
): String? {
    val rowKey = focusRestoreKey
        ?.substringBeforeLast(":item:", missingDelimiterValue = "")
        ?.takeIf { it.isNotBlank() }
        ?: return null
    if (rowKey in effectiveRowKeys) return rowKey
    val index = orderedRowKeys.indexOf(rowKey)
    if (index < 0) return null
    orderedRowKeys.subList(index + 1, orderedRowKeys.size)
        .firstOrNull { it in effectiveRowKeys }
        ?.let { return it }
    orderedRowKeys.subList(0, index)
        .lastOrNull { it in effectiveRowKeys }
        ?.let { return it }
    return null
}

internal data class CollectionAnchorAct(

    val presentedRowKey: String?,

    val slot: HomePageScrollTarget,
)

internal fun collectionAnchorAct(
    focusedKey: String?,
    focusedRowKey: String?,
    heroAnchorKey: String,
    rowKeys: List<String>,
    headerCount: Int,
    contentTopPaddingPx: Int,
    rowSpacingPx: Int,
): CollectionAnchorAct {
    val rowKey = focusedRowKey
    if (focusedKey == null || focusedKey == heroAnchorKey || rowKey == null) {
        return CollectionAnchorAct(null, HomeVerticalComposition.heroAnchorScrollTarget())
    }
    val rowIndex = rowKeys.indexOf(rowKey)
    if (rowIndex < 0) {
        return CollectionAnchorAct(null, HomeVerticalComposition.heroAnchorScrollTarget())
    }
    return CollectionAnchorAct(
        presentedRowKey = rowKey,
        slot = HomeVerticalComposition.sectionAnchorScrollTarget(
            itemIndex = rowIndex + headerCount,
            contentTopPaddingPx = contentTopPaddingPx,
            rowSpacingPx = rowSpacingPx,
        ),
    )
}

internal fun collectionLoadAheadRowKeys(
    presentedRowKey: String?,
    rowKeys: List<String>,
): Set<String> {
    if (rowKeys.isEmpty()) return emptySet()
    val presentedIndex = presentedRowKey?.let { key -> rowKeys.indexOf(key) }?.takeIf { it >= 0 } ?: 0
    val end = minOf(presentedIndex + CollectionPageLoadAheadRows + 1, rowKeys.size)
    return rowKeys.subList(presentedIndex, end).toSet()
}

private const val CollectionPageLoadAheadRows = 1

internal fun collectionRowStatusFocusKey(rowKey: String): String = "$rowKey$CollectionRowStatusSuffix"

internal fun collectionRowKeyOfStatusFocusKey(focusKey: String?): String? =
    focusKey?.takeIf { it.endsWith(CollectionRowStatusSuffix) }?.removeSuffix(CollectionRowStatusSuffix)

private const val CollectionRowStatusSuffix = ":status"

@Composable
fun TvCollectionPageScreen(
    stateHolder: CollectionPageStateHolder,
    onOpenItem: (DiscoverItem) -> Unit,
    onBack: () -> Unit,

    heroMetadataFactory: HomeHeroMetadataFactory? = null,
    listState: LazyListState = rememberTvBrowseListState(),

    focusRestoreKey: String? = null,
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()

    val rowMemory = remember(stateHolder) { stateHolder.rowNavigationMemory.asRowMemoryStore() }
    val requesters = rememberFocusRequesters()
    val pageScope = rememberCoroutineScope()
    val heroMetadata = remember(pageScope) { HomeHeroMetadataStateHolder(pageScope, heroMetadataFactory) }
    DisposableEffect(heroMetadata) { onDispose { heroMetadata.release() } }

    var focusedKey by remember { mutableStateOf<String?>(null) }
    val onContentKeyFocused: (String) -> Unit = { key -> focusedKey = key }

    var contextBlockHeightPx by remember { mutableStateOf(0) }

    val effectiveRowKeys = remember(state.rows) { state.rows.map { it.rowKey } }
    val restoreRowKey = remember(focusRestoreKey, effectiveRowKeys, state.rowOrder) {
        collectionPageRestoreRowKey(focusRestoreKey, effectiveRowKeys, state.rowOrder)
    }

    val restorePending = focusRestoreKey != null && focusedKey == null

    val entryKey = collectionPageEntryFocusKey(
        missing = state.missing,

        hasRows = state.rows.isNotEmpty() || state.allRowsDisabled,
        focusRestoreKey = focusRestoreKey,
        effectiveRowKeys = effectiveRowKeys,
        orderedRowKeys = state.rowOrder,
    )
    RequestFocusOnKey(key = entryKey, requesters = requesters)

    if (state.missing) {
        Column(modifier = Modifier.fillMaxSize().padding(tvRailLawPagePadding(CollectionPageVerticalPadding))) {
            Text(
                "This collection is no longer available.",
                style = InfusionTvScaleCalibration.Type.Body,
                modifier = Modifier.tvBrowseNonRailInset(),
            )
        }
        return
    }

    val focusedRowAndItem = remember(focusedKey, state.rows) {
        focusedKey?.let { key ->
            state.rows.firstNotNullOfOrNull { row ->
                row.items.firstOrNull { it.key == key }?.let { row to it }
            }
        }
    }

    val focusedRowKey = focusedRowAndItem?.first?.rowKey ?: collectionRowKeyOfStatusFocusKey(focusedKey)

    val anchorFocusedKey = focusedKey ?: focusRestoreKey?.takeIf { restorePending }
    val anchorRowKey = focusedRowKey ?: restoreRowKey?.takeIf { restorePending }

    val heroCandidate: HomeHeroCandidate? = focusedRowAndItem?.second?.let { HomeHeroAddonItemCandidate(it) }

    LaunchedEffect(heroCandidate?.key) {
        val candidate = heroCandidate
        heroMetadata.feature(
            key = candidate?.key ?: COLLECTION_PAGE_CONTEXT_KEY,
            anchor = candidate?.anchor,
            preview = candidate?.preview,
        )
    }

    val heroPresentation = heroMetadata.presentation.collectAsStateWithLifecycle().value
    val canonicalHero = heroPresentation.hero
    val heroMetadataState = heroPresentation.metadata

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val viewportPx = with(density) { maxHeight.roundToPx() }
        val viewportTarget = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }

        val contentTopPaddingPx = with(density) { CollectionPageVerticalPadding.roundToPx() }
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
        val contentPadding = PaddingValues(
            start = 0.dp,
            end = 0.dp,
            top = CollectionPageVerticalPadding,
            bottom = CollectionPageVerticalPadding + pageEndAllowance,
        )
        val rowKeys = remember(state.rows) { state.rows.map { it.rowKey } }
        val anchorAct = remember(anchorFocusedKey, anchorRowKey, rowKeys, contentTopPaddingPx, rowSpacingPx) {
            collectionAnchorAct(
                focusedKey = anchorFocusedKey,
                focusedRowKey = anchorRowKey,
                heroAnchorKey = CollectionPageArtworkFocusKey,
                rowKeys = rowKeys,
                headerCount = 1,
                contentTopPaddingPx = contentTopPaddingPx,
                rowSpacingPx = rowSpacingPx,
            )
        }

        SideEffect {
            val slot = anchorAct.slot
            if (listState.firstVisibleItemIndex != slot.index ||
                listState.firstVisibleItemScrollOffset != slot.offsetPx
            ) {
                listState.requestScrollToItem(slot.index, slot.offsetPx)
            }
        }
        val activeRowKey = anchorAct.presentedRowKey

        val loadAheadRowKeys = collectionLoadAheadRowKeys(anchorAct.presentedRowKey, rowKeys)

        val identityReservedPx = with(density) { homeRowItemIdentityReservedHeight().roundToPx() }
        val identityRowKey = if (heroCandidate != null) activeRowKey else null

        val heroArtworkRef = homeHeroArtworkRef(heroCandidate)
            ?: state.backdropLocator?.let { locator -> MediaArtworkRef.of(locator, ArtworkPolicy.BACKDROP) }
        val heroArtwork = rememberHomeHeroArtwork(heroArtworkRef, viewportTarget)
        TvDetailsHeroCanvas(
            hero = canonicalHero,

            primarySettled = heroMetadataState == IdentityMetadataState.SETTLED,
            underlay = DetailsArtwork(ref = heroArtwork),
            scrolledPastHero = false,

            posterPresentation = HeroPosterPresentation.Corner,
        )

        val heroMinHeight = with(density) {
            (
                HomeVerticalComposition.heroBlockHeightPx(
                    viewportPx = viewportPx,
                    contentTopPaddingPx = contentTopPaddingPx,
                    rowSpacingPx = rowSpacingPx,
                    firstSectionStagedHeightPx = if (state.rows.isEmpty()) 0 else sectionStagePx,
                ) - contextBlockHeightPx
                ).coerceAtLeast(0).toDp()
        }

        TvBrowsePageZone(requesters) {
            TvBrowseColumn(
                rows = state.rows,
                rowKey = { it.rowKey },
                rowItemKeys = { row -> row.items.map { item -> item.key } },
                modifier = Modifier.fillMaxSize(),
                listTag = "collection-page-rows",
                listState = listState,

                loadAheadRowKeys = loadAheadRowKeys,

                pageOwnsVerticalFraming = true,

                sharedRowRules = true,

                rememberedRowMemory = rowMemory,
                contentPadding = contentPadding,
                header = {
                    CollectionPageHero(
                        state = state,
                        hero = canonicalHero,
                        candidate = heroCandidate,

                        metadata = heroMetadataState,
                        minHeight = heroMinHeight,
                        requesters = requesters,
                        onFocusedKeyChanged = onContentKeyFocused,
                        onContextBlockMeasured = { measured -> contextBlockHeightPx = measured },
                    )
                },
            ) { row ->
                CollectionPageRow(
                    row = row,
                    isActive = row.rowKey == activeRowKey,
                    stageMinHeight = sectionStageMinHeight,
                    requesters = requesters,
                    onFocusedKeyChanged = onContentKeyFocused,
                    onEnsureResolved = { stateHolder.ensureRow(row.rowKey) },
                    onOpenItem = onOpenItem,
                    onLoadMore = { stateHolder.loadMore(row.rowKey) },

                    viewportPx = viewportPx,
                    identityReservedPx = identityReservedPx,
                    identityHero = canonicalHero,
                    identityPreview = heroCandidate?.preview,
                    identityMetadata = heroMetadataState,
                    showIdentity = row.rowKey == identityRowKey,

                    isStatusFocused = focusedKey == collectionRowStatusFocusKey(row.rowKey),
                )
            }
        }
    }

    LaunchedEffect(restoreRowKey, restorePending, state.rows) {
        if (restorePending && restoreRowKey != null) stateHolder.ensureRow(restoreRowKey)
    }
}

@Composable
private fun CollectionPageHero(
    state: CollectionPageUiState,
    hero: CanonicalHeroUi?,
    candidate: HomeHeroCandidate?,
    minHeight: Dp,
    requesters: FocusKeyRegistry,
    onFocusedKeyChanged: (String) -> Unit,
    onContextBlockMeasured: (Int) -> Unit,

    metadata: IdentityMetadataState = IdentityMetadataState.SETTLED,
) {
    val requester = remember { FocusRequester() }
    Column {
        TvHomeHeroBlock(
            hero = hero,
            metadata = metadata,

            preview = candidate?.preview,
            minHeight = minHeight,
            requesters = requesters,
            requester = requester,
            onFocusedKeyChanged = onFocusedKeyChanged,

            collectionLogoLocator = if (candidate == null) state.logoLocator else null,

            collectionLogoRecolour = state.entityKind != null,

            heroFocusKey = CollectionPageArtworkFocusKey,
            modifier = Modifier.tvBrowseNonRailInset(),
        )

        Column(
            modifier = Modifier
                .tvBrowseNonRailInset()

                .onSizeChanged { size -> onContextBlockMeasured(size.height) }
                .padding(top = CollectionPageContextGap),
            verticalArrangement = Arrangement.spacedBy(CollectionPageContextGap),
        ) {
            Text(state.title, style = InfusionTvScaleCalibration.Type.HeadlineNormal)
            if (state.rows.isEmpty()) {

                Text(
                    if (state.allRowsDisabled) "No rows enabled." else "This collection has no rows.",
                    style = InfusionTvScaleCalibration.Type.Body,
                )
            }
        }
    }
}

private val CollectionPageContextGap = 8.dp

@Composable
private fun CollectionPageRow(
    row: CollectionPageRowUi,
    isActive: Boolean,
    stageMinHeight: Dp,
    requesters: FocusKeyRegistry,
    onFocusedKeyChanged: (String) -> Unit,
    onEnsureResolved: () -> Unit,
    onOpenItem: (DiscoverItem) -> Unit,
    onLoadMore: () -> Unit,

    viewportPx: Int,

    identityReservedPx: Int,

    identityHero: CanonicalHeroUi?,

    identityPreview: MediaPreview?,

    identityMetadata: IdentityMetadataState = IdentityMetadataState.SETTLED,

    showIdentity: Boolean,

    isStatusFocused: Boolean,
) {

    val cardPresentation = LocalContentCardPresentation.current
    val cardWatchedState = LocalCardWatchedState.current

    val nearViewport = LocalArtworkLoadEnabled.current
    LaunchedEffect(row.rowKey, nearViewport) {
        if (nearViewport) onEnsureResolved()
    }
    val filtered = remember(row.items, row.title, cardPresentation.hideWatchedTitles, cardWatchedState) {
        hideWatchedShelf(
            shelf = DiscoverShelf(
                key = row.rowKey,
                title = row.title,
                sourceLabel = row.sourceLabel,
                state = ShelfState.Loaded(items = row.items, hasMore = row.hasMore),
            ),
            hideWatchedTitles = cardPresentation.hideWatchedTitles,
            watchedState = cardWatchedState,
        )
    }
    val items = (filtered.state as? ShelfState.Loaded)?.items.orEmpty()

    if (isStatusFocused) {
        RequestFocusOnKey(key = items.firstOrNull()?.key, requesters = requesters)
    }

    val itemKeys = remember(items) { items.map { it.key } }
    val paging = rememberRowPaging(
        itemKeys = itemKeys,
        hasMore = row.hasMore,
        onLoadMore = onLoadMore,
        pagingFailed = row.failure != null,

        pagingFailureToken = row.failureToken,
    )
    val onFocusedKey: (String) -> Unit = { key ->
        onFocusedKeyChanged(key)
        paging.onFocusedKey(key)
    }

    val density = LocalDensity.current
    var rowContentPx by remember(row.rowKey) { mutableStateOf(0) }
    val identityRoomPx = viewportPx - (rowContentPx + identityReservedPx)

    val identityPresented = showIdentity && rowContentPx > 0 && identityRoomPx >= 0

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
            Text(row.title, style = InfusionTvScaleCalibration.Type.Emphasis, modifier = Modifier.tvBrowseNonRailInset())
            when {
                items.isNotEmpty() -> InfusionContentRow(
                    items = items,
                    key = { it.key },

                    paging = paging,
                ) { item ->
                    AddonPosterCard(
                        itemKey = item.key,
                        preview = item.preview,
                        requesters = requesters,
                        onClick = { onOpenItem(item) },
                        onFocusedKeyChanged = onFocusedKey,
                    )
                }

                else -> Box(
                    modifier = stableFocusModifier(
                        key = collectionRowStatusFocusKey(row.rowKey),
                        requesters = requesters,
                        onFocusedKeyChanged = onFocusedKeyChanged,
                    ).focusable(),
                ) {
                    val failed = row.failure != null
                    Text(
                        text = when {
                            failed -> "Could not load this row."
                            row.loading -> "Loading…"
                            row.resolved -> "No items."
                            else -> ""
                        },
                        style = InfusionTvScaleCalibration.Type.Body,
                        color = if (failed) MaterialTheme.colorScheme.error else Color.Unspecified,
                        modifier = Modifier.tvBrowseNonRailInset(),
                    )
                }
            }
        }
    }
}

private const val COLLECTION_PAGE_CONTEXT_KEY = "collection-page:context"

private fun CollectionPageRowNavigationMemory.asRowMemoryStore(): RowMemoryStore {

    val memory = this
    return object : RowMemoryStore, RowViewportMemory {
        override fun rememberedItem(rowKey: Any): String? =
            memory.rememberedItem(rowKey.toString())

        override fun rememberItem(rowKey: Any, itemKey: String) =
            memory.rememberItem(rowKey.toString(), itemKey)

        override fun rememberedViewport(rowKey: Any): RowViewport? =
            memory.viewport(rowKey.toString())
                ?.let { row -> RowViewport(row.leadingKey, row.leadingOffsetPx) }

        override fun rememberViewport(rowKey: Any, viewport: RowViewport) =
            memory.rememberViewport(rowKey.toString(), viewport.leadingKey, viewport.leadingOffsetPx)
    }
}
