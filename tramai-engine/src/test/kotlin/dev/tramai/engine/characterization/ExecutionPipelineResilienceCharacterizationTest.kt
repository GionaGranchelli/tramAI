package dev.tramai.engine.characterization

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.engine.TramaiEngine
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Execution-pipeline characterization scenarios 8-14: retry, fallback, circuit
 * breaker, structured-output repair, and tool governance ordering.
 *
 * These tests freeze the CURRENT observable semantics of master — they do not
 * "improve" ordering. Approved traces reflect real engine behavior; security
 * ordering is asserted explicitly.
 */
class ExecutionPipelineResilienceCharacterizationTest {

    private fun index(fixture: ExecutionTraceFixture, type: String): Int =
        fixture.trace.snapshot().indexOfFirst { it.type == type }

    private fun types(fixture: ExecutionTraceFixture): List<String> =
        fixture.trace.snapshot().map { it.type }

    @Test
    fun `scenario 8 - retry then success records attempt lifecycle and retry ordering`() {
        val fixture = ExecutionTraceFixture().apply {
            providerFailures = 1
            recordEngineEvents = true
        }
        val result = runBlocking { fixture.engine().create(TraceService::class).retryOnce("input") }
        assertThat(result).isEqualTo("answer")
        assertThat(fixture.trace.snapshot()).containsExactlyElementsOf(fixture.approved("retry-success.trace"))
        // attempt lifecycle: first attempt fails, second succeeds
        assertThat(fixture.trace.snapshot().filter { it.type == "provider.start" }).hasSize(2)
        assertThat(fixture.trace.snapshot().filter { it.type == "provider.start" }[0].attributes["attempt"]).isEqualTo("1")
        assertThat(fixture.trace.snapshot().filter { it.type == "provider.start" }[1].attributes["attempt"]).isEqualTo("2")
        assertThat(fixture.trace.snapshot().filter { it.type == "operation.complete" }).hasSize(2)
    }

    @Test
    fun `scenario 9 - retry exhausted then fallback records primary retry fallback sequence`() {
        val fixture = ExecutionTraceFixture().apply {
            providerFailures = 2 // primary fails both attempts (providerRetries=1)
            fallbackEnabled = true
            recordEngineEvents = true
        }
        val result = runBlocking { fixture.engine().create(TraceService::class).retryThenFallback("input") }
        assertThat(result).isEqualTo("answer")
        assertThat(fixture.trace.snapshot()).containsExactlyElementsOf(fixture.approved("retry-fallback.trace"))
        // primary called twice (initial + retry), fallback called once
        val starts = fixture.trace.snapshot().filter { it.type == "provider.start" }
        assertThat(starts).hasSize(3)
        assertThat(starts.map { it.attributes["provider"] }).containsExactly("primary", "primary", "fallback")
        // policy BEFORE_FALLBACK evaluated between the primary retry exhaustion and the fallback attempt
        val fallbackPolicyIndex = fixture.trace.snapshot().indexOfFirst { it.type == "policy.evaluate" && it.attributes["point"] == "BEFORE_FALLBACK" }
        val secondCompleteIndex = fixture.trace.snapshot().indexOfLast { it.type == "operation.complete" && it.attributes["outcome"] == "failure" }
        val fallbackStartIndex = starts[2].let { fixture.trace.snapshot().indexOf(it) }
        assertThat(fallbackPolicyIndex).isBetween(secondCompleteIndex, fallbackStartIndex)
    }

    @Test
    fun `scenario 10 - open circuit breaker bypasses provider on subsequent invocation`() {
        val fixture = ExecutionTraceFixture().apply {
            providerFailures = 1
            circuitBreakerEnabled = true
            recordEngineEvents = true
        }
        val engine = fixture.engine()
        val service = engine.create(TraceService::class)
        // First invocation: failure opens the breaker
        assertThatThrownBy { runBlocking { service.answer("input") } }
            .isInstanceOf(ProviderException::class.java)
        assertThat(fixture.trace.snapshot()).containsExactlyElementsOf(fixture.approved("circuit-breaker-open.trace"))
        assertThat(types(fixture)).contains("engine.event")

        // Second invocation on the SAME engine: breaker open, provider bypassed
        assertThatThrownBy { runBlocking { service.answer("input") } }
            .isInstanceOf(Exception::class.java)
        // The only provider.start belongs to the FIRST invocation; the circuit-opened
        // event terminates provider access — nothing after it reaches the provider
        val allTypes = types(fixture)
        assertThat(allTypes.count { it == "provider.start" }).isEqualTo(1)
        val openedIndex = fixture.trace.snapshot().indexOfFirst { it.type == "engine.event" && it.attributes["name"] == "tramai.circuit.opened" }
        assertThat(openedIndex).isGreaterThan(-1)
        assertThat(allTypes.drop(openedIndex + 1)).doesNotContain("provider.start", "provider.failure")
    }

    @Test
    fun `scenario 11 - structured output repair retries after parse failure`() {
        val fixture = ExecutionTraceFixture().apply {
            structuredFailures = 1
        }
        val result = runBlocking { fixture.engine().create(TraceService::class).structured("input") }
        assertThat(result).isEqualTo(TraceStructuredResult("parsed"))
        assertThat(fixture.trace.snapshot()).containsExactlyElementsOf(fixture.approved("structured-repair.trace"))
        // provider called twice: initial parse failure then repair attempt
        assertThat(fixture.trace.snapshot().filter { it.type == "provider.start" }).hasSize(2)
        assertThat(types(fixture)).contains("structured.parse.failure")
        // repair attempt succeeds
        assertThat(types(fixture).last()).isEqualTo("operation.complete")
    }

    @Test
    fun `scenario 12 - successful tool call records governance execution and reinjection ordering`() {
        val fixture = ExecutionTraceFixture().apply {
            recordEngineEvents = true
        }
        val result = runBlocking { fixture.engine().create(TraceService::class).toolCall("input") }
        assertThat(result).isEqualTo("answer")
        assertThat(fixture.trace.snapshot()).containsExactlyElementsOf(fixture.approved("tool-execution.trace"))
        // governance before execution
        assertThat(index(fixture, "tool.execute.start")).isGreaterThan(index(fixture, "policy.evaluate"))
        // tool execution happens; reinjection means a second provider start
        assertThat(fixture.trace.snapshot().filter { it.type == "provider.start" }).hasSize(2)
        assertThat(types(fixture)).contains("tool.execute.start", "tool.execute.success")
        // explicit security ordering: tool execution policy allows BEFORE tool executes
        assertThat(fixture.trace.snapshot().indexOfFirst { it.type == "policy.allow" && it.attributes["point"] == "BEFORE_TOOL_EXECUTION" })
            .isLessThan(index(fixture, "tool.execute.start"))
    }

    @Test
    fun `scenario 13 - tool exposure denial keeps denied tool out of provider request`() {
        val fixture = ExecutionTraceFixture().apply {
            denyToolExposure = true
        }
        assertThatThrownBy { runBlocking { fixture.engine().create(TraceService::class).toolCall("input") } }
            .isInstanceOf(PolicyViolationException::class.java)
        assertThat(fixture.trace.snapshot()).containsExactlyElementsOf(fixture.approved("tool-exposure-denied.trace"))
        // provider never invoked — no provider.start, no tool execution
        assertThat(types(fixture)).doesNotContain("provider.start")
        assertThat(types(fixture)).doesNotContain("tool.execute.start")
        // scoped deny at BEFORE_TOOL_EXPOSURE
        assertThat(index(fixture, "policy.deny")).isGreaterThan(-1)
    }

    @Test
    fun `scenario 14 - tool execution denial prevents execution and reinjection`() {
        val fixture = ExecutionTraceFixture().apply {
            denyToolExecution = true
        }
        assertThatThrownBy { runBlocking { fixture.engine().create(TraceService::class).toolCall("input") } }
            .isInstanceOf(PolicyViolationException::class.java)
        assertThat(fixture.trace.snapshot()).containsExactlyElementsOf(fixture.approved("tool-execution-denied.trace"))
        // model called the tool but execution never occurs
        assertThat(types(fixture)).contains("provider.start")
        assertThat(types(fixture)).doesNotContain("tool.execute.start")
        assertThat(types(fixture)).doesNotContain("tool.execute.success")
        // no reinjection — single provider start
        assertThat(fixture.trace.snapshot().filter { it.type == "provider.start" }).hasSize(1)
    }
}
