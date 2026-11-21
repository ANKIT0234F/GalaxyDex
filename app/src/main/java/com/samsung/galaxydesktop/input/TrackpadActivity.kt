package com.samsung.galaxydesktop.input

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemGestureExclusion
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samsung.galaxydesktop.desktop.CursorController
import com.samsung.galaxydesktop.gatekeeper.DeviceValidator
import com.samsung.galaxydesktop.service.DesktopSession
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Phone-side trackpad. Pure-black screen at minimum brightness (OLED pixels are off, so it
 * saves power and avoids burn-in) that keeps the touch digitizer active.
 *
 *  1-finger drag  -> move cursor      1-finger tap -> left click
 *  2-finger drag  -> scroll           2-finger tap -> context menu (long press)
 *  3-finger tap   -> back
 */
class TrackpadActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DeviceValidator.isSupported(this)) {
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.also {
            it.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF // lowest, not off
        }
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent { TrackpadScreen() }
    }
}

@Composable
private fun TrackpadScreen() {
    val context = LocalContext.current
    val view = LocalView.current
    val display by DesktopSession.activeDisplay.collectAsState()
    val serviceConnected by CursorAccessibilityService.connected.collectAsState()
    var touched by remember { mutableStateOf(false) }

    // External pixels per phone pixel, so a full swipe crosses a useful part of the monitor.
    val phoneLongSide = context.resources.displayMetrics.let { max(it.widthPixels, it.heightPixels) }
    val sensitivity = display
        ?.let { (max(it.widthPx, it.heightPx).toFloat() / phoneLongSide * BASE_GAIN).coerceIn(BASE_GAIN, MAX_SENSITIVITY) }
        ?: BASE_GAIN

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemGestureExclusion()
            .pointerInput(sensitivity) {
                trackpadGestures(
                    sensitivity = sensitivity,
                    onTouch = { touched = true },
                    haptic = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val dim = Color(0xFF3C3C3C)
        when {
            display == null -> Text("Waiting for an external display…", color = dim, fontSize = 14.sp)
            !serviceConnected -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Turn on the GalaxyDesktop Cursor accessibility service to click and scroll.",
                    color = dim, fontSize = 14.sp, textAlign = TextAlign.Center
                )
                Button(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.padding(top = 12.dp)
                ) { Text("Open Accessibility settings") }
            }
            !touched -> Text(
                "1 finger: move / click\n2 fingers: scroll / right-click\n3 fingers: back",
                color = dim, fontSize = 14.sp, textAlign = TextAlign.Center
            )
        }
    }
}

private const val BASE_GAIN = 0.75f
private const val MAX_SENSITIVITY = 3f
private const val ACCEL_GAIN = 0.9f
private const val ACCEL_EXPONENT = 1.5f
private const val MAX_CURVE = 5f
private const val TAP_MAX_MS = 350L
private const val SCROLL_STEP_PX = 36f
private const val SCROLL_GAIN = 3f

private suspend fun PointerInputScope.trackpadGestures(
    sensitivity: Float,
    onTouch: () -> Unit,
    haptic: () -> Unit
) {
    val tapSlop = viewConfiguration.touchSlop * 1.5f

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onTouch()
        val startMs = down.uptimeMillis
        var lastMs = startMs
        var maxPointers = 1
        var travel = 0f
        var scrollAccum = 0f

        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            maxPointers = max(maxPointers, pressed.size)
            lastMs = event.changes.maxOf { it.uptimeMillis }

            when {
                // Only move the cursor if this gesture never had a second finger; otherwise the
                // remaining finger would make the cursor jump when the other one lifts.
                pressed.size == 1 && maxPointers == 1 -> {
                    val change = pressed[0]
                    val delta = change.positionChange()
                    travel += delta.getDistance()
                    val dt = (change.uptimeMillis - change.previousUptimeMillis).coerceAtLeast(1L)
                    val moved = accelerate(delta, dt, sensitivity)
                    CursorController.moveBy(moved.x, moved.y)
                }
                pressed.size == 2 -> {
                    val dx = pressed.map { it.positionChange().x }.average().toFloat()
                    val dy = pressed.map { it.positionChange().y }.average().toFloat()
                    travel += abs(dx) + abs(dy)
                    scrollAccum += dy
                    if (abs(scrollAccum) >= SCROLL_STEP_PX) {
                        val sent = CursorAccessibilityService.instance
                            ?.scrollAtCursor(scrollAccum * SCROLL_GAIN * sensitivity)
                        if (sent == true) scrollAccum = 0f
                    }
                }
            }
            event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
        } while (event.changes.any { it.pressed })

        // A short, nearly-stationary touch is a tap; the finger count picks the action.
        if (travel < tapSlop && lastMs - startMs < TAP_MAX_MS) {
            val service = CursorAccessibilityService.instance
            val handled = when (maxPointers) {
                1 -> service?.clickAtCursor()
                2 -> service?.contextClickAtCursor()
                else -> service?.back()
            }
            if (handled == true) haptic()
        }
    }
}

/** Faster swipes travel disproportionately further (non-linear pointer acceleration). */
private fun accelerate(delta: Offset, dtMs: Long, sensitivity: Float): Offset {
    val speed = delta.getDistance() / dtMs // phone px per ms
    val curve = (1f + ACCEL_GAIN * speed.pow(ACCEL_EXPONENT)).coerceAtMost(MAX_CURVE)
    return delta * (sensitivity * curve)
}
