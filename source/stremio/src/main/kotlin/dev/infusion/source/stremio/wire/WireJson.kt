package dev.infusion.source.stremio.wire

import dev.infusion.core.network.DiagnosticReason

class WireException(
    val reason: DiagnosticReason,
    message: String,
    val limitGuard: dev.infusion.core.network.DiagnosticLimitGuard? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    init {
        require(limitGuard == null || reason == DiagnosticReason.LIMIT_EXCEEDED) {
            "limit guard must only accompany LIMIT_EXCEEDED"
        }
    }
}

object WireJson {

    fun field(o: JsonValue.Obj, key: String): JsonValue? = o.fields[key]

    fun has(o: JsonValue.Obj, key: String): Boolean = o.fields.containsKey(key)

    fun string(o: JsonValue.Obj, key: String): String? = when (val v = o.fields[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Str -> v.value
        else -> typeError(key, "string")
    }

    fun requiredString(o: JsonValue.Obj, key: String): String =
        string(o, key) ?: throw WireException(DiagnosticReason.SCHEMA_INVALID, "missing required field: $key")

    fun bool(o: JsonValue.Obj, key: String): Boolean? = when (val v = o.fields[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Bool -> v.value
        else -> typeError(key, "boolean")
    }

    fun int(o: JsonValue.Obj, key: String): Long? = when (val v = o.fields[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Num.Int -> v.value
        else -> typeError(key, "integer")
    }

    fun intOrNull(o: JsonValue.Obj, key: String, max: Long): Long? {
        val v = int(o, key) ?: return null
        if (v < 0 || v > max) throw WireException(DiagnosticReason.SCHEMA_INVALID, "field out of range: $key")
        return v
    }

    fun obj(o: JsonValue.Obj, key: String): JsonValue.Obj? = when (val v = o.fields[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Obj -> v
        else -> typeError(key, "object")
    }

    fun requiredObj(o: JsonValue.Obj, key: String): JsonValue.Obj =
        obj(o, key) ?: throw WireException(DiagnosticReason.SCHEMA_INVALID, "missing required field: $key")

    fun array(o: JsonValue.Obj, key: String): List<JsonValue>? = when (val v = o.fields[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Arr -> v.elements
        else -> typeError(key, "array")
    }

    fun requiredArray(o: JsonValue.Obj, key: String): List<JsonValue> =
        array(o, key) ?: throw WireException(DiagnosticReason.SCHEMA_INVALID, "missing required field: $key")

    fun stringArray(o: JsonValue.Obj, key: String, maxElements: Int = 4096, maxElementBytes: Int = 4096): List<String>? {
        val arr = array(o, key) ?: return null
        if (arr.size > maxElements) throw WireException(DiagnosticReason.LIMIT_EXCEEDED, "array too large: $key")
        return arr.map { v ->
            val s = (v as? JsonValue.Str)?.value ?: typeError(key, "array of strings")
            if (s.toByteArray(Charsets.UTF_8).size > maxElementBytes) {
                throw WireException(DiagnosticReason.SCHEMA_INVALID, "string too long in: $key")
            }
            s
        }
    }

    private fun typeError(key: String, expected: String): Nothing =
        throw WireException(DiagnosticReason.SCHEMA_INVALID, "field has wrong type: $key (expected $expected)")
}
