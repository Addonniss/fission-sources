package dev.infusion.tv.ui.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.infusion.feature.vertical.jellyfin.JellyfinTabRowItem
import dev.infusion.feature.vertical.jellyfin.library.GridDirection
import dev.infusion.feature.vertical.jellyfin.library.GridStep
import dev.infusion.feature.vertical.jellyfin.library.LibraryFooter
import dev.infusion.feature.vertical.jellyfin.library.LibraryGridCell
import dev.infusion.feature.vertical.jellyfin.library.LibraryGridMath
import dev.infusion.tv.ui.focus.FocusKeyRegistry
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.screens.JellyfinRowCard
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.min

@Composable
fun TvMediaGrid(
    cells: List<LibraryGridCell>,
    columns: Int,
    shape: ContentCardShape,
    footer: LibraryFooter?,
    requesters: FocusKeyRegistry,

    footerKey: String?,

    hasMore: Boolean?,
    onFocusChanged: (item: JellyfinTabRowItem?, absoluteServerIndex: Int?) -> Unit,
    onFirstVisibleChanged: (absoluteServerIndex: Int?) -> Unit,

    onNearEnd: () -> Unit,

    onUnloadedEntered: (absoluteServerIndex: Int) -> Unit,
    onOpenItem: (JellyfinTabRowItem) -> Unit,
    onFooterActivated: () -> Unit,
    onExitUp: () -> Boolean,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),

    focusTargetKey: String? = null,
    focusTargetRevision: Int = 0,

    categoryTiles: Map<String, LibraryCategoryTile> = emptyMap(),
    emptyContent: @Composable () -> Unit = {},
) {
    val safeColumns = columns.coerceAtLeast(1)
    val rows = remember(cells, safeColumns) { cells.chunked(safeColumns) }
    val focusableIndexes = remember(cells) { cells.indices.filter { cells[it].isFocusable } }
    val scope = rememberCoroutineScope()

    fun cellKey(index: Int): String? = cells.getOrNull(index)?.item?.key

    fun focusIndex(index: Int) {
        val key = cellKey(index) ?: return
        requesters.arm(key)
        if (requesters.request(key)) {
            requesters.disarm()
        } else {

            scope.launch { listState.scrollToItem((index / safeColumns).coerceAtLeast(0)) }
        }
    }

    fun focusFooter() {
        val key = footerKey ?: return
        requesters.arm(key)
        if (requesters.request(key)) requesters.disarm()
    }

    var handledFocusRevision by remember { mutableIntStateOf(-1) }
    LaunchedEffect(focusTargetRevision) {
        if (focusTargetRevision == handledFocusRevision) return@LaunchedEffect
        val key = focusTargetKey ?: return@LaunchedEffect
        handledFocusRevision = focusTargetRevision
        val index = cells.indexOfFirst { it.item?.key == key }
        if (index >= 0) focusIndex(index)
    }

    fun lastFocusableOfFinalRow(): Int? {
        if (focusableIndexes.isEmpty()) return null
        val lastRowStart = ((cells.size - 1).coerceAtLeast(0) / safeColumns) * safeColumns
        return focusableIndexes.lastOrNull { it >= lastRowStart } ?: focusableIndexes.last()
    }

    fun nextFocusable(from: Int, bound: Int): Int? {
        if (bound < from) return null
        for (index in from..bound) if (cells.getOrNull(index)?.isFocusable == true) return index
        return null
    }

    fun previousFocusable(from: Int, bound: Int): Int? {
        if (bound > from) return null
        for (index in from downTo bound) if (cells.getOrNull(index)?.isFocusable == true) return index
        return null
    }

    fun step(index: Int, key: Key): Boolean {
        val direction = when (key) {
            Key.DirectionLeft -> GridDirection.LEFT
            Key.DirectionRight -> GridDirection.RIGHT
            Key.DirectionUp -> GridDirection.UP
            Key.DirectionDown -> GridDirection.DOWN
            else -> return false
        }
        return when (val result = LibraryGridMath.step(
            current = index,
            direction = direction,
            cells = cells,
            columns = safeColumns,
            hasFooter = footerKey != null,
            hasMore = hasMore,
        )) {
            is GridStep.Move -> {
                focusIndex(result.index)
                true
            }
            GridStep.Footer -> {
                focusFooter()
                true
            }
            GridStep.NearEnd -> {
                onNearEnd()
                true
            }
            is GridStep.LoadUnloaded -> {

                onUnloadedEntered(result.absoluteServerIndex)
                true
            }
            GridStep.ExitUp -> onExitUp()
            GridStep.Edge -> true
        }
    }

    val prefetchRows = 2
    LaunchedEffect(listState, cells.size, safeColumns, hasMore) {
        snapshotFlow {
            val info = listState.layoutInfo
            val firstRow = info.visibleItemsInfo.minOfOrNull { it.index } ?: 0
            val lastRow = info.visibleItemsInfo.maxOfOrNull { it.index } ?: 0
            firstRow to lastRow
        }.collect { (firstRow, lastRow) ->
            val firstCell = (firstRow * safeColumns).coerceIn(0, (cells.size - 1).coerceAtLeast(0))
            onFirstVisibleChanged(cells.getOrNull(firstCell)?.absoluteServerIndex)

            if (hasMore != false && lastRow >= rows.size - 1 - prefetchRows && rows.isNotEmpty()) {
                onNearEnd()
            }
        }
    }

    if (cells.isEmpty() && footer == null) {
        emptyContent()
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        CompositionLocalProvider(

            LocalContentRowViewport provides ContentRowDensity.densityReferenceWidth(maxWidth),
        ) {

            val cardWidth = contentCardWidth(kind = null, shapeOverride = shape)
            val gridWidth = cardWidth * safeColumns + ContentRowDensity.CardSpacing * (safeColumns - 1)
            val horizontalInset = centeredGridInset(
                available = maxWidth,
                grid = gridWidth,
                base = TvBrowseContentAlignmentInset,
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("library-grid"),
                verticalArrangement = Arrangement.spacedBy(ContentRowDensity.CardSpacing),
                contentPadding = PaddingValues(
                    start = horizontalInset,
                    end = horizontalInset,
                    top = ContentRowDensity.RowVerticalPadding,
                    bottom = ContentRowDensity.RowVerticalPadding * 3,
                ),
            ) {
                itemsIndexed(rows, key = { rowIndex, _ -> "grid-row:$rowIndex" }) { rowIndex, row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ContentRowDensity.CardSpacing),
                        verticalAlignment = Alignment.Top,
                    ) {
                        row.forEachIndexed { column, cell ->
                            val flatIndex = rowIndex * safeColumns + column
                            val loadedItem = cell.item
                            if (loadedItem != null) {
                                GridCard(
                                    item = loadedItem,
                                    shape = shape,
                                    categoryTile = categoryTiles[loadedItem.key],
                                    requesters = requesters,
                                    onFocused = { key ->
                                        onFocusChanged(loadedItem, cell.absoluteServerIndex)
                                        requesters.onFocusReport(key)
                                    },
                                    onStep = { event -> step(flatIndex, event) },
                                    onOpen = { onOpenItem(loadedItem) },
                                )
                            } else {

                                UnloadedCell(shape = shape)
                            }
                        }

                        repeat(safeColumns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (footer != null && footerKey != null) {
                    item(key = "grid-footer") {
                        GridFooter(
                            footer = footer,
                            footerKey = footerKey,
                            requesters = requesters,
                            onActivated = onFooterActivated,
                            onFocused = { key -> requesters.onFocusReport(key) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GridCard(
    item: JellyfinTabRowItem,
    shape: ContentCardShape,
    categoryTile: LibraryCategoryTile? = null,
    requesters: FocusKeyRegistry,
    onFocused: (String) -> Unit,
    onStep: (Key) -> Boolean,
    onOpen: () -> Unit,
) {
    Box(
        modifier = Modifier
            .then(cardSizeModifier(shape))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {

                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> true
                        else -> false
                    }
                } else {
                    onStep(event.key)
                }
            },
    ) {
        JellyfinRowCard(
            preview = categoryTile?.let { tile ->

                item.preview.copy(artwork = tile.artwork)
            } ?: item.preview,
            focusKey = item.key,
            cardShape = shape,
            categoryTitle = categoryTile?.title,
            requesters = requesters,
            onClick = onOpen,
            onFocusedKeyChanged = onFocused,
        )
    }
}

@Composable
private fun UnloadedCell(shape: ContentCardShape) {
    Box(modifier = cardSizeModifier(shape, fillHeight = true).testTag("library-grid-unloaded"))
}

@Composable
private fun GridFooter(
    footer: LibraryFooter,
    footerKey: String,
    requesters: FocusKeyRegistry,
    onActivated: () -> Unit,
    onFocused: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (footer) {
            LibraryFooter.Loading -> Text(
                text = "Loading more…",
                style = InfusionTvScaleCalibration.Type.Emphasis,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
            )
            is LibraryFooter.Retry -> {
                InfusionTvButton(
                    onClick = onActivated,
                    modifier = stableFocusModifier(
                        key = footerKey,
                        requesters = requesters,
                        onFocusedKeyChanged = onFocused,
                    ).testTag("library-grid-footer-retry"),
                ) {
                    Text("Couldn't load more — Retry", style = InfusionTvScaleCalibration.Type.Emphasis)
                }
                Spacer(Modifier.padding(horizontal = 8.dp))
                Text(
                    text = footer.message,
                    style = InfusionTvScaleCalibration.Type.Body,
                )
            }
            LibraryFooter.Continue -> {
                InfusionTvButton(
                    onClick = onActivated,
                    modifier = stableFocusModifier(
                        key = footerKey,
                        requesters = requesters,
                        onFocusedKeyChanged = onFocused,
                    ).testTag("library-grid-footer-continue"),
                ) {
                    Text("Load more", style = InfusionTvScaleCalibration.Type.Emphasis)
                }
            }
        }
    }
}

@Composable
private fun cardSizeModifier(shape: ContentCardShape, fillHeight: Boolean = false): Modifier {
    val width = contentCardWidth(kind = null, shapeOverride = shape)
    val aspect = when (shape) {
        ContentCardShape.LANDSCAPE -> ContentCardPresentation.LANDSCAPE_ASPECT_RATIO
        ContentCardShape.PORTRAIT -> ContentCardPresentation.PORTRAIT_ASPECT_RATIO
        ContentCardShape.SQUARE -> ContentCardPresentation.SQUARE_ASPECT_RATIO
    }
    return Modifier.width(width).then(if (fillHeight) Modifier.height(width / aspect) else Modifier)
}

fun gridColumns(
    shape: ContentCardShape,
    usableWidth: Dp = ContentRowDensity.ReferenceRowViewport,
): Int {
    val cardWidth = ContentRowDensity.cardWidth(usableWidth, shape)
    val spacing = ContentRowDensity.CardSpacing
    if (cardWidth <= 0.dp) return 1
    val fit = ((usableWidth + spacing) / (cardWidth + spacing))
    return floor(fit.toDouble()).toInt().coerceAtLeast(1)
}

fun centeredGridInset(available: Dp, grid: Dp, base: Dp): Dp {
    val usable = (available - base * 2).coerceAtLeast(0.dp)
    val leftover = (usable - grid).coerceAtLeast(0.dp)
    return base + leftover / 2
}

@Composable
fun LibraryStatusLine(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(text, style = InfusionTvScaleCalibration.Type.Emphasis)
    }
}
