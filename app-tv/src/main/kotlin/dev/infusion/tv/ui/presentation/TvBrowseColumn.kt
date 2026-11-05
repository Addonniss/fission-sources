package dev.infusion.tv.ui.presentation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.infusion.core.artworkimage.LocalArtworkLoadEnabled
import dev.infusion.tv.ui.focus.ContentFocusObserver
import dev.infusion.tv.ui.focus.ContentNeighbourhood
import dev.infusion.tv.ui.focus.ContentSlot
import dev.infusion.tv.ui.focus.FocusKeyRegistry
import dev.infusion.tv.ui.focus.LocalTvZoneEntry
import dev.infusion.tv.ui.focus.MaterializationProbe
import dev.infusion.tv.ui.focus.TvZoneEntry
import dev.infusion.tv.ui.focus.rememberTvZoneEntry
import dev.infusion.tv.ui.shell.LocalTvTopShelfInset
import dev.infusion.tv.ui.shell.LocalTvTopShelfScrollSeam

val TvBrowseContentAlignmentInset: Dp = 48.dp

private val TvBrowseTopPadding = 8.dp

private val TvBrowseBottomPadding = 24.dp

val TvBrowseContentPadding = PaddingValues(
    start = 0.dp,
    end = 0.dp,
    top = TvBrowseTopPadding,
    bottom = TvBrowseBottomPadding,
)

fun Modifier.tvBrowseNonRailInset(): Modifier =
    padding(horizontal = TvBrowseContentAlignmentInset)

fun tvRailContentPadding(vertical: Dp = 0.dp): PaddingValues = PaddingValues(
    start = TvBrowseContentAlignmentInset,
    end = 0.dp,
    top = vertical,
    bottom = vertical,
)

fun tvRailLawPagePadding(vertical: Dp): PaddingValues = PaddingValues(
    start = 0.dp,
    end = 0.dp,
    top = vertical,
    bottom = vertical,
)

@Composable
fun tvBrowseContentPadding(): PaddingValues =
    tvBrowseContentPadding(LocalTvTopShelfInset.current)

fun tvBrowseContentPadding(shelfInset: Dp): PaddingValues = if (shelfInset <= 0.dp) {
    TvBrowseContentPadding
} else {
    PaddingValues(
        start = 0.dp,
        end = 0.dp,
        top = TvBrowseTopPadding + shelfInset,
        bottom = TvBrowseBottomPadding,
    )
}

val TvBrowseRowSpacingDp: Dp = 24.dp

val TvBrowseRowSpacing = Arrangement.spacedBy(TvBrowseRowSpacingDp)

val TvBrowsePrecompositionAhead: Dp = 6000.dp

val TvBrowsePrecompositionBehind: Dp = 4000.dp

const val TvBrowseArtworkRowMargin: Int = 1

private val TvBrowseFocusInertBringIntoViewSpec: BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

internal fun stageOwnedVerticalFramingSpec(
    ambient: BringIntoViewSpec,

    earlierStageBottomPx: () -> Float,

    stageBottomPx: () -> Float,

    stageIsPresented: () -> Boolean,

    atRestAnchorDistancePx: () -> Float,

    stillEstablishingAtRest: () -> Boolean = { false },

    returnInProgress: () -> Boolean = { false },

    laterStageFraming: BringIntoViewSpec? = null,

    laterStageTopBandBottomPx: (() -> Float)? = null,

    laterStageAnchorDistancePx: (() -> Float)? = null,
): BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {

        if (returnInProgress()) return 0f

        if (stillEstablishingAtRest()) {
            return atRestAnchorDistancePx()
        }

        if (offset + size <= earlierStageBottomPx()) {
            return atRestAnchorDistancePx()
        }

        if (!stageIsPresented()) {
            return ambient.calculateScrollDistance(offset, size, containerSize)
        }

        val bandBottomPx = laterStageTopBandBottomPx
        if (bandBottomPx != null && offset + size <= bandBottomPx()) {
            return minOf(laterStageAnchorDistancePx?.invoke() ?: 0f, 0f)
        }

        if (bandBottomPx == null) {
            val fullyPresented = offset >= 0f && offset + size <= stageBottomPx()
            if (fullyPresented) return 0f
        }
        return (laterStageFraming ?: ambient).calculateScrollDistance(offset, size, containerSize)
    }
}

@Composable
fun TvBrowsePageZone(
    requesters: FocusKeyRegistry,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalTvZoneEntry provides rememberTvZoneEntry(requesters)) {
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> TvBrowseColumn(
    rows: List<T>,
    rowKey: (T) -> Any,
    rowItemKeys: (T) -> List<String>,
    modifier: Modifier = Modifier,

    contentPadding: PaddingValues = tvBrowseContentPadding(),
    verticalArrangement: Arrangement.Vertical = TvBrowseRowSpacing,
    listTag: String? = null,

    listState: LazyListState = rememberTvBrowseListState(),

    header: (@Composable () -> Unit)? = null,

    pageOwnsVerticalFraming: Boolean = false,

    loadAheadRowKeys: Set<Any> = emptySet(),

    arrivingRowKeys: Set<Any> = emptySet(),

    originRowArrivalIndeterminate: Boolean = false,

    sharedRowRules: Boolean = false,

    rememberedRowMemory: RowMemoryStore? = null,

    downRestoresRemembered: Boolean = false,

    centredVerticalFraming: Boolean = false,
    rowContent: @Composable (T) -> Unit,
) {

    val suppliedZone = LocalTvZoneEntry.current
    val suppliedRegistry = suppliedZone?.registry
    val createdOwner = remember(sharedRowRules, suppliedRegistry) {
        if (sharedRowRules && suppliedRegistry == null) FocusKeyRegistry() else null
    }
    val registry: FocusKeyRegistry? =
        if (sharedRowRules) suppliedRegistry ?: createdOwner else suppliedRegistry

    val zone: TvZoneEntry? =
        if (sharedRowRules && registry != null) {
            rememberTvZoneEntry(registry, adoptsPageRegistrations = true)
        } else {
            suppliedZone
        }

    val headerCount = if (header != null) 1 else 0

    val ownedCentredTransitions =
        centredVerticalFraming && !pageOwnsVerticalFraming && sharedRowRules && registry != null

    val ambientBringIntoViewSpec = LocalBringIntoViewSpec.current
    val pageBringIntoViewSpec = when {

        pageOwnsVerticalFraming -> TvBrowseFocusInertBringIntoViewSpec

        ownedCentredTransitions -> TvBrowseFocusInertBringIntoViewSpec

        centredVerticalFraming -> remember(centredVerticalFraming, listState) {
            centredBrowseBringIntoViewSpec(
                canScrollBackward = { listState.canScrollBackward },
                canScrollForward = { listState.canScrollForward },
            )
        }

        else -> ambientBringIntoViewSpec
    }

    val currentRows = rememberUpdatedState(rows)
    val currentItemKeys = rememberUpdatedState(rowItemKeys)
    val currentRowKey = rememberUpdatedState(rowKey)

    val rowViewportMemory: RowViewportMemory? = rememberedRowMemory as? RowViewportMemory

    val currentArrivingRowKeys = rememberUpdatedState(arrivingRowKeys)

    val currentArrivalIndeterminate = rememberUpdatedState(originRowArrivalIndeterminate)
    if (registry != null) {
        val probe = remember {
            object : MaterializationProbe {
                override fun canMaterialize(key: String): Boolean =
                    currentRows.value.any { row -> currentItemKeys.value(row).any { it == key } }

                override fun hasData(): Boolean = currentRows.value.isNotEmpty()
            }
        }

        registry.registerMaterialization(probe)
        DisposableEffect(registry, probe) {
            onDispose { registry.unregisterMaterialization(probe) }
        }

        val neighbourhood = remember {
            object : ContentNeighbourhood {
                override fun locationOf(key: String): ContentSlot? {
                    val rows = currentRows.value
                    val keysOf = currentItemKeys.value
                    rows.forEachIndexed { rowIndex, row ->
                        val itemIndex = keysOf(row).indexOf(key)
                        if (itemIndex >= 0) {
                            return ContentSlot(currentRowKey.value(row), rowIndex, itemIndex)
                        }
                    }
                    return null
                }

                override fun nearbySurvivor(slot: ContentSlot): String? {
                    val rows = currentRows.value
                    val keysOf = currentItemKeys.value
                    if (rows.isEmpty()) return null

                    val origin = rows.indexOfFirst { currentRowKey.value(it) == slot.rowKey }
                        .takeIf { it >= 0 }
                        ?: slot.rowIndex.coerceIn(0, rows.lastIndex)
                    val originKeys = keysOf(rows[origin])
                    val originSurvivor = originKeys.nearbyInRow(slot.itemIndex)
                    if (originSurvivor != null) return originSurvivor

                    for (step in 1..rows.lastIndex) {
                        for (candidate in intArrayOf(origin + step, origin - step)) {
                            val row = rows.getOrNull(candidate) ?: continue
                            val first = keysOf(row).firstOrNull()
                            if (first != null) return first
                        }
                    }

                    return null
                }

                override fun isOriginRowArriving(slot: ContentSlot): Boolean =
                    slot.rowKey in currentArrivingRowKeys.value ||

                        currentArrivalIndeterminate.value
            }
        }
        registry.registerContentNeighbourhood(neighbourhood)
        DisposableEffect(registry, neighbourhood) {
            onDispose { registry.unregisterContentNeighbourhood(neighbourhood) }
        }
    }

    var rowNavigation: TvRowNavigation? = null

    val verticalAct = remember { mutableStateOf<VerticalAct?>(null) }
    if (registry != null && sharedRowRules) {

        val currentRetainedMemory = rememberUpdatedState(rememberedRowMemory)

        val currentDownRestoresRemembered = rememberUpdatedState(downRestoresRemembered)

        val rowMemory = remember { mutableMapOf<Any, String>() }
        fun rememberedItemOf(rowKey: Any): String? =
            currentRetainedMemory.value?.rememberedItem(rowKey) ?: rowMemory[rowKey]
        fun rememberItemOf(rowKey: Any, itemKey: String) {
            val retained = currentRetainedMemory.value
            if (retained != null) retained.rememberItem(rowKey, itemKey) else rowMemory[rowKey] = itemKey
        }
        val rowFocusObserver = remember {
            ContentFocusObserver { key, slot ->

                if (slot != null) rememberItemOf(slot.rowKey, key)
            }
        }
        registry.registerContentFocusObserver(rowFocusObserver)
        DisposableEffect(registry, rowFocusObserver) {
            onDispose { registry.unregisterContentFocusObserver(rowFocusObserver) }
        }

        val rowLeadingVisible = remember { mutableMapOf<Any, String>() }
        rowNavigation = remember {
            object : TvRowNavigation {

                private var verticalEntryRow: Any? = null

                fun originRowIndex(fromKey: String?): Int? {
                    if (fromKey == null) return null
                    currentRows.value.forEachIndexed { index, row ->
                        if (fromKey in currentItemKeys.value(row)) return index
                    }
                    return null
                }

                fun itemKeysPerRow(): List<List<String>> =
                    currentRows.value.map { row -> currentItemKeys.value(row) }

                fun leadingVisibleInRow(index: Int): String? {
                    val row = currentRows.value.getOrNull(index) ?: return null
                    return rowLeadingVisible[currentRowKey.value(row)]
                }

                fun rememberedInRow(index: Int): String? {
                    val row = currentRows.value.getOrNull(index) ?: return null
                    return rememberedItemOf(currentRowKey.value(row))
                }

                fun noteVerticalEntry(target: String) {
                    val index = itemKeysPerRow().indexOfFirst { target in it }
                    val row = currentRows.value.getOrNull(index)
                    if (row == null) {
                        verticalEntryRow = null
                        return
                    }
                    val rowKey = currentRowKey.value(row)
                    verticalEntryRow = if (rowLeadingVisible[rowKey] == target) rowKey else null
                }

                override fun downTarget(fromKey: String?): String? {
                    val origin = originRowIndex(fromKey) ?: return null
                    val keysPerRow = itemKeysPerRow()
                    val target = if (currentDownRestoresRemembered.value) {

                        nextRowRememberedItemKey(
                            rowItemKeys = keysPerRow,
                            originRowIndex = origin,
                            rememberedItemInRow = { index -> rememberedInRow(index) },
                            visibleItemInRow = { index -> leadingVisibleInRow(index) },
                        )
                    } else {

                        nextRowFirstFullyVisibleItemKey(
                            rowItemKeys = keysPerRow,
                            originRowIndex = origin,
                            visibleItemInRow = { index -> leadingVisibleInRow(index) },
                        )
                    } ?: return null
                    noteVerticalEntry(target)
                    noteVerticalAct(target)
                    return target
                }

                override fun upTarget(fromKey: String?): String? {
                    val origin = originRowIndex(fromKey) ?: return null

                    val target = previousRowRememberedItemKey(itemKeysPerRow(), origin) { rowIndex ->
                        rememberedInRow(rowIndex)
                    } ?: return null
                    noteVerticalEntry(target)
                    noteVerticalAct(target)
                    return target
                }

                fun noteVerticalAct(target: String) {
                    verticalAct.value = VerticalAct(target, (verticalAct.value?.serial ?: 0) + 1)
                }

                override fun isVerticalEntry(rowKey: Any?): Boolean =
                    rowKey != null && verticalEntryRow == rowKey

                override fun onHorizontalStep(rowKey: Any?) {
                    if (rowKey != null && verticalEntryRow == rowKey) verticalEntryRow = null
                }

                override fun publishFirstFullyVisibleItem(rowKey: Any, itemKey: String?) {
                    if (itemKey == null) {
                        rowLeadingVisible.remove(rowKey)
                    } else {
                        rowLeadingVisible[rowKey] = itemKey
                    }
                }
            }
        }
    }

    if (ownedCentredTransitions) {
        val act = verticalAct.value
        LaunchedEffect(act, listState, rows) {
            val target = act?.target ?: return@LaunchedEffect
            val index = rows.indexOfFirst { row -> rowItemKeys(row).any { it == target } }
            if (index < 0) return@LaunchedEffect
            val slot = index + headerCount

            withFrameNanos { }
            suspend fun place(): Boolean {
                val layout = listState.layoutInfo
                val item = layout.visibleItemsInfo.firstOrNull { it.index == slot } ?: return false

                val usableStart = layout.viewportStartOffset.toFloat()
                val usableEnd = layout.viewportEndOffset.toFloat()
                val delta = centredFramingDeltaPx(
                    offset = item.offset - usableStart,
                    size = item.size.toFloat(),
                    containerSize = usableEnd - usableStart,
                    canScrollBackward = listState.canScrollBackward,
                    canScrollForward = listState.canScrollForward,
                )
                if (delta != 0f) listState.scrollBy(delta)
                return true
            }
            if (place()) return@LaunchedEffect

            listState.scrollToItem(slot)
            withFrameNanos { }
            place()
        }
    }

    val armedKey = zone?.armedKey
    LaunchedEffect(armedKey, rows) {
        if (armedKey == null) return@LaunchedEffect
        if (registry?.isLive(armedKey) == true) return@LaunchedEffect
        val index = rows.indexOfFirst { row -> rowItemKeys(row).any { it == armedKey } }
        if (index < 0) return@LaunchedEffect

        val slot = index + headerCount
        val rowOnScreen = listState.layoutInfo.visibleItemsInfo.any { it.index == slot }
        if (rowOnScreen) return@LaunchedEffect
        listState.scrollToItem(slot)
    }

    val rowKeeperOwnsFraming = focusedRowKeeperApplies(pageOwnsVerticalFraming)
    val focusedRowViewportOrder = rows.map { row -> rowKey(row) }
    LaunchedEffect(registry, listState, focusedRowViewportOrder, rowKeeperOwnsFraming) {
        if (!rowKeeperOwnsFraming) return@LaunchedEffect
        val registered = registry ?: return@LaunchedEffect
        val focused = registered.lastFocusedKey ?: return@LaunchedEffect
        val index = rows.indexOfFirst { row -> rowItemKeys(row).any { it == focused } }
        if (index < 0) return@LaunchedEffect
        val slot = index + headerCount

        withFrameNanos { }
        val layout = listState.layoutInfo

        val usableStart = (layout.viewportStartOffset + layout.beforeContentPadding).toFloat()
        val usableEnd = (layout.viewportEndOffset + layout.afterContentPadding).toFloat()
        val item = layout.visibleItemsInfo.firstOrNull { it.index == slot }
            ?: return@LaunchedEffect
        val delta = focusedRowRevealDeltaPx(
            offset = item.offset.toFloat(),
            size = item.size.toFloat(),
            usableStart = usableStart,
            usableEnd = usableEnd,
        )
        if (delta != 0f) listState.scrollBy(delta)
    }

    val centredFocusedKey = if (centredVerticalFraming && !pageOwnsVerticalFraming) {
        registry?.lastFocusedKey
    } else {
        null
    }
    LaunchedEffect(registry, listState, centredFocusedKey, focusedRowViewportOrder) {
        val registered = registry ?: return@LaunchedEffect
        val focused = registered.lastFocusedKey ?: return@LaunchedEffect
        val index = rows.indexOfFirst { row -> rowItemKeys(row).any { it == focused } }
        if (index < 0) return@LaunchedEffect
        val slot = index + headerCount

        withFrameNanos { }
        val layout = listState.layoutInfo
        val visible = layout.visibleItemsInfo
        if (!focusedRowNeedsReveal(visible.firstOrNull()?.index, visible.lastOrNull()?.index, slot)) {
            return@LaunchedEffect
        }

        listState.scrollToItem(slot)
    }

    val shelfScrollSeam = LocalTvTopShelfScrollSeam.current
    if (shelfScrollSeam != null) {
        LaunchedEffect(shelfScrollSeam, listState) {
            snapshotFlow { listState.shelfScrollOffsetPx() }
                .collect { shelfScrollSeam.publish(it) }
        }
    }

    val topResetToken = shelfScrollSeam?.topResetToken ?: 0
    if (shelfScrollSeam != null) {
        LaunchedEffect(shelfScrollSeam, topResetToken) {
            if (topResetToken == 0) return@LaunchedEffect
            if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                return@LaunchedEffect
            }

            listState.scrollToItem(0, 0)
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides pageBringIntoViewSpec) {
        LazyColumn(
            state = listState,
            modifier = modifier.then(
                if (listTag != null) Modifier.testTag(listTag) else Modifier,
            ),
            verticalArrangement = verticalArrangement,
            contentPadding = contentPadding,
        ) {
            if (header != null) {
                item(key = TvBrowseHeaderKey) {

                    CompositionLocalProvider(
                        LocalBringIntoViewSpec provides ambientBringIntoViewSpec,
                    ) {
                        header()
                    }
                }
            }
            itemsIndexed(rows, key = { _, row -> rowKey(row) }) { index, row ->

                CompositionLocalProvider(
                    LocalArtworkLoadEnabled provides isRowLoadEligible(
                        nearViewport = isRowNearViewport(listState, index + headerCount),
                        rowKey = rowKey(row),
                        loadAheadRowKeys = loadAheadRowKeys,
                    ),

                    LocalBringIntoViewSpec provides ambientBringIntoViewSpec,

                    LocalTvRowNavigation provides rowNavigation,

                    LocalTvRowKey provides rowKey(row),

                    LocalTvRowViewportMemory provides rowViewportMemory,
                ) {

                    if (sharedRowRules && zone != null) {
                        CompositionLocalProvider(LocalTvZoneEntry provides zone) {
                            rowContent(row)
                        }
                    } else {
                        rowContent(row)
                    }
                }
            }
        }
    }
}

private const val TvBrowseHeaderKey = "tv:browse:header"

internal data class VerticalAct(val target: String, val serial: Int)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberTvBrowseListState(): LazyListState = rememberLazyListState(
    cacheWindow = LazyLayoutCacheWindow(
        ahead = TvBrowsePrecompositionAhead,
        behind = TvBrowsePrecompositionBehind,
    ),
)

private fun LazyListState.shelfScrollOffsetPx(): Float =
    if (firstVisibleItemIndex == 0) {
        firstVisibleItemScrollOffset.toFloat()
    } else {
        Float.MAX_VALUE
    }

@Composable
private fun isRowNearViewport(listState: LazyListState, index: Int): Boolean {
    val near by remember(listState, index) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) {
                false
            } else {
                val first = visible.first().index
                val last = visible.last().index
                index in (first - TvBrowseArtworkRowMargin)..(last + TvBrowseArtworkRowMargin)
            }
        }
    }
    return near
}

internal fun isRowLoadEligible(
    nearViewport: Boolean,
    rowKey: Any,
    loadAheadRowKeys: Set<Any>,
): Boolean = nearViewport || rowKey in loadAheadRowKeys

internal fun focusedRowRevealDeltaPx(
    offset: Float,
    size: Float,
    usableStart: Float,
    usableEnd: Float,
): Float = when {
    offset + size > usableEnd -> (offset + size) - usableEnd
    offset < usableStart -> offset - usableStart
    else -> 0f
}

internal fun focusedRowKeeperApplies(pageOwnsVerticalFraming: Boolean): Boolean =
    !pageOwnsVerticalFraming

internal fun focusedRowNeedsReveal(
    firstVisibleIndex: Int?,
    lastVisibleIndex: Int?,
    slot: Int,
): Boolean {
    val first = firstVisibleIndex ?: return false
    val last = lastVisibleIndex ?: return false
    return slot < first || slot > last
}

private fun List<String>.nearbyInRow(from: Int): String? =
    if (isEmpty()) null else this[from.coerceIn(0, lastIndex)]
