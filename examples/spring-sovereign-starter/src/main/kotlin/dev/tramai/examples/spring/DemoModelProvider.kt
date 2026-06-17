package dev.tramai.examples.spring

import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * A deterministic local model provider that returns a fixed JSON response
 * for invoice analysis requests.
 *
 * This provider proves the sovereign runtime works without any cloud
 * provider dependency. It identifies as `deterministic-local-provider`,
 * matching the `allowed-providers` and `models` entries in `application.yml`.
 */
class DemoModelProvider : ModelProvider {

    override fun providerId(): String = "deterministic-local-provider"

    override suspend fun complete(request: ModelRequest): ModelResponse {
        return ModelResponse(
            content = """
                {
                  "summary": "Invoice requires review before payment.",
                  "riskLevel": "MEDIUM",
                  "detectedRisks": [
                    "Restricted customer reference present",
                    "High-value invoice",
                    "Short payment window"
                  ],
                  "recommendedAction": "Route to finance approval workflow"
                }
            """.trimIndent(),
        )
    }
}

/**
 * Registers the [DemoModelProvider] as a Spring bean so the Sovereign
 * starter can discover and wire it into [dev.tramai.sovereign.SovereignTramai].
 */
@Configuration
class DemoProviderConfiguration {
    @Bean
    fun deterministicLocalProvider(): ModelProvider =
        DemoModelProvider()
}
