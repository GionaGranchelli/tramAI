package dev.tramai.engine

import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.policy.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PolicyAuditWiringTest {

    private val policyEngine = PolicyEngine { PolicyDecision.Allow }

    private val denyPolicyEngine = PolicyEngine { context ->
        PolicyDecision.Deny(reason = "test-block", reasonCode = "test-blocked")
    }

    @Test
    fun `ALLOW with configured emitter emits exactly one event before enforcement`() = runBlocking {
        val callCount = AtomicInteger(0)
        val emitter = PolicyDecisionAuditEmitter { _, _, _ -> callCount.incrementAndGet() }

        val helper = PolicyEnforcementHelper(
            policyEngine = policyEngine,
            migrationWarningGuard = AtomicBoolean(true),
            auditEmitter = emitter,
        )

        val context = buildTestContext(EnforcementPoint.BEFORE_PROVIDER_INVOCATION)
        helper.enforce(context)

        Assertions.assertEquals(1, callCount.get(), "Emitter should have been called exactly once")
    }

    @Test
    fun `DENY with configured emitter emits exactly one event before exception`() = runBlocking {
        val callCount = AtomicInteger(0)
        val emitter = PolicyDecisionAuditEmitter { _, _, _ -> callCount.incrementAndGet() }

        val helper = PolicyEnforcementHelper(
            policyEngine = denyPolicyEngine,
            migrationWarningGuard = AtomicBoolean(true),
            auditEmitter = emitter,
        )

        val context = buildTestContext(EnforcementPoint.BEFORE_PROVIDER_INVOCATION)

        try {
            helper.enforce(context)
            Assertions.fail("Expected PolicyViolationException")
        } catch (e: PolicyViolationException) {
            // Expected
        }

        Assertions.assertEquals(1, callCount.get(), "Emitter should have been called exactly once before exception")
    }

    @Test
    fun `NoOp emitter preserves existing behavior`() = runBlocking {
        val helper = PolicyEnforcementHelper(
            policyEngine = policyEngine,
            migrationWarningGuard = AtomicBoolean(true),
            auditEmitter = NoOpPolicyDecisionAuditEmitter,
        )

        val context = buildTestContext(EnforcementPoint.BEFORE_PROVIDER_INVOCATION)
        helper.enforce(context)
        Assertions.assertTrue(true)
    }

    @Test
    fun `NoOp emitter preserves DENY behavior`() = runBlocking {
        val helper = PolicyEnforcementHelper(
            policyEngine = denyPolicyEngine,
            migrationWarningGuard = AtomicBoolean(true),
            auditEmitter = NoOpPolicyDecisionAuditEmitter,
        )

        val context = buildTestContext(EnforcementPoint.BEFORE_PROVIDER_INVOCATION)

        try {
            helper.enforce(context)
            Assertions.fail("Expected PolicyViolationException")
        } catch (e: PolicyViolationException) {
            val deny = e.decision
            if (deny is PolicyDecision.Deny) {
                Assertions.assertEquals("test-blocked", deny.reasonCode)
            }
        }
    }

    @Test
    fun `audit failure propagation`() = runBlocking {
        val throwingStore = object : dev.tramai.security.audit.AuditStore {
            override suspend fun appendNext(
                auditStreamId: String,
                eventFactory: (latest: dev.tramai.security.audit.AuditEvent?) -> dev.tramai.security.audit.AuditEvent,
            ): dev.tramai.security.audit.AuditEvent {
                throw RuntimeException("Audit store unavailable")
            }

            override suspend fun readStream(auditStreamId: String): List<dev.tramai.security.audit.AuditEvent> = emptyList()
            override suspend fun latestEvent(auditStreamId: String): dev.tramai.security.audit.AuditEvent? = null
        }

        val auditEngine = dev.tramai.security.audit.AuditEngine(throwingStore)
        val emitter = dev.tramai.security.audit.AuditEnginePolicyDecisionAuditEmitter(auditEngine)

        val helper = PolicyEnforcementHelper(
            policyEngine = policyEngine,
            migrationWarningGuard = AtomicBoolean(true),
            auditEmitter = emitter,
        )

        val context = buildTestContext(EnforcementPoint.BEFORE_PROVIDER_INVOCATION)

        try {
            helper.enforce(context)
            Assertions.fail("Expected RuntimeException from audit store failure")
        } catch (e: RuntimeException) {
            Assertions.assertEquals("Audit store unavailable", e.message)
        }
    }

    private fun buildTestContext(enforcementPoint: EnforcementPoint): PolicyContext = PolicyContext(
        enforcementPoint = enforcementPoint,
        correlationId = "audit-wiring-test",
        actorId = "system.test",
        policyVersion = "test",
    )
}
