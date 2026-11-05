package dev.infusion.core.model

data class ArtworkRendition(

    val maxSidePx: Int? = null,
) {
    init {
        require(maxSidePx == null || maxSidePx > 0) { "maxSidePx must be positive when set" }
    }

    val isBounded: Boolean get() = maxSidePx != null

    val token: String get() = maxSidePx?.let { "max$it" } ?: ""

    companion object {

        val Unbounded: ArtworkRendition = ArtworkRendition(null)
    }
}

object ArtworkRenditionPolicy {

    const val HEADROOM: Int = 2

    const val SIZE_STEP_PX: Int = 128

    const val MIN_CLASS_PX: Int = 256

    const val MAX_CLASS_PX: Int = 4096

    fun forBox(targetWidthPx: Int, targetHeightPx: Int): ArtworkRendition {
        if (targetWidthPx <= 0 || targetHeightPx <= 0) return ArtworkRendition.Unbounded
        val longAxis = maxOf(targetWidthPx, targetHeightPx).toLong()

        val required = longAxis * HEADROOM
        val stepped = ((required + SIZE_STEP_PX - 1) / SIZE_STEP_PX) * SIZE_STEP_PX
        val side = stepped.coerceIn(MIN_CLASS_PX.toLong(), MAX_CLASS_PX.toLong())
        return ArtworkRendition(side.toInt())
    }
}
