package dev.infusion.core.pairing

import dev.infusion.core.network.Redactor

object PairingRedaction {

    private const val MARKER = "[REDACTED]"

    fun sessionId(id: String): String = "${id.take(4)}…"

    fun url(raw: String): String = Redactor.url(raw)

    fun token(token: String): String = Redactor.token(token)

    fun redact(text: String, secrets: Collection<String>): String {
        var out = text
        for (secret in secrets.sortedByDescending { it.length }) {
            if (secret.isNotEmpty()) out = out.replace(secret, MARKER)
        }
        return out
    }
}
