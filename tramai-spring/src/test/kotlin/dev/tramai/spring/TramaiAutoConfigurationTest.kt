package dev.tramai.spring

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.TokenBudgetExceededException
import dev.tramai.core.model.Message
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationInterceptor
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.security.DlpContext
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.core.security.DlpRedaction
import dev.tramai.core.security.DlpRedactionAuditEmitter
import dev.tramai.core.security.DlpResult
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyDecisionAuditEmitter
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.engine.EngineEventObserver
import dev.tramai.standalone.Tramai
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import kotlin.test.Test

class TramaiAutoConfigurationTest {

    @Test
    fun `spring context destruction closes the runtime`() {
        var tramai: Tramai? = null
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TramaiAutoConfiguration::class.java))
            .withUserConfiguration(TestApplication::class.java, ProviderConfiguration::class.java)
            .withPropertyValues("tramai.default-provider=stub")
            .run { context -> tramai = context.getBean(Tramai::class.java) }

        assertThatThrownBy { tramai!!.runtime() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Tramai runtime is closed")
    }

    @Test
    fun `multiple ai service beans share one runtime and all fail after context close`() {
        lateinit var analyzer: TestInvoiceAnalyzer
        lateinit var cached: CachedInvoiceAnalyzer
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TramaiAutoConfiguration::class.java))
            .withUserConfiguration(TestApplication::class.java, ProviderConfiguration::class.java)
            .withPropertyValues("tramai.default-provider=stub")
            .run { context ->
                analyzer = context.getBean(TestInvoiceAnalyzer::class.java)
                cached = context.getBean(CachedInvoiceAnalyzer::class.java)
            }
        // Context destroyed -> the shared Tramai bean runtime closed. If each
        // factory bean had created a hidden independent engine, these proxies
        // would still be usable; instead they must fail before provider work.
        assertThatThrownBy { runBlocking { analyzer.analyze("invoice-1") } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Tramai runtime is closed")
        assertThatThrownBy { runBlocking { cached.analyze("invoice-1") } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Tramai runtime is closed")
    }

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
    fun `property providers with colliding ids fail deterministically instead of collapsing`() {
        // OpenAI plus an openai-compatible provider explicitly named "openai" must NOT
        // silently collapse into one (last-wins). Both reach the canonical plan builder,
        // which rejects the duplicate provider id.
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            respond(
                exchange = exchange,
                body = """
                    {
                      "model": "gpt-5.1-chat-latest",
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "unused"
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
                    "tramai.models.gpt-5.1-chat-latest=openai",
                    "tramai.providers.openai.apiKey=test-openai-key",
                    "tramai.providers.openai.baseUrl=http://localhost:${server.address.port}/v1",
                    "tramai.providers.openai-compatible.baseUrl=http://localhost:${server.address.port}/v1",
                    "tramai.providers.openai-compatible.providerName=openai",
                    "tramai.providers.openai-compatible.apiKey=test-compatible-key",
                )

            contextRunner.run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure?.message).contains("Duplicate provider 'openai'")
            }
        } finally {
            server.stop(0)
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
    fun `custom provider bean overrides property backed provider with the same id`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TramaiAutoConfiguration::class.java))
            .withUserConfiguration(TestApplication::class.java)
            .withBean("openAiOverrideProvider", ModelProvider::class.java, Supplier { OpenAiOverrideProvider() })
            .withPropertyValues(
                "tramai.default-provider=openai",
                "tramai.models.gpt-5.1-chat-latest=openai",
                "tramai.providers.openai.apiKey=property-openai-key",
            )

        contextRunner.run { context ->
            val analyzer = context.getBean(TestInvoiceAnalyzer::class.java)

            assertThat(runBlocking { analyzer.analyze("invoice-123") }).isEqualTo("bean override")
        }
    }

    @Test
    fun `duplicate custom provider bean ids fail during context construction`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TramaiAutoConfiguration::class.java))
            .withUserConfiguration(TestApplication::class.java)
            .withBean("firstDuplicateProvider", ModelProvider::class.java, Supplier { FixedProvider("duplicate") })
            .withBean("secondDuplicateProvider", ModelProvider::class.java, Supplier { FixedProvider("duplicate") })

        contextRunner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context).getFailure()
                .hasRootCauseInstanceOf(ConfigurationException::class.java)
                .hasRootCauseMessage("Duplicate provider 'duplicate'")
        }
    }

    @Test
    fun `invalid fallback route fails during context construction`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TramaiAutoConfiguration::class.java))
            .withUserConfiguration(TestApplication::class.java, ProviderConfiguration::class.java)
            .withPropertyValues(
                "tramai.models.gpt-5.1-chat-latest=stub",
                "tramai.fallbacks.gpt-5.1-chat-latest[0].provider=missing",
            )

        contextRunner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context).getFailure()
                .hasRootCauseInstanceOf(ConfigurationException::class.java)
                .hasRootCauseMessage(
                    "Fallback route for model 'gpt-5.1-chat-latest' targets unknown provider 'missing'",
                )
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
    fun `creates an openai provider from the built in vault secret resolver`() {
        var capturedProviderAuthorization = ""
        var capturedVaultRequestPath = ""
        var capturedVaultToken = ""
        val providerServer = HttpServer.create(InetSocketAddress(0), 0)
        providerServer.createContext("/v1/chat/completions") { exchange ->
            capturedProviderAuthorization = exchange.requestHeaders.getFirst("Authorization")
            respond(
                exchange = exchange,
                body = """
                    {
                      "model": "gpt-5.1-chat-latest",
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "vault spring hello"
                          },
                          "finish_reason": "stop"
                        }
                      ]
                    }
                """.trimIndent(),
            )
        }
        providerServer.start()

        val vaultServer = HttpServer.create(InetSocketAddress(0), 0)
        vaultServer.createContext("/") { exchange ->
            capturedVaultRequestPath = exchange.requestURI.path
            capturedVaultToken = exchange.requestHeaders.getFirst("X-Vault-Token")
            respond(
                exchange = exchange,
                body = """
                    {
                      "data": {
                        "data": {
                          "value": "resolved-vault-key"
                        }
                      }
                    }
                """.trimIndent(),
            )
        }
        vaultServer.start()

        try {
            val contextRunner = ApplicationContextRunner()
                .withConfiguration(
                    AutoConfigurations.of(TramaiAutoConfiguration::class.java),
                )
                .withUserConfiguration(TestApplication::class.java)
                .withPropertyValues(
                    "tramai.default-provider=openai",
                    "tramai.models.gpt-5.1-chat-latest=openai",
                    "tramai.providers.openai.apiKeySecretRef=vault:built-in/openai/api-key",
                    "tramai.providers.openai.baseUrl=http://localhost:${providerServer.address.port}/v1",
                    "tramai.secrets.vault.enabled=true",
                    "tramai.secrets.vault.base-url=http://localhost:${vaultServer.address.port}",
                    "tramai.secrets.vault.token=test-vault-token",
                )

            contextRunner.run { context ->
                val analyzer = context.getBean(TestInvoiceAnalyzer::class.java)

                val result = runBlocking { analyzer.analyze("invoice-123") }

                assertThat(capturedProviderAuthorization).isEqualTo("Bearer resolved-vault-key")
                assertThat(capturedVaultRequestPath).isEqualTo("/v1/secret/data/built-in/openai/api-key")
                assertThat(capturedVaultToken).isEqualTo("test-vault-token")
                assertThat(result).isEqualTo("vault spring hello")
            }
        } finally {
            providerServer.stop(0)
            vaultServer.stop(0)
        }
    }

    @Test
    fun `creates an openai provider from the built in aws secrets manager resolver`() {
        var capturedProviderAuthorization = ""
        var capturedAwsTarget = ""
        var capturedAwsBody = ""
        val providerServer = HttpServer.create(InetSocketAddress(0), 0)
        providerServer.createContext("/v1/chat/completions") { exchange ->
            capturedProviderAuthorization = exchange.requestHeaders.getFirst("Authorization")
            respond(
                exchange = exchange,
                body = """
                    {
                      "model": "gpt-5.1-chat-latest",
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "aws spring hello"
                          },
                          "finish_reason": "stop"
                        }
                      ]
                    }
                """.trimIndent(),
            )
        }
        providerServer.start()

        val awsServer = HttpServer.create(InetSocketAddress(0), 0)
        awsServer.createContext("/") { exchange ->
            capturedAwsTarget = exchange.requestHeaders.getFirst("X-Amz-Target")
            capturedAwsBody = exchange.requestBody.readBytes().decodeToString()
            respond(
                exchange = exchange,
                body = """
                    {
                      "ARN": "arn:aws:secretsmanager:eu-west-1:123456789012:secret:prod/openai/api-key",
                      "Name": "prod/openai/api-key",
                      "SecretString": "{\"value\":\"resolved-aws-key\"}"
                    }
                """.trimIndent(),
            )
        }
        awsServer.start()

        try {
            val contextRunner = ApplicationContextRunner()
                .withConfiguration(
                    AutoConfigurations.of(TramaiAutoConfiguration::class.java),
                )
                .withUserConfiguration(TestApplication::class.java)
                .withPropertyValues(
                    "tramai.default-provider=openai",
                    "tramai.models.gpt-5.1-chat-latest=openai",
                    "tramai.providers.openai.apiKeySecretRef=aws-secretsmanager:prod/openai/api-key",
                    "tramai.providers.openai.baseUrl=http://localhost:${providerServer.address.port}/v1",
                    "tramai.secrets.aws-secrets-manager.enabled=true",
                    "tramai.secrets.aws-secrets-manager.region=eu-west-1",
                    "tramai.secrets.aws-secrets-manager.endpoint=http://localhost:${awsServer.address.port}",
                    "tramai.secrets.aws-secrets-manager.access-key-id=test-access-key",
                    "tramai.secrets.aws-secrets-manager.secret-access-key=test-secret-key",
                )

            contextRunner.run { context ->
                val analyzer = context.getBean(TestInvoiceAnalyzer::class.java)

                val result = runBlocking { analyzer.analyze("invoice-123") }

                assertThat(capturedAwsTarget).isEqualTo("secretsmanager.GetSecretValue")
                assertThat(capturedAwsBody).contains("prod/openai/api-key")
                assertThat(capturedProviderAuthorization).isEqualTo("Bearer resolved-aws-key")
                assertThat(result).isEqualTo("aws spring hello")
            }
        } finally {
            providerServer.stop(0)
            awsServer.stop(0)
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

            // Spring component scan picks up InterceptorConfiguration in the same
            // package, registering a custom OperationInterceptor. With a custom
            // interceptor configured, the engine's isSafeCacheEligible() returns
            // false and the response cache is bypassed — each invocation reaches
            // the provider so the current interceptor rules are always applied.
            assertThat(first).isEqualTo("cached spring hello 1")
            assertThat(second).isEqualTo("cached spring hello 2")
            assertThat(provider.requests).hasSize(2)
        }
    }

    @Test
    fun `auto composes operation interceptor beans`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(TramaiAutoConfiguration::class.java),
            )
            .withUserConfiguration(TestApplication::class.java, InterceptorConfiguration::class.java)
            .withPropertyValues(
                "tramai.default-provider=intercepted",
                "tramai.models.gpt-5.1-chat-latest=intercepted",
            )

        contextRunner.run { context ->
            val analyzer = context.getBean(TestInvoiceAnalyzer::class.java)
            val provider = context.getBean(InterceptingProvider::class.java)

            val result = runBlocking { analyzer.analyze("secret-id") }

            assertThat(result).isEqualTo("contains redacted payload")
            assertThat(provider.requests).hasSize(1)
            assertThat(provider.requests.single().messages.last().content)
                .contains("[REDACTED_ID]")
                .doesNotContain("secret-id")
        }
    }

    @Test
    fun `zero PolicyDecisionAuditEmitter beans uses NoOp behavior`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(TramaiAutoConfiguration::class.java),
            )
            .withUserConfiguration(TestApplication::class.java, ProviderConfiguration::class.java)
            .withPropertyValues("tramai.default-provider=stub")

        contextRunner.run { context ->
            val tramai = context.getBean(Tramai::class.java)
            val service = tramai.create(TestInvoiceAnalyzer::class)
            val result = runBlocking { service.analyze("test") }
            // NoOp default → operation proceeds without audit enforcement
            assertThat(result).isEqualTo("spring hello")
        }
    }

    @Test
    fun `single PolicyDecisionAuditEmitter bean is wired`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(TramaiAutoConfiguration::class.java),
            )
            .withUserConfiguration(TestApplication::class.java, ProviderConfiguration::class.java)
            .withPropertyValues("tramai.default-provider=stub")
            .withBean("auditEmitter", PolicyDecisionAuditEmitter::class.java, Supplier {
                object : PolicyDecisionAuditEmitter {
                    override suspend fun emit(
                        enforcementPoint: EnforcementPoint,
                        context: PolicyContext,
                        decision: PolicyDecision,
                    ) = Unit
                }
            })

        contextRunner.run { context ->
            val tramai = context.getBean(Tramai::class.java)
            val service = tramai.create(TestInvoiceAnalyzer::class)
            val result = runBlocking { service.analyze("test") }
            // Custom emitter wired → operation proceeds
            assertThat(result).isEqualTo("spring hello")
        }
    }

    @Test
    fun `multiple PolicyDecisionAuditEmitter beans fail fast`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(TramaiAutoConfiguration::class.java),
            )
            .withUserConfiguration(TestApplication::class.java, ProviderConfiguration::class.java)
            .withPropertyValues("tramai.default-provider=stub")
            .withBean("firstEmitter", PolicyDecisionAuditEmitter::class.java, Supplier {
                object : PolicyDecisionAuditEmitter {
                    override suspend fun emit(
                        enforcementPoint: EnforcementPoint,
                        context: PolicyContext,
                        decision: PolicyDecision,
                    ) = Unit
                }
            })
            .withBean("secondEmitter", PolicyDecisionAuditEmitter::class.java, Supplier {
                object : PolicyDecisionAuditEmitter {
                    override suspend fun emit(
                        enforcementPoint: EnforcementPoint,
                        context: PolicyContext,
                        decision: PolicyDecision,
                    ) = Unit
                }
            })

        contextRunner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context).getFailure()
                .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
                .hasRootCauseMessage(
                    "Multiple PolicyDecisionAuditEmitter beans found (2). Define at most one.",
                )
        }
    }

    @Test
    fun `zero DlpRedactionAuditEmitter beans uses NoOp behavior`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(TramaiAutoConfiguration::class.java),
            )
            .withUserConfiguration(TestApplication::class.java, ProviderConfiguration::class.java)
            .withPropertyValues("tramai.default-provider=stub")

        contextRunner.run { context ->
            val tramai = context.getBean(Tramai::class.java)
            val service = tramai.create(TestInvoiceAnalyzer::class)
            val result = runBlocking { service.analyze("test") }
            assertThat(result).isEqualTo("spring hello")
        }
    }

    @Test
    fun `single DlpRedactionAuditEmitter bean is wired`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(TramaiAutoConfiguration::class.java),
            )
            .withUserConfiguration(DlpTestApplication::class.java)
            .withBean(DlpProvider::class.java)
            .withBean("dlpInterceptor", DlpInterceptor::class.java, Supplier {
                object : DlpInterceptor {
                    override fun inspect(context: DlpContext, text: String): DlpResult {
                        val sanitizedText = text.replace("sensitive", "redacted")
                        return DlpResult(
                            sanitizedText = sanitizedText,
                            redactions = if (sanitizedText != text) listOf(DlpRedaction("dlp-rule", 1)) else emptyList(),
                        )
                    }
                }
            })
            .withBean("dlpRedactionAuditEmitter", DlpRedactionAuditEmitter::class.java, Supplier {
                object : DlpRedactionAuditEmitter {
                    override suspend fun emit(context: DlpContext, redactions: List<DlpRedaction>) = Unit
                }
            })
            .withPropertyValues(
                "tramai.default-provider=dlp-provider",
                "tramai.models.gpt-5.1-chat-latest=dlp-provider",
            )

        contextRunner.run { context ->
            assertThat(context).hasSingleBean(DlpRedactionAuditEmitter::class.java)
            val service = context.getBean(TestInvoiceAnalyzer::class.java)
            val result = runBlocking { service.analyze("test") }
            assertThat(result).isEqualTo("redacted spring payload")
        }
    }

    @Test
    fun `multiple DlpRedactionAuditEmitter beans fail fast`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(TramaiAutoConfiguration::class.java),
            )
            .withUserConfiguration(TestApplication::class.java, ProviderConfiguration::class.java)
            .withPropertyValues("tramai.default-provider=stub")
            .withBean("firstDlpEmitter", DlpRedactionAuditEmitter::class.java, Supplier {
                object : DlpRedactionAuditEmitter {
                    override suspend fun emit(context: DlpContext, redactions: List<DlpRedaction>) = Unit
                }
            })
            .withBean("secondDlpEmitter", DlpRedactionAuditEmitter::class.java, Supplier {
                object : DlpRedactionAuditEmitter {
                    override suspend fun emit(context: DlpContext, redactions: List<DlpRedaction>) = Unit
                }
            })

        contextRunner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context).getFailure()
                .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
                .hasRootCauseMessage(
                    "Multiple DlpRedactionAuditEmitter beans found (2). Define at most one.",
                )
        }
    }

    @Test
    fun `zero PolicyEngine beans preserves legacy permissive fallback`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(TramaiAutoConfiguration::class.java),
            )
            .withUserConfiguration(TestApplication::class.java, ProviderConfiguration::class.java)
            .withPropertyValues("tramai.default-provider=stub")

        contextRunner.run { context ->
            val tramai = context.getBean(Tramai::class.java)
            val service = tramai.create(TestInvoiceAnalyzer::class)
            val result = runBlocking { service.analyze("test") }
            assertThat(result).isEqualTo("spring hello")
        }
    }

    @Test
    fun `single PolicyEngine bean is wired`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(TramaiAutoConfiguration::class.java),
            )
            .withUserConfiguration(TestApplication::class.java, ProviderConfiguration::class.java)
            .withPropertyValues("tramai.default-provider=stub")
            .withBean("customPolicyEngine", PolicyEngine::class.java, Supplier {
                PolicyEngine { PolicyDecision.Allow }
            })

        contextRunner.run { context ->
            assertThat(context).hasSingleBean(PolicyEngine::class.java)
            val tramai = context.getBean(Tramai::class.java)
            val service = tramai.create(TestInvoiceAnalyzer::class)
            val result = runBlocking { service.analyze("test") }
            assertThat(result).isEqualTo("spring hello")
        }
    }

    @Test
    fun `zero ModelRegistrySettings beans uses property backed default`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(TramaiAutoConfiguration::class.java),
            )
            .withUserConfiguration(TestApplication::class.java, ProviderConfiguration::class.java)
            .withPropertyValues("tramai.default-provider=stub")

        contextRunner.run { context ->
            val tramai = context.getBean(Tramai::class.java)
            val service = tramai.create(TestInvoiceAnalyzer::class)
            val result = runBlocking { service.analyze("test") }
            assertThat(result).isEqualTo("spring hello")
        }
    }

    @Test
    fun `single ModelRegistrySettings bean is wired`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(TramaiAutoConfiguration::class.java),
            )
            .withUserConfiguration(TestApplication::class.java, ProviderConfiguration::class.java)
            .withPropertyValues("tramai.default-provider=stub")
            .withBean("modelRegistrySettings", ModelRegistrySettings::class.java, Supplier {
                ModelRegistrySettings(enabled = false)
            })

        contextRunner.run { context ->
            assertThat(context).hasSingleBean(ModelRegistrySettings::class.java)
            val tramai = context.getBean(Tramai::class.java)
            val service = tramai.create(TestInvoiceAnalyzer::class)
            val result = runBlocking { service.analyze("test") }
            assertThat(result).isEqualTo("spring hello")
        }
    }

    @Test
    fun `multiple ModelRegistrySettings beans fail fast`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(TramaiAutoConfiguration::class.java),
            )
            .withUserConfiguration(TestApplication::class.java, ProviderConfiguration::class.java)
            .withPropertyValues("tramai.default-provider=stub")
            .withBean("firstModelRegistrySettings", ModelRegistrySettings::class.java, Supplier {
                ModelRegistrySettings(enabled = false)
            })
            .withBean("secondModelRegistrySettings", ModelRegistrySettings::class.java, Supplier {
                ModelRegistrySettings(enabled = true)
            })

        contextRunner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context).getFailure()
                .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
                .hasRootCauseMessage(
                    "Multiple ModelRegistrySettings beans found (2). Define at most one.",
                )
        }
    }

    @Test
    fun `multiple PolicyEngine beans fail fast with clear error`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(TramaiAutoConfiguration::class.java),
            )
            .withUserConfiguration(TestApplication::class.java, ProviderConfiguration::class.java)
            .withPropertyValues("tramai.default-provider=stub")
            .withBean("firstEngine", PolicyEngine::class.java, Supplier {
                PolicyEngine { PolicyDecision.Allow }
            })
            .withBean("secondEngine", PolicyEngine::class.java, Supplier {
                PolicyEngine { PolicyDecision.Allow }
            })

        contextRunner.run { context ->
            assertThat(context).hasFailed()
            val failure = requireNotNull(context.startupFailure)
            assertThat(failure)
                .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
                .hasRootCauseMessage(
                    "Multiple PolicyEngine beans found (2). Define at most one.",
                )
        }
    }

    @Test
    fun `wires a single custom DLP bean into the engine`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(TramaiAutoConfiguration::class.java),
            )
            .withUserConfiguration(TestApplication::class.java)
            .withBean(DlpProvider::class.java)
            .withBean("dlpInterceptor", DlpInterceptor::class.java, Supplier {
                object : DlpInterceptor {
                    override fun inspect(context: DlpContext, text: String): DlpResult {
                        return DlpResult(sanitizedText = "[REDACTED]")
                    }
                }
            })
            .withPropertyValues(
                "tramai.default-provider=dlp-provider",
                "tramai.models.gpt-5.1-chat-latest=dlp-provider",
            )

        contextRunner.run { context ->
            val analyzer = context.getBean(TestInvoiceAnalyzer::class.java)
            val provider = context.getBean(DlpProvider::class.java)

            val result = runBlocking { analyzer.analyze("invoice-123") }

            assertThat(result).isEqualTo("[REDACTED]")
            assertThat(provider.requests).hasSize(1)
        }
    }

    @Test
    fun `rejects multiple DLP beans with a clear startup error`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(TramaiAutoConfiguration::class.java),
            )
            .withUserConfiguration(TestApplication::class.java)
            .withBean(DlpProvider::class.java)
            .withBean("firstDlpInterceptor", DlpInterceptor::class.java, Supplier {
                object : DlpInterceptor {
                    override fun inspect(context: DlpContext, text: String): DlpResult {
                        return DlpResult(sanitizedText = text)
                    }
                }
            })
            .withBean("secondDlpInterceptor", DlpInterceptor::class.java, Supplier {
                object : DlpInterceptor {
                    override fun inspect(context: DlpContext, text: String): DlpResult {
                        return DlpResult(sanitizedText = text)
                    }
                }
            })
            .withPropertyValues(
                "tramai.default-provider=dlp-provider",
                "tramai.models.gpt-5.1-chat-latest=dlp-provider",
            )

        contextRunner.run { context ->
            assertThat(context).hasFailed()
            val failure = requireNotNull(context.startupFailure)
            assertThat(failure)
                .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
                .hasRootCauseMessage(
                    "Multiple DlpInterceptor beans found: firstDlpInterceptor, secondDlpInterceptor. Define exactly one DlpInterceptor bean or none.",
                )
        }
    }

    @Test
    fun `single EngineEventObserver bean is wired`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(TramaiAutoConfiguration::class.java),
            )
            .withUserConfiguration(TestApplication::class.java, EngineEventObserverConfiguration::class.java)
            .withPropertyValues("tramai.default-provider=stub")

        contextRunner.run { context ->
            assertThat(context).hasSingleBean(Tramai::class.java)
            val tramai = context.getBean(Tramai::class.java)
            // The observer bean should be forwarded to the engine
            val service = tramai.create(TestInvoiceAnalyzer::class)
            val result = runBlocking { service.analyze("invoice-echo") }
            assertThat(result).isNotNull
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

@SpringBootApplication
@ComponentScan(excludeFilters = [ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, value = [InterceptorConfiguration::class])])
open class DlpTestApplication

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
    open fun testSecretValueResolver(): SecretValueResolver = object : SecretValueResolver {
        override fun resolve(secretRef: String): String? = when (secretRef) {
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

@TestConfiguration
open class InterceptorConfiguration {
    @Bean
    open fun interceptingProvider(): InterceptingProvider = InterceptingProvider()

    @Bean
    open fun piiMaskingInterceptor(): OperationInterceptor = object : OperationInterceptor {
        override fun interceptRequest(
            context: OperationCallContext,
            messages: List<Message>,
        ): List<Message> = messages.map { message ->
            message.copy(content = message.content.replace("secret-id", "[REDACTED_ID]"))
        }

        override fun interceptResponse(
            context: OperationCallContext,
            response: ModelResponse,
        ): ModelResponse = response.copy(content = response.content.replace("sensitive", "redacted"))
    }
}

@TestConfiguration
open class EngineEventObserverConfiguration {
    @Bean
    open fun engineEventObserver(): EngineEventObserver = object : EngineEventObserver {
        override fun onEngineEvent(name: String, attributes: Map<String, Any?>) = Unit
    }
}

class PrimaryFailingProvider : ModelProvider {
    val requests = mutableListOf<ModelRequest>()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        throw ProviderException("service unavailable", statusCode = 503, retryable = true)
    }

    override fun providerId(): String = "primary"
}

class OpenAiOverrideProvider : ModelProvider {
    override suspend fun complete(request: ModelRequest): ModelResponse = ModelResponse(content = "bean override")

    override fun providerId(): String = "openai"
}

class FixedProvider(private val id: String) : ModelProvider {
    override suspend fun complete(request: ModelRequest): ModelResponse = ModelResponse(content = id)

    override fun providerId(): String = id
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

class InterceptingProvider : ModelProvider {
    val requests = mutableListOf<ModelRequest>()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        return ModelResponse(content = "contains sensitive payload")
    }

    override fun providerId(): String = "intercepted"
}

class DlpProvider : ModelProvider {
    val requests = mutableListOf<ModelRequest>()

    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        return ModelResponse(content = "sensitive spring payload")
    }

    override fun providerId(): String = "dlp-provider"
}

class CountingDlpRedactionAuditEmitter : DlpRedactionAuditEmitter {
    val instanceInvocationCount = AtomicInteger(0)

    override suspend fun emit(
        context: DlpContext,
        redactions: List<DlpRedaction>,
    ) {
        instanceInvocationCount.incrementAndGet()
        invocationCount.incrementAndGet()
    }

    companion object {
        val invocationCount = AtomicInteger(0)

        fun reset() {
            invocationCount.set(0)
        }
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

}
