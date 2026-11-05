package dev.infusion.core.lanweb

import java.nio.charset.StandardCharsets

class LanwebRequest(
    val method: String,

    val path: String,

    val query: String? = null,

    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val pathParams: Map<String, String> = emptyMap(),
) {
    fun header(name: String): String? = headers[name.lowercase()]

    fun bodyText(): String = String(body, StandardCharsets.UTF_8)

    fun withPathParams(params: Map<String, String>): LanwebRequest = LanwebRequest(
        method = method,
        path = path,
        query = query,
        headers = headers,
        body = body,
        pathParams = params,
    )

    override fun toString(): String = "LanwebRequest($method $path)"
}

class LanwebResponse(
    val status: Int = 200,
    val contentType: String = LanwebContentTypes.JSON,
    val body: ByteArray = ByteArray(0),
    val headers: Map<String, String> = emptyMap(),
) {
    init {
        require(status in 100..599) { "status must be a valid HTTP status code" }
        require(contentType.isNotEmpty()) { "contentType must not be empty" }
        require(!hasCrLf(contentType)) { "contentType must not contain CR/LF" }
        headers.forEach { (name, value) ->
            require(HEADER_NAME.matches(name)) { "invalid response header name" }
            require(!hasCrLf(value)) { "response header values must not contain CR/LF" }
        }
    }

    companion object {

        fun bytes(
            status: Int = 200,
            contentType: String,
            body: ByteArray,
            headers: Map<String, String> = emptyMap(),
        ): LanwebResponse = LanwebResponse(status, contentType, body, headers)

        fun json(status: Int = 200, body: String, headers: Map<String, String> = emptyMap()): LanwebResponse =
            LanwebResponse(status, LanwebContentTypes.JSON, body.toByteArray(StandardCharsets.UTF_8), headers)

        fun error(status: Int): LanwebResponse = json(status, ERROR_BODY)

        fun html(body: String, status: Int = 200, headers: Map<String, String> = emptyMap()): LanwebResponse =
            LanwebResponse(status, LanwebContentTypes.HTML, body.toByteArray(StandardCharsets.UTF_8), headers)

        fun css(body: String, status: Int = 200, headers: Map<String, String> = emptyMap()): LanwebResponse =
            LanwebResponse(status, LanwebContentTypes.CSS, body.toByteArray(StandardCharsets.UTF_8), headers)

        fun javascript(body: String, status: Int = 200, headers: Map<String, String> = emptyMap()): LanwebResponse =
            LanwebResponse(status, LanwebContentTypes.JAVASCRIPT, body.toByteArray(StandardCharsets.UTF_8), headers)

        const val ERROR_BODY: String = """{"status":"error"}"""

        private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    }
}

object LanwebContentTypes {
    const val JSON: String = "application/json"
    const val HTML: String = "text/html; charset=utf-8"
    const val CSS: String = "text/css; charset=utf-8"
    const val JAVASCRIPT: String = "application/javascript; charset=utf-8"
    const val TEXT: String = "text/plain; charset=utf-8"
}

class LanwebHttpException(val status: Int) : RuntimeException("HTTP $status")

private fun hasCrLf(value: String): Boolean = value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
