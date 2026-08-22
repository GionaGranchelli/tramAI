package dev.tramai.structured.tck

import dev.tramai.core.annotations.AiDescription
import dev.tramai.core.annotations.AiMinItems
import dev.tramai.core.annotations.AiRange
import dev.tramai.structured.JavaBeanStructuredOutputFixtures
import dev.tramai.structured.JavaParityFixtures
import kotlin.test.Test
import kotlin.reflect.typeOf

/**
 * Epic 7.2 structured-output contract TCK.
 *
 * One fixture matrix drives the entire lifecycle per case: descriptor
 * compilation → generated schema → raw JSON shape validation →
 * deserialization → runtime value validation → deterministic repair
 * feedback. No layer maintains its own independent fixture lists.
 *
 * Also covers the #262 enum regression class explicitly: enum value
 * membership is deliberately delegated to Jackson deserialization (not moved
 * into shape validation), and the TCK verifies that delegation.
 */
class JacksonStructuredOutputContractTckTest {

    private val tck = StructuredOutputContractTck()

    @Test
    fun `required-string case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "required-string",
                targetType = typeOf<RequiredString>(),
                validJson = """{"value":"hello"}""",
                expectedRootType = "object",
                expectedSchema = mapOf(
                    "type" to SchemaExpectation("object") { it == "object" },
                    "properties.value.type" to SchemaExpectation("string") { it == "string" },
                ),
                expectedValue = { (it as RequiredString).value == "hello" },
                invalidCases = listOf(
                    InvalidCase("""{"value":null}""", FailureStage.SHAPE, "Property 'value' is required"),
                    InvalidCase("{}", FailureStage.SHAPE, "Property 'value' is required"),
                ),
            ),
        )
    }

    @Test
    fun `nullable field case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "nullable-field",
                targetType = typeOf<NullableField>(),
                validJson = """{"nickname":null}""",
                expectedSchema = mapOf(
                    "properties.nickname.nullable" to SchemaExpectation("nullable") { it == true },
                ),
                expectedValue = { (it as NullableField).nickname == null },
                invalidCases = emptyList(),
            ),
        )
    }

    @Test
    fun `optional field omitted case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "optional-field-omitted",
                targetType = typeOf<OptionalField>(),
                validJson = """{}""",
                expectedSchema = mapOf(
                    "properties.nickname.nullable" to SchemaExpectation("nullable") { it == true },
                ),
                expectedValue = { (it as OptionalField).nickname == null },
            ),
        )
    }

    @Test
    fun `nested object case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "nested-object",
                targetType = typeOf<NestedObject>(),
                validJson = """{"inner":{"value":"deep"}}""",
                expectedSchema = mapOf(
                    "properties.inner.type" to SchemaExpectation("object") { it == "object" },
                    "properties.inner.properties.value.type" to SchemaExpectation("string") { it == "string" },
                ),
                expectedValue = { (it as NestedObject).inner.value == "deep" },
                invalidCases = listOf(
                    InvalidCase("""{"inner":{"value":null}}""", FailureStage.SHAPE, "Property 'inner'.'value' is required"),
                    InvalidCase("""{"inner":{}}""", FailureStage.SHAPE, "Property 'inner'.'value' is required"),
                ),
            ),
        )
    }

    @Test
    fun `generic collection case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "generic-collection",
                targetType = typeOf<GenericCollection>(),
                validJson = """{"items":["a","b"]}""",
                expectedSchema = mapOf(
                    "properties.items.type" to SchemaExpectation("array") { it == "array" },
                    "properties.items.items.type" to SchemaExpectation("string items") { it == "string" },
                ),
                expectedValue = { (it as GenericCollection).items == listOf("a", "b") },
            ),
        )
    }

    @Test
    fun `nested collections case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "nested-collections",
                targetType = typeOf<NestedCollections>(),
                validJson = """{"matrix":[[1,2],[3]]}""",
                expectedSchema = mapOf(
                    "properties.matrix.type" to SchemaExpectation("array") { it == "array" },
                    "properties.matrix.items.type" to SchemaExpectation("inner array") { it == "array" },
                    "properties.matrix.items.items.type" to SchemaExpectation("int items") { it == "integer" },
                ),
                expectedValue = { (it as NestedCollections).matrix == listOf(listOf(1, 2), listOf(3)) },
            ),
        )
    }

    @Test
    fun `root array case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "root-array",
                targetType = typeOf<List<String>>(),
                validJson = """["a","b"]""",
                expectedRootType = "array",
                expectedSchema = mapOf(
                    "items.type" to SchemaExpectation("string items") { it == "string" },
                ),
                expectedValue = { it == listOf("a", "b") },
            ),
        )
    }

    @Test
    fun `range constraint case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "range-constraint",
                targetType = typeOf<RangeConstrained>(),
                validJson = """{"confidence":0.5}""",
                expectedSchema = mapOf(
                    "properties.confidence.minimum" to SchemaExpectation("min 0.0") { it == 0.0 },
                    "properties.confidence.maximum" to SchemaExpectation("max 1.0") { it == 1.0 },
                ),
                expectedValue = { (it as RangeConstrained).confidence == 0.5 },
                invalidCases = listOf(
                    InvalidCase("""{"confidence":1.5}""", FailureStage.VALUE_VALIDATION, "must be between 0.0 and 1.0"),
                ),
            ),
        )
    }

    @Test
    fun `minItems constraint case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "min-items-constraint",
                targetType = typeOf<MinItemsConstrained>(),
                validJson = """{"tags":["a","b"]}""",
                expectedSchema = mapOf(
                    "properties.tags.minItems" to SchemaExpectation("minItems 1") { it == 1 },
                ),
                expectedValue = { (it as MinItemsConstrained).tags == listOf("a", "b") },
                invalidCases = listOf(
                    InvalidCase("""{"tags":[]}""", FailureStage.VALUE_VALIDATION, "must contain at least 1 items"),
                ),
            ),
        )
    }

    @Test
    fun `description is schema-only case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "description-schema-only",
                targetType = typeOf<Described>(),
                validJson = """{"value":"x"}""",
                expectedSchema = mapOf(
                    "properties.value.description" to SchemaExpectation("description present") {
                        it == "A described value"
                    },
                ),
                expectedValue = { (it as Described).value == "x" },
                // Description has no runtime semantic effect: any string passes.
                invalidCases = emptyList(),
            ),
        )
    }

    @Test
    fun `unknown property case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "unknown-property",
                targetType = typeOf<RequiredString>(),
                validJson = """{"value":"hello"}""",
                expectedSchema = mapOf(
                    "additionalProperties" to SchemaExpectation("false") { it == false },
                ),
                // The schema declares additionalProperties:false, but the shape
                // validator does not own that rule — it is delegated downstream
                // to Jackson deserialization, which rejects the unknown key.
                invalidCases = listOf(
                    InvalidCase("""{"value":"x","unknownKey":42}""", FailureStage.DESERIALIZATION, "could not be parsed into the requested output type"),
                ),
            ),
        )
    }

    @Test
    fun `recursive type case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "recursive-type",
                targetType = typeOf<JavaBeanStructuredOutputFixtures.JavaRecursiveNode>(),
                validJson = "",
                expectedCompileFailure = "Recursive structured output type is unsupported",
            ),
        )
    }

    @Test
    fun `unsupported map case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "unsupported-map",
                targetType = typeOf<Map<String, String>>(),
                validJson = "",
                expectedCompileFailure = "Unsupported structured output type",
            ),
        )
    }

    @Test
    fun `malformed JSON case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "malformed-json",
                targetType = typeOf<RequiredString>(),
                validJson = """{"value":"ok"}""",
                invalidCases = listOf(
                    // Balanced braces, unparseable content → JSON_PARSE.
                    InvalidCase("""{"value": }""", FailureStage.JSON_PARSE),
                ),
            ),
        )
    }

    @Test
    fun `prose around JSON case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "prose-around-json",
                targetType = typeOf<RequiredString>(),
                validJson = """Sure! Here is the result: {"value":"hello"}""",
                expectedValue = { (it as RequiredString).value == "hello" },
            ),
        )
    }

    @Test
    fun `fenced JSON case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "fenced-json",
                targetType = typeOf<RequiredString>(),
                validJson = "```json\n{\"value\":\"hello\"}\n```",
                expectedValue = { (it as RequiredString).value == "hello" },
            ),
        )
    }

    @Test
    fun `deterministic repair feedback case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "deterministic-repair",
                targetType = typeOf<RequiredString>(),
                validJson = """{"value":"hello"}""",
                invalidCases = listOf(
                    InvalidCase("""{"value":null}""", FailureStage.SHAPE, "Property 'value' is required"),
                    InvalidCase("""{"value":"x","unknownKey":42}""", FailureStage.DESERIALIZATION, "could not be parsed into the requested output type"),
                ),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Enum cases (#262 regression class)
    // ------------------------------------------------------------------

    @Test
    fun `root enum case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "root-enum",
                targetType = typeOf<Level>(),
                validJson = null, // bare scalars are not extractable (objects/arrays only)
                expectedRootType = "string",
                expectedSchema = mapOf(
                    "enum" to SchemaExpectation("declared values") { it == listOf("LOW", "HIGH") },
                ),
                invalidCases = listOf(
                    // Bare string: extractor cannot find a JSON object/array.
                    InvalidCase(""" "YOLO" """, FailureStage.EXTRACTION),
                    // Object form: extractable, but Jackson cannot deserialize into an enum.
                    InvalidCase("""{"name":"LOW","ordinal":0}""", FailureStage.DESERIALIZATION, "could not be parsed into the requested output type"),
                ),
            ),
        )
    }

    @Test
    fun `nested enum case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "nested-enum",
                targetType = typeOf<EnumHolder>(),
                validJson = """{"level":"LOW"}""",
                expectedSchema = mapOf(
                    "properties.level.type" to SchemaExpectation("string") { it == "string" },
                    "properties.level.enum" to SchemaExpectation("declared values") { it == listOf("LOW", "HIGH") },
                ),
                expectedValue = { (it as EnumHolder).level == Level.LOW },
                invalidCases = listOf(
                    InvalidCase("""{"level":"UNKNOWN"}""", FailureStage.DESERIALIZATION, "could not be parsed into the requested output type"),
                ),
            ),
        )
    }

    @Test
    fun `nullable enum case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "nullable-enum",
                targetType = typeOf<NullableEnumHolder>(),
                validJson = """{"level":null}""",
                expectedSchema = mapOf(
                    "properties.level.nullable" to SchemaExpectation("nullable") { it == true },
                ),
                expectedValue = { (it as NullableEnumHolder).level == null },
            ),
        )
    }

    @Test
    fun `every declared enum value succeeds`() {
        listOf("LOW", "HIGH").forEach { value ->
            tck.verify(
                StructuredOutputContractCase(
                    id = "enum-value-$value",
                    targetType = typeOf<EnumHolder>(),
                    validJson = """{"level":"$value"}""",
                    expectedValue = { (it as EnumHolder).level.name == value },
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // JavaBean cases (same observable contract, Java source)
    // ------------------------------------------------------------------

    @Test
    fun `java bean annotated scalar case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "java-bean-scalar",
                targetType = typeOf<JavaBeanStructuredOutputFixtures.JavaScoredResult>(),
                validJson = """{"status":"ok","confidence":0.8,"reasons":["r1"]}""",
                expectedRootType = "object",
                expectedSchema = mapOf(
                    "properties.confidence.minimum" to SchemaExpectation("min") { it == 0.0 },
                    "properties.confidence.maximum" to SchemaExpectation("max") { it == 1.0 },
                    "properties.reasons.minItems" to SchemaExpectation("minItems") { it == 1 },
                ),
                invalidCases = listOf(
                    InvalidCase("""{"status":"ok","confidence":2.0,"reasons":["r1"]}""", FailureStage.VALUE_VALIDATION, "must be between 0.0 and 1.0"),
                    InvalidCase("""{"status":"ok","confidence":0.8,"reasons":[]}""", FailureStage.VALUE_VALIDATION, "must contain at least 1 items"),
                    InvalidCase("""{"confidence":0.8,"reasons":["r1"]}""", FailureStage.SHAPE, "Property 'status' is required"),
                ),
            ),
        )
    }

    @Test
    fun `java bean missing primitive field case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "java-bean-missing-primitive",
                targetType = typeOf<JavaBeanStructuredOutputFixtures.JavaPrimitiveResult>(),
                validJson = """{"count":1,"active":true,"timestamp":100,"score":0.5}""",
                invalidCases = listOf(
                    // Missing primitive must fail at SHAPE, before Jackson's
                    // primitive default (0/false) can hide the problem.
                    InvalidCase("""{"active":true,"timestamp":100,"score":0.5}""", FailureStage.SHAPE, "Property 'count' is required"),
                    InvalidCase("""{"count":1,"active":true,"timestamp":100,"score":0.5,"extra":1}""", FailureStage.DESERIALIZATION, "could not be parsed into the requested output type"),
                ),
            ),
        )
    }

    @Test
    fun `java parity enum case`() {
        tck.verify(
            StructuredOutputContractCase(
                id = "java-parity-enum",
                targetType = typeOf<JavaParityFixtures.JavaParityDto>(),
                validJson = """{"label":"x","score":1.0,"tags":["t"],"nested":{"value":"v"},"level":"HIGH"}""",
                expectedSchema = mapOf(
                    "properties.level.type" to SchemaExpectation("string") { it == "string" },
                    "properties.level.enum" to SchemaExpectation("enum values") { it == listOf("LOW", "HIGH") },
                ),
                invalidCases = listOf(
                    InvalidCase("""{"label":"x","score":1.0,"tags":["t"],"nested":{"value":"v"},"level":"NOPE"}""", FailureStage.DESERIALIZATION, "could not be parsed into the requested output type"),
                ),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private data class RequiredString(val value: String)

    private data class NullableField(val nickname: String?)

    private data class OptionalField(val nickname: String? = null)

    private data class NestedObject(val inner: Inner)

    private data class Inner(val value: String)

    private data class GenericCollection(val items: List<String>)

    private data class NestedCollections(val matrix: List<List<Int>>)

    private data class RangeConstrained(
        @property:AiRange(min = 0.0, max = 1.0)
        val confidence: Double,
    )

    private data class MinItemsConstrained(
        @property:AiMinItems(1)
        val tags: List<String>,
    )

    private data class Described(
        @property:AiDescription("A described value")
        val value: String,
    )

    private enum class Level { LOW, HIGH }

    private data class EnumHolder(val level: Level)

    private data class NullableEnumHolder(val level: Level?)
}
