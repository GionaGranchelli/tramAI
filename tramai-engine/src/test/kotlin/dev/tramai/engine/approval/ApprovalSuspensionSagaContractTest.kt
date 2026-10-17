@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.engine.approval

import dev.tramai.core.approval.ApprovalAuthorization
import dev.tramai.core.approval.ApprovalChallenge
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.ApprovalValidation
import dev.tramai.core.approval.AuthorizeResumeCommand
import dev.tramai.core.approval.ClaimedApprovalContinuation
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.ToolArgumentsDigester
import dev.tramai.core.approval.ValidateResumeCommand
import dev.tramai.core.exception.ApprovalContinuationConflictException
import dev.tramai.core.exception.ApprovalContinuationNotFoundException
import dev.tramai.core.exception.ApprovalSuspendedException
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.GovernedRunScope
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.core.policy.ApprovalRequirement
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.GovernedSuspendedInvocation
import dev.tramai.engine.GovernedSuspendedInvocationStore
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.engine.tool.ToolExecutionRequest
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.resumeWithException

/**
 * Contract tests for the suspension saga in [ApprovalSuspensionCoordinator].
 *
 * The saga has three suspend layers, and a failure can arrive on the resumed frame of any of them:
 *
 * ```
 * suspendToolExecution          the saga itself
 *   └── compensateSuspension    ordinary-failure recovery, in reverse order
 *         └── compensateStep    per-action guard: ordinary failures are swallowed, cancellation is not
 *               └── the generated `compensateSuspension$2$*` lambdas
 *                     └── suspendedInvocationStore.remove / continuationStore.cancel / gate.cancelApproval
 * ```
 *
 * Every collaborator below genuinely suspends (`delay`) before it either answers or fails, so the generated
 * state-machine sentinel returns and case-entry rethrows actually execute. A synchronous double cannot
 * reach them: the call returns normally, the sentinel branch is never taken, and the rethrow that must
 * unwrap a failure delivered on the resumed frame never runs.
 *
 * The doubles stay small but they do not permit states the real SPI forbids: the continuation store refuses a
 * stale expectation and a non-PENDING status, and cancellation of a continuation that was never persisted
 * fails the way the production store fails.
 */
class ApprovalSuspensionSagaContractTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-06-07T12:00:00Z"), ZoneId.of("UTC"))
    private val digester: ToolArgumentsDigester = Sha256ToolArgumentsDigester()
    private val digest = Sha256Digest.of("sha256:" + "1".repeat(64))
    private val toolName = "test_tool"
    private val toolCallId = "call-1"
    private val input = """{"x":2}"""
    private val workflowRunId = "wf-1"
    private val correlationId = "corr-1"
    private val approvalId = "challenge-1"
    private val identity = EngineExecutionIdentity(workflowRunId, correlationId, digest, "policy-v1", "actor-1")

    private val tool: ResolvedTool = SagaFakeTool(toolName)
    private val toolCall = ToolCall(toolCallId, toolName, input)
    private val operation = approvalOperation(ApprovalRegistryService::class.java.getMethod("first"))

    /** The single ordered log every collaborator appends to: this is how the saga's order is proved. */
    private val events = mutableListOf<String>()

    private val gate = SagaGate(events)
    private val continuations = SagaContinuationStore(events)
    private val suspensions = SagaGovernedSuspendedStore(events)
    private val plainSuspensions = SagaSuspendedStore(events)
    private val audit = SagaAuditEmitter(events)

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
        messages =
            listOf(
                Message(MessageRole.USER, "hi"),
                Message(MessageRole.ASSISTANT, "", toolCalls = listOf(toolCall)),
            ),
        toolCallIndex = 0,
        resumingApproval = resumingApproval,
        parentApprovalId = parentApprovalId,
        allowRenewedApprovedBindingDuringResume = allowRenewed,
    )

    private fun requireDecision(timeoutMillis: Long = 60_000) =
        PolicyDecision.RequireApproval(
            ApprovalRequirement(
                toolName = toolName,
                argumentsDigest = digester.digest(SensitiveToolArguments.of(input)).value,
                reason = "testing",
                timeoutMillis = timeoutMillis,
            ),
        )

    private fun coordinator(
        gate: ApprovalGateCoordinator = this.gate,
        continuations: ApprovalContinuationStore? = this.continuations,
        suspensions: SuspendedInvocationStore = this.suspensions,
        audit: ApprovalLifecycleAuditEmitter = this.audit,
    ) = ApprovalSuspensionCoordinator(
        approvalGateCoordinator = gate,
        approvalContinuationStore = continuations,
        suspendedInvocationStore = suspensions,
        resumeOperationRegistry = ResumeOperationRegistry(),
        serviceDefinition = approvalService(operation),
        resumeExecutor = StubSagaExecutor(),
        toolArgumentsDigester = digester,
        clock = fixedClock,
        approvalLifecycleAuditEmitter = audit,
    )

    /** Bounded, deterministic driver: the saga is driven inside the governed scope [scope] installs, if any. */
    private fun <T> bounded(
        scope: GovernedRunIdentity? = null,
        block: suspend () -> T,
    ): T =
        runBlocking {
            withTimeout(2_000) {
                if (scope == null) block() else withContext(GovernedRunScope(scope)) { block() }
            }
        }

    /**
     * kotlinx's stack-trace recovery hands the caller a *copy* of the throwable, so instance identity is not the
     * contract. What is: the exact failure - by type and by its unique message - reaches the caller.
     */
    private fun assertReachesCaller(
        thrown: Throwable,
        expected: Throwable,
    ) {
        assertThat(thrown::class).isEqualTo(expected::class)
        assertThat(thrown.message).isEqualTo(expected.message)
    }

    /** Drives one saga to its outcome, which the suspension contract requires to be a throw. */
    private suspend fun drive(coordinator: ApprovalSuspensionCoordinator): Throwable {
        try {
            coordinator.requireApproval(request(), requireDecision(), input)
        } catch (thrown: Throwable) {
            return thrown
        }
        throw AssertionError("the suspension saga was expected to terminate by throwing")
    }

    private fun suspensionOf(
        coordinator: ApprovalSuspensionCoordinator,
        scope: GovernedRunIdentity? = null,
    ): Throwable = bounded(scope) { drive(coordinator) }

    // ------------------------------------------------------------------
    // The intended suspension path
    // ------------------------------------------------------------------

    @Test
    fun `the saga creates challenge continuation suspended invocation and audit then throws the suspension`() {
        val thrown = suspensionOf(coordinator(), scope = governedIdentity(workflowRunId))

        assertThat(thrown).isInstanceOf(ApprovalSuspendedException::class.java)
        val suspended = thrown as ApprovalSuspendedException
        assertThat(suspended.approvalId).isEqualTo(approvalId)
        assertThat(suspended.workflowRunId).isEqualTo(workflowRunId)
        assertThat(suspended.continuationVersion).isEqualTo(0L)

        // The saga's order, proved rather than inferred: nothing happens before the challenge exists.
        assertThat(events)
            .containsExactly("gate.create", "store.create", "suspended.createGoverned", "audit.suspended")

        assertThat(gate.created).isEqualTo(1)
        assertThat(continuations.created?.status).isEqualTo(ApprovalContinuationStatus.PENDING)
        assertThat(continuations.created?.version).isEqualTo(0L)
        assertThat(audit.suspended).isEqualTo(1)
        assertThat(suspensions.governedCreates).isEqualTo(1)
        assertThat(suspensions.plainCreates).isZero()

        // Suspension is the intended outcome, not a failure: it never compensates.
        assertThat(gate.cancelled).isZero()
        assertThat(continuations.cancelled).isZero()
        assertThat(suspensions.removes).isZero()
    }

    @Test
    fun `an ungoverned saga persists through the plain store operation`() {
        // The same store instance implements both SPI halves, so the branch taken is directly observable.
        val thrown = suspensionOf(coordinator())

        assertThat(thrown).isInstanceOf(ApprovalSuspendedException::class.java)
        assertThat(events).containsExactly("gate.create", "store.create", "suspended.create", "audit.suspended")
        assertThat(suspensions.plainCreates).isEqualTo(1)
        assertThat(suspensions.governedCreates).isZero()
    }

    // ------------------------------------------------------------------
    // Compensation gradient: compensation must reflect what was created
    // ------------------------------------------------------------------

    @Test
    fun `a refusal before the challenge exists compensates nothing`() {
        val failure = IllegalStateException("gate-refused")
        val thrown = suspensionOf(coordinator(gate = RefusingGate(failure)))

        assertReachesCaller(thrown, failure)
        assertThat(events).isEmpty()
    }

    @Test
    fun `a failure after the challenge but before the continuation is compensated in reverse order`() {
        val failure = IllegalStateException("continuation-create-failed")
        val thrown =
            suspensionOf(
                coordinator(continuations = FailingContinuationStore(continuations, failOnCreate = failure)),
            )

        assertReachesCaller(thrown, failure)
        assertThat(events).containsExactly("gate.create", "suspended.remove", "gate.cancel")
        assertThat(gate.cancelled).isEqualTo(1)
        // Nothing persisted, so nothing was cancelled: the store refuses the impossible transition and
        // compensation swallows that refusal rather than replacing the initiating failure.
        assertThat(continuations.created).isNull()
        assertThat(continuations.cancelled).isZero()
    }

    @Test
    fun `a failure on the challenge creation's resumed frame reaches the caller and compensates nothing`() {
        val failure = IllegalStateException("challenge-create-failed")
        val thrown = suspensionOf(coordinator(gate = FailingGate(gate, failOnCreate = failure)))

        // The gate suspended and then failed: the failure must arrive unwrapped, and because the challenge never
        // existed there is nothing to compensate for.
        assertReachesCaller(thrown, failure)
        assertThat(events).isEmpty()
        assertThat(gate.cancelled).isZero()
        assertThat(continuations.created).isNull()
    }

    @Test
    fun `a failure after the continuation is persisted cancels it`() {
        val failure = IllegalStateException("suspended-create-failed")
        val thrown =
            suspensionOf(
                coordinator(suspensions = FailingSuspendedStore(plainSuspensions, failOnCreate = failure)),
            )

        assertReachesCaller(thrown, failure)
        assertThat(events)
            .containsExactly(
                "gate.create",
                "store.create",
                "suspended.remove",
                "store.cancel",
                "gate.cancel",
            )
        assertThat(continuations.cancelled).isEqualTo(1)
        assertThat(continuations.state?.status).isEqualTo(ApprovalContinuationStatus.CANCELLED)
        assertThat(continuations.state?.version).isEqualTo(1L)
        assertThat(gate.cancelled).isEqualTo(1)
    }

    @Test
    fun `a failure during the suspension audit compensates everything that was created`() {
        val failure = IllegalStateException("audit-failed")
        val thrown =
            suspensionOf(
                coordinator(audit = FailingAuditEmitter(audit, failOnSuspended = failure)),
            )

        assertReachesCaller(thrown, failure)
        assertThat(events)
            .containsExactly(
                "gate.create",
                "store.create",
                "suspended.create",
                "suspended.remove",
                "store.cancel",
                "gate.cancel",
            )
        assertThat(continuations.state?.status).isEqualTo(ApprovalContinuationStatus.CANCELLED)
    }

    @Test
    fun `an ordinary failure inside compensation is swallowed and compensation continues`() {
        val failure = IllegalStateException("audit-failed")
        val compensationFailure = IllegalStateException("remove-failed")
        val failures = FailingSuspendedStore(plainSuspensions, failOnRemove = compensationFailure)
        val thrown =
            suspensionOf(
                coordinator(
                    suspensions = failures,
                    audit = FailingAuditEmitter(audit, failOnSuspended = failure),
                ),
            )

        // The initiating failure stays primary; the compensation failure does not replace it.
        assertReachesCaller(thrown, failure)
        assertThat(events)
            .containsExactly("gate.create", "store.create", "suspended.create", "store.cancel", "gate.cancel")
        assertThat(failures.removeAttempts).isEqualTo(1)
        assertThat(continuations.cancelled).isEqualTo(1)
        assertThat(gate.cancelled).isEqualTo(1)
    }

    @Test
    fun `a failure on the continuation cancellation still runs the approval cancellation`() {
        val failure = IllegalStateException("audit-failed")
        val stepFailure = IllegalStateException("continuation-cancel-failed")
        val thrown =
            suspensionOf(
                coordinator(
                    continuations = FailingContinuationStore(continuations, failOnCancel = stepFailure),
                    audit = FailingAuditEmitter(audit, failOnSuspended = failure),
                ),
            )

        // The middle compensation step failed; the last one still ran, and the initiating failure stayed primary.
        assertReachesCaller(thrown, failure)
        assertThat(events)
            .containsExactly("gate.create", "store.create", "suspended.create", "suspended.remove", "gate.cancel")
        assertThat(continuations.cancelled).isZero()
        assertThat(gate.cancelled).isEqualTo(1)
    }

    @Test
    fun `a failure on the approval cancellation is swallowed by the last compensation step`() {
        val failure = IllegalStateException("audit-failed")
        val stepFailure = IllegalStateException("approval-cancel-failed")
        val thrown =
            suspensionOf(
                coordinator(
                    gate = FailingGate(gate, failOnCancel = stepFailure),
                    audit = FailingAuditEmitter(audit, failOnSuspended = failure),
                ),
            )

        assertReachesCaller(thrown, failure)
        assertThat(events)
            .containsExactly(
                "gate.create",
                "store.create",
                "suspended.create",
                "suspended.remove",
                "store.cancel",
            )
        assertThat(continuations.cancelled).isEqualTo(1)
        assertThat(gate.cancelled).isZero()
    }

    // ------------------------------------------------------------------
    // Cancellation is not an ordinary failure
    // ------------------------------------------------------------------

    @Test
    fun `a cancellation as the initiating failure propagates without compensation`() {
        val cancellation = CancellationException("cancelled-by-caller")
        val thrown = suspensionOf(coordinator(gate = RefusingGate(cancellation)))

        assertReachesCaller(thrown, cancellation)
        assertThat(events).isEmpty()
    }

    @Test
    fun `a cancellation delivered to a compensation action propagates and stops later steps`() {
        val failure = IllegalStateException("audit-failed")
        val cancellation = CancellationException("cancelled-during-compensation")
        val failures = FailingSuspendedStore(plainSuspensions, failOnRemove = cancellation)
        val thrown =
            suspensionOf(
                coordinator(
                    suspensions = failures,
                    audit = FailingAuditEmitter(audit, failOnSuspended = failure),
                ),
            )

        // compensateStep rethrows cancellation instead of swallowing it, so the later steps never run.
        assertReachesCaller(thrown, cancellation)
        assertThat(events).containsExactly("gate.create", "store.create", "suspended.create")
        assertThat(failures.removeAttempts).isEqualTo(1)
        assertThat(continuations.cancelled).isZero()
        assertThat(gate.cancelled).isZero()
    }

    // ------------------------------------------------------------------
    // Resumed-frame routing of the later compensation actions
    //
    // The g1G4d work cancelled the FIRST compensation action. These two drive the SECOND and the THIRD:
    // the action genuinely returns COROUTINE_SUSPENDED, the test resumes it later with its own
    // CancellationException, and the assertion is that the caller still observes that cancellation
    // instead of compensation swallowing it.
    // ------------------------------------------------------------------

    @Test
    fun `a cancellation delivered to the second compensation action after suspension reaches the caller`(): Unit =
        bounded {
            val initiating = IllegalStateException("audit-failed")
            val cancellation = CancellationException("second-action-cancelled")
            val second = PausingContinuationStore(continuations)
            val saga =
                coordinator(
                    continuations = second,
                    audit = FailingAuditEmitter(audit, failOnSuspended = initiating),
                )
            var thrown: Throwable? = null

            coroutineScope {
                val job = launch { thrown = drive(saga) }

                second.suspended.await()
                assertThat(second.suspendCount).isEqualTo(1)
                assertThat(events)
                    .containsExactly("gate.create", "store.create", "suspended.create", "suspended.remove")
                second.resumeWith(cancellation)
                job.join()
            }

            assertThat(thrown).isInstanceOf(CancellationException::class.java)
            // The test supplies this exact instance; kotlinx's stack-trace recovery hands the caller a copy of it,
            // so what is asserted is the same cancellation by type and by its unique message (see assertReachesCaller).
            assertReachesCaller(thrown!!, cancellation)
            assertThat(gate.cancelled).isZero()
        }

    @Test
    fun `a cancellation delivered to the third compensation action after suspension reaches the caller`(): Unit =
        bounded {
            val initiating = IllegalStateException("audit-failed")
            val cancellation = CancellationException("third-action-cancelled")
            val third = PausingGate(gate)
            val saga =
                coordinator(
                    gate = third,
                    audit = FailingAuditEmitter(audit, failOnSuspended = initiating),
                )
            var thrown: Throwable? = null

            coroutineScope {
                val job = launch { thrown = drive(saga) }

                third.suspended.await()
                assertThat(third.suspendCount).isEqualTo(1)
                assertThat(events)
                    .containsExactly(
                        "gate.create",
                        "store.create",
                        "suspended.create",
                        "suspended.remove",
                        "store.cancel",
                    )
                third.resumeWith(cancellation)
                job.join()
            }

            assertThat(thrown).isInstanceOf(CancellationException::class.java)
            // The test supplies this exact instance; kotlinx's stack-trace recovery hands the caller a copy of it,
            // so what is asserted is the same cancellation by type and by its unique message (see assertReachesCaller).
            assertReachesCaller(thrown!!, cancellation)
            assertThat(gate.cancelled).isZero()
            assertThat(continuations.cancelled).isEqualTo(1)
        }

    // Suspension inside the compensation layers
    // ------------------------------------------------------------------

    @Test
    fun `every compensation action genuinely suspends before it runs`() {
        val failure = IllegalStateException("audit-failed")
        val thrown =
            suspensionOf(coordinator(audit = FailingAuditEmitter(audit, failOnSuspended = failure)))

        assertReachesCaller(thrown, failure)
        assertThat(events).hasSize(6)
        // Each count is only reachable after the call suspended and resumed: a synchronous double
        // cannot produce a single one of them.
        assertThat(suspensions.removeResumes).isEqualTo(1)
        assertThat(continuations.cancelResumes).isEqualTo(1)
        assertThat(gate.cancelResumes).isEqualTo(1)
    }

    @Test
    fun `a governed saga requires a store that can persist the governed record`() {
        // A governed run scope with a store that cannot persist the canonical identity: the saga refuses
        // before creating anything, so there is nothing to compensate.
        val thrown =
            suspensionOf(
                coordinator(suspensions = SagaSuspendedStore(events)),
                scope = governedIdentity(workflowRunId),
            )

        assertThat(thrown).isInstanceOf(ConfigurationException::class.java)
        assertThat(events).isEmpty()
    }
}

// ------------------------------------------------------------------
// Semantic collaborators
//
// Each one suspends before it answers (that is the obligation under test), records what it was asked to do
// into the shared ordered log, and refuses states the production SPI forbids.
// ------------------------------------------------------------------

/** The approval gate: creates the challenge the saga is built around and cancels it during compensation. */
private class SagaGate(
    private val events: MutableList<String>,
    private val approvalId: String = "challenge-1",
) : ApprovalGateCoordinator {
    var created = 0
        private set
    var cancelled = 0
        private set
    var cancelResumes = 0
        private set
    var lastCancelReason: String? = null
        private set

    override suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge {
        delay(1)
        created++
        events += "gate.create"
        val token = ApprovalToken.parsePresented("token-1")
        return ApprovalChallenge(approvalId, token, Instant.parse("2026-06-07T12:10:00Z"))
    }

    override suspend fun cancelApproval(
        approvalId: String,
        expectedVersion: Long,
        reason: String,
    ) {
        delay(1)
        cancelResumes++
        cancelled++
        lastCancelReason = reason
        events += "gate.cancel"
    }

    override suspend fun validateResume(command: ValidateResumeCommand): ApprovalValidation = error("unused")

    override suspend fun authorizeResume(command: AuthorizeResumeCommand): ApprovalAuthorization = error("unused")
}

/** A gate that refuses before doing anything: the saga has no challenge to compensate for. */
private class RefusingGate(
    private val failure: Throwable,
) : ApprovalGateCoordinator {
    override suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge = throw failure

    override suspend fun cancelApproval(
        approvalId: String,
        expectedVersion: Long,
        reason: String,
    ) = Unit

    override suspend fun validateResume(command: ValidateResumeCommand): ApprovalValidation = error("unused")

    override suspend fun authorizeResume(command: AuthorizeResumeCommand): ApprovalAuthorization = error("unused")
}

/**
 * The continuation store: PENDING v0 on create, CANCELLED v1 on cancel. It refuses a stale expectation and a
 * non-PENDING status the way the production store does, so the saga cannot pass against a state that cannot
 * exist.
 */
private class SagaContinuationStore(
    private val events: MutableList<String>,
) : ApprovalContinuationStore {
    var created: ApprovalContinuation? = null
        private set
    var state: ApprovalContinuation? = null
        private set
    var cancelled = 0
        private set
    var cancelResumes = 0
        private set

    override suspend fun create(
        continuation: ApprovalContinuation,
        arguments: SensitiveToolArguments,
    ): ApprovalContinuation {
        delay(1)
        created = continuation
        state = continuation
        events += "store.create"
        return continuation
    }

    override suspend fun get(approvalId: String): ApprovalContinuation? = state

    override suspend fun claimForExecution(
        approvalId: String,
        expectedVersion: Long,
        claimedBy: String,
    ): ClaimedApprovalContinuation = error("not part of the suspension saga")

    override suspend fun complete(
        approvalId: String,
        expectedVersion: Long,
        completedBy: String,
    ): ApprovalContinuation = error("not part of the suspension saga")

    override suspend fun expire(
        approvalId: String,
        expectedVersion: Long,
    ): ApprovalContinuation = error("not part of the suspension saga")

    override suspend fun cancel(
        approvalId: String,
        expectedVersion: Long,
    ): ApprovalContinuation {
        delay(1)
        cancelResumes++
        val current = state ?: throw ApprovalContinuationNotFoundException(approvalId)
        if (current.version != expectedVersion || current.status != ApprovalContinuationStatus.PENDING) {
            throw ApprovalContinuationConflictException(approvalId)
        }
        cancelled++
        events += "store.cancel"
        state = current.copy(status = ApprovalContinuationStatus.CANCELLED, version = current.version + 1)
        return state!!
    }

    override suspend fun findStaleClaimed(
        claimedBefore: Instant,
        limit: Int,
    ): List<ApprovalContinuation> = emptyList()

    override suspend fun forceCancelClaimed(
        approvalId: String,
        expectedVersion: Long,
        cancelledBy: String,
        reasonCode: String,
    ): ApprovalContinuation = error("not part of the suspension saga")

    override suspend fun sweepExpired(): Int = 0
}

/** The suspended-invocation store, without the governed half. */
private class SagaSuspendedStore(
    private val events: MutableList<String>,
) : SuspendedInvocationStore {
    var creates = 0
        private set
    var createResumes = 0
        private set
    var removes = 0
        private set
    var removeResumes = 0
        private set
    var persisted: SuspendedInvocationMetadata? = null
        private set

    override suspend fun create(
        metadata: SuspendedInvocationMetadata,
        replayEnvelope: SensitiveReplayEnvelope,
    ) {
        delay(1)
        createResumes++
        creates++
        persisted = metadata
        events += "suspended.create"
    }

    override suspend fun get(approvalId: String) = persisted?.takeIf { it.approvalId == approvalId }

    override suspend fun revealReplayEnvelope(approvalId: String): SensitiveReplayEnvelope? = null

    override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? {
        delay(1)
        removeResumes++
        removes++
        events += "suspended.remove"
        val removed = persisted?.takeIf { it.approvalId == approvalId }
        persisted = null
        return removed
    }
}

/**
 * A store implementing both halves of the SPI. Which operation the saga uses is therefore directly observable,
 * which is exactly what the governed-vs-ungoverned branch is about.
 */
private class SagaGovernedSuspendedStore(
    private val events: MutableList<String>,
) : GovernedSuspendedInvocationStore {
    var governedCreates = 0
        private set
    var governedCreateResumes = 0
        private set
    var plainCreates = 0
        private set
    var plainCreateResumes = 0
        private set
    var removes = 0
        private set
    var removeResumes = 0
        private set
    var persistedGoverned: GovernedSuspendedInvocation? = null
        private set

    override suspend fun createGoverned(
        suspended: GovernedSuspendedInvocation,
        replayEnvelope: SensitiveReplayEnvelope,
    ) {
        delay(1)
        governedCreateResumes++
        governedCreates++
        persistedGoverned = suspended
        events += "suspended.createGoverned"
    }

    override suspend fun create(
        metadata: SuspendedInvocationMetadata,
        replayEnvelope: SensitiveReplayEnvelope,
    ) {
        delay(1)
        plainCreateResumes++
        plainCreates++
        events += "suspended.create"
    }

    override suspend fun get(approvalId: String): SuspendedInvocationMetadata? = null

    override suspend fun revealReplayEnvelope(approvalId: String): SensitiveReplayEnvelope? = null

    override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? {
        delay(1)
        removeResumes++
        removes++
        events += "suspended.remove"
        return null
    }

    override suspend fun governedRunIdentity(approvalId: String): GovernedRunIdentity? =
        persistedGoverned?.takeIf { it.metadata.approvalId == approvalId }?.runIdentity
}

/** The audit emitter: the saga's last forward step before it throws the suspension. */
private class SagaAuditEmitter(
    private val events: MutableList<String>,
) : ApprovalLifecycleAuditEmitter by NoOpApprovalLifecycleAuditEmitter {
    var suspended = 0
        private set
    var suspendedResumes = 0
        private set

    override suspend fun onToolExecutionSuspended(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        toolCallId: String,
        correlationId: String,
        argumentsDigest: Sha256Digest,
        expiresAt: Instant,
    ) {
        delay(1)
        suspendedResumes++
        suspended++
        events += "audit.suspended"
    }
}

private class StubSagaExecutor : ClaimedResumeExecutor {
    override suspend fun execute(request: ClaimedResumeExecutionRequest): Any? = "executed"
}

private class SagaFakeTool(
    override val name: String,
) : ResolvedTool {
    override val description: String = "test"
    override val inputSchemaJson: String = """{"type":"object"}"""
    override val idempotent: Boolean = false
    override val sideEffectLevel: SideEffectLevel = SideEffectLevel.READ_ONLY
    override val security: dev.tramai.core.policy.ToolSecurityMetadata? = null

    override suspend fun execute(
        input: Any,
        context: ToolExecutionContext,
    ): ToolResult = ToolResult.Success("{}")
}

// ------------------------------------------------------------------
// Failure injection: one semantic knob per collaborator call site
// ------------------------------------------------------------------

/** The gate refuses at creation (before suspending) or fails on creation's resumed frame. */
private class FailingGate(
    private val delegate: ApprovalGateCoordinator,
    private val failOnCreate: Throwable? = null,
    private val failOnCancel: Throwable? = null,
) : ApprovalGateCoordinator by delegate {
    override suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge {
        if (failOnCreate != null) {
            delay(1)
            throw failOnCreate
        }
        return delegate.createApproval(command)
    }

    override suspend fun cancelApproval(
        approvalId: String,
        expectedVersion: Long,
        reason: String,
    ) {
        if (failOnCancel != null) {
            delay(1)
            throw failOnCancel
        }
        delegate.cancelApproval(approvalId, expectedVersion, reason)
    }
}

/** The continuation store fails on create's resumed frame, or on the cancellation compensation performs. */
private class FailingContinuationStore(
    private val delegate: ApprovalContinuationStore,
    private val failOnCreate: Throwable? = null,
    private val failOnCancel: Throwable? = null,
) : ApprovalContinuationStore by delegate {
    override suspend fun create(
        continuation: ApprovalContinuation,
        arguments: SensitiveToolArguments,
    ): ApprovalContinuation {
        if (failOnCreate != null) {
            delay(1)
            throw failOnCreate
        }
        return delegate.create(continuation, arguments)
    }

    override suspend fun cancel(
        approvalId: String,
        expectedVersion: Long,
    ): ApprovalContinuation {
        if (failOnCancel != null) {
            delay(1)
            throw failOnCancel
        }
        return delegate.cancel(approvalId, expectedVersion)
    }
}

/** The suspended-invocation store fails on persistence or on the removal compensation performs. */
private class FailingSuspendedStore(
    private val delegate: SuspendedInvocationStore,
    private val failOnCreate: Throwable? = null,
    private val failOnRemove: Throwable? = null,
) : SuspendedInvocationStore by delegate {
    /** Attempts, not successes: a step that fails records nothing on the delegate. */
    var removeAttempts = 0
        private set

    override suspend fun create(
        metadata: SuspendedInvocationMetadata,
        replayEnvelope: SensitiveReplayEnvelope,
    ) {
        if (failOnCreate != null) {
            delay(1)
            throw failOnCreate
        }
        delegate.create(metadata, replayEnvelope)
    }

    override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? {
        removeAttempts++
        if (failOnRemove != null) {
            delay(1)
            throw failOnRemove
        }
        return delegate.remove(approvalId)
    }
}

/** The audit emitter fails on the suspension entry's resumed frame. */
private class FailingAuditEmitter(
    private val delegate: ApprovalLifecycleAuditEmitter,
    private val failOnSuspended: Throwable? = null,
) : ApprovalLifecycleAuditEmitter by delegate {
    override suspend fun onToolExecutionSuspended(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        toolCallId: String,
        correlationId: String,
        argumentsDigest: Sha256Digest,
        expiresAt: Instant,
    ) {
        if (failOnSuspended != null) {
            delay(1)
            throw failOnSuspended
        }
        delegate.onToolExecutionSuspended(
            approvalId,
            workflowRunId,
            toolName,
            toolCallId,
            correlationId,
            argumentsDigest,
            expiresAt,
        )
    }
}

/**
 * The third compensation action (the gate's `cancelApproval`): genuinely suspends and is resumed only by the test.
 * A successful resume delegates, so an experiment that never suspended cannot masquerade as a passing one.
 */
private class PausingGate(
    private val delegate: ApprovalGateCoordinator,
) : ApprovalGateCoordinator by delegate {
    val suspended = CompletableDeferred<Unit>()
    private var pending: CancellableContinuation<Unit>? = null
    var suspendCount = 0
        private set

    override suspend fun cancelApproval(
        approvalId: String,
        expectedVersion: Long,
        reason: String,
    ) {
        suspendCancellableCoroutine<Unit> { continuation ->
            pending = continuation
            suspendCount++
            suspended.complete(Unit)
        }
        delegate.cancelApproval(approvalId, expectedVersion, reason)
    }

    fun resumeWith(failure: Throwable) {
        requireNotNull(pending) { "the gate cancellation never suspended" }.resumeWithException(failure)
    }
}

/** The second compensation action (the continuation store's `cancel`), same resume-only-by-test shape. */
private class PausingContinuationStore(
    private val delegate: ApprovalContinuationStore,
) : ApprovalContinuationStore by delegate {
    val suspended = CompletableDeferred<Unit>()
    private var pending: CancellableContinuation<ApprovalContinuation>? = null
    var suspendCount = 0
        private set

    override suspend fun cancel(
        approvalId: String,
        expectedVersion: Long,
    ): ApprovalContinuation {
        suspendCancellableCoroutine<ApprovalContinuation> { continuation ->
            pending = continuation
            suspendCount++
            suspended.complete(Unit)
        }
        return delegate.cancel(approvalId, expectedVersion)
    }

    fun resumeWith(failure: Throwable) {
        requireNotNull(pending) { "the continuation cancellation never suspended" }.resumeWithException(failure)
    }
}
