package dev.infusion.core.lanweb

object LanwebLog {

    const val MAX_LOGGED_PATH_CHARS: Int = 96

    fun bindLine(authority: String): String = "lanweb transport bound on $authority"

    fun routeLog(method: String, path: String): String = "$method ${safePath(path)}"

    fun requestLine(routeLog: String?, status: Int): String =
        "lanweb transport: ${routeLog ?: "<unparsed>"} -> $status"

    fun safePath(path: String): String {
        val sanitized = buildString {
            path.forEach { ch ->
                append(if (ch.code in 0x21..0x7e) ch else '?')
            }
        }
        return if (sanitized.length <= MAX_LOGGED_PATH_CHARS) {
            sanitized
        } else {
            sanitized.substring(0, MAX_LOGGED_PATH_CHARS) + "…"
        }
    }
}
