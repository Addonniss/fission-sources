package dev.infusion.tv.ui.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

object RowPaging {

    const val EndThreshold: Int = 4
}

enum class RowEndBoundary {
    REQUESTED,
    PENDING,
    CONTENT_END,
    NONE,
}

class RowPagingState internal constructor() {

    private var itemKeys: List<String> = emptyList()
    private var hasMore: Boolean = false
    private var onLoadMore: (() -> Unit)? = null
    private var requestedItemCount: Int? = null

    private var visibleItemCount: Int = 0

    val nearEndWindow: Int
        get() = maxOf(RowPaging.EndThreshold, visibleItemCount)

    internal fun onVisibleItemsChanged(count: Int) {
        visibleItemCount = count.coerceAtLeast(0)
    }

    private var lastPagingFailureToken: Long? = null

    val lastRequestedItemCount: Int? get() = requestedItemCount

    internal fun update(
        itemKeys: List<String>,
        hasMore: Boolean,
        onLoadMore: (() -> Unit)?,
        pagingFailed: Boolean,
        pagingFailureToken: Long? = null,
    ) {
        this.itemKeys = itemKeys
        this.hasMore = hasMore
        this.onLoadMore = onLoadMore
        if (!pagingFailed) {

            lastPagingFailureToken = null
            return
        }
        val firstSightOfFailure =
            pagingFailureToken == null || pagingFailureToken != lastPagingFailureToken
        lastPagingFailureToken = pagingFailureToken
        if (firstSightOfFailure) requestedItemCount = null
    }

    fun onFocusedKey(key: String) {
        val loadMore = onLoadMore ?: return
        if (!hasMore) return
        val count = itemKeys.size
        if (count <= 0) return
        val focusedIndex = itemKeys.indexOf(key)
        if (focusedIndex < 0) return
        if (focusedIndex < count - 1 - nearEndWindow) return
        if (requestedItemCount == count) return
        requestedItemCount = count
        loadMore()
    }

    fun onEndBoundary(): RowEndBoundary {
        val loadMore = onLoadMore ?: return RowEndBoundary.NONE
        if (itemKeys.isEmpty()) return RowEndBoundary.NONE
        if (!hasMore) return RowEndBoundary.CONTENT_END
        val count = itemKeys.size
        if (requestedItemCount == count) return RowEndBoundary.PENDING
        requestedItemCount = count
        loadMore()
        return RowEndBoundary.REQUESTED
    }
}

@Composable
fun rememberRowPaging(
    itemKeys: List<String>,
    hasMore: Boolean,
    onLoadMore: (() -> Unit)?,
    pagingFailed: Boolean = false,
    pagingFailureToken: Long? = null,
): RowPagingState {
    val state = remember { RowPagingState() }
    state.update(
        itemKeys = itemKeys,
        hasMore = hasMore,
        onLoadMore = onLoadMore,
        pagingFailed = pagingFailed,
        pagingFailureToken = pagingFailureToken,
    )
    return state
}
