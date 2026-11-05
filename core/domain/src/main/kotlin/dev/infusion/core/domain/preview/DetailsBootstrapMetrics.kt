package dev.infusion.core.domain.preview

data class DetailsBootstrapRecord(
    val titleId: String,
    val hintMs: Long,
    val fullMs: Long?,
)

fun interface DetailsBootstrapMetrics {
    fun measured(record: DetailsBootstrapRecord)

    companion object {

        val None: DetailsBootstrapMetrics = DetailsBootstrapMetrics { }
    }
}
