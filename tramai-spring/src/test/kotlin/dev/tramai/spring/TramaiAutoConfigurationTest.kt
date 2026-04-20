package dev.tramai.spring

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.TokenBudgetExceededException
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

    @Test
    fun `creates an openai provider from secret references`() {
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
                            "content": "secret spring hello"
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
                .withUserConfiguration(TestApplication::class.java, SecretResolverConfiguration::class.java)
                .withPropertyValues(
                    "tramai.default-provider=openai",
                    "tramai.models.gpt-5.1-chat-latest=openai",
                    "tramai.providers.openai.apiKeySecretRef=vault:openai/api-key",
                    "tramai.providers.openai.baseUrl=http://localhost:${server.address.port}/v1",
                )

            contextRunner.run { context ->
                val analyzer = context.getBean(TestInvoiceAnalyzer::class.java)

                val result = runBlocking { analyzer.analyze("invoice-123") }

                assertThat(capturedAuthorization).isEqualTo("Bearer resolved-openai-key")
                assertThat(result).isEqualTo("secret spring hello")
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `applies configured fallback routes and circuit breaker settings`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(TramaiAutoConfiguration::class.java),
            )
            .withUserConfiguration(TestApplication::class.java, FallbackProviderConfiguration::class.java)
            .withPropertyValues(
                "tramai.models.gpt-5.1-chat-latest=primary",
                "tramai.fallbacks.gpt-5.1-chat-latest[0].provider=fallback",
                "tramai.fallbacks.gpt-5.1-chat-latest[0].model=gpt-5.1-mini",
                "tramai.resilience.circuit-breaker.enabled=true",
                "tramai.resilience.circuit-breaker.failure-threshold=1",
                "tramai.resilience.circuit-breaker.open-duration-millis=1000",
            )

        contextRunner.run { context ->
            val analyzer = context.getBean(FallbackInvoiceAnalyzer::class.java)
            val primary = context.getBean(PrimaryFailingProvider::class.java)
            val fallback = context.getBean(FallbackSuccessProvider::class.java)

            val first = runBlocking { analyzer.analyze("invoice-123") }
            val second = runBlocking { analyzer.analyze("invoice-456") }

            assertThat(first).isEqualTo("fallback spring hello")
            assertThat(second).isEqualTo("fallback spring hello")
            assertThat(primary.requests).hasSize(1)
            assertThat(fallback.requests).hasSize(2)
            assertThat(fallback.requests.first().model).isEqualTo("gpt-5.1-mini")
        }
    }

    @Test
    fun `applies configured token budget settings`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(TramaiAutoConfiguration::class.java),
            )
            .withUserConfiguration(TestApplication::class.java, ExpensiveProviderConfiguration::class.java)
            .withPropertyValues(
                "tramai.default-provider=expensive",
                "tramai.models.gpt-5.1-chat-latest=expensive",
                "tramai.cost.token-budget.hard-max-tokens-per-attempt=6",
            )

        contextRunner.run { context ->
            val analyzer = context.getBean(TestInvoiceAnalyzer::class.java)

            assertThatThrownBy { runBlocking { analyzer.analyze("invoice-123") } }
                .isInstanceOf(TokenBudgetExceededException::class.java)
        }
    }

    @Test
    fun `applies configured in memory response caching`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(TramaiAutoConfiguration::class.java),
            )
            .withUserConfiguration(TestApplication::class.java, CacheProviderConfiguration::class.java)
            .withPropertyValues(
                "tramai.default-provider=cached",
                "tramai.models.gpt-5.1-chat-latest=cached",
                "tramai.cache.in-memory.enabled=true",
                "tramai.cache.in-memory.max-entries=32",
            )

        contextRunner.run { context ->
            val analyzer = context.getBean(CachedInvoiceAnalyzer::class.java)
            val provider = context.getBean(CachedProvider::class.java)

            val first = runBlocking { analyzer.analyze("invoice-123") }
            val second = runBlocking { analyzer.analyze("invoice-123") }

            assertThat(first).isEqualTo("cached spring hello 1")
            assertThat(second).isEqualTo("cached spring hello 1")
            assertThat(provider.requests).hasSize(1)
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

@AiService
interface CachedInvoiceAnalyzer {
    @Operation(
        prompt = "Analyze the invoice with caching",
        model = "gpt-5.1-chat-latest",
        cacheable = true,
    )
    suspend fun analyze(invoiceId: String): String
}

@AiService
interface FallbackInvoiceAnalyzer {
    @Operation(
        prompt = "Analyze the invoice with fallback routing",
        model = "gpt-5.1-chat-latest",
        providerRetries = 0,
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

@TestConfiguration
open class FallbackProviderConfiguration {
    @Bean
    open fun primaryFailingProvider(): PrimaryFailingProvider = PrimaryFailingProvider()

    @Bean
    open fun fallbackSuccessProvider(): FallbackSuccessProvider = FallbackSuccessProvider()
}

@TestConfiguration
open class ExpensiveProviderConfiguration {
    @Bean
    open fun expensiveProvider(): ModelProvider = object : ModelProvider {
        override suspend fun complete(request: ModelRequest): ModelResponse {
            return ModelResponse(content = "expensive", inputTokens = 3, outputTokens = 4)
        }

        override fun providerId(): String = "expensive"
    }
}

@TestConfiguration
open class SecretResolverConfiguration {
    @Bean
    open fun testSecretValueResolver(): SecretValueResolver = SecretValueResolver { secretRef ->
        when (secretRef) {
            "vault:openai/api-key" -> "resolved-openai-key"
            else -> null
        }
    }
}

@TestConfiguration
open class CacheProviderConfiguration {
    @Bean
    open fun cachedProvider(): CachedProvider = CachedProvider()
}

class PrimaryFailingProvider : ModelProvider {
    val requests = mutableListOf<ModelRequest>()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        throw ProviderException("service unavailable", statusCode = 503, retryable = true)
    }

    override fun providerId(): String = "primary"
}

class FallbackSuccessProvider : ModelProvider {
    val requests = mutableListOf<ModelRequest>()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        return ModelResponse(content = "fallback spring hello")
    }

    override fun providerId(): String = "fallback"
}

class CachedProvider : ModelProvider {
    val requests = mutableListOf<ModelRequest>()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        return ModelResponse(content = "cached spring hello ${requests.size}")
    }

    override fun providerId(): String = "cached"
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
