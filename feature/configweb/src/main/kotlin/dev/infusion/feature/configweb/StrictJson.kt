package dev.infusion.feature.configweb

import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

sealed interface JsonValue {

    data class Obj(val fields: Map<String, JsonValue>) : JsonValue

    data class Arr(val items: List<JsonValue>) : JsonValue

    data class Str(val value: String) : JsonValue

    data class Num(val value: Long) : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data object Null : JsonValue
}

data class JsonLimits(
    val maxDepth: Int = ConfigWebLimits.MAX_JSON_DEPTH,
    val maxArrayEntries: Int = ConfigWebLimits.MAX_JSON_ARRAY_ENTRIES,
    val maxStringChars: Int = ConfigWebLimits.MAX_JSON_STRING_CHARS,
    val maxObjectFields: Int = ConfigWebLimits.MAX_JSON_OBJECT_FIELDS,
) {
    init {
        require(maxDepth > 0 && maxArrayEntries > 0 && maxStringChars > 0 && maxObjectFields > 0)
    }
}

sealed interface StrictJsonResult {

    data class Ok(val value: JsonValue) : StrictJsonResult

    data class Invalid(val field: String, val reason: String) : StrictJsonResult
}

object StrictJson {

    fun parse(bytes: ByteArray, limits: JsonLimits = JsonLimits()): StrictJsonResult {
        val text = decodeUtf8(bytes) ?: return StrictJsonResult.Invalid(ROOT, "body is not valid UTF-8")
        return try {
            val reader = Reader(text, limits)
            val value = reader.readDocument()
            StrictJsonResult.Ok(value)
        } catch (e: Failure) {
            StrictJsonResult.Invalid(e.path.ifEmpty { ROOT }, e.reason)
        }
    }

    const val ROOT: String = "body"

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private class Failure(val path: String, val reason: String) : RuntimeException(reason)

    private class Reader(
        private val text: String,
        private val limits: JsonLimits,
    ) {
        private var index = 0

        fun readDocument(): JsonValue {
            val value = readValue(path = "", depth = 0)
            skipWhitespace()
            if (index != text.length) throw Failure("", "trailing content after the JSON value")
            return value
        }

        private fun readValue(path: String, depth: Int): JsonValue {
            skipWhitespace()
            if (index >= text.length) throw Failure(path, "unexpected end of body")
            return when (val ch = text[index]) {
                '{' -> readObject(path, depth + 1)
                '[' -> readArray(path, depth + 1)
                '"' -> JsonValue.Str(readString(path))
                't' -> { expectLiteral("true", path); JsonValue.Bool(true) }
                'f' -> { expectLiteral("false", path); JsonValue.Bool(false) }
                'n' -> { expectLiteral("null", path); JsonValue.Null }
                '-', in '0'..'9' -> readNumber(path)
                else -> throw Failure(path, "unexpected character '${printable(ch)}'")
            }
        }

        private fun readObject(path: String, depth: Int): JsonValue.Obj {
            if (depth > limits.maxDepth) throw Failure(path, "nesting deeper than ${limits.maxDepth}")
            index++
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw Failure(path, "object keys must be double-quoted")
                val key = readString(path)
                if (fields.containsKey(key)) throw Failure(path, "duplicate field")
                if (fields.size >= limits.maxObjectFields) {
                    throw Failure(path, "more than ${limits.maxObjectFields} fields")
                }
                skipWhitespace()
                if (peek() != ':') throw Failure(childPath(path, key), "expected ':'")
                index++
                val childPath = childPath(path, key)
                fields[key] = readValue(childPath, depth)
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> { index++; return JsonValue.Obj(fields) }
                    else -> throw Failure(path, "expected ',' or '}'")
                }
            }
        }

        private fun readArray(path: String, depth: Int): JsonValue.Arr {
            if (depth > limits.maxDepth) throw Failure(path, "nesting deeper than ${limits.maxDepth}")
            index++
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return JsonValue.Arr(items)
            }
            while (true) {
                if (items.size >= limits.maxArrayEntries) {
                    throw Failure(path, "more than ${limits.maxArrayEntries} entries")
                }
                items += readValue("$path[${items.size}]", depth)
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> { index++; return JsonValue.Arr(items) }
                    else -> throw Failure(path, "expected ',' or ']'")
                }
            }
        }

        private fun readString(path: String): String {
            index++
            val out = StringBuilder()
            while (true) {
                if (index >= text.length) throw Failure(path, "unterminated string")
                val ch = text[index]
                if (ch == '"') {
                    index++
                    return out.toString()
                }
                if (ch == '\\') {
                    if (index + 1 >= text.length) throw Failure(path, "unterminated escape")
                    when (val esc = text[index + 1]) {
                        '"' -> { out.append('"'); index += 2 }
                        '\\' -> { out.append('\\'); index += 2 }
                        '/' -> { out.append('/'); index += 2 }
                        'b' -> { out.append('\b'); index += 2 }
                        'f' -> { out.append('\u000C'); index += 2 }
                        'n' -> { out.append('\n'); index += 2 }
                        'r' -> { out.append('\r'); index += 2 }
                        't' -> { out.append('\t'); index += 2 }
                        'u' -> {
                            val code = readHex4(path, index + 2)
                            index += 6
                            when {
                                code in 0xDC00..0xDFFF -> throw Failure(path, "lone surrogate escape")
                                code in 0xD800..0xDBFF -> {
                                    if (index + 6 > text.length || text[index] != '\\' || text[index + 1] != 'u') {
                                        throw Failure(path, "lone surrogate escape")
                                    }
                                    val low = readHex4(path, index + 2)
                                    if (low !in 0xDC00..0xDFFF) throw Failure(path, "invalid surrogate pair")
                                    out.append(code.toChar())
                                    out.append(low.toChar())
                                    index += 6
                                }
                                else -> out.append(code.toChar())
                            }
                        }
                        else -> throw Failure(path, "invalid escape '${printable(esc)}'")
                    }
                } else {
                    if (ch.code < 0x20) throw Failure(path, "raw control character in string")
                    out.append(ch)
                    index++
                }
                if (out.length > limits.maxStringChars) {
                    throw Failure(path, "string longer than ${limits.maxStringChars} characters")
                }
            }
        }

        private fun readHex4(path: String, at: Int): Int {
            if (at + 4 > text.length) throw Failure(path, "truncated \\u escape")
            var value = 0
            for (offset in 0 until 4) {
                val digit = Character.digit(text[at + offset], 16)
                if (digit < 0) throw Failure(path, "invalid \\u escape")
                value = (value shl 4) or digit
            }
            return value
        }

        private fun readNumber(path: String): JsonValue.Num {
            val start = index
            if (peek() == '-') index++
            val digitsStart = index
            while (index < text.length && text[index] in '0'..'9') index++
            if (index == digitsStart) throw Failure(path, "expected a digit")
            if (index - digitsStart > 1 && text[digitsStart] == '0') throw Failure(path, "leading zero")
            if (index < text.length && (text[index] == '.' || text[index] == 'e' || text[index] == 'E')) {
                throw Failure(path, "expected an integer")
            }
            val raw = text.substring(start, index)
            return JsonValue.Num(raw.toLongOrNull() ?: throw Failure(path, "number out of range"))
        }

        private fun expectLiteral(literal: String, path: String) {
            if (!text.startsWith(literal, index)) throw Failure(path, "invalid literal")
            index += literal.length
        }

        private fun skipWhitespace() {
            while (index < text.length) {
                val ch = text[index]
                if (ch != ' ' && ch != '\t' && ch != '\r' && ch != '\n') return
                index++
            }
        }

        private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

        private fun childPath(parent: String, key: String): String =
            if (parent.isEmpty()) key else "$parent.$key"

        private fun printable(ch: Char): String =
            if (ch.code in 0x20..0x7e) ch.toString() else "\\u%04x".format(ch.code)
    }
}
