package dev.tramai.spring.sovereign.ops

import dev.tramai.spring.sovereign.ops.outbox.RefusalEvidence
import dev.tramai.spring.sovereign.ops.outbox.RefusalRecording
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsRefusalRecorder
import java.time.Instant

/**
 * Test double for the refusal sink: records what the decision plane tried to record, and can be told to
 * fail so the "evidence failure is not fail-open" path is testable.
 */
class RecordingRefusalRecorder(
    private val failure: Throwable? = null,
) : SovereignOpsRefusalRecorder {
    val recorded = mutableListOf<RefusalEvidence>()
    var calls: Int = 0
        private set

    override suspend fun recordRefusal(evidence: RefusalEvidence): RefusalRecording {
        calls++
        failure?.let { throw it }
        recorded += evidence
        return RefusalRecording.Recorded(
            evidence
                .toOutboxRecord(
                    createdAt = Instant.parse("2026-10-31T09:00:00Z"),
                    outboxId = "outbox-${recorded.size}",
                ).copy(status = SovereignOpsAuditOutboxStatus.PENDING),
        )
    }

    fun single(): RefusalEvidence {
        require(recorded.size == 1) { "expected exactly one recorded refusal, got ${recorded.size}" }
        return recorded.single()
    }

    /** Convenience for assertions on the persisted record the plane would have written. */
    fun singleRecord(): SovereignOpsAuditOutboxRecord {
        val createdAt = Instant.parse("2026-10-31T09:00:00Z")
        return recorded.single().toOutboxRecord(createdAt)
    }
}
