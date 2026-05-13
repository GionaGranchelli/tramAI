package dev.tramai.embedding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith

class EmbeddingModelRegistryTest {

    @Test
    fun `register and resolve embedding model`() {
        val model = StubEmbeddingModel(dimensions = 384)
        val registry = EmbeddingModelRegistry.builder()
            .register("test-model", model)
            .build()

        val resolved = registry.resolve("test-model")
        assertNotNull(resolved)
        assertEquals(384, resolved.dimensions())
    }

    @Test
    fun `resolve throws for unknown model`() {
        val registry = EmbeddingModelRegistry.builder().build()
        val exception = assertFailsWith<IllegalArgumentException> {
            registry.resolve("nonexistent")
        }
        assertEquals(
            "No embedding model registered under name 'nonexistent'. Available models: ",
            exception.message,
        )
    }

    @Test
    fun `resolve with multiple registered models`() {
        val modelA = StubEmbeddingModel(dimensions = 384, name = "A")
        val modelB = StubEmbeddingModel(dimensions = 768, name = "B")

        val registry = EmbeddingModelRegistry.builder()
            .register("model-a", modelA)
            .register("model-b", modelB)
            .build()

        assertEquals(384, registry.resolve("model-a").dimensions())
        assertEquals(768, registry.resolve("model-b").dimensions())
    }

    @Test
    fun `resolve with EmbeddingConfig`() {
        val model = StubEmbeddingModel(dimensions = 384, name = "config-test")
        val registry = EmbeddingModelRegistry.builder()
            .register("test-provider", model)
            .build()

        val resolved = registry.resolve(EmbeddingConfig(providerId = "test-provider"))
        assertNotNull(resolved)
        assertEquals(384, resolved.dimensions())
    }

    @Test
    fun `register rejects duplicate names`() {
        val modelA = StubEmbeddingModel(dimensions = 384, name = "A")
        val modelB = StubEmbeddingModel(dimensions = 768, name = "B")

        val exception = assertFailsWith<IllegalArgumentException> {
            EmbeddingModelRegistry.builder()
                .register("same-name", modelA)
                .register("same-name", modelB)
                .build()
        }
        assertEquals(
            "Embedding model 'same-name' is already registered",
            exception.message,
        )
    }

    @Test
    fun `providerId returns stub by default`() {
        val model = StubEmbeddingModel(dimensions = 128)
        assertEquals("stub", model.providerId())
    }

    @Test
    fun `register with default flag and resolveDefault`() {
        val modelA = StubEmbeddingModel(dimensions = 384, name = "A")
        val modelB = StubEmbeddingModel(dimensions = 768, name = "B")

        val registry = EmbeddingModelRegistry.builder()
            .register("model-a", modelA)
            .register("model-b", modelB, default = true)
            .build()

        val defaultModel = registry.resolveDefault()
        assertNotNull(defaultModel)
        assertEquals(768, defaultModel.dimensions())
    }

    @Test
    fun `resolveDefault throws when no default registered`() {
        val registry = EmbeddingModelRegistry.builder()
            .register("some-model", StubEmbeddingModel(dimensions = 128))
            .build()

        val exception = assertFailsWith<IllegalStateException> {
            registry.resolveDefault()
        }
        assertEquals(
            "No default embedding model has been registered. Register one with register(name, model, default = true).",
            exception.message,
        )
    }

    @Test
    fun `resolveDefault throws when empty registry`() {
        val registry = EmbeddingModelRegistry.builder().build()

        val exception = assertFailsWith<IllegalStateException> {
            registry.resolveDefault()
        }
        assertEquals(
            "No default embedding model has been registered. Register one with register(name, model, default = true).",
            exception.message,
        )
    }

    @Test
    fun `last registered default wins`() {
        val modelA = StubEmbeddingModel(dimensions = 384, name = "A")
        val modelB = StubEmbeddingModel(dimensions = 512, name = "B")
        val modelC = StubEmbeddingModel(dimensions = 768, name = "C")

        val registry = EmbeddingModelRegistry.builder()
            .register("a", modelA, default = true)
            .register("b", modelB, default = true)
            .register("c", modelC)
            .build()

        // "b" was the last registered with default=true
        assertEquals(512, registry.resolveDefault().dimensions())
    }
}

private class StubEmbeddingModel(
    private val dimensions: Int,
    private val name: String = "stub",
) : EmbeddingModel {
    override fun providerId(): String = name

    override suspend fun embed(text: String): FloatArray {
        return FloatArray(dimensions) { idx -> (idx + 1).toFloat() / dimensions }
    }

    override suspend fun embedAll(texts: List<String>): List<FloatArray> {
        return texts.map { embed(it) }
    }

    override fun dimensions(): Int = dimensions
}
