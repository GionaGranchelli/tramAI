package dev.tramai.engine.characterization

import dev.tramai.core.exception.ApprovalSuspendedException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.model.StreamChunk
import dev.tramai.engine.ResumeApprovalCommand
import dev.tramai.engine.TramaiEngine
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Execution-pipeline characterization scenarios 15-20: approval suspension and
 * resume, tool-result DLP, streaming, and cancellation.
 *
 * These tests freeze the CURRENT observable semantics of master — they do not
 * "improve" ordering. Cancellation coverage is trace-level ONLY (the deep
 * contract suites own the low-level guarantees); approval scenarios use the
 * engine's own suspend/resume flow with recording collaborators.
 *
 * Frozen quirks (intentional — do NOT "fix" during Phase 3 decomposition):
 * - `streaming.trace`: BEFORE_RESPONSE_RETURN is evaluated before
 *   BEFORE_PROVIDER_INVOCATION on the streaming path (engine L975-977).
 * - `approval-suspension.trace` / `tool-execution-denied.trace` end with
 *   `operation.complete outcome=success` although the invocation throws —
 *   the observation is finalized with parseSuccess=null on suspension/denial.
 */
class ExecutionPipelineApprovalStreamingCharacterizationTest {

    private fun types(fixture: ExecutionTraceFixture): List<String> =
        fixture.trace.snapshot().map { it.type }

    private fun index(fixture: ExecutionTraceFixture, type: String): Int =
        fixture.trace.snapshot().indexOfFirst { it.type == type }

    @Test
    fun `scenario 15 - approval suspension persists continuation and audit before suspending`() {
        val fixture = ExecutionTraceFixture().apply { approvalRequired = true }
        val engine = fixture.engine()
        assertThatThrownBy { runBlocking { engine.create(TraceService::class).toolApproval("input") } }
            .isInstanceOf(ApprovalSuspendedException::class.java)
        assertThat(fixture.trace.snapshot()).containsExactlyElementsOf(fixture.approved("approval-suspension.trace"))
        // tool must NOT execute while suspended
        assertThat(types(fixture)).doesNotContain("tool.execute.start")
        // ordering: approval required → persisted → suspended (audit follows suspension)
        assertThat(index(fixture, "approval.required")).isLessThan(index(fixture, "approval.continuation.persist"))
        assertThat(index(fixture, "approval.continuation.persist")).isLessThan(index(fixture, "invocation.suspended"))
    }

    @Test
    fun `scenario 16 - approval resume validates claims and replays execution`() {
        val fixture = ExecutionTraceFixture().apply { approvalRequired = true }
        val engine = fixture.engine()
        val suspended = runBlocking {
            try {
                engine.create(TraceService::class).toolApproval("input")
                error("expected suspension")
            } catch (e: ApprovalSuspendedException) {
                e
            }
        }
        val result = runBlocking {
            engine.resumeApproval(
                ResumeApprovalCommand(
                    approvalId = suspended.approvalId,
                    approvalExpectedVersion = 0L,
                    continuationExpectedVersion = 0L,
                    presentedToken = suspended.challenge.token,
                    resumedBy = "reviewer",
                ),
            )
        }
        assertThat(result).isEqualTo("answer")
        assertThat(fixture.trace.snapshot()).containsExactlyElementsOf(fixture.approved("approval-resume.trace"))
        // tool eventually executes after resume
        assertThat(types(fixture)).contains("tool.execute.start", "tool.execute.success")
        // ordering: validate → authorize → claim → execute
        assertThat(index(fixture, "approval.resume.validate")).isLessThan(index(fixture, "approval.resume.authorize"))
        assertThat(index(fixture, "approval.resume.authorize")).isLessThan(index(fixture, "approval.continuation.claim"))
        assertThat(index(fixture, "approval.continuation.claim")).isLessThan(index(fixture, "tool.execute.start"))
        // cleanup after completion
        assertThat(types(fixture)).contains("invocation.cleanup")
    }

    @Test
    fun `scenario 17 - tool result dlp inspection precedes reinjection`() {
        val fixture = ExecutionTraceFixture().apply { dlpEnabled = true }
        val result = runBlocking { fixture.engine().create(TraceService::class).toolCall("input") }
        assertThat(result).isEqualTo("answer")
        assertThat(fixture.trace.snapshot()).containsExactlyElementsOf(fixture.approved("tool-result-dlp.trace"))
        // DLP inspects the tool result between tool execution and the reinjection round
        assertThat(index(fixture, "tool.execute.success")).isLessThan(index(fixture, "dlp.tool-result.inspect"))
        val reinjectionStart = fixture.trace.snapshot().indexOfFirst { it.type == "provider.start" && it.attributes["attempt"] == "2" }
        assertThat(reinjectionStart).isGreaterThan(-1)
        assertThat(index(fixture, "dlp.tool-result.inspect")).isLessThan(reinjectionStart)
    }

    @Test
    fun `scenario 18 - streaming success records startup chunks and terminal`() {
        val fixture = ExecutionTraceFixture().apply { streaming = true }
        val chunks = runBlocking {
            fixture.engine().create<dev.tramai.engine.characterization.StreamingTraceService>(dev.tramai.engine.characterization.StreamingTraceService::class)
                .stream("input").toList()
        }
        assertThat(chunks.filterIsInstance<StreamChunk.Complete>().single().fullText).isEqualTo("firstsecond")
        assertThat(fixture.trace.snapshot()).containsExactlyElementsOf(fixture.approved("streaming.trace"))
        assertThat(types(fixture)).contains("streaming.start", "streaming.terminal")
        assertThat(index(fixture, "streaming.start")).isLessThan(index(fixture, "streaming.terminal"))
    }

    @Test
    fun `scenario 19 - streaming terminal failure does not retry or fallback`() {
        val fixture = ExecutionTraceFixture().apply { streaming = true; streamingFails = true }
        val chunks = runBlocking {
            fixture.engine().create<dev.tramai.engine.characterization.StreamingTraceService>(dev.tramai.engine.characterization.StreamingTraceService::class)
                .stream("input").toList()
        }
        // terminal error chunk reaches the consumer; no retry/fallback is attempted
        assertThat(chunks.filterIsInstance<StreamChunk.Error>()).hasSize(1)
        assertThat(chunks.filterIsInstance<StreamChunk.Complete>()).isEmpty()
        assertThat(fixture.trace.snapshot()).containsExactlyElementsOf(fixture.approved("streaming-failure.trace"))
        // streaming terminal has outcome=failure, and no retry/fallback engine events
        assertThat(types(fixture)).contains("streaming.terminal")
        assertThat(fixture.trace.snapshot().filter { it.type == "provider.start" }).hasSize(1)
    }

    @Test
    fun `scenario 20 - cancellation during provider execution terminates pipeline`() {
        val fixture = ExecutionTraceFixture().apply {
            blockingProvider = true
            recordEngineEvents = true
        }
        val engine = fixture.engine()
        val service = engine.create(TraceService::class)
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            service.answer("input")
        }
        runBlocking {
            fixture.providerEntered.await()
            job.cancelAndJoin()
        }
        assertThat(fixture.trace.snapshot()).containsExactlyElementsOf(fixture.approved("cancellation.trace"))
        // provider started execution, then the pipeline terminated cancelled
        assertThat(types(fixture)).contains("provider.execute")
        assertThat(types(fixture)).contains("operation.cancelled")
        // cancellation bypasses retry and fallback — no retry.scheduled / circuit events
        val engineEvents = fixture.trace.snapshot().filter { it.type == "engine.event" }.map { it.attributes["name"] }
        assertThat(engineEvents).doesNotContain("tramai.retry.scheduled", "tramai.circuit.opened")
    }
}
