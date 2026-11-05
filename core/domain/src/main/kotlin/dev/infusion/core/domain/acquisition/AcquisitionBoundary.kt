package dev.infusion.core.domain.acquisition

enum class AcquisitionService(val id: String) {
    RADARR("radarr"),
    SONARR("sonarr"),
}

data class AcquisitionRootFolder(val path: String) {
    init {
        require(path.isNotBlank()) { "root folder path must not be blank" }
    }
}

data class AcquisitionQualityProfile(val id: Int, val name: String) {
    init {
        require(id > 0) { "quality profile id must be positive" }
        require(name.isNotBlank()) { "quality profile name must not be blank" }
    }
}

data class AcquisitionConnection(
    val serviceId: String,
    val url: String,
    val rootFolderPath: String,
    val qualityProfileId: Int,
    val qualityProfileLabel: String,
    val serverVersion: String?,
) {
    init {
        require(serviceId.isNotBlank()) { "serviceId must not be blank" }
        require(url.isNotBlank()) { "url must not be blank" }
        require(rootFolderPath.isNotBlank()) { "rootFolderPath must not be blank" }
        require(qualityProfileId > 0) { "qualityProfileId must be positive" }
        require(qualityProfileLabel.isNotBlank()) { "qualityProfileLabel must not be blank" }
    }
}

data class StoredAcquisitionConnection(
    val connection: AcquisitionConnection,
    val secretReferenceId: String,
) {
    init {
        require(secretReferenceId.isNotBlank()) { "secret reference id must not be blank" }
    }
}

enum class AcquisitionFailureKind {

    UNAUTHORIZED,

    FORBIDDEN,

    NOT_FOUND,

    RATE_LIMITED,

    BAD_REQUEST,

    SERVER_ERROR,

    TIMEOUT,

    NETWORK,

    DNS,

    CLEARTEXT_BLOCKED,

    POLICY,

    MALFORMED,

    EMPTY,

    UNKNOWN,
}

sealed interface AcquisitionTestOutcome {
    data class Ok(
        val serverVersion: String?,
        val roots: List<AcquisitionRootFolder>,
        val profiles: List<AcquisitionQualityProfile>,
    ) : AcquisitionTestOutcome

    data class Failed(
        val kind: AcquisitionFailureKind,
        val status: Int?,
        val message: String,
    ) : AcquisitionTestOutcome
}
