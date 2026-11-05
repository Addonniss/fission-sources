package dev.infusion.core.artworkimage

import androidx.compose.ui.unit.IntSize
import dev.infusion.core.model.ArtworkLocator
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.ArtworkPresentationRole

data class ArtworkRequest(
    val locator: String,
    val policy: ArtworkPolicy,
    val targetWidthPx: Int,
    val targetHeightPx: Int,

    val retainsPreviousSuccess: Boolean = true,

    val presentationRole: ArtworkPresentationRole = ArtworkPresentationRole.BACKGROUND,
) {
    init {
        require(locator.isNotEmpty()) { "locator must not be empty" }
    }

    val targetSize: IntSize get() = IntSize(targetWidthPx, targetHeightPx)

    companion object {

        fun of(
            locator: String,
            policy: ArtworkPolicy,
            targetSize: IntSize,
            presentationRole: ArtworkPresentationRole = ArtworkPresentationRole.BACKGROUND,
        ): ArtworkRequest = ArtworkRequest(
            locator = locator,
            policy = policy,
            targetWidthPx = targetSize.width,
            targetHeightPx = targetSize.height,
            presentationRole = presentationRole,
        )
    }
}
