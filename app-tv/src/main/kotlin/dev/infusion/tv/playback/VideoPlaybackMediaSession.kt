package dev.infusion.tv.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import dev.infusion.core.playback.api.SessionId
import dev.infusion.core.playback.media3.Media3PlaybackBinder
import dev.infusion.feature.vertical.player.PlayerStateHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VideoPlaybackMediaSessionHost(
    private val context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {

    private var owner: PlayerStateHolder? = null

    private var session: MediaSession? = null
    private var publishedSessionId: SessionId? = null

    private val _destinationActive = MutableStateFlow(false)

    val destinationActive: StateFlow<Boolean> = _destinationActive.asStateFlow()

    private val binderObserver: (SessionId, ExoPlayer?) -> Unit = { sessionId, player ->
        onPlayerChanged(sessionId, player)
    }

    val currentSession: MediaSession? get() = session

    fun attach(value: PlayerStateHolder) {
        if (owner === value) return
        if (owner != null) detach(owner!!)
        owner = value
        _destinationActive.value = true
        Media3PlaybackBinder.addObserver(binderObserver)
        val liveId = value.activeEngineSessionId
        val livePlayer = liveId?.let { Media3PlaybackBinder.playerFor(it) }
        if (liveId != null && livePlayer != null) onMain { publish(liveId, livePlayer) }
    }

    fun detach(value: PlayerStateHolder) {
        if (owner !== value) return
        Media3PlaybackBinder.removeObserver(binderObserver)
        owner = null
        _destinationActive.value = false
        onMain { releaseSession() }
    }

    private fun onPlayerChanged(sessionId: SessionId, player: ExoPlayer?) {
        onMain {
            val current = owner?.activeEngineSessionId
            if (current != sessionId) return@onMain
            if (player == null) {

                if (publishedSessionId == sessionId) releaseSession()
                return@onMain
            }
            publish(sessionId, player)
        }
    }

    private fun publish(sessionId: SessionId, player: Player) {
        val current = owner ?: return
        if (current.activeEngineSessionId != sessionId) return
        if (session != null && publishedSessionId == sessionId) return
        releaseSession()
        val wrapper = VideoSessionPlayer(player, current)
        session = MediaSession.Builder(context, wrapper).build()
        publishedSessionId = sessionId
    }

    private fun releaseSession() {
        val active = session
        session = null
        publishedSessionId = null
        if (active != null) active.release()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}

class VideoSessionPlayer(
    val wrapped: Player,
    private val owner: PlayerStateHolder,
) : ForwardingPlayer(wrapped) {

    companion object {

        val AVAILABLE_COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAllReadOnlyCommands()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_FORWARD)
            .add(Player.COMMAND_SEEK_BACK)
            .build()
    }

    override fun getAvailableCommands(): Player.Commands = AVAILABLE_COMMANDS

    override fun isCommandAvailable(command: Int): Boolean = AVAILABLE_COMMANDS.contains(command)

    override fun play() = owner.play()

    override fun pause() = owner.pause()

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) owner.play() else owner.pause()
    }

    override fun seekTo(positionMs: Long) = owner.seekTo(positionMs)

    override fun seekForward() {
        owner.seekForward()
    }

    override fun seekBack() {
        owner.seekBackward()
    }
}
