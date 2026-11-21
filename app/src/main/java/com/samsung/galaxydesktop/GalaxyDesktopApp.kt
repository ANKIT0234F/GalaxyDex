package com.samsung.galaxydesktop

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class GalaxyDesktopApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_DESKTOP,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shown while desktop mode is watching for an external display" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_DESKTOP = "desktop_mode"
    }
}
