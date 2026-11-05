package dev.infusion.feature.vertical.player

enum class PlaybackStartMode(val wireValue: String) {

    RESUME("resume"),

    FROM_BEGINNING("from_beginning"),
    ;

    companion object {

        fun fromWire(value: String?): PlaybackStartMode =
            entries.firstOrNull { it.wireValue == value } ?: RESUME
    }
}
