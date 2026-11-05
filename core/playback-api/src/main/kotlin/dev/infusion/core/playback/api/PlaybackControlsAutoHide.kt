package dev.infusion.core.playback.api

enum class PlaybackControlsAutoHide(

    val storageKey: String,

    val timeoutMs: Long?,
) {

    OFF("off", null),

    THREE_SECONDS("3", 3_000L),

    FIVE_SECONDS("5", 5_000L),

    TEN_SECONDS("10", 10_000L),
    ;

    companion object {

        val DEFAULT: PlaybackControlsAutoHide = OFF

        fun orDefault(storageKey: String?): PlaybackControlsAutoHide {
            val key = storageKey?.trim() ?: return DEFAULT
            return entries.firstOrNull { it.storageKey == key } ?: DEFAULT
        }
    }
}
