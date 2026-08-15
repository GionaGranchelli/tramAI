package dev.tramai.engine.provider

import dev.tramai.core.exception.ModelRegistryException
import dev.tramai.core.model.ModelArtifactDigest
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.model.RegisteredModel
import dev.tramai.engine.ModelRegistryEnforcer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ProviderAuthorizationServiceTest {
    @Test fun `returns authorized registered model without observations`() {
        runBlocking {
        val approved = model(); val service = ProviderAuthorizationService(enforcer { approved })
        assertThat(service.authorize("provider", "model")).isEqualTo(approved)
    }
    }
    @Test fun `rethrows registry failure`() {
        val service = ProviderAuthorizationService(enforcer { null })
        assertThatThrownBy { runBlocking { service.authorize("provider", "missing") } }.isInstanceOf(ModelRegistryException::class.java)
    }
    @Test fun `propagates cancellation unchanged`() {
        val cancellation = CancellationException("stop")
        val service = ProviderAuthorizationService(enforcer { throw cancellation })
        assertThatThrownBy { runBlocking { service.authorize("provider", "model") } }.isSameAs(cancellation)
    }
    private fun enforcer(answer: suspend () -> RegisteredModel?) = ModelRegistryEnforcer(object : ModelRegistry {
        override suspend fun findApprovedModel(providerId: String, modelName: String) = answer()
    }, ModelRegistrySettings(enabled = true))
    private fun model() = RegisteredModel("id", "provider", "model", "r1", ModelArtifactDigest.of("sha256:${"a".repeat(64)}"), true)
}
