package com.samsung.galaxydesktop.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Virtual mouse position in external-display pixels.
 *
 * The trackpad (Prompt 4) calls [moveBy]; the cursor overlay draws [position];
 * the accessibility service reads [position] to inject clicks at the same spot.
 */
object CursorController {
    data class Position(val x: Float, val y: Float)

    private val _position = MutableStateFlow(Position(0f, 0f))
    val position: StateFlow<Position> = _position.asStateFlow()

    @Volatile private var maxX = 1f
    @Volatile private var maxY = 1f

    /** Call when a display is attached. Recenters the cursor. */
    fun setBounds(widthPx: Int, heightPx: Int) {
        maxX = (widthPx - 1).coerceAtLeast(1).toFloat()
        maxY = (heightPx - 1).coerceAtLeast(1).toFloat()
        _position.value = Position(maxX / 2f, maxY / 2f)
    }

    fun moveBy(dx: Float, dy: Float) {
        _position.update { Position((it.x + dx).coerceIn(0f, maxX), (it.y + dy).coerceIn(0f, maxY)) }
    }
}
