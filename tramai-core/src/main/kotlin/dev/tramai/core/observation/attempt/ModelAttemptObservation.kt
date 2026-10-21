package dev.tramai.core.observation.attempt

import java.time.Instant

/**
 * Terminal outcome observed for one provider attempt (Epic 0.7.5b).
 *
 * [UNKNOWN] is a real answer: an attempt whose callbacks stopped without a response, a failure or a
 * cancellation cannot be called successful. Nothing may be inferred from its absence.
 */
enum class AttemptOutcome {
    /** A provider response reached the engine. */
    SUCCEEDED,

    /** The attempt failed before producing a response. */
    FAILED,

    /** The attempt was cancelled externally. */
    CANCELLED,

    /** The attempt ended without any of the above; the reason is not observable here. */
    UNKNOWN,
}

/**
 * Where an observation came from. Observations are attributed telemetry, never governance authority.
 */
enum class AttemptProvenance {
    /** Recorded by an in-process observer inside this engine. */
    OBSERVED_IN_PROCESS,
}

/**
 * Content-free record of exactly one model attempt (Epic 0.7.5b).
 *
 * Deliberately carries no prompt, response, tool payload or reasoning content: the type cannot
 * express protected material, so no filter can forget to strip it.
 *
 * @property invocationId identity of the invocation; `(invocationId, attempt)` is unique
 * @property requestedModel the model the operation asked for
 * @property reportedModel the model the provider claimed it used, or `null` when unreported
 * @property inputTokens prompt tokens as reported, or `null` when the provider reported none
 * @property outputTokens completion tokens as reported, or `null` when the provider reported none
 * @property thinkingTokens reasoning tokens as reported separately. Providers that fold reasoning
 *   into the completion count would be double-counted by any total, so this record never stores a
 *   total and never adds the two together.
 * @property finishReason the normalized finish reason present on the response, or `null` when there
 *   was no response
 * @property finishReasonReported always `false` on this path: [dev.tramai.core.model.ModelResponse]
 *   defaults `finishReason` to `STOP`, so a provider that reported nothing is indistinguishable from
 *   one that reported a normal stop. A default is never promoted to a provider claim.
 * @property failureCategory simple class name of the failure, never its message
 * @property durationMillis observed wall-clock duration of the attempt
 */
data class ModelAttemptObservation(
    val invocationId: String,
    val attempt: Int,
    val workflowRunId: String?,
    val correlationId: String?,
    val routeIndex: Int?,
    val providerId: String,
    val requestedModel: String,
    val reportedModel: String?,
    val startedAt: Instant,
    val completedAt: Instant,
    val durationMillis: Long,
    val outcome: AttemptOutcome,
    val failureCategory: String?,
    val finishReason: String?,
    val finishReasonReported: Boolean,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val thinkingTokens: Int?,
    val provenance: AttemptProvenance,
) {
    init {
        require(invocationId.isNotBlank()) { "attempt-observation-blank-invocation-id" }
        require(attempt >= 0) { "attempt-observation-negative-attempt: $attempt" }
        require(providerId.isNotBlank()) { "attempt-observation-blank-provider-id" }
        require(requestedModel.isNotBlank()) { "attempt-observation-blank-requested-model" }
        require(durationMillis >= 0) { "attempt-observation-negative-duration: $durationMillis" }
        require(!completedAt.isBefore(startedAt)) { "attempt-observation-completion-before-start" }
    }
}

/** Result of appending one observation. */
enum class AttemptAppendOutcome {
    /** The row was written. */
    RECORDED,

    /** An observation with the same `(invocationId, attempt)` was already stored and was left alone. */
    ALREADY_RECORDED,

    /** Nothing is persisted in this process, so nothing was written. */
    UNINSTRUMENTED,
}

/**
 * What a store can say about the attempts behind an identity.
 *
 * [Uninstrumented] is distinct from an empty [Available]: "nothing was ever recorded" and "recording
 * happened and produced no rows" are different findings, and only the second is evidence about the
 * run.
 */
sealed interface AttemptHistory {
    /** Observations this store holds, oldest first. Never fabricated, possibly empty. */
    data class Available(
        val attempts: List<ModelAttemptObservation>,
    ) : AttemptHistory

    /** No observation persistence is configured, so history cannot be established either way. */
    data object Uninstrumented : AttemptHistory
}

/**
 * Append-only store for [ModelAttemptObservation] (Epic 0.7.5b).
 *
 * Deliberately separate from the authoritative audit chain: these records are observed telemetry and
 * must never be read as proof that a governance decision was enforced.
 *
 * Implementations are blocking; the engine calls them from inside a provider attempt and must not
 * change that attempt's outcome on failure.
 */
interface AttemptObservationStore {
    /** Stores one observation, idempotently for the same `(invocationId, attempt)`. */
    fun append(observation: ModelAttemptObservation): AttemptAppendOutcome

    /** Attempts recorded for one governed run, oldest first. */
    fun findByWorkflowRun(workflowRunId: String): AttemptHistory

    /** Attempts recorded for one invocation, oldest first. */
    fun findByInvocation(invocationId: String): AttemptHistory
}

/** Store used when no observation persistence is configured: it records nothing and claims nothing. */
object UninstrumentedAttemptObservationStore : AttemptObservationStore {
    private val nothingRecorded = AttemptAppendOutcome.UNINSTRUMENTED

    override fun append(observation: ModelAttemptObservation): AttemptAppendOutcome = nothingRecorded

    override fun findByWorkflowRun(workflowRunId: String): AttemptHistory = AttemptHistory.Uninstrumented

    override fun findByInvocation(invocationId: String): AttemptHistory = AttemptHistory.Uninstrumented
}
