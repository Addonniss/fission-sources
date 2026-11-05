package dev.infusion.core.model.identity

import dev.infusion.core.model.EditionId
import dev.infusion.core.model.MappingId
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId
import java.time.Instant

enum class MappingStatus {

    PROPOSED,

    ACTIVE,

    SUPERSEDED,
}

enum class MappingOrigin {

    FILE_NAME_PARSE,

    CONNECTOR,

    USER_CORRECTION,

    ALTERNATE_ORDER,
}

data class IdentityMapping(
    val mappingId: MappingId,
    val external: TypedExternalId,
    val canonicalTitleId: TitleId,
    val canonicalEditionId: EditionId? = null,
    val status: MappingStatus = MappingStatus.PROPOSED,
    val confidence: Double = 0.0,
    val origin: MappingOrigin,
    val supersedesMappingId: MappingId? = null,
    val createdAt: Instant = Instant.now(),
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be in 0..1" }
    }

    fun withStatus(status: MappingStatus): IdentityMapping = copy(status = status)

    fun withSupersedes(supersedesMappingId: MappingId?): IdentityMapping =
        copy(supersedesMappingId = supersedesMappingId)
}
