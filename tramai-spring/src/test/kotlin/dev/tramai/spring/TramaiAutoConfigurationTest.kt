package dev.tramai.spring

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import java.net.InetSocketAddress
import kotlin.test.Test

class TramaiAutoConfigurationTest {

    @Test
    fun `registers ai service beans and injects a custom provider bean`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(TramaiAutoConfiguration::class.java),
            )
            .withUserConfiguration(TestApplication::class.java, ProviderConfiguration::class.java)
            .withPropertyValues(
                "tramai.default-provider=stub",
            )

        contextRunner.run { context ->
            assertThat(context).hasSingleBean(TestInvoiceAnalyzer::class.java)
            val analyzer = context.getBean(TestInvoiceAnalyzer::class.java)

            val result = runBlocking { analyzer.analyze("invoice-123") }

            assertThat(result).isEqualTo("spring hello")
        }
    }

    @Test
    fun `creates an openai provider from configuration properties`() {
        var capturedAuthorization = ""
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            capturedAuthorization = exchange.requestHeaders.getFirst("Authorization")
            respond(
                exchange = exchange,
                body = """
                    {
                      "model": "gpt-5.1-chat-latest",
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "openai spring hello"
                          },
                          "finish_reason": "stop"
                        }
                      ]
                    }
                """.trimIndent(),
            )
        }
        server.start()

        try {
            val contextRunner = ApplicationContextRunner()
                .withConfiguration(
                    AutoConfigurations.of(TramaiAutoConfiguration::class.java),
                )
                .withUserConfiguration(TestApplication::class.java)
                .withPropertyValues(
                    "tramai.default-provider=openai",
                    "tramai.models.gpt-5.1-chat-latest=openai",
                    "tramai.providers.openai.apiKey=test-openai-key",
                    "tramai.providers.openai.baseUrl=http://localhost:${server.address.port}/v1",
                )

            contextRunner.run { context ->
                assertThat(context).hasSingleBean(TestInvoiceAnalyzer::class.java)
                assertThat(context.getBean(TramaiProperties::class.java).providers.openai.apiKey).isEqualTo("test-openai-key")
                val analyzer = context.getBean(TestInvoiceAnalyzer::class.java)

                val result = runBlocking { analyzer.analyze("invoice-123") }

                assertThat(capturedAuthorization).isEqualTo("Bearer test-openai-key")
                assertThat(result).isEqualTo("openai spring hello")
            }
        } finally {
            server.stop(0)
        }
    }
}

@AiService
interface TestInvoiceAnalyzer {
    @Operation(
        prompt = "Analyze the invoice",
        model = "gpt-5.1-chat-latest",
    )
    suspend fun analyze(invoiceId: String): String
}

@SpringBootApplication
open class TestApplication

@TestConfiguration
open class ProviderConfiguration {
    @Bean
    open fun stubProvider(): ModelProvider = object : ModelProvider {
        override suspend fun complete(request: ModelRequest): ModelResponse {
            return ModelResponse(content = "spring hello")
        }

        override fun providerId(): String = "stub"
    }
}

private fun respond(
    exchange: HttpExchange,
    body: String,
    status: Int = 200,
) {
    exchange.responseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, body.toByteArray().size.toLong())
    exchange.responseBody.use { it.write(body.toByteArray()) }
}
