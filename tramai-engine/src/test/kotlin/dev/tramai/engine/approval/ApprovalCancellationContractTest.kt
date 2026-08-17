package dev.tramai.engine.approval

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.AuthorizeResumeCommand
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.approval.ValidateResumeCommand
import dev.tramai.core.approval.ApprovalChallenge
import dev.tramai.core.approval.ApprovalValidation
import dev.tramai.core.approval.ApprovalAuthorization
import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.ClaimedApprovalContinuation
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.core.policy.ApprovalRequirement
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.engine.EngineEventObserver
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.PolicyEnforcementHelper
import dev.tramai.engine.ReplayEnvelopeFactory
import dev.tramai.engine.ResumeApprovalCommand
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.ResumeToolDeclarationDigestHelper
import dev.tramai.engine.ResumeToolReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.engine.ToolRegistry
import dev.tramai.engine.tool.ToolExecutionRequest
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cancellation contract for the extracted approval state machines (12 seams).
 *
 * For every seam a cancellation arriving at that exact point must:
 *  - propagate as the SAME CancellationException (identity preserved), and
 *  - NOT be converted into an uncertain business outcome, and
 *  - leave durable state in the documented transition.
 *
 * The 12 seams: (1) approval creation, (2) continuation creation,
 * (3) suspended-metadata persistence, (4) validateResume, (5) BEFORE_WORKFLOW_RESUME
 * policy, (6) authorizeResume, (7) claimForExecution, (8) replay reveal after claim,
 * (9) claimed execution delegate, (10) continuation completion, (11) cleanup,
 * (12) lifecycle audit.
 */
class ApprovalCancellationContractTest {

    private val fixedClock = Clock.fixed(Instant.parse("2026-06-07T12:00:00Z"), ZoneId.of("UTC"))
    private val digester = Sha256ToolArgumentsDigester()
    private val digest = Sha256Digest.of("sha256:" + "1".repeat(64))
    private val input = """{"x":2}"""
    private val inputDigest = digester.digest(SensitiveToolArguments.of(input))
    private val token = ApprovalToken.parsePresented("token-1")
    private val toolName = "test_tool"
    private val toolCallId = "call-1"
    private val approvalId = "approval-1"
    private val identity = EngineExecutionIdentity("wf-1", "corr-1", digest, "policy-v1", "actor-1")
    private val command = ResumeApprovalCommand(approvalId, 1, 3, token, "admin")
    private val cancellation = CancellationException("seam-cancellation")

    private val tool = FakeTool(toolName)
    private val operation = approvalOperation(ApprovalRegistryService::class.java.getMethod("first"))
    private val service = approvalService(operation)
    private val opRef = ResumeOperationReference(
        serviceInterface = ApprovalRegistryService::class.qualifiedName!!,
        methodName = "first",
        jvmMethodDescriptor = "()Ljava/lang/String;",
        resumeDefinitionDigest = dev.tramai.engine.ResumeDefinitionDigestHelper.compute(service, operation),
    )
    private val messages = listOf(
        Message(MessageRole.USER, "compute"),
        Message(MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall(toolCallId, toolName, input))),
    )
    private val prepared = ReplayEnvelopeFactory.prepareForSuspension(opRef, messages, toolCallId, toolName, 0)

    private fun metadata() = SuspendedInvocationMetadata(
        approvalId, toolCallId, toolName, 0, "corr-1", identity, ExecutionSecurityContext(),
        opRef, prepared.digest, conversationId = null, historySize = 0, tokenBudgetSnapshot = null,
        toolReference = ResumeToolReference(toolName, ResumeToolDeclarationDigestHelper.compute(tool)),
        toolSecurity = null,
    )

    private fun continuation(status: ApprovalContinuationStatus = ApprovalContinuationStatus.PENDING) = ApprovalContinuation(
        approvalId, "wf-1", "corr-1", toolCallId, toolName, inputDigest, "policy-v1", digest,
        status, Instant.EPOCH, Instant.MAX, null, null, null, version = 3L,
    )

    private fun requireDecision() = PolicyDecision.RequireApproval(
        ApprovalRequirement(toolName, inputDigest.value, "testing", 60_000),
    )

    private fun toolRequest() = ToolExecutionRequest(
        tool = tool,
        toolCall = ToolCall(toolCallId, toolName, input),
        operation = operation,
        correlationId = "corr-1",
        securityContext = ExecutionSecurityContext(),
        identity = identity,
        messages = messages,
        toolCallIndex = 0,
    )

    // ------------------------------------------------------------------
    // Suspension seams (1-3)
    // ------------------------------------------------------------------

    @Test
    fun `seam 1 - cancellation during approval creation propagates with no persisted state`() = runTest {
        val store = CancellationStore(cancelOn = "approval.create")
        val suspended = CancellationSuspendedStore()
        val gate = CancellationGate(cancelOn = "approval.create")
        val audit = CancellationAuditEmitter()
        val c = suspensionCoordinator(gate, store, suspended, audit)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.requireApproval(toolRequest(), requireDecision(), input) } }
            .isSameAs(cancellation)

        assertThat(store.createdContinuation).isFalse()
        assertThat(suspended.createdMetadata).isFalse()
        assertThat(gate.createdApproval).isFalse()
        assertThat(audit.suspendedCount).isZero()
    }

    @Test
    fun `seam 2 - cancellation during continuation creation propagates without compensation`() = runTest {
        val store = CancellationStore(cancelOn = "continuation.create")
        val suspended = CancellationSuspendedStore()
        val gate = CancellationGate()
        val audit = CancellationAuditEmitter()
        val c = suspensionCoordinator(gate, store, suspended, audit)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.requireApproval(toolRequest(), requireDecision(), input) } }
            .isSameAs(cancellation)

        // rethrowIfCancellation fires BEFORE compensation: no cancel calls, no audit
        assertThat(gate.cancelledApproval).isZero()
        assertThat(store.cancelledContinuation).isZero()
        assertThat(suspended.removedMetadata).isZero()
        assertThat(audit.cancelledCount).isZero()
    }

    @Test
    fun `seam 3 - cancellation during suspended-metadata persistence propagates without compensation`() = runTest {
        val store = CancellationStore()
        val suspended = CancellationSuspendedStore(cancelOn = "metadata.create")
        val gate = CancellationGate()
        val audit = CancellationAuditEmitter()
        val c = suspensionCoordinator(gate, store, suspended, audit)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.requireApproval(toolRequest(), requireDecision(), input) } }
            .isSameAs(cancellation)

        assertThat(gate.cancelledApproval).isZero()
        assertThat(store.cancelledContinuation).isZero()
        assertThat(suspended.removedMetadata).isZero()
    }

    // ------------------------------------------------------------------
    // Resume seams (4-12)
    // ------------------------------------------------------------------

    @Test
    fun `seam 4 - cancellation during validateResume leaves continuation pending`() = runTest {
        val (coordinator, store, suspended) = resumeHarness(cancelOn = "validate")

        assertThatThrownBy { kotlinx.coroutines.runBlocking { coordinator.resume(command) } }
            .isSameAs(cancellation)

        assertThat(store.lastStatus).isEqualTo(ApprovalContinuationStatus.PENDING)
        assertThat(store.claimCount).isZero()
        assertThat(suspended.removedCount).isZero()
        assertThat(store.completeCount).isZero()
    }

    @Test
    fun `seam 5 - cancellation during resume policy evaluation leaves continuation pending`() = runTest {
        val (coordinator, store, suspended) = resumeHarness(cancelOn = "policy")

        assertThatThrownBy { kotlinx.coroutines.runBlocking { coordinator.resume(command) } }
            .isSameAs(cancellation)

        assertThat(store.lastStatus).isEqualTo(ApprovalContinuationStatus.PENDING)
        assertThat(store.claimCount).isZero()
        assertThat(suspended.removedCount).isZero()
        assertThat(store.completeCount).isZero()
    }

    @Test
    fun `seam 6 - cancellation during authorizeResume leaves continuation pending`() = runTest {
        val (coordinator, store, suspended) = resumeHarness(cancelOn = "authorize")

        assertThatThrownBy { kotlinx.coroutines.runBlocking { coordinator.resume(command) } }
            .isSameAs(cancellation)

        assertThat(store.lastStatus).isEqualTo(ApprovalContinuationStatus.PENDING)
        assertThat(store.claimCount).isZero()
        assertThat(suspended.removedCount).isZero()
    }

    @Test
    fun `seam 7 - cancellation during claimForExecution leaves continuation pending`() = runTest {
        val (coordinator, store, suspended) = resumeHarness(cancelOn = "claim")

        assertThatThrownBy { kotlinx.coroutines.runBlocking { coordinator.resume(command) } }
            .isSameAs(cancellation)

        assertThat(store.claimCount).isEqualTo(1)
        assertThat(store.completeCount).isZero()
        assertThat(suspended.removedCount).isZero()
    }

    @Test
    fun `seam 8 - cancellation during replay reveal leaves continuation claimed`() = runTest {
        val (coordinator, store, suspended) = resumeHarness(cancelOn = "reveal")

        assertThatThrownBy { kotlinx.coroutines.runBlocking { coordinator.resume(command) } }
            .isSameAs(cancellation)

        assertThat(store.claimCount).isEqualTo(1)
        assertThat(store.completeCount).isZero()
        assertThat(suspended.removedCount).isZero()
        assertThat(store.lastStatus).isEqualTo(ApprovalContinuationStatus.CLAIMED)
    }

    @Test
    fun `seam 9 - cancellation during claimed execution delegate leaves continuation claimed`() = runTest {
        val (coordinator, store, suspended) = resumeHarness(cancelOn = "execute")

        assertThatThrownBy { kotlinx.coroutines.runBlocking { coordinator.resume(command) } }
            .isSameAs(cancellation)

        assertThat(store.claimCount).isEqualTo(1)
        assertThat(store.completeCount).isZero()
        assertThat(suspended.removedCount).isZero()
    }

    @Test
    fun `seam 10 - cancellation during continuation completion leaves metadata retained`() = runTest {
        val (coordinator, store, suspended) = resumeHarness(cancelOn = "complete")

        assertThatThrownBy { kotlinx.coroutines.runBlocking { coordinator.resume(command) } }
            .isSameAs(cancellation)

        assertThat(store.completeCount).isEqualTo(1)
        assertThat(suspended.removedCount).isZero()
    }

    @Test
    fun `seam 11 - cancellation during cleanup propagates after completion`() = runTest {
        val (coordinator, store, suspended) = resumeHarness(cancelOn = "remove")

        assertThatThrownBy { kotlinx.coroutines.runBlocking { coordinator.resume(command) } }
            .isSameAs(cancellation)

        assertThat(store.completeCount).isEqualTo(1)
        assertThat(suspended.removedCount).isEqualTo(1)
    }

    @Test
    fun `seam 12 - cancellation during completion audit propagates after cleanup`() = runTest {
        val (coordinator, store, suspended) = resumeHarness(cancelOn = "audit.complete")

        assertThatThrownBy { kotlinx.coroutines.runBlocking { coordinator.resume(command) } }
            .isSameAs(cancellation)

        assertThat(store.completeCount).isEqualTo(1)
        assertThat(suspended.removedCount).isEqualTo(1)
    }

    // ------------------------------------------------------------------
    // Harness builders
    // ------------------------------------------------------------------

    private fun suspensionCoordinator(
        gate: CancellationGate,
        store: CancellationStore,
        suspended: CancellationSuspendedStore,
        audit: CancellationAuditEmitter,
    ) = ApprovalSuspensionCoordinator(
        approvalGateCoordinator = gate,
        approvalContinuationStore = store,
        suspendedInvocationStore = suspended,
        resumeOperationRegistry = ResumeOperationRegistry(),
        serviceDefinition = service,
        resumeExecutor = StubResumeExecutor(),
        toolArgumentsDigester = digester,
        clock = fixedClock,
        approvalLifecycleAuditEmitter = audit,
    )

    private fun resumeHarness(
        cancelOn: String,
    ): Triple<ApprovalResumeCoordinator, CancellationStore, CancellationSuspendedStore> {
        val store = CancellationStore(cancelOn = cancelOn)
        val suspended = CancellationSuspendedStore(cancelOn = cancelOn)
        val gate = CancellationGate(cancelOn = cancelOn)
        val audit = CancellationAuditEmitter(cancelOn = cancelOn)
        val observer = CancellationObserver()
        val policy = PolicyEnforcementHelper(
            policyEngine = CancellationPolicyEngine(cancelOn),
            migrationWarningGuard = AtomicBoolean(false),
        )
        val registry = ResumeOperationRegistry().also { it.register(service, operation, CancellationExecutor(cancelOn)) }
        val coordinator = ApprovalResumeCoordinator(
            approvalContinuationStore = store,
            suspendedInvocationStore = suspended,
            resumeOperationRegistry = registry,
            toolRegistry = ToolRegistry(mapOf(toolName to tool)),
            toolArgumentsDigester = digester,
            approvalLifecycleAuditEmitter = audit,
            engineEventObserver = observer,
            claimService = ContinuationClaimService(store),
            authorizationService = ReplayAuthorizationService(gate, suspended, audit, policy, observer),
        )
        return Triple(coordinator, store, suspended)
    }

    // ------------------------------------------------------------------
    // Test doubles with injectable cancellation seams
    // ------------------------------------------------------------------

    private inner class CancellationGate(
        private val cancelOn: String? = null,
    ) : ApprovalGateCoordinator {
        var createdApproval = false
        var cancelledApproval = 0
        var validated = 0
        var authorized = 0

        private fun strike(seam: String) {
            if (cancelOn == seam) throw cancellation
        }

        override suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge {
            strike("approval.create")
            createdApproval = true
            return ApprovalChallenge(approvalId, token, Instant.MAX)
        }

        override suspend fun validateResume(command: ValidateResumeCommand): ApprovalValidation {
            strike("validate")
            validated++
            return ApprovalValidation(command.approvalId, command.consumedBy, Instant.EPOCH, 0L)
        }

        override suspend fun authorizeResume(command: AuthorizeResumeCommand): ApprovalAuthorization {
            strike("authorize")
            authorized++
            return ApprovalAuthorization(command.approvalId, command.consumedBy, Instant.EPOCH, 0L, replayed = false)
        }

        override suspend fun cancelApproval(approvalId: String, expectedVersion: Long, reason: String) { cancelledApproval++ }
    }

    private inner class CancellationStore(
        private val cancelOn: String? = null,
    ) : dev.tramai.core.approval.ApprovalContinuationStore {
        var createdContinuation = false
        var cancelledContinuation = 0
        var claimCount = 0
        var completeCount = 0
        var lastStatus: ApprovalContinuationStatus = ApprovalContinuationStatus.PENDING

        private fun strike(seam: String) {
            if (cancelOn == seam) throw cancellation
        }

        override suspend fun create(continuation: ApprovalContinuation, arguments: SensitiveToolArguments): ApprovalContinuation {
            strike("continuation.create")
            createdContinuation = true
            return continuation
        }

        override suspend fun get(approvalId: String): ApprovalContinuation? = continuation().copy(status = lastStatus)

        override suspend fun claimForExecution(approvalId: String, expectedVersion: Long, claimedBy: String): ClaimedApprovalContinuation {
            claimCount++
            lastStatus = ApprovalContinuationStatus.CLAIMED
            strike("claim")
            return ClaimedApprovalContinuation(continuation().copy(status = ApprovalContinuationStatus.CLAIMED), SensitiveToolArguments.of(input))
        }

        override suspend fun complete(approvalId: String, expectedVersion: Long, completedBy: String): ApprovalContinuation {
            completeCount++
            strike("complete")
            lastStatus = ApprovalContinuationStatus.COMPLETED
            return continuation().copy(status = ApprovalContinuationStatus.COMPLETED)
        }

        override suspend fun expire(approvalId: String, expectedVersion: Long): ApprovalContinuation = continuation()
        override suspend fun cancel(approvalId: String, expectedVersion: Long): ApprovalContinuation {
            cancelledContinuation++
            return continuation().copy(status = ApprovalContinuationStatus.CANCELLED)
        }
        override suspend fun findStaleClaimed(claimedBefore: Instant, limit: Int): List<ApprovalContinuation> = emptyList()
        override suspend fun forceCancelClaimed(approvalId: String, expectedVersion: Long, cancelledBy: String, reasonCode: String): ApprovalContinuation = continuation()
        override suspend fun sweepExpired(): Int = 0
    }

    private inner class CancellationSuspendedStore(
        private val cancelOn: String? = null,
    ) : SuspendedInvocationStore {
        var createdMetadata = false
        var removedMetadata = 0
        var removedCount = 0

        private fun strike(seam: String) {
            if (cancelOn == seam) throw cancellation
        }

        override suspend fun create(metadata: SuspendedInvocationMetadata, replayEnvelope: SensitiveReplayEnvelope) {
            strike("metadata.create")
            createdMetadata = true
        }

        override suspend fun get(approvalId: String): SuspendedInvocationMetadata? = metadata()

        override suspend fun revealReplayEnvelope(approvalId: String): SensitiveReplayEnvelope? {
            strike("reveal")
            return prepared.envelope
        }

        override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? {
            removedCount++
            strike("remove")
            removedMetadata++
            return metadata()
        }
    }

    private inner class CancellationAuditEmitter(
        private val cancelOn: String? = null,
    ) : dev.tramai.core.approval.ApprovalLifecycleAuditEmitter {
        var suspendedCount = 0
        var cancelledCount = 0

        private fun strike(seam: String) {
            if (cancelOn == seam) throw cancellation
        }

        override suspend fun onToolExecutionSuspended(approvalId: String, workflowRunId: String, toolName: String, toolCallId: String, correlationId: String, argumentsDigest: Sha256Digest, expiresAt: Instant) { suspendedCount++ }
        override suspend fun onToolExecutionResumed(approvalId: String, workflowRunId: String, toolName: String, resumedBy: String) = Unit
        override suspend fun onToolExecutionCompleted(approvalId: String, workflowRunId: String, toolName: String, completedBy: String) { strike("audit.complete") }
        override suspend fun onUncertainOutcome(approvalId: String, workflowRunId: String, toolName: String, reason: String) = Unit
        override suspend fun onSuspensionCancelled(approvalId: String, workflowRunId: String, toolName: String, reason: String) { cancelledCount++ }
        override suspend fun onStaleClaimDetected(approvalId: String, workflowRunId: String, toolName: String, claimedAt: Instant) = Unit
        override suspend fun onClaimedContinuationForceCancellationRequested(approvalId: String, workflowRunId: String, toolName: String, cancelledBy: String, reasonCode: String) = Unit
        override suspend fun onClaimedContinuationForceCancelled(approvalId: String, workflowRunId: String, toolName: String, cancelledBy: String, reasonCode: String) = Unit
    }

    private class CancellationObserver : EngineEventObserver {
        override fun onEngineEvent(name: String, attributes: Map<String, Any?>) = Unit
    }

    private inner class CancellationPolicyEngine(
        private val cancelOn: String?,
    ) : PolicyEngine {
        override suspend fun evaluate(context: dev.tramai.core.policy.PolicyContext): PolicyDecision {
            if (cancelOn == "policy") throw cancellation
            return PolicyDecision.Allow
        }
    }

    private inner class CancellationExecutor(
        private val cancelOn: String?,
    ) : ClaimedResumeExecutor {
        var calls = 0
        override suspend fun execute(request: ClaimedResumeExecutionRequest): Any? {
            if (cancelOn == "execute") throw cancellation
            calls++
            return "executed"
        }
    }

    private class FakeTool(
        override val name: String,
    ) : ResolvedTool {
        override val description: String = "test"
        override val inputSchemaJson: String = """{"type":"object"}"""
        override val idempotent: Boolean = false
        override val sideEffectLevel: dev.tramai.core.model.SideEffectLevel = dev.tramai.core.model.SideEffectLevel.READ_ONLY
        override val security: dev.tramai.core.policy.ToolSecurityMetadata? = null
        override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult = ToolResult.Success("{}")
    }
}
