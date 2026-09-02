package dev.tramai.core.provider

import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

/**
 * Exercises the ModelProvider DEFAULT interface method bodies.
 *
 * 10.3c1 routing-core mutation pilot: PIT found three NO_COVERAGE survivors on
 * these defaults (providerId() null-coalesce/empty-return, supportsCapability()
 * false-return). Every other test provider overrides both methods, so the
 * default implementations had zero execution coverage. These tests pin the
 * default contract so the mutants are killed.
 */
class ModelProviderDefaultContractTest {
    private class DefaultProvider : ModelProvider {
        // Deliberately does NOT override providerId() or supportsCapability().
        override suspend fun complete(request: ModelRequest): ModelResponse {
            error("unused")
        }
    }

    @Test
    fun `default providerId returns the class simple name`() {
        val provider = DefaultProvider()
        assertThat(provider.providerId()).isEqualTo("DefaultProvider")
    }

    @Test
    fun `default supportsCapability is false for every capability`() {
        val provider = DefaultProvider()
        ProviderCapability.entries.forEach { capability ->
            assertThat(provider.supportsCapability(capability)).isFalse()
        }
    }
}
