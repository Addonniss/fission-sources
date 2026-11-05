package dev.infusion.tv.updater

import dev.infusion.feature.vertical.music.MusicQueuePhase

fun MusicQueuePhase.suppressesUpdatePresentation(): Boolean = when (this) {
    MusicQueuePhase.PREPARING,
    MusicQueuePhase.PLAYING,
    MusicQueuePhase.PAUSED,
    -> true
    MusicQueuePhase.IDLE,
    MusicQueuePhase.READY,
    MusicQueuePhase.ENDED,
    MusicQueuePhase.ERROR,
    -> false
}
