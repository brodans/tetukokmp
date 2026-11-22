package id.tetuko.app.config

object AppConfig {
    // Default local proxy port range used by Tauri/dev server in original app
    const val LOCAL_API_PORT_START = 14200
    const val LOCAL_API_PORT_END = 14205

    // Default base URL for desktop KMP runtime; can be probed/overridden later
    var defaultApiBaseUrl: String = "http://127.0.0.1:$LOCAL_API_PORT_START"

    // Feature flags aligned with Tauri build
    const val AUTOMATION_AVAILABLE = true
}
