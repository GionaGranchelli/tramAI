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
        assertThat(failure.errorSummary).satisfiesAnyOf(
            { summary -> assertThat(summary).contains("recommendations") },
            { summary -> assertThat(summary).contains("confidence") },
        )
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
        assertThat(failure.errorSummary).contains("Could not find a JSON object or array")
    }

    @Test
    fun `rejects unsupported root types during schema generation`() {
        assertThatThrownBy {
            handler.createContract(typeOf<Map<String, String>>())
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Unsupported structured output type")
    }
}

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
