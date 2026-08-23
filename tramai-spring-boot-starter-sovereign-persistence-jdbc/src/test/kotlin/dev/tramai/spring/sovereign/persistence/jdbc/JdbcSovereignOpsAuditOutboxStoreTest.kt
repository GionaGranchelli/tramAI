package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertSame

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcSovereignOpsAuditOutboxStoreTest {

    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"

        private val postgres = PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("sovereign_ops_test")
            .withUsername("test")
            .withPassword("test")

        private fun createDataSource(): DataSource = PGSimpleDataSource().apply {
            setUrl(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }

        private val BASE_NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")
        private val AGGREGATE_DIGEST = "a".repeat(64)
        private val REASON_DIGEST = "b".repeat(64)

        private fun within(millis: Long) =
            org.assertj.core.api.Assertions.within(java.time.Duration.ofMillis(millis))
    }

    private val testAesKey = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private val testCodec = object : JdbcOpsAuditOutboxPayloadCodec {
        private val ALGORITHM = "AES/GCM/NoPadding"
        private val TAG_LENGTH = 128

        override fun encode(plaintext: ByteArray): JdbcEncryptedAuditOutboxPayload {
            val cipher = Cipher.getInstance(ALGORITHM)
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val keySpec = SecretKeySpec(testAesKey, "AES")
            val spec = GCMParameterSpec(TAG_LENGTH, nonce)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)
            val ciphertext = cipher.doFinal(plaintext)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(plaintext)
                .joinToString("") { "%02x".format(it) }
            return JdbcEncryptedAuditOutboxPayload(
                ciphertext = ciphertext,
                keyId = "test-key-1",
                algorithm = ALGORITHM,
                nonce = nonce,
                payloadDigest = "sha256:$digest",
            )
        }

        override fun decode(envelope: JdbcEncryptedAuditOutboxPayload): ByteArray {
            val cipher = Cipher.getInstance(ALGORITHM)
            val keySpec = SecretKeySpec(testAesKey, "AES")
            val spec = GCMParameterSpec(TAG_LENGTH, envelope.nonce)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
            return cipher.doFinal(envelope.ciphertext)
        }
    }

    private lateinit var dataSource: DataSource
    private lateinit var store: JdbcSovereignOpsAuditOutboxStore

    @BeforeAll
    fun startPostgres() {
        postgres.start()
        dataSource = createDataSource()
        runMigrations()
    }

    @AfterAll
    fun stopPostgres() {
        postgres.stop()
    }

    @BeforeEach
    fun setUp() {
        truncateTables()
        store = JdbcSovereignOpsAuditOutboxStore(
            dataSource = dataSource,
            payloadCodec = testCodec,
            claimLeaseDuration = Duration.ofMinutes(5),
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // isDurable
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun isDurableReturnsTrue() {
        assertThat(store.isDurable()).isTrue
    }

    // ══════════════════════════════════════════════════════════════════
    // Append tests
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun appendPreparedRecordPersistsAndReturnsRecord() {
        runBlocking {
            val record = record("test-append")
            val result = store.append(record)
            assertThat(result).isEqualTo(record)
            assertThat(store.get("test-append")).isEqualTo(record)
        }
    }

    @Test
    fun appendNonPreparedRecordIsRejected() {
        runBlocking {
            val record = record("invalid-status", status = SovereignOpsAuditOutboxStatus.PENDING)
            assertThatThrownBy {
                runBlocking { store.append(record) }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("tramai-sovereign-ops-outbox-invalid-status")
        }
    }

    @Test
    fun duplicateOutboxIdIsRejected() {
        runBlocking {
            store.append(record("dup-id"))
            assertThatThrownBy {
                runBlocking { store.append(record("dup-id")) }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("tramai-sovereign-ops-outbox-duplicate-id")
        }
    }

    @Test
    fun duplicateEventKeyIsRejected() {
        runBlocking {
            store.append(record("first", eventKey = "same-event"))
            assertThatThrownBy {
                runBlocking { store.append(record("second", eventKey = "same-event")) }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("tramai-sovereign-ops-outbox-duplicate-event-key")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Read tests
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun getReturnsStoredRecord() {
        runBlocking {
            val record = record("get-test")
            store.append(record)
            val result = store.get("get-test")
            assertThat(result).isNotNull
            assertThat(result!!.outboxId).isEqualTo("get-test")
            assertThat(result.eventKey).isEqualTo("event-get-test")
            assertThat(result.status).isEqualTo(SovereignOpsAuditOutboxStatus.PREPARED)
            assertThat(result.aggregateIdDigest).isEqualTo(AGGREGATE_DIGEST)
            assertThat(result.operation).isEqualTo("denyApproval")
            assertThat(result.actor).isEqualTo("operator-1")
            assertThat(result.approvalStatus).isEqualTo("DENIED")
            assertThat(result.approvalVersion).isEqualTo(7L)
            assertThat(result.reasonDigest).isEqualTo(REASON_DIGEST)
            assertThat(result.reasonLength).isEqualTo(42)
        }
    }

    @Test
    fun getReturnsNullForUnknownId() {
        runBlocking {
            val result = store.get("unknown-id")
            assertThat(result).isNull()
        }
    }

    @Test
    fun findByEventKeyReturnsStoredRecord() {
        runBlocking {
            store.append(record("find-key", eventKey = "my-unique-key"))
            val result = store.findByEventKey("my-unique-key")
            assertThat(result).isNotNull
            assertThat(result!!.outboxId).isEqualTo("find-key")
            assertThat(result.eventKey).isEqualTo("my-unique-key")
        }
    }

    @Test
    fun findByEventKeyReturnsNullForUnknownKey() {
        runBlocking {
            val result = store.findByEventKey("nonexistent-key")
            assertThat(result).isNull()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Lifecycle tests
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun markReadyForDispatchTransitionsPreparedToPending() {
        runBlocking {
            store.append(record("lifecycle-ready"))
            val result = store.markReadyForDispatch(
                "lifecycle-ready",
                SovereignOpsAuditOutboxStatus.PREPARED,
            )
            assertThat(result.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
            val stored = store.get("lifecycle-ready")
            assertThat(stored!!.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
        }
    }

    @Test
    fun markReadyForDispatchWithWrongExpectedStatusFails() {
        runBlocking {
            store.append(record("wrong-status"))
            store.markReadyForDispatch("wrong-status", SovereignOpsAuditOutboxStatus.PREPARED)
            assertThatThrownBy {
                runBlocking {
                    store.markReadyForDispatch("wrong-status", SovereignOpsAuditOutboxStatus.PREPARED)
                }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("tramai-sovereign-ops-outbox-status-mismatch")
        }
    }

    @Test
    fun markReadyForDispatchOnUnknownIdFails() {
        runBlocking {
            assertThatThrownBy {
                runBlocking {
                    store.markReadyForDispatch("nope", SovereignOpsAuditOutboxStatus.PREPARED)
                }
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("tramai-sovereign-ops-outbox-not-found")
        }
    }

    @Test
    fun markEmittedTransitionsEmittingToEmitted() {
        runBlocking {
            val emittedAt = BASE_NOW.plusSeconds(10)
            store.append(record("emit-me"))
            store.markReadyForDispatch("emit-me", SovereignOpsAuditOutboxStatus.PREPARED)
            store.claimPending("dispatcher", 10, BASE_NOW)
            val result = store.markEmitted(
                "emit-me",
                SovereignOpsAuditOutboxStatus.EMITTING,
                emittedAt,
            )
            assertThat(result.status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTED)
            assertThat(result.emittedAt).isCloseTo(emittedAt, within(1000))
        }
    }

    @Test
    fun markEmittedWithWrongStatusFails() {
        runBlocking {
            store.append(record("wrong-emit"))
            assertThatThrownBy {
                runBlocking {
                    store.markEmitted("wrong-emit", SovereignOpsAuditOutboxStatus.EMITTING, BASE_NOW)
                }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("tramai-sovereign-ops-outbox-status-mismatch")
        }
    }

    @Test
    fun markFailedEmittingRetryableBecomesFailedRetryable() {
        runBlocking {
            store.append(record("fail-retry"))
            store.markReadyForDispatch("fail-retry", SovereignOpsAuditOutboxStatus.PREPARED)
            store.claimPending("dispatcher", 10, BASE_NOW)
            val result = store.markFailed(
                "fail-retry",
                SovereignOpsAuditOutboxStatus.EMITTING,
                errorCode = "NETWORK_ERROR",
                retryable = true,
            )
            assertThat(result.status).isEqualTo(SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE)
            assertThat(result.lastErrorCode).isEqualTo("NETWORK_ERROR")
        }
    }

    @Test
    fun markFailedEmittingPermanentBecomesFailedPermanent() {
        runBlocking {
            store.append(record("fail-perm"))
            store.markReadyForDispatch("fail-perm", SovereignOpsAuditOutboxStatus.PREPARED)
            store.claimPending("dispatcher", 10, BASE_NOW)
            val result = store.markFailed(
                "fail-perm",
                SovereignOpsAuditOutboxStatus.EMITTING,
                errorCode = "INVALID_PAYLOAD",
                retryable = false,
            )
            assertThat(result.status).isEqualTo(SovereignOpsAuditOutboxStatus.FAILED_PERMANENT)
            assertThat(result.lastErrorCode).isEqualTo("INVALID_PAYLOAD")
        }
    }

    @Test
    fun markFailedPreparedPermanentAllowedForOrphanRecovery() {
        runBlocking {
            store.append(record("orphan-recovery"))
            val result = store.markFailed(
                "orphan-recovery",
                SovereignOpsAuditOutboxStatus.PREPARED,
                errorCode = "TRANSITION_FAILED",
                retryable = false,
            )
            assertThat(result.status).isEqualTo(SovereignOpsAuditOutboxStatus.FAILED_PERMANENT)
            assertThat(result.lastErrorCode).isEqualTo("TRANSITION_FAILED")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Claim tests
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun claimPendingClaimsPendingRecords() {
        runBlocking {
            store.append(record("claim-pending"))
            store.markReadyForDispatch("claim-pending", SovereignOpsAuditOutboxStatus.PREPARED)
            val claimed = store.claimPending("worker-1", 10, BASE_NOW)
            assertThat(claimed).hasSize(1)
            assertThat(claimed[0].outboxId).isEqualTo("claim-pending")
            assertThat(claimed[0].status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTING)
            assertThat(claimed[0].claimedBy).isEqualTo("worker-1")
            assertThat(claimed[0].claimExpiresAt).isNotNull
        }
    }

    @Test
    fun claimPendingDoesNotClaimPreparedRecords() {
        runBlocking {
            store.append(record("still-prepared"))
            val claimed = store.claimPending("worker-1", 10, BASE_NOW)
            assertThat(claimed).isEmpty()
        }
    }

    @Test
    fun claimPendingClaimsFailedRetryableRecords() {
        runBlocking {
            store.append(record("retry-claim"))
            store.markReadyForDispatch("retry-claim", SovereignOpsAuditOutboxStatus.PREPARED)
            store.claimPending("worker-1", 10, BASE_NOW)
            store.markFailed(
                "retry-claim",
                SovereignOpsAuditOutboxStatus.EMITTING,
                errorCode = "TIMEOUT",
                retryable = true,
            )
            val claimed = store.claimPending("worker-2", 10, BASE_NOW.plusSeconds(60))
            assertThat(claimed).hasSize(1)
            assertThat(claimed[0].outboxId).isEqualTo("retry-claim")
            assertThat(claimed[0].claimedBy).isEqualTo("worker-2")
        }
    }

    @Test
    fun claimPendingClaimsExpiredEmittingRecords() {
        runBlocking {
            store.append(record("expired-claim"))
            store.markReadyForDispatch("expired-claim", SovereignOpsAuditOutboxStatus.PREPARED)
            store.claimPending("worker-1", 10, BASE_NOW)
            val afterLease = BASE_NOW.plus(Duration.ofMinutes(6))
            val claimed = store.claimPending("worker-2", 10, afterLease)
            assertThat(claimed).hasSize(1)
            assertThat(claimed[0].outboxId).isEqualTo("expired-claim")
            assertThat(claimed[0].claimedBy).isEqualTo("worker-2")
        }
    }

    @Test
    fun claimPendingDoesNotClaimNonExpiredEmitting() {
        runBlocking {
            store.append(record("fresh-claim"))
            store.markReadyForDispatch("fresh-claim", SovereignOpsAuditOutboxStatus.PREPARED)
            store.claimPending("worker-1", 10, BASE_NOW)
            val beforeLease = BASE_NOW.plus(Duration.ofMinutes(1))
            val claimed = store.claimPending("worker-2", 10, beforeLease)
            assertThat(claimed).isEmpty()
        }
    }

    @Test
    fun claimPendingIncrementsAttemptCount() {
        runBlocking {
            store.append(record("attempts"))
            store.markReadyForDispatch("attempts", SovereignOpsAuditOutboxStatus.PREPARED)
            store.claimPending("worker-1", 10, BASE_NOW)
            store.markFailed("attempts", SovereignOpsAuditOutboxStatus.EMITTING, "ERR", retryable = true)
            val afterLease = BASE_NOW.plus(Duration.ofMinutes(6))
            val claimed = store.claimPending("worker-2", 10, afterLease)
            assertThat(claimed[0].attemptCount).isEqualTo(2)
        }
    }

    @Test
    fun claimPendingRespectsLimit() {
        runBlocking {
            store.append(record("a", eventKey = "key-a"))
            store.append(record("b", eventKey = "key-b"))
            store.append(record("c", eventKey = "key-c"))
            store.markReadyForDispatch("a", SovereignOpsAuditOutboxStatus.PREPARED)
            store.markReadyForDispatch("b", SovereignOpsAuditOutboxStatus.PREPARED)
            store.markReadyForDispatch("c", SovereignOpsAuditOutboxStatus.PREPARED)
            val claimed = store.claimPending("worker-1", 2, BASE_NOW)
            assertThat(claimed).hasSize(2)
        }
    }

    @Test
    fun limitZeroReturnsEmptyListFromClaimPending() {
        runBlocking {
            store.append(record("zero-limit"))
            store.markReadyForDispatch("zero-limit", SovereignOpsAuditOutboxStatus.PREPARED)
            val claimed = store.claimPending("worker-1", 0, BASE_NOW)
            assertThat(claimed).isEmpty()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Concurrency tests
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun twoWorkersClaimDifferentPendingRecordsWithoutDuplicates() {
        runBlocking {
            store.append(record("conc-a", eventKey = "conc-key-a"))
            store.append(record("conc-b", eventKey = "conc-key-b"))
            store.markReadyForDispatch("conc-a", SovereignOpsAuditOutboxStatus.PREPARED)
            store.markReadyForDispatch("conc-b", SovereignOpsAuditOutboxStatus.PREPARED)
            coroutineScope {
                val worker1 = async {
                    store.claimPending("worker-1", 10, BASE_NOW)
                }
                val worker2 = async {
                    store.claimPending("worker-2", 10, BASE_NOW)
                }
                val results1 = worker1.await()
                val results2 = worker2.await()
                val allClaimed = results1.map { it.outboxId } + results2.map { it.outboxId }
                assertThat(allClaimed).hasSize(2)
                assertThat(allClaimed).containsOnly("conc-a", "conc-b")
                assertThat(allClaimed).hasSize(allClaimed.toSet().size)
            }
        }
    }

    @Test
    fun concurrentMarkReadyForDispatchAllowsExactlyOneSuccess() {
        runBlocking {
            store.append(record("race-ready"))
            coroutineScope {
                val attempt1 = async {
                    runCatching {
                        store.markReadyForDispatch("race-ready", SovereignOpsAuditOutboxStatus.PREPARED)
                    }
                }
                val attempt2 = async {
                    runCatching {
                        store.markReadyForDispatch("race-ready", SovereignOpsAuditOutboxStatus.PREPARED)
                    }
                }
                val results = listOf(attempt1.await(), attempt2.await())
                val successes = results.count { it.isSuccess }
                assertThat(successes).isEqualTo(1)
            }
        }
    }

    @Test
    fun concurrentMarkEmittedVsMarkFailedAllowsExactlyOneWinner() {
        runBlocking {
            store.append(record("race-emit-fail"))
            store.markReadyForDispatch("race-emit-fail", SovereignOpsAuditOutboxStatus.PREPARED)
            store.claimPending("worker-1", 10, BASE_NOW)
            coroutineScope {
                val emitAttempt = async {
                    runCatching {
                        store.markEmitted("race-emit-fail", SovereignOpsAuditOutboxStatus.EMITTING, BASE_NOW)
                    }
                }
                val failAttempt = async {
                    runCatching {
                        store.markFailed(
                            "race-emit-fail",
                            SovereignOpsAuditOutboxStatus.EMITTING,
                            errorCode = "ERR",
                            retryable = false,
                        )
                    }
                }
                val results = listOf(emitAttempt.await(), failAttempt.await())
                val successes = results.count { it.isSuccess }
                assertThat(successes).isEqualTo(1)
            }
        }
    }

    @Test
    fun concurrentAppendWithSameEventKeyAllowsOneSuccess() {
        runBlocking {
            coroutineScope {
                val attempt1 = async {
                    runCatching { store.append(record("conc-id-1", eventKey = "same-key")) }
                }
                val attempt2 = async {
                    runCatching { store.append(record("conc-id-2", eventKey = "same-key")) }
                }
                val results = listOf(attempt1.await(), attempt2.await())
                val successes = results.count { it.isSuccess }
                assertThat(successes).isEqualTo(1)
            }
        }
    }

    @Test
    fun completedContinuationCannotBeClaimedAgain() {
        runBlocking {
            store.append(record("completed-once"))
            store.markReadyForDispatch("completed-once", SovereignOpsAuditOutboxStatus.PREPARED)
            store.claimPending("worker-1", 10, BASE_NOW)
            store.markEmitted("completed-once", SovereignOpsAuditOutboxStatus.EMITTING, BASE_NOW)
            val afterLease = BASE_NOW.plus(Duration.ofMinutes(10))
            val claimed = store.claimPending("worker-2", 10, afterLease)
            assertThat(claimed).isEmpty()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Diagnostic listing tests
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun listPendingReturnsOnlyPendingRecords() {
        runBlocking {
            store.append(record("prep-only"))
            store.append(record("pend-me"))
            store.markReadyForDispatch("pend-me", SovereignOpsAuditOutboxStatus.PREPARED)
            store.append(record("pend-too"))
            store.markReadyForDispatch("pend-too", SovereignOpsAuditOutboxStatus.PREPARED)
            val pending = store.listPending(10)
            assertThat(pending).hasSize(2)
            assertThat(pending).allMatch { it.status == SovereignOpsAuditOutboxStatus.PENDING }
        }
    }

    @Test
    fun listByStatusReturnsRecordsByExactStatus() {
        runBlocking {
            store.append(record("l-prep"))
            store.append(record("l-pend"))
            store.markReadyForDispatch("l-pend", SovereignOpsAuditOutboxStatus.PREPARED)
            val prepared = store.listByStatus(SovereignOpsAuditOutboxStatus.PREPARED, 10)
            val pending = store.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10)
            assertThat(prepared).hasSize(1)
            assertThat(prepared[0].outboxId).isEqualTo("l-prep")
            assertThat(pending).hasSize(1)
            assertThat(pending[0].outboxId).isEqualTo("l-pend")
        }
    }

    @Test
    fun listExpiredEmittingReturnsExpiredEmittingRecords() {
        runBlocking {
            store.append(record("will-expire"))
            store.markReadyForDispatch("will-expire", SovereignOpsAuditOutboxStatus.PREPARED)
            store.claimPending("worker", 10, BASE_NOW)
            val expired = store.listExpiredEmitting(BASE_NOW.plus(Duration.ofMinutes(6)), 10)
            assertThat(expired).hasSize(1)
            assertThat(expired[0].outboxId).isEqualTo("will-expire")
        }
    }

    @Test
    fun listExpiredEmittingDoesNotReturnNonExpiredEmitting() {
        runBlocking {
            store.append(record("not-expired"))
            store.markReadyForDispatch("not-expired", SovereignOpsAuditOutboxStatus.PREPARED)
            store.claimPending("worker", 10, BASE_NOW)
            val beforeLease = store.listExpiredEmitting(BASE_NOW.plus(Duration.ofMinutes(1)), 10)
            assertThat(beforeLease).isEmpty()
        }
    }

    @Test
    fun listWithLimitZeroReturnsEmptyList() {
        runBlocking {
            store.append(record("no-limit"))
            store.markReadyForDispatch("no-limit", SovereignOpsAuditOutboxStatus.PREPARED)
            assertThat(store.listPending(0)).isEmpty()
            assertThat(store.listByStatus(SovereignOpsAuditOutboxStatus.PREPARED, 0)).isEmpty()
            assertThat(store.listExpiredEmitting(BASE_NOW, 0)).isEmpty()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Queryable column tamper detection
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun queryableColumnEventKeyTamperingDetected() {
        runBlocking {
            store.append(record("tamper-key"))
            tamperColumn("tamper-key", "event_key", "tampered-key")
            assertThatThrownBy {
                runBlocking { store.get("tamper-key") }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("audit-outbox-column-event-key-mismatch")
        }
    }

    @Test
    fun queryableColumnStatusTamperingDetected() {
        runBlocking {
            store.append(record("tamper-status"))
            store.markReadyForDispatch("tamper-status", SovereignOpsAuditOutboxStatus.PREPARED)
            tamperColumn("tamper-status", "status", "PREPARED")
            assertThatThrownBy {
                runBlocking { store.get("tamper-status") }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("audit-outbox-column-status-mismatch")
        }
    }

    @Test
    fun queryableColumnAttemptCountTamperingDetected() {
        runBlocking {
            store.append(record("tamper-attempts"))
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "UPDATE audit_outbox SET attempt_count = 99 WHERE outbox_id = ?"
                ).use { stmt ->
                    stmt.setString(1, "tamper-attempts")
                    stmt.executeUpdate()
                }
            }
            assertThatThrownBy {
                runBlocking { store.get("tamper-attempts") }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("audit-outbox-column-attempt-count-mismatch")
        }
    }

    @Test
    fun encryptedPayloadDecodeFailureFailsClosed() {
        runBlocking {
            store.append(record("decode-fail"))
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "UPDATE audit_outbox SET encrypted_payload = decode('deadbeef', 'hex') WHERE outbox_id = ?"
                ).use { stmt ->
                    stmt.setString(1, "decode-fail")
                    stmt.executeUpdate()
                }
            }
            assertThatThrownBy {
                runBlocking { store.get("decode-fail") }
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("audit-outbox-payload-decryption-failed")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Restart safety
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun restartSafetyNewStoreInstanceCanReadPreviouslyWrittenRecords() {
        runBlocking {
            store.append(record("restart-test"))
            val newStore = JdbcSovereignOpsAuditOutboxStore(
                dataSource = dataSource,
                payloadCodec = testCodec,
            )
            val result = newStore.get("restart-test")
            assertThat(result).isNotNull
            assertThat(result!!.outboxId).isEqualTo("restart-test")
        }
    }

    @Test
    fun restartSafetyNewStoreInstanceCanTransitionRecord() {
        runBlocking {
            store.append(record("restart-transition"))
            val newStore = JdbcSovereignOpsAuditOutboxStore(
                dataSource = dataSource,
                payloadCodec = testCodec,
            )
            val ready = newStore.markReadyForDispatch(
                "restart-transition",
                SovereignOpsAuditOutboxStatus.PREPARED,
            )
            assertThat(ready.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Version invariant
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun concurrentMutationWithStaleVersionFails() {
        runBlocking {
            store.append(record("version-check"))
            store.markReadyForDispatch("version-check", SovereignOpsAuditOutboxStatus.PREPARED)
            store.claimPending("worker-1", 10, BASE_NOW)
            store.markEmitted("version-check", SovereignOpsAuditOutboxStatus.EMITTING, BASE_NOW)
            assertThatThrownBy {
                runBlocking {
                    store.markFailed(
                        "version-check",
                        SovereignOpsAuditOutboxStatus.EMITTING,
                        errorCode = "TOO_LATE",
                        retryable = false,
                    )
                }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("tramai-sovereign-ops-outbox-status-mismatch")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Timestamp round-trip
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun timestampsRoundTripWithoutBeingRegenerated() {
        runBlocking {
            val createdAt = Instant.parse("2026-01-15T10:30:00Z")
            val record = SovereignOpsAuditOutboxRecord(
                outboxId = "tstamp-test",
                aggregateIdDigest = AGGREGATE_DIGEST,
                eventKey = "tstamp-key",
                actor = "operator-1",
                workflowRunId = "wf-1",
                correlationId = "corr-1",
                approvalStatus = "DENIED",
                approvalVersion = 3L,
                reasonDigest = REASON_DIGEST,
                reasonLength = 10,
                createdAt = createdAt,
            )
            store.append(record)
            val result = store.get("tstamp-test")
            assertThat(result).isNotNull
            assertThat(result!!.createdAt).isEqualTo(createdAt)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Claim-time tamper detection (P1)
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun claimPendingFailsClosedWhenDbStatusTamperedFromPreparedToPending() {
        runBlocking {
            store.append(record("claim-tamper-status"))
            // Tamper DB status from PREPARED to PENDING without updating payload
            tamperColumn("claim-tamper-status", "status", "PENDING")
            assertThatThrownBy {
                runBlocking {
                    store.claimPending("worker-1", 10, BASE_NOW)
                }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("audit-outbox-column-status-mismatch")
        }
    }

    @Test
    fun claimPendingFailsClosedWhenDbEventKeyDiffersFromEncryptedPayload() {
        runBlocking {
            store.append(record("claim-tamper-key"))
            store.markReadyForDispatch("claim-tamper-key", SovereignOpsAuditOutboxStatus.PREPARED)
            tamperColumn("claim-tamper-key", "event_key", "tampered-key")
            assertThatThrownBy {
                runBlocking {
                    store.claimPending("worker-1", 10, BASE_NOW)
                }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("audit-outbox-column-event-key-mismatch")
        }
    }

    @Test
    fun claimPendingFailsClosedWhenDbAttemptCountDiffersFromEncryptedPayload() {
        runBlocking {
            store.append(record("claim-tamper-attempts"))
            store.markReadyForDispatch("claim-tamper-attempts", SovereignOpsAuditOutboxStatus.PREPARED)
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "UPDATE audit_outbox SET attempt_count = 99 WHERE outbox_id = ?"
                ).use { stmt ->
                    stmt.setString(1, "claim-tamper-attempts")
                    stmt.executeUpdate()
                }
            }
            assertThatThrownBy {
                runBlocking {
                    store.claimPending("worker-1", 10, BASE_NOW)
                }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("audit-outbox-column-attempt-count-mismatch")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Lifecycle transition guard tests (P2)
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun markReadyForDispatchCannotMoveFailedPermanentBackToPending() {
        runBlocking {
            store.append(record("fail-perm-guard"))
            store.markReadyForDispatch("fail-perm-guard", SovereignOpsAuditOutboxStatus.PREPARED)
            store.claimPending("worker-1", 10, BASE_NOW)
            store.markFailed(
                "fail-perm-guard",
                SovereignOpsAuditOutboxStatus.EMITTING,
                errorCode = "FATAL",
                retryable = false,
            )
            assertThatThrownBy {
                runBlocking {
                    store.markReadyForDispatch("fail-perm-guard", SovereignOpsAuditOutboxStatus.PREPARED)
                }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("tramai-sovereign-ops-outbox-status-mismatch")
        }
    }

    @Test
    fun markEmittedRejectsExpectedStatusPending() {
        runBlocking {
            store.append(record("emit-guard"))
            store.markReadyForDispatch("emit-guard", SovereignOpsAuditOutboxStatus.PREPARED)
            assertThatThrownBy {
                runBlocking {
                    store.markEmitted("emit-guard", SovereignOpsAuditOutboxStatus.PENDING, BASE_NOW)
                }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("tramai-sovereign-ops-outbox-status-mismatch")
        }
    }

    @Test
    fun markFailedRejectsPreparedWithRetryableTrue() {
        runBlocking {
            store.append(record("fail-retry-guard"))
            assertThatThrownBy {
                runBlocking {
                    store.markFailed(
                        "fail-retry-guard",
                        SovereignOpsAuditOutboxStatus.PREPARED,
                        errorCode = "ERR",
                        retryable = true,
                    )
                }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("tramai-sovereign-ops-outbox-status-mismatch")
        }
    }

    @Test
    fun markFailedRejectsEmittedWithRetryableTrue() {
        runBlocking {
            store.append(record("fail-emitted-guard"))
            assertThatThrownBy {
                runBlocking {
                    store.markFailed(
                        "fail-emitted-guard",
                        SovereignOpsAuditOutboxStatus.EMITTED,
                        errorCode = "ERR",
                        retryable = true,
                    )
                }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("tramai-sovereign-ops-outbox-status-mismatch")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Claim-time lastErrorCode / last_failure_type consistency (P1/P2)
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun claimPendingClearsLastErrorCodeInPayload() {
        runBlocking {
            store.append(record("claim-clear-error"))
            store.markReadyForDispatch("claim-clear-error", SovereignOpsAuditOutboxStatus.PREPARED)
            store.claimPending("worker-1", 10, BASE_NOW)
            store.markFailed(
                "claim-clear-error",
                SovereignOpsAuditOutboxStatus.EMITTING,
                errorCode = "TIMEOUT",
                retryable = true,
            )
            val afterLease = BASE_NOW.plus(Duration.ofMinutes(6))
            val claimed = store.claimPending("worker-2", 10, afterLease)
            assertThat(claimed).hasSize(1)
            assertThat(claimed[0].lastErrorCode).isNull()
            // Verify DB column matches
            val stored = store.get("claim-clear-error")
            assertThat(stored!!.lastErrorCode).isNull()
        }
    }

    @Test
    fun tamperedLastFailureTypeDetectedOnRead() {
        runBlocking {
            store.append(record("tamper-last-fail"))
            store.markReadyForDispatch("tamper-last-fail", SovereignOpsAuditOutboxStatus.PREPARED)
            store.claimPending("worker-1", 10, BASE_NOW)
            store.markFailed(
                "tamper-last-fail",
                SovereignOpsAuditOutboxStatus.EMITTING,
                errorCode = "NET_ERR",
                retryable = true,
            )
            // DB now has last_failure_type = 'NET_ERR', payload has lastErrorCode = 'NET_ERR'
            // Now tamper DB column to something different
            tamperColumn("tamper-last-fail", "last_failure_type", "TAMPERED")
            assertThatThrownBy {
                runBlocking { store.get("tamper-last-fail") }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("audit-outbox-column-last-failure-type-mismatch")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Timestamp append consistency (P2)
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun appendUsesRecordCreatedAtInDbColumn() {
        runBlocking {
            val createdAt = Instant.parse("2026-03-15T08:00:00Z")
            val record = SovereignOpsAuditOutboxRecord(
                outboxId = "db-created-at",
                aggregateIdDigest = AGGREGATE_DIGEST,
                eventKey = "db-created-at-key",
                actor = "operator-1",
                workflowRunId = null,
                correlationId = null,
                approvalStatus = "DENIED",
                approvalVersion = 1L,
                reasonDigest = REASON_DIGEST,
                reasonLength = 5,
                createdAt = createdAt,
            )
            store.append(record)
            // Read the DB column directly to verify it matches the payload
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "SELECT created_at FROM audit_outbox WHERE outbox_id = 'db-created-at'"
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        val dbCreatedAt = rs.getTimestamp("created_at").toInstant()
                        val diff = Duration.between(dbCreatedAt, createdAt).abs()
                        assertThat(diff.seconds).isLessThanOrEqualTo(1)
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Nullable timestamp symmetry (P2)
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun tamperedClaimedAtToNonNullDetectedOnPreparedRecord() {
        runBlocking {
            store.append(record("null-claimed-at"))
            // DB claimed_at and next_attempt_at are NULL (PREPARED record)
            // Tamper both to non-null — payload still has null
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "UPDATE audit_outbox SET claimed_at = ?, next_attempt_at = ? WHERE outbox_id = ?"
                ).use { stmt ->
                    stmt.setTimestamp(1, Timestamp.from(BASE_NOW))
                    stmt.setTimestamp(2, Timestamp.from(BASE_NOW))
                    stmt.setString(3, "null-claimed-at")
                    stmt.executeUpdate()
                }
            }
            assertThatThrownBy {
                runBlocking { store.get("null-claimed-at") }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("claimed_at-mismatch")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════

    private fun record(
        outboxId: String,
        status: SovereignOpsAuditOutboxStatus = SovereignOpsAuditOutboxStatus.PREPARED,
        eventKey: String = "event-$outboxId",
    ): SovereignOpsAuditOutboxRecord = SovereignOpsAuditOutboxRecord(
        outboxId = outboxId,
        aggregateType = "approval",
        aggregateIdDigest = AGGREGATE_DIGEST,
        operation = "denyApproval",
        eventKey = eventKey,
        actor = "operator-1",
        workflowRunId = "workflow-$outboxId",
        correlationId = "correlation-$outboxId",
        approvalStatus = "DENIED",
        approvalVersion = 7L,
        reasonDigest = REASON_DIGEST,
        reasonLength = 42,
        createdAt = BASE_NOW,
        status = status,
    )

    private fun tamperColumn(outboxId: String, column: String, value: String) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE audit_outbox SET $column = ? WHERE outbox_id = ?"
            ).use { stmt ->
                stmt.setString(1, value)
                stmt.setString(2, outboxId)
                stmt.executeUpdate()
            }
        }
    }

    private fun truncateTables() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("TRUNCATE TABLE audit_outbox CASCADE")
            }
        }
    }

    private fun runMigrations() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val v1 = javaClass.classLoader
                    .getResourceAsStream("tramai/persistence/jdbc/postgres/V1__sovereign_persistence.sql")
                    ?.bufferedReader()?.readText()
                    ?: error("V1 migration not found")
                stmt.execute(v1)
                val v4 = javaClass.classLoader
                    .getResourceAsStream("tramai/persistence/jdbc/postgres/V4__audit_outbox_hardening.sql")
                    ?.bufferedReader()?.readText()
                    ?: error("V4 migration not found")
                runCatching { stmt.execute(v4) }
            }
        }
    }

    // ── #267/#271 transaction-cleanup precedence regressions ────────

    @Test
    fun `append maps commit SQLException to the fixed database-failure code`() {
        runBlocking {
            val commitFailure = java.sql.SQLException("secret database diagnostic")
            val failing = JdbcSovereignOpsAuditOutboxStore(
                dataSource = dataSourceWithFailures(
                    commitFailure = commitFailure,
                ),
                payloadCodec = testCodec,
            )

            val thrown = runCatching {
                failing.append(record("cancel-commit-sql"))
            }.exceptionOrNull()

            assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
            assertThat(thrown?.message).isEqualTo("tramai-sovereign-ops-outbox-database-failure")
            assertSame(commitFailure, thrown?.cause)
        }
    }

    @Test
    fun `append rethrows CancellationException unchanged when rollback also fails`() {
        runBlocking {
            val cancellation = CancellationException("cancelled by test")
            val rollbackFailure = java.sql.SQLException("rollback failed")
            val failing = JdbcSovereignOpsAuditOutboxStore(
                dataSource = dataSourceWithFailures(
                    commitFailure = cancellation,
                    rollbackFailure = rollbackFailure,
                ),
                payloadCodec = testCodec,
            )

            val thrown = runCatching {
                failing.append(record("cancel-rollback"))
            }.exceptionOrNull()

            assertSame(cancellation, thrown)
            assertEquals(listOf(rollbackFailure), thrown?.suppressed?.toList())
        }
    }

    @Test
    fun `append preserves primary CancellationException when autoCommit restore fails`() {
        runBlocking {
            val cancellation = CancellationException("cancelled by test")
            val restoreFailure = java.sql.SQLException("restore failed")
            val failing = JdbcSovereignOpsAuditOutboxStore(
                dataSource = dataSourceWithFailures(
                    commitFailure = cancellation,
                    restoreFailure = restoreFailure,
                ),
                payloadCodec = testCodec,
            )

            val thrown = runCatching {
                failing.append(record("cancel-restore"))
            }.exceptionOrNull()

            assertSame(cancellation, thrown)
            assertEquals(listOf(restoreFailure), thrown?.suppressed?.toList())
        }
    }

    /**
     * A DataSource whose connections delegate to the real database but fail
     * deterministically: [commitFailure] is thrown from `commit()`, and, when
     * [restoreFailure] is non-null, from the second `setAutoCommit` call (the
     * finally-block restoration). Each `getConnection` captures ONE delegate,
     * so the production code sees a single JDBC transaction rather than a
     * fresh connection per method call. Same shape as the #267/#271
     * failure-injection helpers.
     */
    private fun dataSourceWithFailures(
        commitFailure: Exception = CancellationException("cancelled by test"),
        rollbackFailure: Exception? = null,
        restoreFailure: Exception? = null,
    ): DataSource {
        val real = dataSource
        return java.lang.reflect.Proxy.newProxyInstance(
            DataSource::class.java.classLoader,
            arrayOf(DataSource::class.java),
        ) { _, method, args ->
            if (method.name == "getConnection" && args.isNullOrEmpty()) {
                val delegate = real.connection
                var autoCommitCalls = 0
                java.lang.reflect.Proxy.newProxyInstance(
                    java.sql.Connection::class.java.classLoader,
                    arrayOf(java.sql.Connection::class.java),
                ) { _, connMethod, connArgs ->
                    if (connMethod.name == "commit") throw commitFailure
                    if (connMethod.name == "rollback" && rollbackFailure != null) throw rollbackFailure
                    if (connMethod.name == "setAutoCommit") {
                        autoCommitCalls++
                        if (restoreFailure != null && autoCommitCalls > 1) throw restoreFailure
                    }
                    connMethod.invoke(delegate, *(connArgs ?: emptyArray()))
                }
            } else {
                method.invoke(real, *(args ?: emptyArray()))
            }
        } as DataSource
    }
}
