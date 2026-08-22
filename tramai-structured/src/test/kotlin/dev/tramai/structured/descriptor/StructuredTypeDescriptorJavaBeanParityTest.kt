package dev.tramai.structured.descriptor

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import dev.tramai.core.annotations.AiDescription
import dev.tramai.core.annotations.AiMinItems
import dev.tramai.core.annotations.AiRange
import dev.tramai.structured.JavaParityFixtures
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test
import kotlin.reflect.typeOf

/**
 * JavaBean parity tests for Epic 7.1: an equivalent Kotlin data class and
 * JavaBean DTO must compile to the same semantic descriptor. Runtime
 * accessors differ and are excluded from the comparison.
 */
class StructuredTypeDescriptorJavaBeanParityTest {

    private val compiler = StructuredTypeCompiler(
        JsonMapper.builder().addModule(kotlinModule()).build(),
    )

    private fun semanticOf(descriptor: StructuredTypeDescriptor): String = when (descriptor) {
        is StructuredTypeDescriptor.Scalar -> "scalar(${descriptor.kind}, nullable=${descriptor.nullable})"
        is StructuredTypeDescriptor.Enum -> "enum(${descriptor.values}, nullable=${descriptor.nullable})"
        is StructuredTypeDescriptor.Collection ->
            "collection(${semanticOf(descriptor.item)}, minItems=${descriptor.minItems}, nullable=${descriptor.nullable})"
        is StructuredTypeDescriptor.Object ->
            // typeName excluded: Kotlin and Java class names differ by design.
            "object(nullable=${descriptor.nullable}){" +
                descriptor.properties.joinToString(";") { p ->
                    "${p.name}(required=${p.required}, desc=${p.description}, range=${p.range}, " +
                        "type=${semanticOf(p.type)})"
                } +
                "}"
    }

    @Test
    fun `equivalent Kotlin and JavaBean DTOs compile to the same semantic descriptor`() {
        val kotlin = compiler.compile(typeOf<ParityKotlinDto>())
        val java = compiler.compile(typeOf<JavaParityFixtures.JavaParityDto>())

        // Accessors are excluded: compare names, requiredness, descriptions,
        // ranges, minItems, and nested types only.
        assertThat(semanticOf(kotlin)).isEqualTo(semanticOf(java))
    }

    @Test
    fun `JavaBean enum property compiles to first-class enum descriptor`() {
        val descriptor = compiler.compile(typeOf<JavaParityFixtures.JavaParityDto>())
        val objectDescriptor = descriptor as StructuredTypeDescriptor.Object

        val level = objectDescriptor.properties.single { it.name == "level" }
        assertThat(level.type)
            .isEqualTo(StructuredTypeDescriptor.Enum(listOf("LOW", "HIGH"), nullable = false))
    }

    @Test
    fun `JavaBean scalar field maps to scalar descriptor`() {
        val descriptor = compiler.compile(typeOf<JavaParityFixtures.JavaParityDto>())
        val objectDescriptor = descriptor as StructuredTypeDescriptor.Object

        val label = objectDescriptor.properties.single { it.name == "label" }
        assertThat(label.type)
            .isEqualTo(StructuredTypeDescriptor.Scalar(ScalarKind.STRING, nullable = false))
        assertThat(label.description).isEqualTo("The label")

        val score = objectDescriptor.properties.single { it.name == "score" }
        assertThat(score.type)
            .isEqualTo(StructuredTypeDescriptor.Scalar(ScalarKind.NUMBER, nullable = false))
        assertThat(score.range).isEqualTo(NumericRange(0.0, 100.0))
    }

    @Test
    fun `JavaBean collection with minItems compiles onto collection descriptor`() {
        val descriptor = compiler.compile(typeOf<JavaParityFixtures.JavaParityDto>())
        val objectDescriptor = descriptor as StructuredTypeDescriptor.Object

        val tags = objectDescriptor.properties.single { it.name == "tags" }
        assertThat(tags.type).isInstanceOf(StructuredTypeDescriptor.Collection::class.java)
        val collection = tags.type as StructuredTypeDescriptor.Collection
        assertThat(collection.minItems).isEqualTo(1)
        assertThat(collection.item)
            .isEqualTo(StructuredTypeDescriptor.Scalar(ScalarKind.STRING, nullable = false))
    }

    @Test
    fun `JavaBean nested object compiles to object descriptor`() {
        val descriptor = compiler.compile(typeOf<JavaParityFixtures.JavaParityDto>())
        val objectDescriptor = descriptor as StructuredTypeDescriptor.Object

        val nested = objectDescriptor.properties.single { it.name == "nested" }
        assertThat(nested.type).isInstanceOf(StructuredTypeDescriptor.Object::class.java)
        val nestedObject = nested.type as StructuredTypeDescriptor.Object
        assertThat(nestedObject.properties.single().name).isEqualTo("value")
    }

    private data class ParityKotlinDto(
        @property:AiDescription("The label")
        val label: String,
        @property:AiRange(min = 0.0, max = 100.0)
        val score: Double,
        @property:AiMinItems(1)
        val tags: List<String>,
        val nested: ParityKotlinNested,
        val level: ParityKotlinLevel,
    )

    private data class ParityKotlinNested(val value: String)

    private enum class ParityKotlinLevel { LOW, HIGH }
}
