package dev.tramai.spring.sovereign.ops

import dev.tramai.core.operator.ActorIdentityDigest
import dev.tramai.core.operator.OperatorCapability
import dev.tramai.spring.sovereign.ops.outbox.RefusalEvidence
import dev.tramai.spring.sovereign.ops.outbox.RefusalOperation
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditEvidenceKind
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsRefusalRecorder
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsWorkflowCancellationConflictException
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsWorkflowCancellationStore
import dev.tramai.spring.sovereign.ops.outbox.UnconfiguredRefusalRecorder
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

/**
 * Transactional control plane implementation for authoritative workflow run cancellation (0.7.7m).
 *
 * Enforces:
 * 1. Existence-blind capability gate ([OperatorCapability.CANCEL_WORKFLOW]).
 * 2. Target-scoped workflow authorization ([OperatorWorkflowCancellationPolicy]).
 * 3. Precondition verification against durable checkpoint state.
 * 4. Atomic execution coupling state mutation with audit outbox emission.
 */
class SovereignOpsWorkflowCancellationControlPlane(
    private val mutationStore: SovereignOpsWorkflowCancellationStore,
    private val cancellationPolicy: OperatorWorkflowCancellationPolicy,
    private val clock: Clock = Clock.systemUTC(),
    private val refusalRecorder: SovereignOpsRefusalRecorder = UnconfiguredRefusalRecorder,
) : WorkflowCancellationControlPlane {
    @Suppress("LongMethod", "ReturnCount", "TooGenericExceptionCaught")
    override suspend fun cancel(
        command: WorkflowCancellationCommand,
        authority: OperatorAuthority,
    ): WorkflowCancellationResult {
        // L2: Capability gate (existence-blind)
        if (!authority.holds(OperatorCapability.CANCEL_WORKFLOW)) {
            recordRefusal(
                command = command,
                authority = authority,
                reason = WorkflowUnauthorizedReason.MISSING_CAPABILITY,
                consultedPolicy = null,
            )
            return WorkflowCancellationResult.Unauthorized(
                workflowName = command.workflowName,
                runId = command.runId,
                reason = WorkflowUnauthorizedReason.MISSING_CAPABILITY,
            )
        }

        // L3: Target-scoped authorization policy
        val policyRefusal = cancellationPolicy.evaluate(command.workflowName, authority)
        if (policyRefusal != null) {
            recordRefusal(
                command = command,
                authority = authority,
                reason = policyRefusal,
                consultedPolicy = command.workflowName,
            )
            return WorkflowCancellationResult.Unauthorized(
                workflowName = command.workflowName,
                runId = command.runId,
                reason = policyRefusal,
            )
        }

        // Precondition read: checkpoint existence and current revision
        val currentRun =
            mutationStore.queryRun(command.workflowName, command.runId)
                ?: return WorkflowCancellationResult.NotFound(command.workflowName, command.runId)

        if (currentRun.isCancelled) {
            // Run is already cancelled. Idempotently report the existing cancellation.
            return WorkflowCancellationResult.Cancelled(
                workflowName = command.workflowName,
                runId = command.runId,
                revision = currentRun.revision,
                status = currentRun.status,
                cancelledBy = currentRun.cancelledBy ?: "unknown",
                cancelledAtEpochMillis = currentRun.cancelledAtEpochMillis ?: clock.millis(),
                alreadyCancelled = true,
            )
        }

        if (currentRun.revision != command.expectedRevision) {
            return WorkflowCancellationResult.Stale(
                workflowName = command.workflowName,
                runId = command.runId,
                expectedRevision = command.expectedRevision,
                currentRevision = currentRun.revision,
            )
        }

        // Construct audit intent
        val now = clock.instant()
        val actorDigest = ActorIdentityDigest.of(authority.issuer, authority.subject)
        val auditIntent =
            SovereignOpsAuditOutboxRecord(
                outboxId = UUID.randomUUID().toString(),
                aggregateType = "workflow",
                aggregateIdDigest = sha256Hex("${command.workflowName}:${command.runId}"),
                operation = "cancelWorkflow",
                eventKey = sha256Hex("cancel:${command.workflowName}:${command.runId}:${command.expectedRevision}"),
                actor = authority.subject,
                actorIssuer = authority.issuer,
                actorIdentityDigest = actorDigest,
                workflowRunId = command.runId,
                correlationId = command.correlationId,
                approvalStatus = null,
                approvalVersion = null,
                previousApprovalStatus = "ACTIVE",
                previousApprovalVersion = currentRun.revision,
                reasonDigest = sha256Hex(command.reason),
                reasonLength = command.reason.length,
                createdAt = now,
                status = SovereignOpsAuditOutboxStatus.PREPARED,
                evidenceKind = SovereignOpsAuditEvidenceKind.WORKFLOW_CANCELLED,
            )

        return try {
            val mutationResult =
                mutationStore.cancelWithAuditIntent(
                    command = command,
                    actor = authority.subject,
                    actorIssuer = authority.issuer,
                    actorIdentityDigest = actorDigest,
                    auditIntent = auditIntent,
                )
            WorkflowCancellationResult.Cancelled(
                workflowName = command.workflowName,
                runId = command.runId,
                revision = mutationResult.revision,
                status = mutationResult.status,
                cancelledBy = mutationResult.cancelledBy,
                cancelledAtEpochMillis = mutationResult.cancelledAtEpochMillis,
                alreadyCancelled = mutationResult.alreadyCancelled,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("SwallowedException") e: SovereignOpsWorkflowCancellationConflictException,
        ) {
            staleOrConflict(command)
        }
    }

    @Suppress("ReturnCount")
    private suspend fun staleOrConflict(command: WorkflowCancellationCommand): WorkflowCancellationResult {
        // Check if another concurrent writer won and either advanced the revision or completed cancellation
        val currentRun =
            mutationStore.queryRun(command.workflowName, command.runId)
                ?: return WorkflowCancellationResult.NotFound(command.workflowName, command.runId)

        if (currentRun.isCancelled) {
            return WorkflowCancellationResult.Cancelled(
                workflowName = command.workflowName,
                runId = command.runId,
                revision = currentRun.revision,
                status = currentRun.status,
                cancelledBy = currentRun.cancelledBy ?: "unknown",
                cancelledAtEpochMillis = currentRun.cancelledAtEpochMillis ?: clock.millis(),
                alreadyCancelled = true,
            )
        }

        return if (currentRun.revision != command.expectedRevision) {
            WorkflowCancellationResult.Stale(
                workflowName = command.workflowName,
                runId = command.runId,
                expectedRevision = command.expectedRevision,
                currentRevision = currentRun.revision,
            )
        } else {
            WorkflowCancellationResult.Conflict(
                workflowName = command.workflowName,
                runId = command.runId,
                reason = "tramai-workflow-cancellation-conflict",
            )
        }
    }

    private suspend fun recordRefusal(
        command: WorkflowCancellationCommand,
        authority: OperatorAuthority,
        reason: WorkflowUnauthorizedReason,
        consultedPolicy: String?,
    ) {
        val evidence =
            RefusalEvidence(
                operation = RefusalOperation.CANCEL_WORKFLOW,
                approvalId = "${command.workflowName}:${command.runId}",
                issuer = authority.issuer,
                subject = authority.subject,
                refusalReason = reason.wireName,
                policyVersion = consultedPolicy,
                expectedVersion = command.expectedRevision,
                correlationId = command.correlationId,
                workflowRunId = command.runId,
            )
        refusalRecorder.recordRefusal(evidence)
    }

    companion object {
        private fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            return "sha256:${hash.joinToString("") { "%02x".format(it) }}"
        }
    }
}
