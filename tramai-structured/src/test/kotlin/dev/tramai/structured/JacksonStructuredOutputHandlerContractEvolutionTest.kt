package dev.tramai.structured

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import dev.tramai.core.annotations.AiMinItems
import dev.tramai.core.annotations.AiRange
import dev.tramai.core.structured.StructuredOutputResult
import org.assertj.core.api.Assertions.assertThat
import kotlin.reflect.typeOf
import kotlin.test.Test

/**
 * Contract-evolution and validator-behavior tests for [JacksonStructuredOutputHandler].
 *
 * These tests verify the structured-output lifecycle behaviors documented in
 * PR #168: return-type field evolution, validator schema contribution, validator
 * runtime validation, and repair-friendly failure feedback.
 *
 * PR #169 — see docs/structured-output-contract-lifecycle.md for the full lifecycle.
 */
class JacksonStructuredOutputHandlerContractEvolutionTest {

    private val handler = JacksonStructuredOutputHandler()
    private val mapper: ObjectMapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .build()

    // ── Test DTOs ────────────────────────────────────────────────────────

    private data class RecommendationV1(
        val title: String,
    )

    private data class RecommendationV2(
        val title: String,
        val confidence: Double,
    )

    private data class ScoredAnswer(
        val answer: String,
        @property:AiRange(min = 0.0, max = 1.0)
        val confidence: Double,
    )

    private data class Recommendations(
        @property:AiMinItems(1)
        val items: List<String>,
    )

    // ── Test 1: Contract evolution — new field ────────────────────────────

    @Test
    fun `generated contract includes all public properties of return type`() {
        val v1Contract = handler.createContract(typeOf<RecommendationV1>())
        val v1Schema = mapper.readTree(v1Contract.schemaJson)

        assertThat(v1Schema["type"].asText()).isEqualTo("object")
        assertThat(v1Schema["properties"]?.get("title")).isNotNull
        assertThat(v1Schema["properties"]?.get("confidence")).isNull()
    }

    @Test
    fun `adding a field to return type changes the generated contract`() {
        val v2Contract = handler.createContract(typeOf<RecommendationV2>())
        val v2Schema = mapper.readTree(v2Contract.schemaJson)

        assertThat(v2Schema["type"].asText()).isEqualTo("object")
        assertThat(v2Schema["properties"]?.get("title")).isNotNull
        assertThat(v2Schema["properties"]?.get("confidence")).isNotNull
    }

    // ── Test 2: Contract generation returns fresh instances per call ──────

    @Test
    fun `createContract returns fresh equivalent contracts for same return type`() {
        val first = handler.createContract(typeOf<RecommendationV2>())
        val second = handler.createContract(typeOf<RecommendationV2>())

        assertThat(second).isNotSameAs(first)
        assertThat(second.schemaJson).isEqualTo(first.schemaJson)
    }

    // ── Test 3: @AIRange appears in generated schema ─────────────────────

    @Test
    fun `schema includes AIRange minimum and maximum for annotated property`() {
        val contract = handler.createContract(typeOf<ScoredAnswer>())
        val schema = mapper.readTree(contract.schemaJson)

        val confidence = schema["properties"]?.get("confidence")!!
        assertThat(confidence["type"].asText()).isEqualTo("number")
        assertThat(confidence["minimum"].asDouble()).isEqualTo(0.0)
        assertThat(confidence["maximum"].asDouble()).isEqualTo(1.0)
    }

    // ── Test 4: @AIRange validates parsed output ─────────────────────────

    @Test
    fun `AIRange valid values produce success`() {
        val json = """{"answer":"ok","confidence":0.8}"""
        val result = handler.analyze(json, typeOf<ScoredAnswer>())

        assertThat(result).isInstanceOf(StructuredOutputResult.Success::class.java)
        val success = result as StructuredOutputResult.Success
        assertThat((success.value as ScoredAnswer).confidence).isEqualTo(0.8)
    }

    @Test
    fun `AIRange out-of-range values produce validation failure`() {
        val json = """{"answer":"ok","confidence":1.5}"""
        val result = handler.analyze(json, typeOf<ScoredAnswer>())

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).contains("confidence")
        assertThat(failure.errorSummary).contains("between 0.0 and 1.0")
        assertThat(failure.feedbackMessage).contains("failed validation")
    }

    // ── Test 5: @AIMinItems appears in generated schema ──────────────────

    @Test
    fun `schema includes AIMinItems for annotated collection property`() {
        val contract = handler.createContract(typeOf<Recommendations>())
        val schema = mapper.readTree(contract.schemaJson)

        val items = schema["properties"]?.get("items")!!
        assertThat(items["type"].asText()).isEqualTo("array")
        assertThat(items["minItems"].asInt()).isEqualTo(1)
    }

    // ── Test 6: @AIMinItems validates parsed output ──────────────────────

    @Test
    fun `AIMinItems valid collection size produces success`() {
        val json = """{"items":["one"]}"""
        val result = handler.analyze(json, typeOf<Recommendations>())

        assertThat(result).isInstanceOf(StructuredOutputResult.Success::class.java)
    }

    @Test
    fun `AIMinItems insufficient collection size produces validation failure`() {
        val json = """{"items":[]}"""
        val result = handler.analyze(json, typeOf<Recommendations>())

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).contains("items")
        assertThat(failure.errorSummary).contains("at least 1")
        assertThat(failure.feedbackMessage).contains("failed validation")
    }

    // ── Test 7: Parse failure produces repair-friendly feedback ──────────

    @Test
    fun `parse failure returns repair-friendly feedback`() {
        val result = handler.analyze("not json", typeOf<RecommendationV1>())

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).contains("Could not find a JSON object or array")
        assertThat(failure.feedbackMessage).contains("Return only valid JSON")
    }
}
