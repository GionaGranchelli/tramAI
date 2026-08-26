package dev.tramai.examples.sovereign.consumersmoke

import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
open class SmokeApplication {

    /**
     * H1 contract: a selected sovereign profile requires at least one model
     * provider. The smoke app performs no real AI calls, so a stub provider
     * satisfies the runtime without external network.
     */
    @Bean
    fun smokeProvider(): ModelProvider = object : ModelProvider {
        override fun providerId(): String = "local-provider"

        override suspend fun complete(request: ModelRequest): ModelResponse =
            ModelResponse(content = "smoke")
    }
}

fun main(args: Array<String>) {
    runApplication<SmokeApplication>(*args)
}
