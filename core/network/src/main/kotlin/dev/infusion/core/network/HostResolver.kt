package dev.infusion.core.network

import java.net.InetAddress
import java.net.UnknownHostException

fun interface HostResolver {
    fun resolve(host: String): List<InetAddress>
}

object SystemHostResolver : HostResolver {
    override fun resolve(host: String): List<InetAddress> = try {
        InetAddress.getAllByName(host).toList()
    } catch (_: UnknownHostException) {
        emptyList()
    }
}

class FakeHostResolver(initial: Map<String, List<InetAddress>> = emptyMap()) : HostResolver {
    private val table = LinkedHashMap<String, List<InetAddress>>()
    private val failures = mutableSetOf<String>()

    init {
        table.putAll(initial)
    }

    fun put(host: String, vararg addresses: InetAddress) {
        table[UrlParser.canonicalizeHostname(host)] = addresses.toList()
    }

    fun fail(host: String) {
        failures.add(UrlParser.canonicalizeHostname(host))
    }

    override fun resolve(host: String): List<InetAddress> {
        val key = try {
            UrlParser.canonicalizeHostname(host)
        } catch (e: NetworkPolicyException) {
            return emptyList()
        }
        if (key in failures) return emptyList()
        table[key]?.let { return it }

        return if (IpAddressClassifier.isCanonicalIpv4Literal(key) || IpAddressClassifier.isValidIpv6Literal(key)) {
            try {
                InetAddress.getAllByName(key).toList()
            } catch (_: UnknownHostException) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }
}
