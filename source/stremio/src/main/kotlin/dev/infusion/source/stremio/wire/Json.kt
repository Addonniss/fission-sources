package dev.infusion.source.stremio.wire

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class JsonLimits(
    val maxBytes: Long = 1L * 1024 * 1024,
    val maxDepth: Int = 64,
    val maxKeysPerObject: Int = 4096,
    val maxElementsPerArray: Int = 8192,
    val maxNodes: Int = 100_000,
) {
    companion object {
        val DEFAULT = JsonLimits()

        fun forDocument(maxBytes: Long) = DEFAULT.copy(maxBytes = maxBytes)
    }
}

enum class JsonErrorCode {

    INVALID_UTF8,

    DUPLICATE_KEY,

    NESTING_TOO_DEEP,

    TOO_MANY_KEYS,

    TOO_MANY_ELEMENTS,

    TOO_MANY_NODES,

    NUMBER_OUT_OF_RANGE,

    INVALID_NUMBER,

    INVALID_STRING,

    INVALID_SYNTAX,

    UNTERMINATED,

    TRAILING_CONTENT,
    ;

    val limitGuard: dev.infusion.core.network.DiagnosticLimitGuard?
        get() = when (this) {
            NESTING_TOO_DEEP -> dev.infusion.core.network.DiagnosticLimitGuard.JSON_DEPTH
            TOO_MANY_KEYS -> dev.infusion.core.network.DiagnosticLimitGuard.JSON_OBJECT_KEYS
            TOO_MANY_ELEMENTS -> dev.infusion.core.network.DiagnosticLimitGuard.JSON_ARRAY_ELEMENTS
            TOO_MANY_NODES -> dev.infusion.core.network.DiagnosticLimitGuard.JSON_NODES
            else -> null
        }
}

class StrictJsonException(val code: JsonErrorCode, message: String) : Exception(message)

sealed class JsonValue {
    data object Null : JsonValue()

    data class Bool(val value: Boolean) : JsonValue()

    data class Str(val value: String) : JsonValue()

    sealed class Num : JsonValue() {
        data class Int(val value: Long) : Num()

        data class Dbl(val value: Double) : Num()
    }

    data class Arr(val elements: List<JsonValue>) : JsonValue()

    data class Obj(val fields: LinkedHashMap<String, JsonValue>) : JsonValue()

    fun asObject(): Obj? = this as? Obj
    fun asArray(): Arr? = this as? Arr
    fun asString(): String? = (this as? Str)?.value

    fun toRaw(): Any? = when (this) {
        Null -> null
        is Bool -> value
        is Str -> value
        is Num.Int -> value
        is Num.Dbl -> value
        is Arr -> elements.map { it.toRaw() }
        is Obj -> fields.mapValues { it.value.toRaw() }
    }
}

object StrictJson {

    fun parse(bytes: ByteArray, limits: JsonLimits = JsonLimits.DEFAULT): JsonValue {
        if (bytes.size.toLong() > limits.maxBytes) {
            throw StrictJsonException(JsonErrorCode.TOO_MANY_NODES, "document exceeds byte cap")
        }
        val text = decodeUtf8(bytes, limits)
        val parser = Parser(text, limits)
        return parser.parse()
    }

    private fun decodeUtf8(bytes: ByteArray, limits: JsonLimits): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            throw StrictJsonException(JsonErrorCode.INVALID_UTF8, "utf-8 BOM rejected")
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw StrictJsonException(JsonErrorCode.INVALID_UTF8, "invalid utf-8")
        }
    }

    private class Parser(private val s: String, private val limits: JsonLimits) {
        private var pos = 0
        private var nodes = 0

        fun parse(): JsonValue {
            skipWs()
            val v = parseValue(0)
            skipWs()
            if (pos != s.length) throw err(JsonErrorCode.TRAILING_CONTENT, "trailing content after value")
            return v
        }

        private fun parseValue(depth: Int): JsonValue {
            if (++nodes > limits.maxNodes) throw err(JsonErrorCode.TOO_MANY_NODES, "document too large")
            if (depth > limits.maxDepth) throw err(JsonErrorCode.NESTING_TOO_DEEP, "nesting too deep")
            if (pos >= s.length) throw err(JsonErrorCode.UNTERMINATED, "unexpected end of input")
            return when (val c = s[pos]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> parseString()
                't' -> parseLiteral("true", JsonValue.Bool(true))
                'f' -> parseLiteral("false", JsonValue.Bool(false))
                'n' -> parseLiteral("null", JsonValue.Null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw err(JsonErrorCode.INVALID_SYNTAX, "unexpected character")
            }
        }

        private fun parseLiteral(literal: String, value: JsonValue): JsonValue {
            if (!s.startsWith(literal, pos)) throw err(JsonErrorCode.INVALID_SYNTAX, "invalid literal")
            pos += literal.length
            return value
        }

        private fun parseObject(depth: Int): JsonValue.Obj {
            expect('{')
            skipWs()
            val fields = LinkedHashMap<String, JsonValue>()
            if (peekOrFail() == '}') {
                pos++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWs()
                val c = peekOrFail()
                if (c != '"') throw err(JsonErrorCode.INVALID_SYNTAX, "object key must be a quoted string")
                val key = parseString().value
                if (fields.containsKey(key)) {
                    throw err(JsonErrorCode.DUPLICATE_KEY, "duplicate object key")
                }
                if (fields.size >= limits.maxKeysPerObject) {
                    throw err(JsonErrorCode.TOO_MANY_KEYS, "too many object keys")
                }
                skipWs()
                expect(':')
                skipWs()
                val v = parseValue(depth + 1)
                fields[key] = v
                skipWs()
                when (peekOrFail()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonValue.Obj(fields)
                    }
                    else -> throw err(JsonErrorCode.INVALID_SYNTAX, "expected ',' or '}'")
                }
            }
        }

        private fun parseArray(depth: Int): JsonValue.Arr {
            expect('[')
            skipWs()
            val elements = ArrayList<JsonValue>()
            if (peekOrFail() == ']') {
                pos++
                return JsonValue.Arr(elements)
            }
            while (true) {
                if (elements.size >= limits.maxElementsPerArray) {
                    throw err(JsonErrorCode.TOO_MANY_ELEMENTS, "too many array elements")
                }
                skipWs()
                elements.add(parseValue(depth + 1))
                skipWs()
                when (peekOrFail()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonValue.Arr(elements)
                    }
                    else -> throw err(JsonErrorCode.INVALID_SYNTAX, "expected ',' or ']'")
                }
            }
        }

        private fun parseString(): JsonValue.Str {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw err(JsonErrorCode.UNTERMINATED, "unterminated string")
                val c = s[pos]
                when {
                    c == '"' -> {
                        pos++
                        return JsonValue.Str(sb.toString())
                    }
                    c == '\\' -> {
                        pos++
                        if (pos >= s.length) throw err(JsonErrorCode.UNTERMINATED, "unterminated escape")
                        when (val e = s[pos]) {
                            '"' -> {
                                sb.append('"')
                                pos++
                            }
                            '\\' -> {
                                sb.append('\\')
                                pos++
                            }
                            '/' -> {
                                sb.append('/')
                                pos++
                            }
                            'b' -> {
                                sb.append('\b')
                                pos++
                            }
                            'f' -> {
                                sb.append('\u000C')
                                pos++
                            }
                            'n' -> {
                                sb.append('\n')
                                pos++
                            }
                            'r' -> {
                                sb.append('\r')
                                pos++
                            }
                            't' -> {
                                sb.append('\t')
                                pos++
                            }
                            'u' -> {

                                pos++
                                val cp = parseHex4()
                                when {
                                    cp in 0xD800..0xDBFF -> {

                                        if (pos + 1 >= s.length || s[pos] != '\\' || s[pos + 1] != 'u') {
                                            throw err(JsonErrorCode.INVALID_STRING, "lone high surrogate escape")
                                        }
                                        pos += 2
                                        val low = parseHex4()
                                        if (low !in 0xDC00..0xDFFF) {
                                            throw err(JsonErrorCode.INVALID_STRING, "invalid low surrogate escape")
                                        }
                                        sb.append(cp.toChar()).append(low.toChar())
                                    }
                                    cp in 0xDC00..0xDFFF -> {
                                        throw err(JsonErrorCode.INVALID_STRING, "lone low surrogate escape")
                                    }
                                    else -> sb.append(cp.toChar())
                                }
                            }
                            else -> throw err(JsonErrorCode.INVALID_STRING, "invalid escape sequence")
                        }
                    }
                    c.code < 0x20 -> throw err(JsonErrorCode.INVALID_STRING, "unescaped control character in string")
                    else -> {
                        sb.append(c)
                        pos++
                    }
                }
            }
        }

        private fun parseHex4(): Int {
            if (pos + 4 > s.length) throw err(JsonErrorCode.INVALID_STRING, "truncated unicode escape")
            var v = 0
            repeat(4) {
                val c = s[pos++]
                val d = when (c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> c - 'a' + 10
                    in 'A'..'F' -> c - 'A' + 10
                    else -> throw err(JsonErrorCode.INVALID_STRING, "invalid unicode escape")
                }
                v = (v shl 4) or d
            }
            return v
        }

        private fun parseNumber(): JsonValue.Num {
            val start = pos
            if (s[pos] == '-') pos++
            if (pos >= s.length || s[pos] !in '0'..'9') {
                throw err(JsonErrorCode.INVALID_NUMBER, "number requires an integer part")
            }
            if (s[pos] == '0') {
                pos++
                if (pos < s.length && s[pos] in '0'..'9') {
                    throw err(JsonErrorCode.INVALID_NUMBER, "leading zeros not allowed")
                }
            } else {
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            var isDouble = false
            if (pos < s.length && s[pos] == '.') {
                isDouble = true
                pos++
                if (pos >= s.length || s[pos] !in '0'..'9') {
                    throw err(JsonErrorCode.INVALID_NUMBER, "fraction requires digits")
                }
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isDouble = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                if (pos >= s.length || s[pos] !in '0'..'9') {
                    throw err(JsonErrorCode.INVALID_NUMBER, "exponent requires digits")
                }
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val text = s.substring(start, pos)
            return if (isDouble) {
                val d = text.toDoubleOrNull()
                    ?: throw err(JsonErrorCode.INVALID_NUMBER, "invalid number literal")
                if (!d.isFinite()) throw err(JsonErrorCode.NUMBER_OUT_OF_RANGE, "number out of range")
                JsonValue.Num.Dbl(d)
            } else {
                val l = text.toLongOrNull()
                    ?: throw err(JsonErrorCode.NUMBER_OUT_OF_RANGE, "integer out of range")
                JsonValue.Num.Int(l)
            }
        }

        private fun skipWs() {
            while (pos < s.length) {
                when (s[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        private fun expect(c: Char) {
            if (pos >= s.length || s[pos] != c) throw err(JsonErrorCode.INVALID_SYNTAX, "expected '$c'")
            pos++
        }

        private fun peekOrFail(): Char {
            if (pos >= s.length) throw err(JsonErrorCode.UNTERMINATED, "unexpected end of input")
            return s[pos]
        }

        private fun err(code: JsonErrorCode, message: String): StrictJsonException =
            StrictJsonException(code, message)
    }
}
