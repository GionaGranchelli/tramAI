package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApprovalResumeCredentialRecord
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.core.approval.gateway.SealedResumeToken
import dev.tramai.core.approval.gateway.WorkflowRunId
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
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

/**
 * Test coverage for [JdbcApprovalResumeCredentialStore].
 *
 * Verifies encryption round-trip, failure modes, SPI contract, and
 * security properties (no plaintext leak, no token in toString).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcApprovalResumeCredentialStoreTest {

    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"
        private val postgres = PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("credential_store_test")
            .withUsername("test")
            .withPassword("test")

        private fun createDataSource() = PGSimpleDataSource().apply {
            setUrl(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
    }

    private val aes256Key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val secretKey: SecretKey = SecretKeySpec(aes256Key, "AES")

    private lateinit var dataSource: PGSimpleDataSource
    private lateinit var store: JdbcApprovalResumeCredentialStore
    private lateinit var altKeyStore: JdbcApprovalResumeCredentialStore

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
        truncateTable()
        store = JdbcApprovalResumeCredentialStore(
            dataSourceProvider = { dataSource.connection },
            key = secretKey,
            keyId = "test-key-1",
        )
        val altKeyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val altKey: SecretKey = SecretKeySpec(altKeyBytes, "AES")
        altKeyStore = JdbcApprovalResumeCredentialStore(
            dataSourceProvider = { dataSource.connection },
            key = altKey,
            keyId = "test-key-2",
        )
    }

    @Test
    fun `create and get round trips sealed resume token`() {
        runBlocking {
            val approvalId = ApprovalId("roundtrip-001")
            val token = ResumeToken("super-secret-resume-token-abc123")
            val record = record(approvalId, token)

            store.create(record)
            val retrieved = store.get(approvalId)

            assertThat(retrieved).isNotNull
            assertThat(retrieved!!.resumeToken.revealForInternalResume())
                .isEqualTo(token)
            assertThat(retrieved.approvalId).isEqualTo(approvalId)
            assertThat(retrieved.workflowRunId).isEqualTo(WorkflowRunId("wf-roundtrip-001"))
            assertThat(retrieved.version).isEqualTo(1L)
        }
    }

    @Test
    fun `stored ciphertext does not contain plaintext token`() {
        runBlocking {
            val approvalId = ApprovalId("noleak-001")
            val tokenValue = "highly-sensitive-token-xyz"
            val token = ResumeToken(tokenValue)
            val record = record(approvalId, token)

            store.create(record)

            dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT encrypted_resume_token::text, encryption_key_id, " +
                            "encryption_algorithm, encode(encryption_nonce, 'hex'), " +
                            "payload_digest FROM tramai_approval_resume_credentials " +
                            "WHERE approval_id = 'noleak-001'",
                    ).use { rs ->
                        assertThat(rs.next()).isTrue
                        val dbCiphertext = rs.getString(1)
                        assertThat(dbCiphertext).doesNotContain(tokenValue)
                        val dbKeyId = rs.getString(2)
                        assertThat(dbKeyId).isEqualTo("test-key-1")
                        val dbAlgorithm = rs.getString(3)
                        assertThat(dbAlgorithm).isEqualTo("AES-256-GCM")
                    }
                }
            }
        }
    }

    @Test
    fun `toString never leaks token`() {
        val token = ResumeToken("leak-check-token-999")
        val sealed = SealedResumeToken.seal(token)

        assertThat(sealed.toString()).isEqualTo("[REDACTED]")
        assertThat(sealed.toString()).doesNotContain("leak-check-token")
        assertThat(sealed.toString()).doesNotContain("token")
    }

    @Test
    fun `digest mismatch fails closed`() {
        runBlocking {
            val approvalId = ApprovalId("digestfail-001")
            val record = record(approvalId, ResumeToken("some-token"))

            store.create(record)

            dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        "UPDATE tramai_approval_resume_credentials " +
                            "SET payload_digest = 'sha256:0000000000000000000000000000000000000000000000000000000000000000' " +
                            "WHERE approval_id = 'digestfail-001'",
                    )
                }
            }

            assertThatThrownBy {
                runBlocking { store.get(approvalId) }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("digest-mismatch")
        }
    }

    @Test
    fun `wrong key fails closed`() {
        runBlocking {
            val approvalId = ApprovalId("wrongkey-001")
            val record = record(approvalId, ResumeToken("encrypted-with-key-A"))

            store.create(record)

            assertThatThrownBy {
                runBlocking { altKeyStore.get(approvalId) }
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("decryption-failed")
        }
    }

    @Test
    fun `duplicate approval id maps to IllegalStateException`() {
        runBlocking {
            val approvalId = ApprovalId("duplicate-001")
            val record1 = record(approvalId, ResumeToken("first-token"))

            store.create(record1)

            val record2 = record(approvalId, ResumeToken("second-token"))

            assertThatThrownBy {
                runBlocking { store.create(record2) }
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("credential-already-exists")
        }
    }

    @Test
    fun `delete is idempotent`() {
        runBlocking {
            val approvalId = ApprovalId("idempotent-001")

            store.delete(approvalId)

            val record = record(approvalId, ResumeToken("delete-me"))
            store.create(record)
            assertThat(store.get(approvalId)).isNotNull

            store.delete(approvalId)
            assertThat(store.get(approvalId)).isNull()

            store.delete(approvalId)
        }
    }

    @Test
    fun `get returns null for missing credential`() {
        runBlocking {
            val result = store.get(ApprovalId("does-not-exist-999"))
            assertThat(result).isNull()
        }
    }

    @Test
    fun `missing inbox metadata still maps safely with null fields`() {
        runBlocking {
            val approvalId = ApprovalId("no-inbox-001")
            val record = record(approvalId, ResumeToken("plain-token"))
            store.create(record)

            val retrieved = store.get(approvalId)
            assertThat(retrieved).isNotNull
            assertThat(retrieved!!.resumeToken.revealForInternalResume().value)
                .isEqualTo("plain-token")
        }
    }

    private fun record(
        approvalId: ApprovalId,
        token: ResumeToken,
    ): ApprovalResumeCredentialRecord {
        val now = Instant.parse("2026-06-01T12:00:00Z")
        return ApprovalResumeCredentialRecord(
            approvalId = approvalId,
            workflowRunId = WorkflowRunId("wf-${approvalId.value}"),
            resumeToken = SealedResumeToken.seal(token),
            createdAt = now,
            expiresAt = now.plusSeconds(300),
            version = 1L,
        )
    }

    private fun truncateTable() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("TRUNCATE TABLE tramai_approval_resume_credentials CASCADE")
            }
        }
    }

    private fun runMigrations() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                listOf(
                    "tramai/persistence/jdbc/postgres/V6__approval_resume_credential_custody.sql",
                ).forEach { resource ->
                    val sql = javaClass.classLoader
                        .getResourceAsStream(resource)
                        ?.bufferedReader()
                        ?.readText()
                        ?: error("Migration not found: $resource")
                    stmt.execute(sql)
                }
            }
        }
    }
}
