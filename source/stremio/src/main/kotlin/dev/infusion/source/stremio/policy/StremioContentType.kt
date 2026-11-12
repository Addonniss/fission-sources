package dev.infusion.source.stremio.policy

enum class StremioContentType(val wire: String) {
    MOVIE("movie"),
    SERIES("series"),
    CHANNEL("channel"),
    TV("tv"),

    ALL("all"),

    UNKNOWN("unknown"),
    ;

    val isDeclaredContentType: Boolean
        get() = this != ALL && this != UNKNOWN

    val routableAsCatalog: Boolean
        get() = this != UNKNOWN

    companion object {
        fun parse(value: String): StremioContentType? = entries.firstOrNull { it.wire == value }

        fun isKnown(value: String): Boolean = parse(value)?.let { it.isDeclaredContentType } ?: false

        fun parseOrUnknown(value: String): StremioContentType =
            parse(value)?.takeIf { it.isDeclaredContentType } ?: UNKNOWN
    }
}
