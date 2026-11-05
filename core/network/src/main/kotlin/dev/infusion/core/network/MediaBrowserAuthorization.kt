package dev.infusion.core.network

object MediaBrowserAuthorization {

    const val HEADER = "Authorization"

    const val SCHEME = "MediaBrowser"

    fun identity(
        client: String,
        device: String,
        deviceId: String,
        version: String,
    ): String = buildString {
        append(SCHEME)
        append(' ')
        append(parameter("Client", client))
        append(", ")
        append(parameter("Device", device))
        append(", ")
        append(parameter("DeviceId", deviceId))
        append(", ")
        append(parameter("Version", version))
    }

    fun value(identity: String, token: String?): String =
        if (token.isNullOrEmpty()) identity else "$identity, ${parameter("Token", token)}"

    fun headers(identity: String, token: String?): Map<String, String> =
        mapOf(HEADER to value(identity, token))

    private fun parameter(name: String, raw: String): String = "$name=\"" + sanitize(raw) + "\""

    private fun sanitize(raw: String): String {
        var clean: StringBuilder? = null
        for (index in raw.indices) {
            val ch = raw[index]
            val invalid = ch == '"' || ch == '\\' || ch.code < 0x20 || ch.code == 0x7f
            if (invalid) {
                if (clean == null) clean = StringBuilder(raw.substring(0, index))
            } else {
                clean?.append(ch)
            }
        }
        return clean?.toString() ?: raw
    }
}
