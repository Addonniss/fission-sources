package dev.infusion.core.domain.anchor

import dev.infusion.core.model.TitleId
import dev.infusion.core.model.metadata.MediaIdentity
import dev.infusion.core.model.metadata.MediaType
import kotlinx.coroutines.flow.StateFlow

interface CanonicalDestinationAnchor {

    suspend fun anchor(destination: CanonicalDestinationIdentity): Boolean

    fun materialized(titleId: TitleId): StateFlow<Boolean>
}

data class CanonicalDestinationIdentity(
    val titleId: TitleId,
    val mediaType: MediaType,
    val identity: MediaIdentity,

    val displayTitle: String?,
    val year: Int?,
)
