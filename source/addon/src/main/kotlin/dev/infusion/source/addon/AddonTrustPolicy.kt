package dev.infusion.source.addon

import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.IpAddressClass
import dev.infusion.core.network.IpAddressClassifier
import dev.infusion.core.network.TrustClass

object AddonTrustPolicy {

    fun classify(manifestUrl: CanonicalUrl, lanApproved: Boolean = false): TrustClass {
        if (manifestUrl.scheme == "http") return TrustClass.LAN_TRUSTED
        val ipClass = IpAddressClassifier.classifyLiteral(manifestUrl.host)
        return when (ipClass) {

            null -> if (lanApproved) TrustClass.LAN_TRUSTED else TrustClass.PUBLIC

            IpAddressClass.PUBLIC -> TrustClass.PUBLIC

            else -> TrustClass.LAN_TRUSTED
        }
    }
}
