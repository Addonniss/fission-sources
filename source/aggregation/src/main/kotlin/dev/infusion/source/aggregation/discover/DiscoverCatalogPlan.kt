package dev.infusion.source.aggregation.discover

sealed interface DiscoverCatalogPlan {

    fun executes(shelfKey: String): Boolean

    data object Declared : DiscoverCatalogPlan {
        override fun executes(shelfKey: String): Boolean = true
    }

    data class Keys(private val shelfKeys: Set<String>) : DiscoverCatalogPlan {
        constructor(vararg shelfKeys: String) : this(shelfKeys.toSet())

        override fun executes(shelfKey: String): Boolean = shelfKey in shelfKeys
    }
}
