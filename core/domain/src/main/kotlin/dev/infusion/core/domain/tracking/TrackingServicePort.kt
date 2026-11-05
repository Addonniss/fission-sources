package dev.infusion.core.domain.tracking

import dev.infusion.core.model.sync.TrackingCapabilities

data class ConnectorRegistration(
    val connectorId: String,
    val displayName: String,
    val capabilities: TrackingCapabilities,
)

interface TrackingServicePort {

    fun connectedConnectors(): List<ConnectorRegistration>

    fun capabilitiesOf(connectorId: String): TrackingCapabilities?
}

class MutableTrackingServicePort : TrackingServicePort {

    private val mutationLock = Any()

    @Volatile
    private var registrations: List<ConnectorRegistration> = emptyList()

    override fun connectedConnectors(): List<ConnectorRegistration> = registrations

    override fun capabilitiesOf(connectorId: String): TrackingCapabilities? =
        registrations.firstOrNull { it.connectorId == connectorId }?.capabilities

    fun publish(connected: List<ConnectorRegistration>) {
        val snapshot = java.util.Collections.unmodifiableList(ArrayList(connected))
        synchronized(mutationLock) { registrations = snapshot }
    }

    fun publishSingle(registration: ConnectorRegistration?) {
        publish(if (registration == null) emptyList() else listOf(registration))
    }

    fun publishConnector(connectorId: String, registration: ConnectorRegistration?) {
        require(connectorId.isNotBlank()) { "a connector registration requires its connector id" }
        require(registration == null || registration.connectorId == connectorId) {
            "a connector registration must carry the id it is published under"
        }
        synchronized(mutationLock) {
            val next = registrations.filterNot { it.connectorId == connectorId }.toMutableList()
            if (registration != null) next += registration
            registrations = java.util.Collections.unmodifiableList(next)
        }
    }
}
