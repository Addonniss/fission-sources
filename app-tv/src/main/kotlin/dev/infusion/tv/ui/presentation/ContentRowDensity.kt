package dev.infusion.tv.ui.presentation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.infusion.core.model.TitleKind
import dev.infusion.tv.ui.focus.LocalTvZoneEntry
import kotlin.math.floor
import kotlin.math.sqrt

object ContentRowDensity {

    const val POSTER_VISIBLE_CARDS: Float = 6f + 1f / 3f

    val LANDSCAPE_TO_POSTER_WIDTH: Float = sqrt(
        (1f / ContentCardPresentation.PORTRAIT_ASPECT_RATIO) *
            ContentCardPresentation.LANDSCAPE_ASPECT_RATIO,
    )

    val SQUARE_TO_POSTER_WIDTH: Float = sqrt(
        (1f / ContentCardPresentation.PORTRAIT_ASPECT_RATIO) *
            ContentCardPresentation.SQUARE_ASPECT_RATIO,
    )

    val CardSpacing: Dp = 12.dp

    val RowEdgePadding: Dp = 4.dp

    val RowVerticalPadding: Dp = 4.dp

    val ReferenceRowViewport: Dp = 864.dp

    fun densityReferenceWidth(
        rowWidth: Dp,
        startInset: Dp = TvBrowseContentAlignmentInset,
        trailingAllowance: Dp = startInset,
    ): Dp = (rowWidth - startInset - trailingAllowance).coerceAtLeast(0.dp)

    fun cardWidth(
        rowWidth: Dp,
        shape: ContentCardShape,
        cardSpacing: Dp = CardSpacing,
        edgePadding: Dp = RowEdgePadding,
    ): Dp {
        val usable = (rowWidth - edgePadding * 2).coerceAtLeast(0.dp)
        val gaps = floor(POSTER_VISIBLE_CARDS).toInt()
        val posterWidth = ((usable - cardSpacing * gaps) / POSTER_VISIBLE_CARDS)
            .coerceAtLeast(0.dp)
        return when (shape) {
            ContentCardShape.PORTRAIT -> posterWidth
            ContentCardShape.LANDSCAPE -> posterWidth * LANDSCAPE_TO_POSTER_WIDTH
            ContentCardShape.SQUARE -> posterWidth * SQUARE_TO_POSTER_WIDTH
        }
    }

    fun visibleCards(
        rowWidth: Dp,
        shape: ContentCardShape,
        cardSpacing: Dp = CardSpacing,
        edgePadding: Dp = RowEdgePadding,
    ): Float {
        val usable = (rowWidth - edgePadding * 2).coerceAtLeast(0.dp)
        val width = cardWidth(rowWidth, shape, cardSpacing, edgePadding)
        if (width <= 0.dp) return 0f
        val slot = width + cardSpacing
        val complete = floor(((usable + cardSpacing) / slot).toDouble()).toInt().coerceAtLeast(0)
        val partial = ((usable - slot * complete) / width).coerceIn(0f, 1f)
        return complete + partial
    }
}

val LocalContentRowViewport = compositionLocalOf { ContentRowDensity.ReferenceRowViewport }

@Composable
fun contentCardWidth(kind: TitleKind?, shapeOverride: ContentCardShape? = null): Dp =
    ContentRowDensity.cardWidth(
        rowWidth = LocalContentRowViewport.current,
        shape = shapeOverride ?: LocalContentCardPresentation.current.shapeFor(kind),
    )

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> InfusionContentRow(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = TvBrowseContentAlignmentInset,
        end = 0.dp,
        top = ContentRowDensity.RowVerticalPadding,
        bottom = ContentRowDensity.RowVerticalPadding,
    ),

    paging: RowPagingState? = null,

    onWindowChanged: (loaded: Int, materialized: Int) -> Unit = { _, _ -> },
    itemContent: @Composable (T) -> Unit,
) {
    val zone = LocalTvZoneEntry.current

    val rowNavigation = LocalTvRowNavigation.current

    val rowKey = LocalTvRowKey.current
    val density = LocalDensity.current

    val rowItemKeys: List<String> = items.mapNotNull { key(it) as? String }

    val listState = rememberLazyListState(cacheWindow = TvRowRules.RailCacheWindow)

    val viewportMemory = LocalTvRowViewportMemory.current
    val restoredPlacement = remember { mutableStateOf(false) }
    val viewportResolved = remember { mutableStateOf(false) }
    val restoredIndex = remember { mutableIntStateOf(-1) }
    val restoredOffset = remember { mutableIntStateOf(0) }
    val restoredLeadingKey = remember { mutableStateOf<String?>(null) }
    val anchoredRowSpec = rememberRailAnchoredSpec(listState, restoredPlacement)
    val ambientBringIntoViewSpec = LocalBringIntoViewSpec.current
    val rowKeyHandler = rememberRailSelectionHandler(rowItemKeys, paging, restoredPlacement)
    PublishRailLeadingVisibleItem(listState)

    val currentItemKeys = rememberUpdatedState(rowItemKeys)
    if (viewportMemory != null) {
        LaunchedEffect(rowKey, listState, viewportMemory) {
            val key = rowKey ?: return@LaunchedEffect
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    if (!viewportResolved.value) return@collect
                    if (restoredPlacement.value && index != restoredIndex.intValue) return@collect
                    val leading = currentItemKeys.value.getOrNull(index) ?: return@collect
                    viewportMemory.rememberViewport(key, RowViewport(leading, offset))
                }
        }
    }

    if (paging != null) {
        LaunchedEffect(paging, listState) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.count { it.size > 0 } }
                .collect { visible -> paging.onVisibleItemsChanged(visible) }
        }
    }

    val materialization = rememberContentRowMaterialization()
    materialization.bindLoadedCount(items.size)
    materialization.observeVisibleRange(listState)

    if (!viewportResolved.value && rowKey != null) {
        val rememberedViewport = viewportMemory?.rememberedViewport(rowKey)
        val placement = rowViewportPlacement(rowItemKeys, rememberedViewport)
        when {
            placement != null -> {
                materialization.holdViewport(placement.index)
                SideEffect {
                    listState.requestScrollToItem(placement.index, placement.offsetPx)
                    restoredIndex.intValue = placement.index
                    restoredOffset.intValue = placement.offsetPx
                    restoredLeadingKey.value = rememberedViewport?.leadingKey
                    restoredPlacement.value = true
                    viewportResolved.value = true
                }
            }

            rememberedViewport == null || rowItemKeys.isNotEmpty() -> SideEffect {
                viewportResolved.value = true
            }
        }
    }

    if (restoredPlacement.value) {
        val leading = restoredLeadingKey.value
        val currentIndex = if (leading == null) -1 else rowItemKeys.indexOf(leading)
        if (currentIndex >= 0 && currentIndex != restoredIndex.intValue) {
            materialization.holdViewport(currentIndex)
            SideEffect {
                listState.requestScrollToItem(currentIndex, restoredOffset.intValue)
                restoredIndex.intValue = currentIndex
            }
        }
    }

    LaunchedEffect(materialization) {
        snapshotFlow { materialization.loadedCount to materialization.materializedCount }
            .collect { (loaded, materialized) -> onWindowChanged(loaded, materialized) }
    }

    val materialized = remember(items, materialization.materializedCount) {
        if (materialization.materializedCount >= items.size) {
            items
        } else {
            items.take(materialization.materializedCount)
        }
    }

    LaunchedEffect(zone?.armedKey, items, materialization.materializedCount) {
        val armed = zone?.armedKey ?: return@LaunchedEffect
        val index = items.indexOfFirst { key(it) == armed }
        if (index >= 0 && !zone.registry.isLive(armed)) {
            if (index >= materialization.materializedCount) {
                materialization.revealThrough(index)
                return@LaunchedEffect
            }

            listState.animateScrollToItem(index)
        }
    }

    val focusedContentKey = zone?.registry?.focusedKey
    LaunchedEffect(focusedContentKey, items, materialization.materializedCount) {
        val focused = focusedContentKey ?: return@LaunchedEffect
        val index = items.indexOfFirst { key(it) == focused }
        if (index < 0) return@LaunchedEffect
        if (index >= materialization.materializedCount) {
            materialization.revealThrough(index)
            return@LaunchedEffect
        }
        val layout = listState.layoutInfo
        val fullyInside = layout.visibleItemsInfo.any { item ->
            item.index == index &&
                item.offset >= 0 &&
                item.offset + item.size <= layout.viewportEndOffset
        }
        if (!fullyInside) listState.animateScrollToItem(index)
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth().then(rowKeyHandler)) {
        CompositionLocalProvider(

            LocalContentRowViewport provides
                ContentRowDensity.densityReferenceWidth(maxWidth),
            LocalContentRowMaterialization provides materialization,

            LocalBringIntoViewSpec provides
                if (rowNavigation != null) anchoredRowSpec else ambientBringIntoViewSpec,
        ) {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ContentRowDensity.CardSpacing),
                contentPadding = contentPadding,
            ) {
                items(materialized, key = key) { item -> itemContent(item) }
            }
        }
    }
}
