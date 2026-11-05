package dev.infusion.feature.configweb

object DraftRequestCodec {

    sealed interface Decoded {

        data class Ok(val operations: List<ConfigOperation>) : Decoded

        data class Invalid(val field: String, val reason: String) : Decoded
    }

    fun decode(
        body: ByteArray,
        limits: JsonLimits = JsonLimits(),
        maxOperations: Int = ConfigWebLimits.MAX_OPERATIONS_PER_DRAFT,
    ): Decoded {
        val root = when (val parsed = StrictJson.parse(body, limits)) {
            is StrictJsonResult.Invalid -> return Decoded.Invalid(parsed.field, parsed.reason)
            is StrictJsonResult.Ok -> parsed.value
        }
        if (root !is JsonValue.Obj) return Decoded.Invalid(StrictJson.ROOT, "expected a JSON object")
        unknownField(root, ROOT_FIELDS, StrictJson.ROOT)?.let { return it }
        val array = root.fields["operations"] as? JsonValue.Arr
            ?: return Decoded.Invalid("operations", "expected an array of operations")
        if (array.items.isEmpty()) {
            return Decoded.Invalid("operations", "a draft must include at least one changed row or operation")
        }
        if (array.items.size > maxOperations) {
            return Decoded.Invalid("operations", "a draft may carry at most $maxOperations operations")
        }

        val operations = ArrayList<ConfigOperation>(array.items.size)
        val identities = HashSet<String>(array.items.size)
        array.items.forEachIndexed { index, item ->
            val field = "operations[$index]"
            if (item !is JsonValue.Obj) return Decoded.Invalid(field, "expected an operation object")
            unknownField(item, OPERATION_FIELDS, field)?.let { return it }

            val familyName = stringField(item, "family")
                ?: return Decoded.Invalid("$field.family", "expected a configuration family")
            val family = ConfigFamily.fromWire(familyName)
                ?: return Decoded.Invalid("$field.family", "unknown configuration family")

            val kindName = stringField(item, "kind")
                ?: return Decoded.Invalid("$field.kind", "expected an operation kind")
            val kind = ConfigOperationKind.fromWire(kindName)
                ?: return Decoded.Invalid("$field.kind", "unknown operation kind")

            val rowKey = stringField(item, "rowKey")
                ?: return Decoded.Invalid("$field.rowKey", "expected a row key")
            if (!ROW_KEY.matches(rowKey)) {
                return Decoded.Invalid("$field.rowKey", "row keys are letters, digits, '.', ':' or '_' (1..64)")
            }

            val targetId = when (val target = optionalString(item, "targetId", ConfigWebLimits.MAX_TARGET_ID_CHARS)) {
                is OptionalString.Absent -> null
                is OptionalString.Invalid -> return Decoded.Invalid(
                    "$field.targetId",
                    "expected a bounded item identity",
                )
                is OptionalString.Present -> target.value
            }

            val value: ConfigValue? = when (val raw = item.fields["value"]) {
                null, JsonValue.Null -> null
                is JsonValue.Obj -> when (val decoded = decodeValue(raw, "$field.value")) {
                    is ValueDecoded.Invalid -> return Decoded.Invalid(decoded.field, decoded.reason)
                    is ValueDecoded.Ok -> decoded.value
                }
                else -> return Decoded.Invalid("$field.value", "expected a value object")
            }

            if (kind.requiresValue && value == null) {
                return Decoded.Invalid("$field.value", "this operation kind requires a value")
            }
            if (kind.requiresTargetId && targetId == null) {
                return Decoded.Invalid("$field.targetId", "this operation kind requires an item identity")
            }
            if (kind.requiresTargetId && value != null) {
                return Decoded.Invalid("$field.value", "this operation kind does not take a value")
            }
            if (kind.requiresValue && targetId != null) {
                return Decoded.Invalid("$field.targetId", "this operation kind does not take an item identity")
            }

            val operation = ConfigOperation(
                family = family,
                kind = kind,
                rowKey = rowKey,
                targetId = targetId,
                value = value,
            )
            if (!identities.add(operation.identityKey())) {
                return Decoded.Invalid(field, "the same row or operation appears twice in this draft")
            }
            operations += operation
        }
        return Decoded.Ok(operations)
    }

    private sealed interface ValueDecoded {
        data class Ok(val value: ConfigValue) : ValueDecoded
        data class Invalid(val field: String, val reason: String) : ValueDecoded
    }

    private sealed interface OptionalString {
        data object Absent : OptionalString
        data class Present(val value: String) : OptionalString
        data object Invalid : OptionalString
    }

    private fun decodeValue(raw: JsonValue.Obj, field: String): ValueDecoded {
        val unknown = raw.fields.keys - VALUE_FIELDS
        if (unknown.isNotEmpty()) {
            val name = unknown.first().takeIf { SAFE_NAME.matches(it) } ?: "<unknown>"
            return ValueDecoded.Invalid("$field.$name", "unknown field")
        }
        val kind = stringField(raw, "kind")
            ?: return ValueDecoded.Invalid("$field.kind", "expected a value kind")
        val value: ConfigValue = when (kind) {
            "flag" -> {
                val flag = raw.fields["flag"] as? JsonValue.Bool
                    ?: return ValueDecoded.Invalid("$field.flag", "expected a boolean")
                ConfigValue.Flag(flag.value)
            }
            "number" -> {
                val number = raw.fields["number"] as? JsonValue.Num
                    ?: return ValueDecoded.Invalid("$field.number", "expected an integer")
                ConfigValue.Count(number.value)
            }
            "text" -> {
                val text = stringField(raw, "text")
                    ?: return ValueDecoded.Invalid("$field.text", "expected a non-empty string")
                ConfigValue.Text(text)
            }
            "order" -> {
                val ids = raw.fields["ids"] as? JsonValue.Arr
                    ?: return ValueDecoded.Invalid("$field.ids", "expected an array of identities")
                val decoded = ArrayList<String>(ids.items.size)
                ids.items.forEachIndexed { index, item ->
                    val text = (item as? JsonValue.Str)?.value
                    if (text.isNullOrEmpty()) {
                        return ValueDecoded.Invalid("$field.ids[$index]", "expected a non-empty identity")
                    }
                    decoded += text
                }
                ConfigValue.Order(decoded)
            }
            else -> return ValueDecoded.Invalid("$field.kind", "unknown value kind")
        }
        val payloadField = when (kind) {
            "flag" -> "flag"
            "number" -> "number"
            "text" -> "text"
            else -> "ids"
        }
        if (raw.fields[payloadField] == null) {
            return ValueDecoded.Invalid("$field.$payloadField", "expected the payload of this value kind")
        }
        return ValueDecoded.Ok(value)
    }

    private fun unknownField(obj: JsonValue.Obj, allowed: Set<String>, field: String): Decoded.Invalid? {
        val unknown = obj.fields.keys - allowed
        if (unknown.isEmpty()) return null

        val name = unknown.first().takeIf { SAFE_NAME.matches(it) } ?: "<unknown>"
        return Decoded.Invalid(if (field == StrictJson.ROOT) name else "$field.$name", "unknown field")
    }

    private fun stringField(obj: JsonValue.Obj, name: String): String? =
        (obj.fields[name] as? JsonValue.Str)?.value?.takeIf { it.isNotEmpty() }

    private fun optionalString(obj: JsonValue.Obj, name: String, maxChars: Int): OptionalString {
        return when (val raw = obj.fields[name]) {
            null, JsonValue.Null -> OptionalString.Absent
            is JsonValue.Str -> {
                val text = raw.value
                if (text.isEmpty() || text.length > maxChars || text.any(Char::isISOControl)) {
                    OptionalString.Invalid
                } else {
                    OptionalString.Present(text)
                }
            }
            else -> OptionalString.Invalid
        }
    }

    private val ROOT_FIELDS: Set<String> = setOf("operations")
    private val OPERATION_FIELDS: Set<String> = setOf("family", "kind", "rowKey", "targetId", "value")
    private val VALUE_FIELDS: Set<String> = setOf("kind", "flag", "number", "text", "ids")
    private val ROW_KEY: Regex = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")
    private val SAFE_NAME: Regex = Regex("[A-Za-z0-9_]{1,32}")
}
