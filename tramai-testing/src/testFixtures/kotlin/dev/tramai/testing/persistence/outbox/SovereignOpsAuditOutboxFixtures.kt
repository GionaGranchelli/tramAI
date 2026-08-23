package dev.tramai.testing.persistence.outbox

import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import java.time.Instant

/**
 * Epic 8.1e: fixtures for the shared
 * [dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore]
 * compatibility contract.
 *
 * Deterministic only — never the domain defaults (`UUID.randomUUID()` /
 * `Instant.now()`): fixed base time, explicit IDs, explicit event keys, a
 * fixed five-minute claim lease. No sleeps, no system clock.
 */
object SovereignOpsAuditOutboxFixtures {

    val T0: Instant = Instant.parse("2026-06-21T12:00:00Z")

    val CLAIM_EXPIRY: java.time.Duration = SovereignOpsAuditOutboxRecord.DEFAULT_CLAIM_EXPIRY

    fun record(
        outboxId: String,
        eventKey: String = "event-$outboxId",
        status: SovereignOpsAuditOutboxStatus = SovereignOpsAuditOutboxStatus.PREPARED,
        createdAt: Instant = T0,
        attemptCount: Int = 0,
        lastErrorCode: String? = null,
        claimedBy: String? = null,
        claimedAt: Instant? = null,
        claimExpiresAt: Instant? = null,
        emittedAt: Instant? = null,
    ): SovereignOpsAuditOutboxRecord = SovereignOpsAuditOutboxRecord(
        outboxId = outboxId,
        aggregateType = "approval",
        aggregateIdDigest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        operation = "denyApproval",
        eventKey = eventKey,
        actor = "operator-1",
        workflowRunId = "workflow-$outboxId",
        correlationId = "correlation-$outboxId",
        approvalStatus = "DENIED",
        approvalVersion = 7L,
        reasonDigest = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        reasonLength = 42,
        createdAt = createdAt,
        status = status,
        attemptCount = attemptCount,
        lastErrorCode = lastErrorCode,
        claimedBy = claimedBy,
        claimedAt = claimedAt,
        claimExpiresAt = claimExpiresAt,
        emittedAt = emittedAt,
    )
}
