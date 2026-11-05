package dev.infusion.core.domain.appearance

enum class ArtworkStyle(val storageKey: String) {

    POSTER("poster"),

    LANDSCAPE("landscape"),
    ;

    companion object {

        val DEFAULT_MOVIES: ArtworkStyle = LANDSCAPE

        val DEFAULT_SERIES: ArtworkStyle = LANDSCAPE

        fun fromStorageKey(key: String?): ArtworkStyle? =
            entries.firstOrNull { it.storageKey == key }

        fun orDefault(key: String?, fallback: ArtworkStyle): ArtworkStyle =
            fromStorageKey(key) ?: fallback
    }
}
