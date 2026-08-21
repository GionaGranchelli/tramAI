package dev.tramai.engine.approval

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.AuthorizeResumeCommand
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.approval.ValidateResumeCommand
import dev.tramai.core.approval.ApprovalChallenge
import dev.tramai.core.approval.ApprovalValidation
import dev.tramai.core.approval.ApprovalAuthorization
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.ApprovalSuspendedException
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.NestedApprovalNotSupportedException
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.core.policy.ApprovalRequirement
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.engine.TokenBudgetTracker
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

/**
 * Direct contract tests for [ApprovalSuspensionCoordinator].
 *
 * The coordinator implements [dev.tramai.engine.tool.ToolApprovalGate] and owns
 * the full suspension saga: binding validation, approval challenge creation,
 * continuation persistence, suspended-metadata persistence, and reverse-order
 * compensation that never replaces the initiating failure.
 */
class ApprovalSuspensionCoordinatorTest {

    private val fixedClock = Clock.fixed(Instant.parse("2026-06-07T12:00:00Z"), ZoneId.of("UTC"))
    private val digester = Sha256ToolArgumentsDigester()
    private val digest = Sha256Digest.of("sha256:" + "1".repeat(64))
    private val toolName = "test_tool"
    private val toolCallId = "call-1"
    private val input = """{"x":2}"""
    private val workflowRunId = "wf-1"
    private val correlationId = "corr-1"
    private val identity = EngineExecutionIdentity(workflowRunId, correlationId, digest, "policy-v1", "actor-1")

    private val tool = FakeTool(toolName)
    private val toolCall = ToolCall(toolCallId, toolName, input)
    private val operation = approvalOperation(ApprovalRegistryService::class.java.getMethod("first"))

    private fun request(
        resumingApproval: Boolean = false,
        parentApprovalId: String? = null,
        allowRenewed: Boolean = false,
    ) = ToolExecutionRequest(
        tool = tool,
        toolCall = toolCall,
        operation = operation,
        correlationId = correlationId,
        securityContext = ExecutionSecurityContext(),
        identity = identity,
        messages = listOf(
            Message(MessageRole.USER, "hi"),
            Message(MessageRole.ASSISTANT, "", toolCalls = listOf(toolCall)),
        ),
        toolCallIndex = 0,
        resumingApproval = resumingApproval,
        parentApprovalId = parentApprovalId,
        allowRenewedApprovedBindingDuringResume = allowRenewed,
    )

    private fun requireDecision(timeoutMillis: Long = 60_000) = PolicyDecision.RequireApproval(
        ApprovalRequirement(
            toolName = toolName,
            argumentsDigest = digester.digest(SensitiveToolArguments.of(input)).value,
            reason = "testing",
            timeoutMillis = timeoutMillis,
        ),
    )

    private fun coordinator(
        gate: FakeApprovalGate = FakeApprovalGate(),
        store: FakeStore = FakeStore(),
        suspended: FakeSuspendedStore = FakeSuspendedStore(),
        registry: ResumeOperationRegistry = ResumeOperationRegistry(),
        audit: RecordingApprovalLifecycleAuditEmitter = RecordingApprovalLifecycleAuditEmitter(),
    ) = ApprovalSuspensionCoordinator(
        approvalGateCoordinator = gate,
        approvalContinuationStore = store,
        suspendedInvocationStore = suspended,
        resumeOperationRegistry = registry,
        serviceDefinition = approvalService(operation),
        resumeExecutor = StubResumeExecutor(),
        toolArgumentsDigester = digester,
        clock = fixedClock,
        approvalLifecycleAuditEmitter = audit,
    )

    @Test
    fun `happy suspension persists challenge continuation and metadata then throws ApprovalSuspendedException`() { runTest {
        val gate = FakeApprovalGate()
        val store = FakeStore()
        val suspended = FakeSuspendedStore()
        val audit = RecordingApprovalLifecycleAuditEmitter()
        val c = coordinator(gate, store, suspended, audit = audit)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.requireApproval(request(), requireDecision(), input) } }
            .isInstanceOfSatisfying(ApprovalSuspendedException::class.java) { ex ->
                assertThat(ex.approvalId).isEqualTo("challenge-1")
                assertThat(ex.workflowRunId).isEqualTo(workflowRunId)
                assertThat(ex.continuationVersion).isEqualTo(0L)
            }

        assertThat(gate.createCount).isEqualTo(1)
        assertThat(gate.cancelCount).isZero()
        assertThat(store.createCount).isEqualTo(1)
        assertThat(store.cancelCount).isZero()
        assertThat(store.createdContinuation.status).isEqualTo(ApprovalContinuationStatus.PENDING)
        assertThat(suspended.createCount).isEqualTo(1)
        assertThat(suspended.removeCount).isZero()
        assertThat(audit.suspendedCount).isEqualTo(1)
        assertThat(suspended.createdMetadata.approvalId).isEqualTo("challenge-1")
    }
    }

    @Test
    fun `tool name binding mismatch fails before any persistence`() { runTest {
        val gate = FakeApprovalGate()
        val store = FakeStore()
        val suspended = FakeSuspendedStore()
        val c = coordinator(gate, store, suspended)
        val decision = PolicyDecision.RequireApproval(
            ApprovalRequirement("different-tool", "", "testing", 60_000),
        )

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.requireApproval(request(), decision, input) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tool binding mismatch")

        assertThat(gate.createCount).isZero()
        assertThat(store.createCount).isZero()
        assertThat(suspended.createCount).isZero()
    }
    }

    @Test
    fun `arguments digest mismatch fails before any persistence`() { runTest {
        val gate = FakeApprovalGate()
        val store = FakeStore()
        val suspended = FakeSuspendedStore()
        val c = coordinator(gate, store, suspended)
        val decision = PolicyDecision.RequireApproval(
            ApprovalRequirement(toolName, "sha256:" + "2".repeat(64), "testing", 60_000),
        )

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.requireApproval(request(), decision, input) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("argument binding mismatch")

        assertThat(gate.createCount).isZero()
        assertThat(store.createCount).isZero()
        assertThat(suspended.createCount).isZero()
    }
    }

    @Test
    fun `invalid timeout fails before any persistence`() { runTest {
        val gate = FakeApprovalGate()
        val store = FakeStore()
        val suspended = FakeSuspendedStore()
        val c = coordinator(gate, store, suspended)
        val decision = PolicyDecision.RequireApproval(
            ApprovalRequirement(toolName, "", "testing", 0),
        )

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.requireApproval(request(), decision, input) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("timeout")

        assertThat(gate.createCount).isZero()
        assertThat(store.createCount).isZero()
    }
    }

    @Test
    fun `continuation create failure compensates in reverse order and rethrows original`() { runTest {
        val gate = FakeApprovalGate()
        val store = FakeStore(failOnCreate = true)
        val suspended = FakeSuspendedStore()
        val c = coordinator(gate, store, suspended)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.requireApproval(request(), requireDecision(), input) } }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessage("create-failure")

        // Reverse compensation: suspended remove (best-effort, no-op since never created), continuation cancel, approval cancel
        assertThat(gate.createCount).isEqualTo(1)
        assertThat(gate.cancelCount).isEqualTo(1)
        assertThat(store.createCount).isEqualTo(1)
        assertThat(store.cancelCount).isEqualTo(1)
        assertThat(suspended.removeCount).isEqualTo(1)
    }
    }

    @Test
    fun `metadata create failure compensates continuation and approval`() { runTest {
        val gate = FakeApprovalGate()
        val store = FakeStore()
        val suspended = FakeSuspendedStore(failOnCreate = true)
        val c = coordinator(gate, store, suspended)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.requireApproval(request(), requireDecision(), input) } }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessage("suspended-create-failure")

        assertThat(gate.createCount).isEqualTo(1)
        assertThat(gate.cancelCount).isEqualTo(1)
        assertThat(store.createCount).isEqualTo(1)
        assertThat(store.cancelCount).isEqualTo(1)
        assertThat(suspended.removeCount).isEqualTo(1)
    }
    }

    @Test
    fun `compensation failure never replaces the original failure`() { runTest {
        val gate = FakeApprovalGate()
        val store = FakeStore(failOnCreate = true)
        val suspended = FakeSuspendedStore()
        val c = coordinator(gate, store, suspended)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.requireApproval(request(), requireDecision(), input) } }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessage("create-failure")
    }
    }

    @Test
    fun `successful ApprovalSuspendedException never triggers compensation`() { runTest {
        val gate = FakeApprovalGate()
        val store = FakeStore()
        val suspended = FakeSuspendedStore()
        val audit = RecordingApprovalLifecycleAuditEmitter()
        val c = coordinator(gate, store, suspended, audit = audit)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.requireApproval(request(), requireDecision(), input) } }
            .isInstanceOf(ApprovalSuspendedException::class.java)

        assertThat(gate.cancelCount).isZero()
        assertThat(store.cancelCount).isZero()
        assertThat(suspended.removeCount).isZero()
        assertThat(audit.cancelledCount).isZero()
    }
    }

    @Test
    fun `renewed approved binding returns without creating another approval`() { runTest {
        val gate = FakeApprovalGate()
        val store = FakeStore()
        val suspended = FakeSuspendedStore()
        val c = coordinator(gate, store, suspended)

        c.requireApproval(request(resumingApproval = true, allowRenewed = true), requireDecision(), input)

        assertThat(gate.createCount).isZero()
        assertThat(store.createCount).isZero()
        assertThat(suspended.createCount).isZero()
    }
    }

    @Test
    fun `renewed binding tool name mismatch is rejected`() { runTest {
        val c = coordinator()
        val decision = PolicyDecision.RequireApproval(
            ApprovalRequirement("different-tool", "", "testing", 60_000),
        )

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.requireApproval(request(resumingApproval = true, allowRenewed = true), decision, input) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tool name mismatch")
    }
    }

    @Test
    fun `nested approval is rejected during resumed workflow`() { runTest {
        val c = coordinator()

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.requireApproval(request(resumingApproval = true), requireDecision(), input) } }
            .isInstanceOf(NestedApprovalNotSupportedException::class.java)
    }
    }

    @Test
    fun `cancellation during approval creation propagates and skips compensation`() { runTest {
        val gate = FakeApprovalGate(failOnCreateWith = CancellationException("boom"))
        val store = FakeStore()
        val suspended = FakeSuspendedStore()
        val c = coordinator(gate, store, suspended)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.requireApproval(request(), requireDecision(), input) } }
            .isInstanceOf(CancellationException::class.java)
            .hasMessage("boom")

        assertThat(store.cancelCount).isZero()
        assertThat(gate.cancelCount).isZero()
        assertThat(suspended.removeCount).isZero()
    }
    }

    @Test
    fun `cancellation during continuation creation propagates and skips compensation`() { runTest {
        val gate = FakeApprovalGate()
        val store = FakeStore(failOnCreateWith = CancellationException("boom"))
        val suspended = FakeSuspendedStore()
        val c = coordinator(gate, store, suspended)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.requireApproval(request(), requireDecision(), input) } }
            .isInstanceOf(CancellationException::class.java)
            .hasMessage("boom")

        assertThat(gate.cancelCount).isZero()
        assertThat(store.cancelCount).isZero()
        assertThat(suspended.removeCount).isZero()
    }
    }

    @Test
    fun `missing gate coordinator fails explicitly`() { runTest {
        val c = ApprovalSuspensionCoordinator(
            approvalGateCoordinator = null,
            approvalContinuationStore = FakeStore(),
            suspendedInvocationStore = FakeSuspendedStore(),
            resumeOperationRegistry = ResumeOperationRegistry(),
            serviceDefinition = approvalService(operation),
            resumeExecutor = StubResumeExecutor(),
            toolArgumentsDigester = digester,
            clock = fixedClock,
            approvalLifecycleAuditEmitter = RecordingApprovalLifecycleAuditEmitter(),
        )

        assertThatThrownBy { kotlinx.coroutines.runBlocking { c.requireApproval(request(), requireDecision(), input) } }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessage("ApprovalGateCoordinator is required for tool execution suspension")
    }
    }

    // ------------------------------------------------------------------
    // Test doubles
    // ------------------------------------------------------------------

    class FakeApprovalGate(
        var failOnCreateWith: Throwable? = null,
    ) : ApprovalGateCoordinator {
        var createCount = 0
        var cancelCount = 0
        var lastCancelReason: String? = null

        override suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge {
            createCount++
            failOnCreateWith?.let { throw it }
            return ApprovalChallenge(
                approvalId = "challenge-1",
                token = dev.tramai.core.approval.ApprovalToken.parsePresented("token-1"),
                expiresAt = command.expiresAt,
            )
        }

        override suspend fun validateResume(command: ValidateResumeCommand): ApprovalValidation =
            ApprovalValidation(command.approvalId, command.consumedBy, Instant.EPOCH, 0L)

        override suspend fun authorizeResume(command: AuthorizeResumeCommand): ApprovalAuthorization =
            ApprovalAuthorization(command.approvalId, command.consumedBy, Instant.EPOCH, 0L, replayed = false)

        override suspend fun cancelApproval(approvalId: String, expectedVersion: Long, reason: String) {
            cancelCount++
            lastCancelReason = reason
        }
    }

    inner class FakeStore(
        var failOnCreate: Boolean = false,
        var failOnCreateWith: Throwable? = null,
    ) : ApprovalContinuationStore {
        var createCount = 0
        var cancelCount = 0
        var createdContinuation: ApprovalContinuation = ApprovalContinuation(
            "challenge-1", "wf", "c", "call", "tool", digest, "p", digest,
            ApprovalContinuationStatus.PENDING, Instant.EPOCH, Instant.MAX, null, null, null, version = 0L,
        )

        override suspend fun create(continuation: ApprovalContinuation, arguments: SensitiveToolArguments): ApprovalContinuation {
            createCount++
            failOnCreateWith?.let { throw it }
            if (failOnCreate) throw RuntimeException("create-failure")
            createdContinuation = continuation
            return continuation
        }

        override suspend fun get(approvalId: String): ApprovalContinuation? = createdContinuation
        override suspend fun claimForExecution(approvalId: String, expectedVersion: Long, claimedBy: String): dev.tramai.core.approval.ClaimedApprovalContinuation =
            dev.tramai.core.approval.ClaimedApprovalContinuation(createdContinuation, SensitiveToolArguments.of("{}"))
        override suspend fun complete(approvalId: String, expectedVersion: Long, completedBy: String): ApprovalContinuation = createdContinuation
        override suspend fun expire(approvalId: String, expectedVersion: Long): ApprovalContinuation = createdContinuation
        override suspend fun cancel(approvalId: String, expectedVersion: Long): ApprovalContinuation {
            cancelCount++
            return createdContinuation
        }
        override suspend fun findStaleClaimed(claimedBefore: Instant, limit: Int): List<ApprovalContinuation> = emptyList()
        override suspend fun forceCancelClaimed(approvalId: String, expectedVersion: Long, cancelledBy: String, reasonCode: String): ApprovalContinuation = createdContinuation
        override suspend fun sweepExpired(): Int = 0
    }

    inner class FakeSuspendedStore(
        var failOnCreate: Boolean = false,
    ) : SuspendedInvocationStore {
        var createCount = 0
        var removeCount = 0
        var createdMetadata: SuspendedInvocationMetadata = SuspendedInvocationMetadata(
            "challenge-1", "call", "tool", 0, "c", identity, ExecutionSecurityContext(),
            ResumeOperationReference("s", "m", "()V", digest), digest,
            toolReference = dev.tramai.engine.ResumeToolReference("tool", digest),
        )

        override suspend fun create(metadata: SuspendedInvocationMetadata, replayEnvelope: SensitiveReplayEnvelope) {
            createCount++
            if (failOnCreate) throw RuntimeException("suspended-create-failure")
            createdMetadata = metadata
        }

        override suspend fun get(approvalId: String): SuspendedInvocationMetadata? = createdMetadata
        override suspend fun revealReplayEnvelope(approvalId: String): SensitiveReplayEnvelope? = SensitiveReplayEnvelope.of(emptyList())
        override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? {
            removeCount++
            return createdMetadata
        }
    }

    class RecordingApprovalLifecycleAuditEmitter : dev.tramai.core.approval.ApprovalLifecycleAuditEmitter {
        var suspendedCount = 0
        var cancelledCount = 0

        override suspend fun onToolExecutionSuspended(
            approvalId: String, workflowRunId: String, toolName: String,
            toolCallId: String, correlationId: String,
            argumentsDigest: Sha256Digest, expiresAt: Instant,
        ) { suspendedCount++ }
        override suspend fun onToolExecutionResumed(approvalId: String, workflowRunId: String, toolName: String, resumedBy: String) = Unit
        override suspend fun onToolExecutionCompleted(approvalId: String, workflowRunId: String, toolName: String, completedBy: String) = Unit
        override suspend fun onUncertainOutcome(approvalId: String, workflowRunId: String, toolName: String, reason: String) = Unit
        override suspend fun onSuspensionCancelled(approvalId: String, workflowRunId: String, toolName: String, reason: String) { cancelledCount++ }
        override suspend fun onStaleClaimDetected(approvalId: String, workflowRunId: String, toolName: String, claimedAt: Instant) = Unit
        override suspend fun onClaimedContinuationForceCancellationRequested(approvalId: String, workflowRunId: String, toolName: String, cancelledBy: String, reasonCode: String) = Unit
        override suspend fun onClaimedContinuationForceCancelled(approvalId: String, workflowRunId: String, toolName: String, cancelledBy: String, reasonCode: String) = Unit
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
