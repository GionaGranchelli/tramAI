package dev.tramai.spring.sovereign.enablefixture

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import dev.tramai.spring.EnableTramai
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableTramai
class SovereignEnableTramaiFixture {

    @Bean
    fun sovereignEnableProvider(): ModelProvider = object : ModelProvider {
        override fun providerId(): String = "local-provider"

        override suspend fun complete(request: ModelRequest): ModelResponse =
            ModelResponse(content = "SOVEREIGN_ENABLE_OK")
    }
}

@AiService
interface SovereignEnableTramaiService {
    @Operation(
        model = "local-model",
        prompt = "Return the sovereign profile test result.",
    )
    suspend fun analyze(input: String): String
}
