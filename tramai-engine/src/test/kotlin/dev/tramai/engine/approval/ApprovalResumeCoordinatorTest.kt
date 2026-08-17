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
import dev.tramai.core.exception.ApprovalNotFoundException
import dev.tramai.core.exception.ApprovalTokenRejectedException
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.NestedApprovalNotSupportedException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.engine.EngineEventObserver
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.PolicyEnforcementHelper
import dev.tramai.engine.ReplayEnvelopeDigestHelper
import dev.tramai.engine.ReplayEnvelopeFactory
import dev.tramai.engine.ResumeApprovalCommand
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.ResumeToolDeclarationDigestHelper
import dev.tramai.engine.ResumeToolReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.engine.ToolRegistry
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct contract tests for [ApprovalResumeCoordinator].
 *
 * The resume flow is a security-sensitive state machine. Ordering is the
 * contract: continuation inspect → metadata load → registry resolve →
 * bindings validate → tool resolve → token validate → policy evaluate →
 * token authorize → continuation claim → replay reveal → replay verify →
 * arguments verify → executor execute → continuation complete →
 * metadata remove → audit complete. These tests record the externally
 * observable collaborator sequence (in-process validation steps such as
 * registry resolve, binding validation, tool resolution and digest checks
 * are asserted by their dedicated component tests) and assert the EXACT
 * recorded order, not just final output.
 */
class ApprovalResumeCoordinatorTest {

    private val digest = Sha256Digest.of("sha256:" + "1".repeat(64))
    private val input = """{"x":2}"""
    private val inputDigest = Sha256ToolArgumentsDigester().digest(SensitiveToolArguments.of(input))
    private val token = ApprovalToken.parsePresented("token-1")
    private val toolName = "test_tool"
    private val toolCallId = "call-1"
    private val approvalId = "approval-1"
    private val resumedBy = "admin"
    private val identity = EngineExecutionIdentity("wf-1", "corr-1", digest, "policy-v1", "actor-1")
    private val command = ResumeApprovalCommand(approvalId, approvalExpectedVersion = 1, continuationExpectedVersion = 3, token, resumedBy)

    private val tool = FakeTool(toolName)
    private val toolReference = ResumeToolReference(toolName, ResumeToolDeclarationDigestHelper.compute(tool))
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
        Message(
            MessageRole.ASSISTANT,
            "",
            toolCalls = listOf(ToolCall(toolCallId, toolName, input)),
        ),
    )
    private val prepared = ReplayEnvelopeFactory.prepareForSuspension(opRef, messages, toolCallId, toolName, 0)

    private fun metadata() = SuspendedInvocationMetadata(
        approvalId = approvalId,
        toolCallId = toolCallId,
        toolName = toolName,
        toolCallIndex = 0,
        correlationId = "corr-1",
        identity = identity,
        securityContext = ExecutionSecurityContext(),
        operationReference = opRef,
        replayEnvelopeDigest = prepared.digest,
        conversationId = null,
        historySize = 0,
        tokenBudgetSnapshot = null,
        toolReference = toolReference,
        toolSecurity = null,
    )

    private fun continuation(status: ApprovalContinuationStatus = ApprovalContinuationStatus.PENDING) = ApprovalContinuation(
        approvalId = approvalId,
        workflowRunId = "wf-1",
        correlationId = "corr-1",
        toolCallId = toolCallId,
        toolName = toolName,
        argumentsDigest = inputDigest,
        policyVersion = "policy-v1",
        workflowDigest = digest,
        status = status,
        createdAt = Instant.EPOCH,
        approvalExpiresAt = Instant.MAX,
        claimedBy = null,
        claimedAt = null,
        completedAt = null,
        version = 3L,
    )

    private fun harness(
        policyDecision: PolicyDecision = PolicyDecision.Allow,
        events: MutableList<String> = mutableListOf(),
        claimFailsWith: Throwable? = null,
        executor: RecordingExecutor = RecordingExecutor(events),
    ): Triple<ApprovalResumeCoordinator, MutableList<String>, RecordingExecutor> {
        val store = RecordingContinuationStore(events, continuation(), claimFailsWith = claimFailsWith)
        val suspended = RecordingSuspendedStore(events, metadata(), prepared.envelope)
        val registry = ResumeOperationRegistry().also { it.register(service, operation, executor) }
        val gate = RecordingGate(events)
        val policy = PolicyEnforcementHelper(
            policyEngine = PolicyEngine { context -> events += "policy.evaluate"; policyDecision },
            migrationWarningGuard = AtomicBoolean(false),
        )
        val audit = RecordingAuditEmitter(events)
        val observer = RecordingObserver(events)
        val claimService = ContinuationClaimService(store)
        val authorizationService = ReplayAuthorizationService(gate, suspended, audit, policy, observer)
        val coordinator = ApprovalResumeCoordinator(
            approvalContinuationStore = store,
            suspendedInvocationStore = suspended,
            resumeOperationRegistry = registry,
            toolRegistry = ToolRegistry(mapOf(toolName to tool)),
            toolArgumentsDigester = Sha256ToolArgumentsDigester(),
            approvalLifecycleAuditEmitter = audit,
            engineEventObserver = observer,
            claimService = claimService,
            authorizationService = authorizationService,
        )
        return Triple(coordinator, events, executor)
    }

    @Test
    fun `happy resume preserves exact observable security sequence`() = runTest {
        val (coordinator, events, executor) = harness()

        val result = coordinator.resume(command)

        assertThat(result).isEqualTo("executed")
        assertThat(events).containsExactly(
            "continuation.inspect",
            "metadata.load",
            "continuation.inspect",
            "token.validate",
            "policy.evaluate",
            "token.authorize",
            "continuation.claim",
            "replay.reveal",
            "executor.execute",
            "continuation.complete",
            "metadata.remove",
            "audit.complete",
        )
        // mutation sensitivity: the executor must have been invoked exactly once, after authorize+claim
        assertThat(executor.calls).isEqualTo(1)
    }

    @Test
    fun `completed continuation is rejected before metadata load`() = runTest {
        val events = mutableListOf<String>()
        val store = RecordingContinuationStore(events, continuation(ApprovalContinuationStatus.COMPLETED))
        val suspended = RecordingSuspendedStore(events, metadata(), prepared.envelope)
        val registry = ResumeOperationRegistry().also { it.register(service, operation, RecordingExecutor(events)) }
        val gate = RecordingGate(events)
        val policy = PolicyEnforcementHelper(PolicyEngine { PolicyDecision.Allow }, AtomicBoolean(false))
        val audit = RecordingAuditEmitter(events)
        val observer = RecordingObserver(events)
        val coordinator = ApprovalResumeCoordinator(
            store, suspended, registry, ToolRegistry(mapOf(toolName to tool)),
            Sha256ToolArgumentsDigester(), audit, observer,
            ContinuationClaimService(store), ReplayAuthorizationService(gate, suspended, audit, policy, observer),
        )

        assertThatThrownBy { kotlinx.coroutines.runBlocking { coordinator.resume(command) } }
            .isInstanceOf(ApprovalTokenRejectedException::class.java)

        assertThat(events).containsExactly("continuation.inspect")
    }

    @Test
    fun `missing suspended metadata yields ApprovalNotFoundException`() = runTest {
        val events = mutableListOf<String>()
        val store = RecordingContinuationStore(events, continuation())
        val suspended = RecordingSuspendedStore(events, null, null)
        val registry = ResumeOperationRegistry().also { it.register(service, operation, RecordingExecutor(events)) }
        val gate = RecordingGate(events)
        val policy = PolicyEnforcementHelper(PolicyEngine { PolicyDecision.Allow }, AtomicBoolean(false))
        val audit = RecordingAuditEmitter(events)
        val observer = RecordingObserver(events)
        val coordinator = ApprovalResumeCoordinator(
            store, suspended, registry, ToolRegistry(mapOf(toolName to tool)),
            Sha256ToolArgumentsDigester(), audit, observer,
            ContinuationClaimService(store), ReplayAuthorizationService(gate, suspended, audit, policy, observer),
        )

        assertThatThrownBy { kotlinx.coroutines.runBlocking { coordinator.resume(command) } }
            .isInstanceOf(ApprovalNotFoundException::class.java)

        assertThat(events).containsExactly("continuation.inspect", "metadata.load")
    }

    @Test
    fun `deny policy cancels state and never claims or executes`() = runTest {
        val events = mutableListOf<String>()
        val (coordinator, _, executor) = harness(
            policyDecision = PolicyDecision.Deny(reason = "blocked", reasonCode = "WF_DENIED"),
            events = events,
        )

        assertThatThrownBy { kotlinx.coroutines.runBlocking { coordinator.resume(command) } }
            .isInstanceOf(PolicyViolationException::class.java)

        assertThat(events).containsSubsequence(
            "continuation.inspect", "metadata.load", "continuation.inspect",
            "token.validate", "policy.evaluate", "cancel-state", "metadata.remove", "audit.cancelled",
        )
        assertThat(events).noneMatch { it == "token.authorize" }
        assertThat(events).noneMatch { it == "continuation.claim" }
        assertThat(events).noneMatch { it == "executor.execute" }
        assertThat(executor.calls).isZero()
    }

    @Test
    fun `nested approval cancels state and never claims or executes`() = runTest {
        val events = mutableListOf<String>()
        val (coordinator, _, executor) = harness(
            policyDecision = PolicyDecision.RequireApproval(
                dev.tramai.core.policy.ApprovalRequirement(toolName, "", "testing", 60_000),
            ),
            events = events,
        )

        assertThatThrownBy { kotlinx.coroutines.runBlocking { coordinator.resume(command) } }
            .isInstanceOf(NestedApprovalNotSupportedException::class.java)

        assertThat(events).containsSubsequence(
            "token.validate", "policy.evaluate", "cancel-state", "metadata.remove", "audit.cancelled",
        )
        assertThat(events).noneMatch { it == "token.authorize" }
        assertThat(events).noneMatch { it == "continuation.claim" }
        assertThat(events).noneMatch { it == "executor.execute" }
    }

    @Test
    fun `claim failure propagates before executor or completion`() = runTest {
        val events = mutableListOf<String>()
        val (coordinator, _, executor) = harness(
            events = events,
            claimFailsWith = RuntimeException("claim-fence"),
        )

        assertThatThrownBy { kotlinx.coroutines.runBlocking { coordinator.resume(command) } }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessage("claim-fence")

        assertThat(events).containsSubsequence(
            "token.validate", "policy.evaluate", "token.authorize", "continuation.claim",
        )
        assertThat(events).noneMatch { it == "executor.execute" }
        assertThat(events).noneMatch { it == "continuation.complete" }
        assertThat(executor.calls).isZero()
    }

    @Test
    fun `missing replay envelope leaves continuation claimed and uncompleted`() = runTest {
        val events = mutableListOf<String>()
        val store = RecordingContinuationStore(events, continuation())
        val suspended = RecordingSuspendedStore(events, metadata(), null)
        val registry = ResumeOperationRegistry().also { it.register(service, operation, RecordingExecutor(events)) }
        val gate = RecordingGate(events)
        val policy = PolicyEnforcementHelper(PolicyEngine { PolicyDecision.Allow }, AtomicBoolean(false))
        val audit = RecordingAuditEmitter(events)
        val observer = RecordingObserver(events)
        val coordinator = ApprovalResumeCoordinator(
            store, suspended, registry, ToolRegistry(mapOf(toolName to tool)),
            Sha256ToolArgumentsDigester(), audit, observer,
            ContinuationClaimService(store), ReplayAuthorizationService(gate, suspended, audit, policy, observer),
        )

        assertThatThrownBy { kotlinx.coroutines.runBlocking { coordinator.resume(command) } }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessage("replay-envelope-not-found")

        assertThat(events).containsSubsequence("continuation.claim", "replay.reveal")
        assertThat(events).noneMatch { it == "continuation.complete" }
    }

    @Test
    fun `replay digest mismatch emits uncertain outcome and leaves continuation claimed`() = runTest {
        val events = mutableListOf<String>()
        val tamperedEnvelope = SensitiveReplayEnvelope.of(
            listOf(Message(MessageRole.USER, "tampered")),
        )
        val store = RecordingContinuationStore(events, continuation())
        val suspended = RecordingSuspendedStore(events, metadata(), tamperedEnvelope)
        val registry = ResumeOperationRegistry().also { it.register(service, operation, RecordingExecutor(events)) }
        val gate = RecordingGate(events)
        val policy = PolicyEnforcementHelper(PolicyEngine { PolicyDecision.Allow }, AtomicBoolean(false))
        val audit = RecordingAuditEmitter(events)
        val observer = RecordingObserver(events)
        val coordinator = ApprovalResumeCoordinator(
            store, suspended, registry, ToolRegistry(mapOf(toolName to tool)),
            Sha256ToolArgumentsDigester(), audit, observer,
            ContinuationClaimService(store), ReplayAuthorizationService(gate, suspended, audit, policy, observer),
        )

        assertThatThrownBy { kotlinx.coroutines.runBlocking { coordinator.resume(command) } }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessage("Replay envelope digest mismatch")

        assertThat(events).containsSubsequence("continuation.claim", "replay.reveal", "uncertain:replay-envelope-digest-mismatch")
        assertThat(events).noneMatch { it == "executor.execute" }
        assertThat(events).noneMatch { it == "continuation.complete" }
    }

    @Test
    fun `argument digest mismatch emits uncertain outcome and leaves continuation claimed`() = runTest {
        val events = mutableListOf<String>()
        val store = RecordingContinuationStore(
            events,
            continuation(),
            claimReturnsWith = ClaimedApprovalContinuation(
                continuation(),
                SensitiveToolArguments.of("""{"x":999}"""),
            ),
        )
        val suspended = RecordingSuspendedStore(events, metadata(), prepared.envelope)
        val registry = ResumeOperationRegistry().also { it.register(service, operation, RecordingExecutor(events)) }
        val gate = RecordingGate(events)
        val policy = PolicyEnforcementHelper(PolicyEngine { PolicyDecision.Allow }, AtomicBoolean(false))
        val audit = RecordingAuditEmitter(events)
        val observer = RecordingObserver(events)
        val coordinator = ApprovalResumeCoordinator(
            store, suspended, registry, ToolRegistry(mapOf(toolName to tool)),
            Sha256ToolArgumentsDigester(), audit, observer,
            ContinuationClaimService(store), ReplayAuthorizationService(gate, suspended, audit, policy, observer),
        )

        assertThatThrownBy { kotlinx.coroutines.runBlocking { coordinator.resume(command) } }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessage("Claimed continuation payload integrity mismatch")

        assertThat(events).containsSubsequence("continuation.claim", "replay.reveal", "uncertain:payload-integrity-mismatch")
        assertThat(events).noneMatch { it == "executor.execute" }
        assertThat(events).noneMatch { it == "continuation.complete" }
    }

    @Test
    fun `executor nested-approval failure emits uncertain outcome and stays claimed`() = runTest {
        val events = mutableListOf<String>()
        val executor = RecordingExecutor(events, throwWith = NestedApprovalNotSupportedException(approvalId, "nested"))
        val (coordinator, _, _) = harness(events = events, executor = executor)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { coordinator.resume(command) } }
            .isInstanceOf(NestedApprovalNotSupportedException::class.java)

        assertThat(events).containsSubsequence("executor.execute", "uncertain:nested-approval-not-supported")
        assertThat(events).noneMatch { it == "continuation.complete" }
        assertThat(events).noneMatch { it == "metadata.remove" }
    }

    @Test
    fun `executor generic failure emits uncertain outcome and stays claimed`() = runTest {
        val events = mutableListOf<String>()
        val executor = RecordingExecutor(events, throwWith = RuntimeException("tool-failed"))
        val (coordinator, _, _) = harness(events = events, executor = executor)

        assertThatThrownBy { kotlinx.coroutines.runBlocking { coordinator.resume(command) } }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessage("tool-failed")

        assertThat(events).containsSubsequence("executor.execute", "uncertain:resume-failed: RuntimeException")
        assertThat(events).noneMatch { it == "continuation.complete" }
        assertThat(events).noneMatch { it == "metadata.remove" }
    }

    @Test
    fun `cancellation after claim during replay reveal stays claimed and is never converted to uncertain outcome`() = runTest {
        val events = mutableListOf<String>()
        val store = RecordingContinuationStore(events, continuation())
        val suspended = RecordingSuspendedStore(events, metadata(), null)
        val registry = ResumeOperationRegistry().also { it.register(service, operation, RecordingExecutor(events)) }
        val gate = RecordingGate(events)
        val policy = PolicyEnforcementHelper(PolicyEngine { PolicyDecision.Allow }, AtomicBoolean(false))
        val audit = RecordingAuditEmitter(events)
        val observer = RecordingObserver(events)
        val coordinator = ApprovalResumeCoordinator(
            store, suspended, registry, ToolRegistry(mapOf(toolName to tool)),
            Sha256ToolArgumentsDigester(), audit, observer,
            ContinuationClaimService(store), ReplayAuthorizationService(gate, suspended, audit, policy, observer),
        )

        // revealReplayEnvelope returns null → ConfigurationException; use a CE-throwing reveal instead
        val cancelled = CancellationException("cancel")
        val suspendedCe = object : SuspendedInvocationStore by suspended {
            override suspend fun revealReplayEnvelope(approvalId: String): SensitiveReplayEnvelope? = throw cancelled
        }
        val coordinatorCe = ApprovalResumeCoordinator(
            store, suspendedCe, registry, ToolRegistry(mapOf(toolName to tool)),
            Sha256ToolArgumentsDigester(), audit, observer,
            ContinuationClaimService(store), ReplayAuthorizationService(gate, suspendedCe, audit, policy, observer),
        )

        assertThatThrownBy { kotlinx.coroutines.runBlocking { coordinatorCe.resume(command) } }
            .isSameAs(cancelled)

        // cancellation is not a business failure: no uncertain-outcome audit, no completion
        assertThat(events).noneMatch { it.startsWith("uncertain:") }
        assertThat(events).noneMatch { it == "continuation.complete" }
        assertThat(events).noneMatch { it == "metadata.remove" }
        assertThat(events).noneMatch { it == "audit.complete" }
    }

    @Test
    fun `resume requires continuation store before loading external state`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = ApprovalResumeCoordinator(
            approvalContinuationStore = null,
            suspendedInvocationStore = RecordingSuspendedStore(events, metadata(), prepared.envelope),
            resumeOperationRegistry = ResumeOperationRegistry(),
            toolRegistry = ToolRegistry(),
            toolArgumentsDigester = null,
            approvalLifecycleAuditEmitter = RecordingAuditEmitter(events),
            engineEventObserver = RecordingObserver(events),
            claimService = ContinuationClaimService(null),
            authorizationService = ReplayAuthorizationService(
                null, RecordingSuspendedStore(events, metadata(), prepared.envelope),
                RecordingAuditEmitter(events), PolicyEnforcementHelper(PolicyEngine { PolicyDecision.Allow }, AtomicBoolean(false)),
                RecordingObserver(events),
            ),
        )

        assertThatThrownBy { kotlinx.coroutines.runBlocking { coordinator.resume(command) } }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessage("ApprovalContinuationStore is required for resume")
    }

    // ------------------------------------------------------------------
    // Test doubles — every collaborator records into the shared events log
    // ------------------------------------------------------------------

    inner class RecordingContinuationStore(
        private val events: MutableList<String>,
        private val value: ApprovalContinuation,
        private val claimReturnsWith: ClaimedApprovalContinuation? = null,
        private val claimFailsWith: Throwable? = null,
    ) : dev.tramai.core.approval.ApprovalContinuationStore {
        override suspend fun create(continuation: ApprovalContinuation, arguments: SensitiveToolArguments): ApprovalContinuation = continuation
        override suspend fun get(approvalId: String): ApprovalContinuation? {
            events += "continuation.inspect"
            return value
        }
        override suspend fun claimForExecution(approvalId: String, expectedVersion: Long, claimedBy: String): ClaimedApprovalContinuation {
            events += "continuation.claim"
            claimFailsWith?.let { throw it }
            return claimReturnsWith ?: ClaimedApprovalContinuation(value, SensitiveToolArguments.of(input))
        }
        override suspend fun complete(approvalId: String, expectedVersion: Long, completedBy: String): ApprovalContinuation {
            events += "continuation.complete"
            return value.copy(status = ApprovalContinuationStatus.COMPLETED, version = expectedVersion)
        }
        override suspend fun expire(approvalId: String, expectedVersion: Long): ApprovalContinuation = value
        override suspend fun cancel(approvalId: String, expectedVersion: Long): ApprovalContinuation {
            events += "cancel-state"
            return value.copy(status = ApprovalContinuationStatus.CANCELLED)
        }
        override suspend fun findStaleClaimed(claimedBefore: Instant, limit: Int): List<ApprovalContinuation> = emptyList()
        override suspend fun forceCancelClaimed(approvalId: String, expectedVersion: Long, cancelledBy: String, reasonCode: String): ApprovalContinuation = value
        override suspend fun sweepExpired(): Int = 0
    }

    class RecordingSuspendedStore(
        private val events: MutableList<String>,
        private val value: SuspendedInvocationMetadata?,
        private val envelope: SensitiveReplayEnvelope?,
    ) : SuspendedInvocationStore {
        override suspend fun create(metadata: SuspendedInvocationMetadata, replayEnvelope: SensitiveReplayEnvelope) = Unit
        override suspend fun get(approvalId: String): SuspendedInvocationMetadata? {
            events += "metadata.load"
            return value
        }
        override suspend fun revealReplayEnvelope(approvalId: String): SensitiveReplayEnvelope? {
            events += "replay.reveal"
            return envelope
        }
        override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? {
            events += "metadata.remove"
            return value
        }
    }

    inner class RecordingGate(
        private val events: MutableList<String>,
    ) : ApprovalGateCoordinator {
        var validateCount = 0
        var authorizeCount = 0

        override suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge =
            ApprovalChallenge("approval-1", token, Instant.MAX)

        override suspend fun validateResume(command: ValidateResumeCommand): ApprovalValidation {
            events += "token.validate"
            validateCount++
            return ApprovalValidation(command.approvalId, command.consumedBy, Instant.EPOCH, 0L)
        }

        override suspend fun authorizeResume(command: AuthorizeResumeCommand): ApprovalAuthorization {
            events += "token.authorize"
            authorizeCount++
            return ApprovalAuthorization(command.approvalId, command.consumedBy, Instant.EPOCH, 0L, replayed = false)
        }

        override suspend fun cancelApproval(approvalId: String, expectedVersion: Long, reason: String) = Unit
    }

    class RecordingAuditEmitter(
        private val events: MutableList<String>,
    ) : dev.tramai.core.approval.ApprovalLifecycleAuditEmitter {
        override suspend fun onToolExecutionSuspended(approvalId: String, workflowRunId: String, toolName: String, toolCallId: String, correlationId: String, argumentsDigest: Sha256Digest, expiresAt: Instant) = Unit
        override suspend fun onToolExecutionResumed(approvalId: String, workflowRunId: String, toolName: String, resumedBy: String) = Unit
        override suspend fun onToolExecutionCompleted(approvalId: String, workflowRunId: String, toolName: String, completedBy: String) { events += "audit.complete" }
        override suspend fun onUncertainOutcome(approvalId: String, workflowRunId: String, toolName: String, reason: String) { events += "uncertain:$reason" }
        override suspend fun onSuspensionCancelled(approvalId: String, workflowRunId: String, toolName: String, reason: String) { events += "audit.cancelled" }
        override suspend fun onStaleClaimDetected(approvalId: String, workflowRunId: String, toolName: String, claimedAt: Instant) = Unit
        override suspend fun onClaimedContinuationForceCancellationRequested(approvalId: String, workflowRunId: String, toolName: String, cancelledBy: String, reasonCode: String) = Unit
        override suspend fun onClaimedContinuationForceCancelled(approvalId: String, workflowRunId: String, toolName: String, cancelledBy: String, reasonCode: String) = Unit
    }

    class RecordingObserver(
        private val events: MutableList<String>,
    ) : EngineEventObserver {
        override fun onEngineEvent(name: String, attributes: Map<String, Any?>) {
            events += "observer:$name"
        }
    }

    private class RecordingExecutor(
        private val events: MutableList<String>,
        private val throwWith: Throwable? = null,
    ) : ClaimedResumeExecutor {
        var calls = 0
        override suspend fun execute(request: ClaimedResumeExecutionRequest): Any? {
            events += "executor.execute"
            calls++
            throwWith?.let { throw it }
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
