package dev.tramai.security.audit

import dev.tramai.core.approval.Sha256Digest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class AuditEngineApprovalLifecycleAuditEmitterTest {

    private val fixedClock = Clock.fixed(Instant.parse("2026-06-11T12:00:00Z"), ZoneId.of("UTC"))
    private val store = InMemoryAuditStore()
    private val auditEngine = AuditEngine(store, clock = fixedClock)
    private val emitter = AuditEngineApprovalLifecycleAuditEmitter(auditEngine)

    private val approvalId = "approval-001"
    private val workflowRunId = "run-001"
    private val toolName = "schedule-payment"
    private val toolCallId = "call-001"
    private val correlationId = "corr-001"
    private val argumentsDigest = Sha256Digest.of("sha256:abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890")
    private val expiresAt = fixedClock.instant().plusSeconds(3600)
    private val resumedBy = "human-operator"
    private val completedBy = "human-operator"
    private val claimedAt = fixedClock.instant()
    private val cancelledBy = "admin"

    // ── P1-2: Stream ID validation ────────────────────────────────────────────

    @Test
    fun `blank stream ID is rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            runTest {
                emitter.onToolExecutionSuspended(
                    approvalId = approvalId,
                    workflowRunId = "  ",
                    toolName = toolName,
                    toolCallId = toolCallId,
                    correlationId = correlationId,
                    argumentsDigest = argumentsDigest,
                    expiresAt = expiresAt,
                )
            }
        }
        assertTrue(ex.message!!.contains("Audit stream ID must not be blank"))
    }

    @Test
    fun `oversized stream ID is rejected`() {
        val longId = "a".repeat(257)
        val ex = assertThrows<IllegalArgumentException> {
            runTest {
                emitter.onToolExecutionSuspended(
                    approvalId = approvalId,
                    workflowRunId = longId,
                    toolName = toolName,
                    toolCallId = toolCallId,
                    correlationId = correlationId,
                    argumentsDigest = argumentsDigest,
                    expiresAt = expiresAt,
                )
            }
        }
        assertTrue(ex.message!!.contains("exceeds maximum length"))
    }

    @Test
    fun `same-prefix stream IDs produce independent audit streams`() = runTest {
        val store2 = InMemoryAuditStore()
        val engine2 = AuditEngine(store2, clock = fixedClock)
        val emitter2 = AuditEngineApprovalLifecycleAuditEmitter(engine2)

        emitter2.onToolExecutionSuspended(
            approvalId = approvalId,
            workflowRunId = "run-prefix-a",
            toolName = toolName,
            toolCallId = toolCallId,
            correlationId = correlationId,
            argumentsDigest = argumentsDigest,
            expiresAt = expiresAt,
        )
        emitter2.onToolExecutionSuspended(
            approvalId = approvalId,
            workflowRunId = "run-prefix-b",
            toolName = toolName,
            toolCallId = toolCallId + "-2",
            correlationId = correlationId,
            argumentsDigest = argumentsDigest,
            expiresAt = expiresAt,
        )

        val streamA = store2.readStream("run-prefix-a")
        val streamB = store2.readStream("run-prefix-b")
        assertEquals(1, streamA.size)
        assertEquals(1, streamB.size)
        assertEquals("APPROVAL_SUSPENDED", streamA[0].enforcementPoint)
        assertEquals("APPROVAL_SUSPENDED", streamB[0].enforcementPoint)
        // Different streams should have no shared hash chain
        assertTrue(streamA[0].previousEventHash == null)
        assertTrue(streamB[0].previousEventHash == null)
    }

    // ── P2-1: toolName in every lifecycle event ───────────────────────────────

    @Test
    fun `toolName is present in metadata for all 8 lifecycle events`() = runTest {
        // 1. onToolExecutionSuspended
        emitter.onToolExecutionSuspended(
            approvalId, workflowRunId, toolName, toolCallId,
            correlationId, argumentsDigest, expiresAt,
        )
        var events = store.readStream(workflowRunId)
        assertEquals(toolName, events.find { it.enforcementPoint == "APPROVAL_SUSPENDED" }!!.metadata["toolName"])

        // 2. onToolExecutionResumed
        emitter.onToolExecutionResumed(approvalId, workflowRunId, toolName, resumedBy)
        events = store.readStream(workflowRunId)
        assertEquals(toolName, events.find { it.enforcementPoint == "APPROVAL_RESUMED" }!!.metadata["toolName"])

        // 3. onToolExecutionCompleted
        emitter.onToolExecutionCompleted(approvalId, workflowRunId, toolName, completedBy)
        events = store.readStream(workflowRunId)
        assertEquals(toolName, events.find { it.enforcementPoint == "APPROVAL_COMPLETED" }!!.metadata["toolName"])

        // 4. onUncertainOutcome
        emitter.onUncertainOutcome(approvalId, workflowRunId, toolName, "timeout")
        events = store.readStream(workflowRunId)
        assertEquals(toolName, events.find { it.enforcementPoint == "APPROVAL_UNCERTAIN_OUTCOME" }!!.metadata["toolName"])

        // 5. onSuspensionCancelled
        emitter.onSuspensionCancelled(approvalId, workflowRunId, toolName, "manual_cancel")
        events = store.readStream(workflowRunId)
        assertEquals(toolName, events.find { it.enforcementPoint == "APPROVAL_SUSPENSION_CANCELLED" }!!.metadata["toolName"])

        // 6. onStaleClaimDetected
        emitter.onStaleClaimDetected(approvalId, workflowRunId, toolName, claimedAt)
        events = store.readStream(workflowRunId)
        assertEquals(toolName, events.find { it.enforcementPoint == "APPROVAL_STALE_CLAIM_DETECTED" }!!.metadata["toolName"])

        // 7. onClaimedContinuationForceCancellationRequested
        emitter.onClaimedContinuationForceCancellationRequested(
            approvalId, workflowRunId, toolName, cancelledBy, "admin_request"
        )
        events = store.readStream(workflowRunId)
        assertEquals(
            toolName,
            events.find { it.enforcementPoint == "APPROVAL_FORCE_CANCELLATION_REQUESTED" }!!.metadata["toolName"],
        )

        // 8. onClaimedContinuationForceCancelled
        emitter.onClaimedContinuationForceCancelled(
            approvalId, workflowRunId, toolName, cancelledBy, "admin_forced"
        )
        events = store.readStream(workflowRunId)
        assertEquals(
            toolName,
            events.find { it.enforcementPoint == "APPROVAL_FORCE_CANCELLED" }!!.metadata["toolName"],
        )
    }

    @Test
    fun `toolName is present in metadata for all 8 events via parameterized`() = runTest {
        val testStore = InMemoryAuditStore()
        val testEngine = AuditEngine(testStore, clock = fixedClock)
        val testEmitter = AuditEngineApprovalLifecycleAuditEmitter(testEngine)

        testEmitter.onToolExecutionSuspended("a", "r1", "my-tool", "tc1", "c1", argumentsDigest, expiresAt)
        testEmitter.onToolExecutionResumed("a", "r1", "my-tool", "op")
        testEmitter.onToolExecutionCompleted("a", "r1", "my-tool", "op")
        testEmitter.onUncertainOutcome("a", "r1", "my-tool", "timeout")
        testEmitter.onSuspensionCancelled("a", "r1", "my-tool", "manual_cancel")
        testEmitter.onStaleClaimDetected("a", "r1", "my-tool", claimedAt)
        testEmitter.onClaimedContinuationForceCancellationRequested("a", "r1", "my-tool", "admin", "reason")
        testEmitter.onClaimedContinuationForceCancelled("a", "r1", "my-tool", "admin", "reason")

        val events = testStore.readStream("r1")
        assertEquals(8, events.size)
        for (event in events) {
            assertEquals("my-tool", event.metadata["toolName"], "toolName must be present in ${event.enforcementPoint}")
        }
    }

    // ── P2-2: Reason code normalization ───────────────────────────────────────

    @Test
    fun `secrets in reason are absent from durable metadata for uncertain outcome`() = runTest {
        val secretReason = "api_key=sk-1234567890abcdef timeout occurred"
        emitter.onUncertainOutcome(approvalId, "run-reason-secret", toolName, secretReason)

        val events = store.readStream("run-reason-secret")
        assertEquals(1, events.size)
        val event = events[0]
        // The metadata should have reasonCode, not reason, with redacted value
        assertEquals("approval_reason_redacted", event.metadata["reasonCode"])
        // The original secret should not be present in any metadata value
        assertTrue(event.metadata.values.none { it.contains("sk-1234567890") })
        assertTrue(event.metadata.values.none { it.contains("api_key") })
    }

    @Test
    fun `secrets in reason are absent from durable metadata for suspension cancelled`() = runTest {
        val secretReason = "password=supersecret! manual cancellation"
        emitter.onSuspensionCancelled(approvalId, "run-reason-secret-2", toolName, secretReason)

        val events = store.readStream("run-reason-secret-2")
        assertEquals(1, events.size)
        val event = events[0]
        assertEquals("approval_reason_redacted", event.metadata["reasonCode"])
        assertTrue(event.metadata.values.none { it.contains("supersecret") })
        assertTrue(event.metadata.values.none { it.contains("password") })
    }

    @Test
    fun `valid reason code passes through normalized`() = runTest {
        emitter.onSuspensionCancelled(approvalId, "run-valid-reason", toolName, "manual_cancel")

        val events = store.readStream("run-valid-reason")
        assertEquals(1, events.size)
        assertEquals("manual_cancel", events[0].metadata["reasonCode"])
    }
}
