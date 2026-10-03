package dev.tramai.engine.approval

import dev.tramai.core.exception.GovernedRunContinuityException
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Contract tests for [requireGovernedContinuity].
 *
 * The rule is that a standalone continuation can only be resumed by the identity it was persisted
 * with: a legacy suspension cannot be attributed to a governed run, and a governed suspension
 * cannot be resumed under a different one. The rejection is asserted together with the run it
 * refused to attribute, because the message is what makes the substitution auditable — a message
 * that reads `null` describes no run at all.
 */
class GovernedContinuationContinuityTest {
    @Test
    fun `a governed resume of a legacy suspension is rejected naming the requested run`() {
        val requested = governedIdentity(runId = "run-substituted")

        val failure =
            assertThrows<GovernedRunContinuityException> {
                requireGovernedContinuity(
                    approvalId = "approval-1",
                    persisted = null,
                    requested = requested,
                )
            }

        assertTrue(
            failure.message.orEmpty().contains("run-substituted"),
            "the rejection must name the run it refused to attribute, got: ${failure.message}",
        )
    }

    @Test
    fun `a governed resume of the same identity proceeds`() {
        val identity = governedIdentity(runId = "run-1")

        requireGovernedContinuity(approvalId = "approval-1", persisted = identity, requested = identity)
    }

    @Test
    fun `a standalone resume recovers the persisted identity`() {
        requireGovernedContinuity(
            approvalId = "approval-1",
            persisted = governedIdentity(runId = "run-1"),
            requested = null,
        )
    }

    @Test
    fun `substituted attribution is rejected before claim or execution`() {
        val failure =
            assertThrows<GovernedRunContinuityException> {
                requireGovernedContinuity(
                    approvalId = "approval-1",
                    persisted = governedIdentity(runId = "run-1"),
                    requested = governedIdentity(runId = "run-2"),
                )
            }

        assertTrue(
            failure.message.orEmpty().contains("attribution was substituted"),
            "the rejection must name the substitution, got: ${failure.message}",
        )
    }
}
