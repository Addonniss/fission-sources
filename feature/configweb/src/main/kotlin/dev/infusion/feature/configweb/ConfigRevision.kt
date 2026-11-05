package dev.infusion.feature.configweb

import java.security.MessageDigest
import java.nio.charset.StandardCharsets

data class RevisionValue(
    val family: ConfigFamily,
    val rowKey: String,
    val value: String,
)

object ConfigRevision {

    const val HEX_LENGTH: Int = 64

    fun fingerprint(values: List<RevisionValue>): String {
        val ordered = values.sortedWith(
            compareBy<RevisionValue> { it.family.ordinal }.thenBy { it.rowKey },
        )
        val canonical = StringBuilder()
        for (row in ordered) {
            appendPart(canonical, row.family.wireName)
            appendPart(canonical, row.rowKey)
            appendPart(canonical, row.value)
            canonical.append('\n')
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toString().toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun appendPart(target: StringBuilder, part: String) {
        target.append(part.length).append(':').append(part)
    }
}
