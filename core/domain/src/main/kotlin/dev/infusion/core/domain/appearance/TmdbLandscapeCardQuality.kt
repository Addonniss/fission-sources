package dev.infusion.core.domain.appearance

enum class TmdbLandscapeCardQuality(

    val storageKey: String,

    val displayName: String,

    val supportingText: String,

    val bucket: String,
) {
    SMALL(
        storageKey = "small",
        displayName = "Small",
        supportingText = "300 px wide.",
        bucket = "w300",
    ),
    MEDIUM(
        storageKey = "medium",
        displayName = "Medium",
        supportingText = "780 px wide.",
        bucket = "w780",
    ),
    ;

    companion object {

        val DEFAULT: TmdbLandscapeCardQuality = MEDIUM

        fun orDefault(storageKey: String?): TmdbLandscapeCardQuality =
            entries.firstOrNull { it.storageKey == storageKey } ?: DEFAULT
    }
}
