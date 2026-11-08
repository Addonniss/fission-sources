package dev.infusion.feature.vertical.collections

data class CollectionRowViewport(
    val leadingKey: String,
    val leadingOffsetPx: Int,
)

class CollectionPageRowNavigationMemory {

    private val rememberedItems = HashMap<String, String>()

    private val viewports = HashMap<String, CollectionRowViewport>()

    fun rememberedItem(rowKey: String): String? = rememberedItems[rowKey]

    fun rememberItem(rowKey: String, itemKey: String) {
        rememberedItems[rowKey] = itemKey
    }

    fun viewport(rowKey: String): CollectionRowViewport? = viewports[rowKey]

    fun rememberViewport(rowKey: String, leadingKey: String, leadingOffsetPx: Int) {
        viewports[rowKey] = CollectionRowViewport(leadingKey, leadingOffsetPx)
    }
}
