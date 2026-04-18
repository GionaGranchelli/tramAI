package io.aurora.spring

import io.aurora.core.annotations.AiService
import io.aurora.core.annotations.Operation
import io.aurora.core.model.ModelRequest
import io.aurora.core.model.ModelResponse
import io.aurora.core.provider.ModelProvider
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.test.Test

class AuroraAutoConfigurationTest {

    @Test
    fun `registers ai service beans and injects a custom provider bean`() {
        val contextRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(AuroraAutoConfiguration::class.java),
            )
            .withUserConfiguration(TestApplication::class.java, ProviderConfiguration::class.java)
            .withPropertyValues(
                "aurora.default-provider=stub",
            )

        contextRunner.run { context ->
            assertThat(context).hasSingleBean(TestInvoiceAnalyzer::class.java)
            val analyzer = context.getBean(TestInvoiceAnalyzer::class.java)

            val result = runBlocking { analyzer.analyze("invoice-123") }

            assertThat(result).isEqualTo("spring hello")
        }
    }
}

@AiService
interface TestInvoiceAnalyzer {
    @Operation(
        prompt = "Analyze the invoice",
        model = "claude-sonnet-4-20250514",
    )
    suspend fun analyze(invoiceId: String): String
}

@SpringBootApplication
open class TestApplication

@Configuration
open class ProviderConfiguration {
    @Bean
    open fun stubProvider(): ModelProvider = object : ModelProvider {
        override suspend fun complete(request: ModelRequest): ModelResponse {
            return ModelResponse(content = "spring hello")
        }

        override fun providerId(): String = "stub"
    }
}
