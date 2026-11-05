package dev.infusion.core.playback.media3

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import dev.infusion.core.playback.api.SessionId

object Media3PlaybackBinder {

    @Volatile
    var activePlayer: ExoPlayer? = null
        private set

    @Volatile
    private var activeSessionId: SessionId? = null

    private val observers = java.util.concurrent.CopyOnWriteArrayList<(SessionId, ExoPlayer?) -> Unit>()

    fun addObserver(observer: (SessionId, ExoPlayer?) -> Unit) {
        observers.add(observer)
    }

    fun removeObserver(observer: (SessionId, ExoPlayer?) -> Unit) {
        observers.remove(observer)
    }

    internal fun attach(sessionId: SessionId, player: ExoPlayer) {
        activeSessionId = sessionId
        activePlayer = player
        observers.forEach { observer -> runCatching { observer(sessionId, player) } }
    }

    internal fun clear(sessionId: SessionId, player: ExoPlayer) {
        if (activeSessionId == sessionId && activePlayer === player) {
            activeSessionId = null
            activePlayer = null
        }
        observers.forEach { observer -> runCatching { observer(sessionId, null) } }
    }

    fun playerFor(sessionId: SessionId): Player? =
        activePlayer?.takeIf { activeSessionId == sessionId }
}
