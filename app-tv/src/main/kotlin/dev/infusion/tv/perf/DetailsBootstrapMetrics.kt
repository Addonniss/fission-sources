package dev.infusion.tv.perf

import dev.infusion.core.domain.preview.DetailsBootstrapMetrics
import dev.infusion.core.domain.preview.DetailsBootstrapRecord

class LogcatDetailsBootstrapMetrics(
    private val tag: String = DEFAULT_TAG,
) : DetailsBootstrapMetrics {

    override fun measured(record: DetailsBootstrapRecord) {
        android.util.Log.d(
            tag,
            "details details_hint_ms=${record.hintMs}" +
                " details_full_ms=${record.fullMs ?: -1}" +
                " title=${record.titleId}",
        )
    }

    companion object {

        const val DEFAULT_TAG: String = "INFUSION_PERF"
    }
}
