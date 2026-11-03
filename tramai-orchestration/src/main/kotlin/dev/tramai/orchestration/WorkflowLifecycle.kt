package dev.tramai.orchestration

import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

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
 * A request to admit one unit of authoritative execution for a run.
 *
 * Admission is an act, not a read: the authority must decide it with a conditional write, so that a
 * cancellation committing at the same instant either loses to it or wins it — never both. [expectedRevision]
 * and [expectedGeneration] are preconditions for the same ABA reason they are on cancellation: a worker
 * holding a generation that has since been rotated must not be able to admit work.
 */
data class WorkflowAdmissionRequest(
    val workflowName: String,
    val workflowId: String,
    val expectedRevision: Long,
    val expectedGeneration: String?,
) {
    init {
        require(workflowName.isNotBlank()) { "workflowName must not be blank" }
        require(workflowId.isNotBlank()) { "workflowId must not be blank" }
    }
}

/**
 * The authority's answer to a [WorkflowAdmissionRequest].
 *
 * [sequence] is the run's admission count after this decision: a successful admission increments it, and a
 * refusal leaves it unchanged. Cancellation reads it to derive its own outcome, which is why an admitted run
 * can never be reported as a clean cancellation.
 */
data class WorkflowAdmission(
    val admitted: Boolean,
    val sequence: Long,
    val cancellation: WorkflowLifecycleState? = null,
) {
    init {
        require(admitted == (cancellation == null)) {
            "an admission is either granted with no cancellation, or refused with the cancellation that refused it"
        }
    }
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

/**
 * Step admission as a capability, so an execution path can be fenced without being handed checkpoint semantics.
 *
 * Nested steps are not checkpoint boundaries, and must not become any to be fenced: they get this and nothing else.
 */
internal fun interface WorkflowStepAdmission {
    suspend fun admit(stepName: String)
}

/** The effect kind every E5-E7 grant is taken for: one local process dispatch. */
internal const val PROCESS_EFFECT_KIND: String = "PROCESS"

/**
 * Process-dispatch admission (E5-E7) as a capability distinct from [WorkflowStepAdmission].
 *
 * A step admission answers "may this step begin" and never authorizes a process: the step was admitted, the effect was
 * not. Every process dispatch therefore asks again for its own grant, immediately before acquisition, and the answer is
 * a single-use permit. Keeping it a separate capability is what stops an admitted step from becoming an admitted
 * process anywhere in the call graph.
 */
internal fun interface WorkflowProcessAdmission {
    suspend fun admitProcess(
        stepName: String,
        effectKind: String,
    ): WorkflowProcessPermit
}

/**
 * One durable grant for one effect dispatch, consumable exactly once.
 *
 * [admissionSequence] is the durable part of the permit's identity, because it is the only component the authority
 * issues; the step name and effect kind are attribution, and attribution is evidence, not authority.
 *
 * Consumption is local to this attempt and deliberately not durable: a worker that dies after dispatch admits again on
 * restart and correctly receives a new sequence. It must never be read as, or relied on for, exactly-once external
 * execution -- the remote side may already have acted on the first grant.
 *
 * One mechanism, one subtype per effect family: the subtypes exist so a permit for one effect cannot be presented at
 * another effect's dispatch site, and the consumption rule cannot drift between them.
 */
internal abstract class EffectPermit(
    val stepName: String,
    val effectKind: String,
    val admissionSequence: Long,
) {
    private val consumed = AtomicBoolean(false)

    /** How this family's permit names itself in the reuse error. */
    protected abstract val effectLabel: String

    /**
     * Consume the permit: true exactly once. A second call is a programming error and fails rather than re-dispatching,
     * because one permit authorizes at most one dispatch attempt.
     */
    fun tryConsume(): Boolean {
        check(!consumed.getAndSet(true)) {
            "$effectLabel permit for '$effectKind' in step '$stepName' (admission $admissionSequence) " +
                "was already consumed; one permit authorizes at most one dispatch"
        }
        return true
    }
}

/** A durable grant for one process dispatch (E5-E7). */
internal class WorkflowProcessPermit(
    stepName: String,
    effectKind: String,
    admissionSequence: Long,
) : EffectPermit(stepName, effectKind, admissionSequence) {
    override val effectLabel: String get() = "process"
}

/**
 * The authorities a governed run gets when it has no durable session at all: every one of them only refuses.
 *
 * Fail-closed by construction rather than by a caller remembering to check -- an effect that cannot be authorized must
 * not be dispatched, and "no authority installed" is never permission. One factory for all families keeps that rule in
 * one place; the messages are effect-kind generic, so the refusal builder is shared too.
 */
internal fun refusingEffectAdmissions(
    workflowName: String,
    workflowId: String,
): WorkflowEffectAdmissions =
    WorkflowEffectAdmissions(
        process =
            WorkflowProcessAdmission { stepName, effectKind ->
                throw refuseProcessAdmission(workflowName, workflowId, stepName, effectKind)
            },
        http =
            WorkflowHttpAdmission { stepName, effectKind ->
                throw refuseProcessAdmission(workflowName, workflowId, stepName, effectKind)
            },
        mcp =
            object : WorkflowMcpAdmission {
                override suspend fun admitServerStart(stepName: String): WorkflowMcpServerStartPermit =
                    throw refuseProcessAdmission(workflowName, workflowId, stepName, MCP_SERVER_START_EFFECT_KIND)

                override suspend fun admitToolCall(stepName: String): WorkflowMcpToolPermit =
                    throw refuseProcessAdmission(workflowName, workflowId, stepName, MCP_TOOL_EFFECT_KIND)
            },
    )

/**
 * The process-dispatch authority in force for the current coroutine frame.
 *
 * Established once by [WorkflowRunner] around a run's step execution, for the same reason [GovernedRunScope] is:
 * a dispatch site deep inside a step (or inside a nested step) asks the frame rather than threading a capability
 * through every signature between the runner and `ProcessBuilder.start()`. Absent means no authority is installed, so
 * an ungoverned dispatch site stays ungoverned instead of inventing one.
 */
internal class WorkflowProcessAdmissionScope(
    val capability: WorkflowProcessAdmission?,
) : AbstractCoroutineContextElement(WorkflowProcessAdmissionScope) {
    companion object Key : CoroutineContext.Key<WorkflowProcessAdmissionScope>
}

/**
 * The process authority for this frame, or null when no governed execution established one.
 *
 * Null is "nothing to ask", never "allowed": the fail-closed decision was already taken by the runner, which installs
 * [refusingProcessAdmission] for a governed run that has nothing durable behind it.
 */
internal suspend fun currentProcessAdmission(): WorkflowProcessAdmission? =
    currentCoroutineContext()[WorkflowProcessAdmissionScope]?.capability

/** The effect kind every E1-E2 grant is taken for: one outbound HTTP attempt. */
internal const val HTTP_EFFECT_KIND: String = "HTTP"

/**
 * HTTP-dispatch admission (E1-E2) as a capability distinct from [WorkflowStepAdmission] and [WorkflowProcessAdmission].
 *
 * Neither answers for the other: an admitted step is not an admitted request, and a process permit is not an HTTP
 * permit, so a site that wants to send must ask this capability and no other. One grant covers one HTTP attempt --
 * the retry loop asks again per attempt, because the retry is a new decision point.
 */
internal fun interface WorkflowHttpAdmission {
    suspend fun admitHttp(
        stepName: String,
        effectKind: String,
    ): WorkflowHttpPermit
}

/** A durable grant for one HTTP attempt (E1-E2); a process permit cannot stand in for it. */
internal class WorkflowHttpPermit(
    stepName: String,
    effectKind: String,
    admissionSequence: Long,
) : EffectPermit(stepName, effectKind, admissionSequence) {
    override val effectLabel: String get() = "http"
}

/**
 * The effect capabilities one governed session issues, built together: process (E5-E7), HTTP (E1-E2) and MCP
 * (E3-E4). They share one durable authority but stay distinct types, so holding one never authorizes another.
 */
internal class WorkflowEffectAdmissions(
    val process: WorkflowProcessAdmission,
    val http: WorkflowHttpAdmission,
    val mcp: WorkflowMcpAdmission,
)

/**
 * The HTTP-dispatch authority in force for the current coroutine frame, established next to
 * [WorkflowProcessAdmissionScope] by [WorkflowRunner], for the same reason: the send site is several frames below
 * the runner, and a nested step inherits the frame rather than receiving a new authority.
 */
internal class WorkflowHttpAdmissionScope(
    val capability: WorkflowHttpAdmission?,
) : AbstractCoroutineContextElement(WorkflowHttpAdmissionScope) {
    companion object Key : CoroutineContext.Key<WorkflowHttpAdmissionScope>
}

/**
 * The HTTP authority for this frame, or null when no governed execution established one.
 *
 * Null is "nothing to ask", never "allowed": the fail-closed decision was taken by the runner, which installs
 * [refusingHttpAdmission] for a governed run that has nothing durable behind it.
 */
internal suspend fun currentHttpAdmission(): WorkflowHttpAdmission? {
    val scope = currentCoroutineContext()[WorkflowHttpAdmissionScope]
    return scope?.capability
}

/** The effect kinds E3-E4 grants are taken for: one MCP server start, one MCP tool invocation. */
internal const val MCP_SERVER_START_EFFECT_KIND: String = "MCP_SERVER_START"
internal const val MCP_TOOL_EFFECT_KIND: String = "MCP_TOOL"

/**
 * MCP admission (E3-E4) as a capability distinct from step, process and HTTP admission.
 *
 * The two MCP effects are independent external operations even though one step performs both: a permitted server start
 * says nothing about the tool invocation that follows, and every reconnect is a new pair of decisions. Neither method's
 * permit type can be presented at the other's dispatch site.
 */
internal interface WorkflowMcpAdmission {
    suspend fun admitServerStart(stepName: String): WorkflowMcpServerStartPermit

    suspend fun admitToolCall(stepName: String): WorkflowMcpToolPermit
}

/** A durable grant for one MCP server start (E4). Cannot authorize the tool call that follows it. */
internal class WorkflowMcpServerStartPermit(
    stepName: String,
    admissionSequence: Long,
) : EffectPermit(stepName, MCP_SERVER_START_EFFECT_KIND, admissionSequence) {
    override val effectLabel: String get() = "mcp server start"
}

/** A durable grant for one MCP tool invocation (E3). Cannot be satisfied by a server-start permit. */
internal class WorkflowMcpToolPermit(
    stepName: String,
    admissionSequence: Long,
) : EffectPermit(stepName, MCP_TOOL_EFFECT_KIND, admissionSequence) {
    override val effectLabel: String get() = "mcp tool"
}

/**
 * The MCP authority in force for the current coroutine frame, established next to the process and HTTP scopes by
 * [WorkflowRunner]. Attribution needs no transport-side plumbing: both dispatch sites sit in the step, which knows its
 * own name.
 */
internal class WorkflowMcpAdmissionScope(
    val capability: WorkflowMcpAdmission?,
) : AbstractCoroutineContextElement(WorkflowMcpAdmissionScope) {
    companion object Key : CoroutineContext.Key<WorkflowMcpAdmissionScope>
}

/** The MCP authority for this frame, or null when no governed execution established one. */
internal suspend fun currentMcpAdmission(): WorkflowMcpAdmission? {
    val scope = currentCoroutineContext()[WorkflowMcpAdmissionScope]
    return scope?.capability
}

/**
 * Raised at an execution boundary when the durable authority refuses admission.
 *
 * The refusal is not a transient error: the run is terminal, or this worker's view of it is stale, and the step (or
 * effect) must not be entered. Callers clean up and stop; they never retry the same attempt.
 */
class WorkflowAdmissionRefusedException(
    message: String,
) : IllegalStateException(message)

/**
 * The refusal a governed step gets when its run can no longer be admitted.
 *
 * Deliberately not a persistence failure: nothing went wrong with the database, the answer was "no".
 */
internal fun refuseStepAdmission(
    workflowName: String,
    workflowId: String,
    stepName: String,
    admission: WorkflowAdmission,
): WorkflowAdmissionRefusedException =
    WorkflowAdmissionRefusedException(
        "workflow '$workflowName' run '$workflowId' cannot enter step '$stepName': " +
            "admission was refused (recorded lifecycle ${admission.cancellation?.status}, " +
            "admission sequence ${admission.sequence})",
    )

/** A process dispatch the authority refused: the effect never leaves the process. */
internal fun refuseProcessAdmission(
    workflowName: String,
    workflowId: String,
    stepName: String,
    effectKind: String,
    admission: WorkflowAdmission,
): WorkflowAdmissionRefusedException =
    WorkflowAdmissionRefusedException(
        "workflow '$workflowName' run '$workflowId' cannot dispatch $effectKind for step '$stepName': " +
            "admission was refused (recorded lifecycle ${admission.cancellation?.status}, " +
            "admission sequence ${admission.sequence})",
    )

/**
 * A governed run with nothing durable to ask: no checkpoint row exists, so no admission precondition can be presented
 * and an unfenced dispatch would be an unauthorized effect. Refusing is the fail-closed direction.
 */
internal fun refuseProcessAdmission(
    workflowName: String,
    workflowId: String,
    stepName: String,
    effectKind: String,
): WorkflowAdmissionRefusedException =
    WorkflowAdmissionRefusedException(
        "workflow '$workflowName' run '$workflowId' cannot dispatch $effectKind for step '$stepName': " +
            "the run is governed but has no durable checkpoint authority to admit the effect, so it must not dispatch",
    )

internal fun refuseAdmission(store: WorkflowCheckpointStore): WorkflowCancellationUnsupportedException =
    WorkflowCancellationUnsupportedException(
        "${store::class.simpleName} cannot admit workflow execution authoritatively; " +
            "an unsupported profile must not admit work it cannot fence against cancellation",
    )

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
