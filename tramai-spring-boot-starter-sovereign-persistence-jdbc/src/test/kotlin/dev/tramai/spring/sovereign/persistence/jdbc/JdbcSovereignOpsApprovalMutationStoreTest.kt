package dev.tramai.spring.sovereign.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.exception.IllegalApprovalTransitionException
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcSovereignOpsApprovalMutationStoreTest {

    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"
        private val postgres = PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("sovereign_ops_mutation_test")
            .withUsername("test")
            .withPassword("test")

        private fun createDataSource(): DataSource = PGSimpleDataSource().apply {
            setUrl(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }

        private val BASE_NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }

    private val testAesKey = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private lateinit var dataSource: DataSource
    private lateinit var codec: JdbcOpsAuditOutboxPayloadCodec
    private lateinit var store: JdbcSovereignOpsApprovalMutationStore

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
        codec = testCodec()
        store = JdbcSovereignOpsApprovalMutationStore(
            dataSource = dataSource,
            payloadCodec = codec,
            clock = Clock.fixed(BASE_NOW.plusSeconds(30), ZoneOffset.UTC),
        )
    }

    // ── Happy path ───────────────────────────────────────────────────

    @Test
    fun `deny approval commits approval denial and pending outbox record atomically`() { runBlocking {
        val approvalId = "approval-a"
        val expiresAt = BASE_NOW.plusSeconds(600) // far in the future
        insertApproval(approvalId, expiresAt = expiresAt)
        val auditIntent = auditIntent(approvalId, "test-key-a")

        val result = store.denyApprovalWithAuditIntent(
            approvalId = approvalId,
            expectedVersion = 1,
            actor = "admin",
            reason = "reason",
            auditIntent = auditIntent,
        )

        assertThat(result.approval.status).isEqualTo(ApprovalStatus.DENIED)
        assertThat(result.approval.version).isEqualTo(2)
        assertThat(result.auditOutboxRecord.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
        assertThat(result.auditOutboxRecord.approvalStatus).isEqualTo("DENIED")
        assertThat(result.auditOutboxRecord.approvalVersion).isEqualTo(2)

        assertThat(selectApprovalStatus(approvalId)).isEqualTo("DENIED")
        assertThat(selectApprovalVersion(approvalId)).isEqualTo(2L)
        assertThat(selectApprovalDecisionType(approvalId)).isEqualTo("DENIED")
        assertThat(selectApprovalDecisionActorHash(approvalId)).isEqualTo(sha256Hex("admin"))
        assertThat(selectOutboxStatus(auditIntent.outboxId)).isEqualTo("PENDING")
        assertThat(countOutboxRowsForEventKey(auditIntent.eventKey)).isEqualTo(1)
    }
    }

    // ── Rollback guards ──────────────────────────────────────────────

    @Test
    fun `outbox conflict prevents approval denial`() { runBlocking {
        val approvalId = "approval-b"
        val eventKey = "test-key-b"
        val expiresAt = BASE_NOW.plusSeconds(600)
        insertApproval(approvalId, expiresAt = expiresAt)
        insertOutboxRecord(auditIntent(approvalId, eventKey))

        assertThatThrownBy {
            runBlocking {
                store.denyApprovalWithAuditIntent(
                    approvalId = approvalId,
                    expectedVersion = 1,
                    actor = "admin",
                    reason = "reason",
                    auditIntent = auditIntent(approvalId, eventKey),
                )
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("duplicate-event-key")

        assertThat(selectApprovalStatus(approvalId)).isEqualTo("PENDING")
        assertThat(selectApprovalVersion(approvalId)).isEqualTo(1L)
        assertThat(countOutboxRowsForEventKey(eventKey)).isEqualTo(1)
    }
    }

    @Test
    fun `approval version conflict rolls back outbox insert`() { runBlocking {
        val approvalId = "approval-c"
        val auditIntent = auditIntent(approvalId, "test-key-c")
        val expiresAt = BASE_NOW.plusSeconds(600)
        insertApproval(approvalId, expiresAt = expiresAt)

        assertThatThrownBy {
            runBlocking {
                store.denyApprovalWithAuditIntent(
                    approvalId = approvalId,
                    expectedVersion = 0,
                    actor = "admin",
                    reason = "reason",
                    auditIntent = auditIntent,
                )
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("tramai-sovereign-ops-approval-version-conflict")

        assertThat(selectApprovalStatus(approvalId)).isEqualTo("PENDING")
        assertThat(selectApprovalVersion(approvalId)).isEqualTo(1L)
        assertThat(countOutboxRowsForEventKey(auditIntent.eventKey)).isZero()
    }
    }

    @Test
    fun `non pending approval denial is rejected`() { runBlocking {
        val approvalId = "approval-d"
        val auditIntent = auditIntent(approvalId, "test-key-d")
        val expiresAt = BASE_NOW.plusSeconds(600)
        insertApproval(approvalId, status = "APPROVED", expiresAt = expiresAt)

        assertThatThrownBy {
            runBlocking {
                store.denyApprovalWithAuditIntent(
                    approvalId = approvalId,
                    expectedVersion = 1,
                    actor = "admin",
                    reason = "reason",
                    auditIntent = auditIntent,
                )
            }
        }.isInstanceOf(IllegalApprovalTransitionException::class.java)

        assertThat(selectApprovalStatus(approvalId)).isEqualTo("APPROVED")
        assertThat(selectApprovalVersion(approvalId)).isEqualTo(1L)
        assertThat(countOutboxRowsForEventKey(auditIntent.eventKey)).isZero()
    }
    }

    @Test
    fun `payload codec failure rolls back approval mutation`() { runBlocking {
        val approvalId = "approval-e"
        val auditIntent = auditIntent(approvalId, "test-key-e")
        val expiresAt = BASE_NOW.plusSeconds(600)
        insertApproval(approvalId, expiresAt = expiresAt)
        val failingStore = JdbcSovereignOpsApprovalMutationStore(
            dataSource = dataSource,
            payloadCodec = object : JdbcOpsAuditOutboxPayloadCodec {
                override fun encode(plaintext: ByteArray): JdbcEncryptedAuditOutboxPayload {
                    throw IllegalStateException("encode failure")
                }

                override fun decode(envelope: JdbcEncryptedAuditOutboxPayload): ByteArray = envelope.ciphertext
            },
            clock = Clock.fixed(BASE_NOW.plusSeconds(30), ZoneOffset.UTC),
        )

        assertThatThrownBy {
            runBlocking {
                failingStore.denyApprovalWithAuditIntent(
                    approvalId = approvalId,
                    expectedVersion = 1,
                    actor = "admin",
                    reason = "reason",
                    auditIntent = auditIntent,
                )
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("encode failure")

        assertThat(selectApprovalStatus(approvalId)).isEqualTo("PENDING")
        assertThat(selectApprovalVersion(approvalId)).isEqualTo(1L)
        assertThat(countOutboxRowsForEventKey(auditIntent.eventKey)).isZero()
    }
    }

    // ── Expiry guard (P1) ────────────────────────────────────────────

    @Test
    fun `expired pending approval denial is rejected and no outbox is inserted`() { runBlocking {
        val approvalId = "approval-f"
        val auditIntent = auditIntent(approvalId, "test-key-f")
        // expiresAt is in the past relative to clock (BASE_NOW + 30s)
        val expiresAt = BASE_NOW.plusSeconds(10) // expired 20s ago
        insertApproval(approvalId, expiresAt = expiresAt)

        assertThatThrownBy {
            runBlocking {
                store.denyApprovalWithAuditIntent(
                    approvalId = approvalId,
                    expectedVersion = 1,
                    actor = "admin",
                    reason = "reason",
                    auditIntent = auditIntent,
                )
            }
        }.isInstanceOf(IllegalApprovalTransitionException::class.java)
            .hasMessageContaining("expired")

        assertThat(selectApprovalStatus(approvalId)).isEqualTo("PENDING")
        assertThat(selectApprovalVersion(approvalId)).isEqualTo(1L)
        assertThat(countOutboxRowsForEventKey(auditIntent.eventKey)).isZero()
    }
    }

    // ── Metadata integrity (P2) ──────────────────────────────────────

    @Test
    fun `malformed approval metadata fails closed and rolls back outbox insert`() { runBlocking {
        val approvalId = "approval-g"
        val auditIntent = auditIntent(approvalId, "test-key-g")
        // Insert with '{}'::jsonb — missing binding, requestedBy, expiresAt, requestedAt
        insertApprovalWithJsonMetadata(approvalId, """{}""")

        assertThatThrownBy {
            runBlocking {
                store.denyApprovalWithAuditIntent(
                    approvalId = approvalId,
                    expectedVersion = 1,
                    actor = "admin",
                    reason = "reason",
                    auditIntent = auditIntent,
                )
            }
        }.isInstanceOf(MissingKotlinParameterException::class.java)

        assertThat(selectApprovalStatus(approvalId)).isEqualTo("PENDING")
        assertThat(selectApprovalVersion(approvalId)).isEqualTo(1L)
        assertThat(countOutboxRowsForEventKey(auditIntent.eventKey)).isZero()
    }
    }

    // ── Actor/reason validation (P2) ─────────────────────────────────

    @Test
    fun `invalid actor is rejected before mutation and no outbox is inserted`() { runBlocking {
        val approvalId = "approval-h"
        val auditIntent = auditIntent(approvalId, "test-key-h")
        val expiresAt = BASE_NOW.plusSeconds(600)
        insertApproval(approvalId, expiresAt = expiresAt)

        // Space is not allowed by SafeActorIdPolicy
        assertThatThrownBy {
            runBlocking {
                store.denyApprovalWithAuditIntent(
                    approvalId = approvalId,
                    expectedVersion = 1,
                    actor = "bad actor",
                    reason = "reason",
                    auditIntent = auditIntent,
                )
            }
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThat(selectApprovalStatus(approvalId)).isEqualTo("PENDING")
        assertThat(selectApprovalVersion(approvalId)).isEqualTo(1L)
        assertThat(countOutboxRowsForEventKey(auditIntent.eventKey)).isZero()
    }
    }

    @Test
    fun `oversized reason is rejected before mutation and no outbox is inserted`() { runBlocking {
        val approvalId = "approval-i"
        val auditIntent = auditIntent(approvalId, "test-key-i")
        val expiresAt = BASE_NOW.plusSeconds(600)
        insertApproval(approvalId, expiresAt = expiresAt)

        val hugeReason = "x".repeat(5000)

        assertThatThrownBy {
            runBlocking {
                store.denyApprovalWithAuditIntent(
                    approvalId = approvalId,
                    expectedVersion = 1,
                    actor = "admin",
                    reason = hugeReason,
                    auditIntent = auditIntent,
                )
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Comment exceeds maximum length")

        assertThat(selectApprovalStatus(approvalId)).isEqualTo("PENDING")
        assertThat(selectApprovalVersion(approvalId)).isEqualTo(1L)
        assertThat(countOutboxRowsForEventKey(auditIntent.eventKey)).isZero()
    }
    }

    // ── Test infrastructure ──────────────────────────────────────────

    private fun testCodec(): JdbcOpsAuditOutboxPayloadCodec =
        object : JdbcOpsAuditOutboxPayloadCodec {
            private val algorithm = "AES/GCM/NoPadding"
            private val tagLength = 128

            override fun encode(plaintext: ByteArray): JdbcEncryptedAuditOutboxPayload {
                val cipher = Cipher.getInstance(algorithm)
                val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
                val keySpec = SecretKeySpec(testAesKey, "AES")
                val spec = GCMParameterSpec(tagLength, nonce)
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)
                val ciphertext = cipher.doFinal(plaintext)
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(plaintext)
                    .joinToString("") { "%02x".format(it) }
                return JdbcEncryptedAuditOutboxPayload(
                    ciphertext = ciphertext,
                    keyId = "test-key-1",
                    algorithm = algorithm,
                    nonce = nonce,
                    payloadDigest = "sha256:$digest",
                )
            }

            override fun decode(envelope: JdbcEncryptedAuditOutboxPayload): ByteArray {
                val cipher = Cipher.getInstance(algorithm)
                val keySpec = SecretKeySpec(testAesKey, "AES")
                val spec = GCMParameterSpec(tagLength, envelope.nonce)
                cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
                return cipher.doFinal(envelope.ciphertext)
            }
        }

    /**
     * Insert an approval with valid metadata matching [JdbcApprovalStore]'s
     * [ApprovalMetadata] shape.
     */
    private fun insertApproval(
        approvalId: String,
        status: String = "PENDING",
        expiresAt: Instant = BASE_NOW.plusSeconds(600),
    ) {
        val metadata = validApprovalMetadata(expiresAt)
        insertApprovalWithJsonMetadata(approvalId, mapper.writeValueAsString(metadata), status)
    }

    private fun insertApprovalWithJsonMetadata(
        approvalId: String,
        metadataJson: String,
        status: String = "PENDING",
    ) {
        dataSource.connection.use { conn ->
            val sql = """
                INSERT INTO approvals (approval_id, status, created_at, sanitized_metadata, version)
                VALUES (?, ?, ?, ?::jsonb, 1)
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, approvalId)
                stmt.setString(2, status)
                stmt.setTimestamp(3, Timestamp.from(BASE_NOW))
                stmt.setString(4, metadataJson)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Produces metadata matching the shape JdbcApprovalStore writes:
     * { binding: {...}, requestedBy, expiresAt, requestedAt }
     */
    private fun validApprovalMetadata(expiresAt: Instant): Map<String, Any?> = mapOf(
        "binding" to mapOf(
            "workflowRunId" to "run-1",
            "toolName" to "test-tool",
            "argumentsDigest" to "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "policyVersion" to "1.0.0",
            "workflowDigest" to "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "approvalTokenDigest" to "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        ),
        "requestedBy" to "test-user",
        "expiresAt" to expiresAt.toString(),
        "requestedAt" to BASE_NOW.toString(),
    )

    private fun insertOutboxRecord(record: SovereignOpsAuditOutboxRecord) {
        val encrypted = codec.encode(mapper.writeValueAsBytes(record.toPersistedOutbox()))
        dataSource.connection.use { conn ->
            val sql = """
                INSERT INTO audit_outbox (
                    outbox_id, event_key, status, correlation_key_hash,
                    created_at, attempt_count,
                    encrypted_payload, encryption_key_id, encryption_algorithm,
                    encryption_nonce, payload_digest, version
                ) VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, 1)
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, record.outboxId)
                stmt.setString(2, record.eventKey)
                stmt.setString(3, record.status.name)
                stmt.setString(4, record.aggregateIdDigest)
                stmt.setTimestamp(5, Timestamp.from(record.createdAt))
                stmt.setBytes(6, encrypted.ciphertext)
                stmt.setString(7, encrypted.keyId)
                stmt.setString(8, encrypted.algorithm)
                stmt.setBytes(9, encrypted.nonce)
                stmt.setString(10, encrypted.payloadDigest)
                stmt.executeUpdate()
            }
        }
    }

    private fun auditIntent(
        approvalId: String,
        eventKey: String,
    ): SovereignOpsAuditOutboxRecord = SovereignOpsAuditOutboxRecord(
        outboxId = UUID.randomUUID().toString(),
        eventKey = eventKey,
        aggregateIdDigest = sha256Hex(approvalId),
        actor = "admin",
        workflowRunId = null,
        correlationId = null,
        approvalStatus = "PENDING",
        approvalVersion = 1,
        reasonDigest = sha256Hex("reason"),
        reasonLength = 6,
        createdAt = BASE_NOW,
    )

    private fun selectApprovalStatus(approvalId: String): String =
        selectSingleValue(
            "SELECT status FROM approvals WHERE approval_id = ?",
            approvalId,
        )

    private fun selectApprovalVersion(approvalId: String): Long =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT version FROM approvals WHERE approval_id = ?"
            ).use { stmt ->
                stmt.setString(1, approvalId)
                stmt.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getLong(1)
                }
            }
        }

    private fun selectApprovalDecisionType(approvalId: String): String? =
        selectNullableValue(
            "SELECT decision_type FROM approvals WHERE approval_id = ?",
            approvalId,
        )

    private fun selectApprovalDecisionActorHash(approvalId: String): String? =
        selectNullableValue(
            "SELECT decision_actor_hash FROM approvals WHERE approval_id = ?",
            approvalId,
        )

    private fun selectOutboxStatus(outboxId: String): String =
        selectSingleValue(
            "SELECT status FROM audit_outbox WHERE outbox_id = ?",
            outboxId,
        )

    private fun countOutboxRowsForEventKey(eventKey: String): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT count(*) FROM audit_outbox WHERE event_key = ?"
            ).use { stmt ->
                stmt.setString(1, eventKey)
                stmt.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getInt(1)
                }
            }
        }

    private fun selectSingleValue(sql: String, value: String): String =
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, value)
                stmt.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getString(1)
                }
            }
        }

    private fun selectNullableValue(sql: String, value: String): String? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, value)
                stmt.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getString(1)
                }
            }
        }

    private fun truncateTables() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("TRUNCATE TABLE audit_outbox, approvals CASCADE")
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

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return "sha256:${hash.joinToString("") { "%02x".format(it) }}"
    }
}
