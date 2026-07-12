package dev.tramai.structured

import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaClaimResult
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaDecision
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaGenericCollectionResult
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaGetterOnlyResult
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaMapResult
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaPrimitiveResult
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaScoredResult
import dev.tramai.core.structured.StructuredOutputResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test
import kotlin.reflect.typeOf

/**
 * Tests for JavaBean structured-output schema generation and validation.
 *
 * These tests verify the new Jackson-introspection path introduced for
 * JavaBean DTOs. Kotlin-only behaviour is tested in [JacksonStructuredOutputHandlerTest].
 */
class JavaBeanStructuredOutputHandlerTest {

    private val handler = JacksonStructuredOutputHandler()

    // ---------------------------------------------------------------
    // Schema generation
    // ---------------------------------------------------------------

    @Test
    fun `schema contains JavaBean properties`() {
        val contract = handler.createContract(typeOf<JavaScoredResult>())

        assertThat(contract.schemaJson)
            .contains("\"status\"")
            .contains("\"confidence\"")
            .contains("\"reasons\"")
            .doesNotContain("\"properties\" : { }")
    }

    @Test
    fun `schema has deterministic alphabetical ordering`() {
        val contract = handler.createContract(typeOf<JavaScoredResult>())

        // Parse JSON and check property order
        assertThat(contract.schemaJson)
            .contains("\"confidence\"")
            .contains("\"reasons\"")
            .contains("\"status\"")

        // Verify alphabetical: confidence < reasons < status
        val confidenceIdx = contract.schemaJson.indexOf("\"confidence\"")
        val reasonsIdx = contract.schemaJson.indexOf("\"reasons\"")
        val statusIdx = contract.schemaJson.indexOf("\"status\"")
        assertThat(confidenceIdx).isLessThan(reasonsIdx)
        assertThat(reasonsIdx).isLessThan(statusIdx)
    }

    @Test
    fun `scalar type mappings`() {
        val contract = handler.createContract(typeOf<JavaPrimitiveResult>())

        assertThat(contract.schemaJson)
            .contains("\"type\" : \"integer\"")   // int count, long timestamp
            .contains("\"type\" : \"boolean\"")   // boolean active
            .contains("\"type\" : \"number\"")    // double score
    }

    @Test
    fun `generic collection produces array schema with items`() {
        val contract = handler.createContract(typeOf<JavaGenericCollectionResult>())

        val json = contract.schemaJson
        assertThat(json)
            .contains("\"type\" : \"array\"")
            .contains("\"type\" : \"string\"")
    }

    @Test
    fun `Java field annotations contribute to schema`() {
        val contract = handler.createContract(typeOf<JavaScoredResult>())

        assertThat(contract.schemaJson)
            .contains("\"description\" : \"Evaluation status\"")
            .contains("\"minimum\" : 0.0")
            .contains("\"maximum\" : 1.0")
            .contains("\"minItems\" : 1")
    }

    @Test
    fun `nested JavaBeans produce nested object schema`() {
        val contract = handler.createContract(typeOf<JavaClaimResult>())

        assertThat(contract.schemaJson)
            .contains("\"type\" : \"object\"")
            .contains("\"outcome\"")
    }

    @Test
    fun `all discovered JavaBean properties are required`() {
        val contract = handler.createContract(typeOf<JavaScoredResult>())

        assertThat(contract.schemaJson)
            .contains("\"required\"")
            .contains("\"status\"")
            .contains("\"confidence\"")
            .contains("\"reasons\"")
    }

    @Test
    fun `getter-only calculated properties are excluded`() {
        val contract = handler.createContract(typeOf<JavaGetterOnlyResult>())

        assertThat(contract.schemaJson)
            .contains("\"firstName\"")
            .contains("\"lastName\"")
            .doesNotContain("\"fullName\"")
    }

    @Test
    fun `unsupported map type throws appropriate error`() {
        assertThatThrownBy {
            handler.createContract(typeOf<JavaMapResult>())
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Unsupported structured output type")
    }

    // ---------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------

    @Test
    fun `valid JavaBean succeeds validation`() {
        val result = handler.analyze(
            rawResponse = """
                {
                  "status": "approved",
                  "confidence": 0.85,
                  "reasons": ["good score"]
                }
            """.trimIndent(),
            targetType = typeOf<JavaScoredResult>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Success::class.java)
        val success = result as StructuredOutputResult.Success
        assertThat(success.value).isNotNull
    }

    @Test
    fun `null required field fails validation`() {
        val result = handler.analyze(
            rawResponse = """
                {
                  "status": null,
                  "confidence": 0.85,
                  "reasons": ["good"]
                }
            """.trimIndent(),
            targetType = typeOf<JavaScoredResult>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).contains("status")
    }

    @Test
    fun `out of range numeric value fails validation`() {
        val result = handler.analyze(
            rawResponse = """
                {
                  "status": "approved",
                  "confidence": 1.5,
                  "reasons": ["good"]
                }
            """.trimIndent(),
            targetType = typeOf<JavaScoredResult>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).contains("confidence")
        assertThat(failure.feedbackMessage).contains("failed validation")
    }

    @Test
    fun `undersized collection fails validation`() {
        val result = handler.analyze(
            rawResponse = """
                {
                  "status": "approved",
                  "confidence": 0.5,
                  "reasons": []
                }
            """.trimIndent(),
            targetType = typeOf<JavaScoredResult>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).contains("reasons")
    }

    @Test
    fun `nested JavaBean violation includes parent path`() {
        val result = handler.analyze(
            rawResponse = """
                {
                  "decision": {
                    "outcome": null
                  }
                }
            """.trimIndent(),
            targetType = typeOf<JavaClaimResult>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        // The nested validation error should reference the property path
        assertThat(failure.errorSummary).contains("decision")
    }

    @Test
    fun `validation feedback is suitable for repair`() {
        val result = handler.analyze(
            rawResponse = """
                {
                  "status": "approved",
                  "confidence": 1.5,
                  "reasons": ["good"]
                }
            """.trimIndent(),
            targetType = typeOf<JavaScoredResult>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.feedbackMessage).contains("corrected JSON")
    }
}
