package dev.tramai.orchestration

/**
 * Terminal lifecycle state of one workflow run.
 *
 * Separate from [WorkflowRecoveryState] on purpose: recovery means an operator must resolve the run before
 * it may progress, while a lifecycle state means the run is dead. Collapsing the two would make a resolved
 * recovery indistinguishable from a cancellation.
 */
enum class WorkflowLifecycleStatus(
    val wireName: String,
) {
    /** Cancellation committed while no attempt was in flight. */
    CANCELLED("cancelled"),

    /**
     * Cancellation committed while an attempt was already in flight, so an external effect may have landed.
     * Never report this as a clean cancellation: the difference is the whole point of the state.
     */
    CANCELLED_UNCERTAIN("cancelled_uncertain"),
}

/**
 * Durable record of a run's terminal cancellation, as stored by the lifecycle authority.
 *
 * [revision] is the revision the cancellation commit produced. It is the proof that this call owns the
 * linearization point: two callers that both claim success cannot both report the same revision unless one
 * of them read a state another caller committed.
 */
data class WorkflowLifecycleState(
    val status: WorkflowLifecycleStatus,
    val revision: Long,
    val checkpointGeneration: String?,
    val cancelledAtEpochMillis: Long,
    val cancelledBy: String,
    val reason: String,
) {
    init {
        require(cancelledBy.isNotBlank()) { "cancelledBy must not be blank" }
        require(reason.isNotBlank()) { "reason must not be blank" }
    }
}

/**
 * A cancellation command: what to cancel, against which durable state, by whom, and why.
 *
 * [expectedRevision] and [expectedGeneration] are both required. The revision alone is not enough: a
 * generation is rotated when a run's identity is re-established, so a privileged command that presents only
 * a matching revision could adopt a generation it never observed (ABA). A null generation is allowed only
 * because legacy rows predate the column, and it then matches only a null generation.
 */
data class WorkflowCancellationRequest(
    val workflowName: String,
    val workflowId: String,
    val expectedRevision: Long,
    val expectedGeneration: String?,
    val actor: String,
    val reason: String,
    val cancelledAtEpochMillis: Long,
    val uncertain: Boolean = false,
) {
    init {
        require(workflowName.isNotBlank()) { "workflowName must not be blank" }
        require(workflowId.isNotBlank()) { "workflowId must not be blank" }
        require(actor.isNotBlank()) { "actor must not be blank" }
        require(reason.isNotBlank()) { "reason must not be blank" }
    }

    val status: WorkflowLifecycleStatus
        get() = if (uncertain) WorkflowLifecycleStatus.CANCELLED_UNCERTAIN else WorkflowLifecycleStatus.CANCELLED
}

/**
 * Raised when a store cannot record a cancellation authoritatively.
 *
 * This is a capability refusal, not a persistence failure: a profile that cannot answer atomically must
 * refuse rather than fall back to a local, in-process cancellation that looks equivalent and is not.
 */
class WorkflowCancellationUnsupportedException(
    message: String,
) : UnsupportedOperationException(message)

internal fun refuseCancellation(store: WorkflowCheckpointStore): WorkflowCancellationUnsupportedException =
    WorkflowCancellationUnsupportedException(
        "${store::class.simpleName} cannot record workflow cancellation authoritatively; " +
            "use a store that implements the lifecycle state (JDBC) instead of a local-only cancellation",
    )

/**
 * The lifecycle columns of one stored row, before interpretation.
 *
 * Kept as a value type so decoding stays a zero-argument operation on the row rather than a nine-parameter
 * function, and so the "is this row cancelled" question has exactly one place to answer.
 */
internal data class WorkflowLifecycleRow(
    val workflowName: String,
    val workflowId: String,
    val lifecycleStatus: String?,
    val revision: Long,
    val checkpointGeneration: String?,
    val cancelledAtEpochMillis: Long?,
    val cancelledBy: String?,
    val cancellationReason: String?,
) {
    fun toState(): WorkflowLifecycleState? {
        if (lifecycleStatus.isNullOrBlank()) return null
        val status =
            WorkflowLifecycleStatus.entries.firstOrNull { it.wireName == lifecycleStatus }
                ?: throw corruption("unknown lifecycle status '$lifecycleStatus'")
        val cancelledAt = cancelledAtEpochMillis
        val actor = cancelledBy?.ifBlank { null }
        val reason = cancellationReason?.ifBlank { null }
        if (cancelledAt == null || actor == null || reason == null) {
            throw corruption("incomplete cancellation record")
        }
        return WorkflowLifecycleState(
            status = status,
            revision = revision,
            checkpointGeneration = checkpointGeneration,
            cancelledAtEpochMillis = cancelledAt,
            cancelledBy = actor,
            reason = reason,
        )
    }

    private fun corruption(detail: String): CorruptCheckpointException =
        CorruptCheckpointException(
            "Persisted workflow cancellation state is invalid for ('$workflowName', '$workflowId'): $detail",
            lifecycleStatus,
        )
}
