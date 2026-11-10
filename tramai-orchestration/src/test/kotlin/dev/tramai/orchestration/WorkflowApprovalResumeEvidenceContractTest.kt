@file:OptIn(dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi::class)
@file:Suppress("LargeClass", "MaxLineLength", "ktlint:standard:max-line-length", "LongMethod")

package dev.tramai.orchestration

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.approval.ApprovalAuthorization
import dev.tramai.core.approval.ApprovalChallenge
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalResumeAdmission
import dev.tramai.core.approval.ApprovalResumeAdmissionRequest
import dev.tramai.core.approval.ApprovalResumePermit
import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.ApprovalValidation
import dev.tramai.core.approval.AuthorizeResumeCommand
import dev.tramai.core.approval.ClaimedApprovalContinuation
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.FieldVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
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
        val continuationStore: ApprovalContinuationStore,
        val tool: CountingTool,
        val engine: TramaiEngine,
        val identity: GovernedRunIdentity,
    )

    private fun createFixture(
        identity: GovernedRunIdentity = testIdentity(),
        checkpoints: InMemoryWorkflowCheckpointStore = InMemoryWorkflowCheckpointStore(),
        continuations: ApprovalContinuationStore = InMemoryApprovalContinuationStore(clock = Clock.systemUTC()),
        tool: CountingTool = CountingTool(),
    ): TestFixture {
        val suspended = RecordingSuspendedStore(identity)
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
        // Assert required fields are present and step attempt identity is strictly absent without brittle field enumeration
        val recordProperties = record::class.java.declaredFields.map { it.name }
        assertThat(recordProperties).contains("metadata", "runIdentity", "provenance", "checkpointBinding")
        assertThat(recordProperties).doesNotContain("stepAttemptId", "attemptId", "persistedStepAttempt")

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
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val workerConfig = WorkerConfig(workerId = "worker-e9-1", poolName = "e9-pool", pollIntervalMillis = 20)
        val fixture = createFixture()
        val service = fixture.engine.create(ApprovalService::class)

        var insideAttemptId: String? = null
        val wf =
            workflow<String>(WORKFLOW_NAME, definitionVersion = "v1") {
                localStep("suspend-step") { state, _ ->
                    insideAttemptId = currentStepAttempt()?.attemptId
                    service.answer("invoke")
                    state
                }
            }.build { it }

        val persistence =
            WorkflowPersistence(
                checkpointStore = checkpointStore,
                stateCodec = StringCodec,
                deleteCheckpointOnCompletion = false,
            )
        val observer = NoOpTramaiWorkerObserver
        val supervisor =
            WorkflowExecutionSupervisor(
                config = workerConfig,
                leaseStore = leaseStore,
                checkpointStore = checkpointStore,
                stepAttemptStore = checkpointStore,
                workflowBindings = WorkflowBindingRegistry { bind(wf, persistence) },
                observability = observer,
                leaseCoordinator = LeaseCoordinator(workerConfig, leaseStore, observer),
                recoveryCoordinator = WorkflowRecoveryCoordinator(leaseStore, checkpointStore),
                leaseRenewalLoop = LeaseRenewalLoop(workerConfig, leaseStore, observer),
                shuttingDownGracefully = { false },
                stepAttemptIdentitySource = StepAttemptIdentitySource { "attempt-worker-e9-1" },
            )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        supervisor.attachScope(scope)
        try {
            val initialCp =
                runBlocking {
                    checkpointStore.save(
                        WorkflowCheckpoint(
                            workflowName = WORKFLOW_NAME,
                            workflowId = RUN_ID,
                            nextStepIndex = 0,
                            stepExecutions = 0,
                            lastCompletedStepName = null,
                            statePayload = StringCodec.encode("start"),
                            metadata = wf.checkpointMetadata() + encodeGovernedRunAttribution(testIdentity()),
                        ),
                    )
                }
            val lease =
                runBlocking {
                    leaseStore.claim(WORKFLOW_NAME, RUN_ID, "worker-e9-1", initialCp.revision, 5_000)
                }
            supervisor.launch(initialCp, lease)

            runBlocking {
                withTimeout(10_000) {
                    while (supervisor.activeExecutionCount() != 0) delay(10)
                }
            }

            // Invariant 1: Step attempt was STARTED during execution
            assertThat(insideAttemptId).isEqualTo("attempt-worker-e9-1")

            // Invariant 2: Supervisor terminated step attempt upon suspension; status is FAILED, not STARTED
            val attempts = runBlocking { checkpointStore.listStepAttempts(RUN_ID) }
            assertThat(attempts).hasSize(1)
            val recordedAttempt = attempts.single()
            assertThat(recordedAttempt.attemptId).isEqualTo("attempt-worker-e9-1")
            assertThat(recordedAttempt.status).isEqualTo(StepAttemptStatus.FAILED)

            // Invariant 3: At the resume boundary outside worker attempt execution, currentStepAttempt is null
            assertThat(runBlocking { currentStepAttempt() }).isNull()
        } finally {
            scope.cancel()
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

        // Invariant: ApprovalResumePermit is a standard class (NOT a data class) to protect single-use tryConsume
        assertThat(ApprovalResumePermit::class.isData).isFalse()
        val declaredMethods = ApprovalResumePermit::class.java.declaredMethods.map { it.name }
        assertThat(declaredMethods).doesNotContain("copy")

        // Single-use tryConsume invariant
        assertThat(permit.tryConsume()).isTrue()
        assertFailsWith<IllegalStateException> {
            permit.tryConsume()
        }

        // The current permit carries no step attempt identity
        val permitFields = permit::class.java.declaredFields.map { it.name }
        assertThat(permitFields).doesNotContain("stepAttemptId", "attemptId")
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
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val synchronizingContinuations =
            object : ApprovalContinuationStore by continuations {
                override suspend fun claimForExecution(
                    approvalId: String,
                    expectedVersion: Long,
                    claimedBy: String,
                ): ClaimedApprovalContinuation {
                    barrier.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    return continuations.claimForExecution(approvalId, expectedVersion, claimedBy)
                }
            }

        val fixture = createFixture(continuations = synchronizingContinuations)
        val suspended = suspendWorkflow(fixture)

        val cmd1 =
            ResumeApprovalCommand(
                approvalId = suspended.approvalId,
                approvalExpectedVersion = 0L,
                continuationExpectedVersion = 0L,
                presentedToken = suspended.challenge.token,
                resumedBy = "worker-1",
            )
        val cmd2 =
            ResumeApprovalCommand(
                approvalId = suspended.approvalId,
                approvalExpectedVersion = 0L,
                continuationExpectedVersion = 0L,
                presentedToken = suspended.challenge.token,
                resumedBy = "worker-2",
            )

        runBlocking(Dispatchers.Default) {
            val call1 = async { runCatching { fixture.engine.resumeApproval(cmd1) } }
            val call2 = async { runCatching { fixture.engine.resumeApproval(cmd2) } }

            val r1 = call1.await()
            val r2 = call2.await()

            // Exactly one succeeds, exactly one fails with ApprovalContinuationConflictException
            val successes = listOf(r1, r2).filter { it.isSuccess }
            val failures = listOf(r1, r2).filter { it.isFailure }

            assertThat(successes).hasSize(1)
            assertThat(failures).hasSize(1)
            assertThat(failures.single().exceptionOrNull())
                .isInstanceOf(ApprovalContinuationConflictException::class.java)

            // Exactly one execution occurred
            assertThat(fixture.tool.executions.get()).isEqualTo(1)
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
        val binding = WorkflowCheckpointBinding(WORKFLOW_NAME, RUN_ID)
        val initial =
            WorkflowCheckpoint(
                workflowName = WORKFLOW_NAME,
                workflowId = RUN_ID,
                nextStepIndex = 1,
                stepExecutions = 1,
                lastCompletedStepName = "step-1",
                statePayload = "payload",
                checkpointGeneration = "gen-1",
                metadata = encodeGovernedRunAttribution(testIdentity()),
            )
        runBlocking { checkpoints.save(initial) }

        val authority = WorkflowApprovalResumeAdmission(checkpoints)
        val request =
            ApprovalResumeAdmissionRequest(
                approvalId = "appr-1",
                toolName = TOOL_NAME,
                runIdentity = testIdentity(),
                checkpointBinding = binding,
            )

        // Policy A (Admission-Time Freshness): fresh admission against current row succeeds
        val permit = runBlocking { authority.admitResume(request) }
        assertThat(permit.admissionSequence).isEqualTo(1L)
        assertThat(permit.approvalId).isEqualTo("appr-1")

        // Now simulate a concurrent generation race (e.g. concurrent retry rotates checkpoint generation)
        val racingStore =
            object : WorkflowCheckpointStore by checkpoints {
                override suspend fun load(
                    workflowName: String,
                    workflowId: String,
                ): WorkflowCheckpoint? {
                    val snapshot = checkpoints.load(workflowName, workflowId)!!
                    // Simulate concurrent worker saving new checkpoint with rotated generation
                    checkpoints.save(
                        snapshot.copy(
                            checkpointGeneration = "gen-rotated-by-retry",
                            revision = snapshot.revision + 1,
                        ),
                    )
                    return snapshot
                }
            }

        val racingAuthority = WorkflowApprovalResumeAdmission(racingStore)
        assertFailsWith<WorkflowCheckpointConflictException> {
            runBlocking { racingAuthority.admitResume(request) }
        }
    }

    @Test
    fun `E9-L11 - Post-execution continuation-completion failure is distinguished from executor failure`() {
        val continuations = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())
        val failingContinuations =
            object : ApprovalContinuationStore by continuations {
                override suspend fun complete(
                    approvalId: String,
                    expectedVersion: Long,
                    completedBy: String,
                ): ApprovalContinuation {
                    error("simulated continuation completion store failure")
                }
            }

        val fixture = createFixture(continuations = failingContinuations)
        val suspended = suspendWorkflow(fixture)

        val command =
            ResumeApprovalCommand(
                approvalId = suspended.approvalId,
                approvalExpectedVersion = 0L,
                continuationExpectedVersion = 0L,
                presentedToken = suspended.challenge.token,
                resumedBy = "operator",
            )

        val failure =
            assertFailsWith<IllegalStateException> {
                runBlocking { fixture.engine.resumeApproval(command) }
            }
        assertThat(failure.message).contains("simulated continuation completion store failure")

        // Invariant 1: The tool did execute (executions == 1) despite post-execution completion store failure
        assertThat(fixture.tool.executions.get()).isEqualTo(1)

        // Invariant 2: Continuation remains in CLAIMED status with version incremented to 1L, ready for recovery reconciliation
        val continuation = runBlocking { continuations.get(suspended.approvalId) }!!
        assertThat(continuation.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
        assertThat(continuation.version).isEqualTo(1L)
    }

    private fun scanBytecodeReferences(classStream: java.io.InputStream): Set<String> {
        val orchestrationReferences = mutableSetOf<String>()
        val visitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    if (signature?.contains("dev/tramai/orchestration") == true) {
                        orchestrationReferences += "$name: signature references dev.tramai.orchestration"
                    }
                    if (superName?.startsWith("dev/tramai/orchestration") == true) {
                        orchestrationReferences += "$name: superclass $superName references dev.tramai.orchestration"
                    }
                    interfaces?.filter { it.startsWith("dev/tramai/orchestration") }?.forEach {
                        orchestrationReferences += "$name: interface $it references dev.tramai.orchestration"
                    }
                }

                override fun visitField(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor? {
                    if (descriptor.contains("dev/tramai/orchestration") ||
                        signature?.contains("dev/tramai/orchestration") == true
                    ) {
                        orchestrationReferences += "Field $name references dev.tramai.orchestration"
                    }
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    if (descriptor.contains("dev/tramai/orchestration") ||
                        signature?.contains("dev/tramai/orchestration") == true
                    ) {
                        orchestrationReferences += "Method $name descriptor references dev.tramai.orchestration"
                    }
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitTypeInsn(
                            opcode: Int,
                            type: String,
                        ) {
                            if (type.startsWith("dev/tramai/orchestration")) {
                                orchestrationReferences += "Type instruction $type in $name"
                            }
                        }

                        override fun visitFieldInsn(
                            opcode: Int,
                            owner: String,
                            fieldName: String,
                            desc: String,
                        ) {
                            if (owner.startsWith("dev/tramai/orchestration") || desc.contains("dev/tramai/orchestration")) {
                                orchestrationReferences += "Field instruction $owner.$fieldName in $name"
                            }
                        }

                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            methodName: String,
                            desc: String,
                            isInterface: Boolean,
                        ) {
                            if (owner.startsWith("dev/tramai/orchestration") || desc.contains("dev/tramai/orchestration")) {
                                orchestrationReferences += "Method instruction $owner.$methodName in $name"
                            }
                        }
                    }
                }
            }
        classStream.use { stream ->
            ClassReader(stream).accept(visitor, 0)
        }
        return orchestrationReferences
    }

    private fun scanClassFiles(files: Sequence<Pair<String, () -> java.io.InputStream>>): Pair<Int, Map<String, Set<String>>> {
        var count = 0
        val offenders = mutableMapOf<String, Set<String>>()
        for ((name, openStream) in files) {
            count++
            val refs = scanBytecodeReferences(openStream())
            if (refs.isNotEmpty()) {
                offenders[name] = refs
            }
        }
        return count to offenders
    }

    private fun scanEngineLocation(location: File): Pair<Int, Map<String, Set<String>>> =
        when {
            location.isDirectory -> {
                val classFiles =
                    location
                        .walkTopDown()
                        .filter { it.isFile && it.name.endsWith(".class") }
                        .map { file ->
                            file.relativeTo(location).path.removeSuffix(".class") to { file.inputStream() }
                        }
                scanClassFiles(classFiles)
            }

            location.name.endsWith(".jar") -> {
                java.util.jar.JarFile(location).use { jar ->
                    val entries =
                        jar
                            .entries()
                            .asSequence()
                            .filter { !it.isDirectory && it.name.endsWith(".class") }
                            .map { entry ->
                                val bytes = jar.getInputStream(entry).use { it.readBytes() }
                                entry.name.removeSuffix(".class") to { bytes.inputStream() }
                            }.toList()
                    scanClassFiles(entries.asSequence())
                }
            }

            else -> {
                error("Engine location is neither a directory nor a jar: ${location.absolutePath}")
            }
        }

    @Test
    fun `E9-L12 - New evidence interface preserves the current module dependency direction`() {
        // Assert that ApprovalResumeAdmission and ApprovalResumePermit are loaded from tramai-core.
        assertThat(ApprovalResumeAdmission::class.java.packageName).isEqualTo("dev.tramai.core.approval")
        assertThat(ApprovalResumePermit::class.java.packageName).isEqualTo("dev.tramai.core.approval")

        // Module-wide bytecode scan across every compiled class in tramai-engine
        val engineLocation =
            File(
                URI.create(
                    checkNotNull(
                        TramaiEngine::class.java.protectionDomain.codeSource.location
                            .toString(),
                    ),
                ),
            )
        check(engineLocation.exists()) {
            "Engine location unavailable: ${engineLocation.absolutePath}"
        }

        val (classCount, offenders) = scanEngineLocation(engineLocation)

        assertThat(classCount)
            .withFailMessage("Engine bytecode scan scanned zero classes — fail closed")
            .isGreaterThanOrEqualTo(100)

        assertThat(offenders)
            .withFailMessage(
                "Engine module must have zero bytecode references to dev.tramai.orchestration; offenders: $offenders",
            ).isEmpty()
    }

    /**
     * Models the D7-E1 extended admission request contract which carries [continuationVersion].
     */
    private data class ContractApprovalResumeAdmissionRequest(
        val approvalId: String,
        val toolName: String,
        val runIdentity: dev.tramai.core.identity.GovernedRunIdentity,
        val checkpointBinding: WorkflowCheckpointBinding,
        val continuationVersion: Long = 0L,
    )

    private suspend fun validateClaimProvenance(
        store: ApprovalContinuationStore?,
        request: ContractApprovalResumeAdmissionRequest,
        evidenceEnabled: Boolean,
    ) {
        if (!evidenceEnabled) {
            // Legacy / evidence-disabled compatibility: admission proceeds without claim version validation
            return
        }
        if (store == null) {
            throw ApprovalResumeAdmissionRefusedException(
                "approval '${request.approvalId}' cannot be admitted: continuationStore is required when evidence tracking is enabled",
            )
        }
        val stored = store.get(request.approvalId)
        val rejectionReason =
            when {
                request.continuationVersion <= 0L -> {
                    "approval '${request.approvalId}' cannot record evidence: continuationVersion must be positive"
                }

                stored == null -> {
                    "approval '${request.approvalId}' not found"
                }

                stored.status != ApprovalContinuationStatus.CLAIMED -> {
                    "approval '${request.approvalId}' is not in CLAIMED status (was ${stored.status})"
                }

                stored.version != request.continuationVersion -> {
                    "approval '${request.approvalId}' continuation version mismatch: store has ${stored.version}, request claimed ${request.continuationVersion}"
                }

                stored.workflowRunId != request.checkpointBinding.workflowId -> {
                    "approval '${request.approvalId}' workflowRunId mismatch: store has ${stored.workflowRunId}, request claimed ${request.checkpointBinding.workflowId}"
                }

                stored.toolName != request.toolName -> {
                    "approval '${request.approvalId}' tool mismatch: store has ${stored.toolName}, request claimed ${request.toolName}"
                }

                else -> {
                    null
                }
            }
        if (rejectionReason != null) {
            throw ApprovalResumeAdmissionRefusedException(rejectionReason)
        }
    }

    private fun verifyEngineDispatchPrecondition(
        evidenceRequired: Boolean,
        permitEvidenceHandle: Any?,
        approvalId: String,
    ) {
        if (evidenceRequired && permitEvidenceHandle == null) {
            error(
                "Approval resume for '$approvalId' requires durable evidence tracking, but admission authority returned permit without evidenceHandle",
            )
        }
    }

    @Test
    fun `E9-L13 - Fail-closed claim provenance validation characterized (production enforcement required in D7-E1)`() {
        val fixture = createFixture()
        val suspended = suspendWorkflow(fixture)

        // Claim the continuation through the store (transitioning PENDING -> CLAIMED, version 0L -> 1L)
        val claimed =
            runBlocking {
                fixture.continuationStore.claimForExecution(
                    approvalId = suspended.approvalId,
                    expectedVersion = 0L,
                    claimedBy = "worker-1",
                )
            }
        assertThat(claimed.continuation.version).isEqualTo(1L)
        assertThat(claimed.continuation.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)

        val binding = WorkflowCheckpointBinding(WORKFLOW_NAME, RUN_ID)

        // Case 1: Substituted / falsified claim version (store has 1L, request claims 7L) is refused fail-closed
        val forgedVersionRequest =
            ContractApprovalResumeAdmissionRequest(
                approvalId = suspended.approvalId,
                toolName = TOOL_NAME,
                runIdentity = testIdentity(),
                checkpointBinding = binding,
                continuationVersion = 7L,
            )
        val forgedFailure =
            assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                runBlocking {
                    validateClaimProvenance(fixture.continuationStore, forgedVersionRequest, evidenceEnabled = true)
                }
            }
        assertThat(forgedFailure.message).contains("continuation version mismatch")

        // Case 2: Substituted / mismatched tool name is refused fail-closed
        val forgedToolRequest =
            ContractApprovalResumeAdmissionRequest(
                approvalId = suspended.approvalId,
                toolName = "other_tool",
                runIdentity = testIdentity(),
                checkpointBinding = binding,
                continuationVersion = claimed.continuation.version,
            )
        val toolMismatchFailure =
            assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                runBlocking {
                    validateClaimProvenance(fixture.continuationStore, forgedToolRequest, evidenceEnabled = true)
                }
            }
        assertThat(toolMismatchFailure.message).contains("tool mismatch")

        // Case 3: Missing continuationStore validator when evidence is enabled fails closed
        val missingStoreFailure =
            assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                runBlocking {
                    validateClaimProvenance(null, forgedVersionRequest, evidenceEnabled = true)
                }
            }
        assertThat(missingStoreFailure.message).contains("continuationStore is required")

        // Case 4: Legacy unversioned request (version = 0L) is refused fail-closed when evidence tracking is enabled
        val legacyRequest =
            ContractApprovalResumeAdmissionRequest(
                approvalId = suspended.approvalId,
                toolName = TOOL_NAME,
                runIdentity = testIdentity(),
                checkpointBinding = binding,
                continuationVersion = 0L,
            )
        val legacyFailure =
            assertFailsWith<ApprovalResumeAdmissionRefusedException> {
                runBlocking {
                    validateClaimProvenance(fixture.continuationStore, legacyRequest, evidenceEnabled = true)
                }
            }
        assertThat(legacyFailure.message).contains("continuationVersion must be positive")

        // Authentic claimed request (version = 1L)
        val authenticRequest =
            ContractApprovalResumeAdmissionRequest(
                approvalId = suspended.approvalId,
                toolName = TOOL_NAME,
                runIdentity = testIdentity(),
                checkpointBinding = binding,
                continuationVersion = claimed.continuation.version,
            )

        // Case 5a: Discriminating test 1 — Evidence disabled, claim version 1L, null handle remains fully compatible
        runBlocking {
            validateClaimProvenance(fixture.continuationStore, authenticRequest, evidenceEnabled = false)
        }
        verifyEngineDispatchPrecondition(
            evidenceRequired = false,
            permitEvidenceHandle = null,
            approvalId = suspended.approvalId,
        )

        // Case 5b: Discriminating test 2 — Evidence required, claim version 1L, null handle refuses before executor invocation
        val missingHandleFailure =
            assertFailsWith<IllegalStateException> {
                verifyEngineDispatchPrecondition(
                    evidenceRequired = true,
                    permitEvidenceHandle = null,
                    approvalId = suspended.approvalId,
                )
            }
        assertThat(missingHandleFailure.message).contains("requires durable evidence tracking")

        // Case 5c: Legacy unversioned request (version = 0L) succeeds when evidence tracking is disabled (runtime backward compatibility)
        runBlocking {
            validateClaimProvenance(fixture.continuationStore, legacyRequest, evidenceEnabled = false)
        }

        // Case 6: Authentic claimed version (1L) and matching tool/run passes validation and matches durable state
        runBlocking {
            validateClaimProvenance(fixture.continuationStore, authenticRequest, evidenceEnabled = true)
        }
    }
}
