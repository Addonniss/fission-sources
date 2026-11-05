package dev.infusion.core.artworkimage

import dev.infusion.core.model.ArtworkPolicy

data class ArtworkLoadRecord(
    val policy: ArtworkPolicy,

    val tier: ArtworkLoadTier,

    val byteFetchMs: Long,

    val decodeMs: Long,

    val totalMs: Long,

    val payloadBytes: Int,
)

enum class ArtworkLoadTier { DECODED_TIER_HIT, PRODUCED, ABSENT }

fun interface ArtworkLoadMetrics {
    fun loadFinished(record: ArtworkLoadRecord)

    companion object {

        val None: ArtworkLoadMetrics = ArtworkLoadMetrics { }
    }
}

class LogcatArtworkLoadMetrics(
    private val tag: String = DEFAULT_TAG,
) : ArtworkLoadMetrics {

    override fun loadFinished(record: ArtworkLoadRecord) {
        val queueMs = (record.totalMs - record.byteFetchMs - record.decodeMs).coerceAtLeast(0)
        android.util.Log.d(
            tag,
            "artwork tier=${record.tier}" +
                " policy=${record.policy}" +
                " bytes=${record.payloadBytes}" +
                " fetchMs=${record.byteFetchMs}" +
                " decodeMs=${record.decodeMs}" +
                " queueMs=$queueMs" +
                " totalMs=${record.totalMs}",
        )
    }

    companion object {

        const val DEFAULT_TAG: String = "INFUSION_PERF"
    }
}
