package dev.infusion.core.lanweb

fun interface LanwebHandler {
    fun handle(request: LanwebRequest): LanwebResponse
}

sealed interface LanwebRouteResolution {

    data class Matched(
        val handler: LanwebHandler,
        val pathParams: Map<String, String>,
    ) : LanwebRouteResolution

    data object NotFound : LanwebRouteResolution

    data object MethodNotAllowed : LanwebRouteResolution
}

class LanwebRouter {

    private val routes: MutableList<Route> = mutableListOf()

    fun get(path: String, handler: LanwebHandler): LanwebRouter = route("GET", path, handler)

    fun post(path: String, handler: LanwebHandler): LanwebRouter = route("POST", path, handler)

    fun route(method: String, path: String, handler: LanwebHandler): LanwebRouter {
        require(METHOD.matches(method)) { "method must be an HTTP token" }
        require(path.startsWith("/")) { "route path must start with '/'" }
        val segments = split(path)
        require(segments.isNotEmpty()) { "route path must have at least one segment" }
        val captures = segments.filter { isPlaceholder(it) }.map { it.substring(1, it.length - 1) }
        require(captures.toSet().size == captures.size) { "duplicate path parameter in $path" }
        require(captures.none { !PARAM_NAME.matches(it) }) { "invalid path parameter name in $path" }
        require(routes.none { it.method == method && it.pattern == path }) {
            "duplicate route declaration: $method $path"
        }
        routes += Route(method = method, pattern = path, segments = segments, handler = handler)
        return this
    }

    fun resolve(method: String, path: String): LanwebRouteResolution {
        val requested = split(path)
        var pathDeclared = false
        for (route in routes) {
            if (route.segments.size != requested.size) continue
            val params = matchSegments(route.segments, requested) ?: continue
            pathDeclared = true
            if (route.method == method) {
                return LanwebRouteResolution.Matched(handler = route.handler, pathParams = params)
            }
        }
        return if (pathDeclared) {
            LanwebRouteResolution.MethodNotAllowed
        } else {
            LanwebRouteResolution.NotFound
        }
    }

    val size: Int get() = routes.size

    private fun matchSegments(
        pattern: List<String>,
        requested: List<String>,
    ): Map<String, String>? {
        val params = LinkedHashMap<String, String>()
        for (index in pattern.indices) {
            val expected = pattern[index]
            val actual = requested[index]
            if (isPlaceholder(expected)) {
                if (actual.isEmpty()) return null
                params[expected.substring(1, expected.length - 1)] = actual
            } else if (expected != actual) {
                return null
            }
        }
        return params
    }

    private class Route(
        val method: String,
        val pattern: String,
        val segments: List<String>,
        val handler: LanwebHandler,
    )

    private companion object {
        val METHOD = Regex("[A-Z]{1,16}")
        val PARAM_NAME = Regex("[A-Za-z][A-Za-z0-9_]{0,31}")

        fun split(path: String): List<String> = path.split('/')

        fun isPlaceholder(segment: String): Boolean =
            segment.length >= 3 && segment.startsWith("{") && segment.endsWith("}")
    }
}
