package dev.tramai.spring.sovereign.ops.outbox

import java.time.Instant

/**
 * 0.7.6f: durable, metadata-level quarantine for audit-outbox evidence the store cannot decode.
 *
 * A decoded listing cannot contain a record that cannot be decoded, so recovery gets its own surface
 * instead of being smuggled through [SovereignOpsAuditOutboxStore]. Nothing here decodes damaged
 * evidence: every operation is keyed by [QuarantinedEvidenceSummary.locator], which each backend can
 * produce without reading the ciphertext.
 *
 * The primary invariant is two-sided: one damaged record must not stop healthy evidence from being
 * dispatched, and uncertainty about the deployment key must never quarantine healthy evidence. The
 * second half is enforced by [quarantineUndecodableEvidence], which refuses to act at all when no
 * scanned record decodes.
 *
 * Implementations are only required where evidence is durable. A store that cannot provide this
 * surface must fail the caller explicitly rather than reporting an empty quarantine as success.
 */
interface SovereignOpsAuditOutboxQuarantineStore {
    /**
     * Classifies the next [limit] dispatchable records and quarantines the ones that cannot be read.
     *
     * Classification happens for the whole batch before anything is mutated, so a systemic failure
     * leaves every record untouched. Quarantining preserves the stored ciphertext byte-for-byte and
     * only adds metadata.
     *
     * @throws IllegalStateException with [ERROR_DECRYPTION_UNAVAILABLE] when no scanned record decodes,
     *   or when every failure carries the same signature: the batch behaves like a wrong key, not like
     *   individual corruption, and mass quarantine is the wrong response.
     */
    suspend fun quarantineUndecodableEvidence(
        limit: Int,
        now: Instant,
    ): QuarantineReport = throw unsupported()

    /** Quarantined evidence as metadata, newest first. Never returns decoded evidence fields. */
    suspend fun listQuarantined(limit: Int): List<QuarantinedEvidenceSummary> = throw unsupported()

    /**
     * The stored encrypted bytes for a quarantined record, untouched and never decoded.
     *
     * @return null when [locator] is not quarantined.
     */
    suspend fun readQuarantinedCiphertext(locator: String): ByteArray? = throw unsupported()

    companion object {
        /** The stored payload could not be decrypted or decoded. */
        const val LABEL_UNDECODABLE: String = "undecodable-evidence"

        /** The payload decoded, but contradicts the queryable columns stored beside it. */
        const val LABEL_CORRUPT: String = "corrupt-evidence"

        /** Raised instead of quarantining when the whole batch looks unusable. */
        const val ERROR_DECRYPTION_UNAVAILABLE: String = "tramai-outbox-decryption-unavailable"

        /** The store has no durable quarantine surface. */
        const val ERROR_UNSUPPORTED: String = "tramai-sovereign-ops-outbox-quarantine-unsupported"

        /** Failure detail is bounded: a quarantine row is metadata, not a stack-trace store. */
        const val MAX_FAILURE_DETAIL: Int = 512

        private fun unsupported(): UnsupportedOperationException = UnsupportedOperationException(ERROR_UNSUPPORTED)
    }
}

/**
 * One quarantined record, as metadata.
 *
 * [locator] is the authoritative recovery handle. [outboxId] and [eventKey] are nullable because a
 * backend that stores them inside the ciphertext genuinely cannot recover them once decryption fails;
 * a null is an explicit, expected state and must never be filled with a synthesised substitute.
 */
data class QuarantinedEvidenceSummary(
    val locator: String,
    val outboxId: String?,
    val eventKey: String?,
    val payloadDigest: String,
    val label: String,
    val quarantinedAt: Instant,
    val failureDetail: String?,
)

/**
 * Outcome of one [SovereignOpsAuditOutboxQuarantineStore.quarantineUndecodableEvidence] run.
 *
 * [decodable] is the control count: the number of scanned records that decoded and therefore proved
 * the deployment key works. [skipped] counts records already quarantined, which are never re-written.
 */
data class QuarantineReport(
    val scanned: Int,
    val decodable: Int,
    val quarantined: List<QuarantinedEvidenceSummary>,
    val skipped: Int,
)
