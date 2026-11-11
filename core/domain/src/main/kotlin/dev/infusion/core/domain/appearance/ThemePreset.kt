package dev.infusion.core.domain.appearance

enum class ThemePreset(
    val storageKey: String,

    val displayName: String,

    val tagline: String,
) {
    CLASSIC(
        storageKey = "classic",
        displayName = "Classic",
        tagline = "The accepted fission look",
    ),
    AURORA(
        storageKey = "aurora",
        displayName = "Aurora",
        tagline = "Glass & northern light",
    ),
    EMBER(
        storageKey = "ember",
        displayName = "Ember",
        tagline = "Warm sunset energy",
    ),
    OBSIDIAN_GOLD(
        storageKey = "obsidian_gold",
        displayName = "Obsidian Gold",
        tagline = "OLED black, brushed metal",
    ),
    ;

    val isClassic: Boolean get() = this == CLASSIC

    companion object {

        val DEFAULT: ThemePreset = CLASSIC

        fun fromStorageKey(key: String?): ThemePreset? =
            entries.firstOrNull { it.storageKey == key }

        fun orDefault(key: String?): ThemePreset = fromStorageKey(key) ?: DEFAULT

        val choices: List<ThemePreset> = listOf(CLASSIC, AURORA, EMBER, OBSIDIAN_GOLD)
    }
}
