package dev.tramai.structured.descriptor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import dev.tramai.core.annotations.AiMinItems
import dev.tramai.core.annotations.AiRange
import dev.tramai.core.structured.StructuredOutputResult
import dev.tramai.structured.JacksonStructuredOutputHandler
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test
import kotlin.reflect.typeOf

/**
 * Schema/validation agreement tests for Epic 7.1: for each descriptor kind,
 * the schema accepts a shape, the raw shape validator accepts the same shape,
 * and the deserializer + value validator accept the resulting value. This is
 * the formal acceptance test — no independent dispatch trees may drift apart.
 */
class StructuredDescriptorSchemaValidationAgreementTest {

    private val handler = JacksonStructuredOutputHandler()
    private val mapper: ObjectMapper = JsonMapper.builder().addModule(kotlinModule()).build()

    @Test
    fun `scalar round-trips through schema, shape, and value validation`() {
        val contract = handler.createContract(typeOf<ScalarHolder>())
        val schema = mapper.readTree(contract.schemaJson)

        assertThat(schema["properties"]["value"]["type"].asText()).isEqualTo("string")

        val result = handler.analyze("""{"value":"hello"}""", typeOf<ScalarHolder>())
        assertThat(result).isInstanceOf(StructuredOutputResult.Success::class.java)
    }

    @Test
    fun `enum round-trips through schema, shape, and value validation`() {
        val contract = handler.createContract(typeOf<EnumHolder>())
        val schema = mapper.readTree(contract.schemaJson)

        assertThat(schema["properties"]["level"]["type"].asText()).isEqualTo("string")
        assertThat(schema["properties"]["level"]["enum"].map { it.asText() })
            .containsExactly("LOW", "HIGH")

        val result = handler.analyze("""{"level":"HIGH"}""", typeOf<EnumHolder>())
        assertThat(result).isInstanceOf(StructuredOutputResult.Success::class.java)
        assertThat(((result as StructuredOutputResult.Success).value as EnumHolder).level.name)
            .isEqualTo("HIGH")
    }

    @Test
    fun `collection with minItems enforces schema and value validation identically`() {
        val contract = handler.createContract(typeOf<CollectionHolder>())
        val schema = mapper.readTree(contract.schemaJson)

        assertThat(schema["properties"]["items"]["type"].asText()).isEqualTo("array")
        assertThat(schema["properties"]["items"]["minItems"].asInt()).isEqualTo(1)

        // Schema says minItems=1, shape accepts the array, value validation rejects empty.
        val empty = handler.analyze("""{"items":[]}""", typeOf<CollectionHolder>())
        assertThat(empty).isInstanceOf(StructuredOutputResult.Failure::class.java)
        assertThat((empty as StructuredOutputResult.Failure).errorSummary)
            .isEqualTo("Structured output failed validation")

        val valid = handler.analyze("""{"items":["one"]}""", typeOf<CollectionHolder>())
        assertThat(valid).isInstanceOf(StructuredOutputResult.Success::class.java)
    }

    @Test
    fun `range constraint is enforced by value validation matching schema`() {
        val contract = handler.createContract(typeOf<RangeHolder>())
        val schema = mapper.readTree(contract.schemaJson)

        assertThat(schema["properties"]["confidence"]["minimum"].asDouble()).isEqualTo(0.0)
        assertThat(schema["properties"]["confidence"]["maximum"].asDouble()).isEqualTo(1.0)

        val outOfRange = handler.analyze("""{"confidence":1.5}""", typeOf<RangeHolder>())
        assertThat(outOfRange).isInstanceOf(StructuredOutputResult.Failure::class.java)
        assertThat((outOfRange as StructuredOutputResult.Failure).errorSummary)
            .isEqualTo("Structured output failed validation")

        val valid = handler.analyze("""{"confidence":0.5}""", typeOf<RangeHolder>())
        assertThat(valid).isInstanceOf(StructuredOutputResult.Success::class.java)
    }

    @Test
    fun `required property missing is rejected by shape validation`() {
        val result = handler.analyze("""{"nickname":"x"}""", typeOf<RequiredHolder>())

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        assertThat((result as StructuredOutputResult.Failure).errorSummary)
            .isEqualTo("Structured output failed validation")
    }

    @Test
    fun `null allowed for nullable property`() {
        val result = handler.analyze("""{"nickname":null}""", typeOf<NullableHolder>())

        assertThat(result).isInstanceOf(StructuredOutputResult.Success::class.java)
        assertThat((result as StructuredOutputResult.Success).value as NullableHolder)
            .isEqualTo(NullableHolder(null))
    }

    @Test
    fun `null item allowed in nullable item collection`() {
        val result = handler.analyze("""{"items":["a",null,"b"]}""", typeOf<NullableItemCollection>())

        assertThat(result).isInstanceOf(StructuredOutputResult.Success::class.java)
        assertThat(((result as StructuredOutputResult.Success).value as NullableItemCollection).items)
            .containsExactly("a", null, "b")
    }

    @Test
    fun `null item rejected in non-nullable item collection`() {
        val result = handler.analyze("""{"items":["a",null]}""", typeOf<NonNullableItemCollection>())

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        assertThat((result as StructuredOutputResult.Failure).errorSummary)
            .isEqualTo("Structured output failed validation")
    }

    // -- Fixtures --

    private data class ScalarHolder(val value: String)

    private data class EnumHolder(val level: Level)

    private enum class Level { LOW, HIGH }

    private data class CollectionHolder(
        @property:AiMinItems(1)
        val items: List<String>,
    )

    private data class RangeHolder(
        @property:AiRange(min = 0.0, max = 1.0)
        val confidence: Double,
    )

    private data class RequiredHolder(
        val requiredValue: String,
        val nickname: String?,
    )

    private data class NullableHolder(val nickname: String?)

    private data class NullableItemCollection(val items: List<String?>)

    private data class NonNullableItemCollection(val items: List<String>)
}
