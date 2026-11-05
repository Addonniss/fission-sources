package dev.infusion.tv.ui.presentation

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize

object CardOnScreen {

    fun isVisible(bounds: Rect, window: IntSize): Boolean {
        if (window.width <= 0 || window.height <= 0) return false
        if (bounds.width <= 0f || bounds.height <= 0f) return false
        return bounds.right > 0f &&
            bounds.left < window.width.toFloat() &&
            bounds.bottom > 0f &&
            bounds.top < window.height.toFloat()
    }
}
