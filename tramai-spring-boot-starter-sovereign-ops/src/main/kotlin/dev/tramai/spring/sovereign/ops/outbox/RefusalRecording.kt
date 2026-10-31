package dev.tramai.spring.sovereign.ops.outbox

/**
 * Outcome of recording one authorization refusal.
 *
 * Both cases are success for the caller: a first attempt and an idempotent retry must be
 * indistinguishable, because the refusal itself is the answer either way. A key match whose content
 * differs is neither case -- it fails closed as `tramai-outbox-event-key-collision`.
 */
sealed interface RefusalRecording {
    /** The record was written by this call. */
    data class Recorded(
        val record: SovereignOpsAuditOutboxRecord,
    ) : RefusalRecording

    /** A record for exactly this refusal already existed; the original is retained unmodified. */
    data class Duplicate(
        val record: SovereignOpsAuditOutboxRecord,
    ) : RefusalRecording
}

/**
 * The narrow write surface the decision plane needs: record one refusal, get back what happened.
 *
 * [SovereignOpsAuditOutboxOperations] extends this, so a store implementation has one `recordRefusal` and
 * the decision plane is not forced to depend on the admin/visibility surface.
 */
fun interface SovereignOpsRefusalRecorder {
    suspend fun recordRefusal(evidence: RefusalEvidence): RefusalRecording
}

/**
 * The default when no recorder is configured: **fails closed**.
 *
 * A refusal that cannot be recorded must never be returned as if it had been (0.7.6f §5.3), so this is a
 * loud failure rather than a silent no-op.
 */
object UnconfiguredRefusalRecorder : SovereignOpsRefusalRecorder {
    override suspend fun recordRefusal(evidence: RefusalEvidence): RefusalRecording =
        throw IllegalStateException(ERROR_REFUSAL_RECORDING_UNCONFIGURED)
}

internal const val ERROR_REFUSAL_RECORDING_UNCONFIGURED: String =
    "tramai-sovereign-ops-refusal-recording-unconfigured"
