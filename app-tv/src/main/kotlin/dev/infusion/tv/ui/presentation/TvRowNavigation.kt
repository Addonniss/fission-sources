package dev.infusion.tv.ui.presentation

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import dev.infusion.tv.ui.focus.LocalTvZoneEntry
import kotlin.math.abs

object TvRowRules {

    val AnchorInsetDp = TvBrowseContentAlignmentInset

    const val HeldRepeatGateMs: Long = 80L

    val RailScrollAnimation: AnimationSpec<Float> = spring(
        dampingRatio = 0.95f,
        stiffness = 180f,
    )

    const val AnchorDeadZonePx: Float = 12f

    const val RailComposedWindowFraction: Float = 1f

    @OptIn(ExperimentalFoundationApi::class)
    val RailCacheWindow: LazyLayoutCacheWindow = LazyLayoutCacheWindow(
        aheadFraction = RailComposedWindowFraction,
        behindFraction = RailComposedWindowFraction,
    )
}

interface TvRowNavigation {

    fun downTarget(fromKey: String?): String?

    fun upTarget(fromKey: String?): String?

    fun isVerticalEntry(rowKey: Any?): Boolean

    fun onHorizontalStep(rowKey: Any?)

    fun publishFirstFullyVisibleItem(rowKey: Any, itemKey: String?)
}

val LocalTvRowNavigation = compositionLocalOf<TvRowNavigation?> { null }

val LocalTvRowKey = compositionLocalOf<Any?> { null }

interface RowMemoryStore {

    fun rememberedItem(rowKey: Any): String?

    fun rememberItem(rowKey: Any, itemKey: String)
}

data class RowViewport(

    val leadingKey: String,

    val leadingOffsetPx: Int,
)

interface RowViewportMemory {

    fun rememberedViewport(rowKey: Any): RowViewport?

    fun rememberViewport(rowKey: Any, viewport: RowViewport)
}

val LocalTvRowViewportMemory = compositionLocalOf<RowViewportMemory?> { null }

data class RowViewportPlacement(val index: Int, val offsetPx: Int)

internal fun rowViewportPlacement(
    itemKeys: List<String>,
    viewport: RowViewport?,
): RowViewportPlacement? {
    if (viewport == null) return null
    val index = itemKeys.indexOf(viewport.leadingKey)
    if (index < 0) return null
    return RowViewportPlacement(index = index, offsetPx = viewport.leadingOffsetPx.coerceAtLeast(0))
}

internal fun anchoredRailBringIntoViewSpec(

    anchorPx: () -> Float,
    canScrollBackward: () -> Boolean,
    canScrollForward: () -> Boolean,

    preserveProgression: () -> Boolean = { false },
): BringIntoViewSpec = object : BringIntoViewSpec {
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float> = TvRowRules.RailScrollAnimation

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {

        if (preserveProgression()) {
            val fullyVisible = offset >= 0f && offset + size <= containerSize
            if (fullyVisible) return 0f

            return if (offset < 0f) offset else 0f
        }

        val delta = offset - anchorPx()

        if (offset >= 0f && offset + size <= containerSize && abs(delta) < TvRowRules.AnchorDeadZonePx) {
            return 0f
        }
        return when {

            delta > 0f && !canScrollForward() -> 0f
            delta < 0f && !canScrollBackward() -> 0f
            else -> delta
        }
    }
}

internal fun centredBrowseBringIntoViewSpec(
    canScrollBackward: () -> Boolean,
    canScrollForward: () -> Boolean,
): BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {

        val fullyVisible = offset >= 0f && offset + size <= containerSize
        if (fullyVisible) return 0f
        return centredFramingDeltaPx(
            offset = offset,
            size = size,
            containerSize = containerSize,
            canScrollBackward = canScrollBackward(),
            canScrollForward = canScrollForward(),
        )
    }
}

internal fun centredFramingDeltaPx(
    offset: Float,
    size: Float,
    containerSize: Float,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
): Float {
    val delta = if (size >= containerSize) {

        offset
    } else {
        offset - (containerSize - size) / 2f
    }
    return when {

        delta > 0f && !canScrollForward -> 0f
        delta < 0f && !canScrollBackward -> 0f
        else -> delta
    }
}

internal class TvRowRepeatGate(private val gateMs: Long) {

    private var lastAcceptedAtMs = 0L
    private var lastDirection = 0

    fun accept(
        repeatCount: Int,
        nowMs: Long,
        direction: Int = lastDirection,
    ): Boolean {
        if (repeatCount <= 0) {
            lastAcceptedAtMs = nowMs
            lastDirection = direction
            return true
        }
        if (direction != 0 && lastDirection != 0 && direction != lastDirection) {
            lastAcceptedAtMs = nowMs
            lastDirection = direction
            return true
        }
        if (nowMs - lastAcceptedAtMs < gateMs) return false
        lastAcceptedAtMs = nowMs
        lastDirection = direction
        return true
    }
}

internal fun nextRowFirstFullyVisibleItemKey(
    rowItemKeys: List<List<String>>,
    originRowIndex: Int,
    visibleItemInRow: (Int) -> String?,
): String? {
    for (index in originRowIndex + 1..rowItemKeys.lastIndex) {
        val keys = rowItemKeys[index]
        if (keys.isEmpty()) continue
        return visibleItemInRow(index)?.takeIf { it in keys } ?: keys.first()
    }
    return null
}

internal fun previousRowRememberedItemKey(
    rowItemKeys: List<List<String>>,
    originRowIndex: Int,
    rememberedItemInRow: (Int) -> String?,
): String? {
    for (index in originRowIndex - 1 downTo 0) {
        val keys = rowItemKeys[index]
        if (keys.isEmpty()) continue
        return rememberedItemInRow(index)?.takeIf { it in keys } ?: keys.first()
    }
    return null
}

internal fun nextRowRememberedItemKey(
    rowItemKeys: List<List<String>>,
    originRowIndex: Int,
    rememberedItemInRow: (Int) -> String?,
    visibleItemInRow: (Int) -> String?,
): String? {
    for (index in originRowIndex + 1..rowItemKeys.lastIndex) {
        val keys = rowItemKeys[index]
        if (keys.isEmpty()) continue
        rememberedItemInRow(index)?.takeIf { it in keys }?.let { return it }
        return visibleItemInRow(index)?.takeIf { it in keys } ?: keys.first()
    }
    return null
}

@Composable
internal fun rememberRailSelectionHandler(
    itemKeys: List<String>,
    paging: RowPagingState? = null,

    restoredPlacement: MutableState<Boolean>? = null,
): Modifier {
    val rowNavigation = LocalTvRowNavigation.current
    val rowKey = LocalTvRowKey.current
    val zone = LocalTvZoneEntry.current

    val repeatGate = remember { TvRowRepeatGate(TvRowRules.HeldRepeatGateMs) }
    if (rowNavigation == null) return Modifier

    val moveSelection: (String) -> Unit = { target ->
        val entry = zone
        if (entry != null && entry.registry.request(target)) {
            entry.registry.disarm()
        } else {
            entry?.arm(target)
        }
    }
    return Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val selected = zone?.registry?.lastFocusedKey
        val index = selected?.let { itemKeys.indexOf(it) } ?: -1
        when (event.key) {
            Key.DirectionLeft, Key.DirectionRight -> {

                if (index < 0) return@onPreviewKeyEvent true
                val step = if (event.key == Key.DirectionLeft) -1 else 1
                val target = index + step

                if (target > itemKeys.lastIndex) {
                    paging?.onEndBoundary()
                    return@onPreviewKeyEvent true
                }

                if (target < 0) return@onPreviewKeyEvent true
                if (!repeatGate.accept(
                        repeatCount = event.nativeKeyEvent.repeatCount,
                        nowMs = System.nanoTime() / 1_000_000L,
                        direction = step,
                    )
                ) {
                    return@onPreviewKeyEvent true
                }

                rowNavigation.onHorizontalStep(rowKey)

                restoredPlacement?.value = false
                moveSelection(itemKeys[target])
                true
            }

            Key.DirectionDown -> {

                val target = rowNavigation.downTarget(selected) ?: return@onPreviewKeyEvent false
                moveSelection(target)
                true
            }

            Key.DirectionUp -> {

                val target = rowNavigation.upTarget(selected) ?: return@onPreviewKeyEvent false
                moveSelection(target)
                true
            }

            else -> false
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberRailAnchoredSpec(
    listState: LazyListState,

    restoredPlacement: State<Boolean>? = null,
): BringIntoViewSpec {
    val rowNavigation = LocalTvRowNavigation.current
    val rowKey = LocalTvRowKey.current
    val ambient = LocalBringIntoViewSpec.current
    val density = LocalDensity.current
    val anchorPx = with(density) { TvRowRules.AnchorInsetDp.toPx() }
    return remember(anchorPx, listState, rowNavigation, rowKey, ambient, restoredPlacement) {
        if (rowNavigation == null) {
            ambient
        } else {
            anchoredRailBringIntoViewSpec(
                anchorPx = { anchorPx },
                canScrollBackward = { listState.canScrollBackward },
                canScrollForward = { listState.canScrollForward },
                preserveProgression = {
                    rowNavigation.isVerticalEntry(rowKey) || restoredPlacement?.value == true
                },
            )
        }
    }
}

@Composable
internal fun PublishRailLeadingVisibleItem(listState: LazyListState) {
    val rowNavigation = LocalTvRowNavigation.current
    val rowKey = LocalTvRowKey.current
    LaunchedEffect(rowNavigation, rowKey, listState) {
        val navigation = rowNavigation ?: return@LaunchedEffect
        val key = rowKey ?: return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val visible = layout.visibleItemsInfo
            val fullyVisible = visible.firstOrNull { item ->
                item.offset >= 0 && item.offset + item.size <= layout.viewportEndOffset
            }
            (fullyVisible ?: visible.firstOrNull())?.key as? String
        }.collect { navigation.publishFirstFullyVisibleItem(key, it) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberSharedRailLaw(
    itemKeys: List<String>,
    paging: RowPagingState? = null,
): SharedRailLaw {
    val listState = rememberLazyListState(cacheWindow = TvRowRules.RailCacheWindow)
    val spec = rememberRailAnchoredSpec(listState)
    val modifier = rememberRailSelectionHandler(itemKeys, paging)
    PublishRailLeadingVisibleItem(listState)

    MaterializeOwnPendingEntry(listState, itemKeys)
    return remember(listState, spec, modifier) { SharedRailLaw(listState, spec, modifier) }
}

@Composable
private fun MaterializeOwnPendingEntry(
    listState: LazyListState,
    itemKeys: List<String>,
) {
    val zone = LocalTvZoneEntry.current
    val registry = zone?.registry
    val armed = registry?.armedKey
    LaunchedEffect(armed, itemKeys, registry, listState) {
        if (armed == null || registry == null) return@LaunchedEffect
        if (registry.isLive(armed)) return@LaunchedEffect
        val index = itemKeys.indexOf(armed)
        if (index < 0) return@LaunchedEffect
        listState.scrollToItem(index)
    }
}

internal class SharedRailLaw(
    val listState: LazyListState,
    val spec: BringIntoViewSpec,
    val modifier: Modifier,
)
