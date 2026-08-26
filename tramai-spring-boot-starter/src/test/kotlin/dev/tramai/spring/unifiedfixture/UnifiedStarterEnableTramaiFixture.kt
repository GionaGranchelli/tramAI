package dev.tramai.spring.unifiedfixture

import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import dev.tramai.spring.EnableTramai
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Same application fixture as [UnifiedStarterFixture] plus explicit
 * [@EnableTramai], used to prove annotation-driven contexts coexist with the
 * unified starter's Boot auto-configuration without duplicates.
 */
@Configuration(proxyBeanMethods = false)
@EnableTramai
class UnifiedStarterEnableTramaiFixture {

    @Bean
    fun unifiedStarterEnableProvider(): ModelProvider = object : ModelProvider {
        override fun providerId(): String = "local-provider"

        override suspend fun complete(request: ModelRequest): ModelResponse =
            ModelResponse(content = "UNIFIED_STARTER_OK")
    }
}
