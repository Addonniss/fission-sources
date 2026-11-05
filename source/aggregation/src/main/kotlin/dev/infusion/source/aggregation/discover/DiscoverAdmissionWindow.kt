package dev.infusion.source.aggregation.discover

object DiscoverAdmissionWindow {

    const val ITEMS = 25

    fun admit(remaining: Int): Int = remaining.coerceIn(0, ITEMS)
}
