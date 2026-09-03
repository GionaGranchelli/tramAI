package dev.tramai.engine.benchmark

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
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.policy.ToolSecurityMetadata
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
import dev.tramai.engine.approval.ApprovalRegistryService
import dev.tramai.engine.approval.ApprovalResumeCoordinator
import dev.tramai.engine.approval.ClaimedResumeExecutionRequest
import dev.tramai.engine.approval.ClaimedResumeExecutor
import dev.tramai.engine.approval.ContinuationClaimService
import dev.tramai.engine.approval.ReplayAuthorizationService
import dev.tramai.engine.approval.ResumeOperationRegistry
import dev.tramai.engine.approval.approvalOperation
import dev.tramai.engine.approval.approvalService
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import dev.tramai.testing.benchmark.BenchmarkHarness
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * B08 — approval suspend/resume. Drives the full resume pipeline of a
 * suspended tool invocation: continuation inspect -> metadata load -> registry
 * resolve -> bindings validate -> tool resolve -> token validate -> policy
 * evaluate -> token authorize -> claim -> replay reveal/verify -> arguments
 * verify -> executor execute -> continuation complete -> metadata remove.
 *
 * Fixture replicates the ApprovalResumeCoordinatorTest happy-path harness
 * (in-memory collaborator fakes returning a fresh PENDING continuation per
 * call); coordinator.resume() is timed end-to-end. Mirrors the documented
 * suspend-then-resume cycle for the same tool call — the suspension half is
 * construction of the suspended state (metadata + replay envelope) before the
 * timed region.
 */
@EnabledIfSystemProperty(named = "tramai.benchmark", matches = "true")
class ApprovalSuspendResumeBenchmark {
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

    private val tool = BenchFakeTool(toolName)
    private val toolReference = ResumeToolReference(toolName, ResumeToolDeclarationDigestHelper.compute(tool))
    private val operation = approvalOperation(ApprovalRegistryService::class.java.getMethod("first"))
    private val service = approvalService(operation)
    private val opRef =
        ResumeOperationReference(
            serviceInterface = ApprovalRegistryService::class.qualifiedName!!,
            methodName = "first",
            jvmMethodDescriptor = "()Ljava/lang/String;",
            resumeDefinitionDigest =
                dev.tramai.engine.ResumeDefinitionDigestHelper
                    .compute(service, operation),
        )

    private val messages =
        listOf(
            Message(MessageRole.USER, "compute"),
            Message(MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall(toolCallId, toolName, input))),
        )
    private val prepared = ReplayEnvelopeFactory.prepareForSuspension(opRef, messages, toolCallId, toolName, 0)

    private fun metadata() =
        SuspendedInvocationMetadata(
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

    private fun continuation() =
        ApprovalContinuation(
            approvalId = approvalId,
            workflowRunId = "wf-1",
            correlationId = "corr-1",
            toolCallId = toolCallId,
            toolName = toolName,
            argumentsDigest = inputDigest,
            policyVersion = "policy-v1",
            workflowDigest = digest,
            status = ApprovalContinuationStatus.PENDING,
            createdAt = Instant.EPOCH,
            approvalExpiresAt = Instant.MAX,
            claimedBy = null,
            claimedAt = null,
            completedAt = null,
            version = 3L,
        )

    @Test
    fun `B08 approval suspend-resume latency`() {
        val store = BenchContinuationStore(continuation(), input)
        val suspended = BenchSuspendedStore(metadata(), prepared.envelope)
        val executor = BenchResumeExecutor()
        val registry = ResumeOperationRegistry().also { it.register(service, operation, executor) }
        val gate = BenchGate(token)
        val audit = BenchAuditEmitter()
        val observer =
            object : EngineEventObserver {
                override fun onEngineEvent(
                    name: String,
                    attributes: Map<String, Any?>,
                ) = Unit
            }
        val policy =
            PolicyEnforcementHelper(
                policyEngine = PolicyEngine { PolicyDecision.Allow },
                migrationWarningGuard = AtomicBoolean(false),
            )
        val coordinator =
            ApprovalResumeCoordinator(
                approvalContinuationStore = store,
                suspendedInvocationStore = suspended,
                resumeOperationRegistry = registry,
                toolRegistry = ToolRegistry(mapOf(toolName to tool)),
                toolArgumentsDigester = Sha256ToolArgumentsDigester(),
                approvalLifecycleAuditEmitter = audit,
                engineEventObserver = observer,
                claimService = ContinuationClaimService(store),
                authorizationService = ReplayAuthorizationService(gate, suspended, audit, policy, observer),
            )

        val probe = runBlocking { coordinator.resume(command) }
        assertEquals("executed", probe, "resume must complete the suspended invocation")

        val (meanUs, p50Us, p95Us) =
            BenchmarkHarness.latency(
                operation = "B08-approval-suspend-resume",
                module = "tramai-engine",
                fixture =
                    "ApprovalResumeCoordinator.resume(ResumeApprovalCommand) full pipeline " +
                        "over in-memory continuation/suspended stores, policy Allow, executor no-op",
            ) {
                val result = runBlocking { coordinator.resume(command) }
                assertEquals("executed", result)
            }
        assertTrue(meanUs > 0.0 && p50Us > 0.0 && p95Us >= p50Us)
    }
}

private class BenchContinuationStore(
    private val value: ApprovalContinuation,
    private val input: String,
) : ApprovalContinuationStore {
    override suspend fun create(
        continuation: ApprovalContinuation,
        arguments: SensitiveToolArguments,
    ): ApprovalContinuation = continuation

    override suspend fun get(approvalId: String): ApprovalContinuation? = value

    override suspend fun claimForExecution(
        approvalId: String,
        expectedVersion: Long,
        claimedBy: String,
    ): ClaimedApprovalContinuation = ClaimedApprovalContinuation(value, SensitiveToolArguments.of(input))

    override suspend fun complete(
        approvalId: String,
        expectedVersion: Long,
        completedBy: String,
    ): ApprovalContinuation = value.copy(status = ApprovalContinuationStatus.COMPLETED, version = expectedVersion)

    override suspend fun expire(
        approvalId: String,
        expectedVersion: Long,
    ): ApprovalContinuation = value

    override suspend fun cancel(
        approvalId: String,
        expectedVersion: Long,
    ): ApprovalContinuation = value

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

private class BenchSuspendedStore(
    private val value: SuspendedInvocationMetadata?,
    private val envelope: SensitiveReplayEnvelope?,
) : SuspendedInvocationStore {
    override suspend fun create(
        metadata: SuspendedInvocationMetadata,
        replayEnvelope: SensitiveReplayEnvelope,
    ) = Unit

    override suspend fun get(approvalId: String): SuspendedInvocationMetadata? = value

    override suspend fun revealReplayEnvelope(approvalId: String): SensitiveReplayEnvelope? = envelope

    override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? = value
}

private class BenchGate(
    private val token: ApprovalToken,
) : ApprovalGateCoordinator {
    override suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge =
        ApprovalChallenge("approval-1", token, Instant.MAX)

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

private class BenchAuditEmitter : ApprovalLifecycleAuditEmitter {
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
    ) = Unit

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

private class BenchResumeExecutor : ClaimedResumeExecutor {
    override suspend fun execute(request: ClaimedResumeExecutionRequest): Any? = "executed"
}

private class BenchFakeTool(
    override val name: String,
) : ResolvedTool {
    override val description: String = "test"
    override val inputSchemaJson: String = """{"type":"object"}"""
    override val idempotent: Boolean = false
    override val sideEffectLevel: SideEffectLevel = SideEffectLevel.READ_ONLY
    override val security: ToolSecurityMetadata? = null

    override suspend fun execute(
        input: Any,
        context: ToolExecutionContext,
    ): ToolResult = ToolResult.Success("{}")
}
