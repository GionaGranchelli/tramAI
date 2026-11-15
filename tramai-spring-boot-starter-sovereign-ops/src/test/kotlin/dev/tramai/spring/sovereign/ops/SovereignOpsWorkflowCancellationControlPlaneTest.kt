@file:Suppress("MaxLineLength")

package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.operator.OperatorCapability
import dev.tramai.core.operator.OperatorPrincipal
import dev.tramai.spring.sovereign.ops.outbox.RefusalEvidence
import dev.tramai.spring.sovereign.ops.outbox.RefusalOperation
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsWorkflowCancellationMutationResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsWorkflowCancellationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsWorkflowRunState
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
            val run =
                SovereignOpsWorkflowRunState(
                    revision = 2L,
                    status = "active",
                    isCancelled = false,
                )
            val mutationStore =
                StubWorkflowCancellationStore(
                    runs = mutableMapOf("invoice-flow:run-1" to run),
                    clock = clock,
                )
            val refusalRecorder = RecordingRefusalRecorder()

            val controlPlane =
                SovereignOpsWorkflowCancellationControlPlane(
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
            assertThat(cancelled.status).isEqualTo("cancelled")
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
            val mutationStore = StubWorkflowCancellationStore(clock = clock)
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
            val mutationStore = StubWorkflowCancellationStore(clock = clock)
            val refusalRecorder = RecordingRefusalRecorder()

            val controlPlane =
                SovereignOpsWorkflowCancellationControlPlane(
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
            val mutationStore = StubWorkflowCancellationStore(clock = clock)
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
            val mutationStore = StubWorkflowCancellationStore(clock = clock)

            val controlPlane =
                SovereignOpsWorkflowCancellationControlPlane(
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
            val run =
                SovereignOpsWorkflowRunState(
                    revision = 5L,
                    status = "active",
                    isCancelled = false,
                )
            val mutationStore =
                StubWorkflowCancellationStore(
                    runs = mutableMapOf("invoice-flow:run-1" to run),
                    clock = clock,
                )

            val controlPlane =
                SovereignOpsWorkflowCancellationControlPlane(
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
            val existingRun =
                SovereignOpsWorkflowRunState(
                    revision = 3L,
                    status = "cancelled",
                    cancelledAtEpochMillis = now.toEpochMilli(),
                    cancelledBy = "original-canceller",
                    isCancelled = true,
                )
            val mutationStore =
                StubWorkflowCancellationStore(
                    runs = mutableMapOf("invoice-flow:run-1" to existingRun),
                    clock = clock,
                )

            val controlPlane =
                SovereignOpsWorkflowCancellationControlPlane(
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

    private class StubWorkflowCancellationStore(
        private val runs: MutableMap<String, SovereignOpsWorkflowRunState> = mutableMapOf(),
        private val clock: Clock = Clock.systemUTC(),
    ) : SovereignOpsWorkflowCancellationStore {
        var lastIntent: SovereignOpsAuditOutboxRecord? = null

        override suspend fun queryRun(
            workflowName: String,
            runId: String,
        ): SovereignOpsWorkflowRunState? = runs["$workflowName:$runId"]

        override suspend fun cancelWithAuditIntent(
            command: WorkflowCancellationCommand,
            actor: String,
            actorIssuer: String,
            actorIdentityDigest: String,
            auditIntent: SovereignOpsAuditOutboxRecord,
        ): SovereignOpsWorkflowCancellationMutationResult {
            lastIntent = auditIntent
            val key = "${command.workflowName}:${command.runId}"
            val current = runs[key] ?: error("Checkpoint missing")
            if (current.isCancelled) {
                return SovereignOpsWorkflowCancellationMutationResult(
                    revision = current.revision,
                    status = current.status,
                    cancelledBy = current.cancelledBy ?: actor,
                    cancelledAtEpochMillis = current.cancelledAtEpochMillis ?: clock.millis(),
                    alreadyCancelled = true,
                )
            }
            val newRevision = command.expectedRevision + 1
            val updated =
                SovereignOpsWorkflowRunState(
                    revision = newRevision,
                    status = "cancelled",
                    cancelledBy = actor,
                    cancelledAtEpochMillis = clock.millis(),
                    isCancelled = true,
                )
            runs[key] = updated
            return SovereignOpsWorkflowCancellationMutationResult(
                revision = newRevision,
                status = "cancelled",
                cancelledBy = actor,
                cancelledAtEpochMillis = clock.millis(),
                alreadyCancelled = false,
            )
        }
    }
}
