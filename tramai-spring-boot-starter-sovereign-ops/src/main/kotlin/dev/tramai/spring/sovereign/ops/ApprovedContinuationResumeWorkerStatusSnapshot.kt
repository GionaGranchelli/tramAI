package dev.tramai.spring.sovereign.ops

import java.time.Instant

data class ApprovedContinuationResumeWorkerStatusSnapshot(
    val enabled: Boolean,
    val lifecycleEnabled: Boolean,
    val running: Boolean,
    val batchSize: Int,
    val intervalMillis: Long,
    val lastCycleStartedAt: Instant?,
    val lastCycleCompletedAt: Instant?,
    val lastCycleDurationMillis: Long?,
    val lastResult: ApprovedContinuationResumeWorkerResult?,
    val lastFailureAt: Instant?,
    /** Only the exception class name (never the message). */
    val lastFailureErrorCode: String?,
    val totalCyclesCompleted: Long,
    val totalCyclesFailed: Long,
)
