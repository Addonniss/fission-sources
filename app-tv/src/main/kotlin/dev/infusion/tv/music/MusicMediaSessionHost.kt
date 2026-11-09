package dev.infusion.tv.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommands
import dev.infusion.core.playback.api.SessionId
import dev.infusion.core.playback.media3.Media3PlaybackBinder
import dev.infusion.feature.vertical.music.MusicQueuePhase
import dev.infusion.feature.vertical.music.MusicQueueState
import dev.infusion.feature.vertical.music.MusicQueueStateHolder
import dev.infusion.tv.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class MusicMediaSessionHost(
    private val context: Context,
    private val holder: MusicQueueStateHolder,

    private val scope: CoroutineScope,
) {
    private var service: MediaSessionService? = null
    private var session: MediaSession? = null
    private var sessionPlayer: MusicSessionPlayer? = null
    private var attachedSessionId: String? = null
    private var pendingPlayer: Pair<String, ExoPlayer>? = null
    private var observing = false

    private val binderObserver: (SessionId, ExoPlayer?) -> Unit = { sessionId, player ->
        onPlayerChanged(sessionId, player)
    }

    fun start() {
        if (observing) return
        observing = true
        Media3PlaybackBinder.addObserver(binderObserver)
        scope.launch {
            holder.state.collect { state -> onState(state) }
        }
    }

    fun stop() {
        if (!observing) return
        observing = false
        Media3PlaybackBinder.removeObserver(binderObserver)
        releaseSession()
        stopService()
    }

    val currentSession: MediaSession? get() = session

    fun onServiceCreated(created: MediaSessionService) {
        service = created

        val pending = pendingPlayer
        if (pending != null) {
            pendingPlayer = null
            attachPlayer(pending.first, pending.second)
        } else if (session != null) {

            val player = sessionPlayer?.wrapped
            val id = attachedSessionId
            releaseSession()
            if (player != null && id != null) attachPlayer(id, player)
        }
    }

    fun onServiceDestroyed() {
        service = null
        sessionPlayer = null
        session = null
        attachedSessionId = null
    }

    private fun onState(state: MusicQueueState) {
        if (!state.hasQueue) {
            releaseSession()
            stopService()
            return
        }
        when (state.phase) {
            MusicQueuePhase.PREPARING, MusicQueuePhase.PLAYING, MusicQueuePhase.PAUSED -> ensureService()

            MusicQueuePhase.ENDED, MusicQueuePhase.ERROR, MusicQueuePhase.IDLE,
            MusicQueuePhase.READY,
            -> {
                releaseSession()
                stopService()
            }
        }
    }

    private fun ensureService() {
        if (service != null) return
        val intent = Intent(context, MusicPlaybackService::class.java)
        runCatching { context.startService(intent) }
    }

    private fun stopService() {
        if (service == null) return
        runCatching { context.stopService(Intent(context, MusicPlaybackService::class.java)) }
    }

    private fun onPlayerChanged(sessionId: SessionId, player: ExoPlayer?) {
        if (player == null) {

            return
        }
        if (holder.state.value.currentSessionId != sessionId.value) return
        ensureService()
        if (service == null) {
            pendingPlayer = sessionId.value to player
            return
        }
        attachPlayer(sessionId.value, player)
    }

    private fun attachPlayer(sessionIdValue: String, player: Player) {
        val wrapper = MusicSessionPlayer(player, holder)
        val existing = session
        if (existing == null) {
            val owner = service ?: context
            val builder = MediaSession.Builder(owner, wrapper).setCallback(callback)
            sessionActivity()?.let { activity -> builder.setSessionActivity(activity) }
            val created = builder.build()
            session = created

            service?.addSession(created)
        } else {
            existing.setPlayer(wrapper)
        }
        sessionPlayer = wrapper
        attachedSessionId = sessionIdValue
    }

    private fun releaseSession() {
        val active = session
        session = null
        sessionPlayer = null
        attachedSessionId = null
        if (active != null) {
            runCatching { active.release() }
        }
    }

    private fun sessionActivity(): PendingIntent? = runCatching {
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }.getOrNull()

    private val callback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailablePlayerCommands(MusicSessionPlayer.AVAILABLE_COMMANDS)
            .build()
    }
}

class MusicSessionPlayer(
    val wrapped: Player,
    private val holder: MusicQueueStateHolder,
) : ForwardingPlayer(wrapped) {

    companion object {

        val AVAILABLE_COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAllReadOnlyCommands()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_PREPARE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .build()
    }

    override fun getAvailableCommands(): Player.Commands = AVAILABLE_COMMANDS

    override fun isCommandAvailable(command: Int): Boolean = AVAILABLE_COMMANDS.contains(command)

    override fun play() = holder.play()

    override fun pause() = holder.pause()

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) holder.play() else holder.pause()
    }

    override fun seekTo(positionMs: Long) = holder.seekTo(positionMs)

    override fun seekToNext() = holder.next()

    override fun seekToNextMediaItem() = holder.next()

    override fun seekToPrevious() = holder.previous()

    override fun seekToPreviousMediaItem() = holder.previous()

    override fun stop() = holder.stop()

    override fun getMediaMetadata(): MediaMetadata = metadata()

    override fun getCurrentMediaItem(): MediaItem? =
        wrapped.currentMediaItem?.buildUpon()?.setMediaMetadata(metadata())?.build()

    override fun getMediaItemAt(index: Int): MediaItem =
        wrapped.getMediaItemAt(index).buildUpon().setMediaMetadata(metadata()).build()

    private fun metadata(): MediaMetadata {
        val current = holder.state.value.current
        return MediaMetadata.Builder()
            .setTitle(current?.title ?: "fission music")
            .setArtist(current?.artist ?: current?.albumName)
            .setAlbumTitle(current?.albumName)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
    }
}
