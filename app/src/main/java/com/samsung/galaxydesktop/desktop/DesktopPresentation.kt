package com.samsung.galaxydesktop.desktop

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.samsung.galaxydesktop.service.DesktopSession
import com.samsung.galaxydesktop.service.ExternalDisplayInfo
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The desktop window on the external display.
 *
 * Window strategy: a TYPE_PRESENTATION / overlay window sits above normal app windows, so a
 * permanent full-screen window would hide freeform apps. Instead the window is full-screen
 * only while the desktop is "empty" (no launched apps) or the drawer is open, and collapses
 * to a taskbar-height strip at the bottom otherwise. The mouse cursor is a separate
 * click-through overlay window so it can float above apps.
 */
class DesktopPresentation(
    private val outer: Context,
    private val target: Display,
    private val info: ExternalDisplayInfo
) : Presentation(outer, target) {

    private val host = ComposeHost()
    private val uiState = DesktopUiState()

    private val taskbarPx = (64f * info.densityDpi / 160f * info.uiScale).roundToInt()
    private val engine by lazy { AppLauncherEngine(outer, info, taskbarPx) }

    private var cursorWindowManager: WindowManager? = null
    private var cursorView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host.create()
        setCancelable(false)
        configureWindow()

        setContentView(ComposeView(context).apply {
            setContent { DesktopTheme(info.uiScale) { DesktopScreen(engine, uiState) } }
        })
        window?.decorView?.let(host::installOn)
    }

    override fun onStart() {
        super.onStart()
        host.start()
        CursorController.setBounds(info.widthPx, info.heightPx)
        addCursorOverlay()

        host.lifecycleScope.launch {
            DesktopSession.visiblePackages.collect { engine.syncVisible(it) }
        }

        // Resize the window between full-screen and taskbar-strip.
        host.lifecycleScope.launch {
            combine(
                snapshotFlow { uiState.drawerOpen },
                engine.running.map { it.isNotEmpty() }
            ) { drawerOpen, hasApps -> drawerOpen || !hasApps }
                .distinctUntilChanged()
                .collect { expanded ->
                    window?.setLayout(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        if (expanded) WindowManager.LayoutParams.MATCH_PARENT else taskbarPx
                    )
                }
        }
    }

    override fun onStop() {
        removeCursorOverlay()
        host.destroy()
        super.onStop()
    }

    private fun configureWindow() {
        val w = window ?: return
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        w.setGravity(Gravity.BOTTOM)
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        )

        // Displays without FLAG_PRESENTATION (e.g. some virtual displays) need an overlay window.
        // UNTESTED on One UI: if the window is rejected the service logs BadTokenException.
        if ((target.flags and Display.FLAG_PRESENTATION) == 0) {
            w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            w.attributes = w.attributes.also { it.token = null }
        }

        // Best-effort frosted look: blur whatever is behind the taskbar strip.
        val wm = context.getSystemService(WindowManager::class.java)
        if (wm.isCrossWindowBlurEnabled) {
            w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            w.attributes = w.attributes.also { it.blurBehindRadius = 48 }
        }
    }

    // --- Cursor overlay ----------------------------------------------------------------

    private fun addCursorOverlay() {
        if (!Settings.canDrawOverlays(outer)) {
            Log.w(TAG, "No overlay permission: virtual cursor won't be visible above apps")
            return
        }
        runCatching {
            val windowContext = outer.createDisplayContext(target)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            val wm = windowContext.getSystemService(WindowManager::class.java)
            val view = ComposeView(windowContext).apply {
                host.installOn(this)
                setContent { DesktopTheme(info.uiScale) { CursorLayer() } }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(view, lp)
            cursorWindowManager = wm
            cursorView = view
        }.onFailure { Log.w(TAG, "Could not add cursor overlay", it) }
    }

    private fun removeCursorOverlay() {
        cursorView?.let { v -> runCatching { cursorWindowManager?.removeView(v) } }
        cursorView = null
        cursorWindowManager = null
    }

    /** ComposeView needs lifecycle / saved-state / view-model owners, which a Dialog doesn't provide. */
    private class ComposeHost : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store

        fun create() {
            savedState.performAttach()
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }

        fun start() { registry.currentState = Lifecycle.State.RESUMED }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }

        fun installOn(view: View) {
            view.setViewTreeLifecycleOwner(this)
            view.setViewTreeSavedStateRegistryOwner(this)
            view.setViewTreeViewModelStoreOwner(this)
        }
    }

    private companion object {
        const val TAG = "DesktopPresentation"
    }
}
