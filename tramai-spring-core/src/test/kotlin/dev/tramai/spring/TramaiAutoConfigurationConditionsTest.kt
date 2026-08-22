package dev.tramai.spring

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.provider.ModelProvider
import dev.tramai.standalone.Tramai
import dev.tramai.spring.TramaiAutoConfigurationTest.CachedInvoiceAnalyzer
import dev.tramai.spring.TramaiAutoConfigurationTest.CachedProvider
import dev.tramai.spring.TramaiAutoConfigurationTest.FixedProvider
import dev.tramai.spring.TramaiAutoConfigurationTest.TestInvoiceAnalyzer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.net.InetSocketAddress
import java.util.function.Supplier
import kotlin.test.Test

/**
 * Characterization of the auto-configuration CONDITIONS and ORDERING rules
 * (Epic 6.3 prep). Every expectation below freezes the CURRENT behavior:
 * conditional bean creation, secret-resolver construction failures, the
 * @ConditionalOnMissingBean tramai() back-off, provider coexistence, the
 * default NoOp cache, and bean-provider visibility at assembly time.
 *
 * Isolation contract: every runner configures EXACTLY
 * AutoConfigurations.of(TramaiAutoConfiguration) plus the beans/properties
 * the scenario declares. No @SpringBootApplication, no component scanning —
 * if a test needs a provider, it registers it as a bean explicitly.
 * @AiService proxies are created via Tramai.create(...) instead of relying
 * on scanned AiService beans, so a fixture moving packages in #261 cannot
 * change what these tests observe.
 */
class TramaiAutoConfigurationConditionsTest {

    @Test
    fun `no providers and no default-provider -- context loads but invoking an AiService fails because no provider is registered for the model`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TramaiAutoConfiguration::class.java))

        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(Tramai::class.java)
            // Premise: there really are no provider beans in the context.
            assertThat(context.getBeansOfType(ModelProvider::class.java)).isEmpty()

            val analyzer = context.getBean(Tramai::class.java).create(TestInvoiceAnalyzer::class)

            assertThatThrownBy { runBlocking { analyzer.analyze("invoice-123") } }
                .isInstanceOf(ConfigurationException::class.java)
                .hasMessageContaining(
                    "No provider is registered for model 'gpt-5.1-chat-latest'. Register the model explicitly or configure a default provider.",
                )
        }
    }

    @Test
    fun `no providers with default-provider=openai -- context fails at startup because the default provider is not registered`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TramaiAutoConfiguration::class.java))
            .withPropertyValues("tramai.default-provider=openai")

        contextRunner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context).getFailure()
                .hasRootCauseInstanceOf(ConfigurationException::class.java)
                .hasRootCauseMessage("Default provider 'openai' is not registered")
        }
    }

    @Test
    fun `openai apiKey and apiKeySecretRef together fail context startup with IllegalStateException`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TramaiAutoConfiguration::class.java))
            .withPropertyValues(
                "tramai.providers.openai.apiKey=test-openai-key",
                "tramai.providers.openai.apiKeySecretRef=vault:openai/api-key",
            )

        contextRunner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context).getFailure()
                .hasRootCauseInstanceOf(IllegalStateException::class.java)
                .hasRootCauseMessage(
                    "tramai.providers.openai.apiKey cannot be configured together with its secret reference",
                )
        }
    }

    @Test
    fun `unresolvable apiKeySecretRef fails context startup with No SecretValueResolver could resolve`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TramaiAutoConfiguration::class.java))
            .withPropertyValues(
                "tramai.providers.openai.apiKeySecretRef=vault:missing-key",
            )

        contextRunner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context).getFailure()
                .hasRootCauseInstanceOf(IllegalStateException::class.java)
                .hasRootCauseMessage(
                    "No SecretValueResolver could resolve 'vault:missing-key' for tramai.providers.openai.apiKey",
                )
        }
    }

    @Test
    fun `vault enabled without baseUrl fails context startup`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TramaiAutoConfiguration::class.java))
            .withPropertyValues("tramai.secrets.vault.enabled=true")

        contextRunner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context).getFailure()
                .hasRootCauseInstanceOf(IllegalStateException::class.java)
                .hasRootCauseMessage(
                    "tramai.secrets.vault.baseUrl must be configured when Vault secret resolution is enabled",
                )
        }
    }

    @Test
    fun `vault enabled with baseUrl but no token fails context startup`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TramaiAutoConfiguration::class.java))
            .withPropertyValues(
                "tramai.secrets.vault.enabled=true",
                "tramai.secrets.vault.base-url=http://localhost:8200",
            )

        contextRunner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context).getFailure()
                .hasRootCauseInstanceOf(IllegalStateException::class.java)
                .hasRootCauseMessage(
                    "tramai.secrets.vault.token must be configured when Vault secret resolution is enabled",
                )
        }
    }

    @Test
    fun `aws-secrets-manager enabled without region fails context startup`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TramaiAutoConfiguration::class.java))
            .withPropertyValues("tramai.secrets.aws-secrets-manager.enabled=true")

        contextRunner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context).getFailure()
                .hasRootCauseInstanceOf(IllegalStateException::class.java)
                .hasRootCauseMessage(
                    "tramai.secrets.aws-secrets-manager.region must be configured when AWS Secrets Manager resolution is enabled",
                )
        }
    }

    @Test
    fun `vault and aws-secrets-manager disabled with no secret config loads the context cleanly`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TramaiAutoConfiguration::class.java))
            // Explicit provider: the scenario's premise is "no secret config",
            // not "no providers at all".
            .withBean("stubProvider", ModelProvider::class.java, Supplier { FixedProvider("stub") })
            .withPropertyValues("tramai.default-provider=stub")

        contextRunner.run { context ->
            // Disabled = zero failure and no built-in secret resolver is instantiated:
            // the tramai bean assembles and invocations reach the provider directly.
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(Tramai::class.java)
            val analyzer = context.getBean(Tramai::class.java).create(TestInvoiceAnalyzer::class)
            assertThat(runBlocking { analyzer.analyze("invoice-123") }).isEqualTo("stub")
        }
    }

    @Test
    fun `user supplied Tramai bean wins over the auto-config conditional tramai bean`() {
        val userTramai = Tramai.builder()
            .provider(FixedProvider("marker"), name = "marker")
            .model("gpt-5.1-chat-latest", "marker")
            .build()

        val contextRunner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TramaiAutoConfiguration::class.java))
            .withBean("userTramai", Tramai::class.java, Supplier { userTramai })

        contextRunner.run { context ->
            // @ConditionalOnMissingBean backs off: exactly one Tramai bean exists and
            // it IS the user's instance (distinguished by its "marker" provider).
            assertThat(context).hasNotFailed()
            assertThat(context.getBeanNamesForType(Tramai::class.java)).hasSize(1)
            assertThat(context.getBean(Tramai::class.java)).isSameAs(userTramai)

            val analyzer = context.getBean(Tramai::class.java).create(TestInvoiceAnalyzer::class)
            assertThat(runBlocking { analyzer.analyze("invoice-123") }).isEqualTo("marker")
        }
    }

    @Test
    fun `openai and ollama property providers coexist and both models route to their provider`() {
        val openAiServer = HttpServer.create(InetSocketAddress(0), 0)
        openAiServer.createContext("/v1/chat/completions") { exchange ->
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
        openAiServer.start()

        val ollamaServer = HttpServer.create(InetSocketAddress(0), 0)
        ollamaServer.createContext("/api/chat") { exchange ->
            respond(
                exchange = exchange,
                body = """
                    {
                      "model": "llama3.2",
                      "message": {
                        "role": "assistant",
                        "content": "ollama spring hello"
                      }
                    }
                """.trimIndent(),
            )
        }
        ollamaServer.start()

        try {
            val contextRunner = ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TramaiAutoConfiguration::class.java))
                .withPropertyValues(
                    "tramai.models.gpt-5.1-chat-latest=openai",
                    "tramai.models.llama3.2=ollama",
                    "tramai.providers.openai.apiKey=test-openai-key",
                    "tramai.providers.openai.baseUrl=http://localhost:${openAiServer.address.port}/v1",
                    "tramai.providers.ollama.baseUrl=http://localhost:${ollamaServer.address.port}",
                )

            contextRunner.run { context ->
                assertThat(context).hasNotFailed()
                val tramai = context.getBean(Tramai::class.java)
                val openAiAnalyzer = tramai.create(TestInvoiceAnalyzer::class)
                val ollamaAnalyzer = tramai.create(TestOllamaAnalyzer::class)

                // Both property-backed providers are registered side by side.
                assertThat(runBlocking { openAiAnalyzer.analyze("invoice-123") }).isEqualTo("openai spring hello")
                assertThat(runBlocking { ollamaAnalyzer.analyze("invoice-123") }).isEqualTo("ollama spring hello")
            }
        } finally {
            openAiServer.stop(0)
            ollamaServer.stop(0)
        }
    }

    @Test
    fun `cache disabled by default -- two identical invocations both reach the provider proving the NoOp cache`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TramaiAutoConfiguration::class.java))
            // Explicit provider bean; cache stays at its default (disabled).
            .withBean("cachedProvider", CachedProvider::class.java, Supplier { CachedProvider() })
            .withPropertyValues(
                "tramai.default-provider=cached",
                "tramai.models.gpt-5.1-chat-latest=cached",
                // tramai.cache.in-memory.enabled stays at its default (false)
            )

        contextRunner.run { context ->
            val tramai = context.getBean(Tramai::class.java)
            val analyzer = tramai.create(CachedInvoiceAnalyzer::class)
            val provider = context.getBean(CachedProvider::class.java)

            val first = runBlocking { analyzer.analyze("invoice-123") }
            val second = runBlocking { analyzer.analyze("invoice-123") }

            // NoOp default cache: identical invocations both reach the provider bean.
            assertThat(first).isEqualTo("cached spring hello 1")
            assertThat(second).isEqualTo("cached spring hello 2")
            assertThat(provider.requests).hasSize(2)
        }
    }

    @Test
    fun `user registered ModelProvider beans are all visible to the tramai bean at assembly`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TramaiAutoConfiguration::class.java))
            .withBean("alphaProvider", ModelProvider::class.java, Supplier { FixedProvider("alpha") })
            .withBean("betaProvider", ModelProvider::class.java, Supplier { FixedProvider("beta") })
            .withPropertyValues(
                "tramai.models.gpt-5.1-chat-latest=alpha",
                "tramai.models.llama3.2=beta",
            )

        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            val tramai = context.getBean(Tramai::class.java)
            val openAiAnalyzer = tramai.create(TestInvoiceAnalyzer::class)
            val ollamaAnalyzer = tramai.create(TestOllamaAnalyzer::class)

            // Both bean providers were visible when the tramai bean was assembled.
            assertThat(runBlocking { openAiAnalyzer.analyze("invoice-123") }).isEqualTo("alpha")
            assertThat(runBlocking { ollamaAnalyzer.analyze("invoice-123") }).isEqualTo("beta")
        }
    }
}

@AiService
interface TestOllamaAnalyzer {
    @Operation(
        prompt = "Analyze the invoice with ollama",
        model = "llama3.2",
    )
    suspend fun analyze(invoiceId: String): String
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
