package dev.infusion.feature.vertical.browse

import dev.infusion.source.aggregation.discover.DiscoverItem
import dev.infusion.source.aggregation.discover.DiscoverShelf
import dev.infusion.source.aggregation.discover.DiscoverShelfDeclaration
import dev.infusion.source.aggregation.discover.DiscoverShelfEmission
import dev.infusion.source.aggregation.discover.ShelfState

internal class ProgressiveShelfAccumulator {

    private data class OrderedShelf(val order: Int, val shelf: DiscoverShelf)

    private val current = LinkedHashMap<String, OrderedShelf>()

    private var previous: List<OrderedShelf> = emptyList()

    fun begin() {
        current.clear()
    }

    fun accept(emission: DiscoverShelfEmission) {
        current[emission.shelf.key] = OrderedShelf(emission.order, emission.shelf)
    }

    fun acceptRefreshed(emission: DiscoverShelfEmission) {
        val published = current[emission.shelf.key]?.shelf
            ?: previous.firstOrNull { row -> row.shelf.key == emission.shelf.key }?.shelf
        current[emission.shelf.key] =
            OrderedShelf(emission.order, emission.shelf.retainingLoadedTailOf(published))
    }

    fun append(shelfKey: String, items: List<DiscoverItem>, hasMore: Boolean): Boolean {
        val inCurrent = current[shelfKey]
        if (inCurrent != null) {
            val updated = inCurrent.shelf.appendedPage(items, hasMore) ?: return false
            current[shelfKey] = inCurrent.copy(shelf = updated)
            return true
        }
        val index = previous.indexOfFirst { it.shelf.key == shelfKey }
        if (index < 0) return false
        val row = previous[index]
        val updated = row.shelf.appendedPage(items, hasMore) ?: return false
        previous = previous.toMutableList().also { it[index] = row.copy(shelf = updated) }
        return true
    }

    fun replace(shelf: DiscoverShelf): Boolean {
        val inCurrent = current[shelf.key]
        if (inCurrent != null) {
            current[shelf.key] = inCurrent.copy(shelf = shelf)
            return true
        }
        val index = previous.indexOfFirst { it.shelf.key == shelf.key }
        if (index < 0) return false
        previous = previous.toMutableList().also { rows -> rows[index] = rows[index].copy(shelf = shelf) }
        return true
    }

    private fun DiscoverShelf.appendedPage(items: List<DiscoverItem>, pageHasMore: Boolean): DiscoverShelf? {
        val loaded = state as? ShelfState.Loaded ?: return null
        val appended = appendShelfPage(loaded.items, items, pageHasMore)
        return copy(
            state = loaded.copy(
                items = appended.items,
                hasMore = appended.hasMore,
            ),
        )
    }

    fun snapshot(): List<DiscoverShelf> =
        (previous.associateBy { it.shelf.key } + current)
            .values
            .sortedWith(ORDER)
            .map { it.shelf }

    fun complete(): List<DiscoverShelf> {
        previous = current.values.sortedWith(ORDER).toList()
        current.clear()
        return previous.map { it.shelf }
    }

    fun reconcile(plan: List<DiscoverShelfDeclaration>): List<String> {
        val published = LinkedHashMap<String, DiscoverShelf>()
        previous.forEach { row -> published[row.shelf.key] = row.shelf }
        current.forEach { (key, row) -> published[key] = row.shelf }
        val retained = ArrayList<OrderedShelf>(plan.size)
        val missing = ArrayList<String>()
        plan.forEachIndexed { index, declaration ->
            val shelf = published[declaration.key]
            if (shelf == null) {
                missing += declaration.key
            } else {

                retained += OrderedShelf(
                    index,
                    if (shelf.title == declaration.title && shelf.sourceLabel == declaration.sourceLabel) {
                        shelf
                    } else {
                        shelf.copy(title = declaration.title, sourceLabel = declaration.sourceLabel)
                    },
                )
            }
        }

        previous = retained
        current.clear()
        return missing
    }

    fun publish(order: Int, shelf: DiscoverShelf) {
        previous = (
            previous.filterNot { row -> row.shelf.key == shelf.key } +
                OrderedShelf(order, shelf)
            ).sortedWith(ORDER)
    }

    private companion object {

        val ORDER: Comparator<OrderedShelf> =
            compareBy({ it.order }, { it.shelf.key })
    }
}

internal fun DiscoverShelf.hasVisibleContent(): Boolean =
    (state as? ShelfState.Loaded)?.items?.isNotEmpty() == true

internal data class AppendedShelfPage(
    val items: List<DiscoverItem>,
    val hasMore: Boolean,
)

internal fun appendShelfPage(
    loaded: List<DiscoverItem>,
    page: List<DiscoverItem>,
    pageHasMore: Boolean,
): AppendedShelfPage {
    val knownKeys = loaded.mapTo(mutableSetOf()) { it.key }
    val newItems = page.filter { knownKeys.add(it.key) }
    return AppendedShelfPage(
        items = loaded + newItems,
        hasMore = pageHasMore && newItems.isNotEmpty(),
    )
}

internal fun DiscoverShelf.retainingLoadedTailOf(published: DiscoverShelf?): DiscoverShelf {
    val fresh = state as? ShelfState.Loaded ?: return this
    val held = published?.state as? ShelfState.Loaded ?: return this
    if (fresh.items.isEmpty() || held.items.size <= fresh.items.size) return this
    if (fresh.items.indices.any { index -> fresh.items[index].key != held.items[index].key }) return this
    return copy(
        state = fresh.copy(
            items = fresh.items + held.items.subList(fresh.items.size, held.items.size),
            hasMore = held.hasMore,
        ),
    )
}
