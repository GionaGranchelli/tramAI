package dev.tramai.engine.planning

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import dev.tramai.engine.TramaiEngine
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EnginePlanningIntegrationTest {

    @Test
    fun `registered and created services execute through compiled operation plans`() {
        val provider = RecordingProvider()
        val engine = TramaiEngine(provider)
        engine.registerService(EnginePlanningService::class)
        val service = engine.create(EnginePlanningService::class)

        val result = runBlocking { service.echo("value") }

        assertThat(result).isEqualTo("planned response")
        assertThat(provider.requests.single().operationInterface).isEqualTo(EnginePlanningService::class.java.name)
        assertThat(provider.requests.single().operationMethod).isEqualTo("echo")
        engine.close()
    }

    @Test
    fun `repeated compilation of the same service yields equal plans`() {
        val compiler = ServiceDefinitionCompiler(
            OperationDefinitionCompiler(
                toolRegistry = dev.tramai.engine.ToolRegistry(),
                promptSanitizer = null,
                fingerprintFactory = OperationFingerprintFactory(),
            ),
        )
        val first = compiler.compile(EnginePlanningService::class)
        val second = compiler.compile(EnginePlanningService::class)

        assertThat(second.operations.keys).isEqualTo(first.operations.keys)
        assertThat(second.operations.values.map { it.definition })
            .containsExactlyElementsOf(first.operations.values.map { it.definition })
        assertThat(second.operations.values.map { it.fingerprint })
            .containsExactlyElementsOf(first.operations.values.map { it.fingerprint })
    }

    private class RecordingProvider : ModelProvider {
        val requests = mutableListOf<ModelRequest>()

        override suspend fun complete(request: ModelRequest): ModelResponse {
            requests += request
            return ModelResponse(content = "planned response")
        }
    }

    @AiService
    private interface EnginePlanningService {
        @Operation(prompt = "Echo", model = "test-model")
        suspend fun echo(input: String): String
    }
}
