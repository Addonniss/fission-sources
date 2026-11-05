package dev.infusion.core.model

import java.time.Instant

enum class AvailabilityKind {
    LOCAL,
    SERVER,
    ADDON,
    LEGAL_LINK,
}

data class Availability(
    val availabilityId: AvailabilityId,
    val titleId: TitleId,
    val editionId: EditionId? = null,
    val sourceId: SourceId,
    val nativeResourceId: String,
    val kind: AvailabilityKind,
    val regionHint: String? = null,
    val expiresAt: Instant? = null,
    val observedAt: Instant = Instant.now(),
    val provenance: Provenance,
) {
    init {
        require(nativeResourceId.isNotBlank()) { "nativeResourceId must not be blank" }
    }
}
