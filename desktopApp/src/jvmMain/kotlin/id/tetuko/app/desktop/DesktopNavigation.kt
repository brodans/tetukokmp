package id.tetuko.app.desktop

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Navigation intents from desktop integrations (tray/mini window quick actions).
 */
object DesktopNavigation {
    private val _pageIntents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val pageIntents: SharedFlow<String> = _pageIntents.asSharedFlow()

    fun navigateTo(pageId: String) {
        _pageIntents.tryEmit(pageId)
    }
}
