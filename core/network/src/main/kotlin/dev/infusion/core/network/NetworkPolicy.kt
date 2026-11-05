package dev.infusion.core.network

import java.net.InetAddress

class NetworkPolicyDecision private constructor(
    val allowed: Boolean,
    val reason: DiagnosticReason?,
) {
    companion object {
        val ALLOW = NetworkPolicyDecision(allowed = true, reason = null)
        fun deny(reason: DiagnosticReason) = NetworkPolicyDecision(allowed = false, reason = reason)
    }
}

fun interface NetworkPolicy {
    fun decide(
        host: String,
        resolved: List<InetAddress>,
        scheme: String,
        trust: TrustClass,
        approvedLanHost: String?,
    ): NetworkPolicyDecision
}

object StrictNetworkPolicy : NetworkPolicy {

    override fun decide(
        host: String,
        resolved: List<InetAddress>,
        scheme: String,
        trust: TrustClass,
        approvedLanHost: String?,
    ): NetworkPolicyDecision {
        if (scheme != "https" && scheme != "http") {
            return NetworkPolicyDecision.deny(DiagnosticReason.SCHEME_DENIED)
        }
        val hostApproved = trust == TrustClass.LAN_TRUSTED &&
            approvedLanHost != null &&
            approvedLanHost.equals(host, ignoreCase = true)
        if (scheme == "http" && (trust != TrustClass.LAN_TRUSTED || !hostApproved)) {
            return NetworkPolicyDecision.deny(DiagnosticReason.SCHEME_DENIED)
        }

        if (resolved.isEmpty()) {
            return NetworkPolicyDecision.deny(DiagnosticReason.DNS_FAILED)
        }
        for (address in resolved) {
            when (IpAddressClassifier.classify(address)) {
                IpAddressClass.PUBLIC -> Unit
                IpAddressClass.PRIVATE -> if (!hostApproved) {
                    return NetworkPolicyDecision.deny(DiagnosticReason.PRIVATE_DESTINATION)
                }
                IpAddressClass.LOOPBACK -> if (!allowTestLoopback) {
                    return NetworkPolicyDecision.deny(DiagnosticReason.PRIVATE_DESTINATION)
                }
                else -> return NetworkPolicyDecision.deny(DiagnosticReason.PRIVATE_DESTINATION)
            }
        }
        return NetworkPolicyDecision.ALLOW
    }

    @Volatile
    var allowTestLoopback: Boolean = false
        private set

    fun <T> withTestLoopback(block: () -> T): T {
        val previous = allowTestLoopback
        allowTestLoopback = true
        try {
            return block()
        } finally {
            allowTestLoopback = previous
        }
    }
}
