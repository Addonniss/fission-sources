package dev.infusion.tv.ui.focus

import androidx.compose.runtime.compositionLocalOf

class DirectionalPressOwnership {

    private var pressDownTime: Long = NO_PRESS

    fun claim(downTime: Long, repeatCount: Int): Boolean {

        if (downTime <= 0L) return true
        if (downTime == pressDownTime && repeatCount <= 0) return false
        pressDownTime = downTime
        return true
    }

    private companion object {
        const val NO_PRESS: Long = Long.MIN_VALUE
    }
}

val LocalDirectionalPressOwnership =
    compositionLocalOf<DirectionalPressOwnership?> { null }
