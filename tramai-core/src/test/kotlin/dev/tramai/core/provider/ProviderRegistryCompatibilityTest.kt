package dev.tramai.core.provider

import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class ProviderRegistryCompatibilityTest {
    @Test
    fun `old builder API produces a working registry`() {
        val primary = NamedProvider("primary")
        val fallback = NamedProvider("fallback")
        val default = NamedProvider("default")
        val registry = ProviderRegistry.builder().provider("primary", primary).provider("fallback", fallback)
            .provider("default", default).model("model", "primary").fallbackModel("model", "fallback-model", "fallback")
            .defaultProvider("default").build()

        assertThat(registry.resolve(Operation(prompt = "unused", model = "model"))).isSameAs(primary)
        assertThat(registry.resolve(Operation(prompt = "unused", model = "other"))).isSameAs(default)
    }

    @Test
    fun `resolution keeps explicit provider model route and default precedence`() {
        val explicit = NamedProvider("explicit")
        val routed = NamedProvider("routed")
        val default = NamedProvider("default")
        val registry = ProviderRegistry.builder().provider("explicit", explicit).provider("routed", routed)
            .provider("default", default).model("model", "routed").defaultProvider("default").build()

        assertThat(registry.resolve(Operation(prompt = "unused", model = "model", provider = "explicit"))).isSameAs(explicit)
        assertThat(registry.resolve(Operation(prompt = "unused", model = "model"))).isSameAs(routed)
        assertThat(registry.resolve(Operation(prompt = "unused", model = "other"))).isSameAs(default)
    }

    @Test
    fun `fallback ordering remains primary then configured fallbacks`() {
        val registry = ProviderRegistry.builder().provider("one", NamedProvider("one")).provider("two", NamedProvider("two"))
            .provider("three", NamedProvider("three")).model("model", "one").fallbackProvider("model", "two")
            .fallbackModel("model", "alternate", "three").build()

        assertThat(registry.resolveCandidates(Operation(prompt = "unused", model = "model"))).extracting<String> { it.providerName }
            .containsExactly("one", "two", "three")
    }

    @Test
    fun `single provider remains the default provider`() {
        val provider = NamedProvider("single")
        assertThat(ProviderRegistry.singleProvider(provider).resolve(Operation(prompt = "unused", model = "anything"))).isSameAs(provider)
    }

    @Test
    fun `duplicate provider now fails at build`() = assertThatThrownBy {
        ProviderRegistry.builder().provider("one", NamedProvider("one")).provider("one", NamedProvider("replacement")).build()
    }.isInstanceOf(ConfigurationException::class.java)

    private class NamedProvider(private val name: String) : ModelProvider {
        override suspend fun complete(request: ModelRequest): ModelResponse = error("unused")
        override fun providerId(): String = name
    }
}
