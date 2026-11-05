package dev.infusion.core.domain.catalog

data class ShelfStageRecord(
    val phase: Phase,
    val ordinal: Int = -1,
    val runtimes: Int = 0,
    val catalogs: Int = 0,

    val planned: Int = 0,
    val resolveMs: Long = 0,
    val fetchMs: Long = 0,
    val projectMs: Long = 0,
    val buildMs: Long = 0,
    val streamMs: Long = 0,
    val fetched: Int = 0,
    val admitted: Int = 0,

    val matchMs: Long = 0,
    val availabilityMs: Long = 0,
    val outcome: String = "none",
) {
    enum class Phase { RESOLVE, REQUEST, SHELF }
}

fun interface ShelfStageMetrics {
    fun measured(record: ShelfStageRecord)

    companion object {

        val None: ShelfStageMetrics = ShelfStageMetrics { }
    }
}
