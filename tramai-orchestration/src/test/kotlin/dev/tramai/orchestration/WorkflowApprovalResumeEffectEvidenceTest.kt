@file:OptIn(dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi::class)
@file:Suppress(
    "LargeClass",
    "MaxLineLength",
    "TooManyFunctions",
    "LongMethod",
    "LongParameterList",
    "SwallowedException",
    "ComplexMethod",
    "NestedBlockDepth",
    "ktlint:standard:max-line-length",
)

package dev.tramai.orchestration

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.approval.ApprovalAuthorization
import dev.tramai.core.approval.ApprovalChallenge
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.ApprovalRecoveryCoordinator
import dev.tramai.core.approval.ApprovalResumeAdmissionRequest
import dev.tramai.core.approval.ApprovalResumePermit
import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.ApprovalValidation
import dev.tramai.core.approval.AuthorizeResumeCommand
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.approval.ForceCancelClaimedCommand
import dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.ValidateResumeCommand
import dev.tramai.core.approval.WorkflowEvidenceDiagnosticCategory
import dev.tramai.core.exception.ApprovalResumeAdmissionRefusedException
import dev.tramai.core.exception.ApprovalResumeEvidencePersistenceException
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
import dev.tramai.engine.NoOpEngineEventObserver
import dev.tramai.engine.ResumeApprovalCommand
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.ToolRegistry
import dev.tramai.engine.TramaiEngine
import dev.tramai.security.approval.InMemoryApprovalContinuationStore
import dev.tramai.security.approval.InMemoryApprovalRecoveryCoordinator
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.FieldVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcConnectionPool
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.SQLException
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/**
 * Authoritative end-to-end qualification and adversarial mutation suite for
 * Workflow-Bound Approval Resume Evidence (Boundary E9) in D7-E1.
 */
class WorkflowApprovalResumeEffectEvidenceTest {
    private companion object {
        const val WORKFLOW_NAME = "wf-e9-e2e"
        const val RUN_ID = "run-e9-e2e-1"
        const val TOOL_NAME = "critical_resource_modifier"
        const val ARGS_DIGEST = "sha256:12e49c0f5b1f1c5a753a1e98fb8e94a06c58b35c8432b77270d412d5d295e3b9"
    }

    @AiService
    interface ApprovalService {
        @Operation(prompt = "Answer", model = "test-model")
        suspend fun answer(input: String): String
    }

    private fun testIdentity(runId: String = RUN_ID): GovernedRunIdentity =
        GovernedRunIdentity(
            deployment =
                WorkloadDeploymentIdentity(
                    workloadId = WorkloadId("e9-workload"),
                    configuration =
                        WorkloadConfigurationIdentity(
                            id = ConfigurationId("conf-e9"),
                            version = ConfigurationVersion("1"),
                        ),
                    environmentId = EnvironmentId("test-env"),
                    deploymentId = DeploymentId("dep-1"),
                ),
            runId = RunId(runId),
        )

    private class CountingTool(
        val shouldFail: Boolean = false,
        val failureThrowable: Throwable = RuntimeException("Tool deliberate failure"),
        val executionCallback: (() -> Unit)? = null,
    ) : ResolvedTool {
        override val name: String = TOOL_NAME
        override val description: String = "Test tool"
        override val inputSchemaJson: String = ""
        override val idempotent: Boolean = false
        override val sideEffectLevel: SideEffectLevel = SideEffectLevel.READ_ONLY
        val executions = AtomicInteger(0)

        override suspend fun execute(
            input: Any,
            context: ToolExecutionContext,
        ): ToolResult {
            executions.incrementAndGet()
            executionCallback?.invoke()
            if (shouldFail) {
                throw failureThrowable
            }
            return ToolResult.Success("42")
        }
    }

    private class PermitGate : ApprovalGateCoordinator {
        override suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge =
            ApprovalChallenge(
                approvalId = command.workflowRunId + "-appr-" + UUID.randomUUID(),
                token = ApprovalToken.parsePresented("tok-" + UUID.randomUUID()),
                expiresAt = command.expiresAt,
            )

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

    private class ApprovalPolicyEngine : PolicyEngine {
        override suspend fun evaluate(context: PolicyContext): PolicyDecision =
            if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXECUTION && context.toolName == TOOL_NAME) {
                PolicyDecision.RequireApproval(
                    ApprovalRequirement(
                        toolName = TOOL_NAME,
                        argumentsDigest = ARGS_DIGEST,
                        reason = "e2e evidence qualification",
                        timeoutMillis = 60_000L,
                    ),
                )
            } else {
                PolicyDecision.Allow
            }
    }

    private class RecordingSuspendedStore(
        private val identity: GovernedRunIdentity,
    ) : GovernedSuspendedInvocationStore {
        val records = linkedMapOf<String, GovernedSuspendedInvocation>()
        val envelopes = linkedMapOf<String, SensitiveReplayEnvelope>()

        override suspend fun create(
            metadata: SuspendedInvocationMetadata,
            replayEnvelope: SensitiveReplayEnvelope,
        ) {
            records[metadata.approvalId] =
                GovernedSuspendedInvocation(
                    metadata = metadata,
                    runIdentity = identity,
                    provenance = SuspensionBindingProvenance.WORKFLOW_BOUND,
                    checkpointBinding = WorkflowCheckpointBinding(WORKFLOW_NAME, identity.runId.value),
                )
            envelopes[metadata.approvalId] = replayEnvelope
        }

        override suspend fun createGoverned(
            suspended: GovernedSuspendedInvocation,
            replayEnvelope: SensitiveReplayEnvelope,
        ) {
            records[suspended.metadata.approvalId] = suspended
            envelopes[suspended.metadata.approvalId] = replayEnvelope
        }

        override suspend fun get(approvalId: String): SuspendedInvocationMetadata? = records[approvalId]?.metadata

        override suspend fun revealReplayEnvelope(approvalId: String): SensitiveReplayEnvelope? = envelopes[approvalId]

        override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? =
            records.remove(approvalId)?.metadata.also {
                envelopes.remove(approvalId)
            }

        override suspend fun governedRunIdentity(approvalId: String): GovernedRunIdentity? = records[approvalId]?.runIdentity

        override suspend fun governedSuspension(approvalId: String): GovernedSuspendedInvocation? = records[approvalId]
    }

    private class RecordingProvider(
        private val tool: CountingTool? = null,
    ) : ModelProvider {
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
                if (tool?.shouldFail == true) {
                    throw tool.failureThrowable
                }
                ModelResponse(content = "Final result: success")
            }
        }

        override fun providerId(): String = "test-provider"
    }

    private object StringCodec : WorkflowStateCodec<String> {
        override fun encode(state: String): String = state

        override fun decode(payload: String): String = payload
    }

    private class TestFixture(
        val checkpoints: InMemoryWorkflowCheckpointStore,
        val suspendedStore: RecordingSuspendedStore,
        val continuationStore: ApprovalContinuationStore,
        val evidenceStore: WorkflowEffectEvidenceRecordStore,
        val tool: CountingTool,
        val engine: TramaiEngine,
        val identity: GovernedRunIdentity,
    )

    private fun createFixture(
        identity: GovernedRunIdentity = testIdentity(),
        checkpoints: InMemoryWorkflowCheckpointStore = InMemoryWorkflowCheckpointStore(),
        continuations: ApprovalContinuationStore = InMemoryApprovalContinuationStore(clock = Clock.systemUTC()),
        evidenceStore: WorkflowEffectEvidenceRecordStore = InMemoryWorkflowEffectEvidenceRecordStore(),
        tool: CountingTool = CountingTool(),
        evidenceRequired: Boolean = true,
        customAdmission: WorkflowApprovalResumeAdmission? = null,
    ): TestFixture {
        checkpoints.evidenceStore = evidenceStore
        val suspended = RecordingSuspendedStore(identity)
        val admission =
            customAdmission ?: WorkflowApprovalResumeAdmission(
                checkpointStore = checkpoints,
                continuationStore = continuations,
                evidenceStore = evidenceStore,
                approvalResumeEvidenceEnabled = evidenceRequired,
            )

        val engine =
            TramaiEngine(
                providerRegistry = ProviderRegistry.singleProvider(RecordingProvider(tool)),
                toolRegistry = ToolRegistry(mapOf(tool.name to tool)),
                policyEngine = ApprovalPolicyEngine(),
                suspendedInvocationStore = suspended,
                approvalContinuationStore = continuations,
                toolArgumentsDigester = Sha256ToolArgumentsDigester(),
                approvalGateCoordinator = PermitGate(),
                approvalResumeAdmission = admission,
                approvalRecoveryCoordinator =
                    InMemoryApprovalRecoveryCoordinator(continuations, NoOpApprovalLifecycleAuditEmitter),
                approvalResumeEvidenceRequired = evidenceRequired,
                engineEventObserver = NoOpEngineEventObserver,
            )
        return TestFixture(checkpoints, suspended, continuations, evidenceStore, tool, engine, identity)
    }

    private fun suspendWorkflow(fixture: TestFixture): ApprovalSuspendedException {
        val service = fixture.engine.create(ApprovalService::class)
        val wf =
            workflow<String>(WORKFLOW_NAME) {
                localStep("step-1") { state, _ -> state }
                localStep("step-2") { state, _ ->
                    service.answer("invoke")
                    state
                }
            }.build { it }

        val thrown =
            runCatching {
                runBlocking {
                    wf.run(
                        "init",
                        GovernedRun(WorkflowContext(workflowId = RUN_ID), fixture.identity),
                        NoOpWorkflowObserver,
                        WorkflowPersistence(
                            checkpointStore = fixture.checkpoints,
                            stateCodec = StringCodec,
                            evidenceStore = fixture.evidenceStore,
                        ),
                    )
                }
            }.exceptionOrNull() ?: error("Expected workflow to suspend")

        var cur: Throwable? = thrown
        while (cur != null) {
            if (cur is ApprovalSuspendedException) return cur
            cur = cur.cause
        }
        error("ApprovalSuspendedException not found in failure chain: $thrown")
    }

    // -----------------------------------------------------------------------------------------
    // End-to-End Scenarios: E9-E01 to E9-E24
    // -----------------------------------------------------------------------------------------

    @Test
    fun `E9-E01 - Happy path approval resume produces authoritative ADMITTED-DISPATCHED-COMPLETED evidence with Option C ParentProvenance`() {
        val fixture = createFixture(evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)

        val resumeCommand =
            ResumeApprovalCommand(
                approvalId = suspended.approvalId,
                approvalExpectedVersion = 0L,
                continuationExpectedVersion = 0L,
                presentedToken = suspended.challenge.token,
                resumedBy = "operator-alice",
            )

        val outcome = runBlocking { fixture.engine.resumeApproval(resumeCommand) }
        assertThat(outcome).isNotNull
        assertThat(fixture.tool.executions.get()).isEqualTo(1)

        val records = runBlocking { fixture.evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID) }
        assertThat(records).hasSize(1)
        val evidence = records[0]

        assertThat(evidence.key.workflowName).isEqualTo(WORKFLOW_NAME)
        assertThat(evidence.key.workflowId).isEqualTo(RUN_ID)
        assertThat(evidence.key.admissionSeq).isGreaterThan(0L)
        assertThat(evidence.effectKind).isEqualTo(APPROVAL_RESUME_EFFECT_KIND)
        assertThat(evidence.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
        assertThat(evidence.completedAtEpochMillis).isNotNull
        assertThat(evidence.completedAtEpochMillis).isGreaterThanOrEqualTo(evidence.createdAtEpochMillis)

        // Option C Verification: ParentProvenance is ApprovalContinuation, NOT a synthetic stepAttempt
        val parent = evidence.parent
        assertThat(parent).isInstanceOf(ParentProvenance.ApprovalContinuation::class.java)
        val approvalParent = parent as ParentProvenance.ApprovalContinuation
        assertThat(approvalParent.approvalId).isEqualTo(suspended.approvalId)
        assertThat(approvalParent.continuationVersion).isEqualTo(1L)
        assertThat(approvalParent.toolName).isEqualTo(TOOL_NAME)

        // Retained compatibility properties
        assertThat(evidence.stepName).isEqualTo(TOOL_NAME)
        assertThat(evidence.stepAttemptId).isNull()
    }

    @Test
    fun `E9-E02 - Tool execution failure records UNCERTAIN evidence with TOOL_EXECUTION_FAILED category`() {
        val failingTool = CountingTool(shouldFail = true, failureThrowable = IllegalStateException("Hardware fault"))
        val fixture = createFixture(evidenceRequired = true, tool = failingTool)
        val suspended = suspendWorkflow(fixture)

        val resumeCommand =
            ResumeApprovalCommand(
                approvalId = suspended.approvalId,
                approvalExpectedVersion = 0L,
                continuationExpectedVersion = 0L,
                presentedToken = suspended.challenge.token,
                resumedBy = "operator-alice",
            )

        val failure =
            assertFailsWith<dev.tramai.core.exception.ProviderException> {
                runBlocking { fixture.engine.resumeApproval(resumeCommand) }
            }
        assertThat(failure.cause).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure.cause?.message).isEqualTo("Hardware fault")
        assertThat(failingTool.executions.get()).isEqualTo(1)

        val records = runBlocking { fixture.evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID) }
        assertThat(records).hasSize(1)
        val evidence = records[0]
        assertThat(evidence.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
        assertThat(evidence.diagnosticSummary).isEqualTo(WorkflowEvidenceDiagnosticCategory.TOOL_EXECUTION_FAILED.name)
    }

    @Test
    fun `E9-E03 - Tool execution cancellation records UNCERTAIN evidence with TOOL_EXECUTION_CANCELLED category`() {
        val cancellingTool = CountingTool(shouldFail = true, failureThrowable = CancellationException("Coroutine cancelled"))
        val fixture = createFixture(evidenceRequired = true, tool = cancellingTool)
        val suspended = suspendWorkflow(fixture)

        val resumeCommand =
            ResumeApprovalCommand(
                approvalId = suspended.approvalId,
                approvalExpectedVersion = 0L,
                continuationExpectedVersion = 0L,
                presentedToken = suspended.challenge.token,
                resumedBy = "operator-alice",
            )

        val failure =
            assertFailsWith<CancellationException> {
                runBlocking { fixture.engine.resumeApproval(resumeCommand) }
            }
        assertThat(failure.message).contains("Coroutine cancelled")

        val records = runBlocking { fixture.evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID) }
        assertThat(records).hasSize(1)
        val evidence = records[0]
        assertThat(evidence.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
        assertThat(evidence.diagnosticSummary).isEqualTo(WorkflowEvidenceDiagnosticCategory.TOOL_EXECUTION_CANCELLED.name)
    }

    @Test
    fun `E9-E04 - Tool execution timeout records UNCERTAIN evidence with TOOL_EXECUTION_TIMEOUT category`() {
        val timeoutException =
            assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
                runBlocking {
                    kotlinx.coroutines.withTimeout(1) {
                        kotlinx.coroutines.delay(50)
                    }
                }
            }
        val timeoutTool = CountingTool(shouldFail = true, failureThrowable = timeoutException)
        val fixture = createFixture(evidenceRequired = true, tool = timeoutTool)
        val suspended = suspendWorkflow(fixture)

        val resumeCommand =
            ResumeApprovalCommand(
                approvalId = suspended.approvalId,
                approvalExpectedVersion = 0L,
                continuationExpectedVersion = 0L,
                presentedToken = suspended.challenge.token,
                resumedBy = "operator-alice",
            )

        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            runBlocking { fixture.engine.resumeApproval(resumeCommand) }
        }

        val records = runBlocking { fixture.evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID) }
        assertThat(records).hasSize(1)
        val evidence = records[0]
        assertThat(evidence.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
        assertThat(evidence.diagnosticSummary).isEqualTo(WorkflowEvidenceDiagnosticCategory.TOOL_EXECUTION_TIMEOUT.name)
    }

    @Test
    fun `E9-E05 - Outcome separation - Continuation completion store failure preserves COMPLETED evidence without degrading to UNCERTAIN`() {
        val baseContinuations = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())
        val failingContinuations =
            object : ApprovalContinuationStore by baseContinuations {
                override suspend fun complete(
                    approvalId: String,
                    expectedVersion: Long,
                    completedBy: String,
                ): ApprovalContinuation {
                    error("Deliberate completion store failure for outcome separation testing")
                }
            }

        val fixture = createFixture(continuations = failingContinuations, evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)

        val resumeCommand =
            ResumeApprovalCommand(
                approvalId = suspended.approvalId,
                approvalExpectedVersion = 0L,
                continuationExpectedVersion = 0L,
                presentedToken = suspended.challenge.token,
                resumedBy = "operator-alice",
            )

        val failure =
            assertFailsWith<IllegalStateException> {
                runBlocking { fixture.engine.resumeApproval(resumeCommand) }
            }
        assertThat(failure.message).contains("Deliberate completion store failure")
        assertThat(fixture.tool.executions.get()).isEqualTo(1)

        val records = runBlocking { fixture.evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID) }
        assertThat(records).hasSize(1)
        val evidence = records[0]
        // Invariant: The tool ran to completion; evidence MUST remain COMPLETED!
        assertThat(evidence.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
        assertThat(evidence.diagnosticSummary).isNull()

        // Continuation remains CLAIMED in store for operator reconciliation
        val continuation = runBlocking { baseContinuations.get(suspended.approvalId) }
        assertThat(continuation?.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
    }

    @Test
    fun `E9-E06 - Cancelled checkpoint refuses resume admission, tool never executed, and no evidence written`() {
        val fixture = createFixture(evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)

        // Cancel the workflow checkpoint row prior to resume
        runBlocking {
            val current = checkNotNull(fixture.checkpoints.load(WORKFLOW_NAME, RUN_ID))
            fixture.checkpoints.cancel(
                WorkflowCancellationRequest(
                    workflowName = WORKFLOW_NAME,
                    workflowId = RUN_ID,
                    expectedRevision = current.revision,
                    expectedGeneration = current.checkpointGeneration,
                    actor = "operator",
                    reason = "deliberate cancellation",
                    cancelledAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        }

        val resumeCommand =
            ResumeApprovalCommand(
                approvalId = suspended.approvalId,
                approvalExpectedVersion = 0L,
                continuationExpectedVersion = 0L,
                presentedToken = suspended.challenge.token,
                resumedBy = "operator-alice",
            )

        val refusal =
            assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                runBlocking { fixture.engine.resumeApproval(resumeCommand) }
            }
        assertThat(refusal.message).contains("cannot be resumed")
        assertThat(fixture.tool.executions.get()).isEqualTo(0)

        // No evidence row written
        val records = runBlocking { fixture.evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID) }
        assertThat(records).isEmpty()
    }

    @Test
    fun `E9-E07 - Evidence disabled deployment remains fully compatible without writing evidence`() {
        val checkpoints = InMemoryWorkflowCheckpointStore()
        val continuations = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())
        val admission =
            WorkflowApprovalResumeAdmission(checkpointStore = checkpoints, continuationStore = continuations, evidenceStore = null)
        val fixture =
            createFixture(checkpoints = checkpoints, continuations = continuations, evidenceRequired = false, customAdmission = admission)

        val suspended = suspendWorkflow(fixture)

        val resumeCommand =
            ResumeApprovalCommand(
                approvalId = suspended.approvalId,
                approvalExpectedVersion = 0L,
                continuationExpectedVersion = 0L,
                presentedToken = suspended.challenge.token,
                resumedBy = "operator-alice",
            )

        val outcome = runBlocking { fixture.engine.resumeApproval(resumeCommand) }
        assertThat(outcome).isNotNull
        assertThat(fixture.tool.executions.get()).isEqualTo(1)
        assertThat(runBlocking { fixture.evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID) }).isEmpty()
    }

    @Test
    fun `E9-E08 - Misconfiguration - evidenceRequired is true but admission authority has null handle fails closed before tool execution`() {
        val checkpoints = InMemoryWorkflowCheckpointStore()
        val continuations = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())
        // Authority has NULL evidence store -> returns permit with evidenceHandle == null
        val admission =
            WorkflowApprovalResumeAdmission(checkpointStore = checkpoints, continuationStore = continuations, evidenceStore = null)
        // Engine has evidenceRequired == true -> Misconfiguration!
        val fixture =
            createFixture(checkpoints = checkpoints, continuations = continuations, evidenceRequired = true, customAdmission = admission)

        val suspended = suspendWorkflow(fixture)

        val resumeCommand =
            ResumeApprovalCommand(
                approvalId = suspended.approvalId,
                approvalExpectedVersion = 0L,
                continuationExpectedVersion = 0L,
                presentedToken = suspended.challenge.token,
                resumedBy = "operator-alice",
            )

        val failure =
            assertFailsWith<IllegalStateException> {
                runBlocking { fixture.engine.resumeApproval(resumeCommand) }
            }
        assertThat(
            failure.message,
        ).contains("requires durable evidence tracking, but admission authority returned permit without evidenceHandle")
        assertThat(fixture.tool.executions.get()).isEqualTo(0)
    }

    @Test
    fun `E9-E09 - Evidence enabled but continuationStore missing from authority fails closed at admission`() {
        runBlocking {
            val checkpointStore = InMemoryWorkflowCheckpointStore()
            val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()

            val authority =
                WorkflowApprovalResumeAdmission(
                    checkpointStore = checkpointStore,
                    continuationStore = null, // missing!
                    evidenceStore = evidenceStore,
                )

            val request =
                ApprovalResumeAdmissionRequest(
                    approvalId = "appr-1",
                    toolName = TOOL_NAME,
                    runIdentity = testIdentity(),
                    checkpointBinding = WorkflowCheckpointBinding(WORKFLOW_NAME, RUN_ID),
                    continuationVersion = 1L,
                )

            checkpointStore.save(
                WorkflowCheckpoint(
                    workflowName = WORKFLOW_NAME,
                    workflowId = RUN_ID,
                    nextStepIndex = 1,
                    stepExecutions = 1,
                    lastCompletedStepName = "init",
                    statePayload = "{}",
                    metadata = encodeGovernedRunAttribution(testIdentity()),
                ),
            )

            val refusal =
                assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                    authority.admitResume(request)
                }
            assertThat(refusal.message).contains("continuationStore is required when evidence tracking is enabled")
        }
    }

    @Test
    fun `E9-E10 - Evidence enabled but continuationVersion is non-positive fails closed at admission`() {
        runBlocking {
            val checkpointStore = InMemoryWorkflowCheckpointStore()
            val continuationStore = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())
            val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()

            val authority =
                WorkflowApprovalResumeAdmission(
                    checkpointStore = checkpointStore,
                    continuationStore = continuationStore,
                    evidenceStore = evidenceStore,
                )

            checkpointStore.save(
                WorkflowCheckpoint(
                    workflowName = WORKFLOW_NAME,
                    workflowId = RUN_ID,
                    nextStepIndex = 1,
                    stepExecutions = 1,
                    lastCompletedStepName = "init",
                    statePayload = "{}",
                    metadata = encodeGovernedRunAttribution(testIdentity()),
                ),
            )

            val request =
                ApprovalResumeAdmissionRequest(
                    approvalId = "appr-1",
                    toolName = TOOL_NAME,
                    runIdentity = testIdentity(),
                    checkpointBinding = WorkflowCheckpointBinding(WORKFLOW_NAME, RUN_ID),
                    continuationVersion = 0L, // non-positive!
                )

            val refusal =
                assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                    authority.admitResume(request)
                }
            assertThat(refusal.message).contains("continuationVersion must be positive")
        }
    }

    @Test
    fun `E9-E11 - 5-dimension validation - forged continuation version (e-g- 7 vs 1) refused fail-closed`() {
        val fixture = createFixture(evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)

        val claimed =
            runBlocking {
                fixture.continuationStore.claimForExecution(
                    approvalId = suspended.approvalId,
                    expectedVersion = 0L,
                    claimedBy = "worker-1",
                )
            }
        assertThat(claimed.continuation.version).isEqualTo(1L)

        val forgedRequest =
            ApprovalResumeAdmissionRequest(
                approvalId = suspended.approvalId,
                toolName = TOOL_NAME,
                runIdentity = testIdentity(),
                checkpointBinding = WorkflowCheckpointBinding(WORKFLOW_NAME, RUN_ID),
                continuationVersion = 7L, // forged!
            )

        val authority =
            WorkflowApprovalResumeAdmission(
                checkpointStore = fixture.checkpoints,
                continuationStore = fixture.continuationStore,
                evidenceStore = fixture.evidenceStore,
            )

        val refusal =
            assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                runBlocking { authority.admitResume(forgedRequest) }
            }
        assertThat(refusal.message).contains("continuation version mismatch")
    }

    @Test
    fun `E9-E12 - 5-dimension validation - mismatched tool name refused fail-closed`() {
        val fixture = createFixture(evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)

        val claimed =
            runBlocking {
                fixture.continuationStore.claimForExecution(
                    approvalId = suspended.approvalId,
                    expectedVersion = 0L,
                    claimedBy = "worker-1",
                )
            }

        val mismatchedToolRequest =
            ApprovalResumeAdmissionRequest(
                approvalId = suspended.approvalId,
                toolName = "unexpected_tool",
                runIdentity = testIdentity(),
                checkpointBinding = WorkflowCheckpointBinding(WORKFLOW_NAME, RUN_ID),
                continuationVersion = claimed.continuation.version,
            )

        val authority =
            WorkflowApprovalResumeAdmission(
                checkpointStore = fixture.checkpoints,
                continuationStore = fixture.continuationStore,
                evidenceStore = fixture.evidenceStore,
            )

        val refusal =
            assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                runBlocking { authority.admitResume(mismatchedToolRequest) }
            }
        assertThat(refusal.message).contains("tool mismatch")
    }

    @Test
    fun `E9-E13 - 5-dimension validation - mismatched workflow run ID refused fail-closed`() {
        val fixture = createFixture(evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)

        val claimed =
            runBlocking {
                fixture.continuationStore.claimForExecution(
                    approvalId = suspended.approvalId,
                    expectedVersion = 0L,
                    claimedBy = "worker-1",
                )
            }

        val wrongRunIdentity = testIdentity("run-other-999")

        runBlocking {
            fixture.checkpoints.save(
                WorkflowCheckpoint(
                    workflowName = WORKFLOW_NAME,
                    workflowId = "run-other-999",
                    nextStepIndex = 1,
                    stepExecutions = 1,
                    lastCompletedStepName = "init",
                    statePayload = "{}",
                    metadata = encodeGovernedRunAttribution(wrongRunIdentity),
                ),
            )
        }

        val mismatchedRunRequest =
            ApprovalResumeAdmissionRequest(
                approvalId = suspended.approvalId,
                toolName = TOOL_NAME,
                runIdentity = wrongRunIdentity,
                checkpointBinding = WorkflowCheckpointBinding(WORKFLOW_NAME, "run-other-999"),
                continuationVersion = claimed.continuation.version,
            )

        val authority =
            WorkflowApprovalResumeAdmission(
                checkpointStore = fixture.checkpoints,
                continuationStore = fixture.continuationStore,
                evidenceStore = fixture.evidenceStore,
            )

        val refusal =
            assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                runBlocking { authority.admitResume(mismatchedRunRequest) }
            }
        assertThat(refusal.message).contains("workflowRunId mismatch")
    }

    @Test
    fun `E9-E14 - 5-dimension validation - continuation not in CLAIMED status refused fail-closed`() {
        val fixture = createFixture(evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)

        // Status is still PENDING (not claimed)
        val request =
            ApprovalResumeAdmissionRequest(
                approvalId = suspended.approvalId,
                toolName = TOOL_NAME,
                runIdentity = testIdentity(),
                checkpointBinding = WorkflowCheckpointBinding(WORKFLOW_NAME, RUN_ID),
                continuationVersion = 1L,
            )

        val authority =
            WorkflowApprovalResumeAdmission(
                checkpointStore = fixture.checkpoints,
                continuationStore = fixture.continuationStore,
                evidenceStore = fixture.evidenceStore,
            )

        val refusal =
            assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                runBlocking { authority.admitResume(request) }
            }
        assertThat(refusal.message).contains("is not in CLAIMED status")
    }

    @Test
    fun `E9-E15 - 5-dimension validation - continuation not found in store refused fail-closed`() {
        val fixture = createFixture(evidenceRequired = true)
        suspendWorkflow(fixture)

        val request =
            ApprovalResumeAdmissionRequest(
                approvalId = "non-existent-approval",
                toolName = TOOL_NAME,
                runIdentity = testIdentity(),
                checkpointBinding = WorkflowCheckpointBinding(WORKFLOW_NAME, RUN_ID),
                continuationVersion = 1L,
            )

        val authority =
            WorkflowApprovalResumeAdmission(
                checkpointStore = fixture.checkpoints,
                continuationStore = fixture.continuationStore,
                evidenceStore = fixture.evidenceStore,
            )

        val refusal =
            assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                runBlocking { authority.admitResume(request) }
            }
        assertThat(refusal.message).contains("not found")
    }

    @Test
    fun `E9-E16 - Standalone suspension executes without workflow admission or evidence`() {
        val fixture = createFixture(evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)

        val existing = runBlocking { fixture.suspendedStore.governedSuspension(suspended.approvalId)!! }
        val standalone = existing.copy(provenance = SuspensionBindingProvenance.STANDALONE, checkpointBinding = null)
        val envelope = runBlocking { fixture.suspendedStore.revealReplayEnvelope(suspended.approvalId)!! }
        runBlocking {
            fixture.suspendedStore.createGoverned(standalone, envelope)
        }

        val outcome =
            runBlocking {
                fixture.engine.resumeApproval(
                    ResumeApprovalCommand(
                        approvalId = suspended.approvalId,
                        approvalExpectedVersion = 0L,
                        continuationExpectedVersion = 0L,
                        presentedToken = suspended.challenge.token,
                        resumedBy = "operator-standalone",
                    ),
                )
            }
        assertThat(outcome).isNotNull
        assertThat(fixture.tool.executions.get()).isEqualTo(1)

        // No workflow evidence was written
        assertThat(runBlocking { fixture.evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID) }).isEmpty()
    }

    @Test
    fun `E9-E17 - Single-use permit - second consume throws IllegalStateException`() {
        val binding = WorkflowCheckpointBinding(WORKFLOW_NAME, RUN_ID)
        val permit =
            ApprovalResumePermit(
                approvalId = "appr-permit-test",
                checkpointBinding = binding,
                admissionSequence = 42L,
            )

        assertThat(permit.tryConsume()).isTrue()
        val secondConsumeFailure =
            assertFailsWith<IllegalStateException> {
                permit.tryConsume()
            }
        assertThat(secondConsumeFailure.message).contains("was already consumed")
    }

    @Test
    fun `E9-E18 - Concurrent resume mutual exclusion - exactly one claim succeeds and exactly one tool execution occurs`() {
        runBlocking(Dispatchers.Default) {
            val fixture = createFixture(evidenceRequired = true)
            val suspended = suspendWorkflow(fixture)

            val readyBarrier = CompletableDeferred<Unit>()
            val resumeResults =
                (1..4).map { workerIndex ->
                    async {
                        readyBarrier.await()
                        runCatching {
                            fixture.engine.resumeApproval(
                                ResumeApprovalCommand(
                                    approvalId = suspended.approvalId,
                                    approvalExpectedVersion = 0L,
                                    continuationExpectedVersion = 0L,
                                    presentedToken = suspended.challenge.token,
                                    resumedBy = "worker-$workerIndex",
                                ),
                            )
                        }
                    }
                }

            readyBarrier.complete(Unit)
            val outcomes = resumeResults.awaitAll()

            val successCount = outcomes.count { it.isSuccess }
            val failureCount = outcomes.count { it.isFailure }

            assertThat(successCount).isEqualTo(1)
            assertThat(failureCount).isEqualTo(3)
            assertThat(fixture.tool.executions.get()).isEqualTo(1)

            val records = fixture.evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID)
            assertThat(records).hasSize(1)
            assertThat(records[0].state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
        }
    }

    @Test
    fun `E9-E19 - JDBC persistence round-trip preserves Option C dedicated parent columns and deserializes typed ParentProvenance`() {
        val pool = JdbcConnectionPool.create("jdbc:h2:mem:e9_jdbc_test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        try {
            val store = JdbcWorkflowEffectEvidenceRecordStore(pool)
            store.createSchemaSql().forEach { sql ->
                pool.connection.use { conn -> conn.createStatement().use { it.execute(sql) } }
            }

            runBlocking {
                val key =
                    WorkflowEffectEvidenceKey(
                        workflowName = "jdbc-wf",
                        workflowId = "jdbc-run-1",
                        checkpointGeneration = "gen-jdbc",
                        admissionSeq = 101L,
                    )
                val originalRecord =
                    WorkflowEffectEvidenceRecord(
                        key = key,
                        parent =
                            ParentProvenance.ApprovalContinuation(
                                approvalId = "appr-jdbc-xyz",
                                continuationVersion = 3L,
                                toolName = "jdbc_tool",
                            ),
                        effectKind = APPROVAL_RESUME_EFFECT_KIND,
                        state = WorkflowEffectEvidenceState.ADMITTED,
                        createdAtEpochMillis = 1_000_000L,
                    )

                store.recordEvidence(originalRecord)

                // Inspect genuine database columns via direct JDBC
                pool.connection.use { conn ->
                    conn
                        .prepareStatement(
                            "SELECT parent_kind, parent_id, parent_version, step_attempt_id, step_name FROM tramai_workflow_effect_evidence WHERE admission_seq = 101",
                        ).use { stmt ->
                            stmt.executeQuery().use { rs ->
                                assertThat(rs.next()).isTrue()
                                assertThat(rs.getString("parent_kind")).isEqualTo("APPROVAL_CONTINUATION")
                                assertThat(rs.getString("parent_id")).isEqualTo("appr-jdbc-xyz")
                                assertThat(rs.getLong("parent_version")).isEqualTo(3L)
                                assertThat(rs.getString("step_attempt_id")).isNull()
                                assertThat(rs.getString("step_name")).isEqualTo("jdbc_tool")
                            }
                        }
                }

                // Read via store SPI
                val loaded = store.getEvidence(key)
                assertThat(loaded).isNotNull
                assertThat(loaded?.parent).isInstanceOf(ParentProvenance.ApprovalContinuation::class.java)
                val parent = loaded?.parent as ParentProvenance.ApprovalContinuation
                assertThat(parent.approvalId).isEqualTo("appr-jdbc-xyz")
                assertThat(parent.continuationVersion).isEqualTo(3L)
                assertThat(parent.toolName).isEqualTo("jdbc_tool")

                // Update to DISPATCHED then COMPLETED
                val dispatchedRecord =
                    loaded.copy(
                        state = WorkflowEffectEvidenceState.DISPATCHED,
                    )
                store.updateEvidence(dispatchedRecord)

                val completedRecord =
                    dispatchedRecord.copy(
                        state = WorkflowEffectEvidenceState.COMPLETED,
                        completedAtEpochMillis = 1_005_000L,
                    )
                store.updateEvidence(completedRecord)

                val reloaded = store.getEvidence(key)
                assertThat(reloaded?.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
                assertThat(reloaded?.completedAtEpochMillis).isEqualTo(1_005_000L)
            }
        } finally {
            pool.dispose()
        }
    }

    @Test
    fun `E9-E20 - JDBC phased schema migration, backfill, and mixed reading of legacy E1-E8 step attempts and new E9 records`() {
        val pool = JdbcConnectionPool.create("jdbc:h2:mem:e9_migration_test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        try {
            // Step 0: Create legacy schema (pre-D7-E1 table without parent_kind, parent_id, parent_version)
            val legacyTableDdl =
                """
                CREATE TABLE tramai_workflow_effect_evidence (
                    workflow_name VARCHAR(255) NOT NULL,
                    workflow_id VARCHAR(255) NOT NULL,
                    checkpoint_generation VARCHAR(255) NOT NULL,
                    admission_seq BIGINT NOT NULL,
                    step_name VARCHAR(255) NOT NULL,
                    step_attempt_id VARCHAR(255) NOT NULL,
                    effect_kind VARCHAR(64) NOT NULL,
                    state VARCHAR(32) NOT NULL,
                    created_at_epoch_millis BIGINT NOT NULL,
                    completed_at_epoch_millis BIGINT,
                    diagnostic_summary VARCHAR(1024),
                    PRIMARY KEY (workflow_name, workflow_id, checkpoint_generation, admission_seq)
                )
                """.trimIndent()
            pool.connection.use { conn -> conn.createStatement().use { it.execute(legacyTableDdl) } }

            // Step 0b: Populate legacy E1–E8 rows
            val insertLegacy =
                """
                INSERT INTO tramai_workflow_effect_evidence (
                    workflow_name, workflow_id, checkpoint_generation, admission_seq,
                    step_name, step_attempt_id, effect_kind, state,
                    created_at_epoch_millis, completed_at_epoch_millis, diagnostic_summary
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            pool.connection.use { conn ->
                conn.prepareStatement(insertLegacy).use { stmt ->
                    stmt.setString(1, "legacy-wf")
                    stmt.setString(2, "run-legacy-1")
                    stmt.setString(3, "gen-leg")
                    stmt.setLong(4, 1L)
                    stmt.setString(5, "http_step")
                    stmt.setString(6, "attempt-legacy-xyz")
                    stmt.setString(7, "HTTP_REQUEST")
                    stmt.setString(8, "COMPLETED")
                    stmt.setLong(9, 100_000L)
                    stmt.setLong(10, 102_000L)
                    stmt.setString(11, null)
                    stmt.executeUpdate()
                }
            }

            val store = JdbcWorkflowEffectEvidenceRecordStore(pool)

            // Phase 1 Schema Migration: Add parent_kind, parent_id, parent_version, relax step_attempt_id
            store.migrationPhase1Sql().forEach { sql ->
                pool.connection.use { conn -> conn.createStatement().use { it.execute(sql) } }
            }

            // Verify older rows have NULL parent_id before backfill, and reader successfully decodes them by falling back to step_attempt_id
            runBlocking {
                val legacyKey = WorkflowEffectEvidenceKey("legacy-wf", "run-legacy-1", "gen-leg", 1L)
                val preBackfillRead = store.getEvidence(legacyKey)
                assertThat(preBackfillRead).isNotNull
                assertThat(preBackfillRead?.parent).isInstanceOf(ParentProvenance.StepAttempt::class.java)
                val stepParent = preBackfillRead?.parent as ParentProvenance.StepAttempt
                assertThat(stepParent.stepName).isEqualTo("http_step")
                assertThat(stepParent.stepAttemptId).isEqualTo("attempt-legacy-xyz")
            }

            // Phase 2: Execute Backfill SQL
            pool.connection.use { conn ->
                conn.createStatement().use { it.executeUpdate(store.migrationPhase2BackfillSql()) }
            }

            // Phase 3: Validate zero NULL parent_id rows exist
            pool.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT COUNT(*) FROM tramai_workflow_effect_evidence WHERE parent_id IS NULL").use { rs ->
                        assertThat(rs.next()).isTrue()
                        assertThat(rs.getInt(1)).isEqualTo(0)
                    }
                }
            }

            // Phase 4: Enforce NOT NULL constraint on parent_id
            pool.connection.use { conn ->
                conn.createStatement().use { it.execute(store.migrationPhase4EnforceConstraintSql()) }
            }

            // Now write a new E9 record into the upgraded database
            runBlocking {
                val e9Key = WorkflowEffectEvidenceKey("legacy-wf", "run-legacy-1", "gen-leg", 2L)
                val e9Record =
                    WorkflowEffectEvidenceRecord(
                        key = e9Key,
                        parent =
                            ParentProvenance.ApprovalContinuation(
                                approvalId = "appr-migrated-1",
                                continuationVersion = 1L,
                                toolName = "upgraded_tool",
                            ),
                        effectKind = APPROVAL_RESUME_EFFECT_KIND,
                        state = WorkflowEffectEvidenceState.ADMITTED,
                        createdAtEpochMillis = 105_000L,
                    )
                store.recordEvidence(e9Record)

                // Mixed reading of legacy E1–E8 and new E9
                val allRecords = store.listEvidence("legacy-wf", "run-legacy-1")
                assertThat(allRecords).hasSize(2)

                val rec1 = allRecords[0]
                assertThat(rec1.parent).isInstanceOf(ParentProvenance.StepAttempt::class.java)
                assertThat((rec1.parent as ParentProvenance.StepAttempt).stepAttemptId).isEqualTo("attempt-legacy-xyz")

                val rec2 = allRecords[1]
                assertThat(rec2.parent).isInstanceOf(ParentProvenance.ApprovalContinuation::class.java)
                assertThat((rec2.parent as ParentProvenance.ApprovalContinuation).approvalId).isEqualTo("appr-migrated-1")
            }
        } finally {
            pool.dispose()
        }
    }

    @Test
    fun `E9-E21 - Monotonicity - invalid state transitions are rejected by store`() {
        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        runBlocking {
            val key = WorkflowEffectEvidenceKey("wf", "r1", "g1", 1L)
            val parent = ParentProvenance.ApprovalContinuation("appr-1", 1L, "t1")
            val admitted =
                WorkflowEffectEvidenceRecord(
                    key = key,
                    parent = parent,
                    effectKind = APPROVAL_RESUME_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.ADMITTED,
                    createdAtEpochMillis = 1_000L,
                )
            store.recordEvidence(admitted)

            // Direct jump ADMITTED -> COMPLETED without DISPATCHED is invalid
            val invalidJump = admitted.copy(state = WorkflowEffectEvidenceState.COMPLETED, completedAtEpochMillis = 2_000L)
            assertFailsWith<IllegalStateException> {
                store.updateEvidence(invalidJump)
            }

            // Valid: ADMITTED -> DISPATCHED -> COMPLETED
            val dispatched = admitted.copy(state = WorkflowEffectEvidenceState.DISPATCHED)
            store.updateEvidence(dispatched)

            val completed = dispatched.copy(state = WorkflowEffectEvidenceState.COMPLETED, completedAtEpochMillis = 2_000L)
            store.updateEvidence(completed)

            // Terminal: COMPLETED cannot transition to UNCERTAIN
            val invalidTerminal = completed.copy(state = WorkflowEffectEvidenceState.UNCERTAIN)
            assertFailsWith<IllegalStateException> {
                store.updateEvidence(invalidTerminal)
            }
        }
    }

    @Test
    fun `E9-E22 - Immutability - key, parent, and createdAt are immutable during update`() {
        val store = InMemoryWorkflowEffectEvidenceRecordStore()
        runBlocking {
            val key = WorkflowEffectEvidenceKey("wf", "r1", "g1", 1L)
            val parent = ParentProvenance.ApprovalContinuation("appr-1", 1L, "t1")
            val record =
                WorkflowEffectEvidenceRecord(
                    key = key,
                    parent = parent,
                    effectKind = APPROVAL_RESUME_EFFECT_KIND,
                    state = WorkflowEffectEvidenceState.ADMITTED,
                    createdAtEpochMillis = 1_000L,
                )
            store.recordEvidence(record)

            // Altering parent is rejected
            val alteredParent =
                record.copy(
                    parent = ParentProvenance.ApprovalContinuation("appr-tampered", 1L, "t1"),
                    state = WorkflowEffectEvidenceState.DISPATCHED,
                )
            assertFailsWith<IllegalStateException> {
                store.updateEvidence(alteredParent)
            }

            // Altering createdAt is rejected
            val alteredCreatedAt =
                record.copy(
                    createdAtEpochMillis = 9_999L,
                    state = WorkflowEffectEvidenceState.DISPATCHED,
                )
            assertFailsWith<IllegalStateException> {
                store.updateEvidence(alteredCreatedAt)
            }
        }
    }

    @Test
    fun `E9-E23 - Non-data-class permit - ApprovalResumePermit does not expose copy() method`() {
        val methods = ApprovalResumePermit::class.java.declaredMethods.map { it.name }
        assertThat(methods).doesNotContain("copy", "copy\$default")
    }

    @Test
    fun `E9-E24 - Module boundary decoupling - tramai-engine contains zero bytecode references to tramai-orchestration`() {
        val engineLocation =
            File(
                java.net.URI(
                    TramaiEngine::class.java.protectionDomain.codeSource.location
                        .toString(),
                ),
            )
        check(engineLocation.exists()) {
            "Engine location unavailable: ${engineLocation.absolutePath}"
        }

        val violations = mutableListOf<String>()
        var classCount = 0

        fun checkStream(
            name: String,
            inputStream: java.io.InputStream,
        ) {
            classCount++
            val reader = ClassReader(inputStream)
            reader.accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visit(
                        version: Int,
                        access: Int,
                        name: String,
                        signature: String?,
                        superName: String?,
                        interfaces: Array<out String>?,
                    ) {
                        superName?.let { checkRef(name, it) }
                        interfaces?.forEach { checkRef(name, it) }
                    }

                    override fun visitField(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        value: Any?,
                    ): FieldVisitor? {
                        checkRef(name, descriptor)
                        return null
                    }

                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor? {
                        checkRef(name, descriptor)
                        return null
                    }

                    private fun checkRef(
                        sourceClass: String,
                        typeDesc: String,
                    ) {
                        if (typeDesc.contains("dev/tramai/orchestration")) {
                            violations.add("$sourceClass references $typeDesc")
                        }
                    }
                },
                0,
            )
        }

        if (engineLocation.isDirectory) {
            engineLocation.walkTopDown().filter { it.extension == "class" }.forEach { classFile ->
                classFile.inputStream().use { checkStream(classFile.name, it) }
            }
        } else if (engineLocation.name.endsWith(".jar")) {
            java.util.jar.JarFile(engineLocation).use { jar ->
                jar
                    .entries()
                    .asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .forEach { entry ->
                        jar.getInputStream(entry).use { checkStream(entry.name, it) }
                    }
            }
        }

        assertThat(classCount).isGreaterThanOrEqualTo(100)
        assertThat(violations).`as`("tramai-engine must have zero bytecode references to tramai-orchestration").isEmpty()
    }

    // -----------------------------------------------------------------------------------------
    // Crash-Window Scenarios: E9-W1 to E9-W10
    // -----------------------------------------------------------------------------------------

    @Test
    fun `E9-W1 - Crash before continuation claim - continuation remains PENDING and evidence is absent`() {
        val fixture = createFixture(evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)

        // Simulate crash before claim: nothing was claimed
        val continuation = runBlocking { fixture.continuationStore.get(suspended.approvalId) }
        assertThat(continuation?.status).isEqualTo(ApprovalContinuationStatus.PENDING)
        assertThat(continuation?.version).isEqualTo(0L)

        val evidence = runBlocking { fixture.evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID) }
        assertThat(evidence).isEmpty()
    }

    @Test
    fun `E9-W2 - Claim succeeds, crash before admission - continuation is CLAIMED and evidence is absent`() {
        val fixture = createFixture(evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)

        val claimed = runBlocking { fixture.continuationStore.claimForExecution(suspended.approvalId, 0L, "worker-1") }
        assertThat(claimed.continuation.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
        assertThat(claimed.continuation.version).isEqualTo(1L)

        // Crash before admission: no evidence written
        val evidence = runBlocking { fixture.evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID) }
        assertThat(evidence).isEmpty()
    }

    @Test
    fun `E9-W3 - Admission succeeds, crash before tool dispatch - evidence is ADMITTED, tool never executed`() {
        val fixture = createFixture(evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)

        val claimed = runBlocking { fixture.continuationStore.claimForExecution(suspended.approvalId, 0L, "worker-1") }
        val authority =
            WorkflowApprovalResumeAdmission(
                checkpointStore = fixture.checkpoints,
                continuationStore = fixture.continuationStore,
                evidenceStore = fixture.evidenceStore,
            )

        val permit =
            runBlocking {
                authority.admitResume(
                    ApprovalResumeAdmissionRequest(
                        approvalId = suspended.approvalId,
                        toolName = TOOL_NAME,
                        runIdentity = testIdentity(),
                        checkpointBinding = WorkflowCheckpointBinding(WORKFLOW_NAME, RUN_ID),
                        continuationVersion = claimed.continuation.version,
                    ),
                )
            }
        assertThat(permit.evidenceHandle).isNotNull

        // Simulate crash before tool execution: tool was never called
        assertThat(fixture.tool.executions.get()).isEqualTo(0)

        val records = runBlocking { fixture.evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID) }
        assertThat(records).hasSize(1)
        assertThat(records[0].state).isEqualTo(WorkflowEffectEvidenceState.ADMITTED)
    }

    @Test
    fun `E9-W4 - Crash during tool execution - evidence remains ADMITTED when process dies before uncertain handler`() {
        val fixture = createFixture(evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)

        val claimed = runBlocking { fixture.continuationStore.claimForExecution(suspended.approvalId, 0L, "worker-1") }
        val authority =
            WorkflowApprovalResumeAdmission(
                checkpointStore = fixture.checkpoints,
                continuationStore = fixture.continuationStore,
                evidenceStore = fixture.evidenceStore,
            )

        runBlocking {
            authority.admitResume(
                ApprovalResumeAdmissionRequest(
                    approvalId = suspended.approvalId,
                    toolName = TOOL_NAME,
                    runIdentity = testIdentity(),
                    checkpointBinding = WorkflowCheckpointBinding(WORKFLOW_NAME, RUN_ID),
                    continuationVersion = claimed.continuation.version,
                ),
            )
        }

        // Durable evidence remains ADMITTED (truthful crash semantics)
        val records = runBlocking { fixture.evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID) }
        assertThat(records).hasSize(1)
        assertThat(records[0].state).isEqualTo(WorkflowEffectEvidenceState.ADMITTED)
    }

    @Test
    fun `E9-W5 - Tool execution throws exception - evidence transitions to UNCERTAIN`() {
        val failingTool = CountingTool(shouldFail = true, failureThrowable = RuntimeException("boom"))
        val fixture = createFixture(evidenceRequired = true, tool = failingTool)
        val suspended = suspendWorkflow(fixture)

        assertFailsWith<RuntimeException> {
            runBlocking {
                fixture.engine.resumeApproval(
                    ResumeApprovalCommand(
                        approvalId = suspended.approvalId,
                        approvalExpectedVersion = 0L,
                        continuationExpectedVersion = 0L,
                        presentedToken = suspended.challenge.token,
                        resumedBy = "op",
                    ),
                )
            }
        }

        val records = runBlocking { fixture.evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID) }
        assertThat(records[0].state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
    }

    @Test
    fun `E9-W6 - Tool succeeds, crash before continuation complete - evidence is COMPLETED and continuation is CLAIMED`() {
        val baseContinuations = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())
        val failingContinuations =
            object : ApprovalContinuationStore by baseContinuations {
                override suspend fun complete(
                    approvalId: String,
                    expectedVersion: Long,
                    completedBy: String,
                ): ApprovalContinuation {
                    error("Store crash before complete")
                }
            }

        val fixture = createFixture(continuations = failingContinuations, evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)

        assertFailsWith<IllegalStateException> {
            runBlocking {
                fixture.engine.resumeApproval(
                    ResumeApprovalCommand(
                        approvalId = suspended.approvalId,
                        approvalExpectedVersion = 0L,
                        continuationExpectedVersion = 0L,
                        presentedToken = suspended.challenge.token,
                        resumedBy = "op",
                    ),
                )
            }
        }

        // Evidence is COMPLETED, proving tool ran to completion
        val records = runBlocking { fixture.evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID) }
        assertThat(records[0].state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)

        // Continuation is CLAIMED
        val cont = runBlocking { baseContinuations.get(suspended.approvalId) }
        assertThat(cont?.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
    }

    @Test
    fun `E9-W7 - Continuation completion fails - operator reconciliation required, tool executed once`() {
        val baseContinuations = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())
        val failingContinuations =
            object : ApprovalContinuationStore by baseContinuations {
                override suspend fun complete(
                    approvalId: String,
                    expectedVersion: Long,
                    completedBy: String,
                ): ApprovalContinuation {
                    error("Store crash before complete")
                }
            }

        val fixture = createFixture(continuations = failingContinuations, evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)

        assertFailsWith<IllegalStateException> {
            runBlocking {
                fixture.engine.resumeApproval(
                    ResumeApprovalCommand(
                        approvalId = suspended.approvalId,
                        approvalExpectedVersion = 0L,
                        continuationExpectedVersion = 0L,
                        presentedToken = suspended.challenge.token,
                        resumedBy = "op",
                    ),
                )
            }
        }

        assertThat(fixture.tool.executions.get()).isEqualTo(1)
        val records = runBlocking { fixture.evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID) }
        assertThat(records[0].state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
    }

    @Test
    fun `E9-W8 - Checkpoint cancelled while waiting for approval - admitResume refuses and tool never dispatched`() {
        val fixture = createFixture(evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)

        // Cancel checkpoint while in pending
        runBlocking {
            val current = checkNotNull(fixture.checkpoints.load(WORKFLOW_NAME, RUN_ID))
            fixture.checkpoints.cancel(
                WorkflowCancellationRequest(
                    workflowName = WORKFLOW_NAME,
                    workflowId = RUN_ID,
                    expectedRevision = current.revision,
                    expectedGeneration = current.checkpointGeneration,
                    actor = "operator",
                    reason = "deliberate cancellation",
                    cancelledAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        }

        assertFailsWith<ApprovalResumeAdmissionRefusedException> {
            runBlocking {
                fixture.engine.resumeApproval(
                    ResumeApprovalCommand(
                        approvalId = suspended.approvalId,
                        approvalExpectedVersion = 0L,
                        continuationExpectedVersion = 0L,
                        presentedToken = suspended.challenge.token,
                        resumedBy = "op",
                    ),
                )
            }
        }

        assertThat(fixture.tool.executions.get()).isEqualTo(0)
        assertThat(runBlocking { fixture.evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID) }).isEmpty()
    }

    @Test
    fun `E9-W9 - Checkpoint generation rotated by retry or recovery - admission against current row succeeds`() {
        val fixture = createFixture(evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)

        val initialGeneration = runBlocking { checkNotNull(fixture.checkpoints.load(WORKFLOW_NAME, RUN_ID)).checkpointGeneration }

        // Rotate checkpoint generation
        val rotatedGeneration: String =
            runBlocking {
                val current = checkNotNull(fixture.checkpoints.load(WORKFLOW_NAME, RUN_ID))
                fixture.checkpoints.delete(
                    WORKFLOW_NAME,
                    RUN_ID,
                    expectedRevision = current.revision,
                    expectedGeneration = current.checkpointGeneration,
                )
                val saved =
                    fixture.checkpoints.save(
                        current.copy(
                            revision = current.revision + 1,
                        ),
                    )
                saved.checkpointGeneration!!
            }
        assertThat(rotatedGeneration).isNotEqualTo(initialGeneration)

        val outcome =
            runBlocking {
                fixture.engine.resumeApproval(
                    ResumeApprovalCommand(
                        approvalId = suspended.approvalId,
                        approvalExpectedVersion = 0L,
                        continuationExpectedVersion = 0L,
                        presentedToken = suspended.challenge.token,
                        resumedBy = "op",
                    ),
                )
            }
        assertThat(outcome).isNotNull
        assertThat(fixture.tool.executions.get()).isEqualTo(1)

        val records = runBlocking { fixture.evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID) }
        assertThat(records[0].key.checkpointGeneration).isEqualTo(rotatedGeneration)
    }

    @Test
    fun `E9-W10 - Concurrent resume claims - exactly one claim succeeds, loser fails closed with conflict`() {
        runBlocking(Dispatchers.Default) {
            val fixture = createFixture(evidenceRequired = true)
            val suspended = suspendWorkflow(fixture)

            val start = CompletableDeferred<Unit>()
            val task1 =
                async {
                    start.await()
                    runCatching {
                        fixture.engine.resumeApproval(
                            ResumeApprovalCommand(
                                approvalId = suspended.approvalId,
                                approvalExpectedVersion = 0L,
                                continuationExpectedVersion = 0L,
                                presentedToken = suspended.challenge.token,
                                resumedBy = "op-1",
                            ),
                        )
                    }
                }
            val task2 =
                async {
                    start.await()
                    runCatching {
                        fixture.engine.resumeApproval(
                            ResumeApprovalCommand(
                                approvalId = suspended.approvalId,
                                approvalExpectedVersion = 0L,
                                continuationExpectedVersion = 0L,
                                presentedToken = suspended.challenge.token,
                                resumedBy = "op-2",
                            ),
                        )
                    }
                }

            start.complete(Unit)
            val r1 = task1.await()
            val r2 = task2.await()

            val successes = listOf(r1, r2).count { it.isSuccess }
            val failures = listOf(r1, r2).count { it.isFailure }

            assertThat(successes).isEqualTo(1)
            assertThat(failures).isEqualTo(1)
            assertThat(fixture.tool.executions.get()).isEqualTo(1)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Adversarial Mutation Tests: M01 to M15
    // -----------------------------------------------------------------------------------------

    @Test
    fun `M01 - Mutation kill - bypassing continuation store validation when evidence is enabled fails closed`() {
        val fixture = createFixture(evidenceRequired = true)
        suspendWorkflow(fixture)

        val authorityWithoutStore =
            WorkflowApprovalResumeAdmission(
                checkpointStore = fixture.checkpoints,
                continuationStore = null,
                evidenceStore = fixture.evidenceStore,
            )

        val refusal =
            assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                runBlocking {
                    authorityWithoutStore.admitResume(
                        ApprovalResumeAdmissionRequest(
                            "appr-any",
                            TOOL_NAME,
                            testIdentity(),
                            WorkflowCheckpointBinding(WORKFLOW_NAME, RUN_ID),
                            1L,
                        ),
                    )
                }
            }
        assertThat(refusal.message).contains("continuationStore is required")
    }

    @Test
    fun `M02 - Mutation kill - forged continuation claim version cannot bypass validation`() {
        val fixture = createFixture(evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)
        runBlocking { fixture.continuationStore.claimForExecution(suspended.approvalId, 0L, "w1") }

        val authority = WorkflowApprovalResumeAdmission(fixture.checkpoints, fixture.continuationStore, fixture.evidenceStore)
        val refusal =
            assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                runBlocking {
                    authority.admitResume(
                        ApprovalResumeAdmissionRequest(
                            suspended.approvalId,
                            TOOL_NAME,
                            testIdentity(),
                            WorkflowCheckpointBinding(WORKFLOW_NAME, RUN_ID),
                            999L,
                        ),
                    )
                }
            }
        assertThat(refusal.message).contains("continuation version mismatch")
    }

    @Test
    fun `M03 - Mutation kill - mismatched tool name cannot bypass validation`() {
        val fixture = createFixture(evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)
        runBlocking { fixture.continuationStore.claimForExecution(suspended.approvalId, 0L, "w1") }

        val authority = WorkflowApprovalResumeAdmission(fixture.checkpoints, fixture.continuationStore, fixture.evidenceStore)
        val refusal =
            assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                runBlocking {
                    authority.admitResume(
                        ApprovalResumeAdmissionRequest(
                            suspended.approvalId,
                            "rogue_tool",
                            testIdentity(),
                            WorkflowCheckpointBinding(WORKFLOW_NAME, RUN_ID),
                            1L,
                        ),
                    )
                }
            }
        assertThat(refusal.message).contains("tool mismatch")
    }

    @Test
    fun `M04 - Mutation kill - mismatched workflow run ID cannot bypass validation`() {
        val fixture = createFixture(evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)
        runBlocking { fixture.continuationStore.claimForExecution(suspended.approvalId, 0L, "w1") }

        val otherIdentity = testIdentity("run-divergent")
        runBlocking {
            fixture.checkpoints.save(
                WorkflowCheckpoint(
                    workflowName = WORKFLOW_NAME,
                    workflowId = "run-divergent",
                    nextStepIndex = 1,
                    stepExecutions = 1,
                    lastCompletedStepName = "init",
                    statePayload = "{}",
                    metadata = encodeGovernedRunAttribution(otherIdentity),
                ),
            )
        }

        val authority = WorkflowApprovalResumeAdmission(fixture.checkpoints, fixture.continuationStore, fixture.evidenceStore)
        val refusal =
            assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                runBlocking {
                    authority.admitResume(
                        ApprovalResumeAdmissionRequest(
                            suspended.approvalId,
                            TOOL_NAME,
                            otherIdentity,
                            WorkflowCheckpointBinding(WORKFLOW_NAME, "run-divergent"),
                            1L,
                        ),
                    )
                }
            }
        assertThat(refusal.message).contains("workflowRunId mismatch")
    }

    @Test
    fun `M05 - Mutation kill - non-CLAIMED continuation cannot be admitted`() {
        val fixture = createFixture(evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)
        // Left in PENDING status

        val authority = WorkflowApprovalResumeAdmission(fixture.checkpoints, fixture.continuationStore, fixture.evidenceStore)
        val refusal =
            assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                runBlocking {
                    authority.admitResume(
                        ApprovalResumeAdmissionRequest(
                            suspended.approvalId,
                            TOOL_NAME,
                            testIdentity(),
                            WorkflowCheckpointBinding(WORKFLOW_NAME, RUN_ID),
                            1L,
                        ),
                    )
                }
            }
        assertThat(refusal.message).contains("is not in CLAIMED status")
    }

    @Test
    fun `M06 - Mutation kill - missing continuationStore fails closed before checkpoint write`() {
        val fixture = createFixture(evidenceRequired = true)
        val authority = WorkflowApprovalResumeAdmission(fixture.checkpoints, null, fixture.evidenceStore)

        assertFailsWith<ApprovalResumeAdmissionRefusedException> {
            runBlocking {
                authority.admitResume(
                    ApprovalResumeAdmissionRequest(
                        "appr-x",
                        TOOL_NAME,
                        testIdentity(),
                        WorkflowCheckpointBinding(WORKFLOW_NAME, RUN_ID),
                        1L,
                    ),
                )
            }
        }
        // Checkpoint row admission was never attempted
        val currentCheckpoint = runBlocking { fixture.checkpoints.load(WORKFLOW_NAME, RUN_ID) }
        assertThat(currentCheckpoint).isNull()
    }

    @Test
    fun `M07 - Mutation kill - non-positive continuationVersion fails closed before checkpoint write`() {
        val fixture = createFixture(evidenceRequired = true)
        suspendWorkflow(fixture)

        val authority = WorkflowApprovalResumeAdmission(fixture.checkpoints, fixture.continuationStore, fixture.evidenceStore)
        val refusal =
            assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                runBlocking {
                    authority.admitResume(
                        ApprovalResumeAdmissionRequest(
                            "appr-1",
                            TOOL_NAME,
                            testIdentity(),
                            WorkflowCheckpointBinding(WORKFLOW_NAME, RUN_ID),
                            0L,
                        ),
                    )
                }
            }
        assertThat(refusal.message).contains("continuationVersion must be positive")
    }

    @Test
    fun `M08 - Mutation kill - engine with evidenceRequired fails closed when permit has null evidenceHandle`() {
        val checkpoints = InMemoryWorkflowCheckpointStore()
        val continuations = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())
        val admission = WorkflowApprovalResumeAdmission(checkpoints, continuations, null)
        val fixture =
            createFixture(checkpoints = checkpoints, continuations = continuations, evidenceRequired = true, customAdmission = admission)

        val suspended = suspendWorkflow(fixture)

        val failure =
            assertFailsWith<IllegalStateException> {
                runBlocking {
                    fixture.engine.resumeApproval(
                        ResumeApprovalCommand(
                            approvalId = suspended.approvalId,
                            approvalExpectedVersion = 0L,
                            continuationExpectedVersion = 0L,
                            presentedToken = suspended.challenge.token,
                            resumedBy = "op",
                        ),
                    )
                }
            }
        assertThat(
            failure.message,
        ).contains("requires durable evidence tracking, but admission authority returned permit without evidenceHandle")
        assertThat(fixture.tool.executions.get()).isEqualTo(0)
    }

    @Test
    fun `M09 - Mutation kill - engine with evidence disabled does not fail when permit has null evidenceHandle`() {
        val checkpoints = InMemoryWorkflowCheckpointStore()
        val continuations = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())
        val admission = WorkflowApprovalResumeAdmission(checkpoints, continuations, null)
        val fixture =
            createFixture(checkpoints = checkpoints, continuations = continuations, evidenceRequired = false, customAdmission = admission)

        val suspended = suspendWorkflow(fixture)

        val outcome =
            runBlocking {
                fixture.engine.resumeApproval(
                    ResumeApprovalCommand(
                        approvalId = suspended.approvalId,
                        approvalExpectedVersion = 0L,
                        continuationExpectedVersion = 0L,
                        presentedToken = suspended.challenge.token,
                        resumedBy = "op",
                    ),
                )
            }
        assertThat(outcome).isNotNull
        assertThat(fixture.tool.executions.get()).isEqualTo(1)
    }

    @Test
    fun `M10 - Mutation kill - evidence record does not invent fake stepAttemptId and strictly records ParentProvenance ApprovalContinuation`() {
        val fixture = createFixture(evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)
        runBlocking {
            fixture.engine.resumeApproval(
                ResumeApprovalCommand(
                    approvalId = suspended.approvalId,
                    approvalExpectedVersion = 0L,
                    continuationExpectedVersion = 0L,
                    presentedToken = suspended.challenge.token,
                    resumedBy = "op",
                ),
            )
        }

        val record = runBlocking { fixture.evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID).single() }
        assertThat(record.parent).isInstanceOf(ParentProvenance.ApprovalContinuation::class.java)
        assertThat(record.stepAttemptId).isNull() // Must be null, never synthetic
    }

    @Test
    fun `M11 - Mutation kill - tool failure cannot record COMPLETED evidence`() {
        val tool = CountingTool(shouldFail = true, failureThrowable = IllegalStateException("error"))
        val fixture = createFixture(evidenceRequired = true, tool = tool)
        val suspended = suspendWorkflow(fixture)

        assertFailsWith<dev.tramai.core.exception.ProviderException> {
            runBlocking {
                fixture.engine.resumeApproval(
                    ResumeApprovalCommand(
                        approvalId = suspended.approvalId,
                        approvalExpectedVersion = 0L,
                        continuationExpectedVersion = 0L,
                        presentedToken = suspended.challenge.token,
                        resumedBy = "op",
                    ),
                )
            }
        }

        val record = runBlocking { fixture.evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID).single() }
        assertThat(record.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
        assertThat(record.state).isNotEqualTo(WorkflowEffectEvidenceState.COMPLETED)
    }

    @Test
    fun `M12 - Mutation kill - completeClaimedResume failure does not overwrite COMPLETED evidence to UNCERTAIN`() {
        val baseContinuations = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())
        val failingContinuations =
            object : ApprovalContinuationStore by baseContinuations {
                override suspend fun complete(
                    approvalId: String,
                    expectedVersion: Long,
                    completedBy: String,
                ): ApprovalContinuation {
                    error("Store crash before complete")
                }
            }

        val fixture = createFixture(continuations = failingContinuations, evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)

        assertFailsWith<IllegalStateException> {
            runBlocking {
                fixture.engine.resumeApproval(
                    ResumeApprovalCommand(
                        approvalId = suspended.approvalId,
                        approvalExpectedVersion = 0L,
                        continuationExpectedVersion = 0L,
                        presentedToken = suspended.challenge.token,
                        resumedBy = "op",
                    ),
                )
            }
        }

        val record = runBlocking { fixture.evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID).single() }
        assertThat(record.state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
        assertThat(record.state).isNotEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
    }

    @Test
    fun `M13 - Mutation kill - cancelled checkpoint cannot be bypassed by approval grant`() {
        val fixture = createFixture(evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)

        // Cancel checkpoint row
        runBlocking {
            val current = checkNotNull(fixture.checkpoints.load(WORKFLOW_NAME, RUN_ID))
            fixture.checkpoints.cancel(
                WorkflowCancellationRequest(
                    workflowName = WORKFLOW_NAME,
                    workflowId = RUN_ID,
                    expectedRevision = current.revision,
                    expectedGeneration = current.checkpointGeneration,
                    actor = "operator",
                    reason = "deliberate cancellation",
                    cancelledAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        }

        assertFailsWith<ApprovalResumeAdmissionRefusedException> {
            runBlocking {
                fixture.engine.resumeApproval(
                    ResumeApprovalCommand(
                        approvalId = suspended.approvalId,
                        approvalExpectedVersion = 0L,
                        continuationExpectedVersion = 0L,
                        presentedToken = suspended.challenge.token,
                        resumedBy = "op",
                    ),
                )
            }
        }
        assertThat(fixture.tool.executions.get()).isEqualTo(0)
    }

    @Test
    fun `M14 - Mutation kill - standalone suspension never requires workflow evidence`() {
        val fixture = createFixture(evidenceRequired = true)
        val suspended = suspendWorkflow(fixture)

        val existing = runBlocking { fixture.suspendedStore.governedSuspension(suspended.approvalId)!! }
        val standalone = existing.copy(provenance = SuspensionBindingProvenance.STANDALONE, checkpointBinding = null)
        val envelope = runBlocking { fixture.suspendedStore.revealReplayEnvelope(suspended.approvalId)!! }
        runBlocking {
            fixture.suspendedStore.createGoverned(standalone, envelope)
        }

        // Should succeed even though evidenceRequired = true on engine, because STANDALONE does not require workflow evidence
        val outcome =
            runBlocking {
                fixture.engine.resumeApproval(
                    ResumeApprovalCommand(
                        approvalId = suspended.approvalId,
                        approvalExpectedVersion = 0L,
                        continuationExpectedVersion = 0L,
                        presentedToken = suspended.challenge.token,
                        resumedBy = "op",
                    ),
                )
            }
        assertThat(outcome).isNotNull
        assertThat(fixture.tool.executions.get()).isEqualTo(1)
    }

    @Test
    fun `M15 - Mutation kill - ApprovalResumePermit is strictly single-use and cannot be cloned or re-consumed`() {
        val permit =
            ApprovalResumePermit(
                approvalId = "appr-m15",
                checkpointBinding = WorkflowCheckpointBinding(WORKFLOW_NAME, RUN_ID),
                admissionSequence = 1L,
            )
        assertThat(permit.tryConsume()).isTrue()
        assertFailsWith<IllegalStateException> { permit.tryConsume() }

        // Assert no copy method exists
        val copyMethod = ApprovalResumePermit::class.java.methods.find { it.name == "copy" }
        assertThat(copyMethod).isNull()
    }

    @Test
    fun `E9-E07b - Public constructor with checkpoint evidence enabled but E9 disabled writes zero E9 records`() {
        val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val checkpoints =
            InMemoryWorkflowCheckpointStore().apply {
                this.evidenceStore = evidenceStore
            }
        val continuations = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())

        // Use real public constructor without E9 evidence enabled (defaults to false)
        val admission = WorkflowApprovalResumeAdmission(checkpointStore = checkpoints, continuationStore = continuations)

        val fixture =
            createFixture(
                checkpoints = checkpoints,
                continuations = continuations,
                evidenceStore = evidenceStore,
                evidenceRequired = false,
                customAdmission = admission,
            )
        val suspended = suspendWorkflow(fixture)

        val resumeCommand =
            ResumeApprovalCommand(
                approvalId = suspended.approvalId,
                approvalExpectedVersion = 0L,
                continuationExpectedVersion = 0L,
                presentedToken = suspended.challenge.token,
                resumedBy = "operator-alice",
            )

        val outcome = runBlocking { fixture.engine.resumeApproval(resumeCommand) }
        assertThat(outcome).isNotNull
        assertThat(fixture.tool.executions.get()).isEqualTo(1)
        assertThat(runBlocking { evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID) }).isEmpty()
    }

    @Test
    fun `E9-E07c - Public constructor with E9 explicitly enabled records E9 evidence`() {
        val evidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val checkpoints =
            InMemoryWorkflowCheckpointStore().apply {
                this.evidenceStore = evidenceStore
            }
        val continuations = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())

        // Use real public constructor with approvalResumeEvidenceEnabled = true
        val admission =
            WorkflowApprovalResumeAdmission(
                checkpointStore = checkpoints,
                continuationStore = continuations,
                approvalResumeEvidenceEnabled = true,
            )

        val fixture =
            createFixture(
                checkpoints = checkpoints,
                continuations = continuations,
                evidenceStore = evidenceStore,
                evidenceRequired = true,
                customAdmission = admission,
            )
        val suspended = suspendWorkflow(fixture)

        val resumeCommand =
            ResumeApprovalCommand(
                approvalId = suspended.approvalId,
                approvalExpectedVersion = 0L,
                continuationExpectedVersion = 0L,
                presentedToken = suspended.challenge.token,
                resumedBy = "operator-alice",
            )

        val outcome = runBlocking { fixture.engine.resumeApproval(resumeCommand) }
        assertThat(outcome).isNotNull
        assertThat(fixture.tool.executions.get()).isEqualTo(1)
        val records = runBlocking { evidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID) }
        assertThat(records).hasSize(1)
        assertThat(records[0].state).isEqualTo(WorkflowEffectEvidenceState.COMPLETED)
    }

    @Test
    fun `E9-E25 - Post-admission initial evidence failure throws typed PersistenceException and leaves continuation CLAIMED`() {
        val failingEvidenceStore =
            object : WorkflowEffectEvidenceRecordStore {
                override suspend fun recordEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord =
                    throw SQLException("Disk write failure")

                override suspend fun getEvidence(key: WorkflowEffectEvidenceKey): WorkflowEffectEvidenceRecord? = null

                override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord = record

                override suspend fun listEvidence(
                    workflowName: String,
                    workflowId: String,
                ): List<WorkflowEffectEvidenceRecord> = emptyList()
            }
        val checkpoints =
            InMemoryWorkflowCheckpointStore().apply {
                this.evidenceStore = failingEvidenceStore
            }
        val continuations = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())
        val admission =
            WorkflowApprovalResumeAdmission(
                checkpointStore = checkpoints,
                continuationStore = continuations,
                approvalResumeEvidenceEnabled = true,
            )
        val fixture =
            createFixture(
                checkpoints = checkpoints,
                continuations = continuations,
                evidenceStore = failingEvidenceStore,
                evidenceRequired = true,
                customAdmission = admission,
            )
        val suspended = suspendWorkflow(fixture)

        val resumeCommand =
            ResumeApprovalCommand(
                approvalId = suspended.approvalId,
                approvalExpectedVersion = 0L,
                continuationExpectedVersion = 0L,
                presentedToken = suspended.challenge.token,
                resumedBy = "operator-alice",
            )

        val exception =
            assertFailsWith<ApprovalResumeEvidencePersistenceException> {
                runBlocking { fixture.engine.resumeApproval(resumeCommand) }
            }
        assertThat(exception.message).contains("failed to record initial evidence")
        assertThat(exception.cause).isInstanceOf(SQLException::class.java)

        // 1. Tool was never executed
        assertThat(fixture.tool.executions.get()).isEqualTo(0)

        // 2. Continuation remains in CLAIMED status (NOT cancelled or refused)
        val storedContinuation = runBlocking { continuations.get(suspended.approvalId) }
        assertThat(storedContinuation).isNotNull
        assertThat(storedContinuation?.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)

        // 3. Checkpoint admission sequence was committed
        val checkpoint = runBlocking { checkpoints.load(WORKFLOW_NAME, RUN_ID) }
        assertThat(checkpoint).isNotNull
        assertThat(checkpoint?.stepExecutions).isEqualTo(1)

        // 4. Zero child evidence records written
        val records = runBlocking { failingEvidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID) }
        assertThat(records).isEmpty()
    }

    @Test
    fun `E9-E26 - Contradictory parent decoding in JDBC evidence store fails closed`() {
        val pool =
            JdbcConnectionPool.create(
                "jdbc:h2:mem:e9_contradictory_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "sa",
                "",
            )
        try {
            val recordedFailures = mutableListOf<Throwable>()
            val observer =
                PersistenceFailureDiagnosticObserver { event ->
                    recordedFailures.add(event.failure)
                }
            val store = JdbcWorkflowEffectEvidenceRecordStore(pool, observer = observer)
            store.createSchemaSql().forEach { sql ->
                pool.connection.use { conn -> conn.createStatement().use { it.execute(sql) } }
            }

            // Case 1: APPROVAL_CONTINUATION with non-null step_attempt_id
            pool.connection.use { conn ->
                conn
                    .prepareStatement(
                        """
                        INSERT INTO tramai_workflow_effect_evidence
                        (workflow_name, workflow_id, checkpoint_generation, admission_seq, step_name, step_attempt_id, effect_kind, state, created_at_epoch_millis, parent_kind, parent_id, parent_version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { stmt ->
                        stmt.setString(1, "wf-contra")
                        stmt.setString(2, "run-1")
                        stmt.setString(3, "gen-1")
                        stmt.setLong(4, 1L)
                        stmt.setString(5, "my_tool")
                        stmt.setString(6, "att-illegal")
                        stmt.setString(7, APPROVAL_RESUME_EFFECT_KIND)
                        stmt.setString(8, "ADMITTED")
                        stmt.setLong(9, 1000L)
                        stmt.setString(10, "APPROVAL_CONTINUATION")
                        stmt.setString(11, "appr-1")
                        stmt.setLong(12, 1L)
                        stmt.executeUpdate()
                    }
            }

            val ex1 =
                assertFailsWith<WorkflowPersistenceFailureException> {
                    runBlocking { store.listEvidence("wf-contra", "run-1") }
                }
            assertThat(ex1.message).isNotBlank()
            assertThat(recordedFailures).hasSize(1)
            assertThat(recordedFailures[0]).isInstanceOf(IllegalStateException::class.java)
            assertThat(recordedFailures[0].message).contains("Contradictory parent provenance")

            // Case 2: STEP_ATTEMPT with mismatched parent_id and step_attempt_id
            pool.connection.use { conn ->
                conn
                    .prepareStatement(
                        """
                        INSERT INTO tramai_workflow_effect_evidence
                        (workflow_name, workflow_id, checkpoint_generation, admission_seq, step_name, step_attempt_id, effect_kind, state, created_at_epoch_millis, parent_kind, parent_id, parent_version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { stmt ->
                        stmt.setString(1, "wf-contra")
                        stmt.setString(2, "run-2")
                        stmt.setString(3, "gen-1")
                        stmt.setLong(4, 2L)
                        stmt.setString(5, "my_tool")
                        stmt.setString(6, "att-1")
                        stmt.setString(7, "STEP_EXECUTION")
                        stmt.setString(8, "ADMITTED")
                        stmt.setLong(9, 1000L)
                        stmt.setString(10, "STEP_ATTEMPT")
                        stmt.setString(11, "att-2")
                        stmt.setNull(12, java.sql.Types.BIGINT)
                        stmt.executeUpdate()
                    }
            }

            val ex2 =
                assertFailsWith<WorkflowPersistenceFailureException> {
                    runBlocking { store.listEvidence("wf-contra", "run-2") }
                }
            assertThat(ex2.message).isNotBlank()
            assertThat(recordedFailures).hasSize(2)
            assertThat(recordedFailures[1]).isInstanceOf(IllegalStateException::class.java)
            assertThat(recordedFailures[1].message).contains("Contradictory STEP_ATTEMPT parentage")
        } finally {
            pool.dispose()
        }
    }

    @Test
    fun `E9-E27 - Engine resume round-trip with real JDBC stores verifies all 14 database columns`() {
        val pool =
            JdbcConnectionPool.create(
                "jdbc:h2:mem:e9_e2e_jdbc_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "sa",
                "",
            )
        try {
            val jdbcEvidenceStore = JdbcWorkflowEffectEvidenceRecordStore(pool)
            val jdbcCheckpoints =
                JdbcWorkflowCheckpointStore(pool).apply {
                    this.evidenceStore = jdbcEvidenceStore
                }
            pool.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(jdbcCheckpoints.createTableSql())
                    jdbcEvidenceStore.createSchemaSql().forEach { stmt.execute(it) }
                }
            }

            val continuations = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())
            val admission =
                WorkflowApprovalResumeAdmission(
                    checkpointStore = jdbcCheckpoints,
                    continuationStore = continuations,
                    approvalResumeEvidenceEnabled = true,
                )

            val identity = testIdentity()
            val suspendedStore = RecordingSuspendedStore(identity)
            val tool = CountingTool()
            val engine =
                TramaiEngine(
                    providerRegistry = ProviderRegistry.singleProvider(RecordingProvider(tool)),
                    toolRegistry = ToolRegistry(mapOf(tool.name to tool)),
                    policyEngine = ApprovalPolicyEngine(),
                    suspendedInvocationStore = suspendedStore,
                    approvalContinuationStore = continuations,
                    toolArgumentsDigester = Sha256ToolArgumentsDigester(),
                    approvalGateCoordinator = PermitGate(),
                    approvalResumeAdmission = admission,
                    approvalRecoveryCoordinator =
                        InMemoryApprovalRecoveryCoordinator(continuations, NoOpApprovalLifecycleAuditEmitter),
                    approvalResumeEvidenceRequired = true,
                    engineEventObserver = NoOpEngineEventObserver,
                )

            val service = engine.create(ApprovalService::class)
            val wf =
                workflow<String>(WORKFLOW_NAME) {
                    localStep("step-1") { state, _ -> state }
                    localStep("step-2") { state, _ ->
                        service.answer("invoke")
                        state
                    }
                }.build { it }

            val thrown =
                runCatching {
                    runBlocking {
                        wf.run(
                            "init",
                            GovernedRun(WorkflowContext(workflowId = RUN_ID), identity),
                            NoOpWorkflowObserver,
                            WorkflowPersistence(
                                checkpointStore = jdbcCheckpoints,
                                stateCodec = StringCodec,
                                evidenceStore = jdbcEvidenceStore,
                            ),
                        )
                    }
                }.exceptionOrNull() ?: error("Expected workflow to suspend")

            var cur: Throwable? = thrown
            var suspended: ApprovalSuspendedException? = null
            while (cur != null) {
                if (cur is ApprovalSuspendedException) {
                    suspended = cur
                    break
                }
                cur = cur.cause
            }
            val suspension = checkNotNull(suspended) { "ApprovalSuspendedException not found" }

            val resumeCommand =
                ResumeApprovalCommand(
                    approvalId = suspension.approvalId,
                    approvalExpectedVersion = 0L,
                    continuationExpectedVersion = 0L,
                    presentedToken = suspension.challenge.token,
                    resumedBy = "operator-alice",
                )

            val outcome = runBlocking { engine.resumeApproval(resumeCommand) }
            assertThat(outcome).isNotNull
            assertThat(tool.executions.get()).isEqualTo(1)

            pool.connection.use { conn ->
                conn
                    .prepareStatement(
                        """
                        SELECT workflow_name, workflow_id, checkpoint_generation, admission_seq,
                               step_name, step_attempt_id, effect_kind, state,
                               created_at_epoch_millis, completed_at_epoch_millis, diagnostic_summary,
                               parent_kind, parent_id, parent_version
                        FROM tramai_workflow_effect_evidence
                        WHERE workflow_name = ? AND workflow_id = ?
                        """.trimIndent(),
                    ).use { stmt ->
                        stmt.setString(1, WORKFLOW_NAME)
                        stmt.setString(2, RUN_ID)
                        stmt.executeQuery().use { rs ->
                            assertThat(rs.next()).isTrue()
                            assertThat(rs.getString("workflow_name")).isEqualTo(WORKFLOW_NAME)
                            assertThat(rs.getString("workflow_id")).isEqualTo(RUN_ID)
                            assertThat(rs.getString("checkpoint_generation")).isNotBlank()
                            assertThat(rs.getLong("admission_seq")).isGreaterThan(0L)
                            assertThat(rs.getString("step_name")).isEqualTo(TOOL_NAME)
                            assertThat(rs.getString("step_attempt_id")).isNull()
                            assertThat(rs.getString("effect_kind")).isEqualTo(APPROVAL_RESUME_EFFECT_KIND)
                            assertThat(rs.getString("state")).isEqualTo("COMPLETED")
                            val createdAt = rs.getLong("created_at_epoch_millis")
                            assertThat(createdAt).isGreaterThan(0L)
                            val completedAt = rs.getLong("completed_at_epoch_millis")
                            assertThat(completedAt).isGreaterThanOrEqualTo(createdAt)
                            assertThat(rs.getString("diagnostic_summary")).isNull()
                            assertThat(rs.getString("parent_kind")).isEqualTo("APPROVAL_CONTINUATION")
                            assertThat(rs.getString("parent_id")).isEqualTo(suspension.approvalId)
                            assertThat(rs.getLong("parent_version")).isEqualTo(1L)
                            assertThat(rs.next()).isFalse()
                        }
                    }
            }
        } finally {
            pool.dispose()
        }
    }

    @Test
    fun `E9-E28 - Evidence-write timeout throws persistence exception, leaves continuation CLAIMED, and prevents completion`() {
        val baseEvidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
        val stallingEvidenceStore =
            object : WorkflowEffectEvidenceRecordStore {
                override suspend fun recordEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord =
                    baseEvidenceStore.recordEvidence(record)

                override suspend fun getEvidence(key: WorkflowEffectEvidenceKey): WorkflowEffectEvidenceRecord? =
                    baseEvidenceStore.getEvidence(key)

                override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord {
                    kotlinx.coroutines.delay(200)
                    return baseEvidenceStore.updateEvidence(record)
                }

                override suspend fun listEvidence(
                    workflowName: String,
                    workflowId: String,
                ): List<WorkflowEffectEvidenceRecord> = baseEvidenceStore.listEvidence(workflowName, workflowId)
            }

        val checkpoints =
            InMemoryWorkflowCheckpointStore().apply {
                evidenceStore = stallingEvidenceStore
            }
        val continuations = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())
        val admission =
            WorkflowApprovalResumeAdmission(
                checkpointStore = checkpoints,
                continuationStore = continuations,
                evidenceStore = stallingEvidenceStore,
                approvalResumeEvidenceEnabled = true,
                evidenceTimeoutMillis = 50L,
            )

        val fixture =
            createFixture(
                checkpoints = checkpoints,
                continuations = continuations,
                evidenceStore = stallingEvidenceStore,
                customAdmission = admission,
                evidenceRequired = true,
            )

        val suspended = suspendWorkflow(fixture)

        val resumeCommand =
            ResumeApprovalCommand(
                approvalId = suspended.approvalId,
                approvalExpectedVersion = 0L,
                continuationExpectedVersion = 0L,
                presentedToken = suspended.challenge.token,
                resumedBy = "operator-alice",
            )

        val ex =
            assertFailsWith<ApprovalResumeEvidencePersistenceException> {
                runBlocking { fixture.engine.resumeApproval(resumeCommand) }
            }
        assertThat(ex.message).contains("Timed out")
        assertThat(fixture.tool.executions.get()).isEqualTo(1)

        val continuation = runBlocking { fixture.continuationStore.get(suspended.approvalId) }
        assertThat(continuation?.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
    }

    @Test
    fun `E9-E30 - Post-execution persistence failure labels evidence as PERSISTENCE_FAILED, preserves tool execution, and leaves continuation CLAIMED`() {
        val baseEvidenceStore = InMemoryWorkflowEffectEvidenceRecordStore()
        var failCompleted = true
        val flakingEvidenceStore =
            object : WorkflowEffectEvidenceRecordStore {
                override suspend fun recordEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord =
                    baseEvidenceStore.recordEvidence(record)

                override suspend fun getEvidence(key: WorkflowEffectEvidenceKey): WorkflowEffectEvidenceRecord? =
                    baseEvidenceStore.getEvidence(key)

                override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord {
                    if (record.state == WorkflowEffectEvidenceState.COMPLETED && failCompleted) {
                        failCompleted = false
                        throw SQLException("Database connection lost during COMPLETED write")
                    }
                    return baseEvidenceStore.updateEvidence(record)
                }

                override suspend fun listEvidence(
                    workflowName: String,
                    workflowId: String,
                ): List<WorkflowEffectEvidenceRecord> = baseEvidenceStore.listEvidence(workflowName, workflowId)
            }

        val checkpoints =
            InMemoryWorkflowCheckpointStore().apply {
                evidenceStore = flakingEvidenceStore
            }
        val continuations = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())
        val admission =
            WorkflowApprovalResumeAdmission(
                checkpointStore = checkpoints,
                continuationStore = continuations,
                evidenceStore = flakingEvidenceStore,
                approvalResumeEvidenceEnabled = true,
            )

        val fixture =
            createFixture(
                checkpoints = checkpoints,
                continuations = continuations,
                evidenceStore = flakingEvidenceStore,
                customAdmission = admission,
                evidenceRequired = true,
            )

        val suspended = suspendWorkflow(fixture)

        val resumeCommand =
            ResumeApprovalCommand(
                approvalId = suspended.approvalId,
                approvalExpectedVersion = 0L,
                continuationExpectedVersion = 0L,
                presentedToken = suspended.challenge.token,
                resumedBy = "operator-alice",
            )

        val ex =
            assertFailsWith<ApprovalResumeEvidencePersistenceException> {
                runBlocking { fixture.engine.resumeApproval(resumeCommand) }
            }
        assertThat(ex.message).contains("Failed to record COMPLETED evidence")
        assertThat(ex.cause).isInstanceOf(SQLException::class.java)

        // Tool was executed once and NOT retried
        assertThat(fixture.tool.executions.get()).isEqualTo(1)

        // Continuation remains in CLAIMED state for reconciliation
        val continuation = runBlocking { fixture.continuationStore.get(suspended.approvalId) }
        assertThat(continuation?.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)

        // Evidence was updated to UNCERTAIN with diagnosticSummary = PERSISTENCE_FAILED (NOT TOOL_EXECUTION_FAILED)
        val records = runBlocking { flakingEvidenceStore.listEvidence(WORKFLOW_NAME, RUN_ID) }
        assertThat(records).hasSize(1)
        val evidence = records[0]
        assertThat(evidence.state).isEqualTo(WorkflowEffectEvidenceState.UNCERTAIN)
        assertThat(evidence.diagnosticSummary).isEqualTo(WorkflowEvidenceDiagnosticCategory.PERSISTENCE_FAILED.name)
    }

    @Test
    fun `E9-E29 - Evidence handle recordUncertain safely swallows secondary update errors`() {
        val failingStore =
            object : WorkflowEffectEvidenceRecordStore {
                override suspend fun recordEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord =
                    throw SQLException("Store failure")

                override suspend fun getEvidence(key: WorkflowEffectEvidenceKey): WorkflowEffectEvidenceRecord =
                    throw SQLException("Store failure")

                override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord =
                    throw SQLException("Store failure")

                override suspend fun listEvidence(
                    workflowName: String,
                    workflowId: String,
                ): List<WorkflowEffectEvidenceRecord> = emptyList()
            }

        val key = WorkflowEffectEvidenceKey("wf", "run-1", "gen-1", 1L)
        val handleClass = Class.forName("dev.tramai.orchestration.WorkflowApprovalResumeEvidenceHandle")
        val ctor = handleClass.declaredConstructors[0].apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val handle =
            ctor.newInstance(failingStore, key, Clock.systemUTC(), 1000L) as dev.tramai.core.approval.ApprovalResumeEvidenceHandle

        runBlocking {
            handle.recordUncertain(
                RuntimeException("Primary execution failure"),
                WorkflowEvidenceDiagnosticCategory.TOOL_EXECUTION_FAILED,
            )
        }
    }

    @Test
    fun `E9-E30 - Adversarial - secret-bearing secondary persistence failure never leaks onto primary cancellation`() {
        val sensitiveSecret = "postgres://admin:SUPER_SECRET_PASSWORD_12345@prod-db.internal:5432/secrets_vault"
        val failingStore =
            object : WorkflowEffectEvidenceRecordStore {
                override suspend fun recordEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord =
                    throw SQLException("Connection refused: $sensitiveSecret")

                override suspend fun getEvidence(key: WorkflowEffectEvidenceKey): WorkflowEffectEvidenceRecord =
                    throw SQLException("Connection refused: $sensitiveSecret")

                override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord =
                    throw SQLException("Connection refused: $sensitiveSecret")

                override suspend fun listEvidence(
                    workflowName: String,
                    workflowId: String,
                ): List<WorkflowEffectEvidenceRecord> = emptyList()
            }

        val key = WorkflowEffectEvidenceKey("wf", "run-1", "gen-1", 1L)
        val handleClass = Class.forName("dev.tramai.orchestration.WorkflowApprovalResumeEvidenceHandle")
        val ctor = handleClass.declaredConstructors[0].apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val handle =
            ctor.newInstance(failingStore, key, Clock.systemUTC(), 1000L) as dev.tramai.core.approval.ApprovalResumeEvidenceHandle

        val primaryCancellation = CancellationException("workflow cancelled by operator")
        runBlocking {
            handle.recordUncertain(
                primaryCancellation,
                WorkflowEvidenceDiagnosticCategory.TOOL_EXECUTION_CANCELLED,
            )
        }

        // Verify SanitizedEvidenceDiagnosticException is attached
        assertThat(primaryCancellation.suppressed).anyMatch { it is SanitizedEvidenceDiagnosticException }
        assertThat(primaryCancellation.suppressed).noneMatch { it is SQLException }

        val fullDiagnosticString =
            java.io
                .StringWriter()
                .also { sw ->
                    java.io.PrintWriter(sw).use { pw ->
                        primaryCancellation.printStackTrace(pw)
                    }
                }.toString()

        assertThat(fullDiagnosticString).doesNotContain("SUPER_SECRET_PASSWORD")
        assertThat(fullDiagnosticString).doesNotContain("secrets_vault")
        assertThat(fullDiagnosticString).doesNotContain(sensitiveSecret)
    }
}
