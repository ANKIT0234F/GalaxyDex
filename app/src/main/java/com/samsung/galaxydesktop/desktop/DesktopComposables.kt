@file:OptIn(ExperimentalFoundationApi::class)

package com.samsung.galaxydesktop.desktop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

val TaskbarHeight = 64.dp
private val PaneShape = RoundedCornerShape(16.dp)
private val GlassFill = Color.White.copy(alpha = 0.12f)
private val GlassBorder = Color.White.copy(alpha = 0.22f)
private val LabelStyle = TextStyle(color = Color.White, fontSize = 13.sp, shadow = Shadow(Color.Black, blurRadius = 6f))

/** UI state shared between the composables and the window that hosts them. */
class DesktopUiState {
    var drawerOpen by mutableStateOf(false)
}

/** Dark theme plus the extra scale for 4K displays (density only; sp follows density). */
@Composable
fun DesktopTheme(uiScale: Float, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val scaled = remember(base, uiScale) { Density(base.density * uiScale, base.fontScale) }
    CompositionLocalProvider(LocalDensity provides scaled) {
        MaterialTheme(colorScheme = darkColorScheme(), content = content)
    }
}

/**
 * Root layout. When no app is running (or the drawer is open) the window is full-screen
 * and shows wallpaper + pinned shortcuts. Otherwise the window shrinks to just the taskbar
 * so freeform apps stay visible and touchable (see DesktopPresentation).
 */
@Composable
fun DesktopScreen(engine: AppLauncherEngine, state: DesktopUiState) {
    val apps by engine.apps.collectAsState()
    val running by engine.running.collectAsState()
    val pinned by engine.pinned.collectAsState()
    val expanded = state.drawerOpen || running.isEmpty()

    LaunchedEffect(engine) { engine.refreshApps() }

    Box(Modifier.fillMaxSize()) {
        if (expanded) {
            Wallpaper()
            DesktopGrid(
                apps = apps.filter { it.id in pinned },
                modifier = Modifier.padding(bottom = TaskbarHeight),
                onLaunch = { engine.launch(it) }
            )
        }
        if (state.drawerOpen) {
            AppDrawer(
                apps = apps,
                pinned = pinned,
                onLaunch = { engine.launch(it); state.drawerOpen = false },
                onTogglePin = engine::togglePin,
                onDismiss = { state.drawerOpen = false }
            )
        }
        Taskbar(
            running = running,
            onDrawerClick = { state.drawerOpen = !state.drawerOpen },
            onRunningClick = { engine.launch(it) },
            onRunningRemove = { engine.markClosed(it.packageName) },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
fun Wallpaper(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF0B1B3A), Color(0xFF1E3C72), Color(0xFF7B4B94), Color(0xFFE5737F))
                )
            )
    )
}

@Composable
fun DesktopGrid(apps: List<AppEntry>, modifier: Modifier = Modifier, onLaunch: (AppEntry) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp)
    ) {
        items(apps, key = { it.id }) { app ->
            AppTile(app, pinned = false, onClick = { onLaunch(app) })
        }
    }
}

@Composable
fun AppTile(app: AppEntry, pinned: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Column(
        Modifier
            .padding(6.dp)
            .clip(PaneShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            Image(app.icon, contentDescription = app.label, modifier = Modifier.size(56.dp))
            if (pinned) {
                Icon(
                    Icons.Rounded.PushPin, contentDescription = "Pinned",
                    tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).size(16.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(app.label, style = LabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Full-screen launcher overlay. Tap an app to open it, long-press to pin it to the desktop. */
@Composable
fun AppDrawer(
    apps: List<AppEntry>,
    pinned: Set<String>,
    onLaunch: (AppEntry) -> Unit,
    onTogglePin: (AppEntry) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .padding(bottom = TaskbarHeight),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.72f)
                .fillMaxHeight(0.82f)
                .clip(PaneShape)
                .background(GlassFill)
                .border(1.dp, GlassBorder, PaneShape)
                .pointerInput(Unit) { detectTapGestures { } } // swallow taps so they don't dismiss
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(24.dp)
            ) {
                items(apps, key = { it.id }) { app ->
                    AppTile(
                        app,
                        pinned = app.id in pinned,
                        onClick = { onLaunch(app) },
                        onLongClick = { onTogglePin(app) }
                    )
                }
            }
        }
    }
}

@Composable
fun Taskbar(
    running: List<AppEntry>,
    onDrawerClick: () -> Unit,
    onRunningClick: (AppEntry) -> Unit,
    onRunningRemove: (AppEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(TaskbarHeight)
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(PaneShape)
                .background(GlassFill)
                .clickable(onClick = onDrawerClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Apps, contentDescription = "App drawer", tint = Color.White, modifier = Modifier.size(28.dp))
        }

        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            running.forEach { app ->
                Column(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .clip(PaneShape)
                        .combinedClickable(onClick = { onRunningClick(app) }, onLongClick = { onRunningRemove(app) })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(app.icon, contentDescription = app.label, modifier = Modifier.size(36.dp))
                    Box(
                        Modifier
                            .padding(top = 2.dp)
                            .size(width = 16.dp, height = 3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.85f))
                    )
                }
            }
        }

        SystemTray()
    }
}

@Composable
fun SystemTray(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current.applicationContext
    var batteryPct by remember { mutableIntStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    var wifiOn by remember { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(ctx) {
        fun readBattery(i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) batteryPct = level * 100 / scale
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) readBattery(intent)
                else nowMs = System.currentTimeMillis()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // Returns the sticky battery intent, so the first reading is immediate.
        ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)?.let(::readBattery)

        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { wifiOn = true }
            override fun onLost(network: Network) { wifiOn = false }
        }
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            callback
        )

        onDispose {
            ctx.unregisterReceiver(receiver)
            cm.unregisterNetworkCallback(callback)
        }
    }

    val zoned = remember(nowMs) { Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()) }
    val time = remember(nowMs) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(zoned) }
    val date = remember(nowMs) { DateTimeFormatter.ofPattern("EEE, d MMM").format(zoned) }

    Row(
        modifier
            .clip(PaneShape)
            .background(GlassFill)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            if (wifiOn) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
            contentDescription = if (wifiOn) "Wi-Fi connected" else "Wi-Fi off",
            tint = Color.White, modifier = Modifier.size(22.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (charging) Icon(Icons.Rounded.Bolt, contentDescription = "Charging", tint = Color.White, modifier = Modifier.size(18.dp))
            Text(if (batteryPct >= 0) "$batteryPct%" else "--", color = Color.White, fontSize = 15.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(time, color = Color.White, fontSize = 15.sp)
            Text(date, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
        }
    }
}

/** Mouse pointer, drawn at [CursorController.position]. Lives in its own click-through overlay window. */
@Composable
fun CursorLayer() {
    val pos by CursorController.position.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                .size(28.dp)
        ) {
            val s = size.width / 24f
            val arrow = Path().apply {
                moveTo(0f, 0f)
                lineTo(0f, 17f * s)
                lineTo(4.5f * s, 13f * s)
                lineTo(8f * s, 21f * s)
                lineTo(11f * s, 19.5f * s)
                lineTo(7.5f * s, 12f * s)
                lineTo(13f * s, 12f * s)
                close()
            }
            drawPath(arrow, Color.White)
            drawPath(arrow, Color.Black, style = Stroke(width = 1.5f * s))
        }
    }
}
