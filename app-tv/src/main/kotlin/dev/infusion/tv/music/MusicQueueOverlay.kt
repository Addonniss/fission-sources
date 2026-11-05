package dev.infusion.tv.music

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.infusion.core.artworkimage.MediaArtworkImage
import dev.infusion.core.domain.music.MusicQueueSlotId
import dev.infusion.core.model.ArtworkLocator
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.feature.vertical.music.MusicQueueRow
import dev.infusion.feature.vertical.music.MusicQueueWindow
import dev.infusion.tv.ui.focus.FocusKeyRegistry
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.presentation.LocalCardContextMenuHost
import dev.infusion.tv.ui.presentation.musicContextActionModifier
import dev.infusion.tv.ui.presentation.queueRowMenuRequest
import dev.infusion.tv.ui.presentation.requestFocusEventually
import dev.infusion.tv.ui.screens.TvPlayerGlyphs
import dev.infusion.tv.ui.theme.InfusionTvAppearance
import dev.infusion.tv.ui.theme.InfusionTvButton
import dev.infusion.tv.ui.theme.InfusionTvContour
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.LocalInfusionAccent
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun MusicQueueOverlay(
    window: MusicQueueWindow,
    currentSlot: MusicQueueSlotId?,
    contextLabel: String?,
    onReveal: (from: Int, to: Int) -> Unit,
    onPlay: (MusicQueueSlotId) -> Unit,
    onDismiss: () -> Unit,
) {
    val requesters: FocusKeyRegistry = rememberFocusRequesters()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val host = LocalCardContextMenuHost.current
    val accent = LocalInfusionAccent.current
    val rows = window.rows

    suspend fun focusRowAt(index: Int) {
        val row = rows.getOrNull(index) ?: return
        if (row.entry == null) return
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            runCatching { listState.scrollToItem(index) }
            withFrameNanos { }
        }
        requesters.requestFocusEventually(queueRowKey(row.slot))
    }

    fun step(from: Int, delta: Int): Int? {
        var index = from + delta
        while (index in rows.indices) {
            if (rows[index].entry != null) return index
            index += delta
        }
        return null
    }

    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(rows) {
        if (revealed || rows.isEmpty()) return@LaunchedEffect
        revealed = true
        val index = rows.indexOfFirst { it.slot == currentSlot }.takeIf { it >= 0 }
            ?: rows.indexOfFirst { it.entry != null }
        if (index >= 0) focusRowAt(index)
    }

    LaunchedEffect(listState, rows.size) {
        snapshotFlow {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.minOfOrNull { it.index } ?: 0
            val last = info.visibleItemsInfo.maxOfOrNull { it.index } ?: 0
            first to last
        }.distinctUntilChanged().collect { (first, last) ->
            val from = rows.getOrNull(first)?.position
            val to = rows.getOrNull(last)?.position
            if (from != null && to != null) onReveal(from, to)
        }
    }

    var menuRowKey by remember { mutableStateOf("") }
    var menuRowIndex by remember { mutableIntStateOf(-1) }
    val menuOpenFor = host?.musicOpen?.anchorKey
    var menuWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(rows, menuOpenFor) {
        if (menuOpenFor != null) {
            menuWasOpen = true

            val index = rows.indexOfFirst { queueRowKey(it.slot) == menuOpenFor }
            if (index >= 0) {
                menuRowIndex = index
                menuRowKey = menuOpenFor
            }
            return@LaunchedEffect
        }
        if (!menuWasOpen || rows.isEmpty()) return@LaunchedEffect
        menuWasOpen = false

        withFrameNanos { }
        val present = rows.indexOfFirst { queueRowKey(it.slot) == menuRowKey }
        if (present >= 0) {
            focusRowAt(present)
            return@LaunchedEffect
        }
        val index = menuRowIndex.coerceIn(0, rows.lastIndex)
        val target = when {
            rows.getOrNull(index)?.entry != null -> index
            else -> step(index, -1) ?: step(index, +1)
        }
        if (target != null) focusRowAt(target)
    }

    Popup(
        alignment = Alignment.Center,
        offset = IntOffset.Zero,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,

            usePlatformDefaultWidth = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()

                .background(InfusionTvAppearance.OledBlack)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Back || event.key == Key.Escape)
                    ) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .padding(horizontal = 48.dp, vertical = 18.dp)
                .testTag("music-queue-overlay"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Queue",
                    style = InfusionTvScaleCalibration.Type.Display,
                    color = Color.White,
                    modifier = Modifier.testTag("music-queue-title"),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = buildString {
                        if (window.total > 0) {
                            append("${window.currentPosition + 1} of ${window.total}")
                        }
                        contextLabel?.takeIf { it.isNotBlank() }?.let {
                            if (isNotEmpty()) append(" · ")
                            append(it)
                        }
                    },
                    style = InfusionTvScaleCalibration.Type.Headline,
                    color = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.testTag("music-queue-position"),
                )
            }

            if (window.total == 0) {

                Text(
                    text = "The queue is empty.",
                    style = InfusionTvScaleCalibration.Type.Headline,
                    color = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.testTag("music-queue-empty"),
                )
                return@Column
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("music-queue-list"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(rows, key = { _, row -> queueRowKey(row.slot) }) { index, row ->
                    if (row.entry == null) {

                        Spacer(Modifier.fillMaxWidth().height(QueueRowHeight).testTag("music-queue-unloaded"))
                    } else {
                        QueueRow(
                            row = row,
                            index = index,
                            isCurrent = row.slot == currentSlot,
                            accent = accent,
                            requesters = requesters,
                            onStep = { key ->
                                val delta = when (key) {
                                    Key.DirectionUp -> -1
                                    Key.DirectionDown -> +1
                                    Key.DirectionLeft, Key.DirectionRight -> 0
                                    else -> null
                                }
                                when {
                                    delta == null -> false
                                    delta == 0 -> true
                                    else -> {
                                        val target = step(index, delta)
                                        if (target != null) scope.launch { focusRowAt(target) }

                                        true
                                    }
                                }
                            },
                            onPlay = { onPlay(row.slot) },
                            total = window.total,
                        )
                    }
                }
            }
        }
    }
}

fun queueRowKey(slot: MusicQueueSlotId): String = when (slot) {
    is MusicQueueSlotId.Source -> "music-queue:source:${slot.absoluteIndex}"
    is MusicQueueSlotId.Local -> "music-queue:local:${slot.occurrenceId.value}"
}

private val QueueRowHeight = 76.dp

private val QueueRowThumbnail = 56.dp

private val QueueRowMarkerWidth = 40.dp

@Composable
private fun QueueRow(
    row: MusicQueueRow,
    index: Int,
    isCurrent: Boolean,
    accent: Color,
    requesters: FocusKeyRegistry,
    onStep: (Key) -> Boolean,
    onPlay: () -> Unit,
    total: Int,
) {
    val entry = row.entry ?: return
    val focusKey = queueRowKey(row.slot)
    val menuRequest = remember(row.slot, row.position, total, isCurrent, entry.title) {
        queueRowMenuRequest(
            title = entry.title,
            slot = row.slot,
            position = row.position,
            total = total,
            isCurrent = isCurrent,
        )
    }
    InfusionTvButton(
        onClick = onPlay,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = stableFocusModifier(
            key = focusKey,
            requesters = requesters,
            onFocusedKeyChanged = {},
        )
            .fillMaxWidth()
            .height(QueueRowHeight)
            .testTag("music-queue-row")

            .then(musicContextActionModifier(focusKey, menuRequest))
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    onStep(event.key)
                } else {
                    when (event.key) {
                        Key.DirectionUp, Key.DirectionDown,
                        Key.DirectionLeft, Key.DirectionRight,
                        -> true
                        else -> false
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier.width(QueueRowMarkerWidth),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (isCurrent) {

                Icon(
                    imageVector = TvPlayerGlyphs.Play,
                    contentDescription = "Playing",
                    tint = accent,
                    modifier = Modifier.size(18.dp).testTag("music-queue-playing"),
                )
            } else {
                Text(
                    text = "${row.position + 1}",
                    style = InfusionTvScaleCalibration.Type.Body,
                    color = Color.White.copy(alpha = 0.60f),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(QueueRowThumbnail)
                .clip(InfusionTvContour.ArtworkShape)
                .background(Color.White.copy(alpha = 0.06f)),
        ) {
            entry.albumNativeId?.let { albumId ->
                MediaArtworkImage(
                    ref = MediaArtworkRef.catalogTyped(
                        nativeItemId = albumId,
                        type = ArtworkLocator.DEFAULT_CATALOG_TYPE,
                        policy = ArtworkPolicy.POSTER,
                    ),
                    targetSize = IntSize(160, 160),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = entry.title,
                style = InfusionTvScaleCalibration.Type.Emphasis,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            entry.secondaryLine?.let { secondary ->
                Text(
                    text = secondary,
                    style = InfusionTvScaleCalibration.Type.Body,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        entry.durationMs?.let { duration ->
            Spacer(Modifier.width(16.dp))
            Text(
                text = queueDuration(duration),
                style = InfusionTvScaleCalibration.Type.Body,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
            )
        }
    }
}

private fun queueDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    return "${totalSeconds / 60L}:${(totalSeconds % 60L).toString().padStart(2, '0')}"
}
