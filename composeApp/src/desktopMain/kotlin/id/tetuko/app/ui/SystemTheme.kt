package id.tetuko.app.ui

actual fun isSystemDarkModeDetected(): Boolean {
    val gtk = System.getenv("GTK_THEME")?.lowercase().orEmpty()
    val colorScheme = System.getenv("COLOR_SCHEME")?.lowercase().orEmpty()
    val osTheme = System.getProperty("sun.desktop")?.lowercase().orEmpty()
    if (colorScheme == "dark" || gtk.contains("dark") || osTheme.contains("dark")) return true
    if (colorScheme == "light" || gtk.contains("light")) return false

    val os = System.getProperty("os.name", "").lowercase()
    if (os.contains("linux")) {
        val gnomeScheme = readThemeSetting("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
            .lowercase()
        if (gnomeScheme.contains("prefer-dark")) return true
        if (gnomeScheme.contains("prefer-light")) return false

        val gtkTheme = readThemeSetting("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme")
            .lowercase()
        if (gtkTheme.contains("dark")) return true
        if (gtkTheme.isNotBlank()) return false

        val kdeScheme = sequenceOf("kreadconfig6", "kreadconfig5").map { command ->
            readThemeSetting(command, "--file", "kdeglobals", "--group", "General", "--key", "ColorScheme")
        }.firstOrNull { it.isNotBlank() }?.lowercase().orEmpty()
        if (kdeScheme.isNotBlank()) return kdeScheme.contains("dark")
    }
    if (os.contains("windows")) {
        return runCatching {
            val process = ProcessBuilder(
                "reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v", "AppsUseLightTheme",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            Regex("AppsUseLightTheme\\s+REG_DWORD\\s+0x([0-9a-f]+)", RegexOption.IGNORE_CASE)
                .find(output)?.groupValues?.getOrNull(1)?.toIntOrNull(16) == 0
        }.getOrDefault(false)
    }
    if (os.contains("mac")) {
        return runCatching {
            val process = ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle").start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.lowercase()
            process.waitFor()
            output.contains("dark")
        }.getOrDefault(false)
    }
    return false
}

private fun readThemeSetting(vararg command: String): String = runCatching {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@runCatching ""
    }
    if (process.exitValue() != 0) return@runCatching ""
    process.inputStream.bufferedReader().use { it.readText().trim().trim('"', '\'') }
}.getOrDefault("")
