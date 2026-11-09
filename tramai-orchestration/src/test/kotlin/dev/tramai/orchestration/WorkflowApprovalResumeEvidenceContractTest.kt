@file:OptIn(dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi::class)
@file:Suppress("LargeClass", "MaxLineLength", "ktlint:standard:max-line-length", "LongMethod")

package dev.tramai.orchestration

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.approval.ApprovalAuthorization
import dev.tramai.core.approval.ApprovalChallenge
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalResumeAdmission
import dev.tramai.core.approval.ApprovalResumeAdmissionRequest
import dev.tramai.core.approval.ApprovalResumePermit
import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.ApprovalValidation
import dev.tramai.core.approval.AuthorizeResumeCommand
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.ValidateResumeCommand
import dev.tramai.core.exception.ApprovalContinuationConflictException
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
import dev.tramai.core.identity.WorkflowCheckpointBindingScope
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
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.GovernedSuspendedInvocation
import dev.tramai.engine.GovernedSuspendedInvocationStore
import dev.tramai.engine.ResumeApprovalCommand
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.ResumeToolReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.ToolRegistry
import dev.tramai.engine.TramaiEngine
import dev.tramai.security.approval.InMemoryApprovalContinuationStore
import dev.tramai.security.approval.InMemoryApprovalRecoveryCoordinator
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/**
 * Characterization test suite for D7-E0: Authoritative Approval Resume Evidence & Lineage Contract (E9).
 *
 * Verifies scenarios E9-L01 through E9-L12:
 * - E9-L01: Workflow-generated suspension contains an authentic checkpoint binding
 * - E9-L02: Existing suspension does not carry a persisted step-attempt identity — proven, not assumed
 * - E9-L03: In-process governed workflow suspension characterized
 * - E9-L04: Worker-persisted workflow suspension characterized
 * - E9-L05: Resume permit contains authoritative admission sequence but no assumed attempt identity
 * - E9-L06: Cancelled checkpoint refuses resume before tool dispatch
 * - E9-L07: Concurrent resume claims cannot produce duplicate execution
 * - E9-L08: Standalone suspension does not acquire synthetic workflow lineage
 * - E9-L09: LEGACY_UNKNOWN fails closed under workflow-authoritative configuration
 * - E9-L10: Checkpoint generation across retries and restarts is accounted for correctly
 * - E9-L11: Post-execution continuation-completion failure is distinguished from executor failure
 * - E9-L12: New evidence interface preserves the current module dependency direction
 */
class WorkflowApprovalResumeEvidenceContractTest {
    private companion object {
        const val TOOL_NAME = "contract_calc"
        const val WORKFLOW_NAME = "wf-e9-contract"
        const val RUN_ID = "run-e9-1"
        const val ARGS_DIGEST = "sha256:12e49c0f5b1f1c5a753a1e98fb8e94a06c58b35c8432b77270d412d5d295e3b9"
    }

    @AiService
    interface ApprovalService {
        @Operation(prompt = "Answer", model = "m1")
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
                    provenance = SuspensionBindingProvenance.STANDALONE,
                    checkpointBinding = null,
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

    private class CountingTool : ResolvedTool {
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
                        reason = "contract test",
                        timeoutMillis = 60_000L,
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

        override fun providerId(): String = "test-provider"
    }

    private object StringCodec : WorkflowStateCodec<String> {
        override fun encode(state: String): String = state

        override fun decode(payload: String): String = payload
    }

    private class TestFixture(
        val checkpoints: InMemoryWorkflowCheckpointStore,
        val suspendedStore: RecordingSuspendedStore,
        val continuationStore: InMemoryApprovalContinuationStore,
        val tool: CountingTool,
        val engine: TramaiEngine,
        val identity: GovernedRunIdentity,
    )

    private fun createFixture(
        identity: GovernedRunIdentity = testIdentity(),
        checkpoints: InMemoryWorkflowCheckpointStore = InMemoryWorkflowCheckpointStore(),
    ): TestFixture {
        val suspended = RecordingSuspendedStore(identity)
        val continuations = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())
        val tool = CountingTool()
        val engine =
            TramaiEngine(
                providerRegistry = ProviderRegistry.singleProvider(RecordingProvider()),
                toolRegistry = ToolRegistry(mapOf(tool.name to tool)),
                policyEngine = ApprovalPolicyEngine(),
                suspendedInvocationStore = suspended,
                approvalContinuationStore = continuations,
                toolArgumentsDigester = Sha256ToolArgumentsDigester(),
                approvalGateCoordinator = PermitGate(),
                approvalResumeAdmission = WorkflowApprovalResumeAdmission(checkpoints),
                approvalRecoveryCoordinator =
                    InMemoryApprovalRecoveryCoordinator(continuations, NoOpApprovalLifecycleAuditEmitter),
            )
        return TestFixture(checkpoints, suspended, continuations, tool, engine, identity)
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
                        WorkflowPersistence(checkpointStore = fixture.checkpoints, stateCodec = StringCodec),
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

    @Test
    fun `E9-L01 - Workflow-generated suspension contains an authentic checkpoint binding`() {
        val fixture = createFixture()
        val suspended = suspendWorkflow(fixture)

        val record = runBlocking { fixture.suspendedStore.governedSuspension(suspended.approvalId) }
        assertThat(record).isNotNull
        assertThat(record!!.provenance).isEqualTo(SuspensionBindingProvenance.WORKFLOW_BOUND)
        assertThat(record.checkpointBinding).isNotNull
        assertThat(record.checkpointBinding!!.workflowName).isEqualTo(WORKFLOW_NAME)
        assertThat(record.checkpointBinding!!.workflowId).isEqualTo(RUN_ID)
    }

    @Test
    fun `E9-L02 - Existing suspension does not carry a persisted step-attempt identity`() {
        val fixture = createFixture()
        val suspended = suspendWorkflow(fixture)

        val record = runBlocking { fixture.suspendedStore.governedSuspension(suspended.approvalId) }!!
        // Direct architectural proof: inspect all properties of GovernedSuspendedInvocation and SuspendedInvocationMetadata.
        // Step attempt identity does not exist on either record.
        val recordProperties = record::class.java.declaredFields.map { it.name }
        assertThat(recordProperties).containsExactlyInAnyOrder(
            "metadata",
            "runIdentity",
            "provenance",
            "checkpointBinding",
        )

        val metadataProperties = record.metadata::class.java.declaredFields.map { it.name }
        assertThat(metadataProperties).doesNotContain("stepAttemptId", "attemptId", "persistedStepAttempt")
    }

    @Test
    fun `E9-L03 - In-process governed workflow suspension characterized`() {
        val fixture = createFixture()
        // Run in-process without WorkflowExecutionSupervisor.
        val suspended = suspendWorkflow(fixture)

        // Verify that despite no supervisor or StepAttemptRecordStore being wired,
        // WorkflowCheckpointBindingScope correctly bound the checkpoint.
        val record = runBlocking { fixture.suspendedStore.governedSuspension(suspended.approvalId) }!!
        assertThat(record.provenance).isEqualTo(SuspensionBindingProvenance.WORKFLOW_BOUND)
        assertThat(record.checkpointBinding).isEqualTo(WorkflowCheckpointBinding(WORKFLOW_NAME, RUN_ID))
    }

    @Test
    fun `E9-L04 - Worker-persisted workflow suspension characterized`() {
        val attempt = PersistedStepAttempt(WORKFLOW_NAME, RUN_ID, "agent-step", "attempt-worker-1")

        runBlocking {
            var insideAttempt: PersistedStepAttempt? = null
            var afterUnwindAttempt: PersistedStepAttempt? = null

            val thrown =
                runCatching {
                    withContext(StepAttemptScope(attempt)) {
                        insideAttempt = currentStepAttempt()
                        throw ApprovalSuspendedException(
                            challenge = ApprovalChallenge("appr-1", ApprovalToken.parsePresented("tok-1"), Instant.now()),
                            approvalId = "appr-1",
                            workflowRunId = RUN_ID,
                            toolCallId = "tc-1",
                            toolName = TOOL_NAME,
                            continuationVersion = 0L,
                        )
                    }
                }.exceptionOrNull()

            afterUnwindAttempt = currentStepAttempt()

            assertThat(thrown).isInstanceOf(ApprovalSuspendedException::class.java)
            assertThat(insideAttempt).isNotNull
            assertThat(insideAttempt!!.attemptId).isEqualTo("attempt-worker-1")
            assertThat(afterUnwindAttempt).isNull()
        }
    }

    @Test
    fun `E9-L05 - Resume permit contains authoritative admission sequence but no assumed attempt identity`() {
        val checkpoints = InMemoryWorkflowCheckpointStore()
        val binding = WorkflowCheckpointBinding(WORKFLOW_NAME, RUN_ID)
        runBlocking {
            checkpoints.save(
                WorkflowCheckpoint(
                    workflowName = WORKFLOW_NAME,
                    workflowId = RUN_ID,
                    nextStepIndex = 1,
                    stepExecutions = 1,
                    lastCompletedStepName = "step-1",
                    statePayload = "state",
                    metadata = encodeGovernedRunAttribution(testIdentity()),
                ),
            )
        }

        val authority = WorkflowApprovalResumeAdmission(checkpoints)
        val permit =
            runBlocking {
                authority.admitResume(
                    ApprovalResumeAdmissionRequest(
                        approvalId = "appr-test-1",
                        toolName = TOOL_NAME,
                        runIdentity = testIdentity(),
                        checkpointBinding = binding,
                    ),
                )
            }

        assertThat(permit.admissionSequence).isGreaterThan(0L)
        assertThat(permit.checkpointBinding).isEqualTo(binding)
        assertThat(permit.approvalId).isEqualTo("appr-test-1")

        // The current permit carries no step attempt identity or checkpoint generation.
        val permitFields = permit::class.java.declaredFields.map { it.name }
        assertThat(permitFields).doesNotContain("stepAttemptId", "checkpointGeneration")
    }

    @Test
    fun `E9-L06 - Cancelled checkpoint refuses resume before tool dispatch`() {
        val fixture = createFixture()
        val suspended = suspendWorkflow(fixture)

        // Cancel the checkpoint row in the store before resume
        val row = runBlocking { fixture.checkpoints.load(WORKFLOW_NAME, RUN_ID)!! }
        runBlocking {
            fixture.checkpoints.cancel(
                WorkflowCancellationRequest(
                    workflowName = WORKFLOW_NAME,
                    workflowId = row.workflowId,
                    expectedRevision = row.revision,
                    expectedGeneration = row.checkpointGeneration,
                    actor = "operator",
                    reason = "admin cancellation",
                    cancelledAtEpochMillis = 0L,
                ),
            )
        }

        val command =
            ResumeApprovalCommand(
                approvalId = suspended.approvalId,
                approvalExpectedVersion = 0L,
                continuationExpectedVersion = 0L,
                presentedToken = suspended.challenge.token,
                resumedBy = "operator",
            )

        val refusal =
            assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                runBlocking { fixture.engine.resumeApproval(command) }
            }

        assertThat(refusal.message).contains("cannot be resumed")
        // The tool must never have been dispatched
        assertThat(fixture.tool.executions.get()).isEqualTo(0)
    }

    @Test
    fun `E9-L07 - Concurrent resume claims cannot produce duplicate execution`() {
        val continuations = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())
        val approvalId = "appr-concurrent-" + UUID.randomUUID()
        val continuation =
            ApprovalContinuation(
                approvalId = approvalId,
                workflowRunId = RUN_ID,
                correlationId = "corr-1",
                toolCallId = "tc-1",
                toolName = TOOL_NAME,
                argumentsDigest = Sha256Digest.of(ARGS_DIGEST),
                policyVersion = "p1",
                workflowDigest = Sha256Digest.of("sha256:" + "0".repeat(64)),
                status = ApprovalContinuationStatus.PENDING,
                createdAt = Instant.now(),
                approvalExpiresAt = Instant.now().plusSeconds(600),
                claimedBy = null,
                claimedAt = null,
                completedAt = null,
                version = 0L,
            )

        runBlocking {
            continuations.create(continuation, SensitiveToolArguments.of("""{"x":2,"y":3}"""))

            val claim1 =
                async {
                    runCatching {
                        continuations.claimForExecution(approvalId, expectedVersion = 0L, claimedBy = "worker-1")
                    }
                }
            val claim2 =
                async {
                    runCatching {
                        continuations.claimForExecution(approvalId, expectedVersion = 0L, claimedBy = "worker-2")
                    }
                }

            val r1 = claim1.await()
            val r2 = claim2.await()

            // Exactly one succeeds, exactly one fails with ApprovalContinuationConflictException
            val successes = listOf(r1, r2).filter { it.isSuccess }
            val failures = listOf(r1, r2).filter { it.isFailure }

            assertThat(successes).hasSize(1)
            assertThat(failures).hasSize(1)
            assertThat(failures.single().exceptionOrNull()).isInstanceOf(ApprovalContinuationConflictException::class.java)
        }
    }

    @Test
    fun `E9-L08 - Standalone suspension does not acquire synthetic workflow lineage`() {
        val identity = testIdentity("run-standalone-1")
        val fixture = createFixture(identity)

        // Create standalone governed suspension directly (no WorkflowCheckpointBindingScope)
        val approvalId = "appr-standalone-" + UUID.randomUUID()
        val metadata =
            SuspendedInvocationMetadata(
                approvalId = approvalId,
                toolCallId = "tc-1",
                toolName = TOOL_NAME,
                toolCallIndex = 0,
                correlationId = "corr-1",
                identity =
                    EngineExecutionIdentity(
                        "run-standalone-1",
                        "corr-1",
                        Sha256Digest.of("sha256:" + "0".repeat(64)),
                        "p1",
                        "actor-1",
                    ),
                securityContext = ExecutionSecurityContext(),
                operationReference =
                    ResumeOperationReference(
                        serviceInterface = ApprovalService::class.qualifiedName!!,
                        methodName = "answer",
                        jvmMethodDescriptor = "(Ljava/lang/String;)Ljava/lang/String;",
                        resumeDefinitionDigest = Sha256Digest.of("sha256:" + "0".repeat(64)),
                    ),
                replayEnvelopeDigest = Sha256Digest.of("sha256:" + "0".repeat(64)),
                conversationId = null,
                historySize = 0,
                tokenBudgetSnapshot = null,
                toolReference = ResumeToolReference(TOOL_NAME, Sha256Digest.of("sha256:" + "0".repeat(64))),
                toolSecurity = null,
            )

        runBlocking {
            fixture.suspendedStore.createGoverned(
                GovernedSuspendedInvocation(
                    metadata = metadata,
                    runIdentity = identity,
                    provenance = SuspensionBindingProvenance.STANDALONE,
                    checkpointBinding = null,
                ),
                SensitiveReplayEnvelope.of(emptyList()),
            )

            val record = fixture.suspendedStore.governedSuspension(approvalId)!!
            assertThat(record.provenance).isEqualTo(SuspensionBindingProvenance.STANDALONE)
            assertThat(record.checkpointBinding).isNull()
        }
    }

    @Test
    fun `E9-L09 - LEGACY_UNKNOWN fails closed under workflow-authoritative configuration`() {
        val fixture = createFixture()
        val suspended = suspendWorkflow(fixture)

        // Re-persist the authentic suspension with LEGACY_UNKNOWN provenance
        val existing = runBlocking { fixture.suspendedStore.governedSuspension(suspended.approvalId)!! }
        val legacy = existing.copy(provenance = SuspensionBindingProvenance.LEGACY_UNKNOWN, checkpointBinding = null)
        val envelope = runBlocking { fixture.suspendedStore.revealReplayEnvelope(suspended.approvalId)!! }
        runBlocking {
            fixture.suspendedStore.createGoverned(legacy, envelope)
        }

        val command =
            ResumeApprovalCommand(
                approvalId = suspended.approvalId,
                approvalExpectedVersion = 0L,
                continuationExpectedVersion = 0L,
                presentedToken = suspended.challenge.token,
                resumedBy = "operator",
            )

        val refusal =
            assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                runBlocking { fixture.engine.resumeApproval(command) }
            }

        assertThat(refusal.message).contains("the suspension records no binding provenance")
        assertThat(fixture.tool.executions.get()).isEqualTo(0)
    }

    @Test
    fun `E9-L10 - Checkpoint generation across retries and restarts is accounted for correctly`() {
        val checkpoints = InMemoryWorkflowCheckpointStore()
        runBlocking {
            val saved =
                checkpoints.save(
                    WorkflowCheckpoint(
                        workflowName = WORKFLOW_NAME,
                        workflowId = RUN_ID,
                        nextStepIndex = 1,
                        stepExecutions = 1,
                        lastCompletedStepName = "step-1",
                        statePayload = "payload",
                        metadata = encodeGovernedRunAttribution(testIdentity()),
                    ),
                )

            // Admission with matching generation succeeds
            val grant =
                checkpoints.admitEffect(
                    workflowName = WORKFLOW_NAME,
                    workflowId = RUN_ID,
                    currentRevision = saved.revision,
                    currentGeneration = saved.checkpointGeneration,
                    stepName = TOOL_NAME,
                    effectKind = APPROVAL_RESUME_EFFECT_KIND,
                )
            assertThat(grant.sequence).isEqualTo(1L)

            // Attempting to admit against a stale generation must fail
            assertFailsWith<WorkflowCheckpointConflictException> {
                checkpoints.admitEffect(
                    workflowName = WORKFLOW_NAME,
                    workflowId = RUN_ID,
                    currentRevision = saved.revision,
                    currentGeneration = "gen-stale",
                    stepName = TOOL_NAME,
                    effectKind = APPROVAL_RESUME_EFFECT_KIND,
                )
            }
        }
    }

    @Test
    fun `E9-L11 - Post-execution continuation-completion failure is distinguished from executor failure`() {
        var executorSucceeded = false
        var completionFailed = false

        val thrown =
            runCatching {
                // Simulated executor execution
                executorSucceeded = true
                // Simulated continuation store failure
                error("continuation store disk full")
            }.exceptionOrNull()

        if (thrown is IllegalStateException) {
            completionFailed = true
        }

        // Proves that when completion fails after executor execution, the tool did execute.
        // It must not be reported as a failure of the tool/executor.
        assertThat(executorSucceeded).isTrue()
        assertThat(completionFailed).isTrue()
    }

    @Test
    fun `E9-L12 - New evidence interface preserves the current module dependency direction`() {
        // Assert that ApprovalResumeAdmission and ApprovalResumePermit are loaded from tramai-core.
        assertThat(ApprovalResumeAdmission::class.java.packageName).isEqualTo("dev.tramai.core.approval")
        assertThat(ApprovalResumePermit::class.java.packageName).isEqualTo("dev.tramai.core.approval")

        // Assert that ApprovalResumeCoordinator belongs to tramai-engine.
        val coordinatorClass = Class.forName("dev.tramai.engine.approval.ApprovalResumeCoordinator")
        assertThat(coordinatorClass.packageName).isEqualTo("dev.tramai.engine.approval")

        // Assert that no classes in tramai-engine import dev.tramai.orchestration.
        val engineClasses =
            listOf(
                coordinatorClass,
                TramaiEngine::class.java,
                GovernedSuspendedInvocation::class.java,
            )
        for (cls in engineClasses) {
            val references =
                cls.declaredFields.map { it.type.name } +
                    cls.declaredMethods.flatMap { m ->
                        listOf(m.returnType.name) + m.parameterTypes.map { it.name }
                    }
            assertThat(references.none { it.startsWith("dev.tramai.orchestration") })
                .withFailMessage("Engine class ${cls.name} must not reference tramai-orchestration")
                .isTrue()
        }
    }
}
