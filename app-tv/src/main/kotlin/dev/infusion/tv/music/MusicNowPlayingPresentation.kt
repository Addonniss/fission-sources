package dev.infusion.tv.music

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MusicNowPlayingPresentation {

    var lyricsVisible: Boolean by mutableStateOf(true)

    var backdropEnabled: Boolean by mutableStateOf(true)
}
