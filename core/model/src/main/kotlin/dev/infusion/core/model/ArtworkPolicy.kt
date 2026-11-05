package dev.infusion.core.model

enum class ArtworkPolicy {

    POSTER,

    BACKDROP,

    STILL,

    LOGO,
    ;

    val encodedKind: String
        get() = when (this) {
            POSTER -> "poster"
            BACKDROP -> "backdrop"
            STILL -> "still"
            LOGO -> "logo"
        }
}
