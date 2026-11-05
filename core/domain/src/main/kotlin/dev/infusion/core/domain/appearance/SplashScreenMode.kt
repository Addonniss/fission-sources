package dev.infusion.core.domain.appearance

enum class SplashScreenMode(
    val storageKey: String,
    val displayName: String,
    val supportingText: String,
) {

    FAST(
        storageKey = "fast",
        displayName = "Fast",
        supportingText = "Open Home as soon as it is ready.",
    ),

    RECOMMENDED(
        storageKey = "recommended",
        displayName = "Recommended",
        supportingText = "Wait for the first Home background image.",
    ),
    ;

    companion object {

        val DEFAULT: SplashScreenMode = RECOMMENDED

        fun fromStorageKey(key: String?): SplashScreenMode? =
            entries.firstOrNull { it.storageKey == key }

        fun orDefault(
            key: String?,
            fallback: SplashScreenMode = DEFAULT,
        ): SplashScreenMode = fromStorageKey(key) ?: fallback
    }
}
