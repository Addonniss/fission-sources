package dev.infusion.core.domain.appearance

enum class TopShelfAppearance(val storageKey: String, val displayName: String) {

    TEXT_ONLY("text_only", "Text only"),

    ICONS_ONLY("icons_only", "Icons only"),

    ICONS_AND_TEXT("icons_and_text", "Icons & text"),
    ;

    val showsIcons: Boolean get() = this != TEXT_ONLY

    val showsText: Boolean get() = this != ICONS_ONLY

    fun next(): TopShelfAppearance = entries[(ordinal + 1) % entries.size]

    companion object {

        val DEFAULT: TopShelfAppearance = ICONS_AND_TEXT

        fun fromStorageKey(key: String?): TopShelfAppearance? =
            entries.firstOrNull { it.storageKey == key }

        fun orDefault(
            key: String?,
            fallback: TopShelfAppearance = DEFAULT,
        ): TopShelfAppearance = fromStorageKey(key) ?: fallback
    }
}
