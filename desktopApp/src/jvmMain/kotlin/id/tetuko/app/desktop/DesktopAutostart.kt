package id.tetuko.app.desktop

import id.tetuko.core.common.AppIdentity
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Desktop autostart integration. Linux: XDG autostart .desktop entry.
 * Windows: HKCU Run registry entry. macOS: LaunchAgent plist.
 */
object DesktopAutostart {
    private enum class Os { LINUX, WINDOWS, MAC, OTHER }

    private fun os(): Os {
        val name = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        return when {
            name.contains("win") -> Os.WINDOWS
            name.contains("mac") || name.contains("darwin") -> Os.MAC
            name.contains("nux") || name.contains("nix") -> Os.LINUX
            else -> Os.OTHER
        }
    }

    fun isSupported(): Boolean = os() != Os.OTHER

    fun isEnabled(): Boolean = runCatching {
        when (os()) {
            Os.LINUX -> Files.exists(linuxDesktopFile())
            Os.WINDOWS -> windowsIsEnabled()
            Os.MAC -> Files.exists(macPlistFile())
            Os.OTHER -> false
        }
    }.getOrDefault(false)

    fun setEnabled(enabled: Boolean): Boolean = runCatching {
        if (enabled) enable() else disable()
    }.getOrDefault(false)

    fun enable(): Boolean = when (os()) {
        Os.LINUX -> linuxEnable()
        Os.WINDOWS -> windowsEnable()
        Os.MAC -> macEnable()
        Os.OTHER -> false
    }

    fun disable(): Boolean = when (os()) {
        Os.LINUX -> linuxDisable()
        Os.WINDOWS -> windowsDisable()
        Os.MAC -> macDisable()
        Os.OTHER -> false
    }

    // --- Linux XDG ---
    private fun linuxAutostartDir(): Path =
        Path.of(System.getProperty("user.home"), ".config", "autostart")

    private fun linuxDesktopFile(): Path =
        linuxAutostartDir().resolve("tetuko-kmp.desktop")

    private fun linuxEnable(): Boolean = runCatching {
        Files.createDirectories(linuxAutostartDir())
        val exec = resolveExecCommand()
        val icon = resolveIconPath()
        val content = buildString {
            appendLine("[Desktop Entry]")
            appendLine("Type=Application")
            appendLine("Version=1.0")
            appendLine("Name=${AppIdentity.APP_NAME}")
            appendLine("Comment=${AppIdentity.APP_FULL_NAME}")
            appendLine("Exec=$exec")
            if (icon.isNotBlank()) appendLine("Icon=$icon")
            appendLine("Terminal=false")
            appendLine("Categories=Office;Utility;")
            appendLine("StartupNotify=false")
            appendLine("X-GNOME-Autostart-enabled=true")
        }
        Files.writeString(linuxDesktopFile(), content)
        runCatching { linuxDesktopFile().toFile().setExecutable(false) }
        true
    }.getOrDefault(false)

    private fun linuxDisable(): Boolean = runCatching {
        Files.deleteIfExists(linuxDesktopFile())
        true
    }.getOrDefault(false)

    // --- Windows Registry (HKCU Run) ---
    private fun windowsRunKey(): String = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private fun windowsValueName(): String = "TETUKO"

    private fun windowsIsEnabled(): Boolean = runCatching {
        val proc = ProcessBuilder("reg", "query", windowsRunKey(), "/v", windowsValueName())
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor() == 0 && out.contains(windowsValueName(), ignoreCase = true)
    }.getOrDefault(false)

    private fun windowsEnable(): Boolean = runCatching {
        val exec = resolveExecCommandForWindows()
        val proc = ProcessBuilder("reg", "add", windowsRunKey(), "/v", windowsValueName(), "/t", "REG_SZ", "/d", exec, "/f")
            .redirectErrorStream(true).start()
        proc.waitFor() == 0
    }.getOrDefault(false)

    private fun windowsDisable(): Boolean = runCatching {
        val proc = ProcessBuilder("reg", "delete", windowsRunKey(), "/v", windowsValueName(), "/f")
            .redirectErrorStream(true).start()
        // success or value not found is acceptable for disable
        proc.waitFor() == 0 || !windowsIsEnabled()
    }.getOrDefault(false)

    // --- macOS LaunchAgent ---
    private fun macPlistFile(): Path =
        Path.of(System.getProperty("user.home"), "Library", "LaunchAgents", "id.tetuko.app.desktop.plist")

    private fun macEnable(): Boolean = runCatching {
        val plist = macPlistFile()
        Files.createDirectories(plist.parent)
        val exec = resolveExecArgsForMac()
        val programArgs = exec.joinToString("") { arg ->
            "    <string>${xmlEscape(arg)}</string>\n"
        }
        val content = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>id.tetuko.app.desktop</string>
  <key>ProgramArguments</key>
  <array>
$programArgs  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>ProcessType</key>
  <string>Interactive</string>
</dict>
</plist>
"""
        Files.writeString(plist, content)
        true
    }.getOrDefault(false)

    private fun macDisable(): Boolean = runCatching {
        Files.deleteIfExists(macPlistFile())
        true
    }.getOrDefault(false)

    private fun resolveIconPath(): String {
        val candidates = listOf(
            File(System.getProperty("user.home"), ".tetuko/icon.png"),
            File(System.getProperty("tetuko.resource.dir", "."), "icons/icon.png"),
            File(System.getProperty("tetuko.resource.dir", "."), "app-resources/icons/icon.png"),
            File("app-resources/icons/icon.png"),
            File("app-resources/icons/128x128.png"),
            File("app-resources/icons/32x32.png"),
        )
        return candidates.firstOrNull { it.exists() }?.absolutePath ?: ""
    }

    private fun resolveExecCommand(): String {
        val appImage = System.getenv("APPIMAGE")
        if (!appImage.isNullOrBlank() && File(appImage).exists()) {
            return quote(appImage) + " --minimized"
        }
        val command = resolveCurrentCommand()
        if (command != null) return command.joinToString(" ") { quote(it) } + " --minimized"
        val cwd = File(System.getProperty("user.dir", ".")).absolutePath
        return "/bin/sh -lc ${quote("cd ${quoteSimple(cwd)} && ./gradlew :desktopApp:run --args='--minimized'")}"
    }

    private fun resolveExecCommandForWindows(): String {
        val command = resolveCurrentCommand()
        if (command != null) return (command + "--minimized").joinToString(" ") { windowsQuote(it) }
        val exe = ProcessHandle.current().info().command().orElse(null)
        if (!exe.isNullOrBlank()) return windowsQuote(exe) + " --minimized"
        return windowsQuote("javaw") + " -jar " + windowsQuote(File("tetuko.jar").absolutePath) + " --minimized"
    }

    private fun resolveExecArgsForMac(): List<String> {
        val command = resolveCurrentCommand()
        if (command != null) return command + "--minimized"
        val exe = ProcessHandle.current().info().command().orElse(null)
        if (!exe.isNullOrBlank()) return listOf(exe, "--minimized")
        return listOf("/usr/bin/open", "-a", AppIdentity.APP_NAME)
    }

    private fun resolveCurrentCommand(): List<String>? {
        val info = runCatching { ProcessHandle.current().info() }.getOrNull() ?: return null
        val cmd = info.command().orElse(null) ?: return null
        val args = info.arguments().orElse(emptyArray())?.toList().orEmpty()
        val exeName = File(cmd).name.lowercase(Locale.ROOT)
        if (exeName.contains("tetuko") || exeName.endsWith(".appimage") || exeName.endsWith(".exe")) {
            return listOf(cmd) + args
        }
        if (exeName == "java" || exeName == "javaw" || exeName == "java.exe" || exeName == "javaw.exe") {
            val joined = args.joinToString(" ")
            if (joined.contains("-jar") || joined.contains("desktopApp")) {
                return listOf(cmd) + args
            }
        }
        return null
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun windowsQuote(s: String): String = if (s.any { it.isWhitespace() || it == '"' }) "\"" + s.replace("\"", "\\\"") + "\"" else s
    private fun quoteSimple(s: String): String = s.replace("'", "'\\''")
    private fun xmlEscape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
