package dev.infusion.core.domain.appearance

enum class AccentColor(
    val storageKey: String,

    val followsTheme: Boolean = false,
) {

    THEME("theme", followsTheme = true),
    ORANGE("orange"),
    RED("red"),
    AMBER("amber"),
    GREEN("green"),
    BLUE("blue"),
    PURPLE("purple"),
    ;

    companion object {

        val DEFAULT: AccentColor = THEME

        fun fromStorageKey(key: String?): AccentColor? =
            entries.firstOrNull { it.storageKey == key }

        fun orDefault(key: String?): AccentColor = fromStorageKey(key) ?: DEFAULT
    }
}
