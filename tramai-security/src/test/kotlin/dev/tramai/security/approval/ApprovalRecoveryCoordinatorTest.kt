package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.ForceCancelClaimedCommand
import dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.ApprovalAuthorizationException
import dev.tramai.core.exception.ApprovalRecoveryAuditUnavailableException
import dev.tramai.core.exception.ApprovalRecoveryUnavailableException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
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
        assertThat(audit.forceCancellationRequestedEvents).containsExactly(
            "approvalId=cont-1 workflowRunId=wf-run-1 toolName=search-tool cancelledBy=operator-9 reasonCode=worker-lost",
        )
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
        val coordinator = InMemoryApprovalRecoveryCoordinator(store = store, lifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter)

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
                override suspend fun onClaimedContinuationForceCancellationRequested(
                    approvalId: String,
                    workflowRunId: String,
                    toolName: String,
                    cancelledBy: String,
                    reasonCode: String,
                ) {
                    error("audit-down")
                }
            },
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
            .isInstanceOf(ApprovalRecoveryAuditUnavailableException::class.java)
            .hasMessage("Approval recovery audit is unavailable")

        assertThat(store.get("cont-1")!!.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
    }

    @Test
    fun `completion audit failure is swallowed after cancellation succeeds`() : Unit = runBlocking {
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
                    error("notify-down")
                }
            },
        )

        val cancelled = coordinator.forceCancelClaimed(
            ForceCancelClaimedCommand(
                approvalId = "cont-1",
                expectedVersion = 1L,
                operatorId = "operator-1",
                reasonCode = "worker-lost",
            ),
        )

        assertThat(cancelled.status).isEqualTo(ApprovalContinuationStatus.CANCELLED_UNCERTAIN)
        assertThat(store.get("cont-1")!!.status).isEqualTo(ApprovalContinuationStatus.CANCELLED_UNCERTAIN)
    }

    @Test
    fun `internal exception details do not leak`() {
        createClaimedContinuation()
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
            lifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
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

    @Test
    fun `cancellation from stale detection audit propagates unchanged`() {
        createClaimedContinuation(approvalId = "stale-1", claimedAt = clock.instant().minusSeconds(120))
        val cancellation = CancellationException("stop-detection")
        val coordinator = InMemoryApprovalRecoveryCoordinator(
            store = store,
            lifecycleAuditEmitter = object : ApprovalLifecycleAuditEmitter by dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter {
                override suspend fun onStaleClaimDetected(
                    approvalId: String,
                    workflowRunId: String,
                    toolName: String,
                    claimedAt: Instant,
                ) {
                    throw cancellation
                }
            },
        )

        val thrown = catchThrowable {
            runBlocking {
                coordinator.findStaleClaims(
                    claimedBefore = clock.instant().minusSeconds(30),
                    limit = 10,
                )
            }
        }

        assertThat(thrown).isSameAs(cancellation)
    }

    @Test
    fun `cancellation from store mutation propagates unchanged`() {
        createClaimedContinuation()
        val cancellation = CancellationException("stop-mutation")
        val coordinator = InMemoryApprovalRecoveryCoordinator(
            store = object : ApprovalContinuationStore by store {
                override suspend fun forceCancelClaimed(
                    approvalId: String,
                    expectedVersion: Long,
                    cancelledBy: String,
                    reasonCode: String,
                ): ApprovalContinuation {
                    throw cancellation
                }
            },
            lifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
        )

        val thrown = catchThrowable {
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

        assertThat(thrown).isSameAs(cancellation)
    }

    @Test
    fun `cancellation from post-mutation audit propagates unchanged`() {
        createClaimedContinuation()
        val cancellation = CancellationException("stop-post-mutation-audit")
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
                    throw cancellation
                }
            },
        )

        val thrown = catchThrowable {
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

        assertThat(thrown).isSameAs(cancellation)
    }

    @Test
    fun `store get failure exposes only safe exception`() {
        val leakyStore = object : ApprovalContinuationStore by store {
            override suspend fun get(approvalId: String): ApprovalContinuation? {
                throw RuntimeException("""backend secret: {"password":"never-leak"}""")
            }
        }
        val coordinator = InMemoryApprovalRecoveryCoordinator(
            store = leakyStore,
            lifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
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
        // Verify "password" and "never-leak" are absent from all exception metadata
        assertThat(throwable.toString()).doesNotContain("password")
        assertThat(throwable.toString()).doesNotContain("never-leak")
    }

    @Test
    fun `audit sink failure exposes only safe exception`() {
        createClaimedContinuation()
        val coordinator = InMemoryApprovalRecoveryCoordinator(
            store = store,
            lifecycleAuditEmitter = object : ApprovalLifecycleAuditEmitter by NoOpApprovalLifecycleAuditEmitter {
                override suspend fun onClaimedContinuationForceCancellationRequested(
                    approvalId: String,
                    workflowRunId: String,
                    toolName: String,
                    cancelledBy: String,
                    reasonCode: String,
                ) {
                    throw RuntimeException("""sink secret: {"api_key":"sk-abc123"}""")
                }
            },
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
        assertThat(throwable).isInstanceOf(ApprovalRecoveryAuditUnavailableException::class.java)
        assertThat(throwable.message).isEqualTo("Approval recovery audit is unavailable")
        assertThat(throwable.cause).isNull()
        assertThat(throwable.toString()).doesNotContain("api_key")
        assertThat(throwable.toString()).doesNotContain("sk-abc123")
        // Verify continuation stays CLAIMED
        assertThat(runBlocking { store.get("cont-1") }!!.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
    }

    @Test
    fun `store get IllegalArgumentException with secret text is sanitized`() {
        val leakyStore = object : ApprovalContinuationStore by store {
            override suspend fun get(approvalId: String): ApprovalContinuation? {
                throw IllegalArgumentException("""database secret: {"password":"hunter2"}""")
            }
        }
        val coordinator = InMemoryApprovalRecoveryCoordinator(
            store = leakyStore,
            lifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
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
        assertThat(throwable.toString()).doesNotContain("password")
        assertThat(throwable.toString()).doesNotContain("hunter2")
        assertThat(throwable.toString()).doesNotContain("database")
    }

    @Test
    fun `store forceCancelClaimed IllegalArgumentException with secret text is sanitized`() {
        createClaimedContinuation()
        val leakyStore = object : ApprovalContinuationStore by store {
            override suspend fun forceCancelClaimed(
                approvalId: String,
                expectedVersion: Long,
                cancelledBy: String,
                reasonCode: String,
            ): ApprovalContinuation {
                throw IllegalArgumentException("""backend config: {"api_key":"sk-1234"}""")
            }
        }
        val coordinator = InMemoryApprovalRecoveryCoordinator(
            store = leakyStore,
            lifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
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
        assertThat(throwable.toString()).doesNotContain("api_key")
        assertThat(throwable.toString()).doesNotContain("sk-1234")
    }

    @Test
    fun `store findStaleClaimed failure exposes only safe exception`() {
        val leakyStore = object : ApprovalContinuationStore by store {
            override suspend fun findStaleClaimed(claimedBefore: Instant, limit: Int): List<ApprovalContinuation> {
                throw RuntimeException("""db secret: {"conn_string":"jdbc:postgres://secret@host/db"}""")
            }
        }
        val coordinator = InMemoryApprovalRecoveryCoordinator(
            store = leakyStore,
            lifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
        )
        val throwable = catchThrowable {
            runBlocking {
                coordinator.findStaleClaims(
                    claimedBefore = Instant.parse("2026-06-06T10:00:00Z"),
                    limit = 10,
                )
            }
        }
        assertThat(throwable).isInstanceOf(ApprovalRecoveryUnavailableException::class.java)
        assertThat(throwable.message).isEqualTo("Approval recovery is unavailable")
        assertThat(throwable.cause).isNull()
        assertThat(throwable.toString()).doesNotContain("conn_string")
        assertThat(throwable.toString()).doesNotContain("secret@host")
        assertThat(throwable.toString()).doesNotContain("db secret")
    }

    @Test
    fun `stale detection audit IllegalArgumentException does not fail discovery`() {
        createClaimedContinuation(approvalId = "stale-1", claimedAt = Instant.parse("2026-06-06T09:50:00Z"))
        val coordinator = InMemoryApprovalRecoveryCoordinator(
            store = store,
            lifecycleAuditEmitter = object : ApprovalLifecycleAuditEmitter by NoOpApprovalLifecycleAuditEmitter {
                override suspend fun onStaleClaimDetected(
                    approvalId: String,
                    workflowRunId: String,
                    toolName: String,
                    claimedAt: Instant,
                ) {
                    throw IllegalArgumentException("""audit secret: {"token":"sk-5678"}""")
                }
            },
        )
        val stale = runBlocking {
            coordinator.findStaleClaims(
                claimedBefore = Instant.parse("2026-06-06T10:00:00Z"),
                limit = 10,
            )
        }
        // Discovery succeeds even though audit emission fails
        assertThat(stale.map { it.approvalId }).contains("stale-1")
    }

    @Test
    fun `post-mutation audit IllegalArgumentException does not undo cancellation`() {
        createClaimedContinuation()
        val coordinator = InMemoryApprovalRecoveryCoordinator(
            store = store,
            lifecycleAuditEmitter = object : ApprovalLifecycleAuditEmitter by NoOpApprovalLifecycleAuditEmitter {
                override suspend fun onClaimedContinuationForceCancelled(
                    approvalId: String,
                    workflowRunId: String,
                    toolName: String,
                    cancelledBy: String,
                    reasonCode: String,
                ) {
                    throw IllegalArgumentException("""post-audit secret: {"token":"sk-9012"}""")
                }
            },
        )
        val cancelled = runBlocking {
            coordinator.forceCancelClaimed(
                ForceCancelClaimedCommand(
                    approvalId = "cont-1",
                    expectedVersion = 1L,
                    operatorId = "operator-1",
                    reasonCode = "worker-lost",
                ),
            )
        }
        // Force cancellation succeeds despite post-mutation audit failure
        assertThat(cancelled.status).isEqualTo(ApprovalContinuationStatus.CANCELLED_UNCERTAIN)
        assertThat(runBlocking { store.get("cont-1") }!!.status).isEqualTo(ApprovalContinuationStatus.CANCELLED_UNCERTAIN)
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
    val forceCancellationRequestedEvents = mutableListOf<String>()
    val forceCancelledEvents = mutableListOf<String>()

    override suspend fun onStaleClaimDetected(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        claimedAt: Instant,
    ) {
        staleDetectedEvents += "approvalId=$approvalId workflowRunId=$workflowRunId toolName=$toolName claimedAt=$claimedAt"
    }

    override suspend fun onClaimedContinuationForceCancellationRequested(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        cancelledBy: String,
        reasonCode: String,
    ) {
        forceCancellationRequestedEvents +=
            "approvalId=$approvalId workflowRunId=$workflowRunId toolName=$toolName cancelledBy=$cancelledBy reasonCode=$reasonCode"
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
