package dev.infusion.core.model

data class Edition(
    val editionId: EditionId,
    val titleId: TitleId,
    val label: String,
    val evidence: String? = null,
    val runtimeMinutes: Int? = null,
    val aliases: Set<TypedExternalId> = emptySet(),
    val confidence: Double = 0.0,
    val provenance: Provenance? = null,
) {
    init {
        require(label.isNotBlank()) { "label must not be blank" }
        require(confidence in 0.0..1.0) { "confidence must be in 0..1" }
        require(runtimeMinutes == null || runtimeMinutes > 0) { "runtimeMinutes must be positive when known" }
    }
}
