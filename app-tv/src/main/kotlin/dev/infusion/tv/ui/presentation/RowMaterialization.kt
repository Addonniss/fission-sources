package dev.infusion.tv.ui.presentation

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

object RowMaterialization {

    const val InitialBound: Int = 25

    const val RevealStep: Int = InitialBound

    fun initialCount(loadedCount: Int): Int = loadedCount.coerceAtMost(InitialBound)
}

@Stable
class ContentRowMaterializationState internal constructor() {

    private var loaded by mutableIntStateOf(0)
    private var materialized by mutableIntStateOf(0)

    private var lastVisibleIndex: Int = -1

    val loadedCount: Int get() = loaded

    val materializedCount: Int get() = materialized

    fun bindLoadedCount(loadedCount: Int) {
        loaded = loadedCount
        materialized = materialized
            .coerceAtMost(loadedCount)
            .coerceAtLeast(RowMaterialization.initialCount(loadedCount))
        revealIfWindowConsumed()
    }

    fun onVisibleRange(lastVisibleIndex: Int) {
        this.lastVisibleIndex = lastVisibleIndex
        revealIfWindowConsumed()
    }

    private fun revealIfWindowConsumed() {
        if (lastVisibleIndex < 0) return
        if (materialized >= loaded) return
        if (lastVisibleIndex < materialized - 1 - RowPaging.EndThreshold) return
        materialized = (materialized + RowMaterialization.RevealStep).coerceAtMost(loaded)
    }

    fun revealThrough(index: Int) {
        if (index < 0) return
        materialized = materialized
            .coerceAtLeast(index + 1 + RowPaging.EndThreshold)
            .coerceAtMost(loaded)
    }

    fun holdViewport(index: Int) {
        if (index < 0) return
        materialized = materialized
            .coerceAtLeast(index + 1 + RowMaterialization.RevealStep)
            .coerceAtMost(loaded)
    }
}

val LocalContentRowMaterialization = compositionLocalOf { ContentRowMaterializationState() }

@Composable
fun rememberContentRowMaterialization(): ContentRowMaterializationState =
    remember { ContentRowMaterializationState() }

internal fun LazyListLayoutInfo.lastViewportIntersectingIndex(): Int =
    visibleItemsInfo
        .lastOrNull { item ->
            item.offset < viewportEndOffset && item.offset + item.size > viewportStartOffset
        }
        ?.index
        ?: -1

@Composable
internal fun ContentRowMaterializationState.observeVisibleRange(
    listState: LazyListState,
) {
    LaunchedEffect(this, listState) {
        snapshotFlow { listState.layoutInfo.lastViewportIntersectingIndex() }
            .collect { lastVisible -> onVisibleRange(lastVisible) }
    }
}
