package dev.tramai.examples.offline

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation

/**
 * Minimal service interface for sovereign offline verification.
 *
 * Calls the "offline-test-model" via the loopback provider and returns
 * a deterministic response.
 */
@AiService
fun interface OfflineEchoService {

    @Operation(
        prompt = "Return a deterministic offline response for: {input}",
        model = "offline-test-model",
    )
    suspend fun echo(input: String): String
}
