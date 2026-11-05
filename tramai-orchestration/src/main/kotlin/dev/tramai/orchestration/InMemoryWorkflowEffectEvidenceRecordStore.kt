package dev.tramai.orchestration

/**
 * In-memory implementation of [WorkflowEffectEvidenceRecordStore].
 */
internal class InMemoryWorkflowEffectEvidenceRecordStore : WorkflowEffectEvidenceRecordStore {
    private val records = linkedMapOf<WorkflowEffectEvidenceKey, WorkflowEffectEvidenceRecord>()
    private val monitor = Any()

    override suspend fun recordEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord {
        synchronized(monitor) {
            val key = record.key
            if (records.containsKey(key)) {
                throw IllegalStateException("Evidence record already exists for key: $key")
            }
            records[key] = record
            return record
        }
    }

    override suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord {
        synchronized(monitor) {
            val key = record.key
            val existing = records[key] ?: throw IllegalStateException("Evidence record does not exist for key: $key")
            validateTransition(existing, record)
            records[key] = record
            return record
        }
    }

    override suspend fun getEvidence(key: WorkflowEffectEvidenceKey): WorkflowEffectEvidenceRecord? {
        synchronized(monitor) {
            return records[key]
        }
    }

    override suspend fun listEvidence(
        workflowName: String,
        workflowId: String,
    ): List<WorkflowEffectEvidenceRecord> {
        synchronized(monitor) {
            return records.values
                .filter { it.key.workflowName == workflowName && it.key.workflowId == workflowId }
                .sortedBy { it.key.admissionSeq }
        }
    }

    private fun validateTransition(
        existing: WorkflowEffectEvidenceRecord,
        updated: WorkflowEffectEvidenceRecord,
    ) {
        check(existing.key == updated.key) {
            "Evidence key is immutable: expected ${existing.key}, got ${updated.key}"
        }
        check(existing.stepName == updated.stepName) {
            "stepName is immutable: expected '${existing.stepName}', got '${updated.stepName}'"
        }
        check(existing.stepAttemptId == updated.stepAttemptId) {
            "stepAttemptId is immutable: expected '${existing.stepAttemptId}', got '${updated.stepAttemptId}'"
        }
        check(existing.effectKind == updated.effectKind) {
            "effectKind is immutable: expected '${existing.effectKind}', got '${updated.effectKind}'"
        }
        check(existing.createdAtEpochMillis == updated.createdAtEpochMillis) {
            "createdAtEpochMillis is immutable"
        }

        // Monotonic state progression:
        // ADMITTED -> DISPATCHED -> COMPLETED
        // Any -> UNCERTAIN
        // UNCERTAIN is terminal
        // COMPLETED is terminal
        if (existing.state == updated.state) return

        when (existing.state) {
            WorkflowEffectEvidenceState.ADMITTED -> {
                check(
                    updated.state == WorkflowEffectEvidenceState.DISPATCHED ||
                        updated.state == WorkflowEffectEvidenceState.UNCERTAIN,
                ) {
                    "Invalid transition from ADMITTED to ${updated.state}"
                }
            }

            WorkflowEffectEvidenceState.DISPATCHED -> {
                check(
                    updated.state == WorkflowEffectEvidenceState.COMPLETED ||
                        updated.state == WorkflowEffectEvidenceState.UNCERTAIN,
                ) {
                    "Invalid transition from DISPATCHED to ${updated.state}"
                }
            }

            WorkflowEffectEvidenceState.COMPLETED -> {
                error("Terminal state COMPLETED cannot transition to ${updated.state}")
            }

            WorkflowEffectEvidenceState.UNCERTAIN -> {
                error("Terminal state UNCERTAIN cannot transition to ${updated.state}")
            }
        }
    }
}
