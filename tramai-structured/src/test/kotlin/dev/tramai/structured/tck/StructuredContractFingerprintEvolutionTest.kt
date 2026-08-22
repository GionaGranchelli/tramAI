package dev.tramai.structured.tck

import dev.tramai.core.annotations.AiDescription
import dev.tramai.core.annotations.AiMinItems
import dev.tramai.core.annotations.AiRange
import dev.tramai.structured.descriptor.NumericRange
import dev.tramai.structured.descriptor.ScalarKind
import dev.tramai.structured.descriptor.StructuredContractFingerprint
import dev.tramai.structured.descriptor.StructuredDescriptorCache
import dev.tramai.structured.descriptor.StructuredPropertyDescriptor
import dev.tramai.structured.descriptor.StructuredTypeCompiler
import dev.tramai.structured.descriptor.StructuredTypeDescriptor
import dev.tramai.structured.descriptor.ValueAccessor
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test
import kotlin.reflect.typeOf

/**
 * Epic 7.2 fingerprint-evolution contract.
 *
 * One baseline descriptor, mutated exactly one semantic element at a time,
 * while holding everything else constant — so a fingerprint change is
 * attributable to precisely that mutation. Inverse tests prove runtime
 * accessors, compiler instances, and (critically) Object.typeName never leak
 * into the fingerprint.
 */
class StructuredContractFingerprintEvolutionTest {

    private val fingerprint = StructuredContractFingerprint()

    private val stringProp = StructuredPropertyDescriptor(
        name = "value",
        type = StructuredTypeDescriptor.Scalar(ScalarKind.STRING, nullable = false),
        required = true,
        description = null,
        range = null,
        accessor = ValueAccessor { null },
    )

    private fun objectDescriptor(
        properties: List<StructuredPropertyDescriptor> = listOf(stringProp),
        nullable: Boolean = false,
        typeName: String = "Baseline",
    ): StructuredTypeDescriptor.Object = StructuredTypeDescriptor.Object(
        typeName = typeName,
        properties = properties,
        nullable = nullable,
    )

    private fun fp(descriptor: StructuredTypeDescriptor): String =
        fingerprint.fingerprint(descriptor)

    // ------------------------------------------------------------------
    // One-mutation-at-a-time: each semantic element must change the hash
    // ------------------------------------------------------------------

    @Test
    fun `baseline fingerprint is stable`() {
        assertThat(fp(objectDescriptor())).isEqualTo(fp(objectDescriptor()))
    }

    @Test
    fun `property added changes fingerprint`() {
        val baseline = objectDescriptor()
        val mutated = objectDescriptor(
            properties = listOf(
                stringProp,
                stringProp.copy(name = "extra"),
            ),
        )
        assertThat(fp(mutated)).isNotEqualTo(fp(baseline))
    }

    @Test
    fun `property removed changes fingerprint`() {
        val baseline = objectDescriptor(properties = listOf(stringProp, stringProp.copy(name = "extra")))
        val mutated = objectDescriptor(properties = listOf(stringProp))
        assertThat(fp(mutated)).isNotEqualTo(fp(baseline))
    }

    @Test
    fun `requiredness change changes fingerprint`() {
        val baseline = objectDescriptor()
        val mutated = objectDescriptor(properties = listOf(stringProp.copy(required = false)))
        assertThat(fp(mutated)).isNotEqualTo(fp(baseline))
    }

    @Test
    fun `descriptor nullability change changes fingerprint`() {
        val baseline = objectDescriptor(nullable = false)
        val mutated = objectDescriptor(nullable = true)
        assertThat(fp(mutated)).isNotEqualTo(fp(baseline))
    }

    @Test
    fun `scalar kind change changes fingerprint`() {
        val baseline = objectDescriptor()
        val mutated = objectDescriptor(
            properties = listOf(
                stringProp.copy(type = StructuredTypeDescriptor.Scalar(ScalarKind.INTEGER, nullable = false)),
            ),
        )
        assertThat(fp(mutated)).isNotEqualTo(fp(baseline))
    }

    @Test
    fun `numeric range change changes fingerprint`() {
        val baseline = objectDescriptor(properties = listOf(stringProp.copy(range = NumericRange(0.0, 1.0))))
        val mutated = objectDescriptor(properties = listOf(stringProp.copy(range = NumericRange(0.0, 100.0))))
        assertThat(fp(mutated)).isNotEqualTo(fp(baseline))
    }

    @Test
    fun `description change changes fingerprint`() {
        val baseline = objectDescriptor(properties = listOf(stringProp.copy(description = "old")))
        val mutated = objectDescriptor(properties = listOf(stringProp.copy(description = "new")))
        assertThat(fp(mutated)).isNotEqualTo(fp(baseline))
    }

    @Test
    fun `minItems change changes fingerprint`() {
        val baseline = objectDescriptor(
            properties = listOf(
                stringProp.copy(
                    type = StructuredTypeDescriptor.Collection(
                        item = StructuredTypeDescriptor.Scalar(ScalarKind.STRING, nullable = false),
                        minItems = 1,
                        nullable = false,
                    ),
                ),
            ),
        )
        val mutated = objectDescriptor(
            properties = listOf(
                stringProp.copy(
                    type = StructuredTypeDescriptor.Collection(
                        item = StructuredTypeDescriptor.Scalar(ScalarKind.STRING, nullable = false),
                        minItems = 2,
                        nullable = false,
                    ),
                ),
            ),
        )
        assertThat(fp(mutated)).isNotEqualTo(fp(baseline))
    }

    @Test
    fun `collection item contract change changes fingerprint`() {
        val baseline = objectDescriptor(
            properties = listOf(
                stringProp.copy(
                    type = StructuredTypeDescriptor.Collection(
                        item = StructuredTypeDescriptor.Scalar(ScalarKind.STRING, nullable = false),
                        minItems = null,
                        nullable = false,
                    ),
                ),
            ),
        )
        val mutated = objectDescriptor(
            properties = listOf(
                stringProp.copy(
                    type = StructuredTypeDescriptor.Collection(
                        item = StructuredTypeDescriptor.Scalar(ScalarKind.INTEGER, nullable = false),
                        minItems = null,
                        nullable = false,
                    ),
                ),
            ),
        )
        assertThat(fp(mutated)).isNotEqualTo(fp(baseline))
    }

    @Test
    fun `enum membership change changes fingerprint`() {
        val baseline = objectDescriptor(
            properties = listOf(
                stringProp.copy(type = StructuredTypeDescriptor.Enum(listOf("LOW", "HIGH"), nullable = false)),
            ),
        )
        val mutated = objectDescriptor(
            properties = listOf(
                stringProp.copy(type = StructuredTypeDescriptor.Enum(listOf("LOW", "HIGH", "URGENT"), nullable = false)),
            ),
        )
        assertThat(fp(mutated)).isNotEqualTo(fp(baseline))
    }

    @Test
    fun `nested descriptor change changes fingerprint`() {
        val baseline = objectDescriptor(
            properties = listOf(
                stringProp.copy(
                    name = "nested",
                    type = objectDescriptor(typeName = "Inner"),
                ),
            ),
        )
        val mutated = objectDescriptor(
            properties = listOf(
                stringProp.copy(
                    name = "nested",
                    type = objectDescriptor(
                        typeName = "Inner",
                        properties = listOf(stringProp.copy(name = "different")),
                    ),
                ),
            ),
        )
        assertThat(fp(mutated)).isNotEqualTo(fp(baseline))
    }

    // ------------------------------------------------------------------
    // Inverse tests: non-semantic differences must NOT change the hash
    // ------------------------------------------------------------------

    @Test
    fun `different accessor does not change fingerprint`() {
        val withAccessorA = objectDescriptor(
            properties = listOf(stringProp.copy(accessor = ValueAccessor { "a" })),
        )
        val withAccessorB = objectDescriptor(
            properties = listOf(stringProp.copy(accessor = ValueAccessor { "b" })),
        )
        assertThat(fp(withAccessorA)).isEqualTo(fp(withAccessorB))
    }

    @Test
    fun `same semantic descriptor with different typeName has same fingerprint`() {
        // The core Epic 7.2 pressure-test: equivalent Kotlin and JavaBean
        // DTOs have different class names but the same JSON contract, so
        // the fingerprint must not depend on typeName.
        val kotlinNamed = objectDescriptor(typeName = "dev.tramai.structured.KotlinDto")
        val javaNamed = objectDescriptor(typeName = "dev.tramai.structured.JavaDto")

        assertThat(fp(javaNamed)).isEqualTo(fp(kotlinNamed))
    }

    @Test
    fun `equivalent repeated compilation has same fingerprint`() {
        val compiler = StructuredTypeCompiler(
            JsonMapper.builder().addModule(kotlinModule()).build(),
        )
        assertThat(fp(compiler.compile(typeOf<FpKotlinDto>()))).isEqualTo(fp(compiler.compile(typeOf<FpKotlinDto>())))
    }

    @Test
    fun `different compiler instances have same fingerprint`() {
        val compilerA = StructuredTypeCompiler(JsonMapper.builder().addModule(kotlinModule()).build())
        val compilerB = StructuredTypeCompiler(JsonMapper.builder().addModule(kotlinModule()).build())

        assertThat(fp(compilerA.compile(typeOf<FpKotlinDto>()))).isEqualTo(fp(compilerB.compile(typeOf<FpKotlinDto>())))
    }

    @Test
    fun `cache never changes the compiled fingerprint`() {
        val compiler = StructuredTypeCompiler(JsonMapper.builder().addModule(kotlinModule()).build())
        val cache = StructuredDescriptorCache()

        val direct = compiler.compile(typeOf<FpKotlinDto>())
        val cached = cache.getOrCompile(typeOf<FpKotlinDto>()) { compiler.compile(it) }

        assertThat(fp(cached)).isEqualTo(fp(direct))
    }

    // ------------------------------------------------------------------
    // Compiled-fixture mutation tests (real compiler, class names differ)
    // ------------------------------------------------------------------

    @Test
    fun `compiled equivalent Kotlin DTOs with different names fingerprint identically`() {
        val compiler = StructuredTypeCompiler(JsonMapper.builder().addModule(kotlinModule()).build())

        // Two unrelated classes with the same semantic shape. If typeName
        // leaked into the hash, this assertion would fail.
        assertThat(fp(compiler.compile(typeOf<FpKotlinDto>()))).isEqualTo(fp(compiler.compile(typeOf<FpKotlinDtoSameShape>())))
    }

    private data class FpKotlinDto(
        val value: String,
        val score: Double,
    )

    private data class FpKotlinDtoSameShape(
        val value: String,
        val score: Double,
    )
}
