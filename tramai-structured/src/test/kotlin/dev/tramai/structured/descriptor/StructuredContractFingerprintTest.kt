package dev.tramai.structured.descriptor

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import dev.tramai.core.annotations.AiDescription
import dev.tramai.core.annotations.AiMinItems
import dev.tramai.core.annotations.AiRange
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test
import kotlin.reflect.typeOf

/**
 * Fingerprint stability tests for Epic 7.1: the canonical SHA-256 fingerprint
 * must be stable across identical semantic contracts and change exactly when
 * the contract changes. Runtime accessor identity must never leak into it.
 */
class StructuredContractFingerprintTest {

    private val compiler = StructuredTypeCompiler(
        JsonMapper.builder().addModule(kotlinModule()).build(),
    )
    private val fingerprint = StructuredContractFingerprint()

    @Test
    fun `same type yields the same fingerprint`() {
        val first = fingerprint.fingerprint(compiler.compile(typeOf<FpObject>()))
        val second = fingerprint.fingerprint(compiler.compile(typeOf<FpObject>()))

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `fingerprint is a stable 64-char sha256 hex`() {
        val fp = fingerprint.fingerprint(compiler.compile(typeOf<FpObject>()))

        assertThat(fp).matches("[0-9a-f]{64}")
    }

    @Test
    fun `property added changes fingerprint`() {
        val before = fingerprint.fingerprint(compiler.compile(typeOf<FpObjectV1>()))
        val after = fingerprint.fingerprint(compiler.compile(typeOf<FpObjectV2>()))

        assertThat(after).isNotEqualTo(before)
    }

    @Test
    fun `requiredness change changes fingerprint`() {
        val required = fingerprint.fingerprint(compiler.compile(typeOf<FpNullable>()))
        val optional = fingerprint.fingerprint(compiler.compile(typeOf<FpRequired>()))

        assertThat(optional).isNotEqualTo(required)
    }

    @Test
    fun `range change changes fingerprint`() {
        val narrow = fingerprint.fingerprint(compiler.compile(typeOf<FpNarrowRange>()))
        val wide = fingerprint.fingerprint(compiler.compile(typeOf<FpWideRange>()))

        assertThat(wide).isNotEqualTo(narrow)
    }

    @Test
    fun `enum member change changes fingerprint`() {
        val two = fingerprint.fingerprint(compiler.compile(typeOf<FpEnumTwo>()))
        val three = fingerprint.fingerprint(compiler.compile(typeOf<FpEnumThree>()))

        assertThat(three).isNotEqualTo(two)
    }

    @Test
    fun `description change changes fingerprint`() {
        val plain = fingerprint.fingerprint(compiler.compile(typeOf<FpNoDescription>()))
        val described = fingerprint.fingerprint(compiler.compile(typeOf<FpWithDescription>()))

        assertThat(described).isNotEqualTo(plain)
    }

    @Test
    fun `minItems change changes fingerprint`() {
        val one = fingerprint.fingerprint(compiler.compile(typeOf<FpMinOne>()))
        val two = fingerprint.fingerprint(compiler.compile(typeOf<FpMinTwo>()))

        assertThat(two).isNotEqualTo(one)
    }

    @Test
    fun `different accessor identity does not change fingerprint`() {
        // The same type compiled twice produces two distinct accessor closures,
        // but the semantic fingerprint must be identical.
        val first = fingerprint.fingerprint(compiler.compile(typeOf<FpObject>()))
        val second = fingerprint.fingerprint(compiler.compile(typeOf<FpObject>()))

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `property reordering changes fingerprint`() {
        // Compilers always sort properties, so the reordering sensitivity of the
        // fingerprint WALK is tested by constructing descriptors directly.
        val propertyA = StructuredPropertyDescriptor(
            name = "a",
            type = StructuredTypeDescriptor.Scalar(ScalarKind.STRING, nullable = false),
            required = true,
            description = null,
            range = null,
            accessor = ValueAccessor { null },
        )
        val propertyB = propertyA.copy(name = "b")

        val ab = StructuredTypeDescriptor.Object(
            typeName = "Reordered",
            properties = listOf(propertyA, propertyB),
            nullable = false,
        )
        val ba = ab.copy(properties = listOf(propertyB, propertyA))

        assertThat(fingerprint.fingerprint(ab)).isNotEqualTo(fingerprint.fingerprint(ba))
    }

    // -- Fixtures --

    private data class FpObject(
        val name: String,
        val score: Double,
    )

    private data class FpObjectV1(val name: String)

    private data class FpObjectV2(val name: String, val extra: String)

    private data class FpNullable(val value: String?)

    private data class FpRequired(val value: String)

    private data class FpNarrowRange(
        @property:AiRange(min = 0.0, max = 0.5)
        val value: Double,
    )

    private data class FpWideRange(
        @property:AiRange(min = 0.0, max = 1.0)
        val value: Double,
    )

    private enum class FpEnumTwo { A, B }

    private enum class FpEnumThree { A, B, C }

    private data class FpNoDescription(val value: String)

    private data class FpWithDescription(
        @property:AiDescription("A described value")
        val value: String,
    )

    private data class FpMinOne(
        @property:AiMinItems(1)
        val items: List<String>,
    )

    private data class FpMinTwo(
        @property:AiMinItems(2)
        val items: List<String>,
    )
}
