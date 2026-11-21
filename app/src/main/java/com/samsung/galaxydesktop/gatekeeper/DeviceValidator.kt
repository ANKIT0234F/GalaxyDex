package com.samsung.galaxydesktop.gatekeeper

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Runtime device checks. This is a safety net against running on unsupported hardware,
 * not tamper-proof enforcement (a modified APK can skip it).
 */
object DeviceValidator {

    const val MIN_SDK = 36

    /** ro.build.version.oneui encodes One UI 8.5 as 80500. */
    const val MIN_ONEUI_PROP = 80500

    /**
     * Fallback only, used when the system property is unreadable.
     * UNVERIFIED: One UI 8.0 reports 160000, so 8.5 is assumed to be 160100.
     * Confirm on a real device and adjust.
     */
    const val MIN_SEM_PLATFORM_INT = 160100

    data class Status(
        val isSamsung: Boolean,
        val sdkOk: Boolean,
        val oneUiOk: Boolean,
        val freeformSupported: Boolean,
        val oneUiProp: Int?,
        val semPlatformInt: Int?
    ) {
        /** Hard requirements. If false the app must not start desktop mode. */
        val isSupported: Boolean get() = isSamsung && sdkOk && oneUiOk

        /** Supported device, but freeform isn't enabled yet: send the user to the setup wizard. */
        val needsFreeformSetup: Boolean get() = isSupported && !freeformSupported

        val rejectionReasons: List<String>
            get() = buildList {
                if (!isSamsung) add("Not a Samsung device (${Build.MANUFACTURER}/${Build.BRAND}).")
                if (!sdkOk) add("Android 16 (API $MIN_SDK) or newer is required; found API ${Build.VERSION.SDK_INT}.")
                if (!oneUiOk) add("One UI 8.5 or newer is required (oneui=$oneUiProp, sem=$semPlatformInt).")
            }
    }

    fun check(context: Context): Status {
        val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
            Build.BRAND.equals("samsung", ignoreCase = true)
        val sdkOk = Build.VERSION.SDK_INT >= MIN_SDK

        val oneUiProp = readOneUiProp()
        val sem = readSemPlatformInt()
        val oneUiOk = when {
            oneUiProp != null -> oneUiProp >= MIN_ONEUI_PROP
            sem != null -> sem >= MIN_SEM_PLATFORM_INT
            else -> false
        }

        val freeform = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)

        return Status(samsung, sdkOk, oneUiOk, freeform, oneUiProp, sem)
    }

    /** Convenience for call sites that only need the hard pass/fail. */
    fun isSupported(context: Context): Boolean = check(context).isSupported

    private fun readOneUiProp(): Int? = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("getprop", "ro.build.version.oneui"))
        val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
        out.toIntOrNull()
    }.getOrNull()

    /** Build.VERSION.SEM_PLATFORM_INT only exists on Samsung builds, so reflect. */
    private fun readSemPlatformInt(): Int? = runCatching {
        Build.VERSION::class.java.getField("SEM_PLATFORM_INT").getInt(null)
    }.getOrNull()
}
