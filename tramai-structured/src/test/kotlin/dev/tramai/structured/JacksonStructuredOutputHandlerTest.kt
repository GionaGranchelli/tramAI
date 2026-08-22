package dev.tramai.structured

import dev.tramai.core.annotations.AiDescription
import dev.tramai.core.annotations.AiMinItems
import dev.tramai.core.annotations.AiRange
import dev.tramai.core.structured.StructuredOutputResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test
import kotlin.reflect.typeOf

class JacksonStructuredOutputHandlerTest {

    private val handler = JacksonStructuredOutputHandler()

    @Test
    fun `generates schema with nullability and custom annotations`() {
        val contract = handler.createContract(typeOf<SpendAnalysis>())

        assertThat(contract.schemaJson)
            .contains("\"type\" : \"object\"")
            .contains("\"description\" : \"Total spend in USD, always positive\"")
            .contains("\"minimum\" : 0.0")
            .contains("\"maximum\" : 1.0")
            .contains("\"minItems\" : 1")
            .contains("\"nickname\"")
    }

    @Test
    fun `parses fenced json successfully`() {
        val result = handler.analyze(
            rawResponse = """
                ```json
                {
                  "totalSpend": 1200.5,
                  "recommendations": ["right-size"],
                  "confidence": 0.95,
                  "nickname": null
                }
                ```
            """.trimIndent(),
            targetType = typeOf<SpendAnalysis>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Success::class.java)
        val success = result as StructuredOutputResult.Success
        assertThat(success.value).isEqualTo(
            SpendAnalysis(
                totalSpend = 1200.5,
                recommendations = listOf("right-size"),
                confidence = 0.95,
                nickname = null,
            ),
        )
    }

    @Test
    fun `returns validation failure when annotated constraints are violated`() {
        val result = handler.analyze(
            rawResponse = """
                {
                  "totalSpend": 1200.5,
                  "recommendations": [],
                  "confidence": 1.4,
                  "nickname": "finops"
                }
            """.trimIndent(),
            targetType = typeOf<SpendAnalysis>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).isEqualTo("Structured output failed validation")
        assertThat(failure.feedbackMessage).contains("failed validation")
    }

    @Test
    fun `returns parse failure when no json payload can be extracted`() {
        val result = handler.analyze(
            rawResponse = "No structured payload available",
            targetType = typeOf<SpendAnalysis>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).isEqualTo("Could not extract JSON content from the model response")
    }

    @Test
    fun `rejects unsupported root types during schema generation`() {
        assertThatThrownBy {
            handler.createContract(typeOf<Map<String, String>>())
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Unsupported structured output type")
    }

    // -----------------------------------------------------------------------
    // Enum contract: schema generation, shape validation, and deserialization
    // must all agree that a Kotlin enum is a flat string (regression: the
    // schema used to describe enums as name/ordinal objects, which no model
    // output could satisfy and still deserialize).
    // -----------------------------------------------------------------------

    @Test
    fun `enum root type generates a string enum schema`() {
        val schema = handler.generateSchema(typeOf<Risk>())
            .let { jackson.readTree(it) }

        assertThat(schema.get("type").asText()).isEqualTo("string")
        assertThat(schema.get("enum").map { it.asText() })
            .containsExactly("LOW", "HIGH")
    }

    @Test
    fun `nested enum property generates a string enum schema`() {
        val schema = handler.generateSchema(typeOf<Assessment>())
            .let { jackson.readTree(it) }
        val riskSchema = schema.get("properties").get("risk")

        assertThat(riskSchema.get("type").asText()).isEqualTo("string")
        assertThat(riskSchema.get("enum").map { it.asText() })
            .containsExactly("LOW", "HIGH")
    }

    @Test
    fun `flat string enum value passes shape validation and deserializes`() {
        val result = handler.analyze(
            rawResponse = """{"risk":"LOW"}""",
            targetType = typeOf<Assessment>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Success::class.java)
        val success = result as StructuredOutputResult.Success
        assertThat((success.value as Assessment).risk).isEqualTo(Risk.LOW)
    }

    @Test
    fun `invalid enum value is rejected cleanly`() {
        val result = handler.analyze(
            rawResponse = """{"risk":"YOLO"}""",
            targetType = typeOf<Assessment>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).isEqualTo("Could not deserialize the JSON payload")
    }

    @Test
    fun `nullable enum keeps nullability semantics in schema and parsing`() {
        val schema = handler.generateSchema(typeOf<NullableAssessment>())
            .let { jackson.readTree(it) }
        val riskSchema = schema.get("properties").get("risk")
        assertThat(riskSchema.get("type").asText()).isEqualTo("string")
        assertThat(riskSchema.has("nullable")).isTrue()
        assertThat(riskSchema.get("nullable").asBoolean()).isTrue()

        val result = handler.analyze(
            rawResponse = """{"risk":null}""",
            targetType = typeOf<NullableAssessment>(),
        )
        assertThat(result).isInstanceOf(StructuredOutputResult.Success::class.java)
        assertThat((result as StructuredOutputResult.Success).value as NullableAssessment)
            .isEqualTo(NullableAssessment(risk = null))
    }

    @Test
    fun `schema and parser agree for every declared enum value`() {
        val schema = handler.generateSchema(typeOf<Assessment>())
            .let { jackson.readTree(it) }
        val declaredNames = schema.get("properties").get("risk").get("enum")
            .map { it.asText() }

        assertThat(declaredNames).containsExactly("LOW", "HIGH")
        declaredNames.forEach { name ->
            val result = handler.analyze(
                rawResponse = """{"risk":"$name"}""",
                targetType = typeOf<Assessment>(),
            )
            assertThat(result).isInstanceOf(StructuredOutputResult.Success::class.java)
            assertThat(((result as StructuredOutputResult.Success).value as Assessment).risk.name)
                .isEqualTo(name)
        }
    }

    @Test
    fun `pre-fix object form of an enum is rejected`() {
        // The historical bug: models followed the object schema and emitted
        // {"name":"LOW","ordinal":0}, which can never deserialize. This test
        // pins that the object form stays rejected while the flat string wins.
        val result = handler.analyze(
            rawResponse = """{"risk":{"name":"LOW","ordinal":0}}""",
            targetType = typeOf<Assessment>(),
        )
        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
    }
}

private val jackson = com.fasterxml.jackson.databind.ObjectMapper()

private enum class Risk { LOW, HIGH }

private data class Assessment(
    val risk: Risk,
)

private data class NullableAssessment(
    val risk: Risk?,
)

private data class SpendAnalysis(
    @property:AiDescription("Total spend in USD, always positive")
    @property:AiRange(min = 0.0, max = 1000000.0)
    val totalSpend: Double,
    @property:AiDescription("List of cost reduction recommendations, ordered by impact")
    @property:AiMinItems(1)
    val recommendations: List<String>,
    @property:AiDescription("Confidence score between 0.0 and 1.0")
    @property:AiRange(min = 0.0, max = 1.0)
    val confidence: Double,
    val nickname: String?,
)
