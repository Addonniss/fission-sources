package dev.infusion.core.domain.appearance

enum class TmdbBackdropQuality(

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
    LARGE(
        storageKey = "large",
        displayName = "Large",
        supportingText = "1280 px wide.",
        bucket = "w1280",
    ),
    ORIGINAL(
        storageKey = "original",
        displayName = "Original",
        supportingText = "Uses the uploaded image size — not guaranteed 4K.",
        bucket = "original",
    ),
    ;

    companion object {

        val DEFAULT: TmdbBackdropQuality = LARGE

        fun orDefault(storageKey: String?): TmdbBackdropQuality =
            entries.firstOrNull { it.storageKey == storageKey } ?: DEFAULT
    }
}
