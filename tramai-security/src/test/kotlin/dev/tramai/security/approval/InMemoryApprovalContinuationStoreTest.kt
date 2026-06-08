package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.ApprovalContinuationConflictException
import dev.tramai.core.exception.ApprovalContinuationNotClaimableException
import dev.tramai.core.exception.ApprovalContinuationNotCompletableException
import dev.tramai.core.exception.ApprovalContinuationNotFoundException
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
        val raw = "{}"
        val continuation = aPendingContinuation(argumentsJson = raw)

        val created = store.create(continuation, SensitiveToolArguments.of(raw))

        assertThat(created).isEqualTo(continuation)
        assertThat(store.get("cont-1")).isEqualTo(continuation)
    }

    @Test
    fun `duplicate approvalId rejected`() : Unit = runBlocking {
        createPendingContinuation()

        assertThatThrownBy {
            runBlocking {
                store.create(
                    aPendingContinuation(toolName = "other-tool"),
                    SensitiveToolArguments.of("{}"),
                )
            }
        }
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `malformed ID rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.create(
                        aPendingContinuation(approvalId = "  "),
                        SensitiveToolArguments.of("{}"),
                    )
                }
            }
            .withMessage("approvalId must not be blank")
    }

    @Test
    fun `control characters rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.create(
                        aPendingContinuation(toolName = "search\n-tool"),
                        SensitiveToolArguments.of("{}"),
                    )
                }
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
                        SensitiveToolArguments.of("{}"),
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
                        aPendingContinuation(completedAt = fixedClock.instant()),
                        SensitiveToolArguments.of("{}"),
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
                            approvalExpiresAt = fixedClock.instant().minusSeconds(1),
                        ),
                        SensitiveToolArguments.of("{}"),
                    )
                }
            }
            .withMessage("approvalExpiresAt must be in the future")
    }

    @Test
    fun `bounded TTL enforced`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.create(
                        aPendingContinuation(
                            approvalExpiresAt = fixedClock.instant().plus(Duration.ofHours(3)),
                        ),
                        SensitiveToolArguments.of("{}"),
                    )
                }
            }
            .withMessage("approvalExpiresAt exceeds maximum continuation TTL of PT2H")
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
                    SensitiveToolArguments.of(raw),
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
                    SensitiveToolArguments.of(raw),
                )
            }
        }

        assertThrowableTreeDoesNotContain(throwable, raw)
    }

    @Test
    fun `zero maxContinuationTtl rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                InMemoryApprovalContinuationStore(
                    clock = fixedClock,
                    maxContinuationTtl = Duration.ZERO,
                )
            }
            .withMessage("maxContinuationTtl must be positive")
    }

    @Test
    fun `negative maxContinuationTtl rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                InMemoryApprovalContinuationStore(
                    clock = fixedClock,
                    maxContinuationTtl = Duration.ofSeconds(-1),
                )
            }
            .withMessage("maxContinuationTtl must be positive")
    }

    @Test
    fun `future createdAt rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.create(
                        aPendingContinuation(createdAt = fixedClock.instant().plusSeconds(1)),
                        SensitiveToolArguments.of("{}"),
                    )
                }
            }
            .withMessage("createdAt must not be in the future")
    }

    @Test
    fun `PENDING with claimedBy rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.create(
                        aPendingContinuation(claimedBy = "runner-1"),
                        SensitiveToolArguments.of("{}"),
                    )
                }
            }
            .withMessage("Initial continuation must not have claimedBy set")
    }

    @Test
    fun `PENDING with claimedAt rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.create(
                        aPendingContinuation(claimedAt = fixedClock.instant()),
                        SensitiveToolArguments.of("{}"),
                    )
                }
            }
            .withMessage("Initial continuation must not have claimedAt set")
    }

    @Test
    fun `oversized ID rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.create(
                        aPendingContinuation(approvalId = "a".repeat(257)),
                        SensitiveToolArguments.of("{}"),
                    )
                }
            }
            .withMessage("approvalId exceeds maximum length of 256")
    }

    @Test
    fun `surrounding whitespace rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    store.create(
                        aPendingContinuation(approvalId = " cont-id "),
                        SensitiveToolArguments.of("{}"),
                    )
                }
            }
            .withMessage("approvalId must not contain surrounding whitespace")
    }

    @Test
    fun `control characters rejected by get`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking { store.get("cont\n-1") }
            }
            .withMessage("approvalId must not contain control characters")
    }

    @Test
    fun `throwable trees never include raw arguments`() {
        val raw = """{"sensitiveField":"fixture-redaction-marker"}"""

        val throwable = catchThrowable {
            runBlocking {
                store.create(
                    aPendingContinuation(
                        argumentsJson = raw,
                        argumentsDigest = Sha256Digest.of(
                            "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                        ),
                    ),
                    SensitiveToolArguments.of(raw),
                )
            }
        }

        assertThrowableTreeDoesNotContain(throwable, raw)
    }

    @Test
    fun `metadata toString never includes raw arguments`() {
        val raw = """{"sensitiveField":"never-print"}"""
        val continuation = aPendingContinuation(argumentsJson = raw)

        assertThat(continuation.toString()).doesNotContain(raw)
        assertThat(continuation.toString()).doesNotContain("arguments=")
    }

    @Test
    fun `pending continuation can be claimed`() : Unit = runBlocking {
        createPendingContinuation()

        val claimed = store.claimForExecution("cont-1", 0L, "runner-1")

        assertThat(claimed.continuation.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
        assertThat(claimed.continuation.version).isEqualTo(1L)
    }

    @Test
    fun `claim stores claimedBy and claimedAt`() : Unit = runBlocking {
        createPendingContinuation()

        val claimed = store.claimForExecution("cont-1", 0L, "runner-1")

        assertThat(claimed.continuation.claimedBy).isEqualTo("runner-1")
        assertThat(claimed.continuation.claimedAt).isEqualTo(fixedClock.instant())
    }

    @Test
    fun `winning claim receives exact raw JSON`() : Unit = runBlocking {
        val raw = """{"sensitiveField":"never-print"}"""
        createPendingContinuation(argumentsJson = raw)

        val claimed = store.claimForExecution("cont-1", 0L, "runner-1")

        assertThat(claimed.arguments.reveal()).isEqualTo(raw)
    }

    @Test
    fun `stored entry scrubbed after claim`() : Unit = runBlocking {
        createPendingContinuation(argumentsJson = """{"sensitiveField":"never-print"}""")

        store.claimForExecution("cont-1", 0L, "runner-1")

        assertThat(readStored("cont-1")!!.arguments).isNull()
    }

    @Test
    fun `second claim cannot retrieve arguments`() : Unit = runBlocking {
        val raw = """{"sensitiveField":"never-print"}"""
        createPendingContinuation(argumentsJson = raw)
        store.claimForExecution("cont-1", 0L, "runner-1")

        assertThatThrownBy {
            runBlocking { store.claimForExecution("cont-1", 1L, "runner-2") }
        }
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
        assertThat(readStored("cont-1")!!.arguments).isNull()
    }

    @Test
    fun `expired pending continuation cannot be claimed`() : Unit = runBlocking {
        createPendingContinuation(approvalExpiresAt = fixedClock.instant().plusSeconds(5))
        fixedClock.advance(Duration.ofSeconds(6))

        assertThatThrownBy {
            runBlocking { store.claimForExecution("cont-1", 0L, "runner-1") }
        }
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
    }

    @Test
    fun `late claim marks continuation EXPIRED`() : Unit = runBlocking {
        createPendingContinuation(approvalExpiresAt = fixedClock.instant().plusSeconds(5))
        fixedClock.advance(Duration.ofSeconds(6))

        assertThatThrownBy {
            runBlocking { store.claimForExecution("cont-1", 0L, "runner-1") }
        }
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)

        assertThat(readStored("cont-1")!!.continuation.status).isEqualTo(ApprovalContinuationStatus.EXPIRED)
    }

    @Test
    fun `late claim scrubs arguments`() : Unit = runBlocking {
        createPendingContinuation(
            approvalId = "late-claim-scrub",
            argumentsJson = """{"sensitiveField":"never-print"}""",
            approvalExpiresAt = fixedClock.instant().plusSeconds(5),
        )
        fixedClock.advance(Duration.ofSeconds(6))

        assertThatThrownBy {
            runBlocking { store.claimForExecution("late-claim-scrub", 0L, "runner-1") }
        }
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)

        assertThat(readStored("late-claim-scrub")!!.arguments).isNull()
    }

    @Test
    fun `stale version rejected`() : Unit = runBlocking {
        createPendingContinuation()

        assertThatThrownBy {
            runBlocking { store.claimForExecution("cont-1", 1L, "runner-1") }
        }
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `second claim rejected`() : Unit = runBlocking {
        createPendingContinuation()
        store.claimForExecution("cont-1", 0L, "runner-1")

        assertThatThrownBy {
            runBlocking { store.claimForExecution("cont-1", 1L, "runner-2") }
        }
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
    }

    @Test
    fun `concurrent claims exactly one succeeds`() : Unit = runBlocking {
        createPendingContinuation(argumentsJson = """{"sensitiveField":"race-secret"}""")
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
        assertThat(settled.single { it.isSuccess }.getOrThrow().continuation.status)
            .isEqualTo(ApprovalContinuationStatus.CLAIMED)
        assertThat(settled.single { it.isFailure }.exceptionOrNull())
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `concurrent claims allow exactly one raw-payload winner`() : Unit = runBlocking {
        val raw = """{"sensitiveField":"race-secret"}"""
        createPendingContinuation(argumentsJson = raw)
        val latch = CountDownLatch(1)

        val results = listOf("runner-1", "runner-2").map { runner ->
            async(Dispatchers.Default) {
                latch.await()
                runCatching { store.claimForExecution("cont-1", 0L, runner) }
            }
        }

        latch.countDown()
        val settled = results.awaitAll()

        val winner = settled.single { it.isSuccess }.getOrThrow()
        assertThat(winner.arguments.reveal()).isEqualTo(raw)
        assertThat(settled.single { it.isFailure }.exceptionOrNull())
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `overflow maps to ApprovalContinuationConflictException on claim`() : Unit = runBlocking {
        insertDirect(
            continuation = aPendingContinuation(
                approvalId = "overflow-claim",
                version = Long.MAX_VALUE,
            ),
            arguments = SensitiveToolArguments.of("{}"),
        )

        assertThatThrownBy {
            runBlocking { store.claimForExecution("overflow-claim", Long.MAX_VALUE, "runner-1") }
        }
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `claimed continuation can complete`() : Unit = runBlocking {
        createPendingContinuation()
        store.claimForExecution("cont-1", 0L, "runner-1")

        val completed = store.complete("cont-1", 1L, "runner-1")

        assertThat(completed.status).isEqualTo(ApprovalContinuationStatus.COMPLETED)
        assertThat(completed.version).isEqualTo(2L)
    }

    @Test
    fun `completion retains no arguments`() : Unit = runBlocking {
        createPendingContinuation(argumentsJson = """{"sensitiveField":"never-print"}""")
        store.claimForExecution("cont-1", 0L, "runner-1")

        store.complete("cont-1", 1L, "runner-1")

        assertThat(readStored("cont-1")!!.arguments).isNull()
    }

    @Test
    fun `pending continuation cannot complete`() : Unit = runBlocking {
        createPendingContinuation()

        assertThatThrownBy {
            runBlocking { store.complete("cont-1", 0L, "runner-1") }
        }
            .isInstanceOf(ApprovalContinuationNotCompletableException::class.java)
    }

    @Test
    fun `completed continuation cannot complete again`() : Unit = runBlocking {
        createPendingContinuation()
        store.claimForExecution("cont-1", 0L, "runner-1")
        store.complete("cont-1", 1L, "runner-1")

        assertThatThrownBy {
            runBlocking { store.complete("cont-1", 2L, "runner-1") }
        }
            .isInstanceOf(ApprovalContinuationNotCompletableException::class.java)
    }

    @Test
    fun `completion stores completedAt`() : Unit = runBlocking {
        createPendingContinuation()
        store.claimForExecution("cont-1", 0L, "runner-1")
        fixedClock.advance(Duration.ofSeconds(7))

        val completed = store.complete("cont-1", 1L, "runner-1")

        assertThat(completed.completedAt).isEqualTo(fixedClock.instant())
    }

    @Test
    fun `overflow maps to typed conflict on complete`() : Unit = runBlocking {
        insertDirect(
            continuation = aPendingContinuation(
                approvalId = "overflow-complete",
                status = ApprovalContinuationStatus.CLAIMED,
                claimedBy = "runner-1",
                claimedAt = fixedClock.instant(),
                version = Long.MAX_VALUE,
            ),
            arguments = null,
        )

        assertThatThrownBy {
            runBlocking { store.complete("overflow-complete", Long.MAX_VALUE, "runner-1") }
        }
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `correct claimant can complete`() : Unit = runBlocking {
        createPendingContinuation()
        store.claimForExecution("cont-1", 0L, "runner-1")

        val completed = store.complete("cont-1", 1L, "runner-1")

        assertThat(completed.status).isEqualTo(ApprovalContinuationStatus.COMPLETED)
        assertThat(completed.claimedBy).isEqualTo("runner-1")
    }

    @Test
    fun `different runner cannot complete another runner's claim`() : Unit = runBlocking {
        createPendingContinuation()
        store.claimForExecution("cont-1", 0L, "runner-1")

        assertThatThrownBy {
            runBlocking { store.complete("cont-1", 1L, "runner-2") }
        }
            .isInstanceOf(ApprovalContinuationNotCompletableException::class.java)
    }

    @Test
    fun `blank completedBy is rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking { store.complete("cont-1", 1L, " ") }
            }
            .withMessage("completedBy must not be blank")
    }

    @Test
    fun `pending continuation expires only after deadline`() : Unit = runBlocking {
        createPendingContinuation(approvalExpiresAt = fixedClock.instant().plusSeconds(10))
        fixedClock.advance(Duration.ofSeconds(10))

        val expired = store.expire("cont-1", 0L)

        assertThat(expired.status).isEqualTo(ApprovalContinuationStatus.EXPIRED)
        assertThat(expired.version).isEqualTo(1L)
    }

    @Test
    fun `expiry scrubs arguments`() : Unit = runBlocking {
        createPendingContinuation(
            approvalId = "expire-scrub",
            argumentsJson = """{"sensitiveField":"never-print"}""",
            approvalExpiresAt = fixedClock.instant().plusSeconds(1),
        )
        fixedClock.advance(Duration.ofSeconds(1))

        store.expire("expire-scrub", 0L)

        assertThat(readStored("expire-scrub")!!.arguments).isNull()
    }

    @Test
    fun `timeout before deadline rejected`() : Unit = runBlocking {
        createPendingContinuation(approvalExpiresAt = fixedClock.instant().plusSeconds(10))

        assertThatThrownBy {
            runBlocking { store.expire("cont-1", 0L) }
        }
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `claimed continuation cannot expire`() : Unit = runBlocking {
        createPendingContinuation(approvalExpiresAt = fixedClock.instant().plusSeconds(10))
        store.claimForExecution("cont-1", 0L, "runner-1")
        fixedClock.advance(Duration.ofSeconds(11))

        assertThatThrownBy {
            runBlocking { store.expire("cont-1", 1L) }
        }
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `completed continuation cannot expire`() : Unit = runBlocking {
        createPendingContinuation(approvalExpiresAt = fixedClock.instant().plusSeconds(10))
        store.claimForExecution("cont-1", 0L, "runner-1")
        store.complete("cont-1", 1L, "runner-1")
        fixedClock.advance(Duration.ofSeconds(11))

        assertThatThrownBy {
            runBlocking { store.expire("cont-1", 2L) }
        }
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `pending continuation can cancel`() : Unit = runBlocking {
        createPendingContinuation()

        val cancelled = store.cancel("cont-1", 0L)

        assertThat(cancelled.status).isEqualTo(ApprovalContinuationStatus.CANCELLED)
        assertThat(cancelled.version).isEqualTo(1L)
    }

    @Test
    fun `cancellation scrubs arguments`() : Unit = runBlocking {
        createPendingContinuation(
            approvalId = "cancel-scrub",
            argumentsJson = """{"sensitiveField":"never-print"}""",
        )

        store.cancel("cancel-scrub", 0L)

        assertThat(readStored("cancel-scrub")!!.arguments).isNull()
    }

    @Test
    fun `claimed continuation cannot cancel`() : Unit = runBlocking {
        createPendingContinuation()
        store.claimForExecution("cont-1", 0L, "runner-1")

        assertThatThrownBy {
            runBlocking { store.cancel("cont-1", 1L) }
        }
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `completed continuation cannot cancel`() : Unit = runBlocking {
        createPendingContinuation()
        store.claimForExecution("cont-1", 0L, "runner-1")
        store.complete("cont-1", 1L, "runner-1")

        assertThatThrownBy {
            runBlocking { store.cancel("cont-1", 2L) }
        }
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `expired continuation cannot cancel`() : Unit = runBlocking {
        createPendingContinuation(approvalExpiresAt = fixedClock.instant().plusSeconds(5))
        fixedClock.advance(Duration.ofSeconds(6))

        assertThatThrownBy {
            runBlocking { store.cancel("cont-1", 0L) }
        }
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `late cancellation marks continuation EXPIRED`() : Unit = runBlocking {
        createPendingContinuation(approvalExpiresAt = fixedClock.instant().plusSeconds(5))
        fixedClock.advance(Duration.ofSeconds(6))

        assertThatThrownBy {
            runBlocking { store.cancel("cont-1", 0L) }
        }
            .isInstanceOf(ApprovalContinuationConflictException::class.java)

        assertThat(readStored("cont-1")!!.continuation.status).isEqualTo(ApprovalContinuationStatus.EXPIRED)
    }

    @Test
    fun `late cancellation scrubs arguments`() : Unit = runBlocking {
        createPendingContinuation(
            approvalId = "late-cancel-scrub",
            argumentsJson = """{"sensitiveField":"never-print"}""",
            approvalExpiresAt = fixedClock.instant().plusSeconds(5),
        )
        fixedClock.advance(Duration.ofSeconds(6))

        assertThatThrownBy {
            runBlocking { store.cancel("late-cancel-scrub", 0L) }
        }
            .isInstanceOf(ApprovalContinuationConflictException::class.java)

        assertThat(readStored("late-cancel-scrub")!!.arguments).isNull()
    }

    @Test
    fun `cancelled continuation cannot cancel`() : Unit = runBlocking {
        createPendingContinuation()
        store.cancel("cont-1", 0L)

        assertThatThrownBy {
            runBlocking { store.cancel("cont-1", 1L) }
        }
            .isInstanceOf(ApprovalContinuationConflictException::class.java)
    }

    @Test
    fun `claimForExecution on nonexistent continuation throws ApprovalContinuationNotFoundException`() : Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { store.claimForExecution("does-not-exist", 0L, "runner-1") }
        }
            .isInstanceOf(ApprovalContinuationNotFoundException::class.java)
    }

    @Test
    fun `complete on nonexistent continuation throws ApprovalContinuationNotFoundException`() : Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { store.complete("does-not-exist", 0L, "runner-1") }
        }
            .isInstanceOf(ApprovalContinuationNotFoundException::class.java)
    }

    @Test
    fun `expire on nonexistent continuation throws ApprovalContinuationNotFoundException`() : Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { store.expire("does-not-exist", 0L) }
        }
            .isInstanceOf(ApprovalContinuationNotFoundException::class.java)
    }

    @Test
    fun `cancel on nonexistent continuation throws ApprovalContinuationNotFoundException`() : Unit = runBlocking {
        assertThatThrownBy {
            runBlocking { store.cancel("does-not-exist", 0L) }
        }
            .isInstanceOf(ApprovalContinuationNotFoundException::class.java)
    }

    @Test
    fun `no raw JSON in exception messages`() {
        val raw = """{"sensitiveField":"never-print"}"""

        val throwable = catchThrowable {
            runBlocking {
                store.create(
                    aPendingContinuation(
                        argumentsJson = raw,
                        argumentsDigest = Sha256Digest.of(
                            "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                        ),
                    ),
                    SensitiveToolArguments.of(raw),
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
                    SensitiveToolArguments.of(raw),
                )
            }
        }

        assertThrowableTreeDoesNotContain(throwable, raw)
    }

    @Test
    fun `get never exposes raw arguments`() : Unit = runBlocking {
        val raw = """{"sensitiveField":"never-print"}"""
        createPendingContinuation(argumentsJson = raw)

        val fetched = store.get("cont-1")

        assertThat(fetched).isNotNull()
        assertThat(fetched!!.toString()).doesNotContain(raw)
        assertThat(fetched.toString()).doesNotContain("arguments=")
    }

    @Test
    fun `get lazily marks expired continuation EXPIRED`() : Unit = runBlocking {
        createPendingContinuation(approvalExpiresAt = fixedClock.instant().plusSeconds(5))
        fixedClock.advance(Duration.ofSeconds(6))

        store.get("cont-1")

        assertThat(readStored("cont-1")!!.continuation.status).isEqualTo(ApprovalContinuationStatus.EXPIRED)
    }

    @Test
    fun `get lazily scrubs expired arguments`() : Unit = runBlocking {
        createPendingContinuation(
            approvalId = "lazy-get-scrub",
            argumentsJson = """{"sensitiveField":"never-print"}""",
            approvalExpiresAt = fixedClock.instant().plusSeconds(5),
        )
        fixedClock.advance(Duration.ofSeconds(6))

        store.get("lazy-get-scrub")

        assertThat(readStored("lazy-get-scrub")!!.arguments).isNull()
    }

    @Test
    fun `lazy expiry increments version exactly once`() : Unit = runBlocking {
        createPendingContinuation(approvalExpiresAt = fixedClock.instant().plusSeconds(5))
        fixedClock.advance(Duration.ofSeconds(6))

        store.get("cont-1")
        store.get("cont-1")

        assertThat(readStored("cont-1")!!.continuation.version).isEqualTo(1L)
    }

    @Test
    fun `explicit expire after lazy expiry is rejected without another increment`() : Unit = runBlocking {
        createPendingContinuation(approvalExpiresAt = fixedClock.instant().plusSeconds(5))
        fixedClock.advance(Duration.ofSeconds(6))
        store.get("cont-1")

        assertThatThrownBy {
            runBlocking { store.expire("cont-1", 1L) }
        }
            .isInstanceOf(ApprovalContinuationConflictException::class.java)

        assertThat(readStored("cont-1")!!.continuation.version).isEqualTo(1L)
    }

    @Test
    fun `throwable trees remain raw-argument free`() {
        val raw = """{"sensitiveField":"fixture-redaction-marker"}"""

        val throwable = catchThrowable {
            runBlocking {
                store.create(
                    aPendingContinuation(
                        argumentsJson = raw,
                        argumentsDigest = Sha256Digest.of(
                            "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                        ),
                    ),
                    SensitiveToolArguments.of(raw),
                )
            }
        }

        assertThrowableTreeDoesNotContain(throwable, raw)
    }

    @Test
    fun `sweepExpired expires idle elapsed continuation`() : Unit = runBlocking {
        createPendingContinuation(
            approvalId = "sweep-expired",
            approvalExpiresAt = fixedClock.instant().plusSeconds(5),
        )
        fixedClock.advance(Duration.ofSeconds(6))

        store.sweepExpired()

        assertThat(readStored("sweep-expired")!!.continuation.status).isEqualTo(ApprovalContinuationStatus.EXPIRED)
    }

    @Test
    fun `sweepExpired scrubs idle elapsed payload`() : Unit = runBlocking {
        createPendingContinuation(
            approvalId = "sweep-scrub",
            argumentsJson = """{"sensitiveField":"never-print"}""",
            approvalExpiresAt = fixedClock.instant().plusSeconds(5),
        )
        fixedClock.advance(Duration.ofSeconds(6))

        store.sweepExpired()

        assertThat(readStored("sweep-scrub")!!.arguments).isNull()
    }

    @Test
    fun `sweepExpired ignores valid PENDING continuation`() : Unit = runBlocking {
        createPendingContinuation(
            approvalId = "sweep-pending",
            argumentsJson = """{"sensitiveField":"still-present"}""",
            approvalExpiresAt = fixedClock.instant().plusSeconds(30),
        )

        store.sweepExpired()

        val stored = readStored("sweep-pending")!!
        assertThat(stored.continuation.status).isEqualTo(ApprovalContinuationStatus.PENDING)
        assertThat(stored.arguments).isNotNull()
    }

    @Test
    fun `sweepExpired ignores CLAIMED continuation`() : Unit = runBlocking {
        insertDirect(
            continuation = aPendingContinuation(
                approvalId = "sweep-claimed",
                status = ApprovalContinuationStatus.CLAIMED,
                claimedBy = "runner-1",
                claimedAt = fixedClock.instant().minusSeconds(20),
                approvalExpiresAt = fixedClock.instant().minusSeconds(10),
                version = 4L,
            ),
            arguments = null,
        )

        store.sweepExpired()

        assertThat(readStored("sweep-claimed")!!.continuation.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
    }

    @Test
    fun `sweepExpired ignores COMPLETED continuation`() : Unit = runBlocking {
        insertDirect(
            continuation = aPendingContinuation(
                approvalId = "sweep-completed",
                status = ApprovalContinuationStatus.COMPLETED,
                claimedBy = "runner-1",
                claimedAt = fixedClock.instant().minusSeconds(20),
                completedAt = fixedClock.instant().minusSeconds(15),
                approvalExpiresAt = fixedClock.instant().minusSeconds(10),
                version = 5L,
            ),
            arguments = null,
        )

        store.sweepExpired()

        assertThat(readStored("sweep-completed")!!.continuation.status).isEqualTo(ApprovalContinuationStatus.COMPLETED)
    }

    @Test
    fun `sweepExpired ignores CANCELLED continuation`() : Unit = runBlocking {
        insertDirect(
            continuation = aPendingContinuation(
                approvalId = "sweep-cancelled",
                status = ApprovalContinuationStatus.CANCELLED,
                approvalExpiresAt = fixedClock.instant().minusSeconds(10),
                version = 2L,
            ),
            arguments = null,
        )

        store.sweepExpired()

        assertThat(readStored("sweep-cancelled")!!.continuation.status).isEqualTo(ApprovalContinuationStatus.CANCELLED)
    }

    @Test
    fun `sweepExpired is idempotent`() : Unit = runBlocking {
        createPendingContinuation(
            approvalId = "sweep-idempotent",
            approvalExpiresAt = fixedClock.instant().plusSeconds(5),
        )
        fixedClock.advance(Duration.ofSeconds(6))

        store.sweepExpired()
        store.sweepExpired()

        assertThat(readStored("sweep-idempotent")!!.continuation.version).isEqualTo(1L)
    }

    @Test
    fun `sweepExpired returns transitioned count`() : Unit = runBlocking {
        createPendingContinuation(
            approvalId = "sweep-expired-1",
            approvalExpiresAt = fixedClock.instant().plusSeconds(5),
        )
        createPendingContinuation(
            approvalId = "sweep-expired-2",
            approvalExpiresAt = fixedClock.instant().plusSeconds(5),
        )
        createPendingContinuation(
            approvalId = "sweep-valid",
            approvalExpiresAt = fixedClock.instant().plusSeconds(30),
        )
        fixedClock.advance(Duration.ofSeconds(6))

        val transitioned = store.sweepExpired()

        assertThat(transitioned).isEqualTo(2)
    }

    @Test
    fun `concurrent sweep and late claim expire exactly once and never release payload`() : Unit = runBlocking {
        val raw = """{"sensitiveField":"race-value"}"""
        createPendingContinuation(
            approvalId = "sweep-claim-race",
            argumentsJson = raw,
            approvalExpiresAt = fixedClock.instant().plusSeconds(5),
        )
        fixedClock.advance(Duration.ofSeconds(6))
        val latch = CountDownLatch(1)

        val sweepResult = async(Dispatchers.Default) {
            latch.await()
            runCatching { store.sweepExpired() }
        }
        val claimResult = async(Dispatchers.Default) {
            latch.await()
            runCatching {
                store.claimForExecution("sweep-claim-race", 0L, "runner-1")
            }
        }

        latch.countDown()

        val sweep = sweepResult.await().getOrThrow()
        val claim = claimResult.await()
        val stored = readStored("sweep-claim-race")!!

        assertThat(sweep).isIn(0, 1)
        assertThat(claim.exceptionOrNull())
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)

        assertThat(stored.continuation.status)
            .isEqualTo(ApprovalContinuationStatus.EXPIRED)
        assertThat(stored.continuation.version).isEqualTo(1L)
        assertThat(stored.arguments).isNull()
        assertThat(claim.exceptionOrNull()?.message ?: "").doesNotContain(raw)
    }

    @Test
    fun `sweep never exposes raw arguments`() : Unit = runBlocking {
        val raw = """{"sensitiveField":"never-print"}"""
        createPendingContinuation(
            approvalId = "sweep-no-leak",
            argumentsJson = raw,
            approvalExpiresAt = fixedClock.instant().plusSeconds(5),
        )
        fixedClock.advance(Duration.ofSeconds(6))

        val sweepResult = runCatching { store.sweepExpired() }
        val stored = readStored("sweep-no-leak")!!
        val fetched = store.get("sweep-no-leak")

        assertThat(sweepResult.getOrThrow()).isEqualTo(1)
        assertThat(stored.arguments).isNull()
        assertThat(stored.toString()).doesNotContain(raw)
        assertThat(fetched.toString()).doesNotContain(raw)
        sweepResult.exceptionOrNull()?.let { assertThrowableTreeDoesNotContain(it, raw) }
    }

    @Test
    fun `findStaleClaimed returns stale CLAIMED records only in deterministic order`() : Unit = runBlocking {
        insertDirect(
            continuation = aPendingContinuation(
                approvalId = "stale-b",
                status = ApprovalContinuationStatus.CLAIMED,
                claimedBy = "runner-1",
                claimedAt = fixedClock.instant().minusSeconds(60),
                version = 1L,
            ),
            arguments = null,
        )
        insertDirect(
            continuation = aPendingContinuation(
                approvalId = "fresh-claimed",
                status = ApprovalContinuationStatus.CLAIMED,
                claimedBy = "runner-1",
                claimedAt = fixedClock.instant().minusSeconds(5),
                version = 1L,
            ),
            arguments = null,
        )
        insertDirect(
            continuation = aPendingContinuation(
                approvalId = "stale-a",
                status = ApprovalContinuationStatus.CLAIMED,
                claimedBy = "runner-2",
                claimedAt = fixedClock.instant().minusSeconds(60),
                version = 1L,
            ),
            arguments = null,
        )
        insertDirect(
            continuation = aPendingContinuation(
                approvalId = "pending",
                status = ApprovalContinuationStatus.PENDING,
            ),
            arguments = SensitiveToolArguments.of("""{"sensitiveField":"pending"}"""),
        )
        insertDirect(
            continuation = aPendingContinuation(
                approvalId = "completed",
                status = ApprovalContinuationStatus.COMPLETED,
                claimedBy = "runner-3",
                claimedAt = fixedClock.instant().minusSeconds(80),
                completedAt = fixedClock.instant().minusSeconds(70),
                version = 2L,
            ),
            arguments = null,
        )
        insertDirect(
            continuation = aPendingContinuation(
                approvalId = "expired",
                status = ApprovalContinuationStatus.EXPIRED,
                version = 1L,
            ),
            arguments = null,
        )
        insertDirect(
            continuation = aPendingContinuation(
                approvalId = "cancelled",
                status = ApprovalContinuationStatus.CANCELLED,
                version = 1L,
            ),
            arguments = null,
        )
        insertDirect(
            continuation = aPendingContinuation(
                approvalId = "cancelled-uncertain",
                status = ApprovalContinuationStatus.CANCELLED_UNCERTAIN,
                claimedBy = "runner-4",
                claimedAt = fixedClock.instant().minusSeconds(90),
                recoveryResolvedBy = "operator-1",
                recoveryResolvedAt = fixedClock.instant().minusSeconds(30),
                recoveryReasonCode = "worker-lost",
                version = 2L,
            ),
            arguments = null,
        )

        val stale = store.findStaleClaimed(
            claimedBefore = fixedClock.instant().minusSeconds(30),
            limit = 10,
        )

        assertThat(stale.map { it.approvalId }).containsExactly("stale-a", "stale-b")
        assertThat(stale).allMatch { it.status == ApprovalContinuationStatus.CLAIMED }
    }

    @Test
    fun `findStaleClaimed enforces bounds and never leaks raw arguments`() : Unit = runBlocking {
        val raw = """{"sensitiveField":"find-stale-redaction"}"""
        insertDirect(
            continuation = aPendingContinuation(
                approvalId = "stale-redacted",
                status = ApprovalContinuationStatus.CLAIMED,
                claimedBy = "runner-1",
                claimedAt = fixedClock.instant().minusSeconds(45),
                version = 1L,
            ),
            arguments = null,
        )

        val stale = store.findStaleClaimed(
            claimedBefore = fixedClock.instant().minusSeconds(30),
            limit = 1,
        )

        assertThat(stale.single().toString()).doesNotContain(raw)
        assertThat(stale.single().toString()).doesNotContain("arguments=")

        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.findStaleClaimed(fixedClock.instant(), 0) } }
            .withMessage("limit must be between 1 and 100")
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { store.findStaleClaimed(fixedClock.instant(), 101) } }
            .withMessage("limit must be between 1 and 100")
    }

    @Test
    fun `forceCancelClaimed transitions CLAIMED to CANCELLED_UNCERTAIN with recovery metadata`() : Unit = runBlocking {
        createPendingContinuation(argumentsJson = """{"sensitiveField":"claim"}""")
        store.claimForExecution("cont-1", 0L, "runner-1")
        fixedClock.advance(Duration.ofSeconds(15))

        val cancelled = store.forceCancelClaimed("cont-1", 1L, "operator-7", "worker-lost")

        assertThat(cancelled.status).isEqualTo(ApprovalContinuationStatus.CANCELLED_UNCERTAIN)
        assertThat(cancelled.version).isEqualTo(2L)
        assertThat(cancelled.recoveryResolvedBy).isEqualTo("operator-7")
        assertThat(cancelled.recoveryResolvedAt).isEqualTo(fixedClock.instant())
        assertThat(cancelled.recoveryReasonCode).isEqualTo("worker-lost")
        assertThat(readStored("cont-1")!!.arguments).isNull()
    }

    @Test
    fun `forceCancelClaimed rejects stale version and repeated force cancellation`() : Unit = runBlocking {
        createPendingContinuation()
        store.claimForExecution("cont-1", 0L, "runner-1")

        assertThatThrownBy {
            runBlocking { store.forceCancelClaimed("cont-1", 0L, "operator-1", "worker-lost") }
        }
            .isInstanceOf(ApprovalContinuationConflictException::class.java)

        store.forceCancelClaimed("cont-1", 1L, "operator-1", "worker-lost")

        assertThatThrownBy {
            runBlocking { store.forceCancelClaimed("cont-1", 2L, "operator-2", "worker-lost") }
        }
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
    }

    @Test
    fun `forceCancelClaimed rejects PENDING and COMPLETED continuations`() : Unit = runBlocking {
        createPendingContinuation(approvalId = "pending-force-cancel")
        createPendingContinuation(approvalId = "completed-force-cancel")
        store.claimForExecution("completed-force-cancel", 0L, "runner-1")
        store.complete("completed-force-cancel", 1L, "runner-1")

        assertThatThrownBy {
            runBlocking { store.forceCancelClaimed("pending-force-cancel", 0L, "operator-1", "worker-lost") }
        }
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)

        assertThatThrownBy {
            runBlocking { store.forceCancelClaimed("completed-force-cancel", 2L, "operator-1", "worker-lost") }
        }
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)
    }

    @Test
    fun `CANCELLED_UNCERTAIN cannot be claimed or completed`() : Unit = runBlocking {
        createPendingContinuation()
        store.claimForExecution("cont-1", 0L, "runner-1")
        store.forceCancelClaimed("cont-1", 1L, "operator-1", "worker-lost")

        assertThatThrownBy {
            runBlocking { store.claimForExecution("cont-1", 2L, "runner-2") }
        }
            .isInstanceOf(ApprovalContinuationNotClaimableException::class.java)

        assertThatThrownBy {
            runBlocking { store.complete("cont-1", 2L, "runner-1") }
        }
            .isInstanceOf(ApprovalContinuationNotCompletableException::class.java)
    }

    private fun createPendingContinuation(
        approvalId: String = "cont-1",
        argumentsJson: String = "{}",
        approvalExpiresAt: Instant = fixedClock.instant().plusSeconds(3600),
    ): ApprovalContinuation {
        val continuation = aPendingContinuation(
            approvalId = approvalId,
            argumentsJson = argumentsJson,
            approvalExpiresAt = approvalExpiresAt,
        )
        runBlocking {
            store.create(continuation, SensitiveToolArguments.of(argumentsJson))
        }
        return continuation
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
        approvalExpiresAt: Instant = fixedClock.instant().plusSeconds(3600),
        status: ApprovalContinuationStatus = ApprovalContinuationStatus.PENDING,
        claimedBy: String? = null,
        claimedAt: Instant? = null,
        completedAt: Instant? = null,
        recoveryResolvedBy: String? = null,
        recoveryResolvedAt: Instant? = null,
        recoveryReasonCode: String? = null,
        version: Long = 0L,
    ): ApprovalContinuation = ApprovalContinuation(
        approvalId = approvalId,
        workflowRunId = workflowRunId,
        correlationId = correlationId,
        toolCallId = toolCallId,
        toolName = toolName,
        argumentsDigest = argumentsDigest,
        policyVersion = policyVersion,
        workflowDigest = workflowDigest,
        status = status,
        createdAt = createdAt,
        approvalExpiresAt = approvalExpiresAt,
        claimedBy = claimedBy,
        claimedAt = claimedAt,
        completedAt = completedAt,
        recoveryResolvedBy = recoveryResolvedBy,
        recoveryResolvedAt = recoveryResolvedAt,
        recoveryReasonCode = recoveryReasonCode,
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
    private fun insertDirect(
        continuation: ApprovalContinuation,
        arguments: SensitiveToolArguments?,
    ) {
        val field = store.javaClass.getDeclaredField("store")
        field.isAccessible = true
        val map = field.get(store) as ConcurrentHashMap<String, StoredApprovalContinuation>
        map[continuation.approvalId] = StoredApprovalContinuation(
            continuation = continuation,
            arguments = arguments,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun readStored(approvalId: String): StoredApprovalContinuation? {
        val field = store.javaClass.getDeclaredField("store")
        field.isAccessible = true
        val map = field.get(store) as ConcurrentHashMap<String, StoredApprovalContinuation>
        return map[approvalId]
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
