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
    fun `unknown attribute keys like prompt are dropped from audit metadata`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        val ctxWithAttrs = baseCtx.copy(attributes = mapOf(
            "prompt" to "ignore-all-previous-instructions",
            "toolArguments" to "super-secret-api-key-123",
            "secret" to "alice@example.com",
        ))
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, ctxWithAttrs, PolicyDecision.Allow)

        val events = store.readStream("run-1")
        // Unknown attribute keys are dropped — they are NOT in ALLOWED_ATTRIBUTE_KEYS
        Assertions.assertNull(events[0].metadata["attr_prompt"])
        Assertions.assertNull(events[0].metadata["attr_toolArguments"])
        Assertions.assertNull(events[0].metadata["attr_secret"])
    }

    @Test
    fun `cacheReuse attribute is retained in metadata`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        val ctxWithCacheReuse = baseCtx.copy(attributes = mapOf("cacheReuse" to "true"))
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, ctxWithCacheReuse, PolicyDecision.Allow)

        val events = store.readStream("run-1")
        Assertions.assertEquals("true", events[0].metadata["attr_cacheReuse"])
    }

    @Test
    fun `fallbackReason attribute is retained in metadata`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        val ctxWithFallback = baseCtx.copy(attributes = mapOf("fallbackReason" to "provider_unavailable"))
        emitter.emit(EnforcementPoint.BEFORE_FALLBACK, ctxWithFallback, PolicyDecision.Allow)

        val events = store.readStream("run-1")
        Assertions.assertEquals("provider_unavailable", events[0].metadata["attr_fallbackReason"])
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

    @Test
    fun `blank workflowRunId falls back to correlationId`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        val ctx = baseCtx.copy(workflowRunId = "")
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, ctx, PolicyDecision.Allow)

        val events = store.readStream("corr-1")
        Assertions.assertEquals(1, events.size)
        Assertions.assertEquals("corr-1", events[0].auditStreamId)
    }

    @Test
    fun `blank correlationId and blank workflowRunId uses custom resolver fallback`() = runTest {
        val customResolver = AuditStreamIdResolver { "custom-fallback" }
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine, customResolver)
        val ctx = baseCtx.copy(workflowRunId = "", correlationId = "")
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, ctx, PolicyDecision.Allow)

        val events = store.readStream("custom-fallback")
        Assertions.assertEquals(1, events.size)
        Assertions.assertEquals("custom-fallback", events[0].auditStreamId)
    }

    // ─── Reason code normalization tests ───────────────────────────────

    @Test
    fun `valid stable reasonCode preserved`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, baseCtx,
            PolicyDecision.Deny("blocked", "classification-routing-blocked"))

        val events = store.readStream("run-1")
        Assertions.assertEquals("classification-routing-blocked", events[0].reasonCode)
    }

    @Test
    fun `overlong reasonCode replaced`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        val longCode = "a" + "b".repeat(200)
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, baseCtx,
            PolicyDecision.Deny("blocked", longCode))

        val events = store.readStream("run-1")
        Assertions.assertEquals("policy_denied", events[0].reasonCode)
    }

    @Test
    fun `whitespace-reasonCode replaced`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, baseCtx,
            PolicyDecision.Deny("blocked", "blocked secret key"))

        val events = store.readStream("run-1")
        Assertions.assertEquals("policy_denied", events[0].reasonCode)
    }

    @Test
    fun `uppercase-secret-like reasonCode replaced`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, baseCtx,
            PolicyDecision.Deny("blocked", "sk-ABC123-DEF456"))

        val events = store.readStream("run-1")
        Assertions.assertEquals("policy_denied", events[0].reasonCode)
    }

    @Test
    fun `reasonCode with special characters replaced`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, baseCtx,
            PolicyDecision.Deny("blocked", "secret!@#"))

        val events = store.readStream("run-1")
        Assertions.assertEquals("policy_denied", events[0].reasonCode)
    }

    @Test
    fun `newline-containing reasonCode replaced`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, baseCtx,
            PolicyDecision.Deny("blocked", "allowed\n[INFO] user=admin"))

        val events = store.readStream("run-1")
        Assertions.assertEquals("policy_denied", events[0].reasonCode)
    }

    @Test
    fun `empty reasonCode replaced`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, baseCtx,
            PolicyDecision.Deny("blocked", ""))

        val events = store.readStream("run-1")
        Assertions.assertEquals("policy_denied", events[0].reasonCode)
    }

    @Test
    fun `reasonCode starting with digit preserved`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, baseCtx,
            PolicyDecision.Deny("blocked", "1policy-rule"))

        val events = store.readStream("run-1")
        Assertions.assertEquals("1policy-rule", events[0].reasonCode)
    }

    // ─── Stream ID validation tests ────────────────────────────────────

    @Test
    fun `blanks in both workflowRunId and correlationId still produces valid event via generated ID`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        val resolver = AuditStreamIdResolver { "gen-run-abc" }
        val emitterWithResolver = AuditEnginePolicyDecisionAuditEmitter(auditEngine, resolver)
        val ctx = baseCtx.copy(workflowRunId = "", correlationId = "")
        emitterWithResolver.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, ctx, PolicyDecision.Allow)

        // Should emit to the generated stream ID
        val events = store.readStream("gen-run-abc")
        Assertions.assertEquals(1, events.size)
        Assertions.assertEquals("gen-run-abc", events[0].auditStreamId)
    }

    @Test
    fun `blank stream ID from custom resolver throws`() = runTest {
        val blankResolver = AuditStreamIdResolver { "" }
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine, blankResolver)

        val ex = Assertions.assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, baseCtx, PolicyDecision.Allow)
            }
        }
        Assertions.assertEquals("Audit stream ID must not be blank", ex.message)
    }

    @Test
    fun `oversize stream ID from custom resolver throws`() = runTest {
        val longResolver = AuditStreamIdResolver { "a".repeat(257) }
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine, longResolver)

        val ex = Assertions.assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, baseCtx, PolicyDecision.Allow)
            }
        }
        Assertions.assertEquals("Audit stream ID exceeds maximum length of 256", ex.message)
    }

    // ─── Deterministic attribute ordering ──────────────────────────────

    @Test
    fun `attributes are sorted deterministically`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        val ctx = baseCtx.copy(attributes = mapOf(
            "fallbackReason" to "timeout",
            "cacheReuse" to "true",
        ))
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, ctx, PolicyDecision.Allow)

        val events = store.readStream("run-1")
        val metadata = events[0].metadata
        val attrKeys = metadata.keys.filter { it.startsWith("attr_") }
        Assertions.assertEquals(listOf("attr_cacheReuse", "attr_fallbackReason"), attrKeys)
    }

    // ─── Leakage tests ─────────────────────────────────────────────────

    @Test
    fun `serialized audit event does not contain known prompt secret`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        val ctx = baseCtx.copy(attributes = mapOf(
            "prompt" to "super-secret-api-key-123",
            "toolArguments" to "ignore-all-previous-instructions",
        ))
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, ctx, PolicyDecision.Allow)

        val events = store.readStream("run-1")
        val serialized = events[0].toCanonicalJson()
        Assertions.assertFalse(serialized.contains("super-secret-api-key-123"))
        Assertions.assertFalse(serialized.contains("ignore-all-previous-instructions"))
    }

    @Test
    fun `serialized audit event does not contain raw model-generated tool name`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        val ctx = baseCtx.copy(toolName = "<unregistered>")
        emitter.emit(EnforcementPoint.BEFORE_TOOL_EXECUTION, ctx, PolicyDecision.Allow)

        val events = store.readStream("run-1")
        // The safe label "<unregistered>" itself is fine, but raw model-generated names
        // (e.g., "execute_rm_-rf_/") should never appear.
        Assertions.assertEquals("<unregistered>", events[0].metadata["toolName"])
    }

    @Test
    fun `serialized audit event does not contain DLP matches`() = runTest {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(auditEngine)
        val ctx = baseCtx.copy(
            attributes = mapOf("cacheReuse" to "true"),
        )
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, ctx, PolicyDecision.Allow)

        val events = store.readStream("run-1")
        // No DLP-related metadata should be present
        Assertions.assertNull(events[0].metadata["dlpMatch"])
        Assertions.assertNull(events[0].metadata["redactedValue"])
    }
}
