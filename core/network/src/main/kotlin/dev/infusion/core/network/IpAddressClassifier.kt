package dev.infusion.core.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

enum class IpAddressClass {

    PUBLIC,

    PRIVATE,

    LOOPBACK,

    LINK_LOCAL,

    MULTICAST,

    UNSPECIFIED,

    CGNAT,

    DOCUMENTATION,

    BENCHMARK,

    RESERVED,

    BROADCAST,
}

object IpAddressClassifier {

    private val CANONICAL_IPV4 = Regex(
        "^(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}$",
    )

    fun isCanonicalIpv4Literal(s: String): Boolean = CANONICAL_IPV4.matches(s)

    fun isValidIpv6Literal(s: String): Boolean {
        var literal = s
        if (literal.startsWith("[") && literal.endsWith("]")) literal = literal.substring(1, literal.length - 1)
        if (!literal.contains(':')) return false
        return try {
            InetAddress.getByName(literal)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun classifyLiteral(literal: String): IpAddressClass? {
        val l = literal.trim()
        return when {
            isCanonicalIpv4Literal(l) -> classifyV4(toIpv4Bytes(l))
            isValidIpv6Literal(l) -> classify(InetAddress.getByName(stripBrackets(l)))
            else -> null
        }
    }

    fun classify(address: InetAddress): IpAddressClass {
        if (address is Inet4Address) return classifyV4(address.address)
        val b = address.address
        require(b.size == 16) { "unexpected address length ${b.size}" }

        val first = b[0].toInt() and 0xff
        val second = b[1].toInt() and 0xff
        val low12Zero = b.copyOfRange(0, 12).all { it == 0.toByte() }

        if (b.all { it == 0.toByte() }) return IpAddressClass.UNSPECIFIED
        if (low12Zero) {

            if (b[10] == 0xff.toByte() && b[11] == 0xff.toByte()) {
                return classifyV4(byteArrayOf(b[12], b[13], b[14], b[15]))
            }
            if (b[10] == 0.toByte() && b[11] == 0.toByte()) {
                if (b[12] == 0.toByte() && b[13] == 0.toByte() && b[14] == 0.toByte() && b[15] == 1.toByte()) {
                    return IpAddressClass.LOOPBACK
                }

                if (b.copyOfRange(12, 16).any { it != 0.toByte() }) {
                    return classifyV4(byteArrayOf(b[12], b[13], b[14], b[15]))
                }
            }
        }
        if ((first and 0xfe) == 0xfc) return IpAddressClass.PRIVATE
        if (first == 0xfe && (second and 0xc0) == 0x80) return IpAddressClass.LINK_LOCAL
        if (first == 0xfe && (second and 0xc0) == 0xc0) return IpAddressClass.RESERVED
        if (first == 0xff) return IpAddressClass.MULTICAST
        if (first == 0x20 && second == 0x01 && (b[2].toInt() and 0xff) == 0x0d && (b[3].toInt() and 0xff) == 0xb8) {
            return IpAddressClass.DOCUMENTATION
        }
        if (first == 0x20 && second == 0x01 && (b[2].toInt() and 0xf0) == 0x10) {
            return IpAddressClass.RESERVED
        }
        return IpAddressClass.PUBLIC
    }

    private fun classifyV4(b: ByteArray): IpAddressClass {
        val a0 = b[0].toInt() and 0xff
        val a1 = b[1].toInt() and 0xff
        val a2 = b[2].toInt() and 0xff
        return when {
            a0 == 0 -> IpAddressClass.UNSPECIFIED
            a0 == 10 -> IpAddressClass.PRIVATE
            a0 == 100 && a1 in 64..127 -> IpAddressClass.CGNAT
            a0 == 127 -> IpAddressClass.LOOPBACK
            a0 == 169 && a1 == 254 -> IpAddressClass.LINK_LOCAL
            a0 == 172 && a1 in 16..31 -> IpAddressClass.PRIVATE
            a0 == 192 && a1 == 0 && a2 == 0 -> IpAddressClass.RESERVED
            a0 == 192 && a1 == 0 && a2 == 2 -> IpAddressClass.DOCUMENTATION
            a0 == 192 && a1 == 168 -> IpAddressClass.PRIVATE
            a0 == 192 && a1 == 88 && a2 == 99 -> IpAddressClass.RESERVED
            a0 == 198 && a1 == 51 && a2 == 100 -> IpAddressClass.DOCUMENTATION
            a0 == 198 && a1 in 18..19 -> IpAddressClass.BENCHMARK
            a0 == 203 && a1 == 0 && a2 == 113 -> IpAddressClass.DOCUMENTATION
            a0 in 224..239 -> IpAddressClass.MULTICAST
            a0 in 240..254 -> IpAddressClass.RESERVED
            a0 == 255 -> IpAddressClass.BROADCAST
            else -> IpAddressClass.PUBLIC
        }
    }

    private fun toIpv4Bytes(s: String): ByteArray =
        s.split('.').map { it.toInt().toByte() }.toByteArray()

    private fun stripBrackets(s: String): String =
        if (s.startsWith("[") && s.endsWith("]")) s.substring(1, s.length - 1) else s
}
