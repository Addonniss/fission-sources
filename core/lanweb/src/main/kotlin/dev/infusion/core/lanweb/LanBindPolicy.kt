package dev.infusion.core.lanweb

import dev.infusion.core.network.IpAddressClass
import dev.infusion.core.network.IpAddressClassifier

object LanBindPolicy {

    fun isLanLocalLiteral(address: String): Boolean {
        val classified = IpAddressClassifier.classifyLiteral(address.trim()) ?: return false
        return classified == IpAddressClass.LOOPBACK ||
            classified == IpAddressClass.PRIVATE ||
            classified == IpAddressClass.LINK_LOCAL
    }

    fun requireLanLocalLiteral(address: String, label: String = "bindAddress") {
        require(isLanLocalLiteral(address)) {
            "$label must be a LAN-local literal (loopback, private, or link-local) — " +
                "never a wildcard like 0.0.0.0 (exposes the listener on every interface) or a hostname"
        }
    }
}
