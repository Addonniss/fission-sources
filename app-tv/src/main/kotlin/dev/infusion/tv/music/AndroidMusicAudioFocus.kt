package dev.infusion.tv.music

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import dev.infusion.core.domain.music.MusicAudioFocus
import dev.infusion.core.domain.music.MusicAudioFocusChange
import dev.infusion.core.domain.music.MusicAudioFocusListener

class AndroidMusicAudioFocus(context: Context) : MusicAudioFocus {

    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    private var listener: MusicAudioFocusListener? = null
    private var request: AudioFocusRequest? = null

    private var holdsFocus: Boolean = false

    override fun request(): Boolean {
        val manager = audioManager ?: return true
        if (holdsFocus) return true
        val focusRequest = request ?: run {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { change ->
                    listener?.onAudioFocusChange(change.toMusicChange())
                }
                .build()
                .also { request = it }
        }
        val result = manager.requestAudioFocus(focusRequest)
        holdsFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return holdsFocus
    }

    override fun abandon() {
        val manager = audioManager ?: return
        if (!holdsFocus) return
        request?.let { focusRequest -> manager.abandonAudioFocusRequest(focusRequest) }
        holdsFocus = false
    }

    override fun setListener(listener: MusicAudioFocusListener?) {
        this.listener = listener
    }

    private fun Int.toMusicChange(): MusicAudioFocusChange = when (this) {
        AudioManager.AUDIOFOCUS_GAIN -> MusicAudioFocusChange.GAIN
        AudioManager.AUDIOFOCUS_LOSS -> MusicAudioFocusChange.LOSS_PERMANENT
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> MusicAudioFocusChange.LOSS_TRANSIENT
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> MusicAudioFocusChange.LOSS_TRANSIENT_CAN_DUCK
        else -> MusicAudioFocusChange.LOSS_TRANSIENT
    }
}
