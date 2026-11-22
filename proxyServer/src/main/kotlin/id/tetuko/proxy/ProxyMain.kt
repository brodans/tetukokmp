package id.tetuko.proxy

/**
 * Standalone entry point: `./gradlew :proxyServer:runProxy` (or run the class
 * directly). The desktop app normally embeds the same server, so this is only
 * needed for debugging, CI smoke tests, or a headless deployment.
 */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 14200
    val server = startProxyServer(port)
    println("TETUKO proxy listening on http://127.0.0.1:$port")
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { server.close() } })
    Thread.currentThread().join()
}
