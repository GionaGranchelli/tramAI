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
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.ValidateResumeCommand
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.NestedApprovalNotSupportedException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.exception.StructuredOutputException
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.core.policy.ApprovalRequirement
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.NoOpPolicyDecisionAuditEmitter
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyDecisionAuditEmitter
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.engine.EngineEventObserver
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.GovernedSuspendedInvocation
import dev.tramai.engine.PolicyEnforcementHelper
import dev.tramai.engine.ReplayEnvelopeFactory
import dev.tramai.engine.ResumeApprovalCommand
import dev.tramai.engine.ResumeDefinitionDigestHelper
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.ResumeToolDeclarationDigestHelper
import dev.tramai.engine.ResumeToolReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.engine.ToolRegistry
import dev.tramai.engine.withCapturedSecondaryDiagnostics
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The collaborator contracts `ApprovalResumeCoordinator` must honour when a collaborator genuinely
 * suspends, and when one fails on the resumed frame.
 *
 * Every store behind the released resume suites completes synchronously, so the suspension protocol
 * itself is never exercised: a suspend call that returns normally leaves the sentinel return of the
 * generated state machine unexecuted, and the rethrow that must unwrap a failure arriving on the
 * resumed frame never runs. These tests state the contract where it is observable — a collaborator
 * suspends for real (and resumes), and the coordinator must either complete the resume or propagate
 * the collaborator's own failure to the caller.
 *
 * Scope note: this is the resume saga, which has no single transactional boundary. What is pinned
 * here is that a failure after suspension is *propagated*, that no later step of the chain runs
 * after it, and that a denial or a nested-approval requirement still cancels the continuation and
 * reaches the caller when the cancellation itself suspends.
 */
class ApprovalResumeSuspensionContractTest {
    private val digest = Sha256Digest.of("sha256:" + "1".repeat(64))
    private val input = """{"x":2}"""
    private val inputDigest = Sha256ToolArgumentsDigester().digest(SensitiveToolArguments.of(input))
    private val token = ApprovalToken.parsePresented("token-1")
    private val toolName = "test_tool"
    private val toolCallId = "call-1"
    private val approvalId = "approval-1"
    private val resumedBy = "admin"
    private val identity = EngineExecutionIdentity("wf-1", "corr-1", digest, "policy-v1", "actor-1")
    private val command =
        ResumeApprovalCommand(
            approvalId,
            approvalExpectedVersion = 1,
            continuationExpectedVersion = 3,
            token,
            resumedBy,
        )

    private val tool = SuspensionFakeTool(toolName)
    private val toolReference = ResumeToolReference(toolName, ResumeToolDeclarationDigestHelper.compute(tool))
    private val operation = approvalOperation(ApprovalRegistryService::class.java.getMethod("first"))
    private val service = approvalService(operation)
    private val opRef =
        ResumeOperationReference(
            serviceInterface = ApprovalRegistryService::class.qualifiedName!!,
            methodName = "first",
            jvmMethodDescriptor = "()Ljava/lang/String;",
            resumeDefinitionDigest = ResumeDefinitionDigestHelper.compute(service, operation),
        )

    private val messages =
        listOf(
            Message(MessageRole.USER, "compute"),
            Message(MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall(toolCallId, toolName, input))),
        )
    private val prepared = ReplayEnvelopeFactory.prepareForSuspension(opRef, messages, toolCallId, toolName, 0)

    private fun metadata(replayEnvelopeDigest: Sha256Digest = prepared.digest) =
        SuspendedInvocationMetadata(
            approvalId = approvalId,
            toolCallId = toolCallId,
            toolName = toolName,
            toolCallIndex = 0,
            correlationId = "corr-1",
            identity = identity,
            securityContext = ExecutionSecurityContext(),
            operationReference = opRef,
            replayEnvelopeDigest = replayEnvelopeDigest,
            conversationId = null,
            historySize = 0,
            tokenBudgetSnapshot = null,
            toolReference = toolReference,
            toolSecurity = null,
        )

    private fun continuation(status: ApprovalContinuationStatus = ApprovalContinuationStatus.PENDING) =
        ApprovalContinuation(
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

    // ------------------------------------------------------------------
    // Fixture assembly
    // ------------------------------------------------------------------

    private val baseContinuations = RecordingSuspensionContinuationStore(suspensionContinuation())
    private val baseSuspensions = RecordingSuspensionSuspendedStore(metadata(), prepared.envelope)
    private val baseGate = RecordingSuspensionGate()
    private val baseExecutor = RecordingSuspensionExecutor()
    private val baseAudit = RecordingSuspensionAuditEmitter()

    /** The policy-decision audit emitter the fixture wires; set it where the policy seam must suspend. */
    private var policyAudit: PolicyDecisionAuditEmitter = NoOpPolicyDecisionAuditEmitter

    /** The policy decision the fixture's engine returns; set it by the tests that need a non-allow verdict. */
    private var policyDecision: PolicyDecision = PolicyDecision.Allow

    private fun suspensionContinuation() = continuation()

    private fun coordinator(
        continuations: ApprovalContinuationStore = baseContinuations,
        suspensions: SuspendedInvocationStore = baseSuspensions,
        gate: ApprovalGateCoordinator = baseGate,
        executor: ClaimedResumeExecutor = baseExecutor,
        audit: ApprovalLifecycleAuditEmitter = baseAudit,
    ): ApprovalResumeCoordinator {
        val registry = ResumeOperationRegistry().also { it.register(service, operation, executor) }
        return ApprovalResumeCoordinator(
            approvalContinuationStore = continuations,
            suspendedInvocationStore = suspensions,
            resumeOperationRegistry = registry,
            toolRegistry = ToolRegistry(mapOf(toolName to tool)),
            toolArgumentsDigester = Sha256ToolArgumentsDigester(),
            approvalLifecycleAuditEmitter = audit,
            engineEventObserver = RecordingSuspensionObserver(),
            claimService = ContinuationClaimService(continuations),
            authorizationService =
                ReplayAuthorizationService(
                    gate,
                    suspensions,
                    audit,
                    PolicyEnforcementHelper(
                        policyEngine = PolicyEngine { policyDecision },
                        migrationWarningGuard = AtomicBoolean(false),
                        auditEmitter = policyAudit,
                    ),
                    RecordingSuspensionObserver(),
                ),
        )
    }

    private fun resume(coordinator: ApprovalResumeCoordinator): Any? = bounded { coordinator.resume(command) }

    /** A coroutine test bounded well below the mutation runner's own timeout. */
    private fun <T> bounded(block: suspend () -> T): T = runBlocking { withTimeout(2_000) { block() } }

    /**
     * The collaborator's own failure must reach the caller unchanged. kotlinx's stack-trace recovery
     * hands the caller a copy of the original instance, so what identifies the failure is its type and
     * message — every test uses a distinct message, which is what makes an earlier collaborator's
     * failure distinguishable from the one under test.
     */
    private fun assertReachesCaller(
        thrown: Throwable,
        expected: Throwable,
    ) {
        assertThat(thrown).isInstanceOf(expected.javaClass)
        assertThat(thrown.message).isEqualTo(expected.message)
    }

    private fun failureOf(coordinator: ApprovalResumeCoordinator): Throwable {
        val caught =
            try {
                resume(coordinator)
                null
            } catch (e: Throwable) {
                e
            }
        return caught ?: throw AssertionError("the resume was expected to fail and did not")
    }

    // ------------------------------------------------------------------
    // A. The saga completes when every collaborator genuinely suspends
    // ------------------------------------------------------------------

    @Test
    fun `a resume completes when every collaborator genuinely suspends`() {
        val continuations = SuspendingReadStore(baseContinuations)
        val suspensions = ResumeSuspendingSuspendedStore(baseSuspensions)
        val gate = ResumeSuspendingGate(baseGate)
        val executor = ResumeSuspendingExecutor(baseExecutor)
        val audit = ResumeSuspendingAuditEmitter(baseAudit)
        policyAudit = ResumeSuspendingPolicyAuditEmitter()

        val result =
            resume(
                coordinator(
                    continuations = continuations,
                    suspensions = suspensions,
                    gate = gate,
                    executor = executor,
                    audit = audit,
                ),
            )

        assertThat(result).isEqualTo("executed")
        // Every suspension must have been resumed for the saga to reach the executor: a synchronous
        // fake cannot produce these counts.
        assertThat(continuations.readResumes).isEqualTo(2)
        assertThat(continuations.claimResumes).isEqualTo(1)
        assertThat(continuations.completeResumes).isEqualTo(1)
        assertThat(suspensions.metadataLoadResumes).isEqualTo(1)
        assertThat(suspensions.revealResumes).isEqualTo(1)
        assertThat(suspensions.removeResumes).isEqualTo(1)
        assertThat(gate.validateResumes).isEqualTo(1)
        assertThat(gate.authorizeResumes).isEqualTo(1)
        assertThat(executor.executeResumes).isEqualTo(1)
        assertThat(audit.completionResumes).isEqualTo(1)
        assertThat((policyAudit as ResumeSuspendingPolicyAuditEmitter).emitResumes).isEqualTo(1)
        assertThat(baseContinuations.completedStatus).isEqualTo(ApprovalContinuationStatus.COMPLETED)
    }

    @Test
    fun `a governed resume completes and executes inside the recovered run scope`() {
        val governed = TestGovernedSuspendedInvocationStore(baseSuspensions).also { it.governed += governedRecord() }
        val suspensions = ResumeSuspendingGovernedStore(governed)
        val executor = ResumeSuspendingExecutor(baseExecutor)

        val result =
            resume(
                coordinator(
                    suspensions = suspensions,
                    executor = executor,
                    audit = ResumeSuspendingAuditEmitter(baseAudit),
                    gate = ResumeSuspendingGate(baseGate),
                    continuations = SuspendingReadStore(baseContinuations),
                ),
            )

        assertThat(result).isEqualTo("executed")
        assertThat(suspensions.identityReadResumes).isEqualTo(1)
        // The recovered identity is installed around the resumed execution: the executor observes the
        // governed run it belongs to rather than the caller's empty scope.
        assertThat(executor.observedIdentity).isEqualTo(governedRecord().runIdentity)
    }

    private fun governedRecord() = GovernedSuspendedInvocation(metadata(), governedIdentity(runId = "wf-1"))

    @Test
    fun `a governed executor failure after suspension reaches the caller`() {
        val failure = IllegalStateException("governed-executor-failed")
        val governed = TestGovernedSuspendedInvocationStore(baseSuspensions).also { it.governed += governedRecord() }
        val executor = ResumeSuspendingExecutor(baseExecutor, failAfterExecuteResumes = failure)

        val thrown =
            failureOf(
                coordinator(
                    suspensions = ResumeSuspendingGovernedStore(governed),
                    executor = executor,
                    continuations = SuspendingReadStore(baseContinuations),
                    gate = ResumeSuspendingGate(baseGate),
                    audit = ResumeSuspendingAuditEmitter(baseAudit),
                ),
            )

        assertReachesCaller(thrown, failure)
        assertThat(baseContinuations.completeCalls).isZero()
    }

    @Test
    fun `a policy-decision audit failure after suspension reaches the caller`() {
        val failure = IllegalStateException("policy-audit-failed")
        policyAudit = ResumeSuspendingPolicyAuditEmitter(failAfterEmitResumes = failure)

        val thrown =
            failureOf(
                coordinator(
                    continuations = SuspendingReadStore(baseContinuations),
                    suspensions = ResumeSuspendingSuspendedStore(baseSuspensions),
                    gate = ResumeSuspendingGate(baseGate),
                    executor = ResumeSuspendingExecutor(baseExecutor),
                    audit = ResumeSuspendingAuditEmitter(baseAudit),
                ),
            )

        assertReachesCaller(thrown, failure)
        assertThat((policyAudit as ResumeSuspendingPolicyAuditEmitter).emitResumes).isEqualTo(1)
        assertThat(baseContinuations.claimCalls).isZero()
    }

    // ------------------------------------------------------------------
    // B. A collaborator failure on the resumed frame reaches the caller
    // ------------------------------------------------------------------

    @Test
    fun `a continuation read failure after suspension reaches the caller`() {
        val failure = IllegalStateException("continuation-read-failed")
        val continuations = SuspendingReadStore(baseContinuations, failOnReadNumber = 1, failure = failure)

        assertReachesCaller(failureOf(coordinator(continuations = continuations)), failure)
        assertThat(baseContinuations.claimCalls).isZero()
        assertThat(baseContinuations.completeCalls).isZero()
    }

    @Test
    fun `a claim-time read failure after suspension reaches the caller without claiming`() {
        val failure = IllegalStateException("claim-read-failed")
        val continuations = SuspendingReadStore(baseContinuations, failOnReadNumber = 2, failure = failure)

        assertReachesCaller(failureOf(coordinator(continuations = continuations)), failure)
        assertThat(baseContinuations.claimCalls).isZero()
        assertThat(baseExecutor.calls).isZero()
    }

    @Test
    fun `a metadata load failure after suspension reaches the caller`() {
        val failure = IllegalStateException("metadata-load-failed")
        val suspensions = ResumeSuspendingSuspendedStore(baseSuspensions, failAfterMetadataLoadResumes = failure)

        assertReachesCaller(failureOf(coordinator(suspensions = suspensions)), failure)
        assertThat(baseExecutor.calls).isZero()
    }

    @Test
    fun `a governed identity read failure after suspension reaches the caller before any claim`() {
        val failure = IllegalStateException("governed-identity-read-failed")
        val governed = TestGovernedSuspendedInvocationStore(baseSuspensions).also { it.governed += governedRecord() }
        val suspensions = ResumeSuspendingGovernedStore(governed, failAfterIdentityReadResumes = failure)

        assertReachesCaller(failureOf(coordinator(suspensions = suspensions)), failure)
        assertThat(baseContinuations.claimCalls).isZero()
    }

    @Test
    fun `a token validation failure after suspension reaches the caller`() {
        val failure = IllegalStateException("token-validation-failed")
        val gate = ResumeSuspendingGate(baseGate, failAfterValidateResumes = failure)

        assertReachesCaller(failureOf(coordinator(gate = gate)), failure)
        assertThat(baseContinuations.claimCalls).isZero()
    }

    @Test
    fun `an authorization failure after suspension reaches the caller before the claim`() {
        val failure = IllegalStateException("authorization-failed")
        val gate = ResumeSuspendingGate(baseGate, failAfterAuthorizeResumes = failure)

        assertReachesCaller(failureOf(coordinator(gate = gate)), failure)
        assertThat(baseContinuations.claimCalls).isZero()
    }

    @Test
    fun `a claim write failure after suspension reaches the caller`() {
        val failure = IllegalStateException("claim-write-failed")
        val continuations = SuspendingReadStore(baseContinuations, failAfterClaimResumes = failure)

        val thrown = failureOf(coordinator(continuations = continuations))
        assertReachesCaller(thrown, failure)
        assertThat(baseExecutor.calls).isZero()
        assertThat(baseContinuations.completeCalls).isZero()
    }

    @Test
    fun `a replay reveal failure after suspension reaches the caller and completes nothing`() {
        val failure = IllegalStateException("replay-reveal-failed")
        val suspensions = ResumeSuspendingSuspendedStore(baseSuspensions, failAfterRevealResumes = failure)

        assertReachesCaller(failureOf(coordinator(suspensions = suspensions)), failure)
        assertThat(baseExecutor.calls).isZero()
        assertThat(baseContinuations.completeCalls).isZero()
        assertThat(baseSuspensions.removeCalls).isZero()
    }

    @Test
    fun `a claimed-arguments integrity mismatch after suspension is rejected`() {
        val mismatched =
            ClaimedApprovalContinuation(
                continuation().copy(argumentsDigest = Sha256Digest.of("sha256:" + "9".repeat(64))),
                SensitiveToolArguments.of(input),
            )
        baseContinuations.claimWith(mismatched)
        val continuations = SuspendingReadStore(baseContinuations)
        val audit = ResumeSuspendingAuditEmitter(baseAudit)

        val thrown = failureOf(coordinator(continuations = continuations, audit = audit))

        assertThat(thrown.message).contains("payload integrity mismatch")
        assertThat(baseExecutor.calls).isZero()
        assertThat(baseContinuations.completeCalls).isZero()
    }

    @Test
    fun `a replay-envelope digest mismatch is rejected and reports the mismatch before claiming`() {
        val mismatched =
            RecordingSuspensionSuspendedStore(
                metadata(replayEnvelopeDigest = Sha256Digest.of("sha256:" + "7".repeat(64))),
                prepared.envelope,
            )
        val audit = ResumeSuspendingAuditEmitter(baseAudit)

        val thrown =
            failureOf(coordinator(suspensions = ResumeSuspendingSuspendedStore(mismatched), audit = audit))

        assertThat(thrown).isInstanceOf(ConfigurationException::class.java)
        assertThat(thrown.message).contains("Replay envelope digest mismatch")
        assertThat(audit.uncertainReasons.single()).isEqualTo("replay-envelope-digest-mismatch")
        assertThat(baseExecutor.calls).isZero()
        assertThat(baseContinuations.completeCalls).isZero()
    }

    @Test
    fun `an executor failure after suspension reaches the caller and completes nothing`() {
        val failure = IllegalStateException("executor-failed")
        val executor = ResumeSuspendingExecutor(baseExecutor, failAfterExecuteResumes = failure)

        assertReachesCaller(failureOf(coordinator(executor = executor)), failure)
        assertThat(baseContinuations.completeCalls).isZero()
        assertThat(baseSuspensions.removeCalls).isZero()
    }

    @Test
    fun `a completion write failure after suspension reaches the caller`() {
        val failure = IllegalStateException("completion-write-failed")
        val continuations =
            ResumeSuspendingCompletionStore(baseContinuations, failAfterCompleteResumes = failure)

        assertReachesCaller(failureOf(coordinator(continuations = continuations)), failure)
    }

    @Test
    fun `a structured-output failure after suspension is reported uncertain and propagated`() {
        val failure = StructuredOutputException("structured-parse-failed")
        val executor = ResumeSuspendingExecutor(baseExecutor, failAfterExecuteResumes = failure)
        val audit = ResumeSuspendingAuditEmitter(baseAudit)

        val thrown = failureOf(coordinator(executor = executor, audit = audit))

        assertReachesCaller(thrown, failure)
        assertThat(audit.uncertainReasons).hasSize(1)
        assertThat(audit.uncertainReasons.single()).startsWith("structured-parse-failed")
        assertThat(audit.uncertainResumes).isEqualTo(1)
    }

    @Test
    fun `a nested-approval requirement raised after suspension is reported uncertain and propagated`() {
        val failure = NestedApprovalNotSupportedException(approvalId, "Nested approval not supported")
        val executor = ResumeSuspendingExecutor(baseExecutor, failAfterExecuteResumes = failure)
        val audit = ResumeSuspendingAuditEmitter(baseAudit)

        val thrown = failureOf(coordinator(executor = executor, audit = audit))

        assertThat(thrown).isInstanceOf(NestedApprovalNotSupportedException::class.java)
        assertThat(audit.uncertainReasons.single()).isEqualTo("nested-approval-not-supported")
        assertThat(audit.uncertainResumes).isEqualTo(1)
    }

    @Test
    fun `an uncertain-outcome audit failure after suspension does not replace the primary failure`() {
        val primary = IllegalStateException("executor-failed")
        val auditFailure = IllegalStateException("audit-failed")
        val audit = ResumeSuspendingAuditEmitter(baseAudit, failAfterUncertainResumes = auditFailure)
        val executor = ResumeSuspendingExecutor(baseExecutor, failAfterExecuteResumes = primary)

        var thrown: Throwable? = null
        val diagnostics =
            withCapturedSecondaryDiagnostics { thrown = failureOf(coordinator(executor = executor, audit = audit)) }

        assertReachesCaller(thrown ?: error("the resume was expected to fail"), primary)
        assertThat(audit.uncertainResumes).isEqualTo(1)
        assertThat(diagnostics.joinToString(" ")).contains("onUncertainOutcome")
    }

    @Test
    fun `a completion audit failure after suspension is recorded and does not fail the resume`() {
        val failure = IllegalStateException("completion-audit-failed")
        val audit = ResumeSuspendingAuditEmitter(baseAudit, failAfterCompletionResumes = failure)

        val diagnostics =
            withCapturedSecondaryDiagnostics {
                val result =
                    resume(
                        coordinator(
                            continuations = SuspendingReadStore(baseContinuations),
                            suspensions = ResumeSuspendingSuspendedStore(baseSuspensions),
                            gate = ResumeSuspendingGate(baseGate),
                            executor = ResumeSuspendingExecutor(baseExecutor),
                            audit = audit,
                        ),
                    )
                assertThat(result).isEqualTo("executed")
            }

        assertThat(audit.completionResumes).isEqualTo(1)
        assertThat(diagnostics.joinToString(" ")).contains("onToolExecutionCompleted")
        assertThat(baseContinuations.completedStatus).isEqualTo(ApprovalContinuationStatus.COMPLETED)
    }

    // ------------------------------------------------------------------
    // C. Denial and nested-approval requirements across a suspension in the cancellation path
    // ------------------------------------------------------------------

    @Test
    fun `a denied resume cancels and reaches the caller when the cancellation suspends`() {
        val continuations =
            ResumeSuspendingCompletionStore(baseContinuations, suspendingCancel = true)
        val suspensions = ResumeSuspendingSuspendedStore(baseSuspensions)
        val audit = ResumeSuspendingAuditEmitter(baseAudit)
        policyDecision = PolicyDecision.Deny("denied by policy", "resume-denied")

        val thrown =
            failureOf(
                coordinator(
                    continuations = continuations,
                    suspensions = suspensions,
                    audit = audit,
                ),
            )

        assertThat(thrown).isInstanceOf(PolicyViolationException::class.java)
        assertThat(continuations.cancelResumes).isEqualTo(1)
        assertThat(continuations.cancelCalls).isEqualTo(1)
        assertThat(baseContinuations.claimCalls).isZero()
        assertThat(baseExecutor.calls).isZero()
    }

    @Test
    fun `a nested-approval requirement cancels and reaches the caller when the cancellation suspends`() {
        val continuations =
            ResumeSuspendingCompletionStore(baseContinuations, suspendingCancel = true)
        val suspensions = ResumeSuspendingSuspendedStore(baseSuspensions)
        val audit = ResumeSuspendingAuditEmitter(baseAudit)
        policyDecision =
            PolicyDecision.RequireApproval(
                ApprovalRequirement(toolName, inputDigest.value, "nested approval required", 600_000L),
            )

        val thrown =
            failureOf(
                coordinator(
                    continuations = continuations,
                    suspensions = suspensions,
                    audit = audit,
                ),
            )

        assertThat(thrown).isInstanceOf(NestedApprovalNotSupportedException::class.java)
        // The requirement is raised during authorization, before anything is claimed: the continuation
        // is cancelled, but there is no uncertain outcome to report yet.
        assertThat(audit.uncertainResumes).isZero()
        assertThat(continuations.cancelResumes).isEqualTo(1)
        assertThat(continuations.cancelCalls).isEqualTo(1)
        assertThat(baseExecutor.calls).isZero()
    }
}

// ------------------------------------------------------------------
// Recording doubles
// ------------------------------------------------------------------

private class SuspensionFakeTool(
    override val name: String,
) : ResolvedTool {
    override val description: String = "test"
    override val inputSchemaJson: String = """{"type":"object"}"""
    override val idempotent: Boolean = false
    override val sideEffectLevel: dev.tramai.core.model.SideEffectLevel =
        dev.tramai.core.model.SideEffectLevel.READ_ONLY
    override val security: dev.tramai.core.policy.ToolSecurityMetadata? = null

    override suspend fun execute(
        input: Any,
        context: ToolExecutionContext,
    ): ToolResult = ToolResult.Success("{}")
}

private class RecordingSuspensionContinuationStore(
    private val value: ApprovalContinuation,
) : ApprovalContinuationStore {
    var reads = 0
        private set
    var claimCalls = 0
        private set
    var completeCalls = 0
        private set
    var cancelCalls = 0
        private set
    var completedStatus: ApprovalContinuationStatus? = null
        private set
    var claimReturnsWith: ClaimedApprovalContinuation? = null
        private set

    fun claimWith(claimed: ClaimedApprovalContinuation) {
        claimReturnsWith = claimed
    }

    override suspend fun create(
        continuation: ApprovalContinuation,
        arguments: SensitiveToolArguments,
    ): ApprovalContinuation = continuation

    override suspend fun get(approvalId: String): ApprovalContinuation? {
        reads++
        return value
    }

    override suspend fun claimForExecution(
        approvalId: String,
        expectedVersion: Long,
        claimedBy: String,
    ): ClaimedApprovalContinuation {
        claimCalls++
        return claimReturnsWith ?: ClaimedApprovalContinuation(value, SensitiveToolArguments.of("""{"x":2}"""))
    }

    override suspend fun complete(
        approvalId: String,
        expectedVersion: Long,
        completedBy: String,
    ): ApprovalContinuation {
        completeCalls++
        completedStatus = ApprovalContinuationStatus.COMPLETED
        return value.copy(status = ApprovalContinuationStatus.COMPLETED, version = expectedVersion)
    }

    override suspend fun expire(
        approvalId: String,
        expectedVersion: Long,
    ): ApprovalContinuation = value

    override suspend fun cancel(
        approvalId: String,
        expectedVersion: Long,
    ): ApprovalContinuation {
        cancelCalls++
        return value.copy(status = ApprovalContinuationStatus.CANCELLED)
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
    ): ApprovalContinuation = value

    override suspend fun sweepExpired(): Int = 0
}

private class RecordingSuspensionSuspendedStore(
    private val value: SuspendedInvocationMetadata?,
    private val envelope: SensitiveReplayEnvelope?,
) : SuspendedInvocationStore {
    var removeCalls = 0
        private set

    override suspend fun create(
        metadata: SuspendedInvocationMetadata,
        replayEnvelope: SensitiveReplayEnvelope,
    ) = Unit

    override suspend fun get(approvalId: String): SuspendedInvocationMetadata? = value

    override suspend fun revealReplayEnvelope(approvalId: String): SensitiveReplayEnvelope? = envelope

    override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? {
        removeCalls++
        return value
    }
}

private class RecordingSuspensionGate : ApprovalGateCoordinator {
    override suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge =
        ApprovalChallenge("approval-1", ApprovalToken.parsePresented("token-1"), Instant.MAX)

    override suspend fun validateResume(command: ValidateResumeCommand): ApprovalValidation =
        ApprovalValidation(command.approvalId, command.consumedBy, Instant.EPOCH, 0L)

    override suspend fun authorizeResume(command: AuthorizeResumeCommand): ApprovalAuthorization =
        ApprovalAuthorization(command.approvalId, command.consumedBy, Instant.EPOCH, 0L, replayed = false)

    override suspend fun cancelApproval(
        approvalId: String,
        expectedVersion: Long,
        reason: String,
    ) = Unit
}

private class RecordingSuspensionExecutor : ClaimedResumeExecutor {
    var calls = 0
        private set

    override suspend fun execute(request: ClaimedResumeExecutionRequest): Any? {
        calls++
        return "executed"
    }
}

private class RecordingSuspensionAuditEmitter : ApprovalLifecycleAuditEmitter {
    val uncertainReasons = mutableListOf<String>()

    override suspend fun onToolExecutionSuspended(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        toolCallId: String,
        correlationId: String,
        argumentsDigest: Sha256Digest,
        expiresAt: Instant,
    ) = Unit

    override suspend fun onToolExecutionResumed(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        resumedBy: String,
    ) = Unit

    override suspend fun onToolExecutionCompleted(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        completedBy: String,
    ) = Unit

    override suspend fun onUncertainOutcome(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        reason: String,
    ) {
        uncertainReasons += reason
    }

    override suspend fun onSuspensionCancelled(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        reason: String,
    ) = Unit

    override suspend fun onStaleClaimDetected(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        claimedAt: Instant,
    ) = Unit

    override suspend fun onClaimedContinuationForceCancellationRequested(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        cancelledBy: String,
        reasonCode: String,
    ) = Unit

    override suspend fun onClaimedContinuationForceCancelled(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        cancelledBy: String,
        reasonCode: String,
    ) = Unit
}

private class RecordingSuspensionObserver : EngineEventObserver {
    override fun onEngineEvent(
        name: String,
        attributes: Map<String, Any?>,
    ) = Unit
}

// ------------------------------------------------------------------
// Delegating collaborators that genuinely suspend, and optionally fail on the resumed frame.
// Each carries at most one failure per operation it performs: "this collaborator suspends, then
// fails" — no mutation-shaped switches.
// ------------------------------------------------------------------

/**
 * The continuation read/claim path suspends. [failure] is thrown when the read identified by
 * [failOnReadNumber] resumes: a resume reads the continuation twice (eligibility, then claim), and the
 * two reads fail different contracts, so the seam is named rather than counted.
 */
private class SuspendingReadStore(
    private val delegate: ApprovalContinuationStore,
    private val failOnReadNumber: Int? = null,
    private val failure: Throwable? = null,
    private val failAfterClaimResumes: Throwable? = null,
) : ApprovalContinuationStore by delegate {
    var readResumes = 0
        private set
    var claimResumes = 0
        private set
    var completeResumes = 0
        private set
    var reads = 0
        private set

    override suspend fun get(approvalId: String): ApprovalContinuation? {
        delay(1)
        reads++
        readResumes++
        if (failure != null && failOnReadNumber == reads) throw failure
        return delegate.get(approvalId)
    }

    override suspend fun claimForExecution(
        approvalId: String,
        expectedVersion: Long,
        claimedBy: String,
    ): ClaimedApprovalContinuation {
        delay(1)
        claimResumes++
        failAfterClaimResumes?.let { throw it }
        return delegate.claimForExecution(approvalId, expectedVersion, claimedBy)
    }

    override suspend fun complete(
        approvalId: String,
        expectedVersion: Long,
        completedBy: String,
    ): ApprovalContinuation {
        delay(1)
        completeResumes++
        return delegate.complete(approvalId, expectedVersion, completedBy)
    }
}

/** The continuation completion and cancellation path suspends. */
private class ResumeSuspendingCompletionStore(
    private val delegate: ApprovalContinuationStore,
    private val failAfterCompleteResumes: Throwable? = null,
    private val suspendingCancel: Boolean = false,
) : ApprovalContinuationStore by delegate {
    var completeResumes = 0
        private set
    var cancelResumes = 0
        private set
    var cancelCalls = 0
        private set

    override suspend fun complete(
        approvalId: String,
        expectedVersion: Long,
        completedBy: String,
    ): ApprovalContinuation {
        delay(1)
        completeResumes++
        failAfterCompleteResumes?.let { throw it }
        return delegate.complete(approvalId, expectedVersion, completedBy)
    }

    override suspend fun cancel(
        approvalId: String,
        expectedVersion: Long,
    ): ApprovalContinuation {
        if (suspendingCancel) delay(1)
        cancelResumes++
        cancelCalls++
        return delegate.cancel(approvalId, expectedVersion)
    }
}

/** The suspended-invocation read path (metadata, replay envelope, cleanup) suspends. */
private class ResumeSuspendingSuspendedStore(
    private val delegate: SuspendedInvocationStore,
    private val failAfterMetadataLoadResumes: Throwable? = null,
    private val failAfterRevealResumes: Throwable? = null,
) : SuspendedInvocationStore by delegate {
    var metadataLoadResumes = 0
        private set
    var revealResumes = 0
        private set
    var removeResumes = 0
        private set

    override suspend fun get(approvalId: String): SuspendedInvocationMetadata? {
        delay(1)
        metadataLoadResumes++
        failAfterMetadataLoadResumes?.let { throw it }
        return delegate.get(approvalId)
    }

    override suspend fun revealReplayEnvelope(approvalId: String): SensitiveReplayEnvelope? {
        delay(1)
        revealResumes++
        failAfterRevealResumes?.let { throw it }
        return delegate.revealReplayEnvelope(approvalId)
    }

    override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? {
        delay(1)
        removeResumes++
        return delegate.remove(approvalId)
    }
}

/** The governed read path suspends; the recovered identity arrives on the resumed frame. */
private class ResumeSuspendingGovernedStore(
    private val delegate: dev.tramai.engine.GovernedSuspendedInvocationStore,
    private val failAfterIdentityReadResumes: Throwable? = null,
) : dev.tramai.engine.GovernedSuspendedInvocationStore by delegate {
    var metadataLoadResumes = 0
        private set
    var identityReadResumes = 0
        private set

    override suspend fun get(approvalId: String): SuspendedInvocationMetadata? {
        delay(1)
        metadataLoadResumes++
        return delegate.get(approvalId)
    }

    override suspend fun revealReplayEnvelope(approvalId: String): SensitiveReplayEnvelope? {
        delay(1)
        return delegate.revealReplayEnvelope(approvalId)
    }

    override suspend fun governedRunIdentity(approvalId: String): dev.tramai.core.identity.GovernedRunIdentity? {
        delay(1)
        identityReadResumes++
        failAfterIdentityReadResumes?.let { throw it }
        return delegate.governedRunIdentity(approvalId)
    }
}

/** The gate's validation and authorization calls suspend. */
private class ResumeSuspendingGate(
    private val delegate: ApprovalGateCoordinator,
    private val failAfterValidateResumes: Throwable? = null,
    private val failAfterAuthorizeResumes: Throwable? = null,
) : ApprovalGateCoordinator by delegate {
    var validateResumes = 0
        private set
    var authorizeResumes = 0
        private set

    override suspend fun validateResume(command: ValidateResumeCommand): ApprovalValidation {
        delay(1)
        validateResumes++
        failAfterValidateResumes?.let { throw it }
        return delegate.validateResume(command)
    }

    override suspend fun authorizeResume(command: AuthorizeResumeCommand): ApprovalAuthorization {
        delay(1)
        authorizeResumes++
        failAfterAuthorizeResumes?.let { throw it }
        return delegate.authorizeResume(command)
    }
}

/** The claimed-resume executor suspends before performing the side effect. */
private class ResumeSuspendingExecutor(
    private val delegate: ClaimedResumeExecutor,
    private val failAfterExecuteResumes: Throwable? = null,
) : ClaimedResumeExecutor by delegate {
    var executeResumes = 0
        private set
    var observedIdentity: dev.tramai.core.identity.GovernedRunIdentity? = null
        private set

    override suspend fun execute(request: ClaimedResumeExecutionRequest): Any? {
        delay(1)
        executeResumes++
        observedIdentity =
            dev.tramai.core.identity.GovernedRunScope
                .resolve(kotlinx.coroutines.currentCoroutineContext())
        failAfterExecuteResumes?.let { throw it }
        return delegate.execute(request)
    }
}

/** The lifecycle audit emitter suspends on its post-suspension emissions. */
private class ResumeSuspendingAuditEmitter(
    private val delegate: ApprovalLifecycleAuditEmitter,
    private val failAfterUncertainResumes: Throwable? = null,
    private val failAfterCompletionResumes: Throwable? = null,
) : ApprovalLifecycleAuditEmitter by delegate {
    val uncertainReasons = mutableListOf<String>()
    var uncertainResumes = 0
        private set
    var completionResumes = 0
        private set

    override suspend fun onUncertainOutcome(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        reason: String,
    ) {
        delay(1)
        uncertainResumes++
        uncertainReasons += reason
        failAfterUncertainResumes?.let { throw it }
        delegate.onUncertainOutcome(approvalId, workflowRunId, toolName, reason)
    }

    override suspend fun onToolExecutionCompleted(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        completedBy: String,
    ) {
        delay(1)
        completionResumes++
        failAfterCompletionResumes?.let { throw it }
        delegate.onToolExecutionCompleted(approvalId, workflowRunId, toolName, completedBy)
    }
}

/**
 * The policy-decision audit emitter suspends, which is the only suspension point reachable from
 * `ReplayAuthorizationService.decideResumePolicy` — and therefore the only way the resume can be
 * left sitting on the policy seam's sentinel.
 */
private class ResumeSuspendingPolicyAuditEmitter(
    private val failAfterEmitResumes: Throwable? = null,
) : PolicyDecisionAuditEmitter {
    var emitResumes = 0
        private set

    override suspend fun emit(
        enforcementPoint: EnforcementPoint,
        context: PolicyContext,
        decision: PolicyDecision,
    ) {
        delay(1)
        emitResumes++
        failAfterEmitResumes?.let { throw it }
    }
}
