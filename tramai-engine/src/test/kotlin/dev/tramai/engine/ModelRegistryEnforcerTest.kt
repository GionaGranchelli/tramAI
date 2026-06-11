package dev.tramai.engine

import dev.tramai.core.exception.ModelDisabledException
import dev.tramai.core.exception.ModelNotRegisteredException
import dev.tramai.core.model.ModelArtifactDigest
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.model.RegisteredModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class ModelRegistryEnforcerTest {

    @Test
    fun `disabled registry enforcement returns null`() : Unit = runBlocking {
        val enforcer = ModelRegistryEnforcer(
            registry = registryReturning(registeredModel()),
            settings = ModelRegistrySettings(enabled = false),
        )

        assertThat(enforcer.authorize("provider-1", "model-1")).isNull()
    }

    @Test
    fun `enabled registry rejects unknown model`() : Unit = runBlocking {
        val enforcer = ModelRegistryEnforcer(
            registry = registryReturning(null),
            settings = ModelRegistrySettings(enabled = true),
        )

        assertThatThrownBy {
            runBlocking { enforcer.authorize("provider-1", "missing") }
        }.isInstanceOf(ModelNotRegisteredException::class.java)
    }

    @Test
    fun `enabled registry accepts registered model`() : Unit = runBlocking {
        val approved = registeredModel()
        val enforcer = ModelRegistryEnforcer(
            registry = registryReturning(approved),
            settings = ModelRegistrySettings(enabled = true),
        )

        assertThat(enforcer.authorize("provider-1", "model-1")).isEqualTo(approved)
    }

    @Test
    fun `enabled registry rejects disabled model`() : Unit = runBlocking {
        val enforcer = ModelRegistryEnforcer(
            registry = registryReturning(registeredModel(enabled = false)),
            settings = ModelRegistrySettings(enabled = true),
        )

        assertThatThrownBy {
            runBlocking { enforcer.authorize("provider-1", "model-1") }
        }.isInstanceOf(ModelDisabledException::class.java)
    }

    @Test
    fun `cancellation exception propagates`() : Unit = runBlocking {
        val enforcer = ModelRegistryEnforcer(
            registry = object : ModelRegistry {
                override suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel? {
                    throw CancellationException("cancelled")
                }
            },
            settings = ModelRegistrySettings(enabled = true),
        )

        assertThatThrownBy {
            runBlocking { enforcer.authorize("provider-1", "model-1") }
        }.isInstanceOf(CancellationException::class.java)
    }

    private fun registeredModel(
        enabled: Boolean = true,
    ): RegisteredModel = RegisteredModel(
        registryEntryId = "entry-1",
        providerId = "provider-1",
        modelName = "model-1",
        revision = "rev-1",
        artifactDigest = ModelArtifactDigest.of("sha256:${"a".repeat(64)}"),
        enabled = enabled,
    )

    private fun registryReturning(model: RegisteredModel?): ModelRegistry = object : ModelRegistry {
        override suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel? = model
    }
}
