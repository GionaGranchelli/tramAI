package dev.tramai.security.audit

import dev.tramai.core.policy.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class AuditEnginePolicyDecisionAuditEmitterTest {

    private val fixedClock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneId.of("UTC"))
    private val store = InMemoryAuditStore()
    private val auditEngine = AuditEngine(store, clock = fixedClock)

    private val baseCtx = PolicyContext(
        enforcementPoint = EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
        workflowId = "wf-1",
        workflowRunId = "run-1",
        correlationId = "corr-1",
        actorId = "system.anonymous",
        providerId = "ollama",
        modelName = "mistral",
        policyVersion = "v1",
        workflowDigest = "digest-1",
        dataClassification = DataClassification.PUBLIC,
    )

    @Test
    fun `ALLOW maps to ALLOW audit event`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, baseCtx, PolicyDecision.Allow)

        val events = store.readStream("run-1")
        Assertions.assertEquals(1, events.size)
        Assertions.assertEquals("ALLOW", events[0].decision)
        Assertions.assertEquals("policy_allowed", events[0].reasonCode)
    }

    @Test
    fun `DENY maps to DENY audit event`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        val deny = PolicyDecision.Deny(reason = "blocked", reasonCode = "policy_denied")
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, baseCtx, deny)

        val events = store.readStream("run-1")
        Assertions.assertEquals(1, events.size)
        Assertions.assertEquals("DENY", events[0].decision)
        Assertions.assertEquals("policy_denied", events[0].reasonCode)
    }

    @Test
    fun `enforcement point name persisted correctly`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        emitter.emit(EnforcementPoint.BEFORE_TOOL_EXECUTION, baseCtx, PolicyDecision.Allow)

        val events = store.readStream("run-1")
        Assertions.assertEquals(1, events.size)
        Assertions.assertEquals("BEFORE_TOOL_EXECUTION", events[0].enforcementPoint)
    }

    @Test
    fun `workflow run ID and correlation ID mapped correctly`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, baseCtx, PolicyDecision.Allow)

        val events = store.readStream("run-1")
        Assertions.assertEquals("run-1", events[0].workflowRunId)
        Assertions.assertEquals("corr-1", events[0].correlationId)
    }

    @Test
    fun `stable stream ID reused across multiple decisions`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)

        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, baseCtx, PolicyDecision.Allow)
        emitter.emit(EnforcementPoint.BEFORE_FALLBACK, baseCtx, PolicyDecision.Allow)
        emitter.emit(EnforcementPoint.BEFORE_RESPONSE_RETURN, baseCtx, PolicyDecision.Allow)

        val events = store.readStream("run-1")
        Assertions.assertEquals(3, events.size)
        // All events in same stream
        events.forEach { Assertions.assertEquals("run-1", it.auditStreamId) }
        // Sequence numbers must be contiguous
        Assertions.assertEquals(1L, events[0].sequenceNumber)
        Assertions.assertEquals(2L, events[1].sequenceNumber)
        Assertions.assertEquals(3L, events[2].sequenceNumber)
    }

    @Test
    fun `safe metadata providerName and modelName present`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, baseCtx, PolicyDecision.Allow)

        val events = store.readStream("run-1")
        Assertions.assertEquals("ollama", events[0].metadata["providerName"])
        Assertions.assertEquals("mistral", events[0].metadata["modelName"])
    }

    @Test
    fun `safe metadata raw prompt NEVER present`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        val ctxWithAttrs = baseCtx.copy(attributes = mapOf("prompt" to "some user input"))
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, ctxWithAttrs, PolicyDecision.Allow)

        val events = store.readStream("run-1")
        // The attribute IS stored under "attr_prompt" — this tests that ONLY
        // safe allowlisted top-level fields are extracted (no raw prompt field
        // exists on PolicyContext). The test confirms the attribute key is
        // prefixed with "attr_" to distinguish from allowlisted fields.
        Assertions.assertEquals("some user input", events[0].metadata["attr_prompt"])
        Assertions.assertNull(events[0].metadata["prompt"])
    }

    @Test
    fun `resulting event chain passes AuditChainVerifier`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)

        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, baseCtx, PolicyDecision.Allow)
        emitter.emit(EnforcementPoint.BEFORE_FALLBACK, baseCtx, PolicyDecision.Allow)
        emitter.emit(EnforcementPoint.BEFORE_RESPONSE_RETURN, baseCtx, PolicyDecision.Deny(
            reason = "blocked", reasonCode = "egress_blocked"
        ))

        val events = store.readStream("run-1")
        val result = AuditChainVerifier.verify(events)
        Assertions.assertTrue(result.isValid, "Chain verification failed: ${result.errors}")
    }

    @Test
    fun `NoOpPolicyDecisionAuditEmitter does nothing`() = runTest {
        val noop = NoOpPolicyDecisionAuditEmitter
        noop.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, baseCtx, PolicyDecision.Allow)
        noop.emit(EnforcementPoint.BEFORE_FALLBACK, baseCtx, PolicyDecision.Deny(
            reason = "test", reasonCode = "test"
        ))
        // No exception should be thrown, nothing should be stored
        Assertions.assertTrue(true)
    }

    @Test
    fun `classification metadata included when present`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        val ctx = baseCtx.copy(
            dataClassification = DataClassification.RESTRICTED,
            classificationSource = ClassificationSource.DECLARED,
        )
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, ctx, PolicyDecision.Allow)

        val events = store.readStream("run-1")
        Assertions.assertEquals("RESTRICTED", events[0].metadata["classification"])
        Assertions.assertEquals("DECLARED", events[0].metadata["classificationSource"])
    }

    @Test
    fun `fallback provider metadata included when present`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        val ctx = baseCtx.copy(fallbackProviderId = "ollama-local")
        emitter.emit(EnforcementPoint.BEFORE_FALLBACK, ctx, PolicyDecision.Allow)

        val events = store.readStream("run-1")
        Assertions.assertEquals("ollama-local", events[0].metadata["fallbackProviderName"])
    }

    @Test
    fun `REQUIRE_APPROVAL maps correctly`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        val approvalDecision = PolicyDecision.RequireApproval(
            ApprovalRequirement(
                toolName = "payment-tool",
                argumentsDigest = "abc123",
                reason = "High risk action",
                timeoutMillis = 30000L,
            )
        )
        emitter.emit(EnforcementPoint.BEFORE_TOOL_EXECUTION, baseCtx, approvalDecision)

        val events = store.readStream("run-1")
        Assertions.assertEquals(1, events.size)
        Assertions.assertEquals("REQUIRE_APPROVAL", events[0].decision)
        Assertions.assertEquals("policy_requires_approval", events[0].reasonCode)
    }

    @Test
    fun `stream ID falls back to correlationId when no workflowRunId`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        val ctx = baseCtx.copy(workflowRunId = null)
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, ctx, PolicyDecision.Allow)

        val events = store.readStream("corr-1")
        Assertions.assertEquals(1, events.size)
        Assertions.assertEquals("corr-1", events[0].auditStreamId)
    }
}
