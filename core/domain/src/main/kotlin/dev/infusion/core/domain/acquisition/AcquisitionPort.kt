package dev.infusion.core.domain.acquisition

import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.model.TitleId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

enum class AcquisitionKind { MOVIE, SHOW, SEASON, EPISODE }

enum class AcquisitionOperation { ADD_TITLE, MONITOR, SEARCH }

data class AcquisitionCapabilities(
    val add: Boolean,
    val monitor: Boolean,
    val search: Boolean,
    val kinds: Set<AcquisitionKind>,
)

data class AcquisitionRequest(
    val kind: AcquisitionKind,
    val titleId: TitleId,
    val externalIds: TrackingExternalIds,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val title: String? = null,
    val year: Int? = null,
) {
    init {
        require(!externalIds.isEmpty) {
            "an acquisition request requires at least one proven external identifier"
        }
        require(kind != AcquisitionKind.SEASON || seasonNumber != null) {
            "a SEASON request requires a season number"
        }
        require(kind != AcquisitionKind.EPISODE || (seasonNumber != null && episodeNumber != null)) {
            "an EPISODE request requires season and episode numbers"
        }
    }
}

data class AcquisitionRef(
    val serviceId: String,
    val serviceInstance: String,
    val kind: AcquisitionKind,
    val titleId: String,
    val serverId: Int,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
) {
    init {
        require(serviceId.isNotBlank()) { "serviceId must not be blank" }
        require(serviceInstance.isNotBlank()) { "serviceInstance must not be blank" }
        require(titleId.isNotBlank()) { "titleId must not be blank" }
    }
}

enum class AcquisitionStatus { ACCEPTED, PARTIAL, ALREADY_MANAGED, UNAVAILABLE, FAILED, UNKNOWN, UNSUPPORTED }

data class AcquisitionOutcome(
    val serviceId: String,
    val serviceInstance: String,
    val status: AcquisitionStatus,
    val ref: AcquisitionRef? = null,
    val titleAdded: Boolean = false,
    val alreadyManaged: Boolean = false,
    val monitored: Boolean = false,
    val searchSubmitted: Boolean = false,

    val searchPending: Boolean = false,
    val reason: String? = null,
)

data class AcquisitionObservation(
    val ref: AcquisitionRef,
    val known: Boolean,
    val managed: Boolean,
    val filePresent: Boolean,
    val reason: String? = null,
)

interface AcquisitionPort {

    val serviceId: String

    val serviceInstance: String

    fun capabilities(): AcquisitionCapabilities

    fun connected(): Boolean

    suspend fun request(request: AcquisitionRequest): AcquisitionOutcome

    suspend fun reconcile(
        request: AcquisitionRequest,
        operation: AcquisitionOperation,
        issuedAtMs: Long,
    ): AcquisitionOutcome

    suspend fun observe(refs: List<AcquisitionRef>): List<AcquisitionObservation>
}

enum class AcquisitionRecordStatus { REQUESTED, ACCEPTED, PARTIAL, DOWNLOADED, FAILED, UNKNOWN, UNSUPPORTED }

data class AcquisitionRecord(
    val id: String,
    val serviceId: String,
    val serviceInstance: String,
    val kind: AcquisitionKind,
    val titleId: String,
    val displayTitle: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val status: AcquisitionRecordStatus,

    val titleAdded: Boolean = false,
    val monitored: Boolean = false,
    val searchSubmitted: Boolean = false,

    val searchPending: Boolean = false,

    val filePresent: Boolean = false,

    val importProvenAtMs: Long? = null,
    val serverId: Int? = null,
    val detail: String? = null,
    val updatedAtMs: Long,
) {
    init {
        require(id.isNotBlank()) { "record id must not be blank" }
        require(serviceId.isNotBlank()) { "serviceId must not be blank" }
        require(serviceInstance.isNotBlank()) { "serviceInstance must not be blank" }
        require(titleId.isNotBlank()) { "titleId must not be blank" }
    }
}

interface AcquisitionOperationStore {
    fun read(profileId: String): List<AcquisitionRecord>
    fun write(profileId: String, records: List<AcquisitionRecord>)
    fun clearProfile(profileId: String)

    fun changes(): Flow<Unit> = emptyFlow()
}
