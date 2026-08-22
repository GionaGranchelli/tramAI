package dev.tramai.structured

import dev.tramai.core.structured.StructuredOutputResult
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test
import kotlin.reflect.typeOf

class JacksonStructuredOutputHandlerBoundaryTest {
    private val handler = JacksonStructuredOutputHandler()

    @Test
    fun `malformed json exposes fixed summary and preserves original failure`() {
        val result = handler.analyze("{\"value\":\"$SO_FIXTURE\",}", typeOf<BoundaryValue>()) as StructuredOutputResult.Failure

        assertThat(result.errorSummary).isEqualTo("Could not parse the JSON payload")
        assertThat(result.errorSummary).doesNotContain(SO_FIXTURE).doesNotContain("Unexpected")
        assertThat(result.failure).isNotNull()
    }

    @Test
    fun `deserialization failure does not expose raw detail in normal fields`() {
        // value:{} is a genuine Jackson deserialization failure (an object
        // cannot coerce to String). Unknown keys are no longer usable to force
        // this: they are rejected at SHAPE by the descriptor contract.
        val result = handler.analyze("{\"value\":{}}", typeOf<BoundaryValue>()) as StructuredOutputResult.Failure

        assertThat(result.errorSummary).isEqualTo("Could not deserialize the JSON payload")
        assertThat(result.failure).isNotNull()
    }

    @Test
    fun `successful parse is unchanged`() {
        val result = handler.analyze("{\"value\":\"ok\"}", typeOf<BoundaryValue>()) as StructuredOutputResult.Success

        assertThat(result.value).isEqualTo(BoundaryValue("ok"))
        assertThat(result.rawResponse).isEqualTo("{\"value\":\"ok\"}")
    }

    private data class BoundaryValue(val value: String)

    private companion object {
        const val SO_FIXTURE = "fixture-sentinel-so-2b4"
    }
}
