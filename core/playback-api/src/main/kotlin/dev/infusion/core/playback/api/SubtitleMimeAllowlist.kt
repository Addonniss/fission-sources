package dev.infusion.core.playback.api

object SubtitleMimeAllowlist {

    private val CANONICAL = setOf(
        "application/x-subrip",
        "text/vtt",
        "text/x-ssa",
        "application/ttml+xml",
        "application/x-ssa",
        "application/x-ssa+xml",
        "text/x-ssa+xml",
        "application/smil",
        "text/x-srt",
        "application/x-sami",
        "text/x-sami",
    )

    private val TOKEN_MAP = mapOf(
        "vtt" to "text/vtt",
        "webvtt" to "text/vtt",
        "srt" to "application/x-subrip",
        "subrip" to "application/x-subrip",
        "ass" to "text/x-ssa",
        "ssa" to "text/x-ssa",
        "ttml" to "application/ttml+xml",
        "dfxp" to "application/ttml+xml",
        "sami" to "application/x-sami",
    )

    fun normalize(declared: String?): String? {
        if (declared.isNullOrBlank()) return null
        val trimmed = declared.trim()
        if (trimmed.length > MAX_DECLARED_FORMAT_LENGTH) return null
        val lower = trimmed.lowercase()
        if (lower in CANONICAL) return lower
        return TOKEN_MAP[lower]
    }

    fun normalizeDeclaredFileName(declared: String?): String? {
        val name = declared?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_DECLARED_FILE_NAME_LENGTH }
            ?: return null

        val leaf = name.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('\\')
        val dot = leaf.lastIndexOf('.')
        if (dot <= 0 || dot == leaf.lastIndex) return null
        val suffix = leaf.substring(dot + 1)
        if (suffix.length > MAX_DECLARED_SUFFIX_LENGTH) return null
        return TOKEN_MAP[suffix.lowercase()]
    }

    private const val MAX_DECLARED_FORMAT_LENGTH = 64

    private const val MAX_DECLARED_FILE_NAME_LENGTH = 256

    private const val MAX_DECLARED_SUFFIX_LENGTH = 8
}
