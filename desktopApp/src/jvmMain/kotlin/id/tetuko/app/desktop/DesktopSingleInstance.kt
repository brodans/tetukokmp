package id.tetuko.app.desktop

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Loopback-only single-instance guard for desktop.
 * A second process forwards route arguments to the existing process and exits.
 */
class DesktopSingleInstance private constructor(
    private val socket: ServerSocket?,
) {
    val acquired: Boolean get() = socket != null

    fun forward(args: Array<String>) {
        runCatching {
            val payload = args.filter { it.startsWith("tetuko://") || it.startsWith("--route=") }
                .joinToString("\n")
            if (payload.isBlank()) return@runCatching
            Socket(InetAddress.getLoopbackAddress(), PORT).use { client ->
                client.soTimeout = 2_000
                client.getOutputStream().write((payload + '\n').toByteArray(Charsets.UTF_8))
            }
        }
    }

    fun close() {
        runCatching { socket?.close() }
    }

    companion object {
        private const val PORT = 18421

        fun acquire(args: Array<String>): DesktopSingleInstance {
            val socket = runCatching {
                ServerSocket(PORT, 1, InetAddress.getLoopbackAddress()).apply {
                    reuseAddress = true
                    soTimeout = 0
                }
            }.getOrNull()
            val result = DesktopSingleInstance(socket)
            if (socket != null) {
                Thread {
                    while (!socket.isClosed) {
                        runCatching {
                            socket.accept().use { client ->
                                val route = client.getInputStream().bufferedReader().use { it.readLine() }
                                if (!route.isNullOrBlank()) {
                                    DesktopNavigation.navigateTo(route.removePrefix("--route=").removePrefix("tetuko://"))
                                    DesktopRestoreSignal.request()
                                }
                            }
                        }
                    }
                }.apply {
                    name = "tetuko-single-instance"
                    isDaemon = true
                    start()
                }
            }
            return result
        }
    }
}
