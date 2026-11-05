package dev.infusion.tv.ui.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.infusion.feature.vertical.jellyfin.JellyfinTabRowItem
import dev.infusion.feature.vertical.jellyfin.library.LibraryFooter
import dev.infusion.feature.vertical.jellyfin.library.LibraryGridCell
import dev.infusion.tv.music.isSongBrowsingRow
import dev.infusion.tv.ui.focus.FocusKeyRegistry
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun TvMediaList(
    cells: List<LibraryGridCell>,
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
    emptyContent: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    fun cellKey(index: Int): String? = cells.getOrNull(index)?.item?.key

    fun focusIndex(index: Int) {
        val key = cellKey(index) ?: return
        requesters.arm(key)
        if (requesters.request(key)) {
            requesters.disarm()
        } else {

            scope.launch { listState.scrollToItem(index.coerceAtLeast(0)) }
        }
    }

    fun focusFooter() {
        val key = footerKey ?: return
        requesters.arm(key)
        if (requesters.request(key)) requesters.disarm()
    }

    fun step(from: Int, delta: Int): Int? {
        var index = from + delta
        while (index in cells.indices) {
            if (cells[index].isFocusable) return index
            index += delta
        }
        return null
    }

    fun onStep(index: Int, key: Key): Boolean {
        val direction = when (key) {
            Key.DirectionUp -> -1
            Key.DirectionDown -> +1

            Key.DirectionLeft, Key.DirectionRight -> return true
            else -> return false
        }
        if (direction < 0 && index == 0) return onExitUp()
        val target = step(index, direction)
        if (target != null) {
            focusIndex(target)
            return true
        }
        if (direction > 0) {

            if (hasMore != false) {
                onNearEnd()
                return true
            }
            if (footerKey != null) {
                focusFooter()
                return true
            }
        }
        return true
    }

    var handledFocusRevision by remember { mutableIntStateOf(-1) }
    LaunchedEffect(focusTargetRevision) {
        if (focusTargetRevision == handledFocusRevision) return@LaunchedEffect
        val key = focusTargetKey ?: return@LaunchedEffect
        handledFocusRevision = focusTargetRevision
        val index = cells.indexOfFirst { it.item?.key == key }
        if (index >= 0) focusIndex(index)
    }

    LaunchedEffect(listState, cells.size, hasMore) {
        snapshotFlow {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.minOfOrNull { it.index } ?: 0
            val last = info.visibleItemsInfo.maxOfOrNull { it.index } ?: 0
            first to last
        }.collect { (first, last) ->
            val firstCell = cells.getOrNull(first)
            onFirstVisibleChanged(firstCell?.absoluteServerIndex)

            if (firstCell?.item == null) {
                firstCell?.absoluteServerIndex?.let(onUnloadedEntered)
            }
            if (hasMore != false && last >= cells.size - 2 && cells.isNotEmpty()) onNearEnd()
        }
    }

    if (cells.isEmpty() && footer == null) {
        emptyContent()
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().testTag("library-list"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(
            start = TvBrowseContentAlignmentInset,
            end = TvBrowseContentAlignmentInset,
            top = ContentRowDensity.RowVerticalPadding,
            bottom = ContentRowDensity.RowVerticalPadding * 3,
        ),
    ) {
        itemsIndexed(cells, key = { index, cell -> "list-row:$index:${cell.item?.key.orEmpty()}" }) { index, cell ->
            val loaded = cell.item
            if (loaded != null) {
                MediaListRow(
                    item = loaded,
                    requesters = requesters,
                    onFocused = { key ->
                        onFocusChanged(loaded, cell.absoluteServerIndex)
                        requesters.onFocusReport(key)
                    },
                    onStep = { key -> onStep(index, key) },
                    onOpen = { onOpenItem(loaded) },
                )
            } else {

                Spacer(Modifier.fillMaxWidth().height(ListRowHeight).testTag("library-list-unloaded"))
            }
        }
        if (footer != null && footerKey != null) {
            item(key = "list-footer") {
                ListFooter(
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

private val ListRowHeight: Dp = 88.dp

private val ListThumbnailSize: Dp = 72.dp

@Composable
private fun MediaListRow(
    item: JellyfinTabRowItem,
    requesters: FocusKeyRegistry,
    onFocused: (String) -> Unit,
    onStep: (Key) -> Boolean,
    onOpen: () -> Unit,
) {
    val songMenuRequest = remember(item.key, item.nativeType, item.unavailable, item.displayTitle) {
        if (item.isSongBrowsingRow()) {
            dev.infusion.tv.ui.presentation.browsingSongMenuRequest(item)
        } else {
            null
        }
    }
    InfusionTvButton(
        onClick = onOpen,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = stableFocusModifier(
            key = item.key,
            requesters = requesters,
            onFocusedKeyChanged = onFocused,
        )
            .fillMaxWidth()
            .height(ListRowHeight)
            .testTag("library-list-row")

            .then(dev.infusion.tv.ui.presentation.musicContextActionModifier(item.key, songMenuRequest))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionUp, Key.DirectionDown,
                        Key.DirectionLeft, Key.DirectionRight,
                        -> true
                        else -> false
                    }
                } else {
                    onStep(event.key)
                }
            },
    ) {
        Box(
            modifier = Modifier
                .size(ListThumbnailSize)
                .contentCardArtworkClip()
                .testTag("library-list-thumb"),
        ) {
            MediaCardArtwork(
                preview = item.preview.copy(

                    artwork = libraryListRowArtwork(item),
                ),
                targetSize = with(androidx.compose.ui.platform.LocalDensity.current) {
                    androidx.compose.ui.unit.IntSize(
                        ListThumbnailSize.roundToPx(),
                        ListThumbnailSize.roundToPx(),
                    )
                },
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = item.displayTitle,
                style = InfusionTvScaleCalibration.Type.Emphasis,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            listSubtitle(item)?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = InfusionTvScaleCalibration.Type.Body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        listDuration(item)?.let { duration ->
            Spacer(Modifier.width(16.dp))
            Text(text = duration, style = InfusionTvScaleCalibration.Type.Body, maxLines = 1)
        }
    }
}

private fun listSubtitle(item: JellyfinTabRowItem): String? {
    val artist = item.albumArtistName?.takeIf { it.isNotBlank() }
    val album = item.albumName?.takeIf { it.isNotBlank() }
    return when {
        artist != null && album != null -> "$artist · $album"
        artist != null -> artist
        album != null -> album
        else -> null
    }
}

private fun listDuration(item: JellyfinTabRowItem): String? {
    val ticks = item.runtimeTicks ?: return null
    if (ticks <= 0L) return null
    val totalSeconds = ticks / TICKS_PER_SECOND
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

private const val TICKS_PER_SECOND: Long = 10_000_000L

@Composable
private fun ListFooter(
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
                    ).testTag("library-list-footer-retry"),
                ) {
                    Text("Couldn't load more — Retry", style = InfusionTvScaleCalibration.Type.Emphasis)
                }
                Spacer(Modifier.padding(horizontal = 8.dp))
                Text(text = footer.message, style = InfusionTvScaleCalibration.Type.Body)
            }
            LibraryFooter.Continue -> {
                InfusionTvButton(
                    onClick = onActivated,
                    modifier = stableFocusModifier(
                        key = footerKey,
                        requesters = requesters,
                        onFocusedKeyChanged = onFocused,
                    ).testTag("library-list-footer-continue"),
                ) {
                    Text("Load more", style = InfusionTvScaleCalibration.Type.Emphasis)
                }
            }
        }
    }
}
