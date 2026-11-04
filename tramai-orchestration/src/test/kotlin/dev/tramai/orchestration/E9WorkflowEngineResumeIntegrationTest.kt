package dev.tramai.orchestration

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.approval.ApprovalAuthorization
import dev.tramai.core.approval.ApprovalChallenge
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.ApprovalValidation
import dev.tramai.core.approval.AuthorizeResumeCommand
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.ValidateResumeCommand
import dev.tramai.core.exception.ApprovalResumeAdmissionRefusedException
import dev.tramai.core.exception.ApprovalSuspendedException
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.SuspensionBindingProvenance
import dev.tramai.core.identity.WorkflowCheckpointBinding
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.core.policy.ApprovalRequirement
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.engine.GovernedSuspendedInvocation
import dev.tramai.engine.GovernedSuspendedInvocationStore
import dev.tramai.engine.ResumeApprovalCommand
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.ToolRegistry
import dev.tramai.engine.TramaiEngine
import dev.tramai.security.approval.InMemoryApprovalContinuationStore
import dev.tramai.security.approval.InMemoryApprovalRecoveryCoordinator
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import kotlinx.coroutines.runBlocking
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E9-INT-001: the whole boundary, composed.
 *
 * Every other E9 test proves one half: the adapter against a real checkpoint store, or the coordinator against a fake
 * authority. This one proves the composition — a real [TramaiEngine] suspending inside a real governed workflow run,
 * the binding produced by [WorkflowRunner] rather than by the test, and the resume admitted (or refused) against the
 * same durable row the workflow writes.
 *
 * Two outcomes are asserted, and the durable counters are read from the store rather than inferred from the exception:
 *
 * - an active workflow admits exactly one effect and invokes the tool once;
 * - a cancelled workflow admits nothing, invokes the tool zero times, and closes the claimed continuation through the
 *   audited uncertain transition.
 */
class E9WorkflowEngineResumeIntegrationTest {
    private companion object {
        const val TOOL_NAME = "test_calculator"
        const val WORKFLOW_NAME = "orders"
        const val RUN_ID = "run-1"
        const val ARGUMENTS_DIGEST = "sha256:12e49c0f5b1f1c5a753a1e98fb8e94a06c58b35c8432b77270d412d5d295e3b9"
    }

    @AiService
    interface ApprovalAgentService {
        @Operation(prompt = "Answer", model = "logical-model")
        suspend fun answer(input: String): String
    }

    /**
     * The public SPI, with the governed accessor the engine consults. The in-memory implementation shipped with the
     * engine is internal, and a test double here is honest: what E9 must prove is that the *engine* stamps the binding,
     * not that one particular store class does.
     */
    private class RecordingSuspendedStore(
        private val identity: GovernedRunIdentity,
    ) : GovernedSuspendedInvocationStore {
        private val records = linkedMapOf<String, GovernedSuspendedInvocation>()
        private val envelopes = linkedMapOf<String, SensitiveReplayEnvelope>()

        override suspend fun create(
            metadata: SuspendedInvocationMetadata,
            replayEnvelope: SensitiveReplayEnvelope,
        ) {
            // A plain create() predates the governed capability: no identity, no provenance, nothing to admit.
            records[metadata.approvalId] =
                GovernedSuspendedInvocation(metadata, identity, SuspensionBindingProvenance.LEGACY_UNKNOWN, null)
            envelopes[metadata.approvalId] = replayEnvelope
        }

        override suspend fun get(approvalId: String): SuspendedInvocationMetadata? = records[approvalId]?.metadata

        override suspend fun revealReplayEnvelope(approvalId: String): SensitiveReplayEnvelope? = envelopes[approvalId]

        override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? {
            envelopes.remove(approvalId)
            return records.remove(approvalId)?.metadata
        }

        override suspend fun createGoverned(
            suspended: GovernedSuspendedInvocation,
            replayEnvelope: SensitiveReplayEnvelope,
        ) {
            records[suspended.metadata.approvalId] = suspended
            envelopes[suspended.metadata.approvalId] = replayEnvelope
        }

        override suspend fun governedRunIdentity(approvalId: String) = records[approvalId]?.runIdentity

        override suspend fun governedSuspension(approvalId: String): GovernedSuspendedInvocation? = records[approvalId]
    }

    private class ApprovalPolicyEngine : PolicyEngine {
        override suspend fun evaluate(context: PolicyContext): PolicyDecision =
            if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXECUTION) {
                PolicyDecision.RequireApproval(
                    ApprovalRequirement(
                        toolName = TOOL_NAME,
                        argumentsDigest = ARGUMENTS_DIGEST,
                        reason = "integration",
                        timeoutMillis = 60_000,
                    ),
                )
            } else {
                PolicyDecision.Allow
            }
    }

    private class RecordingProvider : ModelProvider {
        var calls = 0
            private set

        override suspend fun complete(request: ModelRequest): ModelResponse {
            calls++
            return if (calls == 1) {
                ModelResponse(
                    content = "",
                    toolCalls = listOf(ToolCall("call-1", TOOL_NAME, """{"x":2,"y":3}""")),
                )
            } else {
                ModelResponse(content = "Final result: success")
            }
        }

        override fun providerId(): String = "primary"
    }

    private class CountingTool : ResolvedTool {
        override val name: String = TOOL_NAME
        override val description: String = "Calculator tool"
        override val inputSchemaJson: String = ""
        override val idempotent: Boolean = false
        override val sideEffectLevel: SideEffectLevel = SideEffectLevel.READ_ONLY
        var invocations = 0
            private set

        override suspend fun execute(
            input: Any,
            context: ToolExecutionContext,
        ): ToolResult {
            invocations++
            return ToolResult.Success("""{"result":5}""")
        }
    }

    private class PermitGate : ApprovalGateCoordinator {
        private var next = "approval-1"

        override suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge {
            val id = next
            next = "approval-2"
            return ApprovalChallenge(
                approvalId = id,
                token = ApprovalToken.parsePresented("token-$id"),
                expiresAt = Clock.systemUTC().instant().plusSeconds(600),
            )
        }

        override suspend fun validateResume(command: ValidateResumeCommand): ApprovalValidation =
            ApprovalValidation(
                approvalId = command.approvalId,
                validatedBy = command.consumedBy,
                validatedAt = Clock.systemUTC().instant(),
                version = command.expectedVersion,
            )

        override suspend fun authorizeResume(command: AuthorizeResumeCommand): ApprovalAuthorization =
            ApprovalAuthorization(
                approvalId = command.approvalId,
                consumedBy = command.consumedBy,
                consumedAt = Clock.systemUTC().instant(),
                version = command.expectedVersion,
            )

        override suspend fun cancelApproval(
            approvalId: String,
            expectedVersion: Long,
            reason: String,
        ) = Unit
    }

    /** Counts the admissions that actually reached the durable store; the sequence comes back from the store. */
    private class CountingCheckpointStore(
        private val delegate: WorkflowCheckpointStore,
    ) : WorkflowCheckpointStore by delegate {
        var admitted = 0
            private set

        override suspend fun admit(request: WorkflowAdmissionRequest): WorkflowAdmission =
            delegate.admit(request).also { if (it.admitted) admitted++ }
    }

    private object PlainCodec : WorkflowStateCodec<String> {
        override fun encode(state: String): String = state

        override fun decode(payload: String): String = payload
    }

    private fun integrationIdentity(): GovernedRunIdentity =
        GovernedRunIdentity(
            deployment =
                WorkloadDeploymentIdentity(
                    workloadId = WorkloadId("claims"),
                    configuration =
                        WorkloadConfigurationIdentity(
                            id = ConfigurationId("claims-prod"),
                            version = ConfigurationVersion("17"),
                        ),
                    environmentId = EnvironmentId("production"),
                    deploymentId = DeploymentId("eu-west-amsterdam-01"),
                ),
            runId = RunId(RUN_ID),
        )

    private val identity = integrationIdentity()

    private class Fixture(
        val checkpoints: CountingCheckpointStore,
        val suspended: RecordingSuspendedStore,
        val continuations: InMemoryApprovalContinuationStore,
        val tool: CountingTool,
        val engine: TramaiEngine,
    )

    private fun fixture(): Fixture {
        val checkpoints = CountingCheckpointStore(InMemoryWorkflowCheckpointStore())
        val suspended = RecordingSuspendedStore(identity)
        val continuations = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())
        val provider = RecordingProvider()
        val tool = CountingTool()
        // The public constructor is the only composition a consumer has; if the admission could not be installed here,
        // E9 would be a capability no deployment could reach.
        val engine =
            TramaiEngine(
                providerRegistry = ProviderRegistry.singleProvider(provider),
                toolRegistry = ToolRegistry(mapOf(tool.name to tool)),
                policyEngine = ApprovalPolicyEngine(),
                suspendedInvocationStore = suspended,
                approvalContinuationStore = continuations,
                toolArgumentsDigester = Sha256ToolArgumentsDigester(),
                approvalGateCoordinator = PermitGate(),
                // The real orchestration authority over the same durable rows the workflow writes.
                approvalResumeAdmission = WorkflowApprovalResumeAdmission(checkpoints),
                // Closing a refused claim is the recovery coordinator's job; without one the claim would stay CLAIMED.
                approvalRecoveryCoordinator =
                    InMemoryApprovalRecoveryCoordinator(continuations, NoOpApprovalLifecycleAuditEmitter),
            )
        return Fixture(checkpoints, suspended, continuations, tool, engine)
    }

    /**
     * Runs a governed workflow whose second step calls an AI service. The suspension is created inside the step, so the
     * binding is stamped by [WorkflowRunner] — the test never constructs one.
     */
    private fun suspendInsideWorkflow(fixture: Fixture): ApprovalSuspendedException {
        val service = fixture.engine.create(ApprovalAgentService::class)
        val workflow =
            workflow<String>(WORKFLOW_NAME) {
                localStep("prepare") { state, _ -> state }
                localStep("call-agent") { state, _ ->
                    service.answer("go")
                    state
                }
            }.build { it }

        val failure =
            runCatching {
                runBlocking {
                    workflow.run(
                        "s",
                        GovernedRun(WorkflowContext(workflowId = RUN_ID), identity),
                        NoOpWorkflowObserver,
                        WorkflowPersistence(checkpointStore = fixture.checkpoints, stateCodec = PlainCodec),
                    )
                }
            }.exceptionOrNull() ?: error("the workflow run was expected to suspend")

        generationSequence(failure).filterIsInstance<ApprovalSuspendedException>().firstOrNull()?.let { return it }
        error(
            "expected an ApprovalSuspendedException in the failure chain, got: " +
                generationSequence(failure).map { it.javaClass.name },
        )
    }

    private fun generationSequence(failure: Throwable): List<Throwable> {
        val chain = mutableListOf<Throwable>()
        var current: Throwable? = failure
        while (current != null && chain.size < 10) {
            chain += current
            current = current.cause
        }
        return chain
    }

    private fun resumeCommand(suspended: ApprovalSuspendedException): ResumeApprovalCommand =
        ResumeApprovalCommand(
            approvalId = suspended.approvalId,
            approvalExpectedVersion = 0L,
            continuationExpectedVersion = 0L,
            presentedToken = suspended.challenge.token,
            resumedBy = "operator",
        )

    @Test
    fun `an active workflow persists the binding its runner produced and admits the resume exactly once`() =
        runBlocking {
            val fixture = fixture()
            val suspended = suspendInsideWorkflow(fixture)

            // The binding came from the runner's execution scope, not from this test.
            val record = fixture.suspended.governedSuspension(suspended.approvalId)
            assertNotNull(record, "the suspension must be readable through the governed accessor")
            assertEquals(SuspensionBindingProvenance.WORKFLOW_BOUND, record.provenance)
            assertEquals(
                WorkflowCheckpointBinding(workflowName = WORKFLOW_NAME, workflowId = RUN_ID),
                record.checkpointBinding,
                "the persisted binding must be the one the runner was executing under",
            )

            val before = fixture.checkpoints.admitted
            fixture.engine.resumeApproval(resumeCommand(suspended))

            assertEquals(1, fixture.tool.invocations, "the tool executes once, after the admission")
            assertEquals(
                1,
                fixture.checkpoints.admitted - before,
                "the resume grants exactly one durable admission (the workflow's step admissions are not part of it)",
            )
            assertEquals(null, fixture.suspended.get(suspended.approvalId), "the suspension is consumed by the resume")
        }

    @Test
    fun `a cancelled workflow refuses the resume, runs no tool, and closes the claim as uncertain`() =
        runBlocking {
            val fixture = fixture()
            val suspended = suspendInsideWorkflow(fixture)

            val row = assertNotNull(fixture.checkpoints.load(WORKFLOW_NAME, RUN_ID), "the workflow wrote its row")
            fixture.checkpoints.cancel(
                WorkflowCancellationRequest(
                    workflowName = WORKFLOW_NAME,
                    workflowId = row.workflowId,
                    expectedRevision = row.revision,
                    expectedGeneration = row.checkpointGeneration,
                    actor = "operator",
                    reason = "stopped while awaiting approval",
                    cancelledAtEpochMillis = 0L,
                ),
            )

            val before = fixture.checkpoints.admitted
            val failure =
                runCatching { fixture.engine.resumeApproval(resumeCommand(suspended)) }.exceptionOrNull()
            val refusal =
                generationSequence(assertNotNull(failure))
                    .filterIsInstance<ApprovalResumeAdmissionRefusedException>()
                    .firstOrNull()
            assertNotNull(
                refusal,
                "the resume must be refused, got: ${generationSequence(failure).map { it.javaClass.name }}",
            )
            // Not any refusal: the refusal has to be the durable cancellation, so a broken authority cannot satisfy
            // this test by refusing for its own reason.
            assertTrue(
                refusal.message!!.contains("CANCELLED"),
                "the refusal must name the recorded cancellation, got: ${refusal.message}",
            )

            assertEquals(0, fixture.tool.invocations, "the tool must not run for a cancelled workflow")
            assertEquals(
                0,
                fixture.checkpoints.admitted - before,
                "the refused resume grants no durable admission",
            )
            assertEquals(
                ApprovalContinuationStatus.CANCELLED_UNCERTAIN,
                fixture.continuations.get(suspended.approvalId)?.status,
                "the claimed continuation is closed through the audited uncertain transition",
            )
        }
}
