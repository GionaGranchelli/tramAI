package dev.tramai.spring.sovereign.ops

import java.time.Instant

/**
 * Read-only SPI for inspecting the approved continuation resume backlog.
 *
 * Provides aggregated counts and safe diagnostic data without exposing
 * approval IDs, workflow run IDs, resume tokens, or raw error messages.
 */
interface ApprovedContinuationResumeQueueStatusStore {

    /**
     * Return a snapshot of the resume queue state at [now].
     */
    suspend fun snapshot(now: Instant = Instant.now()): ApprovedContinuationResumeQueueSnapshot
}
