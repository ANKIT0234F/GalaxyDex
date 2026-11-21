package com.samsung.galaxydesktop.service

import android.content.Context
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/** Snapshot of the external display the desktop is running on. */
data class ExternalDisplayInfo(
    val displayId: Int,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    /** Extra UI scale for the Compose layer (1.5x on 4K displays). */
    val uiScale: Float
) {
    companion object {
        private const val UHD_WIDTH = 3840

        fun from(context: Context, display: Display): ExternalDisplayInfo {
            val mode = display.mode
            val w = mode.physicalWidth
            val h = mode.physicalHeight
            val dpi = context.createDisplayContext(display).resources.configuration.densityDpi
            val scale = if (max(w, h) >= UHD_WIDTH) 1.5f else 1f
            return ExternalDisplayInfo(display.displayId, w, h, dpi, scale)
        }
    }
}

/** Process-wide state shared by the service, desktop UI, trackpad and accessibility service. */
object DesktopSession {
    private val _activeDisplay = MutableStateFlow<ExternalDisplayInfo?>(null)
    val activeDisplay: StateFlow<ExternalDisplayInfo?> = _activeDisplay.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    internal fun setActiveDisplay(info: ExternalDisplayInfo?) { _activeDisplay.value = info }
    internal fun setServiceRunning(running: Boolean) { _serviceRunning.value = running }

    /** Packages with a window on the external display, reported by CursorAccessibilityService. */
    private val _visiblePackages = MutableStateFlow<Set<String>>(emptySet())
    val visiblePackages: StateFlow<Set<String>> = _visiblePackages.asStateFlow()
    internal fun setVisiblePackages(packages: Set<String>) { _visiblePackages.value = packages }
}
