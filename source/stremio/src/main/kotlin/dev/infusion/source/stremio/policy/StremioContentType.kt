package dev.infusion.source.stremio.policy

enum class StremioContentType(val wire: String) {
    MOVIE("movie"),
    SERIES("series"),
    CHANNEL("channel"),
    TV("tv"),

    UNKNOWN("unknown"),
    ;

    companion object {
        fun parse(value: String): StremioContentType? = entries.firstOrNull { it.wire == value }

        fun isKnown(value: String): Boolean = parse(value)?.let { it != UNKNOWN } ?: false

        fun parseOrUnknown(value: String): StremioContentType = parse(value) ?: UNKNOWN
    }
}
