package dev.tramai.sovereign

import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRoutingPlan
import dev.tramai.security.ProviderTrustZone
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SovereignRoutingValidationPolicyTest {

    @Test
    fun `policy reads registered providers from the authoritative plan`() {
        val plan = ProviderRoutingPlan.builder()
            .provider("unlisted-provider", FakeProvider("unlisted-provider"), default = true)
            .model("test-model", "unlisted-provider")
            .build()
        val profile = profile(allowedProviders = setOf("local-provider"))

        assertThatThrownBy { SovereignRoutingValidationPolicy.validate(plan, profile) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Registered provider 'unlisted-provider' is not in allowedProviders")
    }

    @Test
    fun `policy reads fallback routes from the authoritative plan`() {
        val plan = ProviderRoutingPlan.builder()
            .provider("local-provider", FakeProvider("local-provider"), default = true)
            .provider("fallback-provider", FakeProvider("fallback-provider"))
            .model("test-model", "local-provider")
            .model("fallback-model", "fallback-provider")
            .fallbackProvider("test-model", "fallback-provider")
            .build()
        val profile = profile(allowedProviders = setOf("local-provider", "fallback-provider"))

        assertThatThrownBy { SovereignRoutingValidationPolicy.validate(plan, profile) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Fallback provider 'fallback-provider' is not in allowedFallbackProviders")
    }

    @Test
    fun `verification targets include primary and fallback plan routes`() {
        val plan = ProviderRoutingPlan.builder()
            .provider("local-provider", FakeProvider("local-provider"), default = true)
            .provider("fallback-provider", FakeProvider("fallback-provider"))
            .model("test-model", "local-provider")
            .fallbackModel("test-model", "fallback-model", "fallback-provider")
            .build()

        assertThat(plan.verificationTargets()).containsExactlyInAnyOrder(
            "local-provider" to "test-model",
            "fallback-provider" to "fallback-model",
        )
    }

    @Test
    fun `policy rejects fallback-only routing configuration`() {
        // A fallback registered without an explicit primary is a sovereign-regression
        // vector (the fallback would masquerade as a primary and execute an unapproved
        // effective model). The canonical plan must reject it at build.
        assertThatThrownBy {
            ProviderRoutingPlan.builder()
                .provider("local-provider", FakeProvider("local-provider"), default = true)
                .fallbackModel("approved-model", "NOT-APPROVED", "local-provider")
                .build()
        }
            .isInstanceOf(dev.tramai.core.exception.ConfigurationException::class.java)
            .hasMessageContaining("no primary route")
    }

    private fun profile(allowedProviders: Set<String>) = SovereignProfileConfiguration(
        allowedModels = setOf("test-model", "fallback-model"),
        allowedProviders = allowedProviders,
        providerZones = allowedProviders.associateWith { ProviderTrustZone.LOCAL },
    )

    private class FakeProvider(private val name: String) : ModelProvider {
        override suspend fun complete(request: ModelRequest): ModelResponse =
            ModelResponse(content = "unused")

        override fun providerId(): String = name
    }
}
