package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.spring.sovereign.ops.SovereignOpsAuditEmitter
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Instant

/**
 * Dispatches pending audit outbox records to the [SovereignOpsAuditEmitter].
 *
 * Behavior:
 * 1. Claims up to [limit] dispatchable records (PENDING, FAILED_RETRYABLE,
 *    or expired EMITTING).
 * 2. For each claimed record, emits the audit event via
 *    [SovereignOpsAuditEmitter.evidenceFromOutbox] — this uses pre-digested values from the outbox record
 *    without re-hashing, and dispatches on what the record is evidence of (0.7.6f §6).
 * 3. On success: marks the record as [SovereignOpsAuditOutboxStatus.EMITTED].
 * 4. On an [EvidenceFailure] — an unrepresentable kind, or legacy evidence whose persisted state cannot
 *    name a kind — marks as [SovereignOpsAuditOutboxStatus.FAILED_PERMANENT]. Parking is deliberate: the
 *    evidence is retained and visible, and retrying cannot make it representable.
 * 5. On any other [RuntimeException] (including emission failure): marks as
 *    [SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE].
 * 6. On [CancellationException]: rethrows without marking.
 *
 * @param outboxStore The outbox store to claim and update records.
 * @param auditEmitter The audit emitter for actual emission.
 * @param claimedBy Identity used when claiming pending records.
 * @param clock Clock for timestamps.
 */
class SovereignOpsAuditOutboxDispatcher(
    private val outboxStore: SovereignOpsAuditOutboxStore,
    private val auditEmitter: SovereignOpsAuditEmitter,
    private val claimedBy: String = "sovereign-ops-dispatcher",
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Dispatch pending outbox records.
     *
     * @param limit Maximum number of records to process.
     * @return Summary of the dispatch run.
     */
    suspend fun dispatchPending(limit: Int): SovereignOpsAuditOutboxDispatchResult {
        val now = clock.instant()

        completeQuarantineRecovery(limit, now)

        val claimed =
            outboxStore.claimPending(
                claimedBy = claimedBy,
                limit = limit,
                now = now,
            )

        var emitted = 0
        var failedRetryable = 0
        var failedPermanent = 0

        for (record in claimed) {
            when (emitOne(record)) {
                Outcome.EMITTED -> emitted++
                Outcome.RETRYABLE -> failedRetryable++
                Outcome.PERMANENT -> failedPermanent++
            }
        }

        return SovereignOpsAuditOutboxDispatchResult(
            claimed = claimed.size,
            emitted = emitted,
            failedRetryable = failedRetryable,
            failedPermanent = failedPermanent,
        )
    }

    /**
     * Emits one claimed record and records the outcome.
     *
     * The classification is total: the emitter is asked for the record's own kind, and every failure mode
     * is named rather than collapsed into "retry later".
     */
    private suspend fun emitOne(record: SovereignOpsAuditOutboxRecord): Outcome =
        try {
            auditEmitter.evidenceFromOutbox(record)
            outboxStore.markEmitted(
                outboxId = record.outboxId,
                expectedStatus = SovereignOpsAuditOutboxStatus.EMITTING,
                expectedAttemptCount = record.attemptCount,
                emittedAt = clock.instant(),
            )
            Outcome.EMITTED
        } catch (e: CancellationException) {
            throw e
        } catch (e: EvidenceFailure) {
            // Evidence the deployment cannot represent is parked: retrying cannot make it representable.
            park(record, e.errorCode)
            Outcome.PERMANENT
        } catch (e: UnsupportedOperationException) {
            // Narrow on purpose: only the evidence contract's own "I cannot represent this kind" codes are
            // permanent. An unrelated UnsupportedOperationException from inside an emitter is an ordinary
            // failure and stays retryable -- swallowing it would park evidence for someone else's bug.
            if (e.message in UNSUPPORTED_EVIDENCE_CODES) {
                park(record, ERROR_UNSUPPORTED_EVIDENCE_KIND)
                Outcome.PERMANENT
            } else {
                retry(record, e)
            }
        } catch (e: Exception) {
            retry(record, e)
        }

    private suspend fun retry(
        record: SovereignOpsAuditOutboxRecord,
        e: Exception,
    ): Outcome {
        outboxStore.markFailed(
            outboxId = record.outboxId,
            expectedStatus = SovereignOpsAuditOutboxStatus.EMITTING,
            expectedAttemptCount = record.attemptCount,
            errorCode = e::class.simpleName ?: "unknown",
            retryable = true,
        )
        return Outcome.RETRYABLE
    }

    private suspend fun park(
        record: SovereignOpsAuditOutboxRecord,
        errorCode: String,
    ) {
        outboxStore.markFailed(
            outboxId = record.outboxId,
            expectedStatus = SovereignOpsAuditOutboxStatus.EMITTING,
            expectedAttemptCount = record.attemptCount,
            errorCode = errorCode,
            retryable = false,
        )
    }

    /**
     * 0.7.6f: one undecodable record must not stop healthy evidence from dispatching.
     *
     * The dispatch run is where a durable quarantine marker gets completed, so the recovery pass runs
     * before the claim: damaged evidence is classified and quarantined (metadata only) and the healthy
     * records in the same batch go out. A store without a durable quarantine surface simply skips this.
     *
     * A systemic failure is not swallowed: `tramai-outbox-decryption-unavailable` propagates, because a
     * wrong deployment key must reach an operator, not turn into an empty successful dispatch.
     */
    private suspend fun completeQuarantineRecovery(
        limit: Int,
        now: Instant,
    ) {
        val recovery = outboxStore.quarantineStore() ?: return
        recovery.quarantineUndecodableEvidence(limit = limit, now = now)
    }
}

/**
 * Summary of a dispatch run.
 */
data class SovereignOpsAuditOutboxDispatchResult(
    val claimed: Int,
    val emitted: Int,
    val failedRetryable: Int,
    val failedPermanent: Int,
)

/** What one emission attempt did to its record. */
private enum class Outcome {
    EMITTED,
    RETRYABLE,
    PERMANENT,
}

/** The messages an emitter uses to say "this build cannot represent this evidence kind". */
private val UNSUPPORTED_EVIDENCE_CODES: Set<String> =
    setOf(
        "tramai-sovereign-ops-approval-evidence-unsupported",
        "tramai-sovereign-ops-refusal-evidence-unsupported",
        "tramai-sovereign-ops-outbox-emission-unsupported",
    )
