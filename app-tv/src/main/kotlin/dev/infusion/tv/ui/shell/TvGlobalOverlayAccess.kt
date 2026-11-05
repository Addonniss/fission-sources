package dev.infusion.tv.ui.shell

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

class TvGlobalOverlayAccess {

    var overlayRequester: FocusRequester? by mutableStateOf(null)
        private set

    var overlayAvailable: Boolean by mutableStateOf(false)
        private set

    var overlayArmed: Boolean by mutableStateOf(false)
        private set

    var nowPlayingVisible: Boolean by mutableStateOf(false)

    var originKey: String? by mutableStateOf(null)
        private set

    private var originRestore: ((String?) -> Boolean)? = null

    fun attachOverlay(requester: FocusRequester, available: Boolean) {
        overlayRequester = requester
        overlayAvailable = available
        if (!available) {
            overlayArmed = false
            originKey = null
        }
    }

    fun detachOverlay(requester: FocusRequester) {
        if (overlayRequester !== requester) return
        overlayRequester = null
        overlayAvailable = false
        overlayArmed = false
        originKey = null
    }

    fun attachOriginRestore(restore: ((String?) -> Boolean)?) {
        originRestore = restore
    }

    fun enterFromShelf(tabKey: String): Boolean {
        if (overlayRequester == null || !overlayAvailable || nowPlayingVisible) return false
        originKey = tabKey
        overlayArmed = true
        return true
    }

    fun returnToOrigin(): Boolean {
        val key = originKey
        overlayArmed = false
        val restore = originRestore ?: return false
        return restore(key)
    }

    fun abandonEntry() {
        overlayArmed = false
        originKey = null
    }
}

val LocalTvGlobalOverlayAccess = compositionLocalOf<TvGlobalOverlayAccess?> { null }
