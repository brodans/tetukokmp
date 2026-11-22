package id.tetuko.app.desktop

import java.awt.SystemTray
import java.awt.TrayIcon

/**
 * Desktop notifications for TETUKO (SimpMusic-like user feedback).
 * Prefers system tray balloon notifications when available.
 */
object DesktopNotifications {
    @Volatile
    private var trayIcon: TrayIcon? = null

    fun attachTrayIcon(icon: TrayIcon?) {
        trayIcon = icon
    }

    fun notify(title: String = "TETUKO", message: String, type: TrayIcon.MessageType = TrayIcon.MessageType.INFO) {
        val icon = trayIcon
        if (icon != null && SystemTray.isSupported()) {
            runCatching { icon.displayMessage(title, message, type) }
        } else {
            // Best-effort fallback: no-op if tray unavailable (avoid crashing headless/unsupported envs)
            println("[TETUKO Notification][$type] $title: $message")
        }
    }

    fun success(message: String) = notify(message = message, type = TrayIcon.MessageType.INFO)
    fun info(message: String) = notify(message = message, type = TrayIcon.MessageType.INFO)
    fun warn(message: String) = notify(message = message, type = TrayIcon.MessageType.WARNING)
    fun error(message: String) = notify(message = message, type = TrayIcon.MessageType.ERROR)
}
