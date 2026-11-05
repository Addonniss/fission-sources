package dev.infusion.core.model

import java.time.Instant

data class ServerItem(
    val serverItemId: String,
    val sourceId: SourceId,
    val nativeId: String,
    val nativeParentId: String? = null,
    val nativeType: String,
    val libraryId: String? = null,
    val revision: String? = null,
    val titleId: TitleId? = null,
    val editionId: EditionId? = null,
    val displayTitle: String? = null,
    val productionYear: Int? = null,
    val payloadRef: PayloadRef? = null,
    val createdAt: Instant = Instant.now(),
) {
    init {
        require(serverItemId.isNotBlank()) { "serverItemId must not be blank" }
        require(nativeId.isNotBlank()) { "nativeId must not be blank" }
        require(nativeType.isNotBlank()) { "nativeType must not be blank" }
        require(productionYear == null || productionYear > 0) { "productionYear must be positive when known" }
    }
}

data class ServerMediaVersion(
    val versionId: String,
    val serverItemId: String,
    val nativeVersionId: String? = null,
    val container: String? = null,
    val facts: CandidateFacts = CandidateFacts(),
    val pathClass: String? = null,
    val decisionInputs: String? = null,
    val createdAt: Instant = Instant.now(),
) {
    init {
        require(versionId.isNotBlank()) { "versionId must not be blank" }
        require(serverItemId.isNotBlank()) { "serverItemId must not be blank" }
    }
}
