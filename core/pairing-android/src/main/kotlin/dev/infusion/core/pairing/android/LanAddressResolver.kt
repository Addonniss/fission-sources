package dev.infusion.core.pairing.android

import dev.infusion.core.pairing.LanEndpoint
import java.net.NetworkInterface

object LanAddressResolver {

    fun resolve(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.filter { it.isUp && !it.isLoopback && !it.isVirtual }
            ?.flatMap { it.inetAddresses.toList() }
            ?.mapNotNull { address ->
                val host = address.hostAddress ?: return@mapNotNull null
                if (!host.contains(':')) host else null
            }
            ?.firstOrNull { LanEndpoint.isLanLocalLiteral(it) }
    }.getOrNull()
}
