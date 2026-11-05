package dev.infusion.core.model

import java.time.Instant

data class Provenance(
    val sourceId: SourceId,
    val protocolVersion: String,
    val retrievedAt: Instant,
    val normalizedBy: String,
    val payloadRef: PayloadRef? = null,
) {
    init {
        require(protocolVersion.isNotBlank()) { "protocolVersion must not be blank" }
        require(normalizedBy.isNotBlank()) { "normalizedBy must not be blank" }
    }
}

data class PayloadRef(
    val payloadType: String,
    val payloadHash: String,
    val payloadVersion: Int = 1,
) {
    init {
        require(payloadType.isNotBlank()) { "payloadType must not be blank" }
        require(payloadHash.isNotBlank()) { "payloadHash must not be blank" }
    }
}
