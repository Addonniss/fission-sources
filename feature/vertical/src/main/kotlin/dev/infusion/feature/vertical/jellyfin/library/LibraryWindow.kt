package dev.infusion.feature.vertical.jellyfin.library

import dev.infusion.feature.vertical.jellyfin.JellyfinTabRowItem
import kotlin.math.abs
import kotlin.math.ceil

data class LibraryPage(
    val pageIndex: Int,
    val startIndex: Int,
    val items: List<JellyfinTabRowItem>,
) {

    val endIndex: Int get() = startIndex + items.size
}

data class LibraryWindow(
    val key: LibraryQueryKey,
    val generation: Long,
    val pageSize: Int = LibraryCapabilities.PAGE_SIZE,
    val retainedPageLimit: Int = LibraryCapabilities.RETAINED_PAGES,
    private val pages: Map<Int, LibraryPage> = emptyMap(),

    val total: Int? = null,
) {

    val pageIndexes: Set<Int> get() = pages.keys

    fun page(pageIndex: Int): LibraryPage? = pages[pageIndex]

    fun isEmpty(): Boolean = pages.isEmpty()

    fun isLoaded(index: Int): Boolean = pageContaining(index) != null

    fun pageContaining(index: Int): LibraryPage? {
        if (index < 0) return null
        val page = pages[index / pageSize] ?: return null

        return page.takeIf { index >= it.startIndex && index < it.endIndex }
    }

    val contiguousLoadedEnd: Int
        get() {
            var end = 0
            var index = 0
            while (true) {
                val page = pages[index] ?: break
                if (page.startIndex != index * pageSize) break
                end = page.endIndex
                if (page.items.isEmpty()) break
                index++
            }
            return end
        }

    val minLoadedStart: Int get() = pages.values.minOfOrNull { it.startIndex } ?: 0

    val maxLoadedEnd: Int get() = pages.values.maxOfOrNull { it.endIndex } ?: 0

    fun loadedRanges(): List<IntRange> =
        pages.values.sortedBy { it.startIndex }
            .filter { it.items.isNotEmpty() }
            .map { it.startIndex until it.endIndex }
            .mergeAdjacent()

    val hasMore: Boolean?
        get() {
            val known = total ?: return null
            return contiguousLoadedEnd < known
        }

    val nextForwardStart: Int get() = contiguousLoadedEnd

    fun withPage(
        page: LibraryPage,
        focusedServerIndex: Int? = null,
        firstVisibleServerIndex: Int? = null,
        total: Int? = this.total,
    ): LibraryWindow {
        val merged = pages.toMutableMap()
        merged[page.pageIndex] = page
        var evicted = merged
        if (merged.size > retainedPageLimit) {
            val protectedIndexes = setOfNotNull(
                focusedServerIndex?.div(pageSize),
                firstVisibleServerIndex?.div(pageSize),
            )
            while (evicted.size > retainedPageLimit) {
                val victim = evicted.values
                    .filter { it.pageIndex !in protectedIndexes }
                    .maxByOrNull { distanceFromFocus(it, focusedServerIndex, firstVisibleServerIndex) }
                    ?: break
                evicted = evicted.toMutableMap().apply { remove(victim.pageIndex) }
            }
        }
        return copy(pages = evicted, total = total ?: this.total)
    }

    fun withoutPage(pageIndex: Int): LibraryWindow = copy(pages = pages - pageIndex)

    fun cleared(): LibraryWindow = copy(pages = emptyMap(), total = null)

    private fun distanceFromFocus(
        page: LibraryPage,
        focused: Int?,
        firstVisible: Int?,
    ): Int {
        val anchors = listOfNotNull(focused, firstVisible)
        if (anchors.isEmpty()) return page.startIndex
        return anchors.minOf { anchor ->
            when {
                anchor < page.startIndex -> page.startIndex - anchor
                anchor >= page.endIndex -> anchor - page.endIndex + 1
                else -> 0
            }
        }
    }

    companion object {
        fun empty(key: LibraryQueryKey, generation: Long): LibraryWindow =
            LibraryWindow(key = key, generation = generation)
    }
}

private fun List<IntRange>.mergeAdjacent(): List<IntRange> {
    if (isEmpty()) return emptyList()
    val out = mutableListOf(first())
    for (range in drop(1)) {
        val last = out.last()
        if (range.first <= last.last + 1) {
            out[out.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
            out += range
        }
    }
    return out
}

data class LibraryGridCell(
    val item: JellyfinTabRowItem?,
    val absoluteServerIndex: Int?,
    val isPlaceholder: Boolean = false,
) {
    val isFocusable: Boolean get() = item != null
}

sealed interface LibraryFooter {

    data object Loading : LibraryFooter

    data class Retry(val message: String) : LibraryFooter

    data object Continue : LibraryFooter
}

data class LibraryProjection(
    val cells: List<LibraryGridCell>,

    val serverTotal: Int?,

    val visibleLoadedCount: Int,

    val hiddenLoadedCount: Int,

    val windowComplete: Boolean,

    val hasMore: Boolean?,

    val hidingEnabled: Boolean = false,
) {

    val focusableCount: Int get() = cells.count { it.isFocusable }

    val countLabel: String?
        get() {
            val total = serverTotal ?: return null
            if (!hidingEnabled) return total.toString()
            if (windowComplete) return (total - hiddenLoadedCount).coerceAtLeast(0).toString()
            return null
        }
}

object LibraryProjector {

    fun project(
        window: LibraryWindow,
        hidingEnabled: Boolean = false,
        isHidden: (JellyfinTabRowItem) -> Boolean = { false },
    ): LibraryProjection {
        val cells = mutableListOf<LibraryGridCell>()
        var hidden = 0
        var visibleLoaded = 0
        val ranges = window.loadedRanges()
        val orderedPages = window.pageIndexes.mapNotNull { window.page(it) }
            .sortedBy { it.startIndex }
            .filter { it.items.isNotEmpty() }

        if (orderedPages.isEmpty()) {
            val total = window.total ?: 0
            repeat(total) {
                cells += LibraryGridCell(item = null, absoluteServerIndex = it, isPlaceholder = true)
            }
            return LibraryProjection(
                cells = cells,
                serverTotal = window.total,
                visibleLoadedCount = 0,
                hiddenLoadedCount = 0,
                windowComplete = window.total == 0,
                hasMore = window.hasMore,
                hidingEnabled = hidingEnabled,
            )
        }

        var cursor = 0
        for (range in ranges) {
            if (range.first > cursor) {
                for (absolute in cursor until range.first) {
                    if (window.total != null && absolute >= window.total) break
                    cells += LibraryGridCell(item = null, absoluteServerIndex = absolute, isPlaceholder = true)
                }
            }
            val pageRange = orderedPages.filter { it.startIndex >= range.first && it.startIndex <= range.last }
            for (page in pageRange) {
                page.items.forEachIndexed { offset, item ->
                    val absolute = page.startIndex + offset
                    if (isHidden(item)) {
                        hidden++
                    } else {
                        visibleLoaded++
                        cells += LibraryGridCell(item = item, absoluteServerIndex = absolute)
                    }
                }
            }
            cursor = maxOf(cursor, range.last + 1)
        }
        if (window.total != null) {
            for (absolute in cursor until window.total) {
                cells += LibraryGridCell(item = null, absoluteServerIndex = absolute, isPlaceholder = true)
            }
        }

        val contiguousEnd = window.contiguousLoadedEnd
        val windowComplete = window.total != null && contiguousEnd >= window.total
        return LibraryProjection(
            cells = cells,
            serverTotal = window.total,
            visibleLoadedCount = visibleLoaded,
            hiddenLoadedCount = hidden,
            windowComplete = windowComplete,
            hasMore = window.hasMore,
            hidingEnabled = hidingEnabled,
        )

    }

    fun visibleIndexOfAbsolute(projection: LibraryProjection, absoluteServerIndex: Int): Int? =
        projection.cells.indexOfFirst { it.absoluteServerIndex == absoluteServerIndex }.takeIf { it >= 0 }

    fun absoluteOfVisible(projection: LibraryProjection, visibleIndex: Int): Int? =
        projection.cells.getOrNull(visibleIndex)?.absoluteServerIndex

    fun rowCount(cellCount: Int, columns: Int): Int =
        if (columns <= 0) 0 else ceil(cellCount.toDouble() / columns).toInt()

    fun distance(a: Int, b: Int): Int = abs(a - b)
}
