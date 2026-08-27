package dev.tramai.orchestration

import java.time.Instant

enum class StepAttemptStatus {
    STARTED,
    COMPLETED,
    FAILED,
    CANCELLED,
    UNKNOWN,
}

enum class ReplayPolicy {
    PURE,
    IDEMPOTENT,
    EXTERNALLY_IDEMPOTENT,
    NON_REPLAYABLE,
}

enum class StepAttemptResolutionAction {
    RETRY_APPROVED,
    WORKFLOW_FAILED,
}

data class StepAttemptRecord(
    val runId: String,
    val stepName: String,
    val attemptId: String,
    val workerId: String,
    val leaseToken: String,
    val status: StepAttemptStatus,
    val startedAt: Long,
    val completedAt: Long? = null,
    val idempotencyKey: String? = null,
    val replayPolicy: ReplayPolicy,
    val inputFingerprint: String? = null,
    val outputSummary: String? = null,
    val resolutionReason: String? = null,
    val resolutionAtEpochMillis: Long? = null,
    val resolutionAction: StepAttemptResolutionAction? = null,
    val approvedIdempotencyKey: String? = null,
)

internal fun decodeResolutionAction(name: String?): StepAttemptResolutionAction? =
    if (name.isNullOrBlank()) {
        null
    } else {
        StepAttemptResolutionAction.entries.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Unknown StepAttemptResolutionAction: '$name'")
    }

/**
 * Persistent storage for [StepAttemptRecord]s, decoupled from checkpoint storage.
 *
 * Attempt identity is the tuple `(runId, stepName, attemptId)`. Implementations must
 * treat the identity as immutable: an update that changes any identity component is
 * rejected, and no operation may make a record reachable under a different identity
 * than the one it was written with.
 *
 * Replacement semantics:
 * - [recordStepAttempt] stores the record, replacing any existing record with the same
 *   identity (upsert). It is NOT an authorization-sensitive transition and must not be
 *   used to change resolution/approval fields that guard execution.
 * - [updateStepAttempt] updates an existing record and fails (throws) when no record
 *   with the identity exists.
 * - [compareAndSetStepAttempt] replaces [expected] with [updated] only when the
 *   persisted record is exactly [expected], and returns `false` otherwise. The persisted
 *   record comparison is exact (all fields, including nullability). [updated] must keep
 *   the identity of [expected]. This is the ONLY operation authorized for
 *   authorization-critical transitions (retry approval, approval consumption,
 *   stale-approval voiding): a checkpoint-revision fence cannot protect the attempt
 *   record because the attempt store and the checkpoint store are independent.
 *
 * Ordering and retrieval:
 * - [listStepAttempts] returns records for one run ordered deterministically by
 *   `startedAt`, then `stepName`. For attempts with EQUAL persisted `startedAt`,
 *   the tie-break must be a stable creation-order authority, never the random
 *   `attemptId` (the in-memory implementation preserves insertion order; the
 *   file/JDBC implementations still use `attemptId` pending a durable sequence
 *   column — tracked as a cross-store contract follow-up).
 * - [latestStepAttempt] returns the record for the given run and step with the maximum
 *   `startedAt`; ties resolve to the LAST-created attempt (in-memory: insertion order;
 *   file/JDBC pending the same follow-up), or `null` when absent.
 *
 * Durability and failure behaviour:
 * - Persistent implementations must survive store recreation and process restart, and
 *   must coordinate concurrent access so exactly one competing CAS wins and a failed or
 *   cancelled mutation leaves the previous complete record readable.
 * - Unknown or malformed persisted values (enum names, schema versions, fingerprints,
 *   identities) fail closed with [StepAttemptRecordCorruptionException]; a malformed
 *   record is never silently skipped or returned.
 * - Operations must propagate coroutine cancellation as [kotlinx.coroutines.CancellationException]
 *   without leaking connections, statements, file locks, or partial mutations.
 * - Implementations make no exactly-once external-effect guarantee and introduce no
 *   cross-store transaction with the checkpoint store; partial approval-first transitions
 *   are safely repeatable, not atomic.
 */
interface StepAttemptRecordStore {
    suspend fun recordStepAttempt(record: StepAttemptRecord): StepAttemptRecord

    suspend fun updateStepAttempt(record: StepAttemptRecord): StepAttemptRecord

    /**
     * Atomically replace the persisted attempt from [expected] to [updated] only when the
     * persisted record is still exactly [expected].
     *
     * Used for authorization-critical transitions (retry approval, approval consumption,
     * stale-approval voiding) where a checkpoint-revision fence cannot protect the attempt
     * record: the attempt store and the checkpoint store are independent, so a stale operator
     * or worker must not overwrite a concurrent successful authorization.
     *
     * @return true when the record was replaced; false when the persisted record differs
     * from [expected] (caller must reload and re-validate before deciding).
     */
    suspend fun compareAndSetStepAttempt(
        expected: StepAttemptRecord,
        updated: StepAttemptRecord,
    ): Boolean

    suspend fun latestStepAttempt(
        runId: String,
        stepName: String,
    ): StepAttemptRecord?

    suspend fun listStepAttempts(runId: String): List<StepAttemptRecord>
}

class StepAttemptRecordCorruptionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    var failureCode: PersistenceFailureCode? = null
        internal set
    var safeFactoryTrusted: Boolean = false
        internal set
}

class NonReplayableStepStateUnknownException(
    val runId: String,
    val stepName: String,
    val priorWorkerId: String,
    val attemptTime: Long,
    val recoveryInstructions: String = "Inspect the external side effect for this step, mark the run safe to resume manually, or roll it back before retrying.",
) : RuntimeException(
    buildString {
        append("Workflow run '")
        append(runId)
        append("' cannot safely resume step '")
        append(stepName)
        append("' because worker '")
        append(priorWorkerId)
        append("' started it at ")
        append(Instant.ofEpochMilli(attemptTime))
        append(" and the final state is unknown. ")
        append(recoveryInstructions)
    },
)

/**
 * Can the workflow step be reconstructed and replayed after interruption?
 * Independent of [WorkflowStepRepetitionSafety]: a reconstructable step is
 * not necessarily safe to repeat.
 */
internal enum class WorkflowStepReplayability {
    REPLAYABLE,
    NON_REPLAYABLE,
}

/**
 * Is repeating the step's side effect safe? Independent of
 * [WorkflowStepReplayability]: a repeat-safe step is not necessarily
 * reconstructable, and a reconstructable step is not necessarily repeat-safe.
 */
internal enum class WorkflowStepRepetitionSafety {
    PURE,
    IDEMPOTENT,
    EXTERNALLY_IDEMPOTENT,
    UNSAFE,
}

/**
 * Two-dimensional runtime replay model: replayability and repetition safety
 * are separate facts, plus the optional stable idempotency key required for
 * externally-idempotent replay. The legacy single-axis [ReplayPolicy] is a
 * persistence-compatibility encoding of this model (see
 * [ReplayPolicyCompatibility]).
 */
internal data class WorkflowStepReplayDescriptor(
    val replayability: WorkflowStepReplayability,
    val repetitionSafety: WorkflowStepRepetitionSafety,
    val idempotencyKey: String? = null,
)

/**
 * Persistent stores require non-blank identity components: base64url-encoding an empty
 * value produces an empty path segment that collapses under [java.nio.file.Path.resolve],
 * and blank runId/stepName/attemptId would otherwise alias or walk store roots.
 */
internal fun StepAttemptRecord.requirePersistableIdentity() {
    require(runId.isNotBlank() && stepName.isNotBlank() && attemptId.isNotBlank()) {
        "Step-attempt identity components (runId, stepName, attemptId) must not be blank"
    }
}
