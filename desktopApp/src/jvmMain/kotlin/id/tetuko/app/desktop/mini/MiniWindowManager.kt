package id.tetuko.app.desktop.mini

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Manager for the TETUKO mini window state (SimpMusic-like mini player pattern).
 */
object MiniWindowManager {
    var isOpen by mutableStateOf(false)

    fun toggle() {
        isOpen = !isOpen
    }
}
