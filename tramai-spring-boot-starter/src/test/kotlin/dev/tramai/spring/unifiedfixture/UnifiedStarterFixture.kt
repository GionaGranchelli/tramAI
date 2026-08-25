package dev.tramai.spring.unifiedfixture

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Plain fixture for the unified starter: no [@EnableTramai], no profile
 * property. The same classes must run under both `tramai.profile=standard`
 * and `tramai.profile=sovereign` with only properties changing.
 */
@Configuration(proxyBeanMethods = false)
class UnifiedStarterFixture {

    @Bean
    fun unifiedStarterProvider(): ModelProvider = object : ModelProvider {
        override fun providerId(): String = "local-provider"

        override suspend fun complete(request: ModelRequest): ModelResponse =
            ModelResponse(content = "UNIFIED_STARTER_OK")
    }
}

@AiService
interface UnifiedStarterAiService {
    @Operation(
        model = "local-model",
        prompt = "Return the unified starter test result.",
    )
    suspend fun analyze(input: String): String
}
