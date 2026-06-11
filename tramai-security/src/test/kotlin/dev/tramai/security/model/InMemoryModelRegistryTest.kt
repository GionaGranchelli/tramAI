package dev.tramai.security.model

import dev.tramai.core.model.ModelArtifactDigest
import dev.tramai.core.model.RegisteredModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.test.assertFails

class InMemoryModelRegistryTest {

    @Test
    fun `approved model lookup returns descriptor`() : Unit = runBlocking {
        val model = registeredModel()
        val registry = InMemoryModelRegistry.builder()
            .register(model)
            .build()

        assertThat(registry.findApprovedModel("provider-1", "model-1")).isEqualTo(model)
    }

    @Test
    fun `unknown model returns null`() : Unit = runBlocking {
        val registry = InMemoryModelRegistry.builder()
            .register(registeredModel())
            .build()

        assertThat(registry.findApprovedModel("provider-1", "missing")).isNull()
    }

    @Test
    fun `disabled model remains visible as disabled`() : Unit = runBlocking {
        val disabled = registeredModel(enabled = false)
        val registry = InMemoryModelRegistry.builder()
            .register(disabled)
            .build()

        assertThat(registry.findApprovedModel("provider-1", "model-1")?.enabled).isFalse()
    }

    @Test
    fun `duplicate providerId and modelName is rejected`() {
        assertThatThrownBy {
            InMemoryModelRegistry.builder()
                .register(registeredModel(registryEntryId = "entry-1"))
                .register(registeredModel(registryEntryId = "entry-2"))
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("already registered")
    }

    @Test
    fun `duplicate error does not expose identifiers`() {
        assertThatThrownBy {
            InMemoryModelRegistry.builder()
                .register(registeredModel(providerId = "secret-provider", modelName = "secret-model"))
                .register(registeredModel(providerId = "secret-provider", modelName = "secret-model"))
                .build()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageNotContaining("secret-provider")
            .hasMessageNotContaining("secret-model")
    }

    @Test
    fun `same modelName under different providers is distinct`() : Unit = runBlocking {
        val first = registeredModel(providerId = "provider-1")
        val second = registeredModel(providerId = "provider-2", registryEntryId = "entry-2")
        val registry = InMemoryModelRegistry.builder()
            .register(first)
            .register(second)
            .build()

        assertThat(registry.findApprovedModel("provider-1", "model-1")).isEqualTo(first)
        assertThat(registry.findApprovedModel("provider-2", "model-1")).isEqualTo(second)
    }

    @Test
    fun `returned descriptors are immutable`() {
        val registry = InMemoryModelRegistry.builder()
            .register(registeredModel())
            .build()
        val field = InMemoryModelRegistry::class.java.getDeclaredField("modelsByKey")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(registry) as Map<*, *>

        assertFails {
            (map as MutableMap<Any, Any>)[Any()] = registeredModel()
        }
    }

    @Test
    fun `concurrent reads produce deterministic results`() : Unit = runBlocking {
        val model = registeredModel()
        val registry = InMemoryModelRegistry.builder()
            .register(model)
            .build()

        val results = coroutineScope {
            List(64) {
                async {
                    registry.findApprovedModel("provider-1", "model-1")
                }
            }.awaitAll()
        }

        assertThat(results).allSatisfy { result -> assertThat(result).isEqualTo(model) }
    }

    @Test
    fun `delimiter collision is impossible with typed keys`() : Unit = runBlocking {
        val a = registeredModel(providerId = "a:b", modelName = "c", registryEntryId = "entry-a")
        val b = registeredModel(providerId = "a", modelName = "b:c", registryEntryId = "entry-b")
        val registry = InMemoryModelRegistry.builder()
            .register(a)
            .register(b)
            .build()

        assertThat(registry.findApprovedModel("a:b", "c")).isEqualTo(a)
        assertThat(registry.findApprovedModel("a", "b:c")).isEqualTo(b)
    }

    @Test
    fun `failed duplicate does not mutate previous entry`() {
        val builder = InMemoryModelRegistry.builder()
            .register(registeredModel(registryEntryId = "entry-1"))
        assertThatThrownBy {
            builder.register(registeredModel(registryEntryId = "entry-2"))
        }.isInstanceOf(IllegalArgumentException::class.java)
        // Build should still succeed with the original entry
        val registry = builder.build()
        runBlocking {
            assertThat(registry.findApprovedModel("provider-1", "model-1")).isNotNull
        }
    }

    private fun registeredModel(
        registryEntryId: String = "entry-1",
        providerId: String = "provider-1",
        modelName: String = "model-1",
        revision: String = "rev-1",
        enabled: Boolean = true,
    ): RegisteredModel = RegisteredModel(
        registryEntryId = registryEntryId,
        providerId = providerId,
        modelName = modelName,
        revision = revision,
        artifactDigest = ModelArtifactDigest.of("sha256:${"a".repeat(64)}"),
        enabled = enabled,
    )
}
