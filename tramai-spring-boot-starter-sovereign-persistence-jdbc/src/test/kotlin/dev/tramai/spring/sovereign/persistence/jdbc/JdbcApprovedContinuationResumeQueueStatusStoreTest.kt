package dev.tramai.spring.sovereign.persistence.jdbc

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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcApprovedContinuationResumeQueueStatusStoreTest {

    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"
        private val postgres = PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("resume_queue_status_store_test")
            .withUsername("test")
            .withPassword("test")

        private fun createDataSource() = PGSimpleDataSource().apply {
            setUrl(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
    }

    private lateinit var dataSource: DataSource
    private lateinit var queueStatusStore: JdbcApprovedContinuationResumeQueueStatusStore
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
        queueStatusStore = JdbcApprovedContinuationResumeQueueStatusStore(dataSource)
    }

    @Test
    fun `empty queue returns zero snapshot`() {
        runBlocking {
            val snapshot = queueStatusStore.snapshot(baseNow)

            assertThat(snapshot.eligibleNow).isZero()
            assertThat(snapshot.delayedRetry).isZero()
            assertThat(snapshot.activeLeases).isZero()
            assertThat(snapshot.expiredLeases).isZero()
            assertThat(snapshot.terminalFailures).isZero()
            assertThat(snapshot.oldestEligibleAgeSeconds).isNull()
            assertThat(snapshot.oldestRetryDueInSeconds).isNull()
            assertThat(snapshot.lastErrorCodeCounts).isEmpty()
        }
    }

    @Test
    fun `eligible approved continuation is counted`() {
        runBlocking {
            insertApprovedPending("eligible-001", createdAt = baseNow.minusSeconds(60), approvalExpiresAt = baseNow.plusSeconds(300))
            insertCredential("eligible-001", workflowRunId = "wf-eligible-001", expiresAt = baseNow.plusSeconds(300))

            val snapshot = queueStatusStore.snapshot(baseNow)

            assertThat(snapshot.eligibleNow).isEqualTo(1L)
        }
    }

    @Test
    fun `pending approval is not counted as eligible`() {
        runBlocking {
            insertApproval("pending-001", "PENDING")
            insertContinuation(
                approvalId = "pending-001",
                workflowRunId = "wf-pending-001",
                createdAt = baseNow.minusSeconds(60),
                approvalExpiresAt = baseNow.plusSeconds(300),
            )
            insertCredential("pending-001", workflowRunId = "wf-pending-001", expiresAt = baseNow.plusSeconds(300))

            val snapshot = queueStatusStore.snapshot(baseNow)

            assertThat(snapshot.eligibleNow).isZero()
        }
    }

    @Test
    fun `missing credential is not counted as eligible`() {
        runBlocking {
            insertApprovedPending("no-credential-001", createdAt = baseNow.minusSeconds(60), approvalExpiresAt = baseNow.plusSeconds(300))

            val snapshot = queueStatusStore.snapshot(baseNow)

            assertThat(snapshot.eligibleNow).isZero()
        }
    }

    @Test
    fun `wrong workflow run credential is not counted as eligible`() {
        runBlocking {
            insertApprovedPending("wrong-workflow-001", createdAt = baseNow.minusSeconds(60), approvalExpiresAt = baseNow.plusSeconds(300))
            insertCredential("wrong-workflow-001", workflowRunId = "wf-other", expiresAt = baseNow.plusSeconds(300))

            val snapshot = queueStatusStore.snapshot(baseNow)

            assertThat(snapshot.eligibleNow).isZero()
        }
    }

    @Test
    fun `delayed retry is counted`() {
        runBlocking {
            insertApprovedPending(
                "delayed-001",
                createdAt = baseNow.minusSeconds(60),
                approvalExpiresAt = baseNow.plusSeconds(300),
                resumeNextAttemptAt = baseNow.plusSeconds(90),
            )
            insertCredential("delayed-001", workflowRunId = "wf-delayed-001", expiresAt = baseNow.plusSeconds(300))

            val snapshot = queueStatusStore.snapshot(baseNow)

            assertThat(snapshot.delayedRetry).isEqualTo(1L)
            assertThat(snapshot.oldestRetryDueInSeconds).isEqualTo(90L)
        }
    }

    @Test
    fun `active lease is counted`() {
        runBlocking {
            insertApprovedPending(
                "active-lease-001",
                createdAt = baseNow.minusSeconds(60),
                approvalExpiresAt = baseNow.plusSeconds(300),
                claimedBy = "worker-a",
                claimedAt = baseNow.plusSeconds(120),
            )
            insertCredential("active-lease-001", workflowRunId = "wf-active-lease-001", expiresAt = baseNow.plusSeconds(300))

            val snapshot = queueStatusStore.snapshot(baseNow)

            assertThat(snapshot.activeLeases).isEqualTo(1L)
        }
    }

    @Test
    fun `expired lease is counted`() {
        runBlocking {
            insertApprovedPending(
                "expired-lease-001",
                createdAt = baseNow.minusSeconds(60),
                approvalExpiresAt = baseNow.plusSeconds(300),
                claimedBy = "worker-a",
                claimedAt = baseNow.minusSeconds(10),
            )
            insertCredential("expired-lease-001", workflowRunId = "wf-expired-lease-001", expiresAt = baseNow.plusSeconds(300))

            val snapshot = queueStatusStore.snapshot(baseNow)

            assertThat(snapshot.expiredLeases).isEqualTo(1L)
        }
    }

    @Test
    fun `terminal cancelled with reason is counted`() {
        runBlocking {
            insertApproval("terminal-001", "APPROVED")
            insertContinuation(
                approvalId = "terminal-001",
                workflowRunId = "wf-terminal-001",
                status = "CANCELLED",
                createdAt = baseNow.minusSeconds(60),
                approvalExpiresAt = baseNow.plusSeconds(300),
                resumeLastErrorCode = "IllegalStateException",
            )

            val snapshot = queueStatusStore.snapshot(baseNow)

            assertThat(snapshot.terminalFailures).isEqualTo(1L)
        }
    }

    @Test
    fun `oldest eligible age is computed as positive seconds`() {
        runBlocking {
            insertApprovedPending("older-001", createdAt = baseNow.minusSeconds(120), approvalExpiresAt = baseNow.plusSeconds(60))
            insertCredential("older-001", workflowRunId = "wf-older-001", expiresAt = baseNow.plusSeconds(300))
            insertApprovedPending("newer-001", createdAt = baseNow.minusSeconds(30), approvalExpiresAt = baseNow.plusSeconds(120))
            insertCredential("newer-001", workflowRunId = "wf-newer-001", expiresAt = baseNow.plusSeconds(300))

            val snapshot = queueStatusStore.snapshot(baseNow)

            assertThat(snapshot.oldestEligibleAgeSeconds).isEqualTo(120L)
        }
    }

    @Test
    fun `error code counts include retryable pending and terminal cancelled failures`() {
        runBlocking {
            // pending retryable — has resume_last_error_code but still PENDING
            insertApproval("pending-retryable-a", "APPROVED")
            insertContinuation(
                approvalId = "pending-retryable-a",
                workflowRunId = "wf-pending-retryable-a",
                status = "PENDING",
                createdAt = baseNow.minusSeconds(60),
                approvalExpiresAt = baseNow.plusSeconds(300),
                resumeLastErrorCode = "TimeoutException",
            )

            // terminal cancelled — CANCELLED with same error code
            insertApproval("terminal-retryable-a", "APPROVED")
            insertContinuation(
                approvalId = "terminal-retryable-a",
                workflowRunId = "wf-terminal-retryable-a",
                status = "CANCELLED",
                createdAt = baseNow.minusSeconds(60),
                approvalExpiresAt = baseNow.plusSeconds(300),
                resumeLastErrorCode = "TimeoutException",
            )

            val snapshot = queueStatusStore.snapshot(baseNow)

            assertThat(snapshot.lastErrorCodeCounts)
                .containsEntry("TimeoutException", 2L)
        }
    }

    @Test
    fun `error code counts are grouped safely`() {
        runBlocking {
            insertApproval("terminal-a", "APPROVED")
            insertContinuation(
                approvalId = "terminal-a",
                workflowRunId = "wf-terminal-a",
                status = "CANCELLED",
                createdAt = baseNow.minusSeconds(60),
                approvalExpiresAt = baseNow.plusSeconds(300),
                resumeLastErrorCode = "IllegalStateException",
            )
            insertApproval("terminal-b", "APPROVED")
            insertContinuation(
                approvalId = "terminal-b",
                workflowRunId = "wf-terminal-b",
                status = "CANCELLED",
                createdAt = baseNow.minusSeconds(60),
                approvalExpiresAt = baseNow.plusSeconds(300),
                resumeLastErrorCode = "IllegalStateException",
            )
            insertApproval("terminal-c", "APPROVED")
            insertContinuation(
                approvalId = "terminal-c",
                workflowRunId = "wf-terminal-c",
                status = "CANCELLED",
                createdAt = baseNow.minusSeconds(60),
                approvalExpiresAt = baseNow.plusSeconds(300),
                resumeLastErrorCode = "TimeoutException",
            )

            val snapshot = queueStatusStore.snapshot(baseNow)

            assertThat(snapshot.lastErrorCodeCounts).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "IllegalStateException" to 2L,
                    "TimeoutException" to 1L,
                ),
            )
        }
    }

    private fun insertApprovedPending(
        approvalId: String,
        createdAt: Instant,
        approvalExpiresAt: Instant,
        claimedBy: String? = null,
        claimedAt: Instant? = null,
        resumeNextAttemptAt: Instant? = null,
    ) {
        insertApproval(approvalId, "APPROVED")
        insertContinuation(
            approvalId = approvalId,
            workflowRunId = "wf-$approvalId",
            createdAt = createdAt,
            approvalExpiresAt = approvalExpiresAt,
            claimedBy = claimedBy,
            claimedAt = claimedAt,
            resumeNextAttemptAt = resumeNextAttemptAt,
        )
    }

    private fun insertApproval(approvalId: String, status: String) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO approvals (approval_id, status, created_at, sanitized_metadata, version)
                VALUES (?, ?, ?, '{}'::jsonb, 0)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, approvalId)
                stmt.setString(2, status)
                stmt.setObject(3, baseNow.atOffset(ZoneOffset.UTC))
                stmt.executeUpdate()
            }
        }
    }

    private fun insertContinuation(
        approvalId: String,
        workflowRunId: String,
        status: String = "PENDING",
        createdAt: Instant,
        approvalExpiresAt: Instant,
        claimedBy: String? = null,
        claimedAt: Instant? = null,
        resumeNextAttemptAt: Instant? = null,
        resumeLastErrorCode: String? = null,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO approval_continuations
                    (approval_id, status, workflow_run_id, correlation_id, tool_call_id, tool_name,
                     arguments_digest, policy_version, workflow_digest, created_at, approval_expires_at,
                     claimed_by, claimed_at, resume_next_attempt_at, resume_last_error_code, version)
                VALUES (?, ?, ?, ?, ?, ?,
                        'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        'v1', 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                        ?, ?, ?, ?, ?, ?, 0)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, approvalId)
                stmt.setString(2, status)
                stmt.setString(3, workflowRunId)
                stmt.setString(4, "corr-$approvalId")
                stmt.setString(5, "tc-$approvalId")
                stmt.setString(6, "tool-$approvalId")
                stmt.setObject(7, createdAt.atOffset(ZoneOffset.UTC))
                stmt.setObject(8, approvalExpiresAt.atOffset(ZoneOffset.UTC))
                stmt.setString(9, claimedBy)
                stmt.setObject(10, claimedAt?.atOffset(ZoneOffset.UTC))
                stmt.setObject(11, resumeNextAttemptAt?.atOffset(ZoneOffset.UTC))
                stmt.setString(12, resumeLastErrorCode)
                stmt.executeUpdate()
            }
        }
    }

    private fun insertCredential(
        approvalId: String,
        workflowRunId: String,
        expiresAt: Instant,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO tramai_approval_resume_credentials
                    (approval_id, workflow_run_id, encrypted_resume_token,
                     encryption_key_id, encryption_algorithm, encryption_nonce,
                     payload_digest, created_at, expires_at, version)
                VALUES (?, ?,
                        decode('74657374', 'hex'),
                        'test-key', 'AES-256-GCM', decode('000000000000000000000000', 'hex'),
                        'sha256:0000000000000000000000000000000000000000000000000000000000000000',
                        ?, ?, 0)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, approvalId)
                stmt.setString(2, workflowRunId)
                stmt.setObject(3, baseNow.atOffset(ZoneOffset.UTC))
                stmt.setObject(4, expiresAt.atOffset(ZoneOffset.UTC))
                stmt.executeUpdate()
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
                    "tramai/persistence/jdbc/postgres/V7__approval_continuations_resume_retry.sql",
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
