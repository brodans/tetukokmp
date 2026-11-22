package id.tetuko.app.desktop.mini

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import id.tetuko.app.desktop.DesktopAutomationScheduler
import id.tetuko.app.desktop.DesktopRestoreSignal
import id.tetuko.app.service.AppServices
import id.tetuko.app.ui.AppState
import id.tetuko.app.ui.TetukoTheme
import id.tetuko.core.common.AppIdentity
import id.tetuko.core.domain.model.AppRoute
import java.awt.Cursor
import java.awt.Dimension
import java.awt.MouseInfo
import java.util.prefs.Preferences
import kotlinx.coroutines.delay

@Composable
fun MiniWindow(
    appState: AppState,
    onCloseRequest: () -> Unit,
) {
    val prefs = remember { Preferences.userRoot().node("TETUKO/MiniWindow") }
    val minWidth = 320f
    val minHeight = 140f
    val savedX = prefs.getFloat("windowX", Float.NaN)
    val savedY = prefs.getFloat("windowY", Float.NaN)
    val savedWidth = prefs.getFloat("windowWidth", 380f).coerceAtLeast(minWidth)
    val savedHeight = prefs.getFloat("windowHeight", 170f).coerceAtLeast(minHeight)

    val windowState = remember {
        WindowState(
            position = if (savedX.isNaN() || savedY.isNaN()) WindowPosition(Alignment.BottomEnd)
            else WindowPosition(savedX.coerceAtLeast(0f).dp, savedY.coerceAtLeast(0f).dp),
            size = DpSize(savedWidth.dp, savedHeight.dp),
        )
    }

    LaunchedEffect(windowState.position, windowState.size) {
        val pos = windowState.position
        if (pos is WindowPosition.Absolute) {
            prefs.putFloat("windowX", pos.x.value)
            prefs.putFloat("windowY", pos.y.value)
        }
        prefs.putFloat("windowWidth", windowState.size.width.value)
        prefs.putFloat("windowHeight", windowState.size.height.value)
    }

    Window(
        onCloseRequest = onCloseRequest,
        title = "${AppIdentity.APP_NAME} - Mini",
        alwaysOnTop = true,
        undecorated = true,
        transparent = true,
        resizable = true,
        state = windowState,
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(
                (minWidth * window.graphicsConfiguration.defaultTransform.scaleX).toInt(),
                (minHeight * window.graphicsConfiguration.defaultTransform.scaleY).toInt(),
            )
        }

        TetukoTheme(darkTheme = appState.isDarkMode) {
            MiniWindowContent(appState = appState, windowState = windowState, onClose = onCloseRequest)
        }
    }
}

@Composable
private fun MiniWindowContent(
    appState: AppState,
    windowState: WindowState,
    onClose: () -> Unit,
) {
    var dragStartMousePos by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var dragStartWindowPos by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var schedulerEnabled by remember { mutableStateOf(AppServices.isAutomationSchedulerEnabled()) }
    var schedulerPaused by remember { mutableStateOf(DesktopAutomationScheduler.isPaused()) }

    LaunchedEffect(Unit) {
        while (true) {
            schedulerEnabled = AppServices.isAutomationSchedulerEnabled()
            schedulerPaused = DesktopAutomationScheduler.isPaused()
            delay(15_000)
        }
    }

    val route = appState.route
    val statusTitle = when (route) {
        is AppRoute.Masuk -> "Belum masuk aplikasi"
        is AppRoute.Login -> "Pilih / login server"
        is AppRoute.App -> appState.currentProfile?.label ?: "TETUKO"
    }
    val statusDetail = when (route) {
        is AppRoute.Masuk -> "Silakan login di jendela utama"
        is AppRoute.Login -> appState.pendingProfile?.nama ?: "Pilih profil server presensi"
        is AppRoute.App -> {
            val page = appState.currentProfile?.let { p ->
                id.tetuko.core.domain.model.AppNavigation.pagesFor(p).firstOrNull { it.id == appState.activePageId }?.label
            } ?: appState.activePageId
            "${appState.serverDisplayName} • $page"
        }
    }
    val schedColor = when {
        !schedulerEnabled -> Color(0xFF94A3B8)
        schedulerPaused -> Color(0xFFF59E0B)
        else -> Color(0xFF22C55E)
    }
    val schedText = when {
        !schedulerEnabled -> "Scheduler mati"
        schedulerPaused -> "Scheduler dijeda"
        else -> "Scheduler aktif"
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(14.dp).padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (route is AppRoute.App) Color(0xFF22C55E) else Color(0xFFF59E0B)))
                    Spacer(Modifier.width(8.dp))
                    Text(AppIdentity.APP_NAME, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Filled.Schedule, null, tint = schedColor, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(schedText, fontSize = 11.sp, color = schedColor, maxLines = 1)
                }

                Text(statusTitle, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(statusDetail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)

                Spacer(Modifier.weight(1f))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = { DesktopRestoreSignal.request() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Buka", fontSize = 12.sp)
                    }

                    if (route is AppRoute.App) {
                        OutlinedButton(
                            onClick = { id.tetuko.app.desktop.DesktopNavigation.navigateTo("preset"); DesktopRestoreSignal.request() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.height(32.dp)
                        ) { Text("Preset", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = { id.tetuko.app.desktop.DesktopNavigation.navigateTo("log"); DesktopRestoreSignal.request() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.height(32.dp)
                        ) { Text("Log", fontSize = 12.sp) }
                    }
                }
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Tutup", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(0.55f)
                    .height(26.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                val mousePos = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                                dragStartMousePos = Pair(mousePos.x, mousePos.y)
                                val currentPos = windowState.position
                                if (currentPos is WindowPosition.Absolute) {
                                    dragStartWindowPos = Pair(currentPos.x.value, currentPos.y.value)
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val startMouse = dragStartMousePos
                                val startWindow = dragStartWindowPos
                                if (startMouse != null && startWindow != null) {
                                    val currentMousePos = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                                    val deltaX = currentMousePos.x - startMouse.first
                                    val deltaY = currentMousePos.y - startMouse.second
                                    windowState.position = WindowPosition(
                                        (startWindow.first + deltaX).dp,
                                        (startWindow.second + deltaY).dp,
                                    )
                                }
                            },
                            onDragEnd = {
                                dragStartMousePos = null
                                dragStartWindowPos = null
                            },
                        )
                    }
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.MOVE_CURSOR))),
            )
        }
    }
}
