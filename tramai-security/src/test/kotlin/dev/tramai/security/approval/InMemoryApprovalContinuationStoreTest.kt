package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.ApprovalContinuationConflictException
import dev.tramai.core.exception.ApprovalContinuationNotClaimableException
import dev.tramai.core.exception.ApprovalContinuationNotCompletableException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InMemoryApprovalContinuationStoreTest {

    private lateinit var fixedClock: ContinuationMutableClock
    private lateinit var store: InMemoryApprovalContinuationStore
    private val digester = Sha256ToolArgumentsDigester()

    @BeforeEach
    fun setUp() {
        fixedClock = ContinuationMutableClock(Instant.parse("2026-06-06T10:00:00Z"))
        store = InMemoryApprovalContinuationStore(
            clock = fixedClock,
            maxContinuationTtl = Duration.ofHours(2),
        )
    }

    @Test
    fun `valid PENDING continuation stored`() : Unit = runBlocking {
        val continuation = aPendingContinuation()

        val created = store.create(continuation)

        assertThat(created).isEqualTo(continuation)
        assertThat(store.get("cont-1")).isEqualTo(continuation)
    }

    @Test
    fun `duplicate approvalId rejected`() : Unit = runBlocking {
        store.create(aPendingContinuation())

        assertThatThrownBy {
            runBlocking { store.create(aPendingContinuation(toolName = "other-tool")) }
        }
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `malformed ID rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking { store.create(aPendingContinuation(approvalId = "  ")) }
            }
            .withMessage("approvalId must not be blank")
    }

    @Test
    fun `control characters rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking { store.create(aPendingContinuation(toolName = "search\n-tool")) }
            }
            .withMessage("toolName must not contain control characters")
    }

    @Test
    fun `initial CLAIMED rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.create(
                        aPendingContinuation(
                            status = ApprovalContinuationStatus.CLAIMED,
                            claimedBy = "runner-1",
                            claimedAt = fixedClock.instant(),
                        ),
                    )
                }
            }
            .withMessage("Initial continuation status must be PENDING, got CLAIMED")
    }

    @Test
    fun `initial completion metadata rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.create(
                        aPendingContinuation(
                            completedAt = fixedClock.instant(),
                        ),
                    )
                }
            }
            .withMessage("Initial continuation must not have completedAt set")
    }

    @Test
    fun `future expiry required`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.create(
                        aPendingContinuation(
                            expiresAt = fixedClock.instant().minusSeconds(1),
                        ),
                    )
                }
            }
            .withMessage("expiresAt must be in the future")
    }

    @Test
    fun `bounded TTL enforced`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.create(
                        aPendingContinuation(
                            expiresAt = fixedClock.instant().plus(Duration.ofHours(3)),
                        ),
                    )
                }
            }
            .withMessage("expiresAt exceeds maximum continuation TTL of PT2H")
    }

    @Test
    fun `arguments digest mismatch rejected safely`() {
        val raw = """{"secret":"value"}"""

        val throwable = catchThrowable {
            runBlocking {
                store.create(
                    aPendingContinuation(
                        argumentsJson = raw,
                        argumentsDigest = Sha256Digest.of(
                            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        ),
                    ),
                )
            }
        }

        assertThat(throwable).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(throwable.message).isEqualTo("argumentsDigest does not match arguments")
        assertThrowableTreeDoesNotContain(throwable, raw)
    }

    @Test
    fun `raw arguments absent from all exception trees`() {
        val raw = """{"apiKey":"super-secret"}"""

        val throwable = catchThrowable {
            runBlocking {
                store.create(
                    aPendingContinuation(
                        argumentsJson = raw,
                        argumentsDigest = Sha256Digest.of(
                            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        ),
                    ),
                )
            }
        }

        assertThrowableTreeDoesNotContain(throwable, raw)
    }

    @Test
    fun `pending continuation can be claimed`() : Unit = runBlocking {
        store.create(aPendingContinuation())

        val claimed = store.claimForExecution("cont-1", 0L, "runner-1")

        assertThat(claimed.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
        assertThat(claimed.version).isEqualTo(1L)
    }

    @Test
    fun `claim stores claimedBy and claimedAt`() : Unit = runBlocking {
        store.create(aPendingContinuation())

        val claimed = store.claimForExecution("cont-1", 0L, "runner-1")

        assertThat(claimed.claimedBy).isEqualTo("runner-1")
        assertThat(claimed.claimedAt).isEqualTo(fixedClock.instant())
    }

    @Test
    fun `expired pending continuation cannot be claimed`() : Unit = runBlocking {
        store.create(aPendingContinuation(expiresAt = fixedClock.instant().plusSeconds(5)))
        fixedClock.advance(Duration.ofSeconds(6))

        assertThatThrownBy {
            runBlocking { store.claimForExecution("cont-1", 0L, "runner-1") }
        }
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
    }

    @Test
    fun `stale version rejected`() : Unit = runBlocking {
        store.create(aPendingContinuation())

        assertThatThrownBy {
            runBlocking { store.claimForExecution("cont-1", 1L, "runner-1") }
        }
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `second claim rejected`() : Unit = runBlocking {
        store.create(aPendingContinuation())
        store.claimForExecution("cont-1", 0L, "runner-1")

        assertThatThrownBy {
            runBlocking { store.claimForExecution("cont-1", 1L, "runner-2") }
        }
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
    }

    @Test
    fun `concurrent claims exactly one succeeds`() : Unit = runBlocking {
        store.create(aPendingContinuation())
        val latch = CountDownLatch(1)

        val results = listOf("runner-1", "runner-2").map { runner ->
            async(Dispatchers.Default) {
                latch.await()
                runCatching { store.claimForExecution("cont-1", 0L, runner) }
            }
        }

        latch.countDown()
        val settled = results.awaitAll()

        assertThat(settled.count { it.isSuccess }).isEqualTo(1)
        assertThat(settled.count { it.isFailure }).isEqualTo(1)
        assertThat(settled.single { it.isSuccess }.getOrThrow().status)
            .isEqualTo(ApprovalContinuationStatus.CLAIMED)
        assertThat(settled.single { it.isFailure }.exceptionOrNull())
            .isInstanceOfAny(
                ApprovalContinuationConflictException::class.java,
                ApprovalContinuationNotClaimableException::class.java,
            )
    }

    @Test
    fun `overflow maps to ApprovalContinuationConflictException on claim`() : Unit = runBlocking {
        insertDirect(
            aPendingContinuation(
                approvalId = "overflow-claim",
                version = Long.MAX_VALUE,
            ),
        )

        assertThatThrownBy {
            runBlocking { store.claimForExecution("overflow-claim", Long.MAX_VALUE, "runner-1") }
        }
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `claimed continuation can complete`() : Unit = runBlocking {
        store.create(aPendingContinuation())
        store.claimForExecution("cont-1", 0L, "runner-1")

        val completed = store.complete("cont-1", 1L)

        assertThat(completed.status).isEqualTo(ApprovalContinuationStatus.COMPLETED)
        assertThat(completed.version).isEqualTo(2L)
    }

    @Test
    fun `pending continuation cannot complete`() : Unit = runBlocking {
        store.create(aPendingContinuation())

        assertThatThrownBy {
            runBlocking { store.complete("cont-1", 0L) }
        }
            .isInstanceOf(ApprovalContinuationNotCompletableException::class.java)
    }

    @Test
    fun `completed continuation cannot complete again`() : Unit = runBlocking {
        store.create(aPendingContinuation())
        store.claimForExecution("cont-1", 0L, "runner-1")
        store.complete("cont-1", 1L)

        assertThatThrownBy {
            runBlocking { store.complete("cont-1", 2L) }
        }
            .isInstanceOf(ApprovalContinuationNotCompletableException::class.java)
    }

    @Test
    fun `completion stores completedAt`() : Unit = runBlocking {
        store.create(aPendingContinuation())
        store.claimForExecution("cont-1", 0L, "runner-1")
        fixedClock.advance(Duration.ofSeconds(7))

        val completed = store.complete("cont-1", 1L)

        assertThat(completed.completedAt).isEqualTo(fixedClock.instant())
    }

    @Test
    fun `overflow maps to typed conflict on complete`() : Unit = runBlocking {
        insertDirect(
            aPendingContinuation(
                approvalId = "overflow-complete",
                status = ApprovalContinuationStatus.CLAIMED,
                claimedBy = "runner-1",
                claimedAt = fixedClock.instant(),
                version = Long.MAX_VALUE,
            ),
        )

        assertThatThrownBy {
            runBlocking { store.complete("overflow-complete", Long.MAX_VALUE) }
        }
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `pending continuation expires only after deadline`() : Unit = runBlocking {
        store.create(aPendingContinuation(expiresAt = fixedClock.instant().plusSeconds(10)))
        fixedClock.advance(Duration.ofSeconds(10))

        val expired = store.expire("cont-1", 0L)

        assertThat(expired.status).isEqualTo(ApprovalContinuationStatus.EXPIRED)
        assertThat(expired.version).isEqualTo(1L)
    }

    @Test
    fun `timeout before deadline rejected`() : Unit = runBlocking {
        store.create(aPendingContinuation(expiresAt = fixedClock.instant().plusSeconds(10)))

        assertThatThrownBy {
            runBlocking { store.expire("cont-1", 0L) }
        }
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
    }

    @Test
    fun `claimed continuation cannot expire`() : Unit = runBlocking {
        store.create(aPendingContinuation(expiresAt = fixedClock.instant().plusSeconds(10)))
        store.claimForExecution("cont-1", 0L, "runner-1")
        fixedClock.advance(Duration.ofSeconds(11))

        assertThatThrownBy {
            runBlocking { store.expire("cont-1", 1L) }
        }
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
    }

    @Test
    fun `completed continuation cannot expire`() : Unit = runBlocking {
        store.create(aPendingContinuation(expiresAt = fixedClock.instant().plusSeconds(10)))
        store.claimForExecution("cont-1", 0L, "runner-1")
        store.complete("cont-1", 1L)
        fixedClock.advance(Duration.ofSeconds(11))

        assertThatThrownBy {
            runBlocking { store.expire("cont-1", 2L) }
        }
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
    }

    @Test
    fun `pending continuation can cancel`() : Unit = runBlocking {
        store.create(aPendingContinuation())

        val cancelled = store.cancel("cont-1", 0L)

        assertThat(cancelled.status).isEqualTo(ApprovalContinuationStatus.CANCELLED)
        assertThat(cancelled.version).isEqualTo(1L)
    }

    @Test
    fun `claimed continuation cannot cancel`() : Unit = runBlocking {
        store.create(aPendingContinuation())
        store.claimForExecution("cont-1", 0L, "runner-1")

        assertThatThrownBy {
            runBlocking { store.cancel("cont-1", 1L) }
        }
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
    }

    @Test
    fun `completed continuation cannot cancel`() : Unit = runBlocking {
        store.create(aPendingContinuation())
        store.claimForExecution("cont-1", 0L, "runner-1")
        store.complete("cont-1", 1L)

        assertThatThrownBy {
            runBlocking { store.cancel("cont-1", 2L) }
        }
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
    }

    @Test
    fun `stored arguments wrapper remains redacted in toString`() : Unit = runBlocking {
        val raw = """{"token":"do-not-log"}"""
        val continuation = aPendingContinuation(argumentsJson = raw)
        store.create(continuation)

        assertThat(store.get("cont-1").toString()).contains("arguments=[REDACTED]")
        assertThat(store.get("cont-1").toString()).doesNotContain(raw)
    }

    @Test
    fun `no raw JSON in exception messages`() {
        val raw = """{"password":"never-print"}"""

        val throwable = catchThrowable {
            runBlocking {
                store.create(
                    aPendingContinuation(
                        argumentsJson = raw,
                        argumentsDigest = Sha256Digest.of(
                            "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                        ),
                    ),
                )
            }
        }

        assertThat(throwable.message).doesNotContain(raw)
    }

    @Test
    fun `no raw JSON in throwable cause or suppressed chains`() {
        val raw = """{"secret":"chain-check"}"""

        val throwable = catchThrowable {
            runBlocking {
                store.create(
                    aPendingContinuation(
                        argumentsJson = raw,
                        argumentsDigest = Sha256Digest.of(
                            "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                        ),
                    ),
                )
            }
        }

        assertThrowableTreeDoesNotContain(throwable, raw)
    }

    private fun aPendingContinuation(
        approvalId: String = "cont-1",
        workflowRunId: String = "wf-run-1",
        correlationId: String = "corr-1",
        toolCallId: String = "tc-1",
        toolName: String = "search-tool",
        argumentsJson: String = "{}",
        argumentsDigest: Sha256Digest = digester.digest(SensitiveToolArguments.of(argumentsJson)),
        policyVersion: String = "v1",
        workflowDigest: Sha256Digest = Sha256Digest.of(
            "sha256:1111111111111111111111111111111111111111111111111111111111111111",
        ),
        createdAt: Instant = fixedClock.instant(),
        expiresAt: Instant = fixedClock.instant().plusSeconds(3600),
        status: ApprovalContinuationStatus = ApprovalContinuationStatus.PENDING,
        claimedBy: String? = null,
        claimedAt: Instant? = null,
        completedAt: Instant? = null,
        version: Long = 0L,
    ): ApprovalContinuation = ApprovalContinuation(
        approvalId = approvalId,
        workflowRunId = workflowRunId,
        correlationId = correlationId,
        toolCallId = toolCallId,
        toolName = toolName,
        argumentsDigest = argumentsDigest,
        arguments = SensitiveToolArguments.of(argumentsJson),
        policyVersion = policyVersion,
        workflowDigest = workflowDigest,
        status = status,
        createdAt = createdAt,
        expiresAt = expiresAt,
        claimedBy = claimedBy,
        claimedAt = claimedAt,
        completedAt = completedAt,
        version = version,
    )

    private fun catchThrowable(block: () -> Unit): Throwable =
        try {
            block()
            throw AssertionError("Expected throwable")
        } catch (t: Throwable) {
            t
        }

    private fun assertThrowableTreeDoesNotContain(throwable: Throwable, raw: String) {
        val visited = LinkedHashSet<Throwable>()
        fun visit(current: Throwable?) {
            if (current == null || !visited.add(current)) return
            assertThat(current.message ?: "").doesNotContain(raw)
            current.suppressed.forEach { visit(it) }
            visit(current.cause)
        }
        visit(throwable)
    }

    @Suppress("UNCHECKED_CAST")
    private fun insertDirect(continuation: ApprovalContinuation) {
        val field = store.javaClass.getDeclaredField("store")
        field.isAccessible = true
        val map = field.get(store) as ConcurrentHashMap<String, ApprovalContinuation>
        map[continuation.approvalId] = continuation
    }
}

private class ContinuationMutableClock(
    private var now: Instant,
    private val zone: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    override fun instant(): Instant = now
    override fun withZone(zone: ZoneId): Clock = ContinuationMutableClock(now, zone)
    override fun getZone(): ZoneId = zone

    fun advance(amount: Duration) {
        now = now.plus(amount)
    }
}
