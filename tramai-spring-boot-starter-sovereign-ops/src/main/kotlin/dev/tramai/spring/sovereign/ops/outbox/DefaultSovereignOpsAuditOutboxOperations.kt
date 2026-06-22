package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.spring.sovereign.ops.SovereignOpsProperties
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * Default implementation of [SovereignOpsAuditOutboxOperations].
 *
 * Delegates read operations to [SovereignOpsAuditOutboxStore] and
 * mutation operations (retry, mark terminal) to the dispatcher
 * and store respectively.
 *
 * ## Validation
 * - Limits are bounded to [MAX_LIMIT] (500), defaulting to [DEFAULT_LIMIT] (100)
 * - outboxId must be non-blank, ≤ 128 chars, matching SAFE_ID pattern
 * - reason must be non-blank, ≤ [MAX_REASON_LENGTH] (4096) chars
 * - Mutations require `mutations-enabled=true` and dispatcher presence
 *
 * ## Security
 * - All summaries are created via [SovereignOpsAuditOutboxRecord.toSummary]
 *   which sanitises error codes using a whitelist regex
 * - No raw reason, raw approval ID, tokens, or envelopes in summaries
 */
class DefaultSovereignOpsAuditOutboxOperations(
    private val outboxStore: SovereignOpsAuditOutboxStore,
    private val outboxDispatcher: SovereignOpsAuditOutboxDispatcher?,
    private val properties: SovereignOpsProperties,
    private val recoveryResolver: SovereignOpsApprovalRecoveryResolver,
) : SovereignOpsAuditOutboxOperations {

    private companion object {
        private const val DEFAULT_LIMIT = 100
        private const val MAX_LIMIT = 500
        private const val MAX_REASON_LENGTH = 4096
        private val SAFE_OUTBOX_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:@+-]{0,127}")
        private val SAFE_ERROR_CODE = Regex("[A-Za-z0-9][A-Za-z0-9._:@+-]{0,127}")
        private val STATUS_ORDER = listOf(
            SovereignOpsAuditOutboxStatus.PREPARED,
            SovereignOpsAuditOutboxStatus.PENDING,
            SovereignOpsAuditOutboxStatus.EMITTING,
            SovereignOpsAuditOutboxStatus.EMITTED,
            SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE,
            SovereignOpsAuditOutboxStatus.FAILED_PERMANENT,
        )
    }

    override suspend fun listOutboxRecords(
        status: SovereignOpsAuditOutboxStatus?,
        limit: Int?,
    ): List<SovereignOpsAuditOutboxSummary> {
        val boundedLimit = validateLimit(limit)
        val records = if (status != null) {
            outboxStore.listByStatus(status, boundedLimit)
        } else {
            listAcrossStatuses(boundedLimit)
        }
        return records.map { it.toSummary() }
    }

    override suspend fun retryPending(limit: Int?): SovereignOpsAuditOutboxDispatchResult {
        val boundedLimit = validateLimit(limit)
        val dispatcher = outboxDispatcher
            ?: throw IllegalStateException("tramai-sovereign-ops-audit-unavailable")
        return try {
            dispatcher.dispatchPending(boundedLimit)
        } catch (e: CancellationException) {
            throw e
        }
    }

    override suspend fun markPreparedFailed(
        outboxId: String,
        reason: String,
    ): SovereignOpsAuditOutboxSummary {
        check(properties.mutationsEnabled) {
            "tramai-sovereign-ops-mutations-disabled"
        }
        validateOutboxId(outboxId)
        validateReason(reason)

        val record = outboxStore.get(outboxId)
            ?: throw IllegalStateException(ERROR_INVALID_OUTBOX_ID)
        require(record.status == SovereignOpsAuditOutboxStatus.PREPARED) {
            "tramai-sovereign-ops-outbox-status-mismatch"
        }
        return outboxStore.markFailed(
            outboxId = outboxId,
            expectedStatus = SovereignOpsAuditOutboxStatus.PREPARED,
            errorCode = "operator-marked-prepared-failed",
            retryable = false,
        ).toSummary()
    }

    override suspend fun recoverPrepared(limit: Int?): SovereignOpsAuditOutboxRecoverySummary {
        check(properties.mutationsEnabled) {
            "tramai-sovereign-ops-mutations-disabled"
        }

        val boundedLimit = validateLimit(limit)
        val prepared = outboxStore.listByStatus(SovereignOpsAuditOutboxStatus.PREPARED, boundedLimit)

        var movedToPending = 0
        var markedFailedPermanent = 0
        var skippedUnresolved = 0
        var resolverFailures = 0

        for (record in prepared) {
            try {
                when (recoveryResolver.resolvePreparedOutboxRecord(record)) {
                    SovereignOpsPreparedRecoveryDecision.COMMITTED_DENIED -> {
                        outboxStore.markReadyForDispatch(
                            outboxId = record.outboxId,
                            expectedStatus = SovereignOpsAuditOutboxStatus.PREPARED,
                        )
                        movedToPending++
                    }

                    SovereignOpsPreparedRecoveryDecision.NOT_COMMITTED -> {
                        outboxStore.markFailed(
                            outboxId = record.outboxId,
                            expectedStatus = SovereignOpsAuditOutboxStatus.PREPARED,
                            errorCode = "prepared-recovery-not-committed",
                            retryable = false,
                        )
                        markedFailedPermanent++
                    }

                    SovereignOpsPreparedRecoveryDecision.UNKNOWN -> {
                        skippedUnresolved++
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                resolverFailures++
            }
        }

        return SovereignOpsAuditOutboxRecoverySummary(
            inspected = prepared.size,
            movedToPending = movedToPending,
            markedFailedPermanent = markedFailedPermanent,
            skippedUnresolved = skippedUnresolved,
            resolverFailures = resolverFailures,
        )
    }

    private suspend fun listAcrossStatuses(limit: Int): List<SovereignOpsAuditOutboxRecord> {
        val results = ArrayList<SovereignOpsAuditOutboxRecord>(limit)
        for (status in STATUS_ORDER) {
            if (results.size >= limit) break
            val remaining = limit - results.size
            results += outboxStore.listByStatus(status, remaining)
        }
        return results
    }

    private fun validateLimit(limit: Int?): Int {
        val resolved = limit ?: DEFAULT_LIMIT
        require(resolved > 0) { "tramai-sovereign-ops-invalid-limit" }
        require(resolved <= MAX_LIMIT) { "tramai-sovereign-ops-invalid-limit" }
        return resolved
    }

    private fun validateOutboxId(outboxId: String) {
        require(outboxId.isNotBlank()) { ERROR_INVALID_OUTBOX_ID }
        require(outboxId.length <= 128) { ERROR_INVALID_OUTBOX_ID }
        require(SAFE_OUTBOX_ID.matches(outboxId)) { ERROR_INVALID_OUTBOX_ID }
    }

    private fun validateReason(reason: String) {
        require(reason.isNotBlank()) { "tramai-sovereign-ops-invalid-reason" }
        require(reason.length <= MAX_REASON_LENGTH) { "tramai-sovereign-ops-invalid-reason" }
    }

    private fun SovereignOpsAuditOutboxRecord.toSummary(): SovereignOpsAuditOutboxSummary =
        SovereignOpsAuditOutboxSummary(
            outboxId = outboxId,
            aggregateType = aggregateType,
            aggregateIdDigest = aggregateIdDigest,
            operation = operation,
            eventKey = eventKey,
            actor = actor,
            workflowRunId = workflowRunId,
            correlationId = correlationId,
            approvalStatus = approvalStatus,
            approvalVersion = approvalVersion,
            reasonLength = reasonLength,
            status = status,
            attemptCount = attemptCount,
            lastErrorCode = sanitizeErrorCode(lastErrorCode),
            claimedBy = claimedBy,
            claimedAt = claimedAt,
            claimExpiresAt = claimExpiresAt,
            createdAt = createdAt,
            emittedAt = emittedAt,
        )

    private fun sanitizeErrorCode(errorCode: String?): String? {
        if (errorCode == null) return null
        return errorCode.takeIf { SAFE_ERROR_CODE.matches(it) }
    }
}

/** @see DefaultSovereignOpsAuditOutboxOperations */
private const val ERROR_INVALID_OUTBOX_ID = "tramai-sovereign-ops-invalid-outbox-id"
