package com.samsung.galaxydesktop.setup

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.samsung.galaxydesktop.gatekeeper.DeviceValidator
import com.samsung.galaxydesktop.input.CursorAccessibilityService
import com.samsung.galaxydesktop.input.TrackpadActivity
import com.samsung.galaxydesktop.service.DesktopMonitorService
import com.samsung.galaxydesktop.service.DesktopSession
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

private enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, READY }

/**
 * First-run wizard: device check, permissions, and the hidden freeform settings.
 *
 * Shizuku is used only to start a small shell-privileged service ([ShellService]); the app
 * can't call Settings.Global.putInt() for these keys on its own.
 */
class SetupWizardActivity : ComponentActivity() {

    private var shizuku by mutableStateOf(ShizukuState.NOT_RUNNING)
    private var refreshTick by mutableIntStateOf(0)
    private var busy by mutableStateOf(false)
    private var log by mutableStateOf("")

    private var shell: IShellService? = null
    private var waiting: CancellableContinuation<IShellService>? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, ShellService::class.java.name))
            .processNameSuffix("shell")
            .daemon(false)
            .version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = IShellService.Stub.asInterface(binder)
            shell = service
            waiting?.takeIf { it.isActive }?.resume(service)
            waiting = null
        }

        override fun onServiceDisconnected(name: ComponentName) {
            shell = null
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshShizuku() }
    private val binderDead = Shizuku.OnBinderDeadListener { shell = null; refreshShizuku() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        refreshShizuku()
        if (result == PackageManager.PERMISSION_GRANTED) applyFreeformSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) { Screen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshShizuku()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        super.onDestroy()
    }

    // --- State ---------------------------------------------------------------------------

    private fun refreshShizuku() {
        shizuku = when {
            !Shizuku.pingBinder() -> if (isShizukuInstalled()) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
            else -> ShizukuState.NEEDS_PERMISSION
        }
        refreshTick++
    }

    private fun isShizukuInstalled(): Boolean = runCatching {
        packageManager.getPackageInfo(SHIZUKU_PACKAGE, PackageManager.PackageInfoFlags.of(0))
    }.isSuccess

    private val accessibilityComponent get() = ComponentName(this, CursorAccessibilityService::class.java)

    private fun isAccessibilityEnabled(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty()
            .split(':')
            .any { ComponentName.unflattenFromString(it) == accessibilityComponent }

    private fun freeformSettingsApplied(): Boolean =
        FREEFORM_KEYS.all { Settings.Global.getInt(contentResolver, it, 0) == 1 }

    // --- Shizuku shell -------------------------------------------------------------------

    private suspend fun ensureShell(): IShellService {
        shell?.let { return it }
        return withTimeout(10_000) {
            suspendCancellableCoroutine { cont ->
                waiting = cont
                Shizuku.bindUserService(userServiceArgs, connection)
            }
        }
    }

    private suspend fun IShellService.run(command: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val raw = exec(command)
        val split = raw.indexOf('\n')
        raw.substring(0, split).toIntOrNull().let { it ?: -1 } to raw.substring(split + 1).trim()
    }

    private fun runWithShell(title: String, commands: suspend IShellService.() -> List<String>) {
        lifecycleScope.launch {
            busy = true
            log = "$title…"
            log = runCatching { ensureShell().commands().joinToString("\n") }
                .getOrElse { "Failed: ${it.message ?: it.javaClass.simpleName}" }
            busy = false
            refreshTick++
        }
    }

    private suspend fun IShellService.runAll(commands: List<String>): List<String> =
        commands.map { cmd ->
            val (code, out) = run(cmd)
            "${if (code == 0) "✓" else "✗"} $cmd${if (out.isNotEmpty()) "  →  $out" else ""}"
        }

    private fun applyFreeformSettings() = runWithShell("Applying freeform settings") {
        runAll(
            listOf(
                // Lets this app write settings itself later, and show the cursor overlay.
                "pm grant $packageName android.permission.WRITE_SECURE_SETTINGS",
                "appops set $packageName SYSTEM_ALERT_WINDOW allow",
                "settings put global enable_freeform_support 1",
                "settings put global force_resizable_activities 1",
                "settings put global enable_non_resizable_multi_window 1",
                // AOSP developer option; harmless if One UI ignores it.
                "settings put global force_desktop_mode_on_external_displays 1"
            )
        ) + "Restart the phone if apps still don't open in floating windows."
    }

    private fun enableAccessibilityViaShizuku() = runWithShell("Enabling accessibility service") {
        val component = accessibilityComponent.flattenToString()
        val current = run("settings get secure enabled_accessibility_services").second
        val updated = when {
            current.isBlank() || current == "null" -> component
            current.split(':').contains(component) -> current
            else -> "$current:$component"
        }
        runAll(
            listOf(
                "settings put secure enabled_accessibility_services $updated",
                "settings put secure accessibility_enabled 1"
            )
        )
    }

    // --- UI ------------------------------------------------------------------------------

    @Composable
    private fun Screen() {
        val status = remember(refreshTick) { DeviceValidator.check(this) }
        val notificationsOk = remember(refreshTick) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        val overlayOk = remember(refreshTick) { Settings.canDrawOverlays(this) }
        val accessibilityOk = remember(refreshTick) { isAccessibilityEnabled() }
        val freeformOk = remember(refreshTick) { freeformSettingsApplied() }
        val running by DesktopSession.serviceRunning.collectAsState()
        val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshTick++
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("GalaxyDesktop setup", style = MaterialTheme.typography.headlineMedium)

            if (!status.isSupported) {
                StepCard("This device isn't supported", done = false) {
                    status.rejectionReasons.forEach { Text(it) }
                    Text("Desktop mode is disabled to avoid breaking the system UI.")
                }
                return@Column
            }

            StepCard("1. Permissions", done = notificationsOk && overlayOk && accessibilityOk) {
                PermissionRow("Notifications", notificationsOk, "Allow") {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                PermissionRow("Display over other apps (cursor)", overlayOk, "Allow") {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
                PermissionRow("Accessibility service (clicks & scrolling)", accessibilityOk, "Open settings") {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                if (!accessibilityOk && shizuku == ShizukuState.READY) {
                    OutlinedButton(onClick = ::enableAccessibilityViaShizuku, enabled = !busy) {
                        Text("Enable accessibility automatically")
                    }
                }
            }

            StepCard("2. Freeform windows", done = freeformOk) {
                Text(
                    "Freeform feature: ${if (status.freeformSupported) "present" else "missing"}  •  " +
                        "Settings: ${if (freeformOk) "applied" else "not applied"}"
                )
                when (shizuku) {
                    ShizukuState.READY -> Button(onClick = ::applyFreeformSettings, enabled = !busy) {
                        Text("Apply freeform settings")
                    }
                    ShizukuState.NEEDS_PERMISSION -> Button(onClick = { Shizuku.requestPermission(REQUEST_CODE) }) {
                        Text("Allow Shizuku access")
                    }
                    ShizukuState.NOT_RUNNING, ShizukuState.NOT_INSTALLED -> ShizukuGuide(shizuku == ShizukuState.NOT_INSTALLED)
                }
            }

            StepCard("3. Start", done = running) {
                Text("Start desktop mode, then connect your display (Smart View, HDMI/DisplayLink, or a scrcpy virtual display).")
                if (!overlayOk) Text("Tip: grant \"Display over other apps\" first or the cursor won't show.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) {
                        Button(onClick = { DesktopMonitorService.stop(this@SetupWizardActivity) }) { Text("Stop") }
                    } else {
                        Button(onClick = { DesktopMonitorService.start(this@SetupWizardActivity) }) { Text("Start desktop mode") }
                    }
                    OutlinedButton(onClick = { startActivity(Intent(this@SetupWizardActivity, TrackpadActivity::class.java)) }) {
                        Text("Open trackpad")
                    }
                }
            }

            if (log.isNotEmpty()) {
                StepCard("Last result", done = null) { Text(log, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    @Composable
    private fun ShizukuGuide(notInstalled: Boolean) {
        Text("Shizuku isn't running. Set it up once, entirely on this phone (Wi-Fi required):")
        if (notInstalled) {
            Text("Install the Shizuku app first (Google Play, or github.com/RikkaApps/Shizuku/releases).")
            OutlinedButton(onClick = {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases")))
            }) { Text("Get Shizuku") }
        }
        GuideStep(
            1, "Turn on Developer options (Settings > About phone > Software information, tap Build number 7 times), then enable Wireless debugging.",
            "Open Developer options"
        ) { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        GuideStep(
            2, "In Wireless debugging tap \"Pair device with pairing code\", then type the code into Shizuku's notification.",
            null, null
        )
        GuideStep(
            3, "Open Shizuku and tap \"Start\" under Wireless debugging. Come back here; this screen updates by itself.",
            "Open Shizuku"
        ) {
            packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)?.let(::startActivity)
        }
        Text(
            "Shizuku stops when the phone restarts. Start it again from its app after each reboot.",
            style = MaterialTheme.typography.bodySmall
        )
    }

    @Composable
    private fun GuideStep(number: Int, text: String, actionLabel: String?, action: (() -> Unit)?) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$number. $text")
            if (actionLabel != null && action != null) {
                OutlinedButton(onClick = action) { Text(actionLabel) }
            }
        }
    }

    @Composable
    private fun PermissionRow(label: String, granted: Boolean, actionLabel: String, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            if (granted) Text("✓ Granted") else OutlinedButton(onClick = onClick) { Text(actionLabel) }
        }
    }

    @Composable
    private fun StepCard(title: String, done: Boolean?, content: @Composable ColumnScope.() -> Unit) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (done != null) {
                        Icon(
                            if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = if (done) "Done" else "Not done",
                            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
                content()
            }
        }
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val REQUEST_CODE = 1001
        val FREEFORM_KEYS = listOf(
            "enable_freeform_support",
            "force_resizable_activities",
            "enable_non_resizable_multi_window"
        )
    }
}
