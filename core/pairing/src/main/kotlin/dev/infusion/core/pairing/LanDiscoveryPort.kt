package dev.infusion.core.pairing

interface LanDiscoveryPort {

    fun advertise(endpoint: LanEndpoint)

    fun stopAdvertising(endpoint: LanEndpoint)

    fun discover(timeoutMs: Long): List<LanEndpoint>
}

class FakeLanDiscovery : LanDiscoveryPort {

    private val advertised = LinkedHashMap<LanEndpoint, Unit>()
    private val scripted = LinkedHashMap<LanEndpoint, Unit>()

    fun seed(endpoints: List<LanEndpoint>) {
        for (endpoint in endpoints) scripted[endpoint] = Unit
    }

    override fun advertise(endpoint: LanEndpoint) {
        advertised[endpoint] = Unit
    }

    override fun stopAdvertising(endpoint: LanEndpoint) {
        advertised.remove(endpoint)
    }

    override fun discover(timeoutMs: Long): List<LanEndpoint> =
        (scripted.keys + advertised.keys).toList()

    fun visibleCount(): Int = scripted.size + advertised.size
}
