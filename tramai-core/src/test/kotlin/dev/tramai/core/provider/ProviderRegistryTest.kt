package dev.tramai.core.provider

import dev.tramai.core.annotations.Operation
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ProviderRegistryTest {

    @Test
    fun `resolve candidates returns primary route plus configured fallbacks`() {
        val primary = NamedProvider("primary")
        val fallback = NamedProvider("fallback")
        val registry = ProviderRegistry.builder()
            .provider("primary", primary)
            .provider("fallback", fallback)
            .model("gpt-5.1-chat-latest", "primary")
            .fallbackModel("gpt-5.1-chat-latest", "gpt-5.1-mini", "fallback")
            .build()

        val candidates = registry.resolveCandidates(
            Operation(
                prompt = "unused",
                model = "gpt-5.1-chat-latest",
            ),
        )

        assertThat(candidates).extracting<String> { it.providerName }
            .containsExactly("primary", "fallback")
        assertThat(candidates).extracting<String> { it.effectiveModelName }
            .containsExactly("gpt-5.1-chat-latest", "gpt-5.1-mini")
    }

    private class NamedProvider(
        private val name: String,
    ) : ModelProvider {
        override suspend fun complete(request: dev.tramai.core.model.ModelRequest): dev.tramai.core.model.ModelResponse {
            error("unused")
        }

        override fun providerId(): String = name
    }
}
