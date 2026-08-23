package dev.tramai.persistence.jdbc

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.ApprovalContinuationConflictException
import dev.tramai.core.exception.ApprovalContinuationNotClaimableException
import dev.tramai.core.exception.ApprovalContinuationNotCompletableException
import dev.tramai.core.exception.ApprovalContinuationNotFoundException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcApprovalContinuationStoreTest {

    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"

        private val postgres = PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("continuation_test")
            .withUsername("test")
            .withPassword("test")

        private fun createDataSource(): DataSource = PGSimpleDataSource().apply {
            setUrl(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
    }

    private val fixedClock: Clock = Clock.fixed(
        Instant.parse("2026-06-21T12:00:00Z"),
        ZoneId.of("UTC"),
    )

    private val testAesKey = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private val testCodec = object : JdbcContinuationArgumentsCodec {
        private val ALGORITHM = "AES/GCM/NoPadding"
        private val TAG_LENGTH = 128

        override fun encode(plaintext: ByteArray): JdbcEncryptedContinuationArguments {
            val cipher = Cipher.getInstance(ALGORITHM)
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val keySpec = SecretKeySpec(testAesKey, "AES")
            val spec = GCMParameterSpec(TAG_LENGTH, nonce)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)
            val ciphertext = cipher.doFinal(plaintext)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(plaintext)
                .joinToString("") { "%02x".format(it) }
            return JdbcEncryptedContinuationArguments(
                ciphertext = ciphertext,
                keyId = "test-key-1",
                algorithm = ALGORITHM,
                nonce = nonce,
                payloadDigest = "sha256:$digest",
            )
        }

        override fun decode(envelope: JdbcEncryptedContinuationArguments): ByteArray {
            val cipher = Cipher.getInstance(ALGORITHM)
            val keySpec = SecretKeySpec(testAesKey, "AES")
            val spec = GCMParameterSpec(TAG_LENGTH, envelope.nonce)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
            return cipher.doFinal(envelope.ciphertext)
        }
    }

    @BeforeAll
    fun startPostgres() {
        postgres.start()
        runMigrations()
    }

    @AfterAll
    fun stopPostgres() {
        postgres.stop()
    }

    private fun runMigrations() {
        val v1Sql = javaClass.getResourceAsStream(
            "/tramai/persistence/jdbc/postgres/V1__sovereign_persistence.sql",
        )?.bufferedReader()?.readText()
            ?: throw IllegalStateException("V1 migration not found")

        val v2Sql = javaClass.getResourceAsStream(
            "/tramai/persistence/jdbc/postgres/V2__approval_continuations.sql",
        )?.bufferedReader()?.readText()
            ?: throw IllegalStateException("V2 migration not found")

        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            c.createStatement().use { stmt -> stmt.execute(v1Sql) }
            c.createStatement().use { stmt -> stmt.execute(v2Sql) }
        }
    }

    @BeforeEach
    fun cleanTable() {
        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("DELETE FROM approval_continuations")
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun store(
        codec: JdbcContinuationArgumentsCodec = testCodec,
        clock: Clock = fixedClock,
    ): JdbcApprovalContinuationStore = JdbcApprovalContinuationStore(
        dataSource = createDataSource(),
        argumentsCodec = codec,
        clock = clock,
    )

    private fun makeDigest(hex: String = "a".repeat(64)): Sha256Digest =
        Sha256Digest.of("sha256:$hex")

    private fun computeArgumentsDigest(arguments: String): Sha256Digest {
        val bytes = MessageDigest.getInstance("SHA-256").digest(arguments.toByteArray(Charsets.UTF_8))
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return Sha256Digest.of("sha256:$hex")
    }

    private fun createContinuation(
        approvalId: String = "cont-test-1",
        arguments: String = "plain-args",
        clock: Clock = this.fixedClock,
    ): Pair<ApprovalContinuation, SensitiveToolArguments> {
        val args = SensitiveToolArguments.of(arguments)
        val digest = computeArgumentsDigest(arguments)
        val creationTime = clock.instant()
        val continuation = ApprovalContinuation(
            approvalId = approvalId,
            workflowRunId = "wf-1",
            correlationId = "corr-1",
            toolCallId = "tc-1",
            toolName = "test-tool",
            argumentsDigest = digest,
            policyVersion = "v1",
            workflowDigest = makeDigest("b".repeat(64)),
            status = ApprovalContinuationStatus.PENDING,
            createdAt = creationTime,
            approvalExpiresAt = creationTime.plusSeconds(300),
            claimedBy = null,
            claimedAt = null,
            completedAt = null,
            version = 0L,
        )
        return continuation to args
    }

    private fun assertRoundTripEquals(
        original: ApprovalContinuation,
        retrieved: ApprovalContinuation,
    ) {
        assertEquals(original.approvalId, retrieved.approvalId)
        assertEquals(original.workflowRunId, retrieved.workflowRunId)
        assertEquals(original.correlationId, retrieved.correlationId)
        assertEquals(original.toolCallId, retrieved.toolCallId)
        assertEquals(original.toolName, retrieved.toolName)
        assertEquals(original.argumentsDigest, retrieved.argumentsDigest)
        assertEquals(original.policyVersion, retrieved.policyVersion)
        assertEquals(original.workflowDigest, retrieved.workflowDigest)
        assertEquals(original.status, retrieved.status)
        assertEquals(original.createdAt, retrieved.createdAt)
        assertEquals(original.approvalExpiresAt, retrieved.approvalExpiresAt)
        assertEquals(original.version, retrieved.version)
    }

    // ═══════════════════════════════════════════════════════════════
    // Create / Get round-trips
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `create and get round-trips continuation`() { runBlocking {
        val s = store()
        val (continuation, args) = createContinuation("roundtrip-1")

        s.create(continuation, args)
        val retrieved = s.get("roundtrip-1")

        assertNotNull(retrieved)
        assertRoundTripEquals(continuation, retrieved)
        assertEquals(ApprovalContinuationStatus.PENDING, retrieved.status)
        assertEquals(0L, retrieved.version)
    }
    }

    @Test
    fun `create rejects duplicate continuation id`() { runBlocking {
        val s = store()
        val (cont, args) = createContinuation("dup-test")

        s.create(cont, args)

        val (cont2, args2) = createContinuation("dup-test")
        assertFailsWith<ApprovalContinuationConflictException> {
            s.create(cont2, args2)
        }
    }
    }

    @Test
    fun `get returns null for non-existent continuation`() { runBlocking {
        val retrieved = store().get("non-existent")
        assertNull(retrieved)
    }
    }

    @Test
    fun `persisted continuation survives new store instance`() { runBlocking {
        val s1 = store()
        s1.create(createContinuation("survive-test").first, createContinuation("survive-test").second)

        val s2 = store()
        val retrieved = s2.get("survive-test")
        assertNotNull(retrieved)
        assertEquals("survive-test", retrieved.approvalId)
        assertEquals(ApprovalContinuationStatus.PENDING, retrieved.status)
    }
    }

    // ═══════════════════════════════════════════════════════════════
    // State-machine invariants
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `PENDING to CLAIMED to COMPLETED full lifecycle`() { runBlocking {
        val s = store()
        s.create(createContinuation("lifecycle-1").first, createContinuation("lifecycle-1").second)

        val claimed = s.claimForExecution("lifecycle-1", 0L, "worker:alice")
        assertEquals(ApprovalContinuationStatus.CLAIMED, claimed.continuation.status)
        assertEquals(1L, claimed.continuation.version)
        assertEquals("worker:alice", claimed.continuation.claimedBy)
        assertNotNull(claimed.continuation.claimedAt)
        assertEquals("plain-args", claimed.arguments.reveal())

        val completed = s.complete("lifecycle-1", 1L, "worker:alice")
        assertEquals(ApprovalContinuationStatus.COMPLETED, completed.status)
        assertEquals(2L, completed.version)
        assertNotNull(completed.completedAt)
    }
    }

    @Test
    fun `PENDING to EXPIRED transition`() { runBlocking {
        val creationTime = fixedClock.instant()
        val (continuation, args) = createContinuation("expire-1")

        val creationClock = Clock.fixed(creationTime, ZoneId.of("UTC"))
        val s1 = store(clock = creationClock)
        s1.create(continuation, args)

        val futureClock = Clock.fixed(creationTime.plusSeconds(600), ZoneId.of("UTC"))
        val s2 = store(clock = futureClock)
        val expired = s2.expire("expire-1", 0L)
        assertEquals(ApprovalContinuationStatus.EXPIRED, expired.status)
        assertEquals(1L, expired.version)
    }
    }

    @Test
    fun `PENDING to CANCELLED transition`() { runBlocking {
        val s = store()
        s.create(createContinuation("cancel-1").first, createContinuation("cancel-1").second)

        val cancelled = s.cancel("cancel-1", 0L)
        assertEquals(ApprovalContinuationStatus.CANCELLED, cancelled.status)
        assertEquals(1L, cancelled.version)
    }
    }

    @Test
    fun `CLAIMED to CANCELLED_UNCERTAIN via force cancel`() { runBlocking {
        val s = store()
        s.create(createContinuation("force-1").first, createContinuation("force-1").second)
        s.claimForExecution("force-1", 0L, "worker:stuck")

        val result = s.forceCancelClaimed(
            approvalId = "force-1",
            expectedVersion = 1L,
            cancelledBy = "admin:alice",
            reasonCode = "stuck.worker",
        )
        assertEquals(ApprovalContinuationStatus.CANCELLED_UNCERTAIN, result.status)
        assertEquals("admin:alice", result.recoveryResolvedBy)
        assertEquals("stuck.worker", result.recoveryReasonCode)
    }
    }

    @Test
    fun `CLAIMED cannot be claimed again`() { runBlocking {
        val s = store()
        s.create(createContinuation("reclaim-1").first, createContinuation("reclaim-1").second)
        s.claimForExecution("reclaim-1", 0L, "worker:eve")

        assertFailsWith<ApprovalContinuationNotClaimableException> {
            s.claimForExecution("reclaim-1", 1L, "worker:mallory")
        }
    }
    }

    @Test
    fun `COMPLETED continuation cannot be claimed again`() { runBlocking {
        val s = store()
        s.create(createContinuation("done-1").first, createContinuation("done-1").second)
        s.claimForExecution("done-1", 0L, "worker:alice")
        s.complete("done-1", 1L, "worker:alice")

        assertFailsWith<ApprovalContinuationNotClaimableException> {
            s.claimForExecution("done-1", 2L, "worker:bob")
        }
    }
    }

    @Test
    fun `CLAIMED cannot transition to EXPIRED`() { runBlocking {
        val s = store()
        s.create(createContinuation("no-lazy-exp").first, createContinuation("no-lazy-exp").second)
        s.claimForExecution("no-lazy-exp", 0L, "worker:eve")

        // Expire should fail because status is CLAIMED, not PENDING
        assertFailsWith<ApprovalContinuationConflictException> {
            s.expire("no-lazy-exp", 1L)
        }
    }
    }

    @Test
    fun `CLAIMED cannot transition to CANCELLED`() { runBlocking {
        val s = store()
        s.create(createContinuation("no-cancel-claimed").first, createContinuation("no-cancel-claimed").second)
        s.claimForExecution("no-cancel-claimed", 0L, "worker:eve")

        // cancel() should fail because status is CLAIMED, not PENDING
        assertFailsWith<ApprovalContinuationConflictException> {
            s.cancel("no-cancel-claimed", 1L)
        }
    }
    }

    // ═══════════════════════════════════════════════════════════════
    // Version invariants — optimistic locking
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `transition with expected version increments version`() { runBlocking {
        val s = store()
        s.create(createContinuation("ver-inc-1").first, createContinuation("ver-inc-1").second)

        val claimed = s.claimForExecution("ver-inc-1", 0L, "worker:alice")
        assertEquals(1L, claimed.continuation.version)

        val completed = s.complete("ver-inc-1", 1L, "worker:alice")
        assertEquals(2L, completed.version)

        val retrieved = s.get("ver-inc-1")
        assertNotNull(retrieved)
        assertEquals(2L, retrieved.version)
    }
    }

    @Test
    fun `transition with stale version fails`() { runBlocking {
        val s = store()
        s.create(createContinuation("stale-ver-1").first, createContinuation("stale-ver-1").second)

        // No production path bumps the version while the row stays PENDING,
        // so simulate a concurrent writer by tampering the row directly.
        // The version guard fires before the status guard for PENDING rows.
        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            c.prepareStatement(
                "UPDATE approval_continuations SET version = 1 WHERE approval_id = 'stale-ver-1'",
            ).use { stmt -> stmt.executeUpdate() }
        }

        // Try claiming with version 0 (stale) instead of 1
        assertFailsWith<ApprovalContinuationConflictException> {
            s.claimForExecution("stale-ver-1", 0L, "worker:bob")
        }
    }
    }

    @Test
    fun `complete with stale version fails`() { runBlocking {
        val s = store()
        s.create(createContinuation("stale-complete").first, createContinuation("stale-complete").second)
        s.claimForExecution("stale-complete", 0L, "worker:alice")

        assertFailsWith<ApprovalContinuationConflictException> {
            s.complete("stale-complete", 0L, "worker:alice")
        }
    }
    }

    // ═══════════════════════════════════════════════════════════════
    // Concurrency invariants
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `concurrent claim has exactly one winner`() { runBlocking {
        val s = store()
        s.create(createContinuation("concurrent-claim").first, createContinuation("concurrent-claim").second)

        coroutineScope {
            val results = listOf(
                async {
                    try {
                        s.claimForExecution("concurrent-claim", 0L, "worker:1")
                        "winner"
                    } catch (_: Exception) {
                        "loser"
                    }
                },
                async {
                    try {
                        s.claimForExecution("concurrent-claim", 0L, "worker:2")
                        "winner"
                    } catch (_: Exception) {
                        "loser"
                    }
                },
                async {
                    try {
                        s.claimForExecution("concurrent-claim", 0L, "worker:3")
                        "winner"
                    } catch (_: Exception) {
                        "loser"
                    }
                },
            )

            val outcomes = results.map { it.await() }
            val winners = outcomes.count { it == "winner" }
            assertEquals(1, winners, "Exactly one concurrent claim must succeed")
        }
    }
    }

    @Test
    fun `same continuation cannot be completed twice concurrently`() { runBlocking {
        val s = store()
        s.create(createContinuation("double-complete").first, createContinuation("double-complete").second)
        s.claimForExecution("double-complete", 0L, "worker:alice")

        coroutineScope {
            val results = listOf(
                async {
                    try {
                        s.complete("double-complete", 1L, "worker:alice")
                        "winner"
                    } catch (_: Exception) {
                        "loser"
                    }
                },
                async {
                    try {
                        s.complete("double-complete", 1L, "worker:alice")
                        "winner"
                    } catch (_: Exception) {
                        "loser"
                    }
                },
            )

            val outcomes = results.map { it.await() }
            val winners = outcomes.count { it == "winner" }
            assertEquals(1, winners, "Exactly one concurrent complete must succeed")
        }
    }
    }

    // ═══════════════════════════════════════════════════════════════
    // Restart invariants (proves persistence, not in-memory state)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `create store A, write, create store B, read`() { runBlocking {
        val sA = store()
        val (cont, args) = createContinuation("store-ab-1")
        sA.create(cont, args)

        val sB = store()
        val retrieved = sB.get("store-ab-1")
        assertNotNull(retrieved)
        assertEquals(ApprovalContinuationStatus.PENDING, retrieved.status)
        assertEquals(0L, retrieved.version)
    }
    }

    @Test
    fun `create store A, claim with store B, complete with store C`() { runBlocking {
        val sA = store()
        sA.create(createContinuation("abc-lifecycle").first, createContinuation("abc-lifecycle").second)

        val sB = store()
        val claimed = sB.claimForExecution("abc-lifecycle", 0L, "worker:alice")
        assertEquals("plain-args", claimed.arguments.reveal())

        val sC = store()
        val completed = sC.complete("abc-lifecycle", 1L, "worker:alice")
        assertEquals(ApprovalContinuationStatus.COMPLETED, completed.status)
        assertEquals(2L, completed.version)
    }
    }

    @Test
    fun `claim arguments survive decryption across store instances`() { runBlocking {
        val s1 = store()
        val (cont, args) = createContinuation("enc-roundtrip", arguments = "complex-arg-value")
        s1.create(cont, args)

        val s2 = store()
        val claimed = s2.claimForExecution("enc-roundtrip", 0L, "worker:alice")
        assertEquals("complex-arg-value", claimed.arguments.reveal())
    }
    }

    // ═══════════════════════════════════════════════════════════════
    // Stale CLAIMED search and sweep
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `findStaleClaimed returns claimed continuations before timestamp`() { runBlocking {
        val s = store()
        s.create(createContinuation("stale-search-1").first, createContinuation("stale-search-1").second)
        s.claimForExecution("stale-search-1", 0L, "worker:slow")

        val stale = s.findStaleClaimed(
            claimedBefore = fixedClock.instant().plusSeconds(1),
            limit = 10,
        )
        assertEquals(1, stale.size)
        assertEquals("stale-search-1", stale.first().approvalId)
    }
    }

    @Test
    fun `sweepExpired transitions PENDING continuations past expiry`() { runBlocking {
        val creationTime = fixedClock.instant()
        val s1 = store(clock = Clock.fixed(creationTime, ZoneId.of("UTC")))
        s1.create(
            createContinuation("sweep-1", clock = Clock.fixed(creationTime, ZoneId.of("UTC"))).first,
            createContinuation("sweep-1", clock = Clock.fixed(creationTime, ZoneId.of("UTC"))).second,
        )
        s1.create(
            createContinuation("sweep-2", clock = Clock.fixed(creationTime, ZoneId.of("UTC"))).first,
            createContinuation("sweep-2", clock = Clock.fixed(creationTime, ZoneId.of("UTC"))).second,
        )

        // Future clock — both should be past expiry
        val futureClock = Clock.fixed(creationTime.plusSeconds(600), ZoneId.of("UTC"))
        val s2 = store(clock = futureClock)

        val swept = s2.sweepExpired()
        assertEquals(2, swept)

        val r1 = s2.get("sweep-1")
        assertNotNull(r1)
        assertEquals(ApprovalContinuationStatus.EXPIRED, r1.status)

        val r2 = s2.get("sweep-2")
        assertNotNull(r2)
        assertEquals(ApprovalContinuationStatus.EXPIRED, r2.status)
    }
    }

    // ═══════════════════════════════════════════════════════════════
    // Encrypted arguments tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `encrypted_arguments is non-null after create`() { runBlocking {
        val s = store()
        s.create(createContinuation("enc-nonnull").first, createContinuation("enc-nonnull").second)

        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            c.prepareStatement(
                "SELECT encrypted_arguments FROM approval_continuations WHERE approval_id = ?",
            ).use { stmt ->
                stmt.setString(1, "enc-nonnull")
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    val bytes = rs.getBytes("encrypted_arguments")
                    assertNotNull(bytes)
                    assertTrue(bytes.isNotEmpty())
                }
            }
        }
    }
    }

    @Test
    fun `encryption metadata fields are non-null after create`() { runBlocking {
        val s = store()
        s.create(createContinuation("enc-meta").first, createContinuation("enc-meta").second)

        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            c.prepareStatement(
                """SELECT encryption_key_id, encryption_algorithm, encryption_nonce, payload_digest
                   FROM approval_continuations WHERE approval_id = ?""",
            ).use { stmt ->
                stmt.setString(1, "enc-meta")
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertNotNull(rs.getString("encryption_key_id"))
                    assertNotNull(rs.getString("encryption_algorithm"))
                    assertNotNull(rs.getBytes("encryption_nonce"))
                    assertNotNull(rs.getString("payload_digest"))
                }
            }
        }
    }
    }

    @Test
    fun `raw arguments are not visible in the database`() { runBlocking {
        val s = store()
        s.create(
            createContinuation("enc-secret", arguments = "super-sensitive-value").first,
            createContinuation("enc-secret", arguments = "super-sensitive-value").second,
        )

        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            c.prepareStatement(
                "SELECT encrypted_arguments FROM approval_continuations WHERE approval_id = ?",
            ).use { stmt ->
                stmt.setString(1, "enc-secret")
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    val ciphertext = rs.getBytes("encrypted_arguments")
                    val text = ciphertext.toString(Charsets.UTF_8)
                    // Verify ciphertext does not contain the plaintext
                    assertTrue(text.contains("super-sensitive-value").not(),
                        "Raw argument content must not be visible in the database")
                }
            }
        }
    }
    }

    @Test
    fun `encrypted_arguments are null after claim`() { runBlocking {
        val s = store()
        s.create(createContinuation("enc-null-after-claim").first,
            createContinuation("enc-null-after-claim").second)
        s.claimForExecution("enc-null-after-claim", 0L, "worker:alice")

        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            c.prepareStatement(
                "SELECT encrypted_arguments FROM approval_continuations WHERE approval_id = ?",
            ).use { stmt ->
                stmt.setString(1, "enc-null-after-claim")
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertNull(rs.getBytes("encrypted_arguments"),
                        "encrypted_arguments must be null after claim")
                }
            }
        }
    }
    }

    // ═══════════════════════════════════════════════════════════════
    // Timestamp round-trip invariants
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `createdAt round-trips when earlier than store clock`() { runBlocking {
        val now = fixedClock.instant()
        val past = now.minusSeconds(30)
        val args = SensitiveToolArguments.of("past-args")
        val digest = computeArgumentsDigest("past-args")
        val continuation = ApprovalContinuation(
            approvalId = "past-time-test",
            workflowRunId = "wf-1",
            correlationId = "corr-1",
            toolCallId = "tc-1",
            toolName = "test-tool",
            argumentsDigest = digest,
            policyVersion = "v1",
            workflowDigest = makeDigest("b".repeat(64)),
            status = ApprovalContinuationStatus.PENDING,
            createdAt = past,
            approvalExpiresAt = now.plus(Duration.ofMinutes(5)),
            claimedBy = null,
            claimedAt = null,
            completedAt = null,
            version = 0L,
        )

        store().create(continuation, args)
        val retrieved = store().get("past-time-test")
        assertNotNull(retrieved)
        assertEquals(past, retrieved.createdAt)
        assertEquals(now.plus(Duration.ofMinutes(5)), retrieved.approvalExpiresAt)
    }
    }

    // ═══════════════════════════════════════════════════════════════
    // Exception mapping
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `SQL unique violation maps to domain conflict`() { runBlocking {
        val s = store()
        s.create(createContinuation("unique-violation").first,
            createContinuation("unique-violation").second)

        assertFailsWith<ApprovalContinuationConflictException> {
            s.create(createContinuation("unique-violation").first,
                createContinuation("unique-violation").second)
        }
    }
    }

    @Test
    fun `missing continuation returns null from get`() { runBlocking {
        assertNull(store().get("does-not-exist"))
    }
    }

    @Test
    fun `claim on missing continuation throws not found`() { runBlocking {
        assertFailsWith<ApprovalContinuationNotFoundException> {
            store().claimForExecution("does-not-exist", 0L, "worker:test")
        }
    }
    }

    @Test
    fun `complete on missing continuation throws not found`() { runBlocking {
        assertFailsWith<ApprovalContinuationNotFoundException> {
            store().complete("does-not-exist", 0L, "worker:test")
        }
    }
    }

    @Test
    fun `expire on missing continuation throws not found`() { runBlocking {
        assertFailsWith<ApprovalContinuationNotFoundException> {
            store().expire("does-not-exist", 0L)
        }
    }
    }

    @Test
    fun `cancel on missing continuation throws not found`() { runBlocking {
        assertFailsWith<ApprovalContinuationNotFoundException> {
            store().cancel("does-not-exist", 0L)
        }
    }
    }

    @Test
    fun `forceCancelClaimed on missing continuation throws not found`() { runBlocking {
        assertFailsWith<ApprovalContinuationNotFoundException> {
            store().forceCancelClaimed("does-not-exist", 0L, "admin:test", "test.reason")
        }
    }
    }

    // ═══════════════════════════════════════════════════════════════
    // Lazy expiry on get()
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `get auto-expires PENDING continuation past expiry`() { runBlocking {
        val creationTime = fixedClock.instant()
        val s1 = store(clock = Clock.fixed(creationTime, ZoneId.of("UTC")))
        s1.create(createContinuation("lazy-expire").first, createContinuation("lazy-expire").second)

        // Read with future clock — should lazy-expire
        val futureClock = Clock.fixed(creationTime.plusSeconds(600), ZoneId.of("UTC"))
        val s2 = store(clock = futureClock)
        val retrieved = s2.get("lazy-expire")
        assertNotNull(retrieved)
        assertEquals(ApprovalContinuationStatus.EXPIRED, retrieved.status)
        assertEquals(1L, retrieved.version)
    }
    }

    @Test
    fun `concurrent claim after lazy expiry fails`() { runBlocking {
        val creationTime = fixedClock.instant()
        val s1 = store(clock = Clock.fixed(creationTime, ZoneId.of("UTC")))
        s1.create(createContinuation("lazy-claim-fails").first,
            createContinuation("lazy-claim-fails").second)

        val futureClock = Clock.fixed(creationTime.plusSeconds(600), ZoneId.of("UTC"))
        val s2 = store(clock = futureClock)

        assertFailsWith<ApprovalContinuationNotClaimableException> {
            s2.claimForExecution("lazy-claim-fails", 0L, "worker:late")
        }
    }
    }

    // ═══════════════════════════════════════════════════════════════
    // Version overflow safety
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `sweep does not transition non-PENDING continuations`() { runBlocking {
        val s = store()
        s.create(createContinuation("sweep-non-pending").first,
            createContinuation("sweep-non-pending").second)
        s.claimForExecution("sweep-non-pending", 0L, "worker:eve")
        s.complete("sweep-non-pending", 1L, "worker:eve")

        val swept = s.sweepExpired()
        assertEquals(0, swept, "COMPLETED continuations must not be swept")
    }
    }

    @Test
    fun `findStaleClaimed ignores non-CLAIMED statuses`() { runBlocking {
        val s = store()
        // Create a PENDING continuation (not stale)
        s.create(createContinuation("not-stale").first, createContinuation("not-stale").second)

        val stale = s.findStaleClaimed(
            claimedBefore = fixedClock.instant().plusSeconds(1),
            limit = 10,
        )
        assertTrue(stale.isEmpty(), "PENDING continuation must not appear in stale results")
    }
    }

    @Test
    fun `expire before expiry time fails`() { runBlocking {
        val s = store()
        s.create(createContinuation("early-expire").first, createContinuation("early-expire").second)

        assertFailsWith<ApprovalContinuationConflictException> {
            s.expire("early-expire", 0L)
        }
    }
    }

    // ═══════════════════════════════════════════════════════════════
    // P1 regression — decrypt-before-CAS atomicity
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `claim preserves encrypted arguments when decode fails`() { runBlocking {
        // Codec that encodes successfully but fails on decode
        val brokenCodec = object : JdbcContinuationArgumentsCodec {
            override fun encode(plaintext: ByteArray): JdbcEncryptedContinuationArguments {
                return testCodec.encode(plaintext)
            }

            override fun decode(envelope: JdbcEncryptedContinuationArguments): ByteArray {
                throw IllegalStateException("simulated-decode-failure")
            }
        }
        val s = store(codec = brokenCodec)
        s.create(createContinuation("decode-fail").first, createContinuation("decode-fail").second)

        // Claim should throw because decode fails
        assertFailsWith<IllegalStateException> {
            s.claimForExecution("decode-fail", 0L, "worker:alice")
        }

        // Row must still be PENDING with encrypted arguments intact
        val retrieved = s.get("decode-fail")
        assertNotNull(retrieved)
        assertEquals(ApprovalContinuationStatus.PENDING, retrieved.status)
        assertEquals(0L, retrieved.version)

        // encrypted_arguments should still be non-null (verified via raw SQL)
        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            c.prepareStatement(
                "SELECT encrypted_arguments FROM approval_continuations WHERE approval_id = ?",
            ).use { stmt ->
                stmt.setString(1, "decode-fail")
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertNotNull(rs.getBytes("encrypted_arguments"),
                        "encrypted_arguments must survive decode failure")
                }
            }
        }
    }
    }

    // ═══════════════════════════════════════════════════════════════
    // Claim CAS-loss error precedence (deterministic interleaving)
    // ═══════════════════════════════════════════════════════════════

    /** Codec that blocks inside decode() until released — a deterministic gate on the claim's CAS boundary. */
    private class GatedCodec(
        private val delegate: JdbcContinuationArgumentsCodec,
    ) : JdbcContinuationArgumentsCodec {
        val decodeStarted = CompletableDeferred<Unit>()
        val releaseDecode = CompletableDeferred<Unit>()

        override fun encode(plaintext: ByteArray): JdbcEncryptedContinuationArguments = delegate.encode(plaintext)

        override fun decode(envelope: JdbcEncryptedContinuationArguments): ByteArray {
            decodeStarted.complete(Unit)
            runBlocking { releaseDecode.await() }
            return delegate.decode(envelope)
        }
    }

    @Test
    fun `claim that loses its CAS to a cancel reports Conflict not NotClaimable`() { runBlocking {
        val id = "cas-loss-claim"
        val gated = GatedCodec(testCodec)
        val s = store(codec = gated)
        val (continuation, args) = createContinuation(approvalId = id)
        s.create(continuation, args)

        // The claim reads PENDING v0, validates, then blocks inside
        // argument decryption — immediately before its CAS update.
        val claim = async(Dispatchers.Default) {
            runCatching { s.claimForExecution(id, 0L, "worker:alice") }
        }
        gated.decodeStarted.await()

        // Cancel wins while the claim is in flight: CANCELLED v1.
        s.cancel(id, 0L)

        // Release the claim: its CAS (WHERE version=0 AND status='PENDING')
        // loses. It must report Conflict (stale version), matching the
        // in-memory and file stores' version-first precedence — not
        // NotClaimable.
        gated.releaseDecode.complete(Unit)
        val outcome = claim.await()

        assertTrue(
            outcome.exceptionOrNull() is ApprovalContinuationConflictException,
            "claim losing to cancel must throw Conflict, was: ${outcome.exceptionOrNull()}",
        )
        val persisted = s.get(id)
        assertNotNull(persisted)
        assertEquals(ApprovalContinuationStatus.CANCELLED, persisted.status)
        assertEquals(1L, persisted.version)
    } }

    // ═══════════════════════════════════════════════════════════════
    // P2 regression — lazy expiry CAS race
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `get re-reads actual state when lazy expiry CAS loses race`() { runBlocking {
        val creationTime = fixedClock.instant()
        val s1 = store(clock = Clock.fixed(creationTime, ZoneId.of("UTC")))
        s1.create(createContinuation("lazy-race").first, createContinuation("lazy-race").second)

        // Another connection claims the row (bypassing the expiration path)
        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            c.createStatement().use { stmt ->
                stmt.execute(
                    """UPDATE approval_continuations
                       SET status = 'CLAIMED', version = 1,
                           claimed_by = 'worker:racer', claimed_at = NOW()
                       WHERE approval_id = 'lazy-race'"""
                )
            }
        }

        // Now read with a future clock: lazy expiry sees PENDING row (stale read),
        // tries CAS, loses because row is now CLAIMED.
        val futureClock = Clock.fixed(creationTime.plusSeconds(600), ZoneId.of("UTC"))
        val s2 = store(clock = futureClock)
        val retrieved = s2.get("lazy-race")

        // Must return the actual persisted state (CLAIMED), not synthetic EXPIRED
        assertNotNull(retrieved)
        assertEquals(ApprovalContinuationStatus.CLAIMED, retrieved.status,
            "get() must return actual persisted state, not synthetic EXPIRED")
        assertEquals(1L, retrieved.version)
    }
    }

    // ═══════════════════════════════════════════════════════════════
    // P2 regression — V2 schema CHECK constraints
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `schema rejects invalid status`() { runBlocking {
        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        assertFailsWith<org.postgresql.util.PSQLException> {
            conn.prepareStatement(
                """INSERT INTO approval_continuations
                   (approval_id, status, version, created_at, approval_expires_at, arguments_digest)
                   VALUES ('bad-status', 'BOGUS', 0, NOW(), NOW() + INTERVAL '5 minutes', 'sha256:${"a".repeat(64)}')"""
            ).use { stmt ->
                stmt.executeUpdate()
            }
        }
    }
    }

    @Test
    fun `schema rejects approval_expires_at before created_at`() { runBlocking {
        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        assertFailsWith<org.postgresql.util.PSQLException> {
            conn.prepareStatement(
                """INSERT INTO approval_continuations
                   (approval_id, status, version, created_at, approval_expires_at, arguments_digest)
                   VALUES ('bad-expiry', 'PENDING', 0, NOW(), NOW() - INTERVAL '5 minutes', 'sha256:${"a".repeat(64)}')"""
            ).use { stmt ->
                stmt.executeUpdate()
            }
        }
    }
    }
}
