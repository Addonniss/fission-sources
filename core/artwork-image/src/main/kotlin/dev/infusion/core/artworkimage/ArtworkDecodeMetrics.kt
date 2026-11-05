package dev.infusion.core.artworkimage

import dev.infusion.core.model.ArtworkPolicy

data class ArtworkDecodeRecord(
    val policy: ArtworkPolicy,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val targetWidthPx: Int,
    val targetHeightPx: Int,
    val sampleSize: Int,
    val durationMs: Long,
    val onMainThread: Boolean,
)

fun interface ArtworkDecodeMetrics {
    fun decoded(record: ArtworkDecodeRecord)

    companion object {

        val None: ArtworkDecodeMetrics = ArtworkDecodeMetrics { }
    }
}

class LogcatArtworkDecodeMetrics(
    private val tag: String = DEFAULT_TAG,
) : ArtworkDecodeMetrics {

    override fun decoded(record: ArtworkDecodeRecord) {
        android.util.Log.d(
            tag,
            "decode policy=${record.policy}" +
                " src=${record.sourceWidth}x${record.sourceHeight}" +
                " dst=${record.targetWidthPx}x${record.targetHeightPx}" +
                " sample=${record.sampleSize}" +
                " ms=${record.durationMs}" +
                " main=${record.onMainThread}",
        )
    }

    companion object {

        const val DEFAULT_TAG: String = "INFUSION_PERF"
    }
}
