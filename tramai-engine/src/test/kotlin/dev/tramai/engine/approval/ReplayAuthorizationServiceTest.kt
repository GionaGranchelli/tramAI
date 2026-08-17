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
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.ApprovalTokenRejectedException
import dev.tramai.core.exception.NestedApprovalNotSupportedException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.engine.EngineEventObserver
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.PolicyEnforcementHelper
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.engine.ResumeApprovalCommand
import dev.tramai.engine.approval.ReplayAuthorizationServiceTest.RecordingGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct contract tests for [ReplayAuthorizationService].
 *
 * The pre-claim authorization protocol has a strict order:
 * validate token (non-destructive) → evaluate BEFORE_WORKFLOW_RESUME policy →
 * authorize (one-time consumption) — and the deny / nested-approval paths must
 * cancel durable state and throw before any authorization.
 */
class ReplayAuthorizationServiceTest {

    private val digest = Sha256Digest.of("sha256:" + "1".repeat(64))
    private val token = ApprovalToken.parsePresented("token-1")
    private val command = ResumeApprovalCommand("a", 1, 3, token, "me")
    private val resolvedTool = FakeTool("tool")

    private fun metadata() = SuspendedInvocationMetadata(
        "a", "call", "tool", 0, "c",
        EngineExecutionIdentity("wf", "c", digest, "p", "actor"),
        ExecutionSecurityContext(),
        ResumeOperationReference("x", "m", "()V", digest),
        digest,
        toolReference = dev.tramai.engine.ResumeToolReference("tool", digest),
    )

    private fun continuation() = ApprovalContinuation(
        "a", "wf", "c", "call", "tool", digest, "p", digest,
        ApprovalContinuationStatus.PENDING, Instant.EPOCH, Instant.MAX, null, null, null, version = 3L,
    )

    private fun service(
        gate: RecordingGate = RecordingGate(),
        policyDecision: PolicyDecision = PolicyDecision.Allow,
        events: MutableList<String> = mutableListOf(),
    ): Triple<ReplayAuthorizationService, RecordingGate, MutableList<String>> {
        val gateRecording = gate.apply { this.events = events }
        val suspended = RecordingSuspendedStore(events)
        val audit = RecordingAuditEmitter(events)
        val policy = PolicyEnforcementHelper(
            policyEngine = PolicyEngine { context ->
                events += "policy:${context.enforcementPoint.name}"
                policyDecision
            },
            migrationWarningGuard = AtomicBoolean(false),
        )
        val observer = RecordingObserver(events)
        return Triple(
            ReplayAuthorizationService(
                approvalGateCoordinator = gateRecording,
                suspendedInvocationStore = suspended,
                approvalLifecycleAuditEmitter = audit,
                policyHelper = policy,
                engineEventObserver = observer,
            ),
            gateRecording,
            events,
        )
    }

    @Test
    fun `recorded ordering validate then policy then authorize`() = runTest {
        val (s, _, events) = service()
        val continuation = continuation()

        s.validateToken(command, metadata(), continuation)
        s.decideResumePolicy(command, metadata(), resolvedTool)
        s.authorize(command, metadata(), continuation)

        assertThat(events).containsExactly(
            "validate",
            "policy:BEFORE_WORKFLOW_RESUME",
            "authorize",
        )
    }

    @Test
    fun `invalid token means policy is never evaluated`() = runTest {
        val gate = RecordingGate(failValidateWith = ApprovalTokenRejectedException("a"))
        val (s, _, events) = service(gate = gate)
        val continuation = continuation()

        assertThatThrownBy { kotlinx.coroutines.runBlocking { s.validateToken(command, metadata(), continuation) } }
            .isInstanceOf(ApprovalTokenRejectedException::class.java)

        assertThat(events).containsExactly("validate")
        assertThat(events).noneMatch { it.startsWith("policy:") }
    }

    @Test
    fun `valid token deny cancels state and does not authorize`() = runTest {
        val deny = PolicyDecision.Deny(reason = "blocked", reasonCode = "WF_DENIED")
        val (s, gate, events) = service(policyDecision = deny)
        val continuation = continuation()

        val decision = s.decideResumePolicy(command, metadata(), resolvedTool)
        assertThat(decision).isInstanceOf(ReplayAuthorizationDecision.Denied::class.java)
        assertThatThrownBy { kotlinx.coroutines.runBlocking { s.denyAndCancel(command, metadata(), (decision as ReplayAuthorizationDecision.Denied).decision, RecordingContinuationStore(events)) } }
            .isInstanceOf(PolicyViolationException::class.java)

        assertThat(gate.authorizeCount).isZero()
        assertThat(events).contains("cancel-state", "audit:cancelled")
        assertThat(events).noneMatch { it == "authorize" }
    }

    @Test
    fun `valid token nested approval cancels state and does not authorize`() = runTest {
        val require = PolicyDecision.RequireApproval(
            dev.tramai.core.policy.ApprovalRequirement("tool", "", "testing", 60_000),
        )
        val (s, gate, events) = service(policyDecision = require)

        val decision = s.decideResumePolicy(command, metadata(), resolvedTool)
        assertThat(decision).isEqualTo(ReplayAuthorizationDecision.RequiresNestedApproval)
        assertThatThrownBy { kotlinx.coroutines.runBlocking { s.cancelForNestedApproval(command, metadata(), RecordingContinuationStore(events)) } }
            .isInstanceOf(NestedApprovalNotSupportedException::class.java)

        assertThat(gate.authorizeCount).isZero()
        assertThat(events).contains("cancel-state", "audit:cancelled")
        assertThat(events).noneMatch { it == "authorize" }
    }

    @Test
    fun `allow authorizes exactly once`() = runTest {
        val (s, gate, _) = service()
        val continuation = continuation()

        s.authorize(command, metadata(), continuation)

        assertThat(gate.authorizeCount).isEqualTo(1)
        assertThat(gate.lastAuthorizeCommand?.consumedBy).isEqualTo("me")
        assertThat(gate.lastAuthorizeCommand?.presentedToken).isEqualTo(token)
    }

    @Test
    fun `fresh authorization emits no replay event`() = runTest {
        val (s, _, events) = service()
        val continuation = continuation()
        val auth = s.authorize(command, metadata(), continuation)

        s.emitAuthorizationReplayed(auth.replayed, command, metadata())
        assertThat(events).noneMatch { it.contains("authorization_replayed") }
    }

    @Test
    fun `exact authorization replay emits fail-open replay event`() = runTest {
        val (s, _, events) = service()
        val continuation = continuation()

        s.emitAuthorizationReplayed(replayed = true, command = command, metadata = metadata())
        assertThat(events).contains("tramai.approval.authorization_replayed")
    }

    @Test
    fun `replay event observer failure is fail-open`() = runTest {
        val gate = RecordingGate()
        val suspended = RecordingSuspendedStore(mutableListOf())
        val audit = RecordingAuditEmitter(mutableListOf())
        val policy = PolicyEnforcementHelper(
            policyEngine = PolicyEngine { PolicyDecision.Allow },
            migrationWarningGuard = AtomicBoolean(false),
        )
        val throwing = object : EngineEventObserver {
            override fun onEngineEvent(name: String, attributes: Map<String, Any?>) {
                throw RuntimeException("observer-down")
            }
        }
        val s = ReplayAuthorizationService(gate, suspended, audit, policy, throwing)

        // must not throw — event observer failures must not prevent resume completion
        s.emitAuthorizationReplayed(replayed = true, command = command, metadata = metadata())
    }

    @Test
    fun `replay event observer cancellation propagates`() = runTest {
        val gate = RecordingGate()
        val suspended = RecordingSuspendedStore(mutableListOf())
        val audit = RecordingAuditEmitter(mutableListOf())
        val policy = PolicyEnforcementHelper(
            policyEngine = PolicyEngine { PolicyDecision.Allow },
            migrationWarningGuard = AtomicBoolean(false),
        )
        val cancellation = CancellationException("cancel")
        val throwing = object : EngineEventObserver {
            override fun onEngineEvent(name: String, attributes: Map<String, Any?>) {
                throw cancellation
            }
        }
        val s = ReplayAuthorizationService(gate, suspended, audit, policy, throwing)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { s.emitAuthorizationReplayed(replayed = true, command = command, metadata = metadata()) } }
            .isSameAs(cancellation)
    }

    @Test
    fun `real cancellation during validation propagates`() = runTest {
        val cancellation = CancellationException("cancel")
        val gate = RecordingGate(failValidateWith = cancellation)
        val (s, _, _) = service(gate = gate)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { s.validateToken(command, metadata(), continuation()) } }
            .isSameAs(cancellation)
    }

    // ------------------------------------------------------------------
    // Test doubles
    // ------------------------------------------------------------------

    inner class RecordingGate(
        var failValidateWith: Throwable? = null,
    ) : ApprovalGateCoordinator {
        var events: MutableList<String> = mutableListOf()
        var validateCount = 0
        var authorizeCount = 0
        var lastAuthorizeCommand: AuthorizeResumeCommand? = null

        override suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge =
            ApprovalChallenge("a", token, Instant.MAX)

        override suspend fun validateResume(command: ValidateResumeCommand): ApprovalValidation {
            validateCount++
            events += "validate"
            failValidateWith?.let { throw it }
            return ApprovalValidation(command.approvalId, command.consumedBy, Instant.EPOCH, 0L)
        }

        override suspend fun authorizeResume(command: AuthorizeResumeCommand): ApprovalAuthorization {
            authorizeCount++
            events += "authorize"
            lastAuthorizeCommand = command
            return ApprovalAuthorization(command.approvalId, command.consumedBy, Instant.EPOCH, 0L, replayed = false)
        }

        override suspend fun cancelApproval(approvalId: String, expectedVersion: Long, reason: String) = Unit
    }

    inner class RecordingContinuationStore(
        private val events: MutableList<String> = mutableListOf(),
    ) : dev.tramai.core.approval.ApprovalContinuationStore {
        override suspend fun create(continuation: ApprovalContinuation, arguments: SensitiveToolArguments): ApprovalContinuation = continuation
        override suspend fun get(approvalId: String): ApprovalContinuation? = null
        override suspend fun claimForExecution(approvalId: String, expectedVersion: Long, claimedBy: String): dev.tramai.core.approval.ClaimedApprovalContinuation =
            dev.tramai.core.approval.ClaimedApprovalContinuation(
                ApprovalContinuation("a", "wf", "c", "call", "tool", digest, "p", digest, ApprovalContinuationStatus.PENDING, Instant.EPOCH, Instant.MAX, null, null, null, version = 3L),
                SensitiveToolArguments.of("{}"),
            )
        override suspend fun complete(approvalId: String, expectedVersion: Long, completedBy: String): ApprovalContinuation =
            ApprovalContinuation("a", "wf", "c", "call", "tool", digest, "p", digest, ApprovalContinuationStatus.COMPLETED, Instant.EPOCH, Instant.MAX, "me", Instant.EPOCH, Instant.EPOCH, version = 4L)
        override suspend fun expire(approvalId: String, expectedVersion: Long): ApprovalContinuation =
            ApprovalContinuation("a", "wf", "c", "call", "tool", digest, "p", digest, ApprovalContinuationStatus.PENDING, Instant.EPOCH, Instant.MAX, null, null, null, version = 3L)
        override suspend fun cancel(approvalId: String, expectedVersion: Long): ApprovalContinuation {
            events += "cancel-state"
            return ApprovalContinuation("a", "wf", "c", "call", "tool", digest, "p", digest, ApprovalContinuationStatus.CANCELLED, Instant.EPOCH, Instant.MAX, null, null, null, version = 3L)
        }
        override suspend fun findStaleClaimed(claimedBefore: Instant, limit: Int): List<ApprovalContinuation> = emptyList()
        override suspend fun forceCancelClaimed(approvalId: String, expectedVersion: Long, cancelledBy: String, reasonCode: String): ApprovalContinuation =
            ApprovalContinuation("a", "wf", "c", "call", "tool", digest, "p", digest, ApprovalContinuationStatus.CANCELLED, Instant.EPOCH, Instant.MAX, null, null, null, version = 3L)
        override suspend fun sweepExpired(): Int = 0
    }

    class RecordingSuspendedStore(
        private val events: MutableList<String>,
    ) : SuspendedInvocationStore {
        override suspend fun create(metadata: SuspendedInvocationMetadata, replayEnvelope: SensitiveReplayEnvelope) = Unit
        override suspend fun get(approvalId: String): SuspendedInvocationMetadata? = null
        override suspend fun revealReplayEnvelope(approvalId: String): SensitiveReplayEnvelope? = null
        override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? {
            events += "suspended.remove"
            return null
        }
    }

    class RecordingAuditEmitter(
        private val events: MutableList<String>,
    ) : dev.tramai.core.approval.ApprovalLifecycleAuditEmitter {
        override suspend fun onToolExecutionSuspended(approvalId: String, workflowRunId: String, toolName: String, toolCallId: String, correlationId: String, argumentsDigest: Sha256Digest, expiresAt: Instant) = Unit
        override suspend fun onToolExecutionResumed(approvalId: String, workflowRunId: String, toolName: String, resumedBy: String) = Unit
        override suspend fun onToolExecutionCompleted(approvalId: String, workflowRunId: String, toolName: String, completedBy: String) = Unit
        override suspend fun onUncertainOutcome(approvalId: String, workflowRunId: String, toolName: String, reason: String) = Unit
        override suspend fun onSuspensionCancelled(approvalId: String, workflowRunId: String, toolName: String, reason: String) { events += "audit:cancelled" }
        override suspend fun onStaleClaimDetected(approvalId: String, workflowRunId: String, toolName: String, claimedAt: Instant) = Unit
        override suspend fun onClaimedContinuationForceCancellationRequested(approvalId: String, workflowRunId: String, toolName: String, cancelledBy: String, reasonCode: String) = Unit
        override suspend fun onClaimedContinuationForceCancelled(approvalId: String, workflowRunId: String, toolName: String, cancelledBy: String, reasonCode: String) = Unit
    }

    class RecordingObserver(
        private val events: MutableList<String>,
    ) : EngineEventObserver {
        override fun onEngineEvent(name: String, attributes: Map<String, Any?>) {
            events += name
        }
    }

    private class FakeTool(
        override val name: String,
    ) : dev.tramai.core.model.ResolvedTool {
        override val description: String = "test"
        override val inputSchemaJson: String = """{"type":"object"}"""
        override val idempotent: Boolean = false
        override val sideEffectLevel: dev.tramai.core.model.SideEffectLevel = dev.tramai.core.model.SideEffectLevel.READ_ONLY
        override val security: dev.tramai.core.policy.ToolSecurityMetadata? = null
        override suspend fun execute(input: Any, context: dev.tramai.core.model.ToolExecutionContext): dev.tramai.core.model.ToolResult =
            dev.tramai.core.model.ToolResult.Success("{}")
    }
}
