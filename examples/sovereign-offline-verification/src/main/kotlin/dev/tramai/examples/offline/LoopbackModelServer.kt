package dev.tramai.examples.offline

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * A tiny JDK-only HTTP loopback server bound to 127.0.0.1 on an ephemeral port.
 *
 * Serves a deterministic POST /complete endpoint that returns
 * the plain-text response "offline-loopback-echo".
 */
class LoopbackModelServer : AutoCloseable {

    private val server: HttpServer = HttpServer.create(
        InetSocketAddress("127.0.0.1", 0),
        0,
    )

    /** The ephemeral port the server is bound to. */
    val port: Int
        get() = server.address.port

    /** Full URL string for the loopback server, e.g. "http://127.0.0.1:34567". */
    val url: String
        get() = "http://127.0.0.1:$port"

    init {
        server.createContext("/complete") { exchange ->
            try {
                // Read and discard the request body
                exchange.requestBody.readAllBytes()

                val response = "offline-loopback-echo"
                exchange.sendResponseHeaders(200, response.length.toLong())
                exchange.responseBody.write(response.toByteArray())
            } finally {
                exchange.close()
            }
        }
        server.executor = null // use the default server executor (daemon thread pool)
        server.start()
    }

    override fun close() {
        server.stop(0) // 0 = immediate shutdown
    }
}
