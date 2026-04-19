package dev.tramai.standalone

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class TramaiTest {

    @Test
    fun `kotlin builder creates suspend service`() {
        val provider = RecordingProvider("anthropic") { ModelResponse(content = "hello") }

        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "anthropic")
        }
        val service = tramai.create<SuspendService>()

        val result = runBlocking { service.respond("world") }

        assertThat(result).isEqualTo("hello")
        assertThat(provider.requests).hasSize(1)
    }

    @Test
    fun `builder supports structured return types`() {
        val provider = RecordingProvider("anthropic") {
            ModelResponse(content = """{"status":"ok"}""")
        }

        val tramai = Tramai {
            provider(provider, default = true)
            model("claude-sonnet-4-20250514", "anthropic")
        }
        val service = tramai.create<StructuredService>()

        val result = runBlocking { service.status("tenant-a") }

        assertThat(result).isEqualTo(Status("ok"))
    }

    @Test
    fun `java style builder creates blocking service`() {
        val provider = RecordingProvider("ollama") { ModelResponse(content = "blocking result") }

        val tramai = Tramai.builder()
            .provider(provider, default = true)
            .model("llama3.2", "ollama")
            .build()
        val service = tramai.create(BlockingService::class)

        assertThat(service.blocking("input")).isEqualTo("blocking result")
    }
}

@AiService
private interface SuspendService {
    @Operation(
        prompt = "Respond with a greeting",
        model = "claude-sonnet-4-20250514",
    )
    suspend fun respond(name: String): String
}

@AiService
private interface StructuredService {
    @Operation(
        prompt = "Return a structured status",
        model = "claude-sonnet-4-20250514",
    )
    suspend fun status(tenantId: String): Status
}

@AiService
private interface BlockingService {
    @Operation(
        prompt = "Return a blocking result",
        model = "llama3.2",
    )
    fun blocking(input: String): String
}

private data class Status(
    val status: String,
)

private class RecordingProvider(
    private val name: String,
    private val responder: suspend (ModelRequest) -> ModelResponse,
) : ModelProvider {
    val requests = mutableListOf<ModelRequest>()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        return responder(request)
    }

    override fun providerId(): String = name
}
