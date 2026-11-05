package dev.infusion.core.model

import java.time.Instant

enum class TrustClass {

    DEVICE_LOCAL,

    PERSONAL_SERVER,

    LAN_NETWORK_SHARE,

    REMOTE_ADDON,

    TRACKING_SERVICE,

    LEGAL_EXTERNAL_LINK,
}

enum class SourceCapability {
    BROWSE,
    SEARCH,
    METADATA,
    STREAM,
    SUBTITLES,
    WATCH_STATE_SYNC,
}

enum class SourceHealth {
    HEALTHY,
    DEGRADED,
    UNREACHABLE,
    UNAUTHORIZED,
    UNKNOWN,
}

data class DisclosureRecord(
    val disclosedAt: Instant,
    val dataSentSummary: String,
    val networkReachSummary: String,
    val secretUseSummary: String,
    val policyVersion: Int = 1,
) {
    init {
        require(dataSentSummary.isNotBlank()) { "dataSentSummary must not be blank" }
        require(networkReachSummary.isNotBlank()) { "networkReachSummary must not be blank" }
        require(secretUseSummary.isNotBlank()) { "secretUseSummary must not be blank" }
    }
}

data class Source(
    val sourceId: SourceId,
    val type: SourceType,
    val trustClass: TrustClass,
    val displayName: String,
    val endpoint: String? = null,
    val enabled: Boolean = true,
    val credentialRef: String? = null,
    val capabilities: Set<SourceCapability> = emptySet(),
    val health: SourceHealth = SourceHealth.UNKNOWN,
    val disclosure: DisclosureRecord? = null,
    val createdAt: Instant = Instant.now(),
) {
    init {
        require(displayName.isNotBlank()) { "displayName must not be blank" }
    }
}
