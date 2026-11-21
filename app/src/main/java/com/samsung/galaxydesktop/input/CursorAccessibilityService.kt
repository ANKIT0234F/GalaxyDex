package com.samsung.galaxydesktop.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import com.samsung.galaxydesktop.desktop.CursorController
import com.samsung.galaxydesktop.service.DesktopSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Turns trackpad input into real gestures on the external display via dispatchGesture().
 * The user must enable it once in Settings > Accessibility.
 *
 * It also reports which apps have windows on the external display (package names only) so the
 * taskbar can drop apps that were closed.
 */
class CursorAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var gestureInFlight = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        _connected.value = false
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> publishVisiblePackages()
        }
    }

    // --- Public actions (called by TrackpadActivity) -----------------------------------

    fun clickAtCursor(): Boolean = withCursor { x, y, id -> tap(x, y, id, longPress = false) }

    /** Android's gesture API has no right-click, so "context menu" is a long press. */
    fun contextClickAtCursor(): Boolean = withCursor { x, y, id -> tap(x, y, id, longPress = true) }

    /**
     * Swipe from the cursor. A finger moving up ([distancePx] < 0) scrolls content up
     * (natural scrolling). Returns false if a previous gesture is still running, so the
     * caller can keep accumulating.
     */
    fun scrollAtCursor(distancePx: Float): Boolean = withCursor { x, y, id ->
        val maxY = DesktopSession.activeDisplay.value?.heightPx?.toFloat() ?: return@withCursor false
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, (y + distancePx).coerceIn(0f, maxY - 1f))
        }
        dispatch(id, path, SCROLL_DURATION_MS)
    }

    /** Global BACK. UNTESTED: it may act on the phone's display rather than the external one. */
    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    // --- Internals ---------------------------------------------------------------------

    private inline fun withCursor(block: (x: Float, y: Float, displayId: Int) -> Boolean): Boolean {
        val display = DesktopSession.activeDisplay.value ?: return false
        val pos = CursorController.position.value
        return block(pos.x, pos.y, display.displayId)
    }

    private fun tap(x: Float, y: Float, displayId: Int, longPress: Boolean): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val duration = if (longPress) ViewConfiguration.getLongPressTimeout() + 150L else TAP_DURATION_MS
        return dispatch(displayId, path, duration)
    }

    private fun dispatch(displayId: Int, path: Path, durationMs: Long): Boolean {
        if (gestureInFlight) return false
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        gestureInFlight = true
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { gestureInFlight = false }
            override fun onCancelled(g: GestureDescription?) { gestureInFlight = false }
        }, handler)
        if (!accepted) gestureInFlight = false
        return accepted
    }

    private fun publishVisiblePackages() {
        val display = DesktopSession.activeDisplay.value ?: return
        val windows = windowsOnAllDisplays.get(display.displayId) ?: return
        val packages = windows
            .mapNotNull { it.root?.packageName?.toString() }
            .filter { it != packageName }
            .toSet()
        DesktopSession.setVisiblePackages(packages)
    }

    companion object {
        private const val TAP_DURATION_MS = 50L
        private const val SCROLL_DURATION_MS = 120L

        @Volatile
        var instance: CursorAccessibilityService? = null
            private set

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()
    }
}
