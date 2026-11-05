package dev.infusion.core.domain.catalog

data class ShelfDeliveryRecord(
    val journey: String,
    val generation: Int,
    val phase: Phase,
    val rows: Int,
    val firstRowMs: Long,
    val firstContentMs: Long,
    val allRowsMs: Long?,
) {
    enum class Phase { FIRST_ROW, COMPLETE }
}

fun interface ShelfDeliveryMetrics {
    fun measured(record: ShelfDeliveryRecord)

    companion object {

        val None: ShelfDeliveryMetrics = ShelfDeliveryMetrics { }
    }
}
