package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.operator.OperatorCapability
import dev.tramai.core.operator.OperatorPrincipal
import dev.tramai.orchestration.WorkflowAdmission
import dev.tramai.orchestration.WorkflowAdmissionRequest
import dev.tramai.orchestration.WorkflowCancellationRequest
import dev.tramai.orchestration.WorkflowCheckpoint
import dev.tramai.orchestration.WorkflowCheckpointStore
import dev.tramai.orchestration.WorkflowLifecycleState
import dev.tramai.orchestration.WorkflowLifecycleStatus
import dev.tramai.spring.sovereign.ops.outbox.RefusalEvidence
import dev.tramai.spring.sovereign.ops.outbox.RefusalOperation
import dev.tramai.spring.sovereign.ops.outbox.RefusalRecording
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsRefusalRecorder
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsWorkflowCancellationMutationResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsWorkflowCancellationStore
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SovereignOpsWorkflowCancellationControlPlaneTest {
    private val now: Instant = Instant.parse("2026-06-25T10:15:30Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val authorizedOperator =
        OperatorAuthority(
            OperatorPrincipal(
                subject = "test-operator",
                issuer = "https://issuer.example",
                authenticatedAt = Instant.parse("2026-10-25T09:00:00Z"),
                capabilities = setOf(OperatorCapability.CANCEL_WORKFLOW),
                approverRoles = setOf(ApproverRole("ops-admin")),
            ),
        )

    private val testCancellationPolicy =
        OperatorWorkflowCancellationPolicy(mapOf("invoice-flow" to setOf(ApproverRole("ops-admin"))))

    @Test
    fun `cancel pending run commits cancellation and returns Cancelled`() {
        runBlocking {
            val checkpoint =
                WorkflowCheckpoint(
                    workflowName = "invoice-flow",
                    workflowId = "run-1",
                    nextStepIndex = 1,
                    stepExecutions = 1,
                    lastCompletedStepName = "step-0",
                    statePayload = "{}",
                    revision = 2L,
                )
            val store = MutableCheckpointStore(mutableMapOf("invoice-flow:run-1" to checkpoint))
            val mutationStore = StubWorkflowCancellationStore(store, clock)
            val refusalRecorder = RecordingRefusalRecorder()

            val controlPlane =
                SovereignOpsWorkflowCancellationControlPlane(
                    checkpointStore = store,
                    mutationStore = mutationStore,
                    cancellationPolicy = testCancellationPolicy,
                    clock = clock,
                    refusalRecorder = refusalRecorder,
                )

            val command =
                WorkflowCancellationCommand(
                    workflowName = "invoice-flow",
                    runId = "run-1",
                    expectedRevision = 2L,
                    reason = "Operator requested cancellation",
                )

            val result = controlPlane.cancel(command, authorizedOperator)

            assertThat(result).isInstanceOf(WorkflowCancellationResult.Cancelled::class.java)
            val cancelled = result as WorkflowCancellationResult.Cancelled
            assertThat(cancelled.workflowName).isEqualTo("invoice-flow")
            assertThat(cancelled.runId).isEqualTo("run-1")
            assertThat(cancelled.revision).isEqualTo(3L)
            assertThat(cancelled.status).isEqualTo(WorkflowLifecycleStatus.CANCELLED)
            assertThat(cancelled.cancelledBy).isEqualTo("test-operator")
            assertThat(cancelled.cancelledAtEpochMillis).isEqualTo(now.toEpochMilli())
            assertThat(cancelled.alreadyCancelled).isFalse()

            assertThat(mutationStore.lastIntent).isNotNull
            assertThat(mutationStore.lastIntent?.workflowRunId).isEqualTo("run-1")
            assertThat(mutationStore.lastIntent?.actor).isEqualTo("test-operator")
            assertThat(refusalRecorder.recorded).isEmpty()
        }
    }

    @Test
    fun `operator lacking CANCEL_WORKFLOW capability is refused existence-blind`() {
        runBlocking {
            val store = MutableCheckpointStore(mutableMapOf())
            val mutationStore = StubWorkflowCancellationStore(store, clock)
            val refusalRecorder = RecordingRefusalRecorder()

            val unprivilegedOperator =
                OperatorAuthority(
                    OperatorPrincipal(
                        subject = "reader",
                        issuer = "https://issuer.example",
                        authenticatedAt = now,
                        capabilities = setOf(OperatorCapability.INSPECT_GOVERNANCE),
                        approverRoles = setOf(ApproverRole("ops-admin")),
                    ),
                )

            val controlPlane =
                SovereignOpsWorkflowCancellationControlPlane(
                    checkpointStore = store,
                    mutationStore = mutationStore,
                    cancellationPolicy = testCancellationPolicy,
                    clock = clock,
                    refusalRecorder = refusalRecorder,
                )

            val command =
                WorkflowCancellationCommand(
                    workflowName = "invoice-flow",
                    runId = "run-secret",
                    expectedRevision = 0L,
                    reason = "Attacker probe",
                )

            val result = controlPlane.cancel(command, unprivilegedOperator)

            assertThat(result).isInstanceOf(WorkflowCancellationResult.Unauthorized::class.java)
            val unauthorized = result as WorkflowCancellationResult.Unauthorized
            assertThat(unauthorized.reason).isEqualTo(WorkflowUnauthorizedReason.MISSING_CAPABILITY)

            assertThat(refusalRecorder.recorded).hasSize(1)
            val refusal = refusalRecorder.recorded.single()
            assertThat(refusal.operation).isEqualTo(RefusalOperation.CANCEL_WORKFLOW)
            assertThat(refusal.refusalReason).isEqualTo(WorkflowUnauthorizedReason.MISSING_CAPABILITY.wireName)
            assertThat(refusal.policyVersion).isNull() // existence-blind: no policy consulted
        }
    }

    @Test
    fun `operator with unconfigured workflow policy is refused`() {
        runBlocking {
            val store = MutableCheckpointStore(mutableMapOf())
            val mutationStore = StubWorkflowCancellationStore(store, clock)
            val refusalRecorder = RecordingRefusalRecorder()

            val controlPlane =
                SovereignOpsWorkflowCancellationControlPlane(
                    checkpointStore = store,
                    mutationStore = mutationStore,
                    cancellationPolicy = testCancellationPolicy, // only has "invoice-flow"
                    clock = clock,
                    refusalRecorder = refusalRecorder,
                )

            val command =
                WorkflowCancellationCommand(
                    workflowName = "unconfigured-workflow",
                    runId = "run-1",
                    expectedRevision = 0L,
                    reason = "Cancel unmapped",
                )

            val result = controlPlane.cancel(command, authorizedOperator)

            assertThat(result).isInstanceOf(WorkflowCancellationResult.Unauthorized::class.java)
            val unauthorized = result as WorkflowCancellationResult.Unauthorized
            assertThat(unauthorized.reason).isEqualTo(WorkflowUnauthorizedReason.WORKFLOW_POLICY_UNAVAILABLE)

            assertThat(refusalRecorder.recorded).hasSize(1)
            val refusal = refusalRecorder.recorded.single()
            assertThat(refusal.refusalReason).isEqualTo(WorkflowUnauthorizedReason.WORKFLOW_POLICY_UNAVAILABLE.wireName)
        }
    }

    @Test
    fun `operator lacking required target role is refused`() {
        runBlocking {
            val store = MutableCheckpointStore(mutableMapOf())
            val mutationStore = StubWorkflowCancellationStore(store, clock)
            val refusalRecorder = RecordingRefusalRecorder()

            val wrongRoleOperator =
                OperatorAuthority(
                    OperatorPrincipal(
                        subject = "finance-guy",
                        issuer = "https://issuer.example",
                        authenticatedAt = now,
                        capabilities = setOf(OperatorCapability.CANCEL_WORKFLOW),
                        approverRoles = setOf(ApproverRole("finance-reviewer")),
                    ),
                )

            val controlPlane =
                SovereignOpsWorkflowCancellationControlPlane(
                    checkpointStore = store,
                    mutationStore = mutationStore,
                    cancellationPolicy = testCancellationPolicy, // requires ops-admin
                    clock = clock,
                    refusalRecorder = refusalRecorder,
                )

            val command =
                WorkflowCancellationCommand(
                    workflowName = "invoice-flow",
                    runId = "run-1",
                    expectedRevision = 0L,
                    reason = "Finance operator cancelling ops flow",
                )

            val result = controlPlane.cancel(command, wrongRoleOperator)

            assertThat(result).isInstanceOf(WorkflowCancellationResult.Unauthorized::class.java)
            val unauthorized = result as WorkflowCancellationResult.Unauthorized
            assertThat(unauthorized.reason).isEqualTo(WorkflowUnauthorizedReason.ROLE_NOT_PERMITTED)

            assertThat(refusalRecorder.recorded).hasSize(1)
            val refusal = refusalRecorder.recorded.single()
            assertThat(refusal.refusalReason).isEqualTo(WorkflowUnauthorizedReason.ROLE_NOT_PERMITTED.wireName)
        }
    }

    @Test
    fun `cancellation on missing workflow run returns NotFound`() {
        runBlocking {
            val store = MutableCheckpointStore(mutableMapOf())
            val mutationStore = StubWorkflowCancellationStore(store, clock)

            val controlPlane =
                SovereignOpsWorkflowCancellationControlPlane(
                    checkpointStore = store,
                    mutationStore = mutationStore,
                    cancellationPolicy = testCancellationPolicy,
                    clock = clock,
                )

            val command =
                WorkflowCancellationCommand(
                    workflowName = "invoice-flow",
                    runId = "missing-run",
                    expectedRevision = 0L,
                    reason = "Cancel missing",
                )

            val result = controlPlane.cancel(command, authorizedOperator)

            assertThat(result).isInstanceOf(WorkflowCancellationResult.NotFound::class.java)
        }
    }

    @Test
    fun `cancellation on stale revision returns Stale`() {
        runBlocking {
            val checkpoint =
                WorkflowCheckpoint(
                    workflowName = "invoice-flow",
                    workflowId = "run-1",
                    nextStepIndex = 1,
                    stepExecutions = 1,
                    lastCompletedStepName = "step-0",
                    statePayload = "{}",
                    revision = 5L,
                )
            val store = MutableCheckpointStore(mutableMapOf("invoice-flow:run-1" to checkpoint))
            val mutationStore = StubWorkflowCancellationStore(store, clock)

            val controlPlane =
                SovereignOpsWorkflowCancellationControlPlane(
                    checkpointStore = store,
                    mutationStore = mutationStore,
                    cancellationPolicy = testCancellationPolicy,
                    clock = clock,
                )

            val command =
                WorkflowCancellationCommand(
                    workflowName = "invoice-flow",
                    runId = "run-1",
                    expectedRevision = 3L, // stale: expected 3, but store is at 5
                    reason = "Stale attempt",
                )

            val result = controlPlane.cancel(command, authorizedOperator)

            assertThat(result).isInstanceOf(WorkflowCancellationResult.Stale::class.java)
            val stale = result as WorkflowCancellationResult.Stale
            assertThat(stale.expectedRevision).isEqualTo(3L)
            assertThat(stale.currentRevision).isEqualTo(5L)
        }
    }

    @Test
    fun `repeated cancellation on already cancelled run returns Cancelled with alreadyCancelled true`() {
        runBlocking {
            val existingState =
                WorkflowLifecycleState(
                    status = WorkflowLifecycleStatus.CANCELLED,
                    revision = 3L,
                    checkpointGeneration = "gen-1",
                    cancelledAtEpochMillis = now.toEpochMilli(),
                    cancelledBy = "original-canceller",
                    reason = "Initial cancellation",
                )
            val store =
                MutableCheckpointStore(
                    checkpoints = mutableMapOf(),
                    lifecycles = mutableMapOf("invoice-flow:run-1" to existingState),
                )
            val mutationStore = StubWorkflowCancellationStore(store, clock)

            val controlPlane =
                SovereignOpsWorkflowCancellationControlPlane(
                    checkpointStore = store,
                    mutationStore = mutationStore,
                    cancellationPolicy = testCancellationPolicy,
                    clock = clock,
                )

            val command =
                WorkflowCancellationCommand(
                    workflowName = "invoice-flow",
                    runId = "run-1",
                    expectedRevision = 2L,
                    reason = "Repeated cancellation",
                )

            val result = controlPlane.cancel(command, authorizedOperator)

            assertThat(result).isInstanceOf(WorkflowCancellationResult.Cancelled::class.java)
            val cancelled = result as WorkflowCancellationResult.Cancelled
            assertThat(cancelled.alreadyCancelled).isTrue()
            assertThat(cancelled.revision).isEqualTo(3L)
            assertThat(cancelled.cancelledBy).isEqualTo("original-canceller")
            assertThat(mutationStore.lastIntent).isNull() // No duplicate mutation
        }
    }

    private class MutableCheckpointStore(
        private val checkpoints: MutableMap<String, WorkflowCheckpoint>,
        private val lifecycles: MutableMap<String, WorkflowLifecycleState> = mutableMapOf(),
    ) : WorkflowCheckpointStore {
        override suspend fun load(
            workflowName: String,
            workflowId: String,
        ): WorkflowCheckpoint? = checkpoints["$workflowName:$workflowId"]

        override suspend fun save(
            checkpoint: WorkflowCheckpoint,
            expectedRevision: Long?,
        ): WorkflowCheckpoint {
            val key = "${checkpoint.workflowName}:${checkpoint.workflowId}"
            checkpoints[key] = checkpoint
            return checkpoint
        }

        override suspend fun delete(
            workflowName: String,
            workflowId: String,
            expectedRevision: Long?,
            expectedGeneration: String?,
        ) {
            checkpoints.remove("$workflowName:$workflowId")
        }

        override suspend fun lifecycle(
            workflowName: String,
            workflowId: String,
        ): WorkflowLifecycleState? = lifecycles["$workflowName:$workflowId"]

        override suspend fun cancel(request: WorkflowCancellationRequest): WorkflowLifecycleState {
            val key = "${request.workflowName}:${request.workflowId}"
            val state =
                WorkflowLifecycleState(
                    status = request.status,
                    revision = request.expectedRevision + 1,
                    checkpointGeneration = request.expectedGeneration,
                    cancelledAtEpochMillis = request.cancelledAtEpochMillis,
                    cancelledBy = request.actor,
                    reason = request.reason,
                )
            lifecycles[key] = state
            return state
        }

        override suspend fun admit(request: WorkflowAdmissionRequest): WorkflowAdmission = WorkflowAdmission(admitted = true, sequence = 1L)
    }

    private class StubWorkflowCancellationStore(
        private val checkpointStore: MutableCheckpointStore,
        private val clock: Clock = Clock.systemUTC(),
    ) : SovereignOpsWorkflowCancellationStore {
        var lastIntent: SovereignOpsAuditOutboxRecord? = null

        override suspend fun cancelWithAuditIntent(
            command: WorkflowCancellationCommand,
            actor: String,
            actorIssuer: String,
            actorIdentityDigest: String,
            auditIntent: SovereignOpsAuditOutboxRecord,
        ): SovereignOpsWorkflowCancellationMutationResult {
            lastIntent = auditIntent
            val checkpoint =
                checkpointStore.load(command.workflowName, command.runId)
                    ?: error("Checkpoint missing")
            val request =
                WorkflowCancellationRequest(
                    workflowName = command.workflowName,
                    workflowId = command.runId,
                    expectedRevision = command.expectedRevision,
                    expectedGeneration = checkpoint.checkpointGeneration,
                    actor = actor,
                    reason = command.reason,
                    cancelledAtEpochMillis = clock.millis(),
                    uncertain = false,
                )
            val state = checkpointStore.cancel(request)
            return SovereignOpsWorkflowCancellationMutationResult(state = state, alreadyCancelled = false)
        }
    }

    private class RecordingRefusalRecorder : SovereignOpsRefusalRecorder {
        val recorded = mutableListOf<RefusalEvidence>()

        override suspend fun recordRefusal(evidence: RefusalEvidence): RefusalRecording {
            recorded += evidence
            return RefusalRecording.Recorded(
                SovereignOpsAuditOutboxRecord(
                    aggregateIdDigest = evidence.approvalIdDigest,
                    eventKey = evidence.eventKey,
                    actor = evidence.subject,
                    workflowRunId = evidence.workflowRunId,
                    correlationId = evidence.correlationId,
                    approvalStatus = null,
                    approvalVersion = null,
                    reasonDigest = "",
                    reasonLength = 0,
                ),
            )
        }
    }
}
