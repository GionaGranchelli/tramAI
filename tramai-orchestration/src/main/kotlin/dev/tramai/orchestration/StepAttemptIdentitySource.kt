package dev.tramai.orchestration

/**
 * 8.3b2b — step-attempt identity authority.
 *
 * Every newly-created [StepAttemptRecord.attemptId] originates from this explicit
 * orchestration composition boundary. Attempt identity is opaque: it must never
 * acquire chronology, wall-time, lease, or claim authority (the #318 split keeps
 * `attemptSequence` as the only chronology; `startedAt` as the only wall time).
 *
 * Invariant: an attempt samples its identity exactly once at creation,
 * propagates it unchanged through update/CAS/re-record, and resume/recovery
 * never manufactures replacement identity.
 *
 * The interface is deliberately a single method: there is exactly one identity
 * kind an attempt owns. Blank values are rejected by the consumer (fail closed
 * before persistence), not silently normalized here.
 */
internal fun interface StepAttemptIdentitySource {
    fun newAttemptId(): String
}

/** Production default: UUID-backed opaque identity. */
internal object DefaultStepAttemptIdentitySource : StepAttemptIdentitySource {
    override fun newAttemptId(): String = java.util.UUID.randomUUID().toString()
}
