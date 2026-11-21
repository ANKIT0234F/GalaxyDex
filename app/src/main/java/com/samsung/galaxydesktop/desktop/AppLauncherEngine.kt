package com.samsung.galaxydesktop.desktop

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import com.samsung.galaxydesktop.service.ExternalDisplayInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class AppEntry(
    val component: ComponentName,
    val label: String,
    val icon: ImageBitmap
) {
    val id: String get() = component.flattenToShortString()
    val packageName: String get() = component.packageName
}

sealed interface LaunchResult {
    /** [freeformRequested] is false when the hidden windowing-mode call was blocked (bounds-only fallback). */
    data class Launched(val freeformRequested: Boolean) : LaunchResult
    data class Failed(val reason: String) : LaunchResult
}

/** Lists installed apps and launches them on the external display. */
class AppLauncherEngine(
    context: Context,
    private val info: ExternalDisplayInfo,
    private val taskbarPx: Int
) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("desktop", Context.MODE_PRIVATE)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _running = MutableStateFlow<List<AppEntry>>(emptyList())
    val running: StateFlow<List<AppEntry>> = _running.asStateFlow()

    private val _pinned = MutableStateFlow(prefs.getStringSet(KEY_PINNED, emptySet()).orEmpty())
    val pinned: StateFlow<Set<String>> = _pinned.asStateFlow()

    suspend fun refreshApps() = withContext(Dispatchers.Default) {
        val pm = ctx.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0L))
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map {
                AppEntry(
                    component = ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                    label = it.loadLabel(pm).toString(),
                    icon = it.loadIcon(pm).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
                )
            }
            .sortedBy { it.label.lowercase() }
        _apps.value = list
    }

    fun launch(entry: AppEntry): LaunchResult {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(entry.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        val options = ActivityOptions.makeBasic()
            .setLaunchDisplayId(info.displayId)
            .setLaunchBounds(nextBounds())
        val freeform = requestFreeformMode(options)

        return try {
            ctx.startActivity(intent, options.toBundle())
            _running.update { if (it.any { r -> r.id == entry.id }) it else it + entry }
            LaunchResult.Launched(freeform)
        } catch (e: Exception) {
            Log.w(TAG, "Launch failed for ${entry.id}", e)
            LaunchResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** The taskbar only knows about apps launched from here; call when a window is known to be gone. */
    fun markClosed(packageName: String) {
        _running.update { list -> list.filterNot { it.packageName == packageName } }
    }

    private val seenVisible = mutableSetOf<String>()

    /**
     * Drops running apps whose window disappeared. Only apps that were seen visible at least
     * once are dropped, so an app that's still starting up isn't removed.
     */
    fun syncVisible(visible: Set<String>) {
        val runningPkgs = _running.value.map { it.packageName }.toSet()
        seenVisible.addAll(visible.filter { it in runningPkgs })
        val gone = runningPkgs.filter { it in seenVisible && it !in visible }.toSet()
        if (gone.isEmpty()) return
        seenVisible.removeAll(gone)
        _running.update { list -> list.filterNot { it.packageName in gone } }
    }

    fun togglePin(entry: AppEntry) {
        _pinned.update { if (entry.id in it) it - entry.id else it + entry.id }
        prefs.edit { putStringSet(KEY_PINNED, _pinned.value) }
    }

    /** 65% of the width, 80% of the space above the taskbar, centered, cascaded slightly per open app. */
    private fun nextBounds(): Rect {
        val availH = info.heightPx - taskbarPx
        val w = (info.widthPx * 0.65f).toInt()
        val h = (availH * 0.80f).toInt()
        val step = (info.widthPx * 0.02f).toInt()
        val offset = ((_running.value.size % 5) - 2) * step
        val left = (info.widthPx - w) / 2 + offset
        val top = (availH - h) / 2 + offset
        return Rect(left, top, left + w, top + h)
    }

    /**
     * ActivityOptions.setLaunchWindowingMode() is @hide. Reflection is the only way without
     * Shizuku, and Android 16 may block it; if so we still launch with display + bounds.
     */
    private fun requestFreeformMode(options: ActivityOptions): Boolean = runCatching {
        ActivityOptions::class.java
            .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
            .invoke(options, WINDOWING_MODE_FREEFORM)
        true
    }.onFailure { Log.i(TAG, "Freeform windowing mode not available: $it") }
        .getOrDefault(false)

    private companion object {
        const val TAG = "AppLauncherEngine"
        const val KEY_PINNED = "pinned"
        const val ICON_PX = 128
        const val WINDOWING_MODE_FREEFORM = 5
    }
}
