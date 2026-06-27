package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.core.approval.gateway.ApprovalId
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Test coverage for [JdbcApprovedContinuationResumeQueue].
 *
 * Verifies claiming semantics, concurrency safety (SKIP LOCKED), batch limits,
 * and temporal filtering.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcApprovedContinuationResumeQueueTest {

    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"
        private val postgres = PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("resume_queue_test")
            .withUsername("test")
            .withPassword("test")

        private fun createDataSource() = PGSimpleDataSource().apply {
            setUrl(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
    }

    private lateinit var dataSource: DataSource
    private lateinit var queue: JdbcApprovedContinuationResumeQueue
    private val baseNow: Instant = Instant.parse("2026-06-01T12:00:00Z")

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
        queue = JdbcApprovedContinuationResumeQueue(dataSource)
    }

    @Test
    fun `claim finds approved pending item with credential`() {
        runBlocking {
            insertApprovedPending("item-001", expiryMinutes = 5)
            insertCredential("item-001")

            val items = queue.claimApprovedPending(
                workerId = "worker-1",
                limit = 10,
                leaseUntil = Instant.now().plusSeconds(120),
            )

            assertThat(items).hasSize(1)
            assertThat(items[0].approvalId.value).isEqualTo("item-001")
        }
    }

    @Test
    fun `batch limit is respected`() {
        runBlocking {
            for (i in 1..5) {
                val id = "batch-item-%03d".format(i)
                insertApprovedPending(id, expiryMinutes = 5)
                insertCredential(id)
            }

            val items = queue.claimApprovedPending(
                workerId = "worker-1",
                limit = 3,
                leaseUntil = Instant.now().plusSeconds(120),
            )

            assertThat(items).hasSize(3)
        }
    }

    @Test
    fun `oldest expiry first ordering`() {
        runBlocking {
            insertApprovedPending("oldest", expiryMinutes = 1)
            insertCredential("oldest")
            insertApprovedPending("middle", expiryMinutes = 2)
            insertCredential("middle")
            insertApprovedPending("newest", expiryMinutes = 5)
            insertCredential("newest")

            val items = queue.claimApprovedPending(
                workerId = "worker-1",
                limit = 10,
                leaseUntil = Instant.now().plusSeconds(120),
            )

            assertThat(items).hasSize(3)
            assertThat(items[0].approvalId.value).isEqualTo("oldest")
            assertThat(items[1].approvalId.value).isEqualTo("middle")
            assertThat(items[2].approvalId.value).isEqualTo("newest")
        }
    }

    @Test
    fun `two workers claim same item only one gets it`() {
        runBlocking {
            insertApprovedPending("concurrent-001", expiryMinutes = 5)
            insertCredential("concurrent-001")

            val items1 = queue.claimApprovedPending(
                workerId = "worker-a",
                limit = 10,
                leaseUntil = Instant.now().plusSeconds(120),
            )
            val items2 = queue.claimApprovedPending(
                workerId = "worker-b",
                limit = 10,
                leaseUntil = Instant.now().plusSeconds(120),
            )

            assertThat(items1).hasSize(1)
            assertThat(items2).isEmpty()
        }
    }

    @Test
    fun `expired continuation is not claimed`() {
        runBlocking {
            // Insert with past expiry but valid created_at
            dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("""
                        INSERT INTO approvals (approval_id, status, created_at, sanitized_metadata, version)
                        VALUES ('expired-001', 'APPROVED', now(), '{}'::jsonb, 0)
                    """.trimIndent())
                    stmt.execute("""
                        INSERT INTO approval_continuations
                            (approval_id, status, workflow_run_id, correlation_id, tool_call_id, tool_name,
                             arguments_digest, policy_version, workflow_digest,
                             created_at, approval_expires_at, version)
                        VALUES ('expired-001', 'PENDING', 'wf-expired-001', 'corr-expired-001', 'tc-expired-001', 'tool-expired-001',
                                'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                                'v1', 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                                now() - interval '10 minutes', now() - interval '5 minutes', 0)
                    """.trimIndent())
                }
            }
            insertCredential("expired-001")

            val items = queue.claimApprovedPending(
                workerId = "worker-1",
                limit = 10,
                leaseUntil = Instant.now().plusSeconds(120),
            )

            assertThat(items).isEmpty()
        }
    }

    @Test
    fun `pending approval is not claimed`() {
        runBlocking {
            dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("""
                        INSERT INTO approvals (approval_id, status, created_at, sanitized_metadata, version)
                        VALUES ('pending-001', 'PENDING', now(), '{}'::jsonb, 0)
                    """.trimIndent())
                    stmt.execute("""
                        INSERT INTO approval_continuations
                            (approval_id, status, workflow_run_id, correlation_id, tool_call_id, tool_name,
                             arguments_digest, policy_version, workflow_digest,
                             created_at, approval_expires_at, version)
                        VALUES ('pending-001', 'PENDING', 'wf-pending-001', 'corr-001', 'tc-001', 'tool-001',
                                'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                                'v1', 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                                now(), now() + interval '5 minutes', 0)
                    """.trimIndent())
                }
            }
            insertCredential("pending-001")

            val items = queue.claimApprovedPending(
                workerId = "worker-1",
                limit = 10,
                leaseUntil = Instant.now().plusSeconds(120),
            )

            assertThat(items).isEmpty()
        }
    }

    @Test
    fun `markResumeSucceeded updates continuation to COMPLETED`() {
        runBlocking {
            insertApprovedPending("succeed-001", expiryMinutes = 5)
            insertCredential("succeed-001")

            val items = queue.claimApprovedPending(
                workerId = "worker-1",
                limit = 10,
                leaseUntil = Instant.now().plusSeconds(120),
            )
            assertThat(items).hasSize(1)

            queue.markResumeSucceeded(ApprovalId("succeed-001"), "worker-1")

            val status = selectValue(
                "SELECT status FROM approval_continuations WHERE approval_id = ?",
                "succeed-001",
            )
            assertThat(status).isEqualTo("COMPLETED")
        }
    }

    @Test
    fun `missing credential does not return item`() {
        runBlocking {
            insertApprovedPending("no-cred-001", expiryMinutes = 5)
            // No credential inserted

            val items = queue.claimApprovedPending(
                workerId = "worker-1",
                limit = 10,
                leaseUntil = Instant.now().plusSeconds(120),
            )

            assertThat(items).isEmpty()
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun insertApprovedPending(approvalId: String, expiryMinutes: Int) {
        val operator = if (expiryMinutes >= 0) "+" else "-"
        val absMinutes = kotlin.math.abs(expiryMinutes)
        val interval = "interval '$absMinutes minutes'"
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    INSERT INTO approvals (approval_id, status, created_at, sanitized_metadata, version)
                    VALUES ('$approvalId', 'APPROVED', now(), '{}'::jsonb, 0)
                """.trimIndent())
                stmt.execute("""
                    INSERT INTO approval_continuations
                        (approval_id, status, workflow_run_id, correlation_id, tool_call_id, tool_name,
                         arguments_digest, policy_version, workflow_digest,
                         created_at, approval_expires_at, version)
                    VALUES ('$approvalId', 'PENDING', 'wf-$approvalId', 'corr-$approvalId', 'tc-$approvalId', 'tool-$approvalId',
                            'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                            'v1', 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                            now(), now() $operator $interval, 0)
                """.trimIndent())
            }
        }
    }

    private fun insertCredential(approvalId: String) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    INSERT INTO tramai_approval_resume_credentials
                        (approval_id, workflow_run_id, encrypted_resume_token,
                         encryption_key_id, encryption_algorithm, encryption_nonce,
                         payload_digest, created_at, expires_at, version)
                    VALUES ('$approvalId', 'wf-$approvalId',
                            decode('74657374', 'hex'),
                            'test-key', 'AES-256-GCM', decode('000000000000000000000000', 'hex'),
                            'sha256:0000000000000000000000000000000000000000000000000000000000000000',
                            now(), now() + interval '5 minutes', 0)
                """.trimIndent())
            }
        }
    }

    private fun selectValue(sql: String, value: String): String? =
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
                stmt.execute("TRUNCATE TABLE approval_continuations, approvals, tramai_approval_resume_credentials CASCADE")
            }
        }
    }

    private fun runMigrations() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                listOf(
                    "tramai/persistence/jdbc/postgres/V1__sovereign_persistence.sql",
                    "tramai/persistence/jdbc/postgres/V2__approval_continuations.sql",
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
