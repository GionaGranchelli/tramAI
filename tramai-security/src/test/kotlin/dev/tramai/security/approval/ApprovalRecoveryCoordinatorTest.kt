package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.ForceCancelClaimedCommand
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.ApprovalAuthorizationException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ApprovalRecoveryCoordinatorTest {

    private lateinit var clock: RecoveryMutableClock
    private lateinit var store: InMemoryApprovalContinuationStore
    private val digester = Sha256ToolArgumentsDigester()

    @BeforeEach
    fun setUp() {
        clock = RecoveryMutableClock(Instant.parse("2026-06-06T10:00:00Z"))
        store = InMemoryApprovalContinuationStore(
            clock = clock,
            maxContinuationTtl = Duration.ofHours(2),
        )
    }

    @Test
    fun `privileged cancellation emits audit event with safe metadata only`() : Unit = runBlocking {
        val audit = RecordingRecoveryAuditEmitter()
        val coordinator = InMemoryApprovalRecoveryCoordinator(
            store = store,
            lifecycleAuditEmitter = audit,
            clock = clock,
        )
        createClaimedContinuation(argumentsJson = """{"secret":"never-log"}""")

        val cancelled = coordinator.forceCancelClaimed(
            ForceCancelClaimedCommand(
                approvalId = "cont-1",
                expectedVersion = 1L,
                operatorId = "operator-9",
                reasonCode = "worker-lost",
            ),
        )

        assertThat(cancelled.status).isEqualTo(ApprovalContinuationStatus.CANCELLED_UNCERTAIN)
        assertThat(audit.forceCancelledEvents).containsExactly(
            "approvalId=cont-1 workflowRunId=wf-run-1 toolName=search-tool cancelledBy=operator-9 reasonCode=worker-lost",
        )
        assertThat(audit.forceCancelledEvents.single()).doesNotContain("secret")
        assertThat(audit.forceCancelledEvents.single()).doesNotContain("{")
    }

    @Test
    fun `findStaleClaims emits detection audit events`() : Unit = runBlocking {
        val audit = RecordingRecoveryAuditEmitter()
        val coordinator = InMemoryApprovalRecoveryCoordinator(
            store = store,
            lifecycleAuditEmitter = audit,
            clock = clock,
        )
        createClaimedContinuation(approvalId = "stale-1", claimedAt = clock.instant().minusSeconds(120))
        createClaimedContinuation(approvalId = "fresh-1", claimedAt = clock.instant().minusSeconds(5))

        val stale = coordinator.findStaleClaims(
            claimedBefore = clock.instant().minusSeconds(30),
            limit = 10,
        )

        assertThat(stale.map { it.approvalId }).containsExactly("stale-1")
        assertThat(audit.staleDetectedEvents).containsExactly(
            "approvalId=stale-1 workflowRunId=wf-run-1 toolName=search-tool claimedAt=${clock.instant().minusSeconds(120)}",
        )
    }

    @Test
    fun `invalid operator ID rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                ForceCancelClaimedCommand(
                    approvalId = "cont-1",
                    expectedVersion = 1L,
                    operatorId = " ",
                    reasonCode = "worker-lost",
                )
            }
            .withMessage("operatorId must not be blank")
    }

    @Test
    fun `invalid reason code rejected`() {
        val coordinator = InMemoryApprovalRecoveryCoordinator(store = store, clock = clock)

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    coordinator.forceCancelClaimed(
                        ForceCancelClaimedCommand(
                            approvalId = "cont-1",
                            expectedVersion = 1L,
                            operatorId = "operator-1",
                            reasonCode = "INVALID",
                        ),
                    )
                }
            }
            .withMessage("reasonCode must match [a-z0-9][a-z0-9._:-]{0,63}")
    }

    @Test
    fun `audit emission failure prevents externally reported success`() : Unit = runBlocking {
        createClaimedContinuation()
        val coordinator = InMemoryApprovalRecoveryCoordinator(
            store = store,
            lifecycleAuditEmitter = object : ApprovalLifecycleAuditEmitter by dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter {
                override suspend fun onClaimedContinuationForceCancelled(
                    approvalId: String,
                    workflowRunId: String,
                    toolName: String,
                    cancelledBy: String,
                    reasonCode: String,
                ) {
                    error("audit-down")
                }
            },
            clock = clock,
        )

        assertThatThrownBy {
            runBlocking {
                coordinator.forceCancelClaimed(
                    ForceCancelClaimedCommand(
                        approvalId = "cont-1",
                        expectedVersion = 1L,
                        operatorId = "operator-1",
                        reasonCode = "worker-lost",
                    ),
                )
            }
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("audit-down")

        assertThat(store.get("cont-1")!!.status).isEqualTo(ApprovalContinuationStatus.CANCELLED_UNCERTAIN)
    }

    @Test
    fun `internal exception details do not leak`() {
        val coordinator = InMemoryApprovalRecoveryCoordinator(
            store = object : ApprovalContinuationStore by store {
                override suspend fun forceCancelClaimed(
                    approvalId: String,
                    expectedVersion: Long,
                    cancelledBy: String,
                    reasonCode: String,
                ): ApprovalContinuation {
                    throw RuntimeException("""backend secret: {"token":"never-leak"}""")
                }
            },
            clock = clock,
        )

        val throwable = catchThrowable {
            runBlocking {
                coordinator.forceCancelClaimed(
                    ForceCancelClaimedCommand(
                        approvalId = "cont-1",
                        expectedVersion = 1L,
                        operatorId = "operator-1",
                        reasonCode = "worker-lost",
                    ),
                )
            }
        }

        assertThat(throwable).isInstanceOf(ApprovalAuthorizationException::class.java)
        assertThat(throwable.message).isEqualTo("Approval authorization failed")
        assertThat(throwable.cause).isNull()
    }

    private fun createClaimedContinuation(
        approvalId: String = "cont-1",
        argumentsJson: String = "{}",
        claimedAt: Instant = clock.instant(),
    ) {
        val continuation = ApprovalContinuation(
            approvalId = approvalId,
            workflowRunId = "wf-run-1",
            correlationId = "corr-1",
            toolCallId = "tc-1",
            toolName = "search-tool",
            argumentsDigest = digester.digest(SensitiveToolArguments.of(argumentsJson)),
            policyVersion = "v1",
            workflowDigest = Sha256Digest.of(
                "sha256:1111111111111111111111111111111111111111111111111111111111111111",
            ),
            status = ApprovalContinuationStatus.CLAIMED,
            createdAt = clock.instant().minusSeconds(180),
            approvalExpiresAt = clock.instant().plusSeconds(1800),
            claimedBy = "runner-1",
            claimedAt = claimedAt,
            completedAt = null,
            version = 1L,
        )
        runBlocking {
            store.create(
                continuation.copy(
                    status = ApprovalContinuationStatus.PENDING,
                    claimedBy = null,
                    claimedAt = null,
                    version = 0L,
                ),
                SensitiveToolArguments.of(argumentsJson),
            )
            if (claimedAt != clock.instant()) {
                clock.set(claimedAt)
                store.claimForExecution(approvalId, 0L, "runner-1")
                clock.set(Instant.parse("2026-06-06T10:00:00Z"))
            } else {
                store.claimForExecution(approvalId, 0L, "runner-1")
            }
        }
    }

    private fun catchThrowable(block: () -> Unit): Throwable =
        try {
            block()
            throw AssertionError("Expected throwable")
        } catch (t: Throwable) {
            t
        }
}

private class RecordingRecoveryAuditEmitter : ApprovalLifecycleAuditEmitter by dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter {
    val staleDetectedEvents = mutableListOf<String>()
    val forceCancelledEvents = mutableListOf<String>()

    override suspend fun onStaleClaimDetected(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        claimedAt: Instant,
    ) {
        staleDetectedEvents += "approvalId=$approvalId workflowRunId=$workflowRunId toolName=$toolName claimedAt=$claimedAt"
    }

    override suspend fun onClaimedContinuationForceCancelled(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        cancelledBy: String,
        reasonCode: String,
    ) {
        forceCancelledEvents +=
            "approvalId=$approvalId workflowRunId=$workflowRunId toolName=$toolName cancelledBy=$cancelledBy reasonCode=$reasonCode"
    }
}

private class RecoveryMutableClock(
    private var now: Instant,
    private val zone: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    override fun instant(): Instant = now
    override fun withZone(zone: ZoneId): Clock = RecoveryMutableClock(now, zone)
    override fun getZone(): ZoneId = zone

    fun set(next: Instant) {
        now = next
    }
}
