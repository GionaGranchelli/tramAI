package dev.tramai.structured.tck

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import dev.tramai.core.structured.StructuredOutputResult
import dev.tramai.structured.JacksonStructuredOutputHandler
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.fail

/**
 * Executes one [StructuredOutputContractCase] through the entire structured
 * output lifecycle and asserts each stage.
 *
 * For every case:
 *   1. createContract → schema must satisfy expectedSchema expectations
 *   2. analyze(validJson) → must succeed AND produce the expected value
 *   3. each invalid case must fail at the declared [FailureStage] with the
 *      expected feedback fragment
 *   4. repair feedback must be deterministic: the same input always yields
 *      the same summary + message
 *
 * Stage disambiguation: EXTRACTION / JSON_PARSE / DESERIALIZATION have
 * unique error summaries. SHAPE and VALUE_VALIDATION share the summary
 * "Structured output failed validation" by design (existing handler
 * behaviour), so those two stages are pinned by the feedback fragment, which
 * carries the stage-specific text ("Property 'x' is required" / "Expected an
 * array" for shape; "must be between", "must not be null" for value).
 */
internal class StructuredOutputContractTck(
    private val handler: JacksonStructuredOutputHandler = JacksonStructuredOutputHandler(),
    private val mapper: ObjectMapper = JsonMapper.builder().addModule(kotlinModule()).build(),
) {

    fun verify(case: StructuredOutputContractCase) {
        if (verifyCompileFailure(case)) return
        verifySchema(case)
        verifyValidRoundTrip(case)
        verifyInvalidCases(case)
        verifyDeterministicRepair(case)
    }

    /** Returns true when compilation must fail (remaining lifecycle skipped). */
    private fun verifyCompileFailure(case: StructuredOutputContractCase): Boolean {
        case.expectedCompileFailure?.let { fragment ->
            assertThatThrownBy { handler.createContract(case.targetType) }
                .withFailMessage("${case.id}: descriptor compilation must fail")
                .isInstanceOf(RuntimeException::class.java)
                .hasMessageContaining(fragment)
            return true
        }
        return false
    }

    private fun verifySchema(case: StructuredOutputContractCase) {
        val contract = handler.createContract(case.targetType)
        val schema = mapper.readTree(contract.schemaJson)

        case.expectedRootType?.let { expected ->
            assertThat(schema["type"].asText())
                .withFailMessage("${case.id}: root schema type mismatch")
                .isEqualTo(expected)
        }

        case.expectedSchema.forEach { (path, expectation) ->
            val node = resolve(schema, path)
            assertThat(node)
                .withFailMessage("${case.id}: schema path '$path' not found")
                .isNotNull()
            val value = node?.let { plainValue(it) }
            assertThat(expectation.predicate(value))
                .withFailMessage("${case.id}: schema assertion failed at '$path': ${expectation.description}")
                .isTrue()
        }
    }

    private fun verifyValidRoundTrip(case: StructuredOutputContractCase) {
        val validJson = case.validJson ?: return
        val result = handler.analyze(validJson, case.targetType)

        require(result is StructuredOutputResult.Success) {
            val summary = (result as? StructuredOutputResult.Failure)?.errorSummary ?: "unknown"
            "${case.id}: valid JSON must succeed, but failed: $summary"
        }
        assertThat(case.expectedValue(result.value))
            .withFailMessage("${case.id}: deserialized value did not satisfy expectation")
            .isTrue()
    }

    private fun verifyInvalidCases(case: StructuredOutputContractCase) {
        case.invalidCases.forEach { invalid ->
            val result = handler.analyze(invalid.json, case.targetType)
            assertThat(result)
                .withFailMessage("${case.id}: '${invalid.json}' must fail but succeeded")
                .isInstanceOf(StructuredOutputResult.Failure::class.java)

            val failure = result as StructuredOutputResult.Failure
            val actualStage = stageOf(failure.errorSummary)

            when (invalid.expectedStage) {
                FailureStage.EXTRACTION, FailureStage.JSON_PARSE, FailureStage.DESERIALIZATION -> {
                    assertThat(actualStage)
                        .withFailMessage("${case.id}: '${invalid.json}' failed at $actualStage, expected ${invalid.expectedStage}")
                        .isEqualTo(invalid.expectedStage)
                }
                FailureStage.SHAPE, FailureStage.VALUE_VALIDATION -> {
                    // Shared summary; pinned by feedback phrasing below.
                    assertThat(actualStage)
                        .withFailMessage("${case.id}: '${invalid.json}' summary '${failure.errorSummary}' is not a validation failure")
                        .isEqualTo(FailureStage.VALUE_VALIDATION)
                }
            }

            invalid.expectedFeedbackFragment?.let { fragment ->
                assertThat(failure.feedbackMessage)
                    .withFailMessage("${case.id}: feedback '${failure.feedbackMessage}' must contain '$fragment'")
                    .contains(fragment)
            }
        }
    }

    private fun verifyDeterministicRepair(case: StructuredOutputContractCase) {
        case.invalidCases.forEach { invalid ->
            val first = handler.analyze(invalid.json, case.targetType) as StructuredOutputResult.Failure
            val second = handler.analyze(invalid.json, case.targetType) as StructuredOutputResult.Failure
            assertThat(second.errorSummary)
                .withFailMessage("${case.id}: repair feedback must be deterministic for '${invalid.json}'")
                .isEqualTo(first.errorSummary)
            assertThat(second.feedbackMessage)
                .withFailMessage("${case.id}: repair message must be deterministic for '${invalid.json}'")
                .isEqualTo(first.feedbackMessage)
        }
    }

    private fun stageOf(summary: String): FailureStage = when {
        summary.contains("Could not extract JSON") -> FailureStage.EXTRACTION
        summary.contains("Could not parse the JSON payload") -> FailureStage.JSON_PARSE
        summary.contains("Could not deserialize") -> FailureStage.DESERIALIZATION
        summary.contains("failed validation") -> FailureStage.VALUE_VALIDATION
        else -> fail("Unknown failure summary: '$summary'")
    }

    private fun resolve(root: com.fasterxml.jackson.databind.JsonNode, path: String): com.fasterxml.jackson.databind.JsonNode? {
        var node: com.fasterxml.jackson.databind.JsonNode? = root
        path.split(".").forEach { segment ->
            node = node?.get(segment)
        }
        return node
    }

    private fun plainValue(node: com.fasterxml.jackson.databind.JsonNode): Any? = when {
        node.isTextual -> node.asText()
        node.isNumber -> node.numberValue()
        node.isBoolean -> node.asBoolean()
        node.isNull -> null
        node.isArray -> node.map { plainValue(it) }
        node.isObject -> node.fields().asSequence().associate { (k, v) -> k to plainValue(v) }
        else -> node.asText()
    }
}
