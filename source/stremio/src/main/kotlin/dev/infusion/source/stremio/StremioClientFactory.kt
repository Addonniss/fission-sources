package dev.infusion.source.stremio

import dev.infusion.core.network.ConcurrencyBudget
import dev.infusion.core.network.LanTrustRegistry
import dev.infusion.core.network.OkHttpHardenedClient
import dev.infusion.core.network.StrictNetworkPolicy
import dev.infusion.core.network.TrustClass
import dev.infusion.source.stremio.url.ConfiguredAddonUrl

object StremioClientFactory {

    fun create(
        configured: ConfiguredAddonUrl,
        trust: TrustClass,
        registry: LanTrustRegistry,
        originBudget: ConcurrencyBudget = ConcurrencyBudget(8),
        limits: StremioLimits = StremioLimits.DEFAULT,
    ): StremioClient {
        val approvedLanHost: String? = when (trust) {
            TrustClass.LAN_TRUSTED -> {
                require(registry.isApproved(configured.manifestUrl.host)) {
                    "LAN-trusted add-on requires an explicit LanTrustRegistry grant for ${configured.origin}"
                }
                configured.manifestUrl.host
            }
            TrustClass.PUBLIC -> null
        }
        val transport = OkHttpHardenedClient(
            trust = trust,
            approvedLanHost = approvedLanHost,
            networkPolicy = StrictNetworkPolicy,
            budget = originBudget,
        )
        return StremioClient(
            configured = configured,
            trust = trust,
            transport = transport,
            approvedLanHost = approvedLanHost,
            limits = limits,
        )
    }
}
