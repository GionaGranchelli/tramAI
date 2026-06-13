package dev.tramai.examples.offline

import dev.tramai.core.model.FinishReason
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [ModelProvider] that forwards requests to a local loopback HTTP server.
 *
 * Every call returns the deterministic response "offline-loopback-response".
 * Tracks the number of invocations via [invocationCount].
 */
class LoopbackHttpModelProvider(
    private val loopbackUrl: String,
) : ModelProvider {

    private val client: HttpClient = HttpClient.newHttpClient()

    /** Counter incremented on each [complete] call. */
    val invocationCount: AtomicInteger = AtomicInteger(0)

    override fun providerId(): String = "loopback-local-provider"

    override suspend fun complete(request: ModelRequest): ModelResponse {
        invocationCount.incrementAndGet()

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$loopbackUrl/complete"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.noBody())
            .timeout(java.time.Duration.ofSeconds(5))
            .build()

        val httpResponse = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())

        // Return deterministic response regardless of server echo
        return ModelResponse(
            content = "offline-loopback-response",
            finishReason = FinishReason.STOP,
        )
    }
}
