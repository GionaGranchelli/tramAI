package dev.tramai.spring.sovereign.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

/**
 * 0.7.6f slice 1 — durable actor provenance on the approval decision (V10).
 *
 * Two properties are asserted, and the second matters more than the first: a decision carrying verified
 * attribution persists the issuer and the identity digest, while a decision carrying none leaves both
 * columns NULL. There is no third behaviour — a missing issuer is never inferred from the request, the
 * subject digest, or a neighbouring row.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcSovereignOpsApprovalProvenanceTest {
    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"
        private val MIGRATIONS =
            listOf(
                "V1__sovereign_persistence.sql",
                "V4__audit_outbox_hardening.sql",
                "V10__approval_actor_provenance.sql",
            )
        private val BASE_NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")
        private val postgres =
            PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("sovereign_provenance")
                .withUsername("test")
                .withPassword("test")
    }

    private lateinit var dataSource: DataSource
    private lateinit var store: JdbcSovereignOpsApprovalMutationStore
    private val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private val mapper: ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @BeforeAll
    fun startPostgres() {
        postgres.start()
        dataSource =
            PGSimpleDataSource().apply {
                setUrl(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
        applyMigrations()
    }

    @AfterAll
    fun stopPostgres() {
        postgres.stop()
    }

    @BeforeEach
    fun setUp() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("TRUNCATE TABLE audit_outbox, approvals CASCADE") }
        }
        store =
            JdbcSovereignOpsApprovalMutationStore(
                dataSource = dataSource,
                payloadCodec = testCodec(),
                clock = Clock.fixed(BASE_NOW.plusSeconds(30), ZoneOffset.UTC),
            )
    }

    @Test
    fun `a decision with verified attribution persists issuer and identity digest`() =
        runBlocking<Unit> {
            insertApproval("approval-provenance")
            val identityDigest = "0123456789abcdef".repeat(4)

            store.denyApprovalWithAuditIntent(
                approvalId = "approval-provenance",
                expectedVersion = 1,
                actor = "admin",
                reason = "reason",
                auditIntent =
                    intent("approval-provenance", "key-provenance").copy(
                        actorIssuer = "https://issuer.example",
                        actorIdentityDigest = identityDigest,
                        previousApprovalStatus = "PENDING",
                        previousApprovalVersion = 1,
                    ),
            )

            assertThat(selectNullable("decision_actor_issuer", "approval-provenance"))
                .isEqualTo("https://issuer.example")
            assertThat(selectNullable("decision_actor_identity_digest", "approval-provenance"))
                .isEqualTo(identityDigest)
            // The pre-0.7.6f digest keeps its released form and meaning.
            assertThat(selectNullable("decision_actor_hash", "approval-provenance"))
                .isEqualTo(sha256Prefixed("admin"))
        }

    @Test
    fun `a decision without attribution leaves both columns null and invents no issuer`() =
        runBlocking<Unit> {
            insertApproval("approval-no-provenance")

            store.denyApprovalWithAuditIntent(
                approvalId = "approval-no-provenance",
                expectedVersion = 1,
                actor = "admin",
                reason = "reason",
                auditIntent = intent("approval-no-provenance", "key-no-provenance"),
            )

            assertThat(selectNullable("decision_actor_issuer", "approval-no-provenance")).isNull()
            assertThat(selectNullable("decision_actor_identity_digest", "approval-no-provenance")).isNull()
            assertThat(selectNullable("decision_actor_hash", "approval-no-provenance"))
                .isEqualTo(sha256Prefixed("admin"))
        }

    @Test
    fun `a decision recorded without attribution still works on a pre-V10 schema`() =
        runBlocking<Unit> {
            // Provenance-free decisions must not depend on the V10 columns: dropping them proves the write
            // path does not reference what it has no value for.
            dataSource.connection.use { conn ->
                conn.createStatement().use {
                    it.execute("ALTER TABLE approvals DROP COLUMN decision_actor_issuer")
                    it.execute("ALTER TABLE approvals DROP COLUMN decision_actor_identity_digest")
                }
            }
            try {
                insertApproval("approval-pre-v10")

                store.denyApprovalWithAuditIntent(
                    approvalId = "approval-pre-v10",
                    expectedVersion = 1,
                    actor = "admin",
                    reason = "reason",
                    auditIntent = intent("approval-pre-v10", "key-pre-v10"),
                )

                assertThat(selectNullable("decision_actor_hash", "approval-pre-v10"))
                    .isEqualTo(sha256Prefixed("admin"))
            } finally {
                dataSource.connection.use { conn ->
                    conn.createStatement().use {
                        it.execute("ALTER TABLE approvals ADD COLUMN decision_actor_issuer TEXT")
                        it.execute("ALTER TABLE approvals ADD COLUMN decision_actor_identity_digest TEXT")
                    }
                }
            }
        }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun applyMigrations() {
        dataSource.connection.use { conn ->
            MIGRATIONS.forEach { file ->
                val sql =
                    javaClass.classLoader
                        .getResource("tramai/persistence/jdbc/postgres/$file")
                        ?.readText()
                        ?: error("migration $file not found")
                conn.createStatement().use { it.execute(sql) }
            }
        }
    }

    private fun insertApproval(approvalId: String) {
        val metadata =
            mapOf(
                "binding" to
                    mapOf(
                        "workflowRunId" to "run-1",
                        "toolName" to "test-tool",
                        "argumentsDigest" to sha256Prefixed("arguments"),
                        "policyVersion" to "1.0.0",
                        "workflowDigest" to sha256Prefixed("workflow"),
                        "approvalTokenDigest" to sha256Prefixed("token"),
                    ),
                "requestedBy" to "test-user",
                "expiresAt" to BASE_NOW.plusSeconds(600).toString(),
                "requestedAt" to BASE_NOW.toString(),
            )
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO approvals (approval_id, status, created_at, sanitized_metadata, version)
                    VALUES (?, 'PENDING', ?, ?::jsonb, 1)
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, approvalId)
                    stmt.setTimestamp(2, Timestamp.from(BASE_NOW))
                    stmt.setString(3, mapper.writeValueAsString(metadata))
                    stmt.executeUpdate()
                }
        }
    }

    private fun intent(
        approvalId: String,
        eventKey: String,
    ): SovereignOpsAuditOutboxRecord =
        SovereignOpsAuditOutboxRecord(
            outboxId = UUID.randomUUID().toString(),
            eventKey = eventKey,
            aggregateIdDigest = sha256Prefixed(approvalId),
            actor = "admin",
            workflowRunId = null,
            correlationId = null,
            approvalStatus = "PENDING",
            approvalVersion = 1,
            reasonDigest = sha256Prefixed("reason"),
            reasonLength = 6,
            createdAt = BASE_NOW,
        )

    private fun selectNullable(
        column: String,
        approvalId: String,
    ): String? =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT $column FROM approvals WHERE approval_id = ?").use { stmt ->
                stmt.setString(1, approvalId)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "approval $approvalId not found" }
                    rs.getString(1)
                }
            }
        }

    private fun sha256Prefixed(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun testCodec(): JdbcOpsAuditOutboxPayloadCodec =
        object : JdbcOpsAuditOutboxPayloadCodec {
            private val algorithm = "AES/GCM/NoPadding"

            override fun encode(plaintext: ByteArray): JdbcEncryptedAuditOutboxPayload {
                val cipher = Cipher.getInstance(algorithm)
                val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
                return JdbcEncryptedAuditOutboxPayload(
                    ciphertext = cipher.doFinal(plaintext),
                    keyId = "test-key-1",
                    algorithm = algorithm,
                    nonce = nonce,
                    payloadDigest =
                        MessageDigest
                            .getInstance("SHA-256")
                            .digest(plaintext)
                            .joinToString("") { "%02x".format(it) },
                )
            }

            override fun decode(envelope: JdbcEncryptedAuditOutboxPayload): ByteArray = envelope.ciphertext
        }
}
