package dev.infusion.tv.music

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.infusion.tv.InfusionTvApplication

class MusicPlaybackService : MediaSessionService() {

    private val host: MusicMediaSessionHost?
        get() = (application as? InfusionTvApplication)?.container?.musicMediaSessionHost

    override fun onCreate() {
        super.onCreate()
        host?.onServiceCreated(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        host?.currentSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val container = (application as? InfusionTvApplication)?.container
        val playing = container?.musicQueueStateHolder?.state?.value?.isPlaying == true
        if (!playing) {
            container?.musicMediaSessionHost?.stop()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        host?.onServiceDestroyed()
        super.onDestroy()
    }
}
