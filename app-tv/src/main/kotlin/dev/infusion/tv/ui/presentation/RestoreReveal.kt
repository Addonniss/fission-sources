package dev.infusion.tv.ui.presentation

internal fun restoreRevealTarget(
    rowTopPx: Int,
    rowHeightPx: Int,
    viewportTopPx: Int,
    viewportHeightPx: Int,
    scrollPx: Int,
    maxScrollPx: Int,
): Int? {
    if (rowHeightPx <= 0 || viewportHeightPx <= 0) return null
    val viewportBottomPx = viewportTopPx + viewportHeightPx

    val contentTopPx = rowTopPx - viewportTopPx + scrollPx
    val target = when {

        rowTopPx >= viewportTopPx && rowTopPx + rowHeightPx <= viewportBottomPx -> return null

        rowTopPx < viewportTopPx -> contentTopPx

        else -> contentTopPx + rowHeightPx - viewportHeightPx
    }
    return target.coerceIn(0, maxScrollPx.coerceAtLeast(0))
}
