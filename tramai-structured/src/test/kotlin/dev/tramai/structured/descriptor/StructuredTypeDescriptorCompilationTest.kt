package dev.tramai.structured.descriptor

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import dev.tramai.core.annotations.AiDescription
import dev.tramai.core.annotations.AiMinItems
import dev.tramai.core.annotations.AiRange
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test
import kotlin.reflect.typeOf

/**
 * Descriptor compilation tests for Epic 7.1: Kotlin reflection compiles into
 * the language-neutral [StructuredTypeDescriptor] exactly once, and all
 * language-specific inspection happens inside compilation.
 */
class StructuredTypeDescriptorCompilationTest {

    private val compiler = StructuredTypeCompiler(
        JsonMapper.builder().addModule(kotlinModule()).build(),
    )

    @Test
    fun `scalar kinds map correctly with nullability`() {
        assertThat(compiler.compile(typeOf<String>()))
            .isEqualTo(StructuredTypeDescriptor.Scalar(ScalarKind.STRING, nullable = false))
        assertThat(compiler.compile(typeOf<Int>()))
            .isEqualTo(StructuredTypeDescriptor.Scalar(ScalarKind.INTEGER, nullable = false))
        assertThat(compiler.compile(typeOf<Double>()))
            .isEqualTo(StructuredTypeDescriptor.Scalar(ScalarKind.NUMBER, nullable = false))
        assertThat(compiler.compile(typeOf<Boolean>()))
            .isEqualTo(StructuredTypeDescriptor.Scalar(ScalarKind.BOOLEAN, nullable = false))
        assertThat(compiler.compile(typeOf<String?>()))
            .isEqualTo(StructuredTypeDescriptor.Scalar(ScalarKind.STRING, nullable = true))
    }

    @Test
    fun `enum compiles to explicit enum descriptor`() {
        val descriptor = compiler.compile(typeOf<Level>())

        assertThat(descriptor)
            .isEqualTo(StructuredTypeDescriptor.Enum(listOf("LOW", "HIGH"), nullable = false))
    }

    @Test
    fun `nullable enum keeps nullability`() {
        val descriptor = compiler.compile(typeOf<Level?>())

        assertThat(descriptor)
            .isEqualTo(StructuredTypeDescriptor.Enum(listOf("LOW", "HIGH"), nullable = true))
    }

    @Test
    fun `list compiles to collection descriptor with item type`() {
        val descriptor = compiler.compile(typeOf<List<String>>())

        assertThat(descriptor)
            .isEqualTo(
                StructuredTypeDescriptor.Collection(
                    item = StructuredTypeDescriptor.Scalar(ScalarKind.STRING, nullable = false),
                    minItems = null,
                    nullable = false,
                ),
            )
    }

    @Test
    fun `nested list compiles to nested collection`() {
        val descriptor = compiler.compile(typeOf<List<List<Int>>>())

        assertThat(descriptor).isInstanceOf(StructuredTypeDescriptor.Collection::class.java)
        val outer = descriptor as StructuredTypeDescriptor.Collection
        assertThat(outer.item).isInstanceOf(StructuredTypeDescriptor.Collection::class.java)
        val inner = outer.item as StructuredTypeDescriptor.Collection
        assertThat(inner.item)
            .isEqualTo(StructuredTypeDescriptor.Scalar(ScalarKind.INTEGER, nullable = false))
    }

    @Test
    fun `object compiles with sorted properties and requiredness`() {
        val descriptor = compiler.compile(typeOf<SampleObject>())

        assertThat(descriptor).isInstanceOf(StructuredTypeDescriptor.Object::class.java)
        val objectDescriptor = descriptor as StructuredTypeDescriptor.Object
        assertThat(objectDescriptor.typeName).contains("SampleObject")
        assertThat(objectDescriptor.properties.map { it.name })
            .containsExactly("name", "score", "tags")
        assertThat(objectDescriptor.properties.map { it.required })
            .containsExactly(false, true, true)
    }

    @Test
    fun `annotations compile into property description and range`() {
        val descriptor = compiler.compile(typeOf<AnnotatedObject>())
        val objectDescriptor = descriptor as StructuredTypeDescriptor.Object

        val score = objectDescriptor.properties.single { it.name == "score" }
        assertThat(score.description).isEqualTo("Score between zero and one")
        assertThat(score.range).isEqualTo(NumericRange(0.0, 1.0))
        assertThat(score.type).isEqualTo(StructuredTypeDescriptor.Scalar(ScalarKind.NUMBER, nullable = false))
    }

    @Test
    fun `minItems compiles onto collection descriptor`() {
        val descriptor = compiler.compile(typeOf<AnnotatedObject>())
        val objectDescriptor = descriptor as StructuredTypeDescriptor.Object

        val tags = objectDescriptor.properties.single { it.name == "tags" }
        assertThat(tags.type).isInstanceOf(StructuredTypeDescriptor.Collection::class.java)
        assertThat((tags.type as StructuredTypeDescriptor.Collection).minItems).isEqualTo(1)
    }

    @Test
    fun `unsupported map type fails with controlled error`() {
        assertThatThrownBy {
            compiler.compile(typeOf<Map<String, String>>())
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Unsupported structured output type")
    }

    @Test
    fun `generic list without item type fails with controlled error`() {
        // A star-projected List has no item type; classifier is still List.
        assertThatThrownBy {
            compiler.compile(typeOf<List<*>>())
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("must declare an item type")
    }

    @Test
    fun `recursive type is rejected with language-neutral error`() {
        assertThatThrownBy {
            compiler.compile(typeOf<RecursiveNode>())
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Recursive structured output type is unsupported")
    }

    @Test
    fun `sibling reuse of same type compiles both properties fully`() {
        val descriptor = compiler.compile(typeOf<SiblingObject>())
        val objectDescriptor = descriptor as StructuredTypeDescriptor.Object

        assertThat(objectDescriptor.properties).hasSize(2)
        objectDescriptor.properties.forEach { property ->
            assertThat(property.type).isInstanceOf(StructuredTypeDescriptor.Object::class.java)
            val nested = property.type as StructuredTypeDescriptor.Object
            assertThat(nested.properties.map { it.name }).containsExactly("value")
        }
    }

    private enum class Level { LOW, HIGH }

    private data class SampleObject(
        val name: String?,
        val score: Double,
        val tags: List<String>,
    )

    private data class AnnotatedObject(
        @property:AiDescription("Score between zero and one")
        @property:AiRange(min = 0.0, max = 1.0)
        val score: Double,
        @property:AiMinItems(1)
        val tags: List<String>,
    )

    private data class RecursiveNode(val next: RecursiveNode?)

    private data class SiblingObject(val left: Leaf, val right: Leaf)

    private data class Leaf(val value: String)
}
