package dev.infusion.core.domain.acquisition

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface AcquisitionConnectionStore {

    fun connection(serviceId: String): StoredAcquisitionConnection?

    fun apiKey(serviceId: String): String?

    fun save(serviceId: String, connection: AcquisitionConnection, apiKey: String)

    fun clear(serviceId: String)

    fun clearProfile(profileId: String)

    fun clearActiveProfile()

    fun connectionChanges(): Flow<Unit> = emptyFlow()
}
