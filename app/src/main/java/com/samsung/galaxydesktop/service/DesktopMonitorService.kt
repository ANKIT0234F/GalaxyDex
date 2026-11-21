package com.samsung.galaxydesktop.service

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.samsung.galaxydesktop.GalaxyDesktopApp
import com.samsung.galaxydesktop.desktop.DesktopPresentation
import com.samsung.galaxydesktop.gatekeeper.DeviceValidator
import com.samsung.galaxydesktop.input.TrackpadActivity

/**
 * Foreground service that watches for external displays and hosts the desktop
 * [DesktopPresentation] on the first usable one.
 *
 * Start it from a visible activity (see [start]); Android 12+ blocks starting
 * foreground services from the background.
 */
class DesktopMonitorService : Service() {

    private lateinit var displayManager: DisplayManager
    private val handler = Handler(Looper.getMainLooper())

    private var presentation: DesktopPresentation? = null
    private var activeDisplayId: Int? = null

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = attachIfFree(displayId)

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == activeDisplayId) {
                detach()
                // Fall back to any other external display that's still connected.
                displayManager.displays.forEach { attachIfFree(it.displayId) }
            }
        }

        override fun onDisplayChanged(displayId: Int) {
            val display = displayManager.getDisplay(displayId)
            when {
                displayId == activeDisplayId && (display == null || !isExternalCandidate(display)) -> detach()
                activeDisplayId == null -> attachIfFree(displayId)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Gatekeeper: never run on unsupported devices.
        val status = DeviceValidator.check(this)
        if (!status.isSupported) {
            Log.w(TAG, "Unsupported device: ${status.rejectionReasons}")
            stopSelf()
            return
        }

        displayManager = getSystemService(DisplayManager::class.java)

        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        DesktopSession.setServiceRunning(true)

        displayManager.registerDisplayListener(listener, handler)
        // A display may already be connected when the service starts.
        displayManager.displays.forEach { attachIfFree(it.displayId) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (::displayManager.isInitialized) {
            displayManager.unregisterDisplayListener(listener)
        }
        detach()
        DesktopSession.setServiceRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Display handling -------------------------------------------------------------

    /**
     * Skip the built-in screen and private displays. FLAG_PRESENTATION is the clean signal
     * for Miracast/HDMI, but some virtual displays (e.g. scrcpy) don't set it, so it's
     * not required.
     */
    private fun isExternalCandidate(display: Display): Boolean =
        display.displayId != Display.DEFAULT_DISPLAY &&
            display.isValid &&
            display.state != Display.STATE_OFF &&
            (display.flags and Display.FLAG_PRIVATE) == 0

    private fun attachIfFree(displayId: Int) {
        if (activeDisplayId != null) return
        val display = displayManager.getDisplay(displayId) ?: return
        if (!isExternalCandidate(display)) return

        if (!Settings.canDrawOverlays(this) && (display.flags and Display.FLAG_PRESENTATION) == 0) {
            Log.w(TAG, "Display $displayId isn't a presentation display and overlay permission is missing")
            return
        }

        val info = ExternalDisplayInfo.from(this, display)
        val p = DesktopPresentation(this, display, info)
        p.setOnDismissListener {
            if (presentation === p) {
                presentation = null
                activeDisplayId = null
                DesktopSession.setActiveDisplay(null)
            }
        }

        try {
            p.show()
            presentation = p
            activeDisplayId = displayId
            DesktopSession.setActiveDisplay(info)
            openTrackpad()
            Log.i(TAG, "Desktop shown on display $displayId (${info.widthPx}x${info.heightPx}, scale ${info.uiScale})")
        } catch (e: WindowManager.InvalidDisplayException) {
            Log.w(TAG, "Display $displayId vanished before show()", e)
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "Window type rejected on display $displayId", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to show on display $displayId", e)
        }
    }

    /**
     * Put the trackpad on the phone screen. Background activity starts can be blocked, so the
     * notification also opens it as a fallback.
     */
    private fun openTrackpad() {
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
        runCatching {
            startActivity(
                Intent(this, TrackpadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                options.toBundle()
            )
        }.onFailure { Log.i(TAG, "Couldn't auto-open trackpad; use the notification: $it") }
    }

    private fun detach() {
        presentation?.let { p ->
            p.setOnDismissListener(null)
            runCatching { p.dismiss() }
        }
        presentation = null
        activeDisplayId = null
        DesktopSession.setActiveDisplay(null)
    }

    // --- Notification ------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, DesktopMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, GalaxyDesktopApp.CHANNEL_DESKTOP)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Desktop mode is on")
            .setContentText("Tap to open the trackpad.")
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 1, Intent(this, TrackpadActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "DesktopMonitor"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.samsung.galaxydesktop.action.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DesktopMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DesktopMonitorService::class.java))
        }
    }
}
