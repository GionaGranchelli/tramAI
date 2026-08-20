package dev.tramai.orchestration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkflowReplayDecisionPolicyTest {
    private val policy = WorkflowReplayDecisionPolicy()

    private fun descriptor(
        replayability: WorkflowStepReplayability,
        repetitionSafety: WorkflowStepRepetitionSafety,
        idempotencyKey: String? = null,
    ) = WorkflowStepReplayDescriptor(replayability, repetitionSafety, idempotencyKey)

    private fun recovery(reason: WorkflowRecoveryReason) = WorkflowReplayDecision.RequireRecovery(reason)

    // ── Required recovery matrix: replayability × repetition safety × key ──

    @Test
    fun `replayable pure auto replays`() {
        assertThat(policy.decide(descriptor(WorkflowStepReplayability.REPLAYABLE, WorkflowStepRepetitionSafety.PURE), null, null))
            .isEqualTo(WorkflowReplayDecision.Replay)
    }

    @Test
    fun `replayable idempotent auto replays without key`() {
        assertThat(policy.decide(descriptor(WorkflowStepReplayability.REPLAYABLE, WorkflowStepRepetitionSafety.IDEMPOTENT), null, null))
            .isEqualTo(WorkflowReplayDecision.Replay)
    }

    @Test
    fun `replayable externally idempotent with matching key auto replays`() {
        assertThat(
            policy.decide(
                descriptor(WorkflowStepReplayability.REPLAYABLE, WorkflowStepRepetitionSafety.EXTERNALLY_IDEMPOTENT),
                storedIdempotencyKey = "key-1",
                currentIdempotencyKey = "key-1",
            ),
        ).isEqualTo(WorkflowReplayDecision.Replay)
    }

    @Test
    fun `replayable externally idempotent with missing stored key requires recovery`() {
        assertThat(
            policy.decide(
                descriptor(WorkflowStepReplayability.REPLAYABLE, WorkflowStepRepetitionSafety.EXTERNALLY_IDEMPOTENT),
                storedIdempotencyKey = null,
                currentIdempotencyKey = "key-1",
            ),
        ).isEqualTo(recovery(WorkflowRecoveryReason.EXTERNAL_IDEMPOTENCY_KEY_MISSING))
    }

    @Test
    fun `replayable externally idempotent with mismatched key requires recovery`() {
        assertThat(
            policy.decide(
                descriptor(WorkflowStepReplayability.REPLAYABLE, WorkflowStepRepetitionSafety.EXTERNALLY_IDEMPOTENT),
                storedIdempotencyKey = "key-1",
                currentIdempotencyKey = "key-2",
            ),
        ).isEqualTo(recovery(WorkflowRecoveryReason.IDEMPOTENCY_KEY_MISMATCH))
    }

    @Test
    fun `replayable unsafe requires manual recovery`() {
        assertThat(policy.decide(descriptor(WorkflowStepReplayability.REPLAYABLE, WorkflowStepRepetitionSafety.UNSAFE), null, null))
            .isEqualTo(recovery(WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN))
    }

    @Test
    fun `non replayable pure still requires manual recovery`() {
        assertThat(policy.decide(descriptor(WorkflowStepReplayability.NON_REPLAYABLE, WorkflowStepRepetitionSafety.PURE), null, null))
            .isEqualTo(recovery(WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN))
    }

    @Test
    fun `non replayable idempotent still requires manual recovery - idempotency cannot make a non replayable operation replayable`() {
        assertThat(policy.decide(descriptor(WorkflowStepReplayability.NON_REPLAYABLE, WorkflowStepRepetitionSafety.IDEMPOTENT), null, null))
            .isEqualTo(recovery(WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN))
    }

    // ── Killer cases, named explicitly ──

    @Test
    fun `replayability cannot make an unsafe side effect repeatable`() {
        assertThat(
            policy.decide(
                descriptor(WorkflowStepReplayability.REPLAYABLE, WorkflowStepRepetitionSafety.UNSAFE, idempotencyKey = "key-1"),
                storedIdempotencyKey = "key-1",
                currentIdempotencyKey = "key-1",
            ),
        ).isEqualTo(recovery(WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN))
    }

    @Test
    fun `idempotency cannot make a non replayable operation replayable`() {
        assertThat(
            policy.decide(
                descriptor(WorkflowStepReplayability.NON_REPLAYABLE, WorkflowStepRepetitionSafety.IDEMPOTENT, idempotencyKey = "key-1"),
                storedIdempotencyKey = "key-1",
                currentIdempotencyKey = "key-1",
            ),
        ).isEqualTo(recovery(WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN))
    }

    @Test
    fun `non replayable wins over externally idempotent even with matching key`() {
        assertThat(
            policy.decide(
                descriptor(WorkflowStepReplayability.NON_REPLAYABLE, WorkflowStepRepetitionSafety.EXTERNALLY_IDEMPOTENT, idempotencyKey = "key-1"),
                storedIdempotencyKey = "key-1",
                currentIdempotencyKey = "key-1",
            ),
        ).isEqualTo(recovery(WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN))
    }
}
