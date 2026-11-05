package dev.infusion.tv.perf

import dev.infusion.core.domain.catalog.ShelfDeliveryMetrics
import dev.infusion.core.domain.catalog.ShelfDeliveryRecord

class LogcatShelfDeliveryMetrics(
    private val tag: String = DEFAULT_TAG,
) : ShelfDeliveryMetrics {

    override fun measured(record: ShelfDeliveryRecord) {
        android.util.Log.d(
            tag,
            "shelves journey=${record.journey}" +
                " gen=${record.generation}" +
                " phase=${record.phase.name.lowercase()}" +
                " rows=${record.rows}" +
                " first_row_ms=${record.firstRowMs}" +
                " first_content_ms=${record.firstContentMs}" +
                " all_rows_ms=${record.allRowsMs ?: -1}",
        )
    }

    companion object {

        const val DEFAULT_TAG: String = "INFUSION_PERF"
    }
}
