package dev.infusion.tv.perf

import dev.infusion.core.domain.catalog.ShelfStageMetrics
import dev.infusion.core.domain.catalog.ShelfStageRecord

class LogcatShelfStageMetrics(
    private val tag: String = DEFAULT_TAG,
) : ShelfStageMetrics {

    override fun measured(record: ShelfStageRecord) {
        when (record.phase) {
            ShelfStageRecord.Phase.RESOLVE ->
                android.util.Log.d(
                    tag,
                    "shelfstage phase=resolve" +
                        " runtimes=${record.runtimes}" +
                        " catalogs=${record.catalogs}" +
                        " planned=${record.planned}" +
                        " resolveMs=${record.resolveMs}",
                )

            ShelfStageRecord.Phase.REQUEST ->
                android.util.Log.d(
                    tag,
                    "shelfstage phase=request" +
                        " ordinal=${record.ordinal}" +
                        " streamMs=${record.streamMs}",
                )

            ShelfStageRecord.Phase.SHELF ->
                android.util.Log.d(
                    tag,
                    "shelfstage phase=shelf" +
                        " ordinal=${record.ordinal}" +
                        " fetchMs=${record.fetchMs}" +
                        " projectMs=${record.projectMs}" +
                        " matchMs=${record.matchMs}" +
                        " availabilityMs=${record.availabilityMs}" +
                        " buildMs=${record.buildMs}" +
                        " streamMs=${record.streamMs}" +
                        " fetched=${record.fetched}" +
                        " admitted=${record.admitted}" +
                        " outcome=${record.outcome}",
                )
        }
    }

    companion object {

        const val DEFAULT_TAG: String = "INFUSION_PERF"
    }
}
