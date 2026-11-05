package dev.infusion.source.stremio.policy

import dev.infusion.core.network.DiagnosticReason
import dev.infusion.source.stremio.wire.WireException

object StremioId {

    const val MAX_ID_BYTES = 512
    const val MAX_NAME_BYTES = 512

    class InvalidId(message: String) : Exception(message)

    fun validate(id: String) {
        if (id.isEmpty()) throw InvalidId("empty id")
        if (id == "." || id == "..") throw InvalidId("path traversal id")
        if (id.length > MAX_ID_BYTES) throw InvalidId("id too long")
        for (c in id) {
            val code = c.code
            if (code < 0x20 || code in 0x7F..0x9F) throw InvalidId("control character in id")
            if (c == '/' || c == '\\' || c == '%') throw InvalidId("unsafe id character")
        }
        if (id.toByteArray(Charsets.UTF_8).size > MAX_ID_BYTES) throw InvalidId("id exceeds byte bound")
    }

    fun validateBoundedString(
        value: String,
        maxBytes: Int,
        what: String,
        allowEmpty: Boolean = false,
        allowLineBreaks: Boolean = false,
    ) {
        if (value.isEmpty() && !allowEmpty) throw invalid("empty $what")
        if (value.toByteArray(Charsets.UTF_8).size > maxBytes) throw invalid("$what too long")
        for (c in value) {
            val code = c.code
            val permittedFormatting = allowLineBreaks && code in intArrayOf('\t'.code, '\n'.code, '\r'.code)
            if ((code < 0x20 && !permittedFormatting) || code == 0x7F) {
                throw invalid("control character in $what")
            }
        }
    }

    private fun invalid(message: String): WireException =
        WireException(DiagnosticReason.SCHEMA_INVALID, message)

    fun validateOrThrow(id: String) {
        try {
            validate(id)
        } catch (e: InvalidId) {
            throw dev.infusion.source.stremio.wire.WireException(DiagnosticReason.SCHEMA_INVALID, e.message ?: "invalid id")
        }
    }
}
